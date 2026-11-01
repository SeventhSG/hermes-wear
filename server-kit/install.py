#!/usr/bin/env python3
"""Hermes Companion server kit installer (stdlib only, Python 3.8+).

Pick the modules you want; the installer does the rest, safely and repeatably.

    python3 install.py                      # interactive menu on a terminal
    python3 install.py list                 # every module, what it needs, what is installed
    python3 install.py --preset recommended --yes
    python3 install.py --modules hub,skills,slash --yes
    python3 install.py --modules hub --skills deploy,travel --yes
    python3 install.py status | verify | check | uninstall [--modules ...]

Safe by default: --dry-run shows every change first, files you edited are backed up or kept, running
services restart only when their own files changed, and nothing outside the kit's paths is touched.
"""
import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import socket
import stat
import subprocess
import sys
import time
import urllib.request

KIT = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(KIT, "modules.json")
STATE_NAME = "kit-state.json"
UNIT_TOKEN = re.compile(r"@([A-Z][A-Z0-9_]*)@")
PATH_TOKEN = re.compile(r"\{([a-z][a-z0-9_]*)\}")


class KitError(Exception):
    """A problem the user can fix; the message says how."""


def load_manifest(path=MANIFEST):
    with open(path, encoding="utf-8") as f:
        m = json.load(f)
    by_id = {}
    for mod in m["modules"]:
        if mod["id"] in by_id:
            raise KitError("duplicate module id " + mod["id"])
        by_id[mod["id"]] = mod
    m["by_id"] = by_id
    return m


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


# ── environment: where things go ──────────────────────────────────────────────────────────────────

class Ctx:
    """Resolved paths and values for one run. `prefix` stages everything under a directory (like DESTDIR)."""

    def __init__(self, manifest, scope=None, prefix="", hermes_home=None, service_user=None, python=None,
                 port=8791, stt_device="cpu", with_proxy=False, home=None):
        euid = os.geteuid() if hasattr(os, "geteuid") else 1
        self.scope = scope or ("system" if euid == 0 else "user")
        if self.scope == "system" and euid != 0 and not prefix:
            raise KitError("Installing for the whole system needs root. Run with sudo, or add --user to install for your own account.")
        self.prefix = prefix.rstrip("/") if prefix else ""
        sudo_user = os.environ.get("SUDO_USER", "")
        self.user = service_user or (sudo_user if euid == 0 and sudo_user and sudo_user != "root" else _current_user())
        self.home = home or (os.environ.get("HOME") if self.user == _current_user() and os.environ.get("HOME") else _home_of(self.user))
        env_home = os.environ.get("HERMES_HOME", "")
        self.hermes_home = os.path.expanduser(hermes_home) if hermes_home else (env_home if env_home and not (euid == 0 and sudo_user) else os.path.join(self.home, ".hermes"))
        paths = manifest["paths"][self.scope]
        self.v = {k: os.path.expanduser(v.replace("~", self.home, 1)) if v.startswith("~") else v for k, v in paths.items()}
        self.v.update({
            "home": self.home, "user": self.user, "hermes_home": self.hermes_home,
            "skills_dir": os.path.join(self.hermes_home, "skills"),
            "port": str(port),
            "stt_port": "10301" if with_proxy else "10300",
            "tts_port": "10200",
            "python": python or ("/usr/bin/python3" if os.path.exists("/usr/bin/python3") else sys.executable),
            "hermes_bin": shutil.which("hermes") or "hermes",
            "codex_bin": shutil.which("codex") or "/usr/bin/codex",
        })
        extra = [self.v["bin_dir"], os.path.join(self.home, ".local/bin"), "/usr/local/bin", "/usr/bin", "/bin"]
        self.v["path_env"] = ":".join(dict.fromkeys(extra))
        self.stt_device = stt_device
        self.systemd_user = self.scope == "user"

    # staging
    def host(self, logical):
        """Where a logical absolute path really lives (under the prefix when staging)."""
        return self.prefix + logical if self.prefix else logical

    def fmt(self, s):
        def sub(m):
            k = m.group(1)
            if k not in self.v:
                raise KitError("unknown placeholder {%s}" % k)
            return self.v[k]
        return PATH_TOKEN.sub(sub, s)

    def unit_values(self):
        user_line = "User=" + self.user if self.scope == "system" and self.user != "root" else ""
        return {
            "HUB_DIR": self.v["hub_dir"], "PYTHON": self.v["python"], "HOME": self.home, "HERMES_HOME": self.hermes_home,
            "PATH": self.v["path_env"], "HERMES_BIN": self.v["hermes_bin"], "CODEX_BIN": self.v["codex_bin"],
            "BRIDGE_DIR": self.v["bridge_dir"], "CLAUDE_BRIDGE_DIR": self.v["claude_bridge_dir"],
            "VOICE_DIR": self.v["voice_dir"], "STT_PORT": self.v["stt_port"], "TTS_PORT": self.v["tts_port"],
            "WANTED_BY": "default.target" if self.scope == "user" else "multi-user.target",
            "SERVICE_USER_LINE": user_line, "LD_LIBRARY_LINE": self.ld_line(),
        }

    def ld_line(self):
        if self.stt_device != "gpu":
            return ""
        site = _venv_site_packages(os.path.join(self.v["voice_dir"], "venv-stt"), self.prefix)
        libs = []
        if site:
            nv = os.path.join(site, "nvidia")
            if os.path.isdir(nv):
                for name in sorted(os.listdir(nv)):
                    lib = os.path.join(nv, name, "lib")
                    if os.path.isdir(lib):
                        libs.append(lib[len(self.prefix):] if self.prefix and lib.startswith(self.prefix) else lib)
        return "Environment=LD_LIBRARY_PATH=" + ":".join(libs) if libs else ""

    def render_unit(self, text):
        vals = self.unit_values()
        lines = []
        for line in text.split("\n"):
            m = UNIT_TOKEN.fullmatch(line.strip()) if line.strip() else None
            if m and m.group(1) in vals and vals[m.group(1)] == "":
                continue  # an optional line (User=, LD_LIBRARY_PATH=) that has no value here
            lines.append(line)

        def sub(m):
            if m.group(1) not in vals:
                raise KitError("unit template uses unknown @%s@" % m.group(1))
            return vals[m.group(1)]
        return UNIT_TOKEN.sub(sub, "\n".join(lines))


def _current_user():
    try:
        import pwd
        return pwd.getpwuid(os.getuid()).pw_name
    except Exception:
        return os.environ.get("USER", "root")


def _home_of(user):
    try:
        import pwd
        return pwd.getpwnam(user).pw_dir
    except Exception:
        return os.path.expanduser("~")


def _venv_site_packages(venv, prefix=""):
    base = prefix + venv if prefix else venv
    lib = os.path.join(base, "lib")
    if not os.path.isdir(lib):
        return ""
    for name in os.listdir(lib):
        sp = os.path.join(lib, name, "site-packages")
        if name.startswith("python") and os.path.isdir(sp):
            return sp
    return ""


# ── env files ─────────────────────────────────────────────────────────────────────────────────────

