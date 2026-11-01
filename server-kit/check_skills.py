#!/usr/bin/env python3
"""Checks the kit's SKILL.md files against the Hermes skill authoring standard (stdlib only).

    python3 check_skills.py [skills_dir]      # default: skills/ next to this file

The rules mirror hermes-agent's tests/skills/test_authoring_standards.py (required fields, description
hardline, name == directory, resolvable related_skills, no machine-local paths, size, paste-safe shell
snippets) plus a few kit rules: standard sections, no host-specific names, no pasted secrets.
Exit status 0 when every skill passes.
"""
import os
import re
import sys

REQUIRED = ("name", "description", "version", "author", "license", "platforms")
PLATFORMS = {"linux", "macos", "windows"}
MARKETING = re.compile(r"\b(powerful|comprehensive|seamless|revolutionary|cutting-edge|state-of-the-art)\b", re.I)
MACHINE_LOCAL = re.compile(r"/home/(?!runner\b)[a-z0-9_-]+/|[A-Z]:\\+Users\\+(?!<)")
HOST_SPECIFIC = re.compile(r"\b(openclaw|stormnode|storm546)\b", re.I)
SECRET = re.compile(r"(sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|Bearer\s+[A-Za-z0-9_\-]{24,})")
PRIVATE_IP = re.compile(r"\b(?:192\.168|10\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b")
SHELL_FENCE_OPEN = re.compile(r"^\s*(`{3,}|~{3,})\s*(bash|sh|shell|zsh|console)\b", re.I)
CONTINUATION_THEN_COMMENT = re.compile(r"\\\s+#")
SECTIONS = ("## When to Use", "## Pitfalls", "## Verification")
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")


# ── a small YAML subset: enough for SKILL.md frontmatter, no dependencies ─────────────────────────

def _scalar(text):
    t = text.strip()
    if not t:
        return ""
    if t[0] in "\"'" and t[-1] == t[0] and len(t) >= 2:
        inner = t[1:-1]
        return inner.replace('\\"', '"').replace("\\\\", "\\") if t[0] == '"' else inner.replace("''", "'")
    if t[0] == "[" and t[-1] == "]":
        inner = t[1:-1].strip()
        return [_scalar(p) for p in _split_flow(inner)] if inner else []
    low = t.lower()
    if low in ("true", "false"):
        return low == "true"
    if low in ("null", "~"):
        return None
    return t


def _split_flow(s):
    parts, depth, cur, quote = [], 0, "", ""
    for ch in s:
        if quote:
            cur += ch
            if ch == quote:
                quote = ""
        elif ch in "\"'":
            quote = ch
            cur += ch
        elif ch == "[":
            depth += 1
            cur += ch
        elif ch == "]":
            depth -= 1
            cur += ch
        elif ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return parts


def parse_yaml(text):
    """Mappings, block and flow lists, quoted and folded (>) scalars. Raises ValueError on anything stranger."""
    lines = [l.rstrip("\n") for l in text.split("\n")]
    lines = [l for l in lines if l.strip() and not l.lstrip().startswith("#")]
    value, pos = _block(lines, 0, 0)
    if pos != len(lines):
        raise ValueError("unparsed frontmatter near: %r" % lines[pos])
    return value


def _indent(line):
    return len(line) - len(line.lstrip(" "))


def _block(lines, pos, indent):
    if pos >= len(lines):
        return {}, pos
    if lines[pos].lstrip().startswith("- "):
        out = []
        while pos < len(lines) and _indent(lines[pos]) == indent and lines[pos].lstrip().startswith("- "):
            item = lines[pos].lstrip()[2:]
            if re.match(r"^[A-Za-z_][\w-]*:(\s|$)", item):  # a list of mappings: treat the item as the first key line
                lines[pos] = " " * (indent + 2) + item
                value, pos = _block(lines, pos, indent + 2)
                out.append(value)
            else:
                out.append(_scalar(item))
                pos += 1
        return out, pos
    out = {}
    while pos < len(lines) and _indent(lines[pos]) == indent:
        m = re.match(r"^(\s*)([A-Za-z_][\w-]*):(?:\s+(.*))?$", lines[pos])
        if not m:
            raise ValueError("cannot parse line: %r" % lines[pos])
        key, rest = m.group(2), (m.group(3) or "").strip()
        pos += 1
        if rest in (">", ">-", "|", "|-"):
            chunk = []
            while pos < len(lines) and _indent(lines[pos]) > indent:
                chunk.append(lines[pos].strip())
                pos += 1
            out[key] = (" " if rest.startswith(">") else "\n").join(chunk)
        elif rest:
            out[key] = _scalar(rest)
        elif pos < len(lines) and (_indent(lines[pos]) > indent or (_indent(lines[pos]) == indent and lines[pos].lstrip().startswith("- "))):
            out[key], pos = _block(lines, pos, _indent(lines[pos]))
        else:
            out[key] = None
    return out, pos


