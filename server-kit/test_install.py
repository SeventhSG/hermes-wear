"""Offline tests for install.py, modules.json and check_skills.py (stdlib unittest, nothing leaves a temp directory).

    python3 -m unittest -v test_install
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_skills  # noqa: E402
import install  # noqa: E402

KIT = install.KIT


def quiet(*_a, **_k):
    pass


class Sandbox:
    """A staged install target: logical paths live under <tmp>/root, Hermes home is /home/u/.hermes inside it."""

    def __init__(self):
        self.tmp = tempfile.mkdtemp(prefix="kit-test-")
        self.prefix = os.path.join(self.tmp, "root")
        self.home = "/home/u"
        self.hermes = self.home + "/.hermes"
        os.makedirs(self.prefix + self.hermes)
        with open(self.prefix + self.hermes + "/.env", "w") as f:
            f.write("API_SERVER_KEY=abc123\n")
        self.manifest = install.load_manifest()
        self.baseline = self.tree()

    def ctx(self, **kw):
        kw.setdefault("scope", "user")
        c = install.Ctx(self.manifest, prefix=self.prefix, hermes_home=self.hermes, service_user="u", home=self.home, **kw)
        return c

    def installer(self, **kw):
        ctxkw = {k: kw.pop(k) for k in ("stt_device", "with_proxy") if k in kw}
        kw.setdefault("out", quiet)
        return install.Installer(self.manifest, self.ctx(**ctxkw), **kw)

    def path(self, logical):
        return self.prefix + logical

    def read(self, logical):
        with open(self.path(logical), encoding="utf-8") as f:
            return f.read()

    def tree(self):
        out = []
        for root, _d, files in os.walk(self.prefix):
            for f in files:
                out.append(os.path.relpath(os.path.join(root, f), self.prefix))
        return sorted(x for x in out if x not in getattr(self, "baseline", []))

    def cleanup(self):
        shutil.rmtree(self.tmp, ignore_errors=True)


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.m = install.load_manifest()

    def test_ids_presets_children_and_dependencies_resolve(self):
        ids = set(self.m["by_id"])
        for mod in self.m["modules"]:
            for d in mod.get("depends", []):
                self.assertIn(d, ids, mod["id"])
            for c in mod.get("children", []):
                self.assertIn(c, ids)
        for name, preset in self.m["presets"].items():
            for mid in preset["modules"]:
                self.assertIn(mid, ids, name)
        for g in {m.get("group") for m in self.m["modules"]}:
            self.assertIn(g, {x["id"] for x in self.m["groups"]})

    def test_every_source_file_and_unit_template_exists(self):
        for mod in self.m["modules"]:
            for step in mod.get("steps", []):
                if "src" in step:
                    ok = any(os.path.exists(os.path.normpath(os.path.join(KIT, step[k]))) for k in ("src", "alt_src") if step.get(k))
                    self.assertTrue(ok, "%s: %s" % (mod["id"], step["src"]))
                if step.get("requirements"):
                    self.assertTrue(os.path.exists(os.path.normpath(os.path.join(KIT, step["requirements"]))))

    def test_path_placeholders_are_all_known(self):
        sb = Sandbox()
        self.addCleanup(sb.cleanup)
        ctx = sb.ctx()
        for mod in self.m["modules"]:
            for step in mod.get("steps", []):
                for key in ("dest", "path"):
                    if key in step:
                        ctx.fmt(step[key])
            for v in mod.get("verify", []):
                for val in v.values():
                    ctx.fmt(val)

    def test_unit_templates_use_only_known_tokens(self):
        sb = Sandbox()
        self.addCleanup(sb.cleanup)
        ctx = sb.ctx()
        for name in sorted(os.listdir(os.path.join(KIT, "systemd"))):
            with open(os.path.join(KIT, "systemd", name), encoding="utf-8") as f:
                text = ctx.render_unit(f.read())
            self.assertNotRegex(text, r"@[A-Z_]+@", name)
            self.assertIn("[Service]", text)
            self.assertNotIn("\n\n\n", text)

    def test_every_skill_module_points_at_a_real_skill_and_all_skills_have_a_module(self):
        skills = {m["skill"] for m in self.m["modules"] if m.get("skill")}
        on_disk = set(os.listdir(os.path.join(KIT, "skills", "companion")))
        self.assertEqual(skills, on_disk)
        self.assertEqual(len(self.m["by_id"]["skills"]["children"]), len(on_disk))

    def test_hub_is_required_and_everything_else_optional(self):
        self.assertTrue(self.m["by_id"]["hub"]["required"])
        self.assertEqual([m["id"] for m in self.m["modules"] if m.get("required")], ["hub"])


class SkillStandardTest(unittest.TestCase):
    def test_shipped_skills_pass_the_hermes_standard(self):
        paths, errs = check_skills.check_dir(os.path.join(KIT, "skills"))
        self.assertEqual(10, len(paths))
        self.assertEqual([], errs)

    def _skill(self, extra_fm="", body=None, name="demo"):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        os.makedirs(os.path.join(d, name))
        fm = "name: %s\ndescription: Do the demo thing.\nversion: 1.0.0\nauthor: Me\nlicense: MIT\nplatforms: [linux]\nmetadata:\n  hermes:\n    tags: [A]\n" % name
        body = body if body is not None else "# Demo\n\n## When to Use\n\nx\n\n## Pitfalls\n\nx\n\n## Verification\n\nx\n"
        with open(os.path.join(d, name, "SKILL.md"), "w") as f:
            f.write("---\n" + fm + extra_fm + "---\n\n" + body)
        return d

    def test_a_good_skill_passes(self):
        _p, errs = check_skills.check_dir(self._skill())
        self.assertEqual([], errs)

    def test_description_rules(self):
        d = self._skill()
        p = os.path.join(d, "demo", "SKILL.md")
        text = open(p).read().replace("Do the demo thing.", "A powerful and comprehensive way to do the demo thing in many ways")
        open(p, "w").write(text)
        errs = check_skills.check_dir(d)[1]
        self.assertTrue(any("hardline 60" in e for e in errs))
        self.assertTrue(any("marketing" in e for e in errs))
        self.assertTrue(any("period" in e for e in errs))

    def test_missing_sections_host_names_and_local_paths_are_caught(self):
        d = self._skill(body="# Demo\n\nRuns on myserver.tail1234.ts.net from /home/bob/project/x and 192.168.1.5.\n")
        errs = " | ".join(check_skills.check_dir(d)[1])
        for needle in ("When to Use", "Pitfalls", "Verification", "host-specific", "machine-local", "private IP"):
            self.assertIn(needle, errs)

    def test_name_must_match_directory_and_related_skills_must_exist(self):
        d = self._skill(extra_fm="")
        p = os.path.join(d, "demo", "SKILL.md")
        text = open(p).read().replace("name: demo", "name: other").replace("tags: [A]", "tags: [A]\n    related_skills: [ghost]")
        open(p, "w").write(text)
        errs = " | ".join(check_skills.check_dir(d)[1])
        self.assertIn("does not match the directory", errs)
        self.assertIn("ghost", errs)

    def test_continuation_comment_and_secrets_are_caught(self):
        body = "# Demo\n\n## When to Use\n\nx\n\n## Pitfalls\n\nx\n\n## Verification\n\n```bash\ncurl \\ # nope\n  -H 'Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789'\n```\n"
        errs = " | ".join(check_skills.check_dir(self._skill(body=body))[1])
        self.assertIn("line-continuation", errs)
        self.assertIn("secret", errs)

    def test_yaml_subset_parser(self):
        fm = check_skills.parse_yaml('a: 1\nb: "two, three"\nc: [x, "y z", 3]\nd: >\n  folded\n  text\ne:\n  f:\n    - one\n    - two\n  g: true\nh:\n  - name: N\n    prompt: P\n')
        self.assertEqual("two, three", fm["b"])
        self.assertEqual(["x", "y z", "3"], fm["c"])
        self.assertEqual("folded text", fm["d"])
        self.assertEqual(["one", "two"], fm["e"]["f"])
        self.assertIs(True, fm["e"]["g"])
        self.assertEqual([{"name": "N", "prompt": "P"}], fm["h"])


class EnvFileTest(unittest.TestCase):
    def test_apply_env_replaces_uncomments_and_appends(self):
        text = "A=1\n# B=2\nC=3\n"
        out = install.apply_env(text, {"A": "x", "B": "y", "D": "z"})
        self.assertEqual("A=x\nB=y\nC=3\nD=z\n", out)
        self.assertEqual("x", install.read_env_value(out, "A"))
        self.assertIsNone(install.read_env_value(out, "Q"))


class SelectionTest(unittest.TestCase):
    def setUp(self):
        self.sb = Sandbox()
        self.addCleanup(self.sb.cleanup)
        self.inst = self.sb.installer()

    def test_hub_is_pulled_in_and_goes_first(self):
        ids = self.inst.resolve(["skill-deploy", "slash"])
        self.assertEqual(["hub", "skill-deploy", "slash"], ids)

    def test_group_expands_and_short_skill_names_work(self):
        self.assertEqual(11, len(self.inst.resolve(["skills"])))
        self.assertEqual(["hub", "skill-travel"], self.inst.resolve(["travel"]))

    def test_unknown_module_is_a_clear_error(self):
        with self.assertRaises(install.KitError) as cm:
            self.inst.resolve(["nope"])
        self.assertIn("nope", str(cm.exception))

    def test_cli_selection_flags(self):
        args = install.build_parser().parse_args(["--preset", "recommended", "--modules", "slash", "--skills", "deploy"])
        sel = install.selection_from_args(self.sb.manifest, args)
        self.assertEqual(["hub", "skills", "phone-mcp", "slash", "hub", "skill-deploy"], sel)

    def test_set_parsing(self):
        self.assertEqual({"APP_REPO": "a/b"}, install.parse_settings(["APP_REPO=a/b"]))
        with self.assertRaises(install.KitError):
            install.parse_settings(["bad"])


class InstallTest(unittest.TestCase):
    def setUp(self):
        self.sb = Sandbox()
        self.addCleanup(self.sb.cleanup)

    def run_install(self, wanted, **kw):
        inst = self.sb.installer(**kw)
        ids = inst.resolve(wanted)
        inst.install(ids)
        return inst, ids

    def test_recommended_install_lays_down_everything(self):
        inst, ids = self.run_install(["hub", "skills", "phone-mcp"])
        sb = self.sb
        self.assertTrue(os.path.exists(sb.path("/home/u/.local/share/hermes-hub/hub.py")))
        self.assertTrue(os.access(sb.path("/home/u/.local/bin/hermes-notify"), os.X_OK))
        self.assertEqual(0o600, os.stat(sb.path("/home/u/.local/share/hermes-hub/env")).st_mode & 0o777)
        for s in ("phone-agent", "deploy", "travel"):
            self.assertTrue(os.path.exists(sb.path("/home/u/.hermes/skills/companion/%s/SKILL.md" % s)), s)
        env = sb.read("/home/u/.local/share/hermes-hub/env")
        self.assertIn("HERMES_ENV=/home/u/.hermes/.env", env)
        self.assertIn("HERMES_HOME=/home/u/.hermes", env)
        self.assertNotIn("<user>", env)
        unit = sb.read("/home/u/.config/systemd/user/hermes-hub.service")
        self.assertIn("ExecStart=/usr/bin/python3 /home/u/.local/share/hermes-hub/hub.py", unit)
        self.assertIn("WantedBy=default.target", unit)
        state = json.loads(sb.read("/home/u/.local/share/hermes-hub/kit-state.json"))
        self.assertEqual({"hub", "phone-mcp"} | {"skill-" + s for s in ("phone-agent", "deploy", "travel")}, set(state["modules"]) & ({"hub", "phone-mcp"} | {"skill-" + s for s in ("phone-agent", "deploy", "travel")}))
        self.assertEqual(12, len(state["modules"]))

    def test_second_run_changes_nothing(self):
        self.run_install(["hub", "skills"])
        before = self.sb.tree()
        inst, _ = self.run_install(["hub", "skills"])
        self.assertEqual(before, self.sb.tree())  # no backups, no .kit-new
        self.assertEqual({"unchanged"}, {e.status for e in inst.events if e.kind in ("file", "config", "secret")})

    def test_dry_run_writes_nothing(self):
        inst = self.sb.installer(dry_run=True)
        inst.install(inst.resolve(["hub", "skills", "slash"]))
        self.assertEqual([], self.sb.tree())
        self.assertTrue(any(e.status == "created" for e in inst.events))

    def test_edited_skill_is_kept_and_the_new_version_saved_beside_it(self):
        self.run_install(["hub", "skill-deploy"])
        p = "/home/u/.hermes/skills/companion/deploy/SKILL.md"
        with open(self.sb.path(p), "a") as f:
            f.write("\n| `mine` | `/srv/mine` | `run.sh` |\n")
        edited = self.sb.read(p)
        with mock.patch.object(install, "KIT", KIT):
            # make the kit version differ from what is installed so the update path is exercised
            src = os.path.join(KIT, "skills", "companion", "deploy", "SKILL.md")
            real = open(src, "rb").read()
            try:
                open(src, "wb").write(real + b"\nNew kit text.\n")
                inst, _ = self.run_install(["hub", "skill-deploy"])
            finally:
                open(src, "wb").write(real)
        self.assertEqual(edited, self.sb.read(p))
        self.assertIn("New kit text.", self.sb.read(p + ".kit-new"))
        self.assertTrue(any(e.status == "kept" for e in inst.events))

    def test_untouched_skill_updates_in_place_without_a_backup(self):
        self.run_install(["hub", "skill-deploy"])
        src = os.path.join(KIT, "skills", "companion", "deploy", "SKILL.md")
        real = open(src, "rb").read()
        try:
            open(src, "wb").write(real + b"\nNew kit text.\n")
            self.run_install(["hub", "skill-deploy"])
        finally:
            open(src, "wb").write(real)
        p = "/home/u/.hermes/skills/companion/deploy/SKILL.md"
        self.assertIn("New kit text.", self.sb.read(p))
        self.assertFalse([f for f in self.sb.tree() if "bak-kit" in f or f.endswith(".kit-new")])

    def test_settings_are_written_masked_in_output_and_backed_up(self):
        self.run_install(["hub"])
        lines = []
        inst = self.sb.installer(settings={"APP_REPO": "me/app", "HERMES_RELAY_KEY": "supersecret"}, out=lines.append)
        inst.install(inst.resolve(["hub"]))
        env = self.sb.read("/home/u/.local/share/hermes-hub/env")
        self.assertIn("APP_REPO=me/app", env)
        self.assertIn("HERMES_RELAY_KEY=supersecret", env)
        self.assertNotIn("supersecret", "\n".join(lines))
        self.assertTrue([f for f in self.sb.tree() if f.endswith(".bak-kit-" + inst.stamp) or "env.bak-kit" in f])

    def test_existing_env_keeps_the_users_values(self):
        self.run_install(["hub"])
        p = self.sb.path("/home/u/.local/share/hermes-hub/env")
        text = open(p).read().replace("PORT=8791", "PORT=9999")
        open(p, "w").write(text)
        self.run_install(["hub"])
        self.assertIn("PORT=9999", self.sb.read("/home/u/.local/share/hermes-hub/env"))

    def test_allowlist_can_be_seeded_but_never_overwritten(self):
        inst = self.sb.installer(allow_units=["nginx", "docker"])
        inst.install(inst.resolve(["hub"]))
        text = self.sb.read("/home/u/.local/share/hermes-hub/ops-allowlist.txt")
        self.assertIn("\nnginx\n", text)
        inst2 = self.sb.installer(allow_units=["other"])
        inst2.install(inst2.resolve(["hub"]))
        self.assertNotIn("other", self.sb.read("/home/u/.local/share/hermes-hub/ops-allowlist.txt"))

    def test_missing_python_module_blocks_slash_unless_forced(self):
        inst = self.sb.installer()
        with mock.patch.object(install.Installer, "_have_pymodule", return_value=False), \
                mock.patch.object(install.Installer, "_have_command", return_value=True):
            with self.assertRaises(install.KitError) as cm:
                inst.install(inst.resolve(["slash"]))
        self.assertIn("websockets", str(cm.exception))
        self.assertEqual([], self.sb.tree())

    def test_slash_creates_a_private_token_once(self):
        with mock.patch.object(install.Installer, "_have_pymodule", return_value=True), \
                mock.patch.object(install.Installer, "_have_command", return_value=True):
            self.run_install(["slash"])
            p = self.sb.path("/home/u/.hermes/secrets/hermes-serve.token")
            first = open(p).read()
            self.assertEqual(0o600, os.stat(p).st_mode & 0o777)
            self.run_install(["slash"])
        self.assertEqual(first, open(p).read())
        unit = self.sb.read("/home/u/.config/systemd/user/hermes-serve.service")
        self.assertIn("/home/u/.hermes/secrets/hermes-serve.token", unit)

    def test_voice_without_deps_copies_files_but_starts_nothing(self):
        inst, _ = self.run_install(["voice-stt", "voice-tts"])
        self.assertTrue(os.path.exists(self.sb.path("/home/u/.local/share/hermes-voice/parakeet_server.py")))
        self.assertFalse(os.path.exists(self.sb.path("/home/u/.local/share/hermes-voice/venv-stt")))
        self.assertTrue(any(e.kind == "venv" and e.status == "skipped" for e in inst.events))
        self.assertTrue(any(e.kind == "download" and e.status == "skipped" for e in inst.events))
        env = self.sb.read("/home/u/.local/share/hermes-voice/voice.env")
        self.assertIn("PARAKEET_DEVICE=cpu", env)
        self.assertIn("KOKORO_VOICE=af_heart", env)
        tts = self.sb.read("/home/u/.config/systemd/user/hermes-voice-tts.service")
        self.assertIn("KOKORO_DIR=/home/u/.local/share/hermes-voice/kokoro", tts)
        self.assertNotIn("User=", tts)

    def test_voice_with_proxy_moves_the_speech_server_to_10301(self):
        ctx = self.sb.ctx(with_proxy=True)
        self.assertEqual("10301", ctx.v["stt_port"])

    def test_gpu_unit_gets_library_path_only_when_the_venv_has_nvidia_libs(self):
        sb = self.sb
        site = sb.path("/home/u/.local/share/hermes-voice/venv-stt/lib/python3.12/site-packages/nvidia/cudnn/lib")
        os.makedirs(site)
        ctx = sb.ctx(stt_device="gpu")
        line = ctx.ld_line()
        self.assertTrue(line.startswith("Environment=LD_LIBRARY_PATH="))
        self.assertIn("/nvidia/cudnn/lib", line)
        self.assertEqual("", sb.ctx(stt_device="cpu").ld_line())

    def test_system_scope_runs_the_service_as_the_chosen_user(self):
        ctx = install.Ctx(self.sb.manifest, scope="system", prefix=self.sb.prefix, hermes_home=self.sb.hermes, service_user="media", home="/home/media")
        unit = ctx.render_unit(open(os.path.join(KIT, "systemd", "hermes-voice-tts.service")).read())
        self.assertIn("User=media", unit)
        self.assertIn("WantedBy=multi-user.target", unit)
        self.assertEqual("/opt/hermes-hub", ctx.v["hub_dir"])

    def test_pve_json_needs_both_values(self):
        inst, _ = self.run_install(["proxmox"])
        self.assertFalse(os.path.exists(self.sb.path("/home/u/.local/share/hermes-hub/pve.json")))
        inst2 = self.sb.installer(pve={"ssh": "root@10.0.0.2", "key": "/k"})
        inst2.install(inst2.resolve(["proxmox"]))
        self.assertEqual({"ssh": "root@10.0.0.2", "key": "/k"}, json.loads(self.sb.read("/home/u/.local/share/hermes-hub/pve.json")))

    def test_codex_module_installs_bridge_command_and_codex_skill(self):
        with mock.patch.object(install.Installer, "_have_pymodule", return_value=True), \
                mock.patch.object(install.Installer, "_have_command", return_value=True):
            self.run_install(["codex"])
        self.assertTrue(os.path.exists(self.sb.path("/home/u/.local/share/hermes-codex-bridge/codex_bridge.py")))
        self.assertTrue(os.access(self.sb.path("/home/u/.local/bin/codex-rc"), os.X_OK))
        self.assertTrue(os.path.exists(self.sb.path("/home/u/.codex/skills/rc/SKILL.md")))
        unit = self.sb.read("/home/u/.config/systemd/user/hermes-codex-bridge.service")
        self.assertIn("/home/u/.local/share/hermes-codex-bridge/codex_bridge.py", unit)

    def test_claude_bridge_copies_sources_and_makes_a_token_but_skips_the_venv_without_deps(self):
        inst, _ = self.run_install(["claude-bridge"])
        base = "/home/u/.local/share/hermes-claude-bridge"
        self.assertTrue(os.path.exists(self.sb.path(base + "/bridge/claude_bridge.py")))
        self.assertTrue(os.path.exists(self.sb.path(base + "/bridge/codex_bridge.py")))  # claude_bridge imports Run and the steer markers
        self.assertFalse([f for f in self.sb.tree() if "test_" in f])
        self.assertRegex(self.sb.read(base + "/env"), r"CLAUDE_BRIDGE_TOKEN=\S{20,}")

    def test_verify_reports_missing_and_present_files(self):
        inst, ids = self.run_install(["hub", "skill-deploy"])
        res = inst.verify(ids)
        self.assertTrue(all(ok for ok, _m, _w in res), res)
        os.remove(self.sb.path("/home/u/.hermes/skills/companion/deploy/SKILL.md"))
        res = inst.verify(["skill-deploy"])
        self.assertFalse(res[0][0])


class UninstallTest(unittest.TestCase):
    def setUp(self):
        self.sb = Sandbox()
        self.addCleanup(self.sb.cleanup)
        inst = self.sb.installer()
        inst.install(inst.resolve(["hub", "skills", "phone-mcp"]))

    def uninstall(self, ids, **kw):
        purge = kw.pop("purge", False)
        inst = self.sb.installer(**kw)
        inst.uninstall(inst.resolve(ids, deps=False), purge=purge)
        return inst

    def test_removes_files_but_keeps_configuration(self):
        self.uninstall(["skill-deploy"])
        self.assertFalse(os.path.exists(self.sb.path("/home/u/.hermes/skills/companion/deploy")))
        self.assertTrue(os.path.exists(self.sb.path("/home/u/.hermes/skills/companion/travel/SKILL.md")))

    def test_hub_cannot_go_while_others_depend_on_it(self):
        inst = self.sb.installer()
        with self.assertRaises(install.KitError):
            inst.uninstall(["hub"])

    def test_full_uninstall_keeps_env_unless_purged(self):
        everything = list(self.sb.installer().installed())
        self.uninstall(everything)
        self.assertTrue(os.path.exists(self.sb.path("/home/u/.local/share/hermes-hub/env")))
        self.assertFalse(os.path.exists(self.sb.path("/home/u/.local/share/hermes-hub/hub.py")))
        self.assertFalse(os.path.exists(self.sb.path("/home/u/.local/share/hermes-hub/kit-state.json")))
        self.assertFalse(os.path.exists(self.sb.path("/home/u/.hermes/skills/companion")))

    def test_purge_deletes_configuration_too(self):
        everything = list(self.sb.installer().installed())
        self.uninstall(everything, purge=True)
        self.assertFalse(os.path.exists(self.sb.path("/home/u/.local/share/hermes-hub/env")))

    def test_edited_files_are_not_deleted(self):
        p = self.sb.path("/home/u/.hermes/skills/companion/deploy/SKILL.md")
        with open(p, "a") as f:
            f.write("\nmine\n")
        self.uninstall(["skill-deploy"])
        self.assertTrue(os.path.exists(p))

    def test_dry_run_removes_nothing(self):
        before = self.sb.tree()
        self.uninstall(["skill-deploy"], dry_run=True)
        self.assertEqual(before, self.sb.tree())


class MenuTest(unittest.TestCase):
    def setUp(self):
        self.sb = Sandbox()
        self.addCleanup(self.sb.cleanup)
        self.inst = self.sb.installer()

    def drive(self, answers, pre=None):
        it = iter(answers)
        return install.interactive_select(self.sb.manifest, self.inst, pre or [], lambda _p: next(it), quiet)

    def test_enter_accepts_the_defaults(self):
        got = self.drive([""])
        self.assertIn("hub", got)
        self.assertIn("phone-mcp", got)
        self.assertIn("skill-deploy", got)
        self.assertNotIn("slash", got)
        self.assertNotIn("claude-bridge", got)

    def test_toggle_by_number_and_pick_skills(self):
        top = [m for m in self.sb.manifest["modules"] if not m["id"].startswith("skill-")]
        n_slash = [m["id"] for m in top].index("slash") + 1
        n_skills = [m["id"] for m in top].index("skills") + 1
        got = self.drive(["n", str(n_slash), "s", "1 2", "", ""])
        self.assertIn("slash", got)
        self.assertIn("hub", got)
        self.assertEqual(2, len([g for g in got if g.startswith("skill-")]))
        got = self.drive([str(n_skills), ""])  # all ten were on: toggling the group turns them off
        self.assertEqual([], [g for g in got if g.startswith("skill-")])

    def test_all_excludes_experimental_and_quit_cancels(self):
        got = self.drive(["a", ""])
        self.assertIn("voice-tts", got)
        self.assertNotIn("claude-bridge", got)
        with self.assertRaises(install.KitError):
            self.drive(["q"])

    def test_required_hub_cannot_be_unticked(self):
        got = self.drive(["1", ""])
        self.assertIn("hub", got)


class SettingsPromptTest(unittest.TestCase):
    def setUp(self):
        self.sb = Sandbox()
        self.addCleanup(self.sb.cleanup)

    def test_questions_are_optional_and_feed_the_installer(self):
        inst = self.sb.installer()
        answers = iter(["https://relay.example", "relkey", "me/app", "", "nginx, docker", "root@10.0.0.2", "/k", "y", "y", "n"])
        got = install.ask_settings(self.sb.manifest, ["hub", "proxmox", "voice-stt", "voice-tts"], inst, lambda _p: next(answers), quiet)
        self.assertEqual({"HERMES_RELAY_URL": "https://relay.example", "HERMES_RELAY_KEY": "relkey", "APP_REPO": "me/app"}, got)
        self.assertEqual(["nginx", "docker"], inst.allow_units)
        self.assertEqual({"ssh": "root@10.0.0.2", "key": "/k"}, inst.pve)
        self.assertEqual("gpu", inst.ctx.stt_device)
        self.assertTrue(inst.deps)
        self.assertFalse(inst.models)

    def test_enter_skips_everything(self):
        inst = self.sb.installer()
        got = install.ask_settings(self.sb.manifest, ["hub"], inst, lambda _p: "", quiet)
        self.assertEqual({}, got)
        self.assertIsNone(inst.allow_units or None)


class SystemdTest(unittest.TestCase):
    """The service steps, with a recording stand-in for systemctl."""

    def setUp(self):
        self.sb = Sandbox()
        self.addCleanup(self.sb.cleanup)
        self.calls = []
        self.active = set()
        patcher = mock.patch.object(install.shutil, "which", side_effect=lambda n: "/bin/" + n if n == "systemctl" else None)
        patcher.start()
        self.addCleanup(patcher.stop)
        have = mock.patch.object(install.Installer, "_have_command", return_value=True)
        have.start()
        self.addCleanup(have.stop)

    def runner(self, cmd, timeout=120, stdin=None, env=None):
        self.calls.append(cmd)
        rc = 0
        if "is-active" in cmd:
            rc = 0 if cmd[-1] in self.active else 3
        return subprocess.CompletedProcess(cmd, rc, "", "")

    def installer(self, **kw):
        return self.sb.installer(no_systemd=False, runner=self.runner, **kw)

    def verbs(self):
        return [" ".join(c[2:]) for c in self.calls if c[:2] == ["systemctl", "--user"]]

    def test_new_hub_is_reloaded_enabled_and_started(self):
        inst = self.installer()
        inst.install(inst.resolve(["hub"]))
        v = self.verbs()
        self.assertEqual("daemon-reload", v[0])
        self.assertIn("enable --now hermes-hub", v)

    def test_running_hub_restarts_only_when_its_files_changed(self):
        inst = self.installer()
        inst.install(inst.resolve(["hub"]))
        self.active = {"hermes-hub"}
        self.calls.clear()
        inst2 = self.installer()
        inst2.install(inst2.resolve(["hub"]))
        self.assertNotIn("restart hermes-hub", self.verbs())
        self.calls.clear()
        inst3 = self.installer(settings={"APP_REPO": "me/app"})
        inst3.install(inst3.resolve(["hub"]))
        self.assertIn("restart hermes-hub", self.verbs())

    def test_codex_bridge_unit_is_installed_but_not_started(self):
        with mock.patch.object(install.Installer, "_have_pymodule", return_value=True), mock.patch.object(install.Installer, "_have_command", return_value=True):
            inst = self.installer()
            inst.install(inst.resolve(["codex"]))
        self.assertNotIn("enable --now hermes-codex-bridge", self.verbs())

    def test_mcp_registration_is_pointed_at_the_chosen_hermes_home_only(self):
        seen = []

        def spy(cmd, timeout=120, stdin=None, env=None):
            seen.append((cmd, env))
            return subprocess.CompletedProcess(cmd, 0, "", "")
        inst = self.sb.installer(no_systemd=True, runner=spy)
        inst.ctx.prefix = ""  # an unstaged run (the staged one never calls hermes), with paths mapped into the sandbox
        step = {"do": "mcp", "name": "phone", "command": "{python}", "args": ["{hub_dir}/phone_mcp.py"]}
        with mock.patch.object(install.shutil, "which", side_effect=lambda n: "/bin/" + n), \
                mock.patch.object(inst.ctx, "host", side_effect=lambda p: self.sb.prefix + p):
            inst._do_mcp("phone-mcp", {"files": {}, "units": [], "config": []}, step)
        calls = [(c, e) for c, e in seen if c[:3] == ["hermes", "mcp", "add"]]
        self.assertEqual(1, len(calls))
        self.assertEqual({"HERMES_HOME": self.sb.hermes}, calls[0][1])

    def test_voice_services_wait_for_their_environments(self):
        inst = self.installer()
        inst.install(inst.resolve(["voice-stt", "voice-tts", "voice-proxy"]))
        v = self.verbs()
        self.assertNotIn("enable --now hermes-voice-stt", v)
        self.assertNotIn("enable --now hermes-voice-tts", v)
        self.assertIn("enable --now hermes-voice-proxy", v)

    def test_dry_run_runs_no_commands(self):
        inst = self.installer(dry_run=True)
        inst.install(inst.resolve(["hub"]))
        self.assertEqual([], self.calls)

    def test_uninstall_stops_and_disables_units(self):
        inst = self.installer()
        inst.install(inst.resolve(["hub"]))
        self.calls.clear()
        inst2 = self.installer()
        inst2.uninstall(["hub"])
        self.assertIn("disable --now hermes-hub", self.verbs())

    def test_failed_venv_reports_the_pip_error(self):
        def failing(cmd, timeout=120, stdin=None, env=None):
            return subprocess.CompletedProcess(cmd, 1, "ERROR: no matching distribution\n", "")
        inst = self.sb.installer(deps=True, no_systemd=True, runner=failing)
        with self.assertRaises(install.KitError) as cm:
            inst.install(inst.resolve(["voice-stt"]))
        self.assertIn("no matching distribution", str(cm.exception))


class CliTest(unittest.TestCase):
    def run_cli(self, argv, **kw):
        lines = []
        rc = install.main(argv, out=lines.append, **kw)
        return rc, "\n".join(lines)

    def test_list_json_is_machine_readable(self):
        rc, out = self.run_cli(["list", "--json", "--user", "--prefix", tempfile.mkdtemp()])
        self.assertEqual(0, rc)
        data = json.loads(out)
        ids = {m["id"] for m in data["modules"]}
        self.assertTrue({"hub", "skills", "voice-tts", "skill-travel"} <= ids)
        self.assertIn("recommended", data["presets"])

    def test_install_needs_a_selection_when_not_interactive(self):
        rc, out = self.run_cli(["--yes", "--user", "--prefix", tempfile.mkdtemp()])
        self.assertEqual(1, rc)
        self.assertIn("nothing selected", out)

    def test_preset_install_and_status_and_verify_end_to_end(self):
        sb = Sandbox()
        self.addCleanup(sb.cleanup)
        base = ["--user", "--prefix", sb.prefix, "--hermes-home", sb.hermes, "--service-user", "u"]
        with mock.patch.object(install.Ctx, "__init__", wraps=install.Ctx.__init__, autospec=True) as init:
            rc, out = self.run_cli(["--preset", "minimal", "--yes"] + base)
        self.assertEqual(0, rc, out)
        rc, out = self.run_cli(["status"] + base)
        self.assertIn("hub", out)
        rc, out = self.run_cli(["verify"] + base)
        self.assertEqual(0, rc, out)
        rc, out = self.run_cli(["uninstall", "--yes"] + base)
        self.assertEqual(0, rc, out)
        rc, out = self.run_cli(["status"] + base)
        self.assertIn("Nothing from the kit is installed", out)

    def test_check_command_validates_the_skills(self):
        rc, out = self.run_cli(["check"])
        self.assertEqual(0, rc)
        self.assertIn("10 skills checked, 0 problems", out)

    def test_system_install_without_root_is_refused_with_advice(self):
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            self.skipTest("running as root")
        rc, out = self.run_cli(["--system", "--preset", "minimal", "--yes"])
        self.assertEqual(1, rc)
        self.assertIn("--user", out)

    def test_json_install_prints_only_json(self):
        sb = Sandbox()
        self.addCleanup(sb.cleanup)
        rc, out = self.run_cli(["--preset", "minimal", "--yes", "--json", "--user", "--prefix", sb.prefix, "--hermes-home", sb.hermes, "--service-user", "u"])
        self.assertEqual(0, rc, out)
        data = json.loads(out)
        self.assertEqual(["hub"], data["modules"])
        self.assertTrue(data["events"])


class DocsTest(unittest.TestCase):
    def test_readme_mentions_every_module(self):
        readme = open(os.path.join(KIT, "README.md"), encoding="utf-8").read()
        for mod in install.load_manifest()["modules"]:
            if not mod["id"].startswith("skill-"):
                self.assertTrue(mod["id"] in readme, "README does not mention " + mod["id"])

    def test_shipped_scripts_compile(self):
        import py_compile
        for rel in ("install.py", "check_skills.py", "hub.py", "phone_mcp.py", "voice/parakeet_server.py", "voice/piper_server.py", "voice/whisper-proxy.py", "bin/codex-rc"):
            py_compile.compile(os.path.join(KIT, rel), cfile=os.path.join(tempfile.mkdtemp(), "x.pyc"), doraise=True)


if __name__ == "__main__":
    unittest.main()