def apply_env(text, values):
    """Set KEY=VALUE in env-file text: replace a live line, else un-comment the first `# KEY=` line, else append."""
    lines = text.split("\n")
    for key, value in values.items():
        live = re.compile(r"^\s*%s=" % re.escape(key))
        commented = re.compile(r"^\s*#\s*%s=" % re.escape(key))
        idx = next((i for i, l in enumerate(lines) if live.match(l)), None)
        if idx is None:
            idx = next((i for i, l in enumerate(lines) if commented.match(l)), None)
        new = "%s=%s" % (key, value)
        if idx is None:
            while lines and lines[-1] == "":
                lines.pop()
            lines.append(new)
            lines.append("")
        else:
            lines[idx] = new
    return "\n".join(lines)


def read_env_value(text, key):
    for l in text.split("\n"):
        m = re.match(r"^\s*%s=(.*)$" % re.escape(key), l)
        if m:
            return m.group(1).strip().strip("\"'")
    return None


# ── the installer ─────────────────────────────────────────────────────────────────────────────────

class Event:
    __slots__ = ("module", "kind", "target", "status", "detail")

    def __init__(self, module, kind, target, status, detail=""):
        self.module, self.kind, self.target, self.status, self.detail = module, kind, target, status, detail

    def as_dict(self):
        return {"module": self.module, "kind": self.kind, "target": self.target, "status": self.status, "detail": self.detail}