def split_frontmatter(content):
    """(frontmatter dict, body) or raises ValueError."""
    if not content.startswith("---"):
        raise ValueError("SKILL.md must start with ---")
    m = re.search(r"\n---\s*\n", content[3:])
    if not m:
        raise ValueError("unclosed frontmatter")
    fm = parse_yaml(content[3:m.start() + 3].strip("\n"))
    if not isinstance(fm, dict):
        raise ValueError("frontmatter must be a mapping")
    return fm, content[m.end() + 3:]


def read_skill(path):
    with open(path, encoding="utf-8") as f:
        return split_frontmatter(f.read())


# ── the checks ────────────────────────────────────────────────────────────────────────────────────

def _shell_lines(content):
    fence = None
    for i, line in enumerate(content.splitlines(), 1):
        if fence is None:
            m = SHELL_FENCE_OPEN.match(line)
            if m:
                fence = m.group(1)
            continue
        if line.strip().startswith(fence):
            fence = None
            continue
        yield i, line


def check_skill(path, all_names):
    """List of problems for one SKILL.md (empty when it passes)."""
    name_dir = os.path.basename(os.path.dirname(path))
    try:
        with open(path, encoding="utf-8") as f:
            content = f.read()
        fm, _ = split_frontmatter(content)
    except (ValueError, OSError) as e:
        return ["%s: %s" % (name_dir, e)]
    errs = []

    def bad(msg):
        errs.append("%s: %s" % (name_dir, msg))

    for field in REQUIRED:
        if field not in fm or fm[field] in (None, "", []):
            bad("missing frontmatter field %r" % field)
    if fm.get("name") != name_dir:
        bad("name %r does not match the directory" % fm.get("name"))
    desc = str(fm.get("description") or "")
    if len(desc) > 60:
        bad("description is %d characters (hardline 60)" % len(desc))
    if desc and not desc.rstrip().endswith("."):
        bad("description must end with a period")
    if MARKETING.search(desc):
        bad("marketing word in description: %r" % MARKETING.search(desc).group(0))
    if fm.get("version") and not SEMVER.match(str(fm["version"])):
        bad("version %r is not x.y.z" % fm["version"])
    plats = fm.get("platforms")
    if isinstance(plats, list):
        unknown = [p for p in plats if p not in PLATFORMS]
        if unknown:
            bad("unknown platforms %s (valid: %s)" % (unknown, sorted(PLATFORMS)))
    hermes = (fm.get("metadata") or {}).get("hermes") or {}
    if not (hermes.get("tags") or fm.get("tags")):
        bad("no tags (metadata.hermes.tags)")
    for rel in hermes.get("related_skills") or []:
        if rel not in all_names:
            bad("related_skills entry %r does not exist in this kit" % rel)
    pre = fm.get("prerequisites") or {}
    for cmd in pre.get("commands") or []:
        if not re.match(r"^[\w.+-]+$", str(cmd)):
            bad("prerequisites.commands entry %r is not a bare command name" % cmd)
    for env in fm.get("required_environment_variables") or []:
        if not isinstance(env, dict) or not env.get("name") or not env.get("prompt"):
            bad("required_environment_variables entries need name and prompt")
    if MACHINE_LOCAL.search(content):
        bad("machine-local path %r" % MACHINE_LOCAL.search(content).group(0))
    if HOST_SPECIFIC.search(content):
        bad("host-specific name %r (kit skills must be generic)" % HOST_SPECIFIC.search(content).group(0))
    if SECRET.search(content):
        bad("something that looks like a secret is pasted into the skill")
    if PRIVATE_IP.search(content):
        bad("private IP address %r (use a placeholder)" % PRIVATE_IP.search(content).group(0))
    if len(content) > 100_000:
        bad("larger than 100k characters, split into references/")
    for i, line in _shell_lines(content):
        if CONTINUATION_THEN_COMMENT.search(line):
            bad("line %d: a comment after a line-continuation backslash breaks pasted commands" % i)
    for section in SECTIONS:
        if section not in content:
            bad("missing section %r" % section)
    if not re.search(r"^# \S", content.split("\n---", 1)[-1], re.M):
        bad("no top-level title")
    return errs


def skill_paths(skills_dir):
    out = []
    for root, _dirs, files in os.walk(skills_dir):
        if "SKILL.md" in files:
            out.append(os.path.join(root, "SKILL.md"))
    return sorted(out)


def check_dir(skills_dir):
    paths = skill_paths(skills_dir)
    names = {os.path.basename(os.path.dirname(p)) for p in paths}
    errs = []
    for p in paths:
        errs.extend(check_skill(p, names))
    return paths, errs


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    target = argv[1] if len(argv) > 1 else os.path.join(here, "skills")
    paths, errs = check_dir(target)
    for e in errs:
        print("FAIL", e)
    print("%d skills checked, %d problems" % (len(paths), len(errs)))
    return 1 if errs or not paths else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