class Installer:
    def __init__(self, manifest, ctx, dry_run=False, deps=False, models=False, settings=None, allow_units=None,
                 pve=None, force=False, no_systemd=None, out=print, runner=None):
        self.m = manifest
        self.ctx = ctx
        self.dry = dry_run
        self.deps = deps
        self.models = models
        self.settings = dict(settings or {})
        self.allow_units = allow_units
        self.pve = pve or {}
        self.force = force
        self.no_systemd = bool(ctx.prefix) if no_systemd is None else no_systemd
        self.out = out
        self.run_cmd = runner or self._run
        self.events = []
        self.changed_units = set()
        self.state = self._load_state()
        self.stamp = time.strftime("%Y%m%d-%H%M%S")

    # state ------------------------------------------------------------------------------------------
    @property
    def state_path(self):
        return self.ctx.host(os.path.join(self.ctx.v["hub_dir"], STATE_NAME))

    def _load_state(self):
        try:
            with open(self.state_path, encoding="utf-8") as f:
                s = json.load(f)
            s.setdefault("modules", {})
            return s
        except (OSError, ValueError):
            return {"kit_version": self.m.get("kit_version", ""), "scope": self.ctx.scope, "modules": {}}

    def _save_state(self):
        if self.dry:
            return
        self.state["kit_version"] = self.m.get("kit_version", "")
        self.state["scope"] = self.ctx.scope
        self.state["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
        os.makedirs(os.path.dirname(self.state_path), exist_ok=True)
        tmp = self.state_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({k: v for k, v in self.state.items()}, f, indent=2, sort_keys=True)
        os.replace(tmp, self.state_path)

    def installed(self):
        return set(self.state["modules"])

    # selection --------------------------------------------------------------------------------------
    def resolve(self, wanted, deps=True):
        """Expand groups and aliases, add dependencies (unless deps=False), return ids in install order (dependencies first)."""
        by_id = self.m["by_id"]
        seen, order = set(), []

        def add(mid):
            if mid in seen:
                return
            mod = by_id.get(mid)
            if mod is None:
                raise KitError("unknown module %r (see: install.py list)" % mid)
            seen.add(mid)
            if mod.get("children"):
                for c in mod["children"]:
                    add(c)
                return
            if deps:
                for d in mod.get("depends", []):
                    add(d)
            order.append(mid)

        for w in wanted:
            add(self.canonical(w))
        if "hub" in order:
            order.remove("hub")
            order.insert(0, "hub")
        return order

    def canonical(self, name):
        by_id = self.m["by_id"]
        if name in by_id:
            return name
        if "skill-" + name in by_id:
            return "skill-" + name
        raise KitError("unknown module %r (see: install.py list)" % name)

    # preflight --------------------------------------------------------------------------------------
    def preflight(self, ids):
        """[(level, module, message)] where level is error, warn or info."""
        issues = []
        if sys.version_info < (3, 8):
            issues.append(("error", "hub", "Python 3.8 or newer is required (this is %d.%d)" % sys.version_info[:2]))
        for mid in ids:
            mod = self.m["by_id"][mid]
            needs = mod.get("needs", {})
            for c in needs.get("commands", []):
                if not self._have_command(c["name"]):
                    issues.append((c.get("level", "warn"), mid, "command %r not found: %s" % (c["name"], c.get("why", ""))))
            for pm in needs.get("python_modules", []):
                if not self._have_pymodule(pm["name"]) and not (self.deps and mid in ("slash", "codex")):
                    issues.append((pm.get("level", "warn"), mid, "Python module %r missing: %s. Install %s" % (pm["name"], pm.get("why", ""), pm.get("fix", pm["name"]))))
            for f in needs.get("files", []):
                path = self.ctx.host(self.ctx.fmt(f["path"]))
                ok = os.path.exists(path)
                if ok and f.get("contains"):
                    try:
                        with open(path, encoding="utf-8", errors="replace") as fh:
                            ok = any(l.startswith(f["contains"]) for l in fh.read().split("\n"))
                    except OSError:
                        ok = False
                if not ok:
                    issues.append(("warn", mid, "%s: %s" % (self.ctx.fmt(f["path"]), f.get("why", "required file missing"))))
            if mod.get("skill"):
                issues.extend(self._skill_issues(mid, mod))
        if self.ctx.scope == "user" and not self.no_systemd and not shutil.which("systemctl"):
            issues.append(("warn", "hub", "systemctl not found: files will be installed but no services started"))
        if "voice-stt" in ids and "voice-proxy" in ids:
            issues.append(("info", "voice-stt", "the failover proxy is selected too, so the speech server will listen on port 10301"))
        if ("voice-stt" in ids or "voice-tts" in ids) and not self.deps:
            issues.append(("info", "voice-stt" if "voice-stt" in ids else "voice-tts", "voice servers are copied but not started: add --with-deps (virtualenv and packages)" + (" and --download-models (TTS voices)" if "voice-tts" in ids else "") + " to finish"))
        if "voice-tts" in ids and self.deps and not self.models:
            issues.append(("warn", "voice-tts", "no voices will be downloaded: add --download-models, or place the Kokoro and Piper files yourself"))
        return issues

    def _skill_issues(self, mid, mod):
        out = []
        try:
            import check_skills
            fm, _ = check_skills.read_skill(os.path.join(KIT, "skills", "companion", mod["skill"], "SKILL.md"))
        except Exception as e:  # a broken skill must not install
            return [("error", mid, "skill %s is not valid: %s" % (mod["skill"], e))]
        for cmd in (fm.get("prerequisites") or {}).get("commands") or []:
            if cmd != "hermes-notify" and not self._have_command(cmd):
                out.append(("info", mid, "%s works better with %r installed" % (mod["skill"], cmd)))
        return out

    def _have_command(self, name):
        if shutil.which(name):
            return True
        for d in (self.ctx.v["bin_dir"], os.path.join(self.ctx.home, ".local/bin")):
            if os.access(os.path.join(self.ctx.host(d), name), os.X_OK):
                return True
        return False

    def _have_pymodule(self, name):
        try:
            r = subprocess.run([self.ctx.v["python"], "-c", "import " + name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
            return r.returncode == 0
        except (OSError, subprocess.SubprocessError):
            return False

    # running ----------------------------------------------------------------------------------------
    def install(self, ids):
        errors = [i for i in self.preflight(ids) if i[0] == "error"]
        if errors and not self.force:
            raise KitError("cannot install yet:\n  " + "\n  ".join("[%s] %s" % (m, t) for _l, m, t in errors))
        for mid in ids:
            self.out("\n== %s" % self.m["by_id"][mid]["name"])
            self._install_module(mid)
        self._systemd_apply(ids)
        self._save_state()
        return self.events

    def _install_module(self, mid):
        mod = self.m["by_id"][mid]
        rec = self.state["modules"].setdefault(mid, {"files": {}, "units": [], "config": []}) if not self.dry else self.state["modules"].get(mid, {"files": {}, "units": [], "config": []})
        rec["version"] = self.m.get("kit_version", "")
        steps = list(mod.get("steps", []))
        if mod.get("skill"):
            steps = [{"do": "copy_tree", "src": "skills/companion/" + mod["skill"], "dest": "{skills_dir}/companion/" + mod["skill"], "policy": "managed"}]
        for step in steps:
            try:
                getattr(self, "_do_" + step["do"])(mid, rec, step)
            except KitError:
                raise
            except OSError as e:
                self._ev(mid, step["do"], step.get("dest") or step.get("path") or step.get("name", ""), "failed", str(e))
                raise KitError("%s: %s failed: %s" % (mid, step["do"], e))
        if mod.get("after"):
            self._ev(mid, "note", "", "todo", mod["after"])

    def _ev(self, module, kind, target, status, detail=""):
        e = Event(module, kind, target, status, detail)
        self.events.append(e)
        self.out("  %-9s %s%s" % (status, target, (" (%s)" % detail) if detail else ""))

    # file writing with policies
    def _write(self, mid, rec, logical_dest, data, mode, policy):
        dest = self.ctx.host(logical_dest)
        new_sha = sha256_bytes(data)
        known = rec["files"].get(logical_dest, {}).get("sha")
        if os.path.exists(dest):
            cur = sha256_file(dest)
            if cur == new_sha:
                self._chmod(dest, mode)
                rec["files"][logical_dest] = {"sha": new_sha, "policy": policy}
                return self._ev(mid, "file", logical_dest, "unchanged")
            pristine = known is not None and cur == known
            if policy == "managed" and not pristine and not self.force:
                if not self.dry:
                    with open(dest + ".kit-new", "wb") as f:
                        f.write(data)
                return self._ev(mid, "file", logical_dest, "kept", "your edits stay; new version saved as %s.kit-new" % os.path.basename(logical_dest))
            if not pristine:
                backup = "%s.bak-kit-%s" % (dest, self.stamp)
                if not self.dry:
                    shutil.copy2(dest, backup)
                detail = "backed up your version to %s" % os.path.basename(backup)
            else:
                detail = ""
            if not self.dry:
                self._atomic(dest, data, mode)
            rec["files"][logical_dest] = {"sha": new_sha, "policy": policy}
            self._mark_unit_change(mid, logical_dest)
            return self._ev(mid, "file", logical_dest, "updated", detail)
        if not self.dry:
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            self._atomic(dest, data, mode)
        rec["files"][logical_dest] = {"sha": new_sha, "policy": policy}
        self._mark_unit_change(mid, logical_dest)
        self._ev(mid, "file", logical_dest, "created")

    def _mark_unit_change(self, mid, logical_dest):
        for step in self.m["by_id"][mid].get("steps", []):
            if step.get("do") == "unit":
                self.changed_units.add((mid, step["name"]))

    def _atomic(self, dest, data, mode):
        tmp = dest + ".kit-tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.chmod(tmp, mode)
        os.replace(tmp, dest)

    def _chmod(self, dest, mode):
        if not self.dry:
            try:
                os.chmod(dest, mode)
            except OSError:
                pass

    def _src(self, step):
        for key in ("src", "alt_src"):
            if step.get(key):
                p = os.path.normpath(os.path.join(KIT, step[key]))
                if os.path.exists(p):
                    return p
        raise KitError("the kit is missing %s (re-download it)" % step.get("src"))

    def _mode(self, step, default="0644"):
        return int(step.get("mode", default), 8)

    def _do_copy(self, mid, rec, step):
        with open(self._src(step), "rb") as f:
            data = f.read()
        self._write(mid, rec, self.ctx.fmt(step["dest"]), data, self._mode(step), step.get("policy", "overwrite"))

    def _do_copy_tree(self, mid, rec, step):
        src = self._src(step)
        dest = self.ctx.fmt(step["dest"])
        skip = step.get("exclude", [])
        import fnmatch
        for root, dirs, files in os.walk(src):
            dirs[:] = sorted(d for d in dirs if not any(fnmatch.fnmatch(d, p) for p in skip))
            for name in sorted(files):
                if any(fnmatch.fnmatch(name, p) for p in skip) or name.endswith(".pyc"):
                    continue
                rel = os.path.relpath(os.path.join(root, name), src)
                with open(os.path.join(root, name), "rb") as f:
                    data = f.read()
                mode = 0o755 if os.access(os.path.join(root, name), os.X_OK) else 0o644
                self._write(mid, rec, os.path.join(dest, rel), data, mode, step.get("policy", "overwrite"))

    def _do_seed(self, mid, rec, step):
        dest = self.ctx.fmt(step["dest"])
        host = self.ctx.host(dest)
        if os.path.exists(host):
            return self._ev(mid, "file", dest, "unchanged", "yours, left alone")
        with open(self._src(step), "rb") as f:
            data = f.read()
        if step.get("src", "").endswith("ops-allowlist.example.txt") and self.allow_units is not None:
            data = self._allowlist(data.decode("utf-8")).encode("utf-8")
        if not self.dry:
            os.makedirs(os.path.dirname(host), exist_ok=True)
            self._atomic(host, data, self._mode(step))
        rec["files"][dest] = {"sha": sha256_bytes(data), "policy": "seed"}
        self._ev(mid, "file", dest, "created")

    def _allowlist(self, text):
        keep = [l for l in text.split("\n") if l.startswith("#") or not l.strip()]
        return "\n".join(keep + [u for u in self.allow_units if u] + [""])

    def _do_env(self, mid, rec, step):
        dest = self.ctx.fmt(step["dest"])
        host = self.ctx.host(dest)
        base = {"HERMES_ENV": os.path.join(self.ctx.hermes_home, ".env"), "HERMES_HOME": self.ctx.hermes_home,
                "HUB_DIR": self.ctx.v["hub_dir"], "PORT": self.ctx.v["port"]}
        explicit = dict(self.settings)
        if os.path.exists(host):
            with open(host, encoding="utf-8") as f:
                text = f.read()
            values = dict(explicit)
            for k, v in base.items():  # repair only the unedited template placeholders
                cur = read_env_value(text, k)
                if cur is None or "<user>" in cur:
                    values[k] = v
            new = apply_env(text, values) if values else text
            if new == text:
                return self._ev(mid, "config", dest, "unchanged")
            if not self.dry:
                shutil.copy2(host, "%s.bak-kit-%s" % (host, self.stamp))
                self._atomic(host, new.encode("utf-8"), self._mode(step, "0600"))
            rec["files"][dest] = {"sha": sha256_bytes(new.encode("utf-8")), "policy": "seed"}
            return self._mark_and_log_env(mid, dest, values, "updated")
        with open(self._src(step), encoding="utf-8") as f:
            text = f.read()
        new = apply_env(text, dict(base, **explicit))
        if not self.dry:
            os.makedirs(os.path.dirname(host), exist_ok=True)
            self._atomic(host, new.encode("utf-8"), self._mode(step, "0600"))
        rec["files"][dest] = {"sha": sha256_bytes(new.encode("utf-8")), "policy": "seed"}
        self._mark_and_log_env(mid, dest, dict(base, **explicit), "created")

    def _mark_and_log_env(self, mid, dest, values, status):
        shown = ", ".join("%s=%s" % (k, "***" if re.search(r"KEY|TOKEN|SECRET|PASSWORD", k) else v) for k, v in values.items() if k in self.settings)
        self.changed_units.add((mid, "hermes-hub"))
        self._ev(mid, "config", dest, status, shown)

    def _do_env_file(self, mid, rec, step):
        dest = self.ctx.fmt(step["dest"])
        host = self.ctx.host(dest)
        wanted = {}
        for k, v in step.get("values", {}).items():
            if v == "@token":
                v = secrets.token_urlsafe(32)
            elif v == "@stt_device":
                v = self.ctx.stt_device
            else:
                v = self.ctx.fmt(v)
            wanted[k] = v
        text = ""
        if os.path.exists(host):
            with open(host, encoding="utf-8") as f:
                text = f.read()
        todo = {k: v for k, v in wanted.items() if read_env_value(text, k) is None or not step.get("keep_existing")}
        todo.update({k: v for k, v in self.settings.items() if k in wanted})
        if not todo:
            return self._ev(mid, "config", dest, "unchanged")
        new = apply_env(text, todo)
        if new == text:
            return self._ev(mid, "config", dest, "unchanged")
        if not self.dry:
            os.makedirs(os.path.dirname(host), exist_ok=True)
            self._atomic(host, new.encode("utf-8"), self._mode(step, "0600"))
        rec["files"][dest] = {"sha": sha256_bytes(new.encode("utf-8")), "policy": "seed"}
        self._ev(mid, "config", dest, "created" if not text else "updated", ", ".join(sorted(todo)))

    def _do_token(self, mid, rec, step):
        dest = self.ctx.fmt(step["dest"])
        host = self.ctx.host(dest)
        if os.path.exists(host) and os.path.getsize(host) > 0:
            return self._ev(mid, "secret", dest, "unchanged", "already there")
        if not self.dry:
            os.makedirs(os.path.dirname(host), mode=0o700, exist_ok=True)
            self._atomic(host, (secrets.token_urlsafe(32) + "\n").encode("ascii"), 0o600)
        rec["files"][dest] = {"sha": "", "policy": "secret"}
        self._ev(mid, "secret", dest, "created")

    def _do_unit(self, mid, rec, step):
        with open(self._src(step), encoding="utf-8") as f:
            text = f.read()
        text = self.ctx.render_unit(text)
        name = step["name"]
        dest = os.path.join(self.ctx.v["unit_dir"], name + ".service")
        self._write(mid, rec, dest, text.encode("utf-8"), 0o644, "overwrite")
        if name not in rec["units"]:
            rec["units"].append(name)

    def _do_venv(self, mid, rec, step):
        path = self.ctx.fmt(step["path"])
        host = self.ctx.host(path)
        if step.get("when") == "deps" and not self.deps:
            return self._ev(mid, "venv", path, "skipped", "run again with --with-deps")
        if os.path.exists(os.path.join(host, "bin", "python")):
            status = "unchanged"
        else:
            if not self.dry:
                self._check(self.run_cmd([self.ctx.v["python"], "-m", "venv", host], timeout=300), "creating the virtualenv " + path)
            status = "created"
        pkgs = []
        for p in step.get("packages", []):
            if p == "@onnxruntime":
                pkgs.extend(["onnxruntime-gpu"] + _NVIDIA_LIBS if self.ctx.stt_device == "gpu" else ["onnxruntime"])
            else:
                pkgs.append(p)
        pip = [os.path.join(host, "bin", "python"), "-m", "pip", "install", "--quiet", "--disable-pip-version-check"]
        if step.get("requirements"):
            req = os.path.normpath(os.path.join(KIT, step["requirements"]))
            if not self.dry:
                self._check(self.run_cmd(pip + ["-r", req], timeout=1800), "installing packages into " + path)
        elif pkgs:
            if not self.dry:
                self._check(self.run_cmd(pip + pkgs, timeout=3600), "installing packages into " + path)
        rec.setdefault("config", [])
        self._ev(mid, "venv", path, status, "%d packages" % len(pkgs) if pkgs else "requirements file")

    def _do_download(self, mid, rec, step):
        dest = self.ctx.fmt(step["dest"])
        host = self.ctx.host(dest)
        if step.get("when") == "models" and not self.models:
            return self._ev(mid, "download", dest, "skipped", "add --download-models")
        if os.path.exists(host) and os.path.getsize(host) > 0:
            return self._ev(mid, "download", dest, "unchanged")
        if self.dry:
            return self._ev(mid, "download", dest, "would download", step["url"])
        os.makedirs(os.path.dirname(host), exist_ok=True)
        tmp = host + ".part"
        self.out("  downloading %s" % step["url"])
        try:
            with urllib.request.urlopen(step["url"], timeout=60) as r, open(tmp, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
        except Exception as e:
            if os.path.exists(tmp):
                os.remove(tmp)
            raise KitError("download failed for %s: %s" % (step["url"], e))
        os.replace(tmp, host)
        self._ev(mid, "download", dest, "created", "%.0f MB" % (os.path.getsize(host) / 1e6))

    def _do_mcp(self, mid, rec, step):
        name = step["name"]
        command = self.ctx.fmt(step["command"])
        args = [self.ctx.fmt(a) for a in step.get("args", [])]
        snippet = "mcp_servers:\n  %s:\n    command: %s\n    args:\n%s    transport: stdio" % (name, command, "".join("      - %s\n" % a for a in args))
        if self.ctx.prefix or not shutil.which("hermes"):
            return self._ev(mid, "mcp", name, "todo", "add this to %s/config.yaml:\n%s" % (self.ctx.hermes_home, snippet))
        if self.dry:
            return self._ev(mid, "mcp", name, "would register", "hermes mcp add %s" % name)
        cfg = os.path.join(self.ctx.hermes_home, "config.yaml")
        try:
            with open(cfg, encoding="utf-8") as f:
                if re.search(r"^\s{2}%s:\s*$" % re.escape(name), f.read(), re.M):
                    return self._ev(mid, "mcp", name, "unchanged", "already in config.yaml")
        except OSError:
            pass
        r = self.run_cmd(["hermes", "mcp", "add", name, "--command", command, "--args"] + args, timeout=60, stdin=subprocess.DEVNULL, env={"HERMES_HOME": self.ctx.hermes_home})
        if r is not None and r.returncode == 0:
            rec["config"].append("mcp:" + name)
            return self._ev(mid, "mcp", name, "created", "registered with hermes mcp add")
        self._ev(mid, "mcp", name, "todo", "could not register automatically. Add this to %s:\n%s" % (cfg, snippet))

    def _do_pve(self, mid, rec, step):
        dest = self.ctx.fmt(step["dest"])
        ssh, key = self.pve.get("ssh", ""), self.pve.get("key", "")
        host = self.ctx.host(dest)
        if os.path.exists(host) and not (ssh and key):
            return self._ev(mid, "config", dest, "kept", "already there")
        if not (ssh and key):
            return self._ev(mid, "config", dest, "skipped", "pass --pve-ssh user@host and --pve-key /path/to/readonly-key (skip if the hub runs on the Proxmox host)")
        data = (json.dumps({"ssh": ssh, "key": key}) + "\n").encode("utf-8")
        self._write(mid, rec, dest, data, 0o600, "seed")

    # processes and systemd --------------------------------------------------------------------------
    def _run(self, cmd, timeout=120, stdin=None, env=None):
        try:
            return subprocess.run(cmd, stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, universal_newlines=True,
                                  env=dict(os.environ, **env) if env else None)
        except (OSError, subprocess.SubprocessError) as e:
            return subprocess.CompletedProcess(cmd, 127, "", str(e))

    def _check(self, result, what):
        if result is not None and result.returncode != 0:
            tail = (result.stdout or "").strip().split("\n")[-6:]
            raise KitError("%s failed:\n    %s" % (what, "\n    ".join(tail)))

    def _systemctl(self, *args):
        cmd = ["systemctl"] + (["--user"] if self.ctx.systemd_user else []) + list(args)
        return self.run_cmd(cmd, timeout=60)

    def _systemd_apply(self, ids):
        units = []
        for mid in ids:
            for step in self.m["by_id"][mid].get("steps", []):
                if step.get("do") == "unit":
                    units.append((mid, step))
        if not units:
            return
        if self.no_systemd or not shutil.which("systemctl"):
            for mid, step in units:
                self._ev(mid, "service", step["name"], "todo", "start it yourself: systemctl %senable --now %s" % ("--user " if self.ctx.systemd_user else "", step["name"]))
            return
        if self.dry:
            for mid, step in units:
                self._ev(mid, "service", step["name"], "would enable" if step.get("enable") else "would install")
            return
        self._systemctl("daemon-reload")
        for mid, step in units:
            name = step["name"]
            if not step.get("enable"):
                self._ev(mid, "service", name, "installed", "not started on purpose; see the module note")
                continue
            if step.get("needs_deps") and not self._deps_present(mid):
                self._ev(mid, "service", name, "skipped", "not started: the virtualenv is not there yet (--with-deps)")
                continue
            active = self._systemctl("is-active", "--quiet", name).returncode == 0
            if active and (mid, name) in self.changed_units:
                r = self._systemctl("restart", name)
                self._ev(mid, "service", name, "restarted" if r.returncode == 0 else "failed", (r.stdout or "").strip()[-200:])
            elif not active:
                r = self._systemctl("enable", "--now", name)
                self._ev(mid, "service", name, "started" if r.returncode == 0 else "failed", (r.stdout or "").strip()[-200:])
            else:
                self._ev(mid, "service", name, "unchanged", "running")

    def _deps_present(self, mid):
        d = self.ctx.v["voice_dir"]
        need = {"voice-stt": "venv-stt", "voice-tts": "venv-tts"}.get(mid)
        return need is None or os.path.exists(self.ctx.host(os.path.join(d, need, "bin", "python")))

    # verify -----------------------------------------------------------------------------------------
    def verify(self, ids):
        """[(ok, module, what)] for every check of the given modules."""
        results = []
        for mid in ids:
            mod = self.m["by_id"][mid]
            checks = list(mod.get("verify", []))
            if mod.get("skill"):
                checks = [{"file": "{skills_dir}/companion/%s/SKILL.md" % mod["skill"]}]
            for c in checks:
                results.append(self._verify_one(mid, c))
        return results

    def _verify_one(self, mid, c):
        if "file" in c:
            p = self.ctx.fmt(c["file"])
            return (os.path.exists(self.ctx.host(p)), mid, "file " + p)
        if "command" in c:
            return (self._have_command(c["command"]), mid, "command " + c["command"])
        if "unit" in c:
            if self.no_systemd or not shutil.which("systemctl"):
                return (True, mid, "service %s (not checked: no systemd here)" % c["unit"])
            ok = self._systemctl("is-active", "--quiet", c["unit"]).returncode == 0
            return (ok, mid, "service %s is running" % c["unit"])
        if "http" in c:
            url = self.ctx.fmt(c["http"])
            if self.ctx.prefix:
                return (True, mid, "%s (not checked: staged install)" % url)
            for _ in range(10):
                try:
                    with urllib.request.urlopen(url, timeout=3) as r:
                        return (200 <= r.status < 300, mid, "%s answers" % url)
                except Exception:
                    time.sleep(1)
            return (False, mid, "%s does not answer" % url)
        if "tcp" in c:
            hostport = self.ctx.fmt(c["tcp"])
            if self.ctx.prefix:
                return (True, mid, "%s (not checked: staged install)" % hostport)
            h, p = hostport.rsplit(":", 1)
            try:
                with socket.create_connection((h, int(p)), timeout=3):
                    return (True, mid, "%s accepts connections" % hostport)
            except OSError:
                return (False, mid, "%s does not accept connections" % hostport)
        return (False, mid, "unknown check %r" % c)

    # uninstall --------------------------------------------------------------------------------------
    def uninstall(self, ids, purge=False):
        installed = self.installed()
        targets = [i for i in ids if i in installed]
        if not targets:
            self.out("Nothing to remove.")
            return self.events
        remaining = installed - set(targets)
        if "hub" in targets and any(self.m["by_id"][r].get("depends") and "hub" in self.m["by_id"][r]["depends"] for r in remaining) and not self.force:
            raise KitError("other installed modules need the hub: %s. Remove them too, or add --force." % ", ".join(sorted(remaining)))
        for mid in reversed(targets):
            self.out("\n== %s" % self.m["by_id"][mid]["name"])
            rec = self.state["modules"][mid]
            for name in rec.get("units", []):
                if not self.dry and not self.no_systemd and shutil.which("systemctl"):
                    self._systemctl("disable", "--now", name)
            for logical, info in list(rec["files"].items()):
                self._remove_file(mid, logical, info, purge)
            if any(c.startswith("mcp:") for c in rec.get("config", [])) and shutil.which("hermes") and not self.dry and not self.ctx.prefix:
                self.run_cmd(["hermes", "mcp", "remove", "phone"], timeout=30, stdin=subprocess.DEVNULL, env={"HERMES_HOME": self.ctx.hermes_home})
            if not self.dry:
                del self.state["modules"][mid]
        if not self.dry and not self.no_systemd and shutil.which("systemctl"):
            self._systemctl("daemon-reload")
        if self.state["modules"]:
            self._save_state()
        elif not self.dry:
            try:
                os.remove(self.state_path)
            except OSError:
                pass
        return self.events

    def _remove_file(self, mid, logical, info, purge):
        host = self.ctx.host(logical)
        if not os.path.exists(host):
            return
        policy = info.get("policy", "overwrite")
        if policy in ("seed", "secret") and not purge:
            return self._ev(mid, "file", logical, "kept", "your configuration (use --purge to delete)")
        if os.path.isfile(host) and info.get("sha") and sha256_file(host) != info["sha"] and not purge and not self.force:
            return self._ev(mid, "file", logical, "kept", "you edited it")
        if not self.dry:
            os.remove(host)
            for extra in (host + ".kit-new",):
                if os.path.exists(extra):
                    os.remove(extra)
            d = os.path.dirname(host)
            while d and d != self.ctx.host("/") and os.path.isdir(d) and not os.listdir(d) and self._inside_kit_dirs(d):
                os.rmdir(d)
                d = os.path.dirname(d)
        self._ev(mid, "file", logical, "removed")

    def _inside_kit_dirs(self, d):
        d = d[len(self.ctx.prefix):] if self.ctx.prefix else d
        roots = [self.ctx.v[k] for k in ("hub_dir", "voice_dir", "bridge_dir", "claude_bridge_dir")] + [os.path.join(self.ctx.hermes_home, "skills", "companion"), os.path.join(self.ctx.home, ".codex", "skills")]
        return any(d == r or d.startswith(r + "/") for r in roots)


_NVIDIA_LIBS = ["nvidia-cublas-cu12", "nvidia-cuda-nvrtc-cu12", "nvidia-cuda-runtime-cu12", "nvidia-cudnn-cu12", "nvidia-cufft-cu12", "nvidia-curand-cu12", "nvidia-nvjitlink-cu12"]


# ── presentation ──────────────────────────────────────────────────────────────────────────────────

def module_rows(manifest, installer):
    """One dict per user-visible module for `list` and for the app."""
    rows = []
    installed = installer.installed()
    for mod in manifest["modules"]:
        rows.append({
            "id": mod["id"], "name": mod["name"], "group": mod.get("group", ""), "summary": mod.get("summary", ""),
            "detail": mod.get("detail", ""), "default": bool(mod.get("default")), "required": bool(mod.get("required")),
            "experimental": bool(mod.get("experimental")), "depends": mod.get("depends", []), "children": mod.get("children", []),
            "installed": mod["id"] in installed or (bool(mod.get("children")) and all(c in installed for c in mod["children"])),
        })
    return rows


def print_list(manifest, installer, out=print):
    groups = {g["id"]: g["title"] for g in manifest["groups"]}
    out("Hermes Companion server kit %s (%s install)\n" % (manifest.get("kit_version", ""), installer.ctx.scope))
    out("Presets: " + "; ".join("%s = %s" % (k, ", ".join(p["modules"])) for k, p in manifest["presets"].items()) + "\n")
    last = None
    for r in module_rows(manifest, installer):
        if r["id"].startswith("skill-"):
            continue
        if r["group"] != last:
            out("\n" + groups.get(r["group"], r["group"]).upper())
            last = r["group"]
        flag = "installed" if r["installed"] else ("default" if r["default"] else "")
        tag = " (experimental)" if r["experimental"] else ""
        out("  %-14s %s%s%s" % (r["id"], r["summary"], tag, ("  [%s]" % flag) if flag else ""))
        if r["children"]:
            for c in r["children"]:
                child = manifest["by_id"][c]
                mark = "installed" if c in installer.installed() else ""
                out("      %-22s %s%s" % (c, child.get("summary", ""), ("  [%s]" % mark) if mark else ""))


def interactive_select(manifest, installer, preselected, input_fn=input, out=print):
    """A terminal checklist: toggle numbers, a = all, n = none, p = preset, d N = details, Enter = go, q = quit."""
    top = [m for m in manifest["modules"] if not m["id"].startswith("skill-")]
    selected = set(installer.resolve(preselected)) if preselected else {m["id"] for m in manifest["modules"] if m.get("default") and not m.get("children")}
    selected.add("hub")
    while True:
        out("\nChoose what to install on this server. Required items are always included.\n")
        for i, mod in enumerate(top, 1):
            if mod.get("children"):
                kids = mod["children"]
                n = sum(1 for k in kids if k in selected)
                box = "[x]" if n == len(kids) else ("[~]" if n else "[ ]")
                label = "%s (%d of %d)" % (mod["name"], n, len(kids))
            else:
                box = "[x]" if mod["id"] in selected else "[ ]"
                label = mod["name"]
            note = " (always)" if mod.get("required") else " (experimental)" if mod.get("experimental") else (" (installed)" if mod["id"] in installer.installed() else "")
            out("  %2d. %s %s%s\n        %s" % (i, box, label, note, mod.get("summary", "")))
        out("\n  Type numbers to toggle (e.g. 2 5), s to pick individual skills, a all, n none, p preset, d <number> details, Enter to continue, q to quit.")
        try:
            raw = input_fn("> ").strip()
        except EOFError:
            raw = "q"
        if raw in ("", "go"):
            return sorted(selected, key=lambda x: list(manifest["by_id"]).index(x))
        if raw == "q":
            raise KitError("cancelled")
        if raw == "a":
            selected = {m["id"] for m in manifest["modules"] if not m.get("children") and not m.get("experimental")}
        elif raw == "n":
            selected = {"hub"}
        elif raw == "p":
            names = list(manifest["presets"])
            for j, k in enumerate(names, 1):
                out("   %d. %s: %s" % (j, manifest["presets"][k]["title"], manifest["presets"][k]["summary"]))
            pick = input_fn("preset number> ").strip()
            if pick.isdigit() and 1 <= int(pick) <= len(names):
                selected = set(installer.resolve(manifest["presets"][names[int(pick) - 1]]["modules"]))
        elif raw == "s":
            selected = _pick_skills(manifest, selected, input_fn, out)
        elif raw.startswith("d "):
            n = raw[2:].strip()
            if n.isdigit() and 1 <= int(n) <= len(top):
                out("\n" + top[int(n) - 1].get("detail", top[int(n) - 1].get("summary", "")))
        else:
            for tok in raw.replace(",", " ").split():
                if tok.isdigit() and 1 <= int(tok) <= len(top):
                    mod = top[int(tok) - 1]
                    ids = mod["children"] if mod.get("children") else [mod["id"]]
                    if mod.get("required"):
                        continue
                    if all(i in selected for i in ids):
                        selected -= set(ids)
                    else:
                        selected |= set(ids)
        for mid in list(selected):  # dependencies follow their dependents
            for d in manifest["by_id"][mid].get("depends", []):
                selected.add(d)


def _pick_skills(manifest, selected, input_fn, out):
    kids = manifest["by_id"]["skills"]["children"]
    while True:
        out("\nSkills (type numbers to toggle, Enter when done):")
        for i, k in enumerate(kids, 1):
            out("  %2d. %s %-14s %s" % (i, "[x]" if k in selected else "[ ]", manifest["by_id"][k]["name"], manifest["by_id"][k].get("summary", "")))
        raw = input_fn("skills> ").strip()
        if not raw:
            return selected
        for tok in raw.replace(",", " ").split():
            if tok.isdigit() and 1 <= int(tok) <= len(kids):
                k = kids[int(tok) - 1]
                selected ^= {k}


def ask(input_fn, text, secret=False):
    """One optional answer; blank means skip. Secrets are not echoed on a real terminal."""
    if secret and input_fn is input:
        import getpass
        return getpass.getpass(text).strip()
    return input_fn(text).strip()


def ask_settings(manifest, ids, inst, input_fn=input, out=print):
    """Optional questions for the chosen modules; every one can be skipped with Enter. Returns {KEY: value} for the hub env."""
    got = {}
    if "hub" in ids:
        out("\nHub settings (Enter to skip any of them)")
        for item in manifest["by_id"]["hub"].get("settings", []):
            v = ask(input_fn, "  %s: " % item["prompt"], item.get("secret", False))
            if v:
                got[item["key"]] = v
        if inst.allow_units is None:
            v = ask(input_fn, "  Services the Ops screen may show and restart, comma-separated (blank = none): ")
            if v:
                inst.allow_units = [u.strip() for u in v.split(",") if u.strip()]
    if "proxmox" in ids and not (inst.pve.get("ssh") and inst.pve.get("key")):
        ssh = ask(input_fn, "\nProxmox host as user@host (blank to skip): ")
        key = ask(input_fn, "  read-only SSH key path: ") if ssh else ""
        if ssh and key:
            inst.pve = {"ssh": ssh, "key": key}
    if "voice-stt" in ids and inst.ctx.stt_device == "cpu":
        v = ask(input_fn, "\nSpeech-to-text on an NVIDIA GPU? [y/N] ").lower()
        if v.startswith("y"):
            inst.ctx.stt_device = "gpu"
    if ("voice-stt" in ids or "voice-tts" in ids) and not inst.deps:
        v = ask(input_fn, "\nCreate the Python environments for the voice servers now (downloads packages)? [y/N] ").lower()
        if v.startswith("y"):
            inst.deps = True
            if "voice-tts" in ids and ask(input_fn, "Download the text-to-speech voices too (about 450 MB)? [y/N] ").lower().startswith("y"):
                inst.models = True
    return got


def print_issues(issues, out=print):
    for level, mid, msg in issues:
        out("  %-5s [%s] %s" % (level.upper() if level != "info" else "note", mid, msg))


# ── command line ──────────────────────────────────────────────────────────────────────────────────

def build_parser():
    p = argparse.ArgumentParser(prog="install.py", description="Hermes Companion server kit installer.",
                                formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("\n\n", 1)[1])
    p.add_argument("command", nargs="?", default="install", choices=["install", "list", "status", "verify", "uninstall", "check"])
    p.add_argument("--modules", "-m", help="comma-separated module ids (see: list). 'skills' means all ten skills.")
    p.add_argument("--skills", help="comma-separated skills to install (adds the hub); same as listing skill-<name> modules")
    p.add_argument("--preset", choices=["minimal", "recommended", "full"], help="a ready-made selection")
    p.add_argument("--all", action="store_true", help="every non-experimental module")
    p.add_argument("--without", help="comma-separated modules to leave out of the selection")
    p.add_argument("--interactive", "-i", action="store_true", help="always show the menu")
    p.add_argument("--yes", "-y", action="store_true", help="do not ask for confirmation")
    p.add_argument("--dry-run", "-n", action="store_true", help="show what would change and change nothing")
    p.add_argument("--json", action="store_true", help="machine-readable output for list, status, verify and install")
    scope = p.add_mutually_exclusive_group()
    scope.add_argument("--system", dest="scope", action="store_const", const="system", help="install for the whole machine (default as root)")
    scope.add_argument("--user", dest="scope", action="store_const", const="user", help="install for your own account, no root needed (default otherwise)")
    p.add_argument("--prefix", default="", help="stage everything under this directory (testing, packaging); starts no services")
    p.add_argument("--no-systemd", action="store_true", help="install files but do not touch services")
    p.add_argument("--hermes-home", help="the Hermes home folder (default: $HERMES_HOME or ~/.hermes)")
    p.add_argument("--service-user", help="account the voice and bridge services run as (default: you, or SUDO_USER)")
    p.add_argument("--python", help="python3 to run the services with")
    p.add_argument("--set", action="append", default=[], metavar="KEY=VALUE", help="set a hub setting in its env file (repeatable), e.g. APP_REPO=owner/name")
    p.add_argument("--allow-units", help="comma-separated systemd units the Ops screen may show and restart")
    p.add_argument("--with-deps", action="store_true", help="create virtualenvs and pip-install what the voice and bridge modules need")
    p.add_argument("--download-models", action="store_true", help="download the text-to-speech voices (about 450 MB)")
    p.add_argument("--stt-device", choices=["cpu", "gpu"], default="cpu", help="speech-to-text on CPU (default) or an NVIDIA GPU")
    p.add_argument("--pve-ssh", help="Proxmox host as user@host for the proxmox module")
    p.add_argument("--pve-key", help="read-only SSH key for --pve-ssh")
    p.add_argument("--purge", action="store_true", help="uninstall: also delete your configuration and secrets")
    p.add_argument("--force", action="store_true", help="go ahead despite blocking problems; overwrite edited skills")
    return p


def parse_settings(items):
    out = {}
    for it in items:
        if "=" not in it or not re.match(r"^[A-Z][A-Z0-9_]*=", it):
            raise KitError("--set expects KEY=VALUE with an UPPER_CASE key, got %r" % it)
        k, v = it.split("=", 1)
        out[k] = v
    return out


def selection_from_args(manifest, args):
    wanted = []
    if args.preset:
        wanted += manifest["presets"][args.preset]["modules"]
    if args.all:
        wanted += [m["id"] for m in manifest["modules"] if not m.get("children") and not m.get("experimental")]
    if args.modules:
        wanted += [x.strip() for x in args.modules.split(",") if x.strip()]
    if args.skills:
        wanted += ["hub"] + ["skill-" + x.strip() for x in args.skills.split(",") if x.strip()]
    return wanted


def tty_or_asked(args, tty):
    return (tty or args.interactive) and not args.json


def main(argv=None, out=print, input_fn=input, installer_factory=None):
    args = build_parser().parse_args(argv)
    try:
        return _main(args, out, input_fn, installer_factory)
    except KitError as e:
        out("\ninstall.py: %s" % e)
        return 1
    except KeyboardInterrupt:
        out("\ncancelled")
        return 130


def _main(args, out, input_fn, installer_factory):
    manifest = load_manifest()
    if args.command == "check":
        import check_skills
        paths, errs = check_skills.check_dir(os.path.join(KIT, "skills"))
        for e in errs:
            out("FAIL " + e)
        out("%d skills checked, %d problems" % (len(paths), len(errs)))
        return 1 if errs else 0
    ctx = Ctx(manifest, scope=args.scope, prefix=args.prefix, hermes_home=args.hermes_home, service_user=args.service_user,
              python=args.python, stt_device=args.stt_device, with_proxy=False)
    inst = (installer_factory or Installer)(manifest, ctx, dry_run=args.dry_run, deps=args.with_deps, models=args.download_models,
                                            settings=parse_settings(args.set), allow_units=[u.strip() for u in args.allow_units.split(",")] if args.allow_units else None,
                                            pve={"ssh": args.pve_ssh or "", "key": args.pve_key or ""}, force=args.force,
                                            no_systemd=True if args.no_systemd else None, out=(lambda *_a, **_k: None) if args.json else out)

    if args.command == "list":
        if args.json:
            out(json.dumps({"kit_version": manifest.get("kit_version"), "scope": ctx.scope, "presets": manifest["presets"], "groups": manifest["groups"], "modules": module_rows(manifest, inst)}, indent=2))
        else:
            print_list(manifest, inst, out)
        return 0
    if args.command == "status":
        mods = inst.state["modules"]
        if args.json:
            out(json.dumps({"kit_version": inst.state.get("kit_version"), "scope": inst.state.get("scope"), "modules": {k: {"version": v.get("version"), "files": len(v["files"]), "units": v.get("units", [])} for k, v in mods.items()}}, indent=2))
        elif not mods:
            out("Nothing from the kit is installed here (looked in %s)." % inst.state_path)
        else:
            out("Kit %s, %s install\n" % (inst.state.get("kit_version"), inst.state.get("scope")))
            for k, v in sorted(mods.items()):
                out("  %-20s v%s  %d files%s" % (k, v.get("version", "?"), len(v["files"]), ("  services: " + ", ".join(v["units"])) if v.get("units") else ""))
            if inst.state.get("kit_version") != manifest.get("kit_version"):
                out("\nThis kit is %s, the install is %s: run install.py again to update." % (manifest.get("kit_version"), inst.state.get("kit_version")))
        return 0
    if args.command == "verify":
        ids = inst.resolve(args.modules.split(",")) if args.modules else sorted(inst.installed(), key=lambda x: list(manifest["by_id"]).index(x))
        res = inst.verify(ids)
        if args.json:
            out(json.dumps([{"ok": ok, "module": m, "check": w} for ok, m, w in res], indent=2))
        else:
            for ok, m, w in res:
                out("  %s [%s] %s" % ("ok  " if ok else "FAIL", m, w))
            out("\n%d of %d checks passed" % (sum(1 for r in res if r[0]), len(res)))
        return 0 if all(r[0] for r in res) else 1
    if args.command == "uninstall":
        ids = inst.resolve(args.modules.split(","), deps=False) if args.modules else sorted(inst.installed(), key=lambda x: list(manifest["by_id"]).index(x))
        if not args.yes and not args.dry_run:
            out("This removes: " + ", ".join(ids) + (" and ALL configuration and secrets" if args.purge else " (configuration and secrets stay)"))
            if input_fn("Continue? [y/N] ").strip().lower() != "y":
                raise KitError("cancelled")
        inst.uninstall(ids, purge=args.purge)
        if args.dry_run:
            out("\nDry run: nothing was changed.")
        return 0

    # install
    say = (lambda *_a, **_k: None) if args.json else out
    wanted = selection_from_args(manifest, args)
    tty = hasattr(sys.stdin, "isatty") and sys.stdin.isatty()
    if not wanted and (args.interactive or (tty and not args.yes and not args.json)):
        wanted = interactive_select(manifest, inst, [], input_fn, say)
    elif args.interactive:
        wanted = interactive_select(manifest, inst, wanted, input_fn, say)
    if not wanted:
        raise KitError("nothing selected. Use --preset, --modules, --all or the menu (run without --yes on a terminal).")
    if args.without:
        drop = set()
        for x in args.without.split(","):
            drop.add(inst.canonical(x.strip()))
        ids_all = inst.resolve(wanted)
        wanted = [i for i in ids_all if i not in drop and i != "hub"] + (["hub"] if "hub" in ids_all else [])
    ids = inst.resolve(wanted)
    if "voice-proxy" in ids and "voice-stt" in ids:
        ctx.v["stt_port"] = "10301"
    if tty_or_asked(args, tty) and not args.yes:
        inst.settings.update(ask_settings(manifest, ids, inst, input_fn, say))
    say("\nHermes Companion server kit %s: %s install into %s" % (manifest.get("kit_version"), ctx.scope, ctx.v["hub_dir"]))
    say("Modules: " + ", ".join(ids))
    issues = inst.preflight(ids)
    if issues:
        say("\nChecks:")
        print_issues(issues, say)
    if any(i[0] == "error" for i in issues) and not args.force:
        raise KitError("fix the errors above (or add --force) and run again.")
    if not args.yes and not args.dry_run:
        if not tty:
            raise KitError("not a terminal: add --yes to proceed without asking.")
        if input_fn("\nInstall now? [Y/n] ").strip().lower() in ("n", "no"):
            raise KitError("cancelled")
    if args.json:
        inst.out = lambda *_a, **_k: None
    inst.install(ids)
    results = inst.verify(ids) if not args.dry_run else []
    if args.json:
        out(json.dumps({"modules": ids, "events": [e.as_dict() for e in inst.events], "verify": [{"ok": ok, "module": m, "check": w} for ok, m, w in results], "issues": [{"level": l, "module": m, "message": t} for l, m, t in issues]}, indent=2))
    else:
        out("\nSummary")
        counts = {}
        for e in inst.events:
            counts[e.status] = counts.get(e.status, 0) + 1
        out("  " + ", ".join("%d %s" % (n, s) for s, n in sorted(counts.items())))
        for ok, m, w in results:
            out("  %s [%s] %s" % ("ok  " if ok else "FAIL", m, w))
        todo = [e for e in inst.events if e.status == "todo"]
        if todo:
            out("\nLeft for you:")
            for e in todo:
                out("  [%s] %s %s" % (e.module, e.target, e.detail))
        if args.dry_run:
            out("\nDry run: nothing was changed.")
    return 0 if all(r[0] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
