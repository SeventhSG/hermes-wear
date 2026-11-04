#!/usr/bin/env python3
"""hermes-hub: local relay between Hermes Agent and the Hermes Companion phone app.

Stdlib only. Listens on 0.0.0.0:$PORT (default 8791). Configuration comes from environment variables.
"""
import hashlib
import hmac
import json
import math
import mimetypes
import os
import re
import socket
import sqlite3
import subprocess
import threading
import time
import urllib.request
import urllib.error
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~")
HERMES_HOME = os.environ.get("HERMES_HOME", os.path.join(HOME, ".hermes"))
HUB_DIR = os.environ.get("HUB_DIR", "/opt/hermes-hub")
DB_PATH = os.environ.get("HUB_DB", os.path.join(HUB_DIR, "hub.db"))
HERMES_ENV = os.environ.get("HERMES_ENV", os.path.join(HERMES_HOME, ".env"))
NTFY_TOKEN_PATH = os.environ.get("NTFY_TOKEN_PATH", os.path.join(HUB_DIR, "ntfy.token"))
ALLOWLIST_PATH = os.environ.get("OPS_ALLOWLIST", os.path.join(HUB_DIR, "ops-allowlist.txt"))
HERMES_API = os.environ.get("HERMES_API", "http://127.0.0.1:8889")
NTFY_URL = os.environ.get("NTFY_URL", "http://127.0.0.1:8091")
NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "hermes-events")
CRON_OUTPUT_DIR = os.environ.get("CRON_OUTPUT_DIR", os.path.join(HERMES_HOME, "cron", "output"))
CRON_JOBS_JSON = os.environ.get("CRON_JOBS_JSON", os.path.join(HERMES_HOME, "cron", "jobs.json"))
PROFILES_DIR = os.environ.get("PROFILES_DIR", os.path.join(HERMES_HOME, "profiles"))
STATE_DB = os.environ.get("HERMES_STATE_DB", os.path.join(HERMES_HOME, "state.db"))
INCOMING_DIR = os.environ.get("INCOMING_DIR", os.path.join(HOME, "incoming", "phone"))
LISTEN_PORT = int(os.environ.get("PORT", "8791"))
HERMES_RELAY_URL = os.environ.get("HERMES_RELAY_URL", "").rstrip("/")
HERMES_RELAY_KEY = os.environ.get("HERMES_RELAY_KEY", "")
# Optional "decisions" forwarder (disabled unless all three are set)
DECISIONS_URL = os.environ.get("DECISIONS_URL", "")
DECISIONS_API_KEY = os.environ.get("DECISIONS_API_KEY", "")
DECISIONS_MODEL_PREFIX = os.environ.get("DECISIONS_MODEL_PREFIX", "")
KEY_CACHE_TTL = 600  # 10 minutes
POLL_INTERVAL = 60
CRON_RELAY_RATE_LIMIT = 1800  # seconds, per job_id (bypassed for ALERT/FAILED/ERROR)
CRON_ALERT_MARKERS = ("ALERT", "FAILED", "ERROR")
CRON_ALERT_RE = re.compile(r"\b(?:%s)\b" % "|".join(CRON_ALERT_MARKERS))
# The same problem is pushed again only after this long (or at once when it gets worse or a new problem joins it).
ALERT_COOLDOWN_S = int(float(os.environ.get("ALERT_COOLDOWN_HOURS", "6")) * 3600)

_key_cache = {}  # key -> expiry epoch
_key_cache_lock = threading.Lock()
_db_lock = threading.Lock()


def _read_ntfy_token():
    with open(NTFY_TOKEN_PATH) as f:
        return f.read().strip()


def db():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    os.makedirs(HUB_DIR, exist_ok=True)
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                priority INTEGER NOT NULL DEFAULT 3,
                session_id TEXT,
                job_id TEXT,
                url TEXT
            );
            CREATE TABLE IF NOT EXISTS push_endpoints (
                endpoint TEXT PRIMARY KEY,
                created_ts INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS seen_outputs (
                path TEXT PRIMARY KEY,
                seen_ts INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS unit_state (
                name TEXT PRIMARY KEY,
                last_state TEXT NOT NULL,
                updated_ts INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS job_output_state (
                job_id TEXT PRIMARY KEY,
                content_hash TEXT NOT NULL,
                last_relay_ts INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS alert_state (
                key TEXT PRIMARY KEY,
                last_ts INTEGER NOT NULL,
                sig TEXT NOT NULL DEFAULT '[]',
                count INTEGER NOT NULL DEFAULT 1
            );
            CREATE TABLE IF NOT EXISTS samples (ts INTEGER NOT NULL, key TEXT NOT NULL, value REAL NOT NULL);
            CREATE INDEX IF NOT EXISTS samples_key_ts ON samples (key, ts);
            """
        )


# ---------------------------------------------------------------- auth ----

def read_env_value(path, name):
    """One value from a Hermes .env (KEY=value, optionally quoted); "" when absent."""
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line.startswith(name + "="):
                    value = line[len(name) + 1:].strip()
                    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                        value = value[1:-1]
                    return value
    except OSError:
        pass
    return ""


def is_bridge_token(bearer):
    """True for the token of a local Codex or Claude Code bridge (the files codex-rc / claude-rc write)."""
    if not bearer:
        return False
    secrets_dir = os.path.join(os.environ.get("HERMES_HOME") or os.path.expanduser("~/.hermes"), "secrets")
    for fname, key in (("codex-bridge.env", "CODEX_BRIDGE_TOKEN"), ("claude-bridge.env", "CLAUDE_BRIDGE_TOKEN")):
        token = read_env_value(os.path.join(secrets_dir, fname), key)
        if token and hmac.compare_digest(token, bearer):
            return True
    return False


MTLS_DIR = os.environ.get("HUB_MTLS_DIR", os.path.expanduser("~/.hermes/secrets/mtls"))


def issue_device_cert(device):
    """A client certificate for one phone, signed by the device CA in MTLS_DIR: the public URL (nginx
    ssl_verify_client) and the TLS voice ports accept only these. The key is made here and sent once, over Tailscale."""
    import datetime
    from cryptography import x509
    from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    ca_pem = open(os.path.join(MTLS_DIR, "ca.pem"), "rb").read()
    ca = x509.load_pem_x509_certificate(ca_pem)
    ca_key = serialization.load_pem_private_key(open(os.path.join(MTLS_DIR, "ca.key"), "rb").read(), None)
    key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.datetime.now(datetime.timezone.utc)
    serial = x509.random_serial_number()
    cert = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, (device or "phone")[:60])]))
            .issuer_name(ca.subject).public_key(key.public_key()).serial_number(serial)
            .not_valid_before(now - datetime.timedelta(minutes=5)).not_valid_after(now + datetime.timedelta(days=365))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH]), critical=False)
            .sign(ca_key, hashes.SHA256()))
    with open(os.path.join(MTLS_DIR, "issued.log"), "a") as f:  # serial per device, for an nginx ssl_crl if one is lost
        f.write(f"{now.isoformat()} {serial:x} {device}\n")
    return {"ok": True, "cert": cert.public_bytes(serialization.Encoding.PEM).decode(), "ca": ca_pem.decode(),
            "key": key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode(),
            "expires": int(cert.not_valid_after_utc.timestamp())}


def is_authorized(bearer):
    if not bearer:
        return False
    now = time.time()
    with _key_cache_lock:
        exp = _key_cache.get(bearer)
        if exp and exp > now:
            return True
    req = urllib.request.Request(
        f"{HERMES_API}/v1/capabilities",
        headers={"Authorization": f"Bearer {bearer}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            ok = resp.status == 200
    except Exception:
        ok = False
    if not ok:
        # The app on a gateway profile (/p/<name>) carries that profile's key; it's the same owner's server.
        ok = any(hmac.compare_digest(bearer, k) for k in _profile_keys())
    if ok:
        with _key_cache_lock:
            _key_cache[bearer] = now + KEY_CACHE_TTL
    return ok


def _profile_keys():
    keys = []
    try:
        names = os.listdir(PROFILES_DIR)
    except OSError:
        return keys
    for name in names:
        key = read_env_value(os.path.join(PROFILES_DIR, name, ".env"), "API_SERVER_KEY")
        if key and len(key) >= 16:
            keys.append(key)
    return keys


# ------------------------------------------------------------- helpers ----

def load_allowlist():
    try:
        with open(ALLOWLIST_PATH) as f:
            return [ln.strip() for ln in f if ln.strip() and not ln.strip().startswith("#")]
    except FileNotFoundError:
        return []


def strip_markdown(text, limit=300):
    text = re.sub(r"[#*`_>\[\]()!-]", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:limit]


def ntfy_publish(title, body, priority, click_url):
    prio_map = {1: 1, 2: 2, 3: 3, 4: 4, 5: 5}
    headers = {
        "Title": title.encode("utf-8"),
        "Priority": str(prio_map.get(priority, 3)).encode("utf-8"),
    }
    try:
        headers["Authorization"] = f"Bearer {_read_ntfy_token()}".encode("utf-8")
    except Exception:
        pass
    if click_url:
        headers["Click"] = click_url.encode("utf-8")
    req = urllib.request.Request(
        f"{NTFY_URL}/{NTFY_TOPIC}", data=body.encode("utf-8"), headers=headers, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=5):
            pass
    except Exception:
        pass


def relay_forward(event):
    """Forward the event to the push relay (contract: POST /notify {title, body, type, priority,
    session_id, job_id, url}, Bearer key). Errors ignored."""
    if not (HERMES_RELAY_URL and HERMES_RELAY_KEY):
        return
    keys = ("title", "body", "type", "priority", "session_id", "job_id", "url")
    req = urllib.request.Request(
        f"{HERMES_RELAY_URL}/notify", data=json.dumps({k: event.get(k) for k in keys}).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {HERMES_RELAY_KEY}"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10):
            pass
    except Exception:
        pass


def deliver_to_endpoints(event):
    relay_forward(event)
    with db() as conn:
        endpoints = [r["endpoint"] for r in conn.execute("SELECT endpoint FROM push_endpoints")]
    payload = json.dumps(event).encode("utf-8")
    token = None
    try:
        token = _read_ntfy_token()
    except Exception:
        pass
    for ep in endpoints:
        headers = {"Content-Type": "application/json"}
        if token and (ep.startswith(NTFY_URL) or ":8091" in ep):
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(ep, data=payload, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=5):
                pass
        except Exception:
            pass


# ------------------------------------------------------ alert policy -----
# One real problem is one notification, not one per check: alerts are keyed, remembered in the database (so a hub
# restart does not repeat them), re-sent only when they change for the worse or after a cooldown, and can be muted.

_NUMBERS = re.compile(r"\d+(?:[.,]\d+)?")
_CLOCK_LINE = re.compile(r"^\s*(?:Time|Date|Timestamp):.*$", re.I | re.M)
_MUTES_LOCK = threading.Lock()


def mutes_path():
    return os.path.join(HUB_DIR, "alert-mutes.json")


def load_mutes(now=None):
    """{key or prefix*: until_epoch}; expired entries are dropped."""
    now = now or time.time()
    try:
        with open(mutes_path()) as f:
            data = json.load(f)
    except (OSError, ValueError):
        return {}
    return {k: v for k, v in data.items() if isinstance(v, (int, float)) and v > now}


def save_mutes(mutes):
    tmp = mutes_path() + ".tmp"
    with open(tmp, "w") as f:
        json.dump(mutes, f)
    os.replace(tmp, mutes_path())


def mute_alert(key, hours):
    with _MUTES_LOCK:
        mutes = load_mutes()
        mutes[key] = int(time.time() + max(0.0, float(hours)) * 3600)
        save_mutes(mutes)
    return mutes[key]


def unmute_alert(key):
    with _MUTES_LOCK:
        mutes = load_mutes()
        removed = mutes.pop(key, None) is not None
        save_mutes(mutes)
    return removed


def alert_muted(key, now=None):
    for k in load_mutes(now):
        if key == k or (k.endswith("*") and key.startswith(k[:-1])):
            return True
    return False


def alert_lines(text):
    """The distinct problems in an alert text with numbers and clock lines removed, so 'High load: 4.2' and 'High load: 7.9' match."""
    out = set()
    for line in _CLOCK_LINE.sub("", text or "").splitlines():
        line = _NUMBERS.sub("#", line).strip()
        if line:
            out.add(line)
    return sorted(out)


def alert_allowed(key, lines=(), cooldown=None, now=None):
    """True when this alert may be pushed now. Not muted, and either new, worse (a line it did not have before),
    or last sent longer ago than the cooldown. Records the push."""
    now = int(now if now is not None else time.time())
    cooldown = ALERT_COOLDOWN_S if cooldown is None else cooldown
    if alert_muted(key, now):
        return False
    lines = sorted(lines)
    with _db_lock, db() as conn:
        row = conn.execute("SELECT last_ts, sig, count FROM alert_state WHERE key=?", (key,)).fetchone()
        if row is not None:
            try:
                old = set(json.loads(row["sig"] or "[]"))
            except ValueError:
                old = set()
            if not (set(lines) - old) and now - row["last_ts"] < cooldown:
                return False
        conn.execute(
            "INSERT INTO alert_state (key, last_ts, sig, count) VALUES (?,?,?,1) "
            "ON CONFLICT(key) DO UPDATE SET last_ts=excluded.last_ts, sig=excluded.sig, count=count+1",
            (key, now, json.dumps(lines)),
        )
    return True


def alert_overview():
    with db() as conn:
        rows = [dict(r) for r in conn.execute("SELECT key, last_ts, count FROM alert_state ORDER BY last_ts DESC LIMIT 100")]
    return {"mutes": [{"key": k, "until": v} for k, v in sorted(load_mutes().items())], "state": rows}


# ------------------------------------------------------------- voice ----
VOICE_STT = os.environ.get("HUB_VOICE_STT", "127.0.0.1:10300")


def wyoming_stt(pcm, language="en", rate=16000):
    """One-shot Wyoming transcription of 16-bit mono PCM, for phones that can't reach the voice port
    (off the LAN without Tailscale: only the https URL is public)."""
    host, port = VOICE_STT.rsplit(":", 1)
    fmt = {"rate": rate, "width": 2, "channels": 1}
    with socket.create_connection((host, int(port)), 10) as s:
        s.settimeout(60)

        def send(type_, data=None, payload=b""):
            head = {"type": type_, "data": data or {}}
            if payload:
                head["payload_length"] = len(payload)
            s.sendall(json.dumps(head).encode() + b"\n" + payload)

        send("transcribe", {"language": language} if language and language != "auto" else {})
        send("audio-start", fmt)
        for i in range(0, len(pcm), 32000):
            send("audio-chunk", fmt, pcm[i:i + 32000])
        send("audio-stop")
        f = s.makefile("rb")
        while True:
            line = f.readline()
            if not line:
                raise ConnectionError("speech server closed the connection")
            head = json.loads(line)
            extra = head.get("data_length") or 0
            data = json.loads(f.read(extra)) if extra else head.get("data") or {}
            if head.get("payload_length"):
                f.read(head["payload_length"])
            if head.get("type") == "transcript":
                return data.get("text", "")



class LiveTake:
    """A streaming Wyoming take kept open on the hub so a phone without the voice port can still see live
    words: it POSTs audio every few hundred ms and each reply carries the newest partial."""
    takes = {}
    lock = threading.Lock()

    def __init__(self, language):
        host, port = VOICE_STT.rsplit(":", 1)
        self.sock = socket.create_connection((host, int(port)), 10)
        self.sock.settimeout(90)
        self.partial, self.endpoint, self.text, self.error = "", False, None, None
        self.done = threading.Event()
        self.used = time.time()
        self.fmt = {"rate": 16000, "width": 2, "channels": 1}
        self._send("transcribe", {"language": language} if language and language != "auto" else {})
        self._send("audio-start", self.fmt)
        threading.Thread(target=self._read, daemon=True).start()
        self.id = hashlib.sha256(os.urandom(16)).hexdigest()[:24]
        with LiveTake.lock:
            for k in [k for k, t in LiveTake.takes.items() if time.time() - t.used > 180]:
                LiveTake.takes.pop(k).close()
            LiveTake.takes[self.id] = self

    def _send(self, type_, data=None, payload=b""):
        head = {"type": type_, "data": data or {}}
        if payload:
            head["payload_length"] = len(payload)
        self.sock.sendall(json.dumps(head).encode() + b"\n" + payload)

    def _read(self):
        try:
            f = self.sock.makefile("rb")
            while True:
                line = f.readline()
                if not line:
                    raise ConnectionError("speech server closed the connection")
                head = json.loads(line)
                extra = head.get("data_length") or 0
                data = json.loads(f.read(extra)) if extra else head.get("data") or {}
                if head.get("payload_length"):
                    f.read(head["payload_length"])
                kind = head.get("type")
                if kind == "transcript-partial":
                    self.partial = data.get("text", "")
                elif kind == "endpoint":
                    self.endpoint = True
                elif kind == "transcript":
                    self.text = data.get("text", "")
                    return
        except Exception as e:
            self.error = str(e)
        finally:
            self.done.set()

    def audio(self, pcm):
        self.used = time.time()
        if pcm and not self.done.is_set():
            self._send("audio-chunk", self.fmt, pcm)
        return {"ok": self.error is None, "partial": self.partial, "endpoint": self.endpoint, "error": self.error}

    def stop(self):
        self._send("audio-stop")
        self.done.wait(60)
        self.close()
        with LiveTake.lock:
            LiveTake.takes.pop(self.id, None)
        if self.text is None:
            raise ConnectionError(self.error or "speech server timed out")
        return self.text

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


def emit_event(type_, title, body, priority=3, session_id=None, job_id=None, url=None, mute_key=None):
    ts = int(time.time())
    with _db_lock, db() as conn:
        cur = conn.execute(
            "INSERT INTO events (ts, type, title, body, priority, session_id, job_id, url) "
            "VALUES (?,?,?,?,?,?,?,?)",
            (ts, type_, title, body, priority, session_id, job_id, url),
        )
        event_id = cur.lastrowid
    event = {
        "id": event_id, "ts": ts, "type": type_, "title": title, "body": body,
        "priority": priority, "session_id": session_id, "job_id": job_id, "url": url,
    }
    if mute_key:
        event["mute_key"] = mute_key  # lets the phone offer "Mute for a day" on this notification
    click = url or ""
    if not click and session_id:
        click = f"hermes://session/{session_id}"
    elif not click and job_id:
        click = f"hermes://job/{job_id}"
    deliver_to_endpoints(event)
    ntfy_publish(title, body, priority, click)
    return event_id


# --------------------------------------------------------- background -----

def _cron_output_content(text):
    """Strip the '# Cron Job: ... **Status:** ...' header block, return the
    body after the '---' separator (empty string if there is none — a bare
    header means the job produced no output)."""
    lines = text.splitlines()
    if "---" in lines:
        return "\n".join(lines[lines.index("---") + 1:]).strip()
    return ""


def _cron_output_is_silent(text):
    """True if the header's Status line marks this as a no-op run
    (silent/empty output, or a watcher reporting no change)."""
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("**Status:**"):
            low = line.lower()
            return "silent" in low or "empty output" in low or "no_change" in low
    return False


def cron_output_worth_relaying(text):
    """Decide whether a cron output file has anything worth pushing to the
    phone: not silent/empty-marked, and has real content after the header."""
    if _cron_output_is_silent(text):
        return False, ""
    content = _cron_output_content(text)
    if not content.strip():
        return False, ""
    return True, content


def _iter_cron_output_files():
    for job_id in os.listdir(CRON_OUTPUT_DIR):
        job_dir = os.path.join(CRON_OUTPUT_DIR, job_id)
        if not os.path.isdir(job_dir):
            continue
        for fname in os.listdir(job_dir):
            fpath = os.path.join(job_dir, fname)
            if os.path.isfile(fpath):
                yield job_id, fpath


def _mark_backlog_seen():
    """On startup, mark every output file that already exists as seen, so a
    hub restart never replays the backlog."""
    now = int(time.time())
    with db() as conn:
        conn.executemany(
            "INSERT OR IGNORE INTO seen_outputs (path, seen_ts) VALUES (?,?)",
            [(fpath, now) for _job_id, fpath in _iter_cron_output_files()],
        )


def cron_relay_loop():
    _mark_backlog_seen()
    jobs_by_id = {}
    while True:
        try:
            with open(CRON_JOBS_JSON) as f:
                data = json.load(f)
            jobs_by_id = {j["id"]: j.get("name", j["id"]) for j in data.get("jobs", [])}
        except Exception:
            pass
        try:
            for job_id, fpath in _iter_cron_output_files():
                with db() as conn:
                    seen = conn.execute(
                        "SELECT 1 FROM seen_outputs WHERE path=?", (fpath,)
                    ).fetchone()
                if seen:
                    continue
                with db() as conn:
                    conn.execute(
                        "INSERT OR IGNORE INTO seen_outputs (path, seen_ts) VALUES (?,?)",
                        (fpath, int(time.time())),
                    )
                try:
                    with open(fpath, "r", errors="replace") as f:
                        text = f.read()
                except Exception:
                    text = ""

                worth, content = cron_output_worth_relaying(text)
                if not worth:
                    continue

                content_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
                is_alert = bool(CRON_ALERT_RE.search(content))
                now = int(time.time())
                with db() as conn:
                    prev = conn.execute(
                        "SELECT content_hash, last_relay_ts FROM job_output_state WHERE job_id=?",
                        (job_id,),
                    ).fetchone()
                if prev and prev["content_hash"] == content_hash:
                    continue  # no change since last relay for this job
                if not is_alert and prev and now - prev["last_relay_ts"] < CRON_RELAY_RATE_LIMIT:
                    continue  # rate-limited
                if is_alert and not alert_allowed("job:" + job_id, alert_lines(content)):
                    continue  # the same alert as before (only numbers or the clock changed), inside its cooldown, or muted

                job_name = jobs_by_id.get(job_id, job_id)
                emit_event(
                    "job", job_name, strip_markdown(content, 300),
                    priority=4 if is_alert else 3, job_id=job_id,
                    mute_key=("job:" + job_id) if is_alert else None,
                )
                with db() as conn:
                    conn.execute(
                        "INSERT INTO job_output_state (job_id, content_hash, last_relay_ts) "
                        "VALUES (?,?,?) ON CONFLICT(job_id) DO UPDATE SET "
                        "content_hash=excluded.content_hash, last_relay_ts=excluded.last_relay_ts",
                        (job_id, content_hash, now),
                    )
        except FileNotFoundError:
            pass
        except Exception:
            pass
        time.sleep(POLL_INTERVAL)


def systemctl_state(unit):
    try:
        out = subprocess.run(
            ["systemctl", "show", unit, "--property=ActiveState,SubState",
             "--no-page"], capture_output=True, text=True, timeout=10,
        ).stdout
        props = dict(line.split("=", 1) for line in out.strip().splitlines() if "=" in line)
        return props.get("ActiveState", "unknown"), props.get("SubState", "unknown")
    except Exception:
        return "unknown", "unknown"


def logs_after(name, is_container, after, lines):
    """Incremental logs: returns (lines, cursor). Empty `after` = last `lines` lines."""
    try:
        if is_container:
            cmd = ["docker", "logs", "--timestamps"] + (["--since", after] if after else ["--tail", str(lines)]) + [name]
            # stderr merged into stdout keeps docker's own ordering; untimestamped lines continue the previous entry.
            o = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=15)
            out, cur = [], after
            for ln in (o.stdout or "").splitlines():
                ts, _, msg = ln.partition(" ")
                if not re.match(r"\d{4}-\d\d-\d\dT", ts):
                    ts, msg = cur or "", ln
                if after and ts <= after:
                    continue  # --since is inclusive; skip already-seen
                out.append(msg)
                cur = max(cur, ts)
            return out, cur
        cmd = ["journalctl", "-u", name, "-o", "json", "--no-pager", "-n", str(5000 if after else lines)]
        if after:
            cmd.insert(-2, "--after-cursor=" + after)
        o = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        out, cur = [], after
        for ln in o.stdout.splitlines():
            try:
                e = json.loads(ln)
            except Exception:
                continue
            m = e.get("MESSAGE")
            if isinstance(m, list):
                m = bytes(m).decode("utf-8", "replace")
            t = time.strftime("%b %d %H:%M:%S", time.localtime(int(e.get("__REALTIME_TIMESTAMP", 0)) / 1e6))
            out.append("%s %s: %s" % (t, e.get("SYSLOG_IDENTIFIER", name), m))
            cur = e.get("__CURSOR", cur)
        return out, cur
    except Exception:
        return [], after


def journal_tail(unit, lines=30):
    try:
        out = subprocess.run(
            ["journalctl", "-u", unit, "-n", str(lines), "--no-pager"],
            capture_output=True, text=True, timeout=10,
        ).stdout
        return out
    except Exception:
        return ""


_infra_seen = {"keys": None, "guests": {}}


def infra_problems(pve, prev=None):
    """{key: text} for Proxmox conditions worth a push: storage, temperature, offline nodes."""
    out = {}
    h = pve.get("host") or {}
    for st in h.get("storage", []):
        if st.get("status") == "unreachable":
            out[f"storage:{st['name']}:down"] = f"Storage {st['name']} unreachable on {h.get('node')}"
        elif st.get("total"):
            pct = 100 * st["used"] / st["total"]
            if pct >= 90:
                lvl = "95" if pct >= 95 else "90"
                out[f"storage:{st['name']}:{lvl}"] = f"Storage {st['name']} {pct:.0f}% full on {h.get('node')}"
    temps = [t["c"] for t in h.get("temps", []) if str(t.get("label", "")).startswith(("Package", "Core", "Tctl", "Tdie"))]
    # Hysteresis: once raised, a temperature alert holds until the CPU is clearly cooler (80), so 84/85 flapping is one alert.
    limit = 80 if prev and f"temp:{h.get('node')}" in prev else 85
    if temps and max(temps) >= limit:
        out[f"temp:{h.get('node')}"] = f"{h.get('node')} CPU at {max(temps):.0f} °C"
    for n in pve.get("nodes", []):
        if n.get("status") == "offline":
            out[f"node:{n.get('node')}"] = f"Proxmox node {n.get('node')} is offline"
    return out


def infra_watch():
    """Pushes new Proxmox problems, their recovery, and guests that stop. The first pass only records state."""
    try:
        pve = infra().get("pve") or {}
    except Exception:
        return
    if not pve.get("reachable") or not pve.get("host"):
        return  # no live host data yet: recording now would make old problems look new on the next pass
    now = infra_problems(pve, _infra_seen["keys"])
    try:
        now.update(cert_problems(certs()))
    except Exception:
        pass
    seen = _infra_seen["keys"]
    if seen is not None:
        for k, text in now.items():
            if k not in seen and alert_allowed("infra:" + k):
                emit_event("alert", text, "Open System in the app for details.", priority=4, mute_key="infra:" + k)
        for k, text in seen.items():
            same = k.rsplit(":", 1)[0] + ":" if k.startswith("storage:") else k
            if k not in now and not any(n.startswith(same) for n in now) and not alert_muted("infra:" + k):
                emit_event("info", "Recovered: " + text, "The condition cleared.", priority=2)
    _infra_seen["keys"] = now
    prev = _infra_seen["guests"]
    cur = {str(g.get("vmid")): (g.get("status"), g.get("name") or str(g.get("vmid"))) for g in pve.get("guests", [])}
    for vmid, (st, name) in cur.items():
        was = prev.get(vmid, (None,))[0]
        if was == "running" and st == "stopped":
            if alert_allowed("guest:" + name):
                emit_event("alert", f"{name} stopped", f"Guest {vmid} is no longer running.", priority=4, mute_key="guest:" + name)
        elif was == "stopped" and st == "running" and not alert_muted("guest:" + name):
            emit_event("info", f"{name} started", f"Guest {vmid} is running.", priority=2)
    _infra_seen["guests"] = cur


def history_downsample(rows, width, use_min=False):
    """[(ts, value)] -> [[bucket_ts, value]] with one point per `width` seconds (average, or min for up/down keys)."""
    b = {}
    for ts, v in rows:
        b.setdefault(ts // width, []).append(v)
    return [[k * width, round(min(v) if use_min else sum(v) / len(v), 2)] for k, v in sorted(b.items())]


def history_sample(n):
    """One row per key into samples; service keys only every 5th pass. Prunes >7 d old rows hourly."""
    now, rows = int(time.time()), {}
    pve = (infra().get("pve") or {})
    h = pve.get("host") or {}
    if h:
        rows["pve.cpu"] = h.get("cpu_pct")
        rows["pve.mem"] = 100.0 * (1 - h["mem_avail"] / h["mem_total"]) if h.get("mem_total") else None
        temps = [t["c"] for t in h.get("temps", []) if str(t.get("label", "")).startswith(("Package", "Core", "Tctl", "Tdie"))]
        rows["pve.temp"] = max(temps) if temps else None
    if pve.get("guests"):
        rows["guests.running"] = sum(g.get("status") == "running" for g in pve["guests"])
    if n % 5 == 0:
        r = Handler._ops_services(None)
        hs = r["host"]
        rows["hub.cpu"] = hs.get("cpu_pct")
        rows["hub.mem"] = 100.0 * hs["mem_used_mb"] / hs["mem_total_mb"] if hs.get("mem_total_mb") else None
        rows["services.bad"] = sum(_svc_bad(s) for s in r["services"])
        for s in r["services"]:
            rows["up:" + s["name"]] = 0 if _svc_bad(s) or (s["kind"] == "docker" and not s["state"].startswith("Up")) else 1
    with db() as conn:
        conn.executemany("INSERT INTO samples (ts, key, value) VALUES (?,?,?)", [(now, k, v) for k, v in rows.items() if v is not None])
        if n % 60 == 0:
            conn.execute("DELETE FROM samples WHERE ts < ?", (now - 7 * 86400,))


def history_query(key, rng):
    span = {"24h": 86400, "7d": 7 * 86400}.get(rng)
    if not span or not key:
        return {"ok": False, "error": "bad params"}, 400
    with db() as conn:
        rows = [(r[0], r[1]) for r in conn.execute("SELECT ts, value FROM samples WHERE key=? AND ts>=? ORDER BY ts", (key, int(time.time()) - span))]
    return {"ok": True, "key": key, "range": rng, "points": history_downsample(rows, span // 119, key.startswith("up:"))}, 200


DIGEST_HOUR = int(os.environ.get("DIGEST_HOUR", "8"))  # local hour for the daily summary of open problems; -1 turns it off
DIGEST_STAMP = os.path.join(HUB_DIR, "digest.last")


def digest_lines():
    """Problems that are still open, one line each. Alerts only fire when something changes; this repeats what persists."""
    out = []
    try:
        pve = infra().get("pve") or {}
        out += list(infra_problems(pve).values())
    except Exception:
        pass
    try:
        out += list(cert_problems(certs()).values())
    except Exception:
        pass
    try:
        for j in _backups_probe().get("jobs", []):
            if j.get("enabled") and j.get("storage_ok") is False:
                out.append(f"Backup job {j['id']} writes to {j['storage']}, which is disabled: its next run fails")
    except Exception:
        pass
    try:
        sec, code = crowdsec()
        stale = [x for x in sec.get("stale_logs", []) if x.get("age_days")] if code == 200 else []
        if stale:
            out.append(f"CrowdSec watches {len(stale)} logs that stopped updating: local attack detection is off")
    except Exception:
        pass
    try:
        sync, code = syncthing()
        now = time.time()
        for d in sync.get("devices", []) if code == 200 else []:
            seen = d.get("last_seen", "")
            if d.get("connected") or not seen:
                continue
            import datetime
            age = (now - datetime.datetime.fromisoformat(seen.replace("Z", "+00:00")).timestamp()) / 86400
            if age >= 7:
                out.append(f"Syncthing: {d['name']} hasn't synced for {int(age)} days")
    except Exception:
        pass
    return out


def morning_digest(now=None):
    """Once a day at DIGEST_HOUR: a quiet event listing open problems (nothing when all is well)."""
    t = time.localtime(now or time.time())
    if DIGEST_HOUR < 0 or t.tm_hour != DIGEST_HOUR:
        return
    today = time.strftime("%Y-%m-%d", t)
    try:
        if open(DIGEST_STAMP).read().strip() == today:
            return
    except OSError:
        pass
    with open(DIGEST_STAMP, "w") as f:
        f.write(today)
    lines = digest_lines()
    if lines:
        emit_event("info", f"Homelab: {len(lines)} open {'problem' if len(lines) == 1 else 'problems'}",
                   "\n".join("\u2022 " + l for l in lines[:12]) + "\nTap to open System.", priority=2, url="app:system")


def ops_watcher_loop():
    n = 0
    while True:
        infra_watch()
        try:
            morning_digest()
        except Exception:
            pass
        try:
            history_sample(n)
        except Exception:
            pass
        n += 1
        for unit in load_allowlist():
            state, _sub = systemctl_state(unit)
            with db() as conn:
                row = conn.execute(
                    "SELECT last_state FROM unit_state WHERE name=?", (unit,)
                ).fetchone()
            prev = row["last_state"] if row else None
            if prev is not None and prev != state:
                if state in ("failed", "inactive"):
                    body = journal_tail(unit, 30)
                    if alert_allowed("unit:" + unit, cooldown=1800):
                        emit_event("alert", f"{unit} is {state}", body[-2000:], priority=5, mute_key="unit:" + unit)
                elif state == "active" and prev in ("failed", "inactive"):
                    emit_event("info", f"{unit} recovered", f"{unit} is now active", priority=2)
            with db() as conn:
                conn.execute(
                    "INSERT INTO unit_state (name, last_state, updated_ts) VALUES (?,?,?) "
                    "ON CONFLICT(name) DO UPDATE SET last_state=excluded.last_state, "
                    "updated_ts=excluded.updated_ts",
                    (unit, state, int(time.time())),
                )
        time.sleep(POLL_INTERVAL)


# -------------------------------------------------------------- host ------

def host_stats():
    stats = {}
    try:
        load1 = os.getloadavg()[0]
    except Exception:
        load1 = 0.0
    stats["load1"] = round(load1, 2)
    try:
        with open("/proc/stat") as f:
            l1 = f.readline().split()[1:]
        t1 = [int(x) for x in l1]
        time.sleep(0.1)
        with open("/proc/stat") as f:
            l2 = f.readline().split()[1:]
        t2 = [int(x) for x in l2]
        idle1, idle2 = t1[3], t2[3]
        total1, total2 = sum(t1), sum(t2)
        dt, didle = total2 - total1, idle2 - idle1
        cpu_pct = round(100.0 * (dt - didle) / dt, 1) if dt else 0.0
    except Exception:
        cpu_pct = 0.0
    stats["cpu_pct"] = cpu_pct
    try:
        meminfo = {}
        with open("/proc/meminfo") as f:
            for line in f:
                k, v = line.split(":", 1)
                meminfo[k] = int(v.strip().split()[0])
        total_mb = meminfo.get("MemTotal", 0) / 1024
        avail_mb = meminfo.get("MemAvailable", 0) / 1024
        stats["mem_total_mb"] = round(total_mb, 1)
        stats["mem_used_mb"] = round(total_mb - avail_mb, 1)
    except Exception:
        stats["mem_total_mb"] = 0
        stats["mem_used_mb"] = 0
    try:
        st = os.statvfs("/")
        total_gb = st.f_frsize * st.f_blocks / (1024 ** 3)
        free_gb = st.f_frsize * st.f_bavail / (1024 ** 3)
        stats["disk_total_gb"] = round(total_gb, 1)
        stats["disk_used_gb"] = round(total_gb - free_gb, 1)
    except Exception:
        stats["disk_total_gb"] = 0
        stats["disk_used_gb"] = 0
    try:
        with open("/proc/uptime") as f:
            stats["uptime_s"] = int(float(f.read().split()[0]))
    except Exception:
        stats["uptime_s"] = 0
    return stats


# -------------------------------------------------------------- infra -----

# Optional: {"ssh": "root@<pve-host>", "key": "/path/to/key"} when the hub runs inside a guest, not on the Proxmox host.
APP_DIR = os.environ.get("APP_DIR", os.path.join(HUB_DIR, "app"))
APP_REPO = os.environ.get("APP_REPO", "")  # "owner/name": GitHub releases are the source; "" = local latest.json only
_gh = {"ts": 0, "rel": None, "busy": set()}
_gh_lock = threading.Lock()


def _gh_token():
    t = os.environ.get("GITHUB_TOKEN", "")
    if t:
        return t
    try:
        return subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def _gh_release():
    """Latest release JSON of APP_REPO, cached 10 min (stale copy kept if GitHub fails), or None."""
    now = time.time()
    if _gh["rel"] is None or now - _gh["ts"] > 600:
        req = urllib.request.Request(f"https://api.github.com/repos/{APP_REPO}/releases/latest",
                                     headers={"Authorization": "Bearer " + _gh_token(),
                                              "Accept": "application/vnd.github+json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                _gh["rel"], _gh["ts"] = json.load(r), now
        except (OSError, ValueError):
            _gh["ts"] = now - 540  # retry in 60 s
    return _gh["rel"]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def _gh_download(asset, dest):
    """Private asset: the API redirects to a signed URL, which must be fetched without the token."""
    req = urllib.request.Request(asset["url"], headers={"Authorization": "Bearer " + _gh_token(),
                                                        "Accept": "application/octet-stream"})
    try:
        urllib.request.build_opener(_NoRedirect).open(req, timeout=30)
        raise OSError("no redirect")
    except urllib.error.HTTPError as e:
        loc = e.headers.get("Location")
        if e.code not in (301, 302, 303, 307, 308) or not loc:
            raise
    with urllib.request.urlopen(loc, timeout=60) as r, open(dest, "wb") as f:
        while True:
            c = r.read(65536)
            if not c:
                break
            f.write(c)


def _apk_version_code(path, body):
    import glob
    aapt = os.environ.get("AAPT2") or next(iter(sorted(glob.glob("/opt/android-sdk/build-tools/*/aapt2"))[::-1]), "")
    if aapt:
        try:
            out = subprocess.run([aapt, "dump", "badging", path], capture_output=True, text=True, timeout=30).stdout
            m = re.search(r"versionCode='(\d+)'", out)
            if m:
                return int(m.group(1))
        except (OSError, subprocess.SubprocessError):
            pass
    m = re.search(r"versionCode:\s*(\d+)", body or "")
    return int(m.group(1)) if m else None


def _gh_fetch(asset, body):
    """Background: download the APK into APP_DIR, write gh-<id>.json (sha256, size, versionCode), keep newest 2."""
    import glob
    base = os.path.join(APP_DIR, "gh-%s" % asset["id"])
    try:
        os.makedirs(APP_DIR, exist_ok=True)
        _gh_download(asset, base + ".part")
        h = hashlib.sha256()
        with open(base + ".part", "rb") as f:
            for c in iter(lambda: f.read(65536), b""):
                h.update(c)
        info = {"sha256": h.hexdigest(), "size": os.path.getsize(base + ".part")}
        vc = _apk_version_code(base + ".part", body)
        if vc is not None:
            info["versionCode"] = vc
        with open(base + ".json", "w") as f:
            json.dump(info, f)
        os.replace(base + ".part", base + ".apk")  # the .apk appears last: it means "ready"
        for old in sorted(glob.glob(os.path.join(APP_DIR, "gh-*.apk")), key=os.path.getmtime)[:-2]:
            for p in (old, old[:-4] + ".json"):
                try:
                    os.remove(p)
                except OSError:
                    pass
    except (OSError, ValueError):
        try:
            os.remove(base + ".part")
        except OSError:
            pass
    finally:
        with _gh_lock:
            _gh["busy"].discard(asset["id"])


def gh_latest():
    """OTA metadata from the newest GitHub release (starts the APK download if needed), or None."""
    rel = _gh_release()
    asset = next((a for a in (rel or {}).get("assets", []) if str(a.get("name", "")).endswith(".apk")), None)
    if not asset:
        return None
    body = rel.get("body") or ""
    d = {"versionName": str(rel.get("tag_name", "")).lstrip("v"), "notes": body[:1000], "size": asset.get("size"),
         "file": "gh-%s.apk" % asset["id"], "ready": False}
    base = os.path.join(APP_DIR, "gh-%s" % asset["id"])
    try:
        with open(base + ".json") as f:
            info = json.load(f)
        if os.path.isfile(base + ".apk"):
            d.update(info, ready=True)
    except (OSError, ValueError):
        pass
    if not d["ready"]:
        with _gh_lock:
            start = asset["id"] not in _gh["busy"]
            _gh["busy"].add(asset["id"])
        if start:
            threading.Thread(target=_gh_fetch, args=(asset, body), daemon=True).start()
    return d


def app_prefetch_loop():
    """Fetch each new release APK as soon as it is published, so a phone's first update check finds it ready."""
    while True:
        try:
            gh_latest()
        except Exception:
            pass
        time.sleep(600)


def _local_latest():
    try:
        with open(os.path.join(APP_DIR, "latest.json")) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def app_latest():
    """OTA metadata (GitHub release if APP_REPO is set and reachable, else APP_DIR/latest.json), or None."""
    return (gh_latest() if APP_REPO else None) or _local_latest()


def app_apk_path():
    """Path of the APK app_latest names, or None (rejects names with a path separator or "..")."""
    d = app_latest() or {}
    name = d.get("file")
    if not isinstance(name, str) or not name or "/" in name or "\\" in name or ".." in name or d.get("ready") is False:
        return None
    p = os.path.join(APP_DIR, name)
    return p if os.path.isfile(p) else None


PVE_CONF = os.environ.get("PVE_CONFIG", os.path.join(HUB_DIR, "pve.json"))
_infra_cache = {"ts": 0, "val": None}
_pve_os = {}  # (node,type,vmid) -> (ts, ostype)
_PVE_ID = re.compile(r"^[A-Za-z0-9._-]+$")
_OS_LABEL = {"l26": "Linux", "l24": "Linux 2.4", "win11": "Windows 11", "win10": "Windows 10", "win8": "Windows 8",
             "win7": "Windows 7", "w2k19": "Windows Server 2019", "w2k22": "Windows Server 2022",
             "wvista": "Windows Vista", "wxp": "Windows XP", "solaris": "Solaris", "other": "Other",
             "ubuntu": "Ubuntu", "debian": "Debian", "alpine": "Alpine", "archlinux": "Arch Linux",
             "centos": "CentOS", "fedora": "Fedora", "opensuse": "openSUSE", "gentoo": "Gentoo",
             "nixos": "NixOS", "devuan": "Devuan", "unmanaged": "Unmanaged"}


def pve_cmd(cmd):
    """Runs cmd on the Proxmox host: locally when this is the host, else over ssh (pve.json)."""
    import shutil
    if shutil.which("pvesh"):
        return cmd
    with open(PVE_CONF) as f:
        c = json.load(f)
    import shlex
    # ssh joins its args and the remote shell re-parses them: quote each one so request data stays data.
    return ["ssh", "-i", c["key"], "-o", "BatchMode=yes", "-o", "ConnectTimeout=4",
            "-o", "StrictHostKeyChecking=accept-new", c["ssh"], " ".join(shlex.quote(a) for a in cmd)]


def pve_get(path, timeout=8):
    """pvesh get via local pvesh or ssh. Returns parsed JSON; raises RuntimeError."""
    cmd = pve_cmd(["pvesh", "get", path, "--output-format", "json"])
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except Exception as e:
        raise RuntimeError(type(e).__name__)
    if r.returncode:
        raise RuntimeError((r.stderr.strip().splitlines() or ["pvesh failed"])[-1][:200])
    return json.loads(r.stdout or "null")


def os_label(ostype):
    return _OS_LABEL.get(ostype) or (ostype or "")


def host_info():
    import shutil
    h = {"hostname": os.uname().nodename, "kernel": os.uname().release, "os": {}}
    try:
        kv = {}
        with open("/etc/os-release") as f:
            for line in f:
                if "=" in line:
                    k, v = line.rstrip().split("=", 1)
                    kv[k] = v.strip('"')
        h["os"] = {"id": kv.get("ID", ""), "name": kv.get("NAME", ""), "version": kv.get("VERSION_ID", ""),
                   "pretty": kv.get("PRETTY_NAME", "")}
    except Exception:
        pass
    if shutil.which("pveversion") or shutil.which("pvesh"):
        h["kind"] = "pve"
        try:
            h["pveversion"] = subprocess.run(["pveversion"], capture_output=True, text=True, timeout=4).stdout.strip()
        except Exception:
            pass
    else:
        try:
            v = subprocess.run(["systemd-detect-virt"], capture_output=True, text=True, timeout=4).stdout.strip()
        except Exception:
            v = ""
        h["kind"] = "bare" if v in ("", "none") else ("lxc" if v in ("lxc", "lxc-libvirt", "openvz") else "vm")
    h.update(host_stats())
    return h


_NODE_KEYS = ("node", "status", "cpu", "maxcpu", "mem", "maxmem", "disk", "maxdisk", "uptime")
_GUEST_KEYS = _NODE_KEYS + ("vmid", "name", "type")


def _pve_fill(pve, now):
    """pvestatd can leave /cluster/resources as status=unknown; refill those nodes from per-node endpoints."""
    for n in pve["nodes"]:
        if n["status"] != "unknown":
            continue
        try:
            st = pve_get(f"/nodes/{n['node']}/status") or {}
            n.update(status="online", cpu=st.get("cpu"), maxcpu=st.get("cpuinfo", {}).get("cpus"),
                     mem=st.get("memory", {}).get("used"), maxmem=st.get("memory", {}).get("total"),
                     disk=st.get("rootfs", {}).get("used"), maxdisk=st.get("rootfs", {}).get("total"), uptime=st.get("uptime"))
            for t in ("lxc", "qemu"):
                for r in pve_get(f"/nodes/{n['node']}/{t}") or []:
                    for g in pve["guests"]:
                        if g["node"] == n["node"] and g["type"] == t and g["vmid"] == r.get("vmid"):
                            g.update(name=r.get("name"), status=r.get("status"), cpu=r.get("cpu"), maxcpu=r.get("cpus"),
                                     mem=r.get("mem"), maxmem=r.get("maxmem"), disk=r.get("disk"), maxdisk=r.get("maxdisk"),
                                     uptime=r.get("uptime"))
        except Exception:
            pass


# Live host stats read straight from /proc and /sys on the Proxmox host. pvestatd (and so every
# pvesh cpu/status figure) goes stale when a storage times out; this probe doesn't depend on it.
_HOST_PROBE = r"""
import json, os, glob, time, socket, subprocess
def run(c):
    try: return subprocess.run(c, capture_output=True, text=True, timeout=6).stdout
    except Exception: return ""
def stat():
    v = [int(x) for x in open("/proc/stat").readline().split()[1:]]; return sum(v), v[3] + v[4]
t1, i1 = stat(); time.sleep(0.4); t2, i2 = stat()
m = {l.split(":")[0]: int(l.split()[1]) * 1024 for l in open("/proc/meminfo")}
st = os.statvfs("/")
temps = []
for h in glob.glob("/sys/class/hwmon/hwmon*"):
    try: name = open(h + "/name").read().strip()
    except Exception: continue
    for t in sorted(glob.glob(h + "/temp*_input")):
        lab = t.replace("_input", "_label")
        try: temps.append({"name": name, "label": open(lab).read().strip() if os.path.exists(lab) else name, "c": int(open(t).read()) / 1000})
        except Exception: pass
cpu = open("/proc/cpuinfo").read()
model = next((l.split(":", 1)[1].strip() for l in cpu.splitlines() if l.startswith("model name")), "")
# Local filesystems and LVM thin pools; pvesm would hang on an unreachable network storage.
storage = []
for l in run(["timeout", "5", "df", "-l", "-B1", "-x", "tmpfs", "-x", "devtmpfs", "-x", "overlay", "-x", "efivarfs", "-x", "fuse",
              "-x", "vfat", "--output=target,fstype,size,used"]).splitlines()[1:]:
    f = l.split()
    if len(f) == 4 and f[2].isdigit(): storage.append({"name": f[0], "type": f[1], "status": "active", "total": int(f[2]), "used": int(f[3])})
for l in run(["timeout", "5", "lvs", "-o", "vg_name,lv_name,lv_size,data_percent", "--noheadings", "--units", "b", "--nosuffix", "-S", "segtype=thin-pool"]).splitlines():
    f = l.split()
    if len(f) == 4: storage.append({"name": f[0] + "/" + f[1], "type": "lvm-thin", "status": "active", "total": int(float(f[2])), "used": int(float(f[2]) * float(f[3]) / 100)})
for l in open("/proc/mounts"):
    f = l.split()
    if f[2] in ("nfs", "nfs4", "cifs", "smb3"):
        ok = subprocess.run(["timeout", "2", "stat", "-f", f[1]], capture_output=True).returncode == 0
        storage.append({"name": f[1], "type": f[2] + " " + f[0], "status": "active" if ok else "unreachable", "total": 0, "used": 0})
guests = {}
for l in run(["timeout", "5", "pct", "list"]).splitlines()[1:]:
    f = l.split(); guests[f[0]] = f[1] if len(f) > 1 else ""
for l in run(["timeout", "8", "qm", "list"]).splitlines()[1:]:
    f = l.split(); guests[f[0]] = f[2] if len(f) > 2 else ""
print(json.dumps({"node": socket.gethostname(), "cpu_pct": round(100.0 * (1 - (i2 - i1) / max(1, t2 - t1)), 1),
    "cores": os.cpu_count(), "model": model, "load": list(os.getloadavg()),
    "mem_total": m.get("MemTotal", 0), "mem_avail": m.get("MemAvailable", 0),
    "swap_total": m.get("SwapTotal", 0), "swap_free": m.get("SwapFree", 0),
    "root_total": st.f_frsize * st.f_blocks, "root_used": st.f_frsize * (st.f_blocks - st.f_bfree),
    "uptime": float(open("/proc/uptime").read().split()[0]), "kernel": os.uname().release,
    "pveversion": run(["pveversion"]).strip(), "temps": temps[:12], "storage": storage, "guests": guests}))
"""


_pve_live = {"ts": 0, "val": None, "busy": False}


def pve_host_live():
    """Latest probe result (≤ ~30 s old); refreshed on a background thread so requests never wait 1-8 s for ssh."""
    import threading
    now = time.time()
    if now - _pve_live["ts"] > 25 and not _pve_live["busy"]:
        _pve_live["busy"] = True
        def refresh():
            try:
                v = _pve_probe()
                if v:  # an ssh blip keeps the last good data instead of blanking the host card
                    _pve_live.update(val=v, ts=time.time())
                else:
                    _pve_live["ts"] = time.time()
            finally:
                _pve_live["busy"] = False
        t = threading.Thread(target=refresh, daemon=True)
        t.start()
        if _pve_live["val"] is None:
            t.join(15)  # first call: wait once so the screen isn't empty
    return _pve_live["val"]


def _pve_probe():
    try:
        r = subprocess.run(pve_cmd(["python3", "-"]), input=_HOST_PROBE, capture_output=True, text=True, timeout=20)
        return json.loads(r.stdout) if r.returncode == 0 and r.stdout.strip() else None
    except Exception:
        return None


def _bg_cached(cache, ttl, fn):
    """Value cached for ttl s, refreshed on a background thread so requests never wait (first call waits once)."""
    if time.time() - cache.get("ts", 0) > ttl and not cache.get("busy"):
        cache["busy"] = True
        def run():
            try:
                cache.update(val=fn(), ts=time.time())
            except Exception as e:
                cache.update(val=cache.get("val") or {"error": str(e)[:200]}, ts=time.time() - ttl + 15)
            finally:
                cache["busy"] = False
        t = threading.Thread(target=run, daemon=True)
        t.start()
        if cache.get("val") is None:
            t.join(20)
    return cache.get("val") or {"error": "warming up"}


def _ms_tcp(host, port):
    t = time.time()
    try:
        socket.create_connection((host, port), 1).close()
        return round((time.time() - t) * 1000)
    except Exception:
        return None


def _ms_ping(host):
    try:
        r = subprocess.run(["ping", "-c", "1", "-W", "1", host], capture_output=True, text=True, timeout=3)
        m = re.search(r"time=([\d.]+)", r.stdout)
        return round(float(m.group(1))) if m else None
    except Exception:
        return None


def _network_probe():
    import shutil
    out = {"self": None, "peers": [], "checks": []}
    if shutil.which("tailscale"):
        d = json.loads(subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True, timeout=8).stdout or "{}")
        me = d.get("Self") or {}
        peers = [{"name": p.get("HostName", ""), "os": p.get("OS", ""), "online": bool(p.get("Online")),
                  "last_seen": p.get("LastSeen", ""), "is_exit_node": bool(p.get("ExitNode")), "relay": p.get("Relay", "")}
                 for p in (d.get("Peer") or {}).values()]
        peers.sort(key=lambda p: (not p["online"], p["name"].lower()))
        out["self"] = {"name": me.get("HostName", ""), "ips": me.get("TailscaleIPs") or [], "online": bool(me.get("Online")),
                       "exit_node": next((p["name"] for p in peers if p["is_exit_node"]), ""), "state": d.get("BackendState", "")}
        out["peers"] = peers[:60]
    gw = re.search(r"default via (\S+)", subprocess.run(["ip", "route"], capture_output=True, text=True, timeout=3).stdout)
    if gw:
        out["checks"].append({"name": "Gateway", "target": gw.group(1), "ms": _ms_ping(gw.group(1))})
    out["checks"].append({"name": "Internet", "target": "1.1.1.1", "ms": _ms_ping("1.1.1.1")})
    hp = urllib.parse.urlparse(HERMES_API)
    out["checks"].append({"name": "Hermes API", "target": "%s:%s" % (hp.hostname, hp.port), "ms": _ms_tcp(hp.hostname, hp.port)})
    out["certs"] = certs()
    return out



CERT_HOSTS = os.path.join(HUB_DIR, "cert-hosts.txt")  # one hostname[:port] per line; '#' comments
_cert_cache = {"ts": 0, "certs": []}


def cert_status(target, timeout=6):
    """Days until the TLS certificate of host[:port] expires, its issuer, or why it can't be checked."""
    import ssl
    host, _, port = target.partition(":")
    out = {"host": target, "days": None, "issuer": "", "error": ""}
    try:
        with socket.create_connection((host, int(port or 443)), timeout=timeout) as raw:
            with ssl.create_default_context().wrap_socket(raw, server_hostname=host) as tls:
                cert = tls.getpeercert()
        out["days"] = int((ssl.cert_time_to_seconds(cert["notAfter"]) - time.time()) // 86400)
        out["issuer"] = dict(x[0] for x in cert.get("issuer", ())).get("organizationName", "")
    except ssl.SSLCertVerificationError as e:
        out["error"] = "invalid: " + (e.verify_message or "verification failed")
    except Exception as e:
        out["error"] = "unreachable: " + type(e).__name__
    return out


def certs(max_age=6 * 3600):
    """Checked at most every 6 h; the list comes from cert-hosts.txt."""
    if time.time() - _cert_cache["ts"] < max_age:
        return _cert_cache["certs"]
    try:
        with open(CERT_HOSTS) as f:
            lines = [l.split("#")[0].strip() for l in f if l.split("#")[0].strip()]
    except OSError:
        lines = []
    hosts = []
    for l in lines:
        found = npm_hosts(l[4:]) if l.startswith("npm:") else [l]
        hosts += [h for h in found if h not in hosts]
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(8) as pool:
        checked = list(pool.map(cert_status, hosts[:80]))
    checked.sort(key=lambda c: (c["days"] is None, c["days"] if c["days"] is not None else 0, c["host"]))
    _cert_cache.update(ts=time.time(), certs=checked)
    return _cert_cache["certs"]


def npm_hosts(vmid):
    """Hostnames on the Let's Encrypt certificates Nginx Proxy Manager keeps in container `vmid` (read-only)."""
    if not str(vmid).isdigit():
        return []
    script = ("for d in /var/lib/docker/volumes/npm-letsencrypt/_data/live/*/; do "
              "openssl x509 -in \"$d/fullchain.pem\" -noout -ext subjectAltName 2>/dev/null; done")
    try:
        r = subprocess.run(pve_cmd(["timeout", "40", "pct", "exec", str(vmid), "--", "sh", "-c", script]),
                           capture_output=True, text=True, timeout=50)
    except Exception:
        return []
    return sorted(set(re.findall(r"DNS:([A-Za-z0-9.-]+)", r.stdout)))


def cert_problems(items):
    """{key: text} worth a push: a certificate under 14 days (renewal has stopped) or failing verification."""
    out = {}
    for c in items:
        if c["error"].startswith("invalid"):
            out[f"cert:{c['host']}"] = f"Certificate for {c['host']} is {c['error']}"
        elif c["days"] is not None and c["days"] < 14:
            out[f"cert:{c['host']}"] = (f"Certificate for {c['host']} expired" if c["days"] < 0
                                        else f"Certificate for {c['host']} expires in {c['days']} days")
    return out



CROWDSEC_CONF = os.path.join(HUB_DIR, "crowdsec.txt")  # the vmid of the container running CrowdSec


def crowdsec_summary(m, alerts):
    """cscli metrics (json) + 24 h alerts -> what the app shows: blocklists, drops, and whether logs are parsed at all."""
    lists, blocked, dropped = [], 0, 0
    for origins in (m.get("bouncers") or {}).values():
        for origin, v in origins.items():
            ips = ((v.get("active_decisions") or {}).get("ip")) or 0
            drop = ((v.get("dropped") or {}).get("packet")) or 0
            if origin:
                lists.append({"name": origin, "ips": ips, "dropped": drop})
                blocked += ips; dropped += drop
    sources = [{"name": k.split(":", 1)[-1], "read": v.get("reads", 0), "parsed": v.get("parsed", 0)}
               for k, v in (m.get("acquisition") or {}).items()]
    blind = bool(sources) and all(x["parsed"] == 0 for x in sources)
    return {"ok": True, "blocked_ips": blocked, "dropped_packets": dropped, "lists": sorted(lists, key=lambda x: -x["ips"]),
            "sources": sources, "blind": blind, "alerts_24h": len(alerts or []),
            "scenarios": sorted({a.get("scenario", "") for a in alerts or [] if a.get("scenario")})[:8]}


def crowdsec():
    try:
        vmid = open(CROWDSEC_CONF).read().strip()
    except OSError:
        return {"ok": False, "error": "not configured"}, 404
    if not vmid.isdigit():
        return {"ok": False, "error": "bad crowdsec.txt"}, 500
    def run(args):
        r = subprocess.run(pve_cmd(["timeout", "25", "pct", "exec", vmid, "--"] + args), capture_output=True, text=True, timeout=35)
        return json.loads(r.stdout or "null")
    try:
        out = crowdsec_summary(run(["cscli", "metrics", "-o", "json"]) or {}, run(["cscli", "alerts", "list", "--since", "24h", "-o", "json"]) or [])
        # Log globs in the acquisition config that match no file: the usual reason it reads nothing useful.
        script = ("L=$(cat /etc/crowdsec/acquis.yaml /etc/crowdsec/acquis.d/*.yaml 2>/dev/null | "
                  "sed -n 's/^ *- *\\(\\/[^ ]*\\)$/\\1/p'); set -f; for g in $L; do n=$(set +f; ls -t $g 2>/dev/null | head -1); "
                  "if [ -z \"$n\" ]; then echo \"$g|missing\"; else a=$(( ($(date +%s) - $(stat -c %Y \"$n\")) / 86400 )); "
                  "[ $a -gt 7 ] && echo \"$g|$a\"; fi; done")
        r = subprocess.run(pve_cmd(["timeout", "15", "pct", "exec", vmid, "--", "sh", "-c", script]), capture_output=True, text=True, timeout=25)
        # "path|missing" or "path|<days since the newest file changed>"
        out["stale_logs"] = [{"path": l.split("|")[0], "age_days": None if l.endswith("|missing") else int(l.split("|")[1])}
                             for l in r.stdout.splitlines() if "|" in l][:10]
        return out, 200
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}, 502



SYNCTHING_CONF = os.path.join(HUB_DIR, "syncthing.txt")  # the vmid of the container running Syncthing
# Runs inside that container: the API key is read there and never leaves it.
_SYNC_SCRIPT = r"""
import glob, json, re, urllib.request
key = ""
for f in glob.glob("/root/.config/syncthing/config.xml") + glob.glob("/root/.local/state/syncthing/config.xml") + glob.glob("/home/*/.config/syncthing/config.xml"):
    m = re.search(r"<apikey>([^<]+)</apikey>", open(f).read())
    if not m: continue
    try:
        urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8384/rest/system/ping", headers={"X-API-Key": m.group(1)}), timeout=4)
        key = m.group(1); break
    except Exception: pass
def g(p):
    return json.load(urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8384/rest/" + p, headers={"X-API-Key": key}), timeout=6))
me = g("system/status")["myID"]
conns, seen = g("system/connections")["connections"], g("stats/device")
devices = [{"id": d["deviceID"][:7], "name": d["name"], "paused": d["paused"], "connected": conns.get(d["deviceID"], {}).get("connected", False),
            "last_seen": seen.get(d["deviceID"], {}).get("lastSeen", "")} for d in g("config/devices") if d["deviceID"] != me]
names = {d["deviceID"]: d["name"] for d in g("config/devices")}
folders = []
for f in g("config/folders"):
    st = g("db/status?folder=" + f["id"])
    folders.append({"label": f["label"] or f["id"], "path": f["path"], "paused": f["paused"], "state": st.get("state", ""),
                    "need": st.get("needFiles", 0), "errors": st.get("errors", 0) + st.get("pullErrors", 0), "bytes": st.get("globalBytes", 0),
                    "shared_with": [names.get(d["deviceID"], "") for d in f["devices"] if d["deviceID"] != me]})
print(json.dumps({"devices": devices, "folders": folders}))
"""


def syncthing():
    try:
        vmid = open(SYNCTHING_CONF).read().strip()
    except OSError:
        return {"ok": False, "error": "not configured"}, 404
    if not vmid.isdigit():
        return {"ok": False, "error": "bad syncthing.txt"}, 500
    try:
        r = subprocess.run(pve_cmd(["timeout", "25", "pct", "exec", vmid, "--", "python3", "-c", _SYNC_SCRIPT]),
                           capture_output=True, text=True, timeout=35)
        out = json.loads(r.stdout)
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}, 502
    out["ok"] = True
    return out, 200


_sync_cache = {}


_sec_cache = {}


_net_cache = {}


def _backups_probe():
    names = {g.get("vmid"): g.get("name") for g in (infra().get("pve") or {}).get("guests", [])}
    last = {}
    tasks = []
    for n in (infra().get("pve") or {}).get("nodes", []):  # per-node history is deeper than /cluster/tasks (last 50); neither touches storage
        r = subprocess.run(pve_cmd(["timeout", "10", "pvesh", "get", "/nodes/%s/tasks" % n["node"], "--typefilter", "vzdump", "--limit", "500",
                                    "--output-format", "json"]), capture_output=True, text=True, timeout=15)
        tasks += json.loads(r.stdout or "[]")
    for t in tasks:
        if not str(t.get("id", "")).isdigit():
            continue
        v = int(t["id"])
        if v not in last or t.get("starttime", 0) > last[v]["starttime"]:
            last[v] = t
    now = time.time()
    guests = [{"vmid": v, "name": names.get(v) or "", "last_ts": t.get("starttime", 0), "last_status": t.get("status", ""),
               "age_days": round((now - t.get("starttime", now)) / 86400, 1)} for v, t in last.items()]
    guests += [{"vmid": v, "name": n or "", "last_ts": 0, "last_status": "", "age_days": None} for v, n in names.items() if v not in last]
    guests.sort(key=lambda g: g["vmid"])
    jobs = [{"id": j.get("id", ""), "schedule": j.get("schedule") or j.get("starttime", ""), "storage": j.get("storage", ""),
             "all": bool(j.get("all")), "vmids": j.get("vmid", ""), "enabled": j.get("enabled", 1) not in (0, "0", False)}
            for j in pve_get("/cluster/backup", 12) or []]
    try:  # a job pointed at disabled or deleted storage fails at its next run
        usable = {st.get("storage") for st in pve_get("/storage", 12) or [] if not st.get("disable")}
        for j in jobs:
            j["storage_ok"] = not j["storage"] or j["storage"] in usable
    except Exception:
        pass
    return {"guests": guests, "jobs": jobs}


_bak_cache = {}


_upd_cache = {}


def _apt_info(run):
    """Upgradable-package summary from the existing apt cache (no refresh). run(cmd)->stdout."""
    out = run("apt list --upgradable 2>/dev/null | grep -v '^Listing'; echo ==; test -e /var/run/reboot-required && echo R")
    body, _, tail = out.partition("\n==\n")
    lines = [l for l in body.splitlines() if "/" in l]
    sample = []
    for l in lines[:8]:
        m = re.match(r"(\S+?)/\S+ (\S+) .*upgradable from: ([^\]\s]+)", l)
        sample.append(f"{m.group(1)} {m.group(3)} → {m.group(2)}" if m else l.split("/")[0])
    return {"packages": len(lines), "security": sum("-security" in l for l in lines), "sample": sample,
            "reboot_required": "R" in tail}


def _updates_probe():
    def sh(c):
        return ["sh", "-c", c]
    def local(c):
        if isinstance(c, str):
            c = sh(c)
        return subprocess.run(c, capture_output=True, text=True, timeout=15).stdout
    def remote(c):
        import shlex
        c = ["sh", "-c", c] if isinstance(c, str) else c  # pve_cmd quotes each arg for ssh
        return subprocess.run(pve_cmd(c), capture_output=True, text=True, timeout=25).stdout
    hosts = [dict(name=os.uname().nodename, kind="hub", **_apt_info(local))]
    try:
        import shutil
        if shutil.which("pvesh"):
            raise RuntimeError("hub runs on the Proxmox host: already listed")
        node = (remote(["hostname"]).strip() or "pve")
        hosts.append(dict(name=node, kind="pve", **_apt_info(remote)))
    except Exception:
        pass
    cur = None
    try:
        with urllib.request.urlopen(f"{HERMES_API}/health", timeout=3) as r:
            cur = json.load(r).get("version")
    except Exception:
        pass
    ctrs = []
    try:
        for l in local(["timeout", "5", "docker", "ps", "-a", "--format", "{{.Names}}|{{.Image}}|{{.CreatedAt}}"]).splitlines()[:40]:
            n, i, c = (l.split("|") + ["", ""])[:3]
            ctrs.append({"name": n, "image": i, "created": c[:10]})
    except Exception:
        pass
    return {"ok": True, "updated": int(time.time()), "hosts": hosts, "hermes": {"current": cur, "latest": None}, "containers": ctrs}


def infra():
    now = time.time()
    if _infra_cache["val"] and now - _infra_cache["ts"] < 15:
        return _infra_cache["val"]
    pve = {"reachable": False, "error": "", "nodes": [], "guests": []}
    try:
        for r in pve_get("/cluster/resources") or []:
            t = r.get("type")
            if t == "node":
                pve["nodes"].append({k: r.get(k) for k in _NODE_KEYS})
            elif t in ("qemu", "lxc"):
                g = {k: r.get(k) for k in _GUEST_KEYS}
                c = _pve_os.get((g["node"], t, g["vmid"]))
                if c and now - c[0] < 600:
                    g["ostype"] = c[1]
                pve["guests"].append(g)
        pve["reachable"] = True
        _pve_fill(pve, now)
        for n in pve["nodes"]:
            if n.get("status") != "online":
                n["wol"] = _node_wol(n.get("node"))
    except Exception as e:
        pve["error"] = str(e)[:200]
    live = pve_host_live()
    if live:
        pve["host"] = live
        # Live pct/qm states beat the stale "unknown" from /cluster/resources on this node.
        for g in pve["guests"]:
            st = live["guests"].get(str(g.get("vmid")))
            if g.get("node") == live["node"] and st:
                g["status"] = st
        for n in pve["nodes"]:
            if n.get("node") == live["node"]:
                n.update(status="online", cpu=live["cpu_pct"] / 100, maxcpu=live["cores"],
                         mem=live["mem_total"] - live["mem_avail"], maxmem=live["mem_total"],
                         disk=live["root_used"], maxdisk=live["root_total"], uptime=int(live["uptime"]))
    val = {"host": host_info(), "pve": pve}
    _infra_cache.update(ts=now, val=val)
    return val


_sum_cache = {"ts": 0, "val": None, "busy": False}


def _svc_bad(s):
    st = s.get("state", "")
    if s.get("kind") == "docker":
        return "unhealthy" in st or "Restarting" in st or (st.startswith("Exited (") and not st.startswith("Exited (0)"))
    return st != "active"


def _summary_build():
    inf = infra()
    pve = inf["pve"]
    h = pve.get("host") or {}
    hi = inf["host"]
    if h:
        temps = [t["c"] for t in h.get("temps", []) if str(t.get("label", "")).startswith(("Package", "Core", "Tctl", "Tdie"))]
        mem_pct = 100.0 * (1 - h["mem_avail"] / h["mem_total"]) if h.get("mem_total") else 0
        root_pct = 100.0 * h["root_used"] / h["root_total"] if h.get("root_total") else 0
        host = {"node": h["node"], "cpu_pct": h["cpu_pct"], "mem_pct": round(mem_pct, 1), "root_pct": round(root_pct, 1),
                "temp_c": max(temps) if temps else None}
    else:
        host = {"node": hi.get("hostname", ""), "cpu_pct": hi.get("cpu_pct"), "mem_pct": hi.get("mem_pct"),
                "root_pct": hi.get("disk_pct"), "temp_c": None}
    guests = pve["guests"]
    nodes = pve["nodes"]
    bad = [s["name"] for s in Handler._ops_services(None)["services"] if _svc_bad(s)]
    worst = {"name": "", "pct": 0}
    problems = []
    for s in h.get("storage", []):
        if s.get("status") == "unreachable":
            problems.append(f"storage {s['name']} unreachable")
        elif s.get("total"):
            p = round(100.0 * s["used"] / s["total"], 1)
            if p >= worst["pct"]:
                worst = {"name": s["name"], "pct": p}
            if p >= 90:
                problems.append(f"storage {s['name']} {p:.0f}%")
    if (host["temp_c"] or 0) >= 80:
        problems.append(f"temp {host['temp_c']:.0f}C")
    if bad:
        problems.append(f"failed: {', '.join(bad[:2])}" + (f" +{len(bad) - 2}" if len(bad) > 2 else ""))
    off = [n["node"] for n in nodes if n.get("status") != "online"]
    if off:
        problems.append(f"offline: {', '.join(off[:2])}")
    return {"ok": True, "updated": int(time.time()), "host": host,
            "guests_running": sum(g.get("status") == "running" for g in guests), "guests_total": len(guests),
            "nodes_online": len(nodes) - len(off), "nodes_total": len(nodes), "services_bad": len(bad),
            "worst_storage": worst, "problems": problems[:4]}


def ops_summary():
    """Small status roll-up for the app widget; stale value served instantly while a thread rebuilds it."""
    import threading
    now = time.time()
    if now - _sum_cache["ts"] > 20 and not _sum_cache["busy"]:
        _sum_cache["busy"] = True
        def refresh():
            try:
                _sum_cache.update(val=_summary_build(), ts=time.time())
            except Exception:
                pass
            finally:
                _sum_cache["busy"] = False
        t = threading.Thread(target=refresh, daemon=True)
        t.start()
        if _sum_cache["val"] is None:
            t.join(20)
    return _sum_cache["val"] or {"ok": False, "error": "summary unavailable"}


def infra_guest(node, typ, vmid):
    if typ not in ("qemu", "lxc") or not _PVE_ID.match(node or "") or not str(vmid).isdigit():
        return {"ok": False, "error": "bad params"}, 400
    base = f"/nodes/{node}/{typ}/{vmid}"
    try:
        out = {"ok": True, "node": node, "type": typ, "vmid": int(vmid), "status": pve_get(base + "/status/current")}
        cfg = pve_get(base + "/config") or {}
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}, 502
    ost = cfg.get("ostype", "")
    _pve_os[(node, typ, int(vmid))] = (time.time(), ost)
    out["config"] = {k: cfg.get(k) for k in ("name", "hostname", "ostype", "cores", "sockets", "memory", "swap", "onboot",
                                            "agent", "arch", "description") if k in cfg}
    out["config"]["nets"] = [v for k, v in cfg.items() if re.match(r"^net\d+$", k)]
    out["os"] = os_label(ost)
    ifaces = []
    try:
        if typ == "lxc":
            ifaces = [{"name": i.get("name"), "ip": i.get("inet"), "ip6": i.get("inet6"), "mac": i.get("hwaddr")}
                      for i in pve_get(base + "/interfaces") or []]
        elif str(cfg.get("agent", "0")).startswith("1") and out["status"].get("status") == "running":
            res = (pve_get(base + "/agent/network-get-interfaces") or {}).get("result", [])
            ifaces = [{"name": i.get("name"), "mac": i.get("hardware-address"),
                       "ip": ", ".join(a["ip-address"] + "/" + str(a.get("prefix", "")) for a in i.get("ip-addresses", [])
                                       if a.get("ip-address-type") == "ipv4")} for i in res]
    except Exception:
        pass
    # Skip loopback and container plumbing (docker bridges, veths) so the phone shows real addresses.
    out["interfaces"] = [i for i in ifaces if not str(i.get("name") or "").startswith(("lo", "br-", "docker", "veth", "virbr"))]
    return out, 200


# ------------------------------------------------------ guest control -----
_ACTIONS = ("start", "shutdown", "stop", "reboot")
_SNAP_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{1,39}$")
AUDIT_LOG = os.path.join(HUB_DIR, "ops-audit.log")
ACTIONS_ALLOW = os.path.join(HUB_DIR, "pve-actions.txt")


def _guest_guard(node, typ, vmid):
    """None when the phone may control this guest, else (error, status)."""
    if typ not in ("qemu", "lxc") or not _PVE_ID.match(node or "") or not str(vmid).isdigit():
        return {"ok": False, "error": "bad params"}, 400
    me = socket.gethostname().split(".")[0]
    for g in (infra().get("pve") or {}).get("guests", []):
        if str(g.get("vmid")) == str(vmid) and (g.get("name") or "").split(".")[0] == me:
            return {"ok": False, "error": "this guest runs the hub; control it from Proxmox"}, 403
    if os.path.exists(ACTIONS_ALLOW):
        with open(ACTIONS_ALLOW) as f:
            allowed = {l.split("#")[0].strip() for l in f}
        if str(vmid) not in allowed:
            return {"ok": False, "error": "vmid not in pve-actions.txt"}, 403
    return None


def _audit(what, result):
    try:
        with open(AUDIT_LOG, "a") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {what} -> {result}\n")
    except Exception:
        pass


def _pvesh(verb, path, *args, timeout=25):
    r = subprocess.run(pve_cmd(["pvesh", verb, path, "--output-format", "json"] + list(args)),
                       capture_output=True, text=True, timeout=timeout)
    if r.returncode:
        raise RuntimeError((r.stderr.strip().splitlines() or ["pvesh failed"])[-1][:200])
    return json.loads(r.stdout) if r.stdout.strip() else None


def guest_action(d):
    node, typ, vmid, action = d.get("node"), d.get("type"), str(d.get("vmid", "")), d.get("action")
    bad = _guest_guard(node, typ, vmid)
    if bad:
        return bad
    if action not in _ACTIONS:
        return {"ok": False, "error": "bad action"}, 400
    try:
        upid = _pvesh("create", f"/nodes/{node}/{typ}/{vmid}/status/{action}")
    except Exception as e:
        _audit(f"{typ}/{vmid} {action}", f"error {e}")
        return {"ok": False, "error": str(e)[:200]}, 502
    _audit(f"{typ}/{vmid} {action}", upid)
    _infra_cache["ts"] = 0
    return {"ok": True, "upid": upid}, 200


def _node_wol(node):
    """True when Proxmox has a Wake-on-LAN MAC for this node (read from the cluster fs, so it works while the node is off)."""
    if not _PVE_ID.match(node or ""):
        return False
    try:
        r = subprocess.run(pve_cmd(["cat", f"/etc/pve/nodes/{node}/config"]), capture_output=True, text=True, timeout=8)
    except Exception:
        return False
    return r.returncode == 0 and any(l.strip().startswith("wakeonlan:") for l in r.stdout.splitlines())


def node_wake(d):
    node = d.get("node")
    if not _PVE_ID.match(node or ""):
        return {"ok": False, "error": "bad node"}, 400
    if not _node_wol(node):
        return {"ok": False, "error": "Wake-on-LAN isn't set up for this node. In Proxmox: the node > System > Options > "
                                      "MAC address for Wake-on-LAN, and enable WoL in its BIOS."}, 409
    try:
        out = _pvesh("create", f"/nodes/{node}/wakeonlan")
    except Exception as e:
        _audit(f"node/{node} wake", f"error {e}")
        return {"ok": False, "error": str(e)[:200]}, 502
    _audit(f"node/{node} wake", out)
    _infra_cache["ts"] = 0
    return {"ok": True}, 200


def rrd_points(rows, width=120):
    """Proxmox rrddata rows -> [[t, cpu%, mem%, net_in B/s, net_out B/s]], about `width` points, empty rows skipped."""
    rows = [r for r in rows or [] if "cpu" in r and r.get("time")]
    step = max(1, len(rows) // width)
    out = []
    for i in range(0, len(rows), step):
        chunk = rows[i:i + step]
        avg = lambda k: sum(float(r.get(k) or 0) for r in chunk) / len(chunk)
        mm = avg("maxmem")
        out.append([chunk[-1]["time"], round(100 * avg("cpu"), 1), round(100 * avg("mem") / mm, 1) if mm else None,
                    round(avg("netin")), round(avg("netout"))])
    return out


def infra_rrd(node, typ, vmid):
    """24 h of CPU, memory and network for a guest (typ qemu/lxc) or a node (typ node)."""
    if not _PVE_ID.match(node or "") or typ not in ("qemu", "lxc", "node") or (typ != "node" and not str(vmid).isdigit()):
        return {"ok": False, "error": "bad params"}, 400
    path = f"/nodes/{node}/rrddata" if typ == "node" else f"/nodes/{node}/{typ}/{vmid}/rrddata"
    try:
        rows = _pvesh("get", path, "--timeframe", "day", "--cf", "AVERAGE", timeout=15)
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}, 502
    return {"ok": True, "points": rrd_points(rows)}, 200


_UPID = re.compile(r"^UPID:[A-Za-z0-9._@!:-]+$")


def infra_tasks():
    """The cluster's last ~50 tasks (backups, starts, stops, updates...), newest first."""
    try:
        rows = pve_get("/cluster/tasks", 12) or []
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}, 502
    tasks = [{"upid": t.get("upid", ""), "node": t.get("node", ""), "type": t.get("type", ""), "id": t.get("id", ""),
              "user": t.get("user", ""), "start": t.get("starttime", 0), "end": t.get("endtime", 0),
              "status": t.get("status", "running")} for t in rows]
    tasks.sort(key=lambda t: t["start"], reverse=True)
    return {"ok": True, "tasks": tasks}, 200


def infra_task_log(node, upid):
    if not _PVE_ID.match(node or "") or not _UPID.match(upid or ""):
        return {"ok": False, "error": "bad params"}, 400
    try:
        rows = _pvesh("get", f"/nodes/{node}/tasks/{upid}/log", "--limit", "300", timeout=15) or []
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}, 502
    return {"ok": True, "lines": [r.get("t", "") for r in rows]}, 200


def guest_snapshots(node, typ, vmid):
    if typ not in ("qemu", "lxc") or not _PVE_ID.match(node or "") or not str(vmid).isdigit():
        return {"ok": False, "error": "bad params"}, 400
    try:
        snaps = _pvesh("get", f"/nodes/{node}/{typ}/{vmid}/snapshot", timeout=15) or []
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}, 502
    return {"ok": True, "snapshots": [{k: s.get(k) for k in ("name", "description", "snaptime", "parent")}
                                      for s in snaps if s.get("name") != "current"]}, 200


def guest_snapshot(d):
    node, typ, vmid, op, name = d.get("node"), d.get("type"), str(d.get("vmid", "")), d.get("op"), d.get("name") or ""
    bad = _guest_guard(node, typ, vmid)
    if bad:
        return bad
    if op not in ("create", "rollback", "delete") or not _SNAP_NAME.match(name):
        return {"ok": False, "error": "bad op or snapshot name"}, 400
    base = f"/nodes/{node}/{typ}/{vmid}/snapshot"
    try:
        if op == "create":
            desc = str(d.get("description") or "")[:200]
            upid = _pvesh("create", base, "--snapname", name, *(["--description", desc] if desc else []))
        elif op == "rollback":
            upid = _pvesh("create", f"{base}/{name}/rollback")
        else:
            upid = _pvesh("delete", f"{base}/{name}")
    except Exception as e:
        _audit(f"{typ}/{vmid} snapshot {op} {name}", f"error {e}")
        return {"ok": False, "error": str(e)[:200]}, 502
    _audit(f"{typ}/{vmid} snapshot {op} {name}", upid)
    return {"ok": True, "upid": upid}, 200


def infra_node(node):
    if not _PVE_ID.match(node or ""):
        return {"ok": False, "error": "bad params"}, 400
    try:
        s = pve_get(f"/nodes/{node}/status") or {}
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}, 502
    ci = s.get("cpuinfo", {})
    return {"ok": True, "node": node, "cpu": s.get("cpu"), "cpuinfo": ci.get("model"), "maxcpu": ci.get("cpus"),
            "memory": s.get("memory"), "rootfs": s.get("rootfs"), "swap": s.get("swap"), "uptime": s.get("uptime"),
            "pveversion": s.get("pveversion"), "kversion": s.get("kversion"), "loadavg": s.get("loadavg")}, 200


# -------------------------------------------------------------- routes ----

def safe_filename(name):
    name = os.path.basename(name or "upload")
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name).lstrip(".") or "upload"
    return name[:200]


# ── Hermes gateway bridge (slash commands, undo/rewind, rollback) ─────────────────────────────
# The HTTP API has no slash commands; Hermes Desktop runs them over the gateway's JSON-RPC
# WebSocket (`hermes serve`). The hub talks to a loopback `hermes serve` (started with
# HERMES_DESKTOP=1 and HERMES_DASHBOARD_SESSION_TOKEN, see hermes-serve.service) and exposes the
# few calls the app needs. Every call resumes the stored session, acts, and closes it again, so
# the gateway never holds a chat live (a live chat would take over /v1/runs turns).
GATEWAY_WS = os.environ.get("HERMES_GATEWAY_WS", "ws://127.0.0.1:9119/api/ws")
GATEWAY_TOKEN_FILE = os.environ.get("HERMES_GATEWAY_TOKEN_FILE", os.path.expanduser("~/.hermes/secrets/hermes-serve.token"))
_gw_lock = threading.Lock()
_gw_catalog = {"at": 0, "data": None}


class GatewayError(Exception):
    def __init__(self, message, code=0):
        super().__init__(message)
        self.code = code


class _Gateway:
    def __enter__(self):
        try:
            from websockets.sync.client import connect
        except ImportError:
            raise GatewayError("python3-websockets is not installed on the hub host")
        try:
            token = open(GATEWAY_TOKEN_FILE).read().strip()
        except OSError:
            raise GatewayError("hermes serve token missing: " + GATEWAY_TOKEN_FILE)
        try:
            self.ws = connect(GATEWAY_WS + "?token=" + urllib.parse.quote(token), max_size=None, open_timeout=15)
        except Exception as e:
            raise GatewayError(f"Hermes gateway (hermes serve) unreachable: {e}")
        self.n = 0
        self.live = []
        return self

    def call(self, method, params, timeout=120):
        self.n += 1
        rid = self.n
        self.ws.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}))
        deadline = time.time() + timeout
        while True:
            left = deadline - time.time()
            if left <= 0:
                raise GatewayError(f"{method} timed out")
            msg = json.loads(self.ws.recv(timeout=left))
            if msg.get("id") != rid:
                continue  # events
            if "error" in msg:
                err = msg["error"] or {}
                raise GatewayError(str(err.get("message", "gateway error")), int(err.get("code", 0) or 0))
            return msg.get("result") or {}

    def resume(self, stored_id, profile=""):
        # A chat on a gateway profile (/p/<name>) lives in that profile's state.db.
        params = {"session_id": stored_id}
        if profile:
            params["profile"] = profile
        res = self.call("session.resume", params, timeout=90)
        self.live.append(res.get("session_id"))
        return res

    def __exit__(self, *exc):
        for sid in self.live:
            try:
                self.call("session.close", {"session_id": sid}, timeout=20)
            except Exception:
                pass
        try:
            self.ws.close()
        except Exception:
            pass


def _gw_profile(d):
    p = str(d.get("profile") or "").strip()
    return p if re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", p) and p != "default" else ""


def gw_commands():
    if _gw_catalog["data"] is not None and time.time() - _gw_catalog["at"] < 600:
        return _gw_catalog["data"]
    with _gw_lock, _Gateway() as g:
        res = g.call("commands.catalog", {})
    out = {"ok": True, "commands": [{"name": p[0], "description": p[1]} for p in res.get("pairs", []) if len(p) >= 2]}
    _gw_catalog.update(at=time.time(), data=out)
    return out


def gw_slash(d):
    sid, command = str(d.get("session_id") or ""), str(d.get("command") or "").strip()
    if not sid or not command.startswith("/"):
        return {"ok": False, "error": "session_id and a /command are required"}, 400
    with _gw_lock, _Gateway() as g:
        rt = g.resume(sid, _gw_profile(d))["session_id"]
        for _ in range(3):  # quick-command aliases
            try:
                res = g.call("slash.exec", {"session_id": rt, "command": command}, timeout=660)
            except GatewayError as e:
                if e.code != 4018:
                    raise
                parts = command.lstrip("/").split(maxsplit=1)
                res = g.call("command.dispatch", {"session_id": rt, "name": parts[0], "arg": parts[1] if len(parts) > 1 else ""}, timeout=660)
            if res.get("type") == "alias" and res.get("target"):
                command = "/" + str(res["target"]).lstrip("/")
                continue
            break
    out = {"ok": True, "type": res.get("type") or "exec", "output": res.get("output", ""), "message": res.get("message", ""),
           "notice": res.get("notice", ""), "warning": res.get("warning", "")}
    return out, 200


def gw_usage(d):
    """Read context and token counters from an existing Hermes serve session."""
    sid = str(d.get("session_id") or "").strip()
    if not sid:
        return {"ok": False, "error": "session_id is required"}, 400
    if not _gw_lock.acquire(timeout=2):
        raise GatewayError("Hermes gateway is busy; try usage again")
    try:
        with _Gateway() as g:
            rt = g.resume(sid, _gw_profile(d))["session_id"]
            usage = g.call("session.usage", {"session_id": rt}, timeout=20)
    finally:
        _gw_lock.release()
    result = {"as_of": int(time.time())}
    used, window = usage.get("context_used"), usage.get("context_max")
    if isinstance(used, (int, float)) and math.isfinite(used) and used >= 0:
        context = {"used_tokens": used, "source": "estimate" if usage.get("context_estimated") else "last_turn"}
        if isinstance(window, (int, float)) and math.isfinite(window) and window > 0:
            context.update(window_tokens=window, percent=max(0, min(100, used / window * 100)))
        result["context"] = context
    session = {field: usage[key] for field, key in (("input_tokens", "input"), ("output_tokens", "output"),
                                                   ("total_tokens", "total"), ("reasoning_tokens", "reasoning"))
               if isinstance(usage.get(key), (int, float)) and math.isfinite(usage[key]) and usage[key] >= 0}
    if session:
        result["session"] = session
    return result, 200


def gw_rewind(d):
    """Drop the user turn with row id `row_id` (or the last `turns` user turns) and everything after it."""
    sid = str(d.get("session_id") or "")
    if not sid:
        return {"ok": False, "error": "session_id required"}, 400
    with _gw_lock, _Gateway() as g:
        res = g.resume(sid, _gw_profile(d))
        rt = res["session_id"]
        if res.get("running"):
            return {"ok": False, "error": "Hermes is still working in this chat; stop it first"}, 409
        msgs = res.get("messages") or []
        if d.get("row_id") is not None:
            target = str(d.get("row_id"))
            idx = next((i for i, m in enumerate(msgs) if str(m.get("row_id")) == target), -1)
            if idx < 0 or msgs[idx].get("role") != "user":
                return {"ok": False, "error": "That message is no longer in this chat's history"}, 404
            turns = sum(1 for m in msgs[idx:] if m.get("role") == "user")
        else:
            turns = max(1, int(d.get("turns") or 1))
        removed = 0
        for _ in range(turns):
            removed += int(g.call("session.undo", {"session_id": rt}).get("removed", 0) or 0)
    return {"ok": True, "turns": turns, "removed": removed}, 200


def gw_rollback(d):
    sid = str(d.get("session_id") or "")
    if not sid:
        return {"ok": False, "error": "session_id required"}, 400
    with _gw_lock, _Gateway() as g:
        rt = g.resume(sid, _gw_profile(d))["session_id"]
        if d.get("hash"):
            res = g.call("rollback.restore", {"session_id": rt, "hash": str(d["hash"])}, timeout=300)
        else:
            res = g.call("rollback.list", {"session_id": rt})
    res["ok"] = bool(res.get("success", True))
    return res, 200


def gw_route(fn, d):
    try:
        return fn(d)
    except GatewayError as e:
        return {"ok": False, "error": str(e)}, 502


# --- *arr / downloader proxy (nzb360-style Media screen) -------------------------------------------------
# /arr/<svc>/<path>?query is forwarded to the service with its key from ~/.hermes/secrets/arr.env.
# Only the (method, path) pairs below pass; everything else is 403.
_ARR_ENV = os.path.expanduser("~/.hermes/secrets/arr.env")
_V3 = r"api/v3/"
_COVER = r"|MediaCover/\d+/(poster|fanart|banner)(-\d+)?\.(jpg|png)"
_ARR_ALLOW = {
    "sonarr": {
        "GET": _V3 + r"(queue|queue/status|calendar|wanted/missing|wanted/cutoff|series|series/\d+|series/lookup|system/status|system/task|update|health|"
               r"rootfolder|qualityprofile|diskspace|history|history/since|history/series|command|command/\d+|episode|episode/\d+|episodefile|episodefile/\d+|release|blocklist|tag)" + _COVER,
        "POST": _V3 + r"(series|command|release|history/failed/\d+)",
        "PUT": _V3 + r"(series/\d+|episode/\d+|episode/monitor)",
        "DELETE": _V3 + r"(queue/\d+|series/\d+|episodefile/\d+|blocklist/\d+|blocklist/bulk)",
    },
    "radarr": {
        "GET": _V3 + r"(queue|queue/status|calendar|wanted/missing|wanted/cutoff|movie|movie/\d+|movie/lookup|system/status|system/task|update|health|"
               r"rootfolder|qualityprofile|diskspace|history|history/since|history/movie|command|command/\d+|moviefile|moviefile/\d+|release|importlist/movie|blocklist|tag)" + _COVER,
        "POST": _V3 + r"(movie|command|release|history/failed/\d+)",
        "PUT": _V3 + r"(movie/\d+|movie/editor)",
        "DELETE": _V3 + r"(queue/\d+|movie/\d+|moviefile/\d+|blocklist/\d+|blocklist/bulk)",
    },
    "prowlarr": {
        "GET": r"api/v1/(indexer|indexer/\d+|indexerstats|indexerstatus|health|system/status|system/task|update|search|history)",
        "POST": r"api/v1/(search|indexer/test|command)",
        "PUT": r"api/v1/indexer/\d+",
    },
    "qbit": {
        "GET": r"api/v2/(torrents/info|torrents/properties|torrents/files|torrents/trackers|torrents/categories|transfer/info|transfer/speedLimitsMode|"
               r"transfer/downloadLimit|transfer/uploadLimit|app/version|sync/maindata)",
        "POST": r"api/v2/(torrents/(add|pause|resume|stop|start|delete|recheck|reannounce|setForceStart|topPrio|bottomPrio|increasePrio|decreasePrio|setCategory)|"
                r"transfer/(toggleSpeedLimitsMode|setDownloadLimit|setUploadLimit))",
    },
    "jellyfin": {
        "GET": r"(Sessions|Items/Counts|System/Info|Users|Users/[0-9a-f]{32}/Items/(Latest|Resume)|Items/[0-9a-f]{32}/Images/(Primary|Backdrop|Thumb)(/\d+)?)",
        "POST": r"(Sessions/[0-9a-f]{32}/Playing/(PlayPause|Pause|Unpause|Stop|NextTrack|PreviousTrack)|Library/Refresh|Users/[0-9a-f]{32}/PlayedItems/[0-9a-f]{32})",
        "DELETE": r"Users/[0-9a-f]{32}/PlayedItems/[0-9a-f]{32}",
    },
}
_ARR_KEY = {"sonarr": ("SONARR", "X-Api-Key"), "radarr": ("RADARR", "X-Api-Key"), "prowlarr": ("PROWLARR", "X-Api-Key"),
            "qbit": ("QBIT", None), "jellyfin": ("JELLYFIN", "X-Emby-Token")}


_ARR_CACHE = {}
_ARR_CACHE_LOCK = threading.Lock()
# Live data (queues, torrents, sessions, commands, releases) is never cached; libraries and artwork are, briefly,
# because Radarr/Prowlarr on CT108 can take 20+ s per call.
_ARR_NOCACHE = re.compile(r"(queue|torrents|transfer|sync|Sessions|command|release|search|history|blocklist|system/task|Resume|Latest)")


_ARR_JOBS = {}
_ARR_DISK_WARNED = 0
_ARR_SLOW = re.compile(r"/(release|search)$")


def _arr_job(key, path, query):
    out = _arr_fetch("GET", path, query, None, None, timeout=360)
    with _ARR_CACHE_LOCK:
        _ARR_JOBS[key] = {"done": True, "out": out, "t": time.time()}


def arr_proxy(method, path, query, body, ctype):
    """Returns (status, bytes, content-type). GETs are cached ~2 min (artwork 1 day); any write clears that service.
    Indexer searches (…/release, prowlarr search) run as background jobs: 202 {"pending": true} until done, poll again."""
    svc = path.split("/")[2] if path.count("/") >= 2 else ""
    key = path + "?" + (query or "")
    if method == "GET" and _ARR_SLOW.search(path):
        with _ARR_CACHE_LOCK:
            j = _ARR_JOBS.get(key)
            if not j or (j["done"] and time.time() - j["t"] > 600):
                for k in [k for k, v in _ARR_JOBS.items() if v["done"] and time.time() - v["t"] > 600]:
                    _ARR_JOBS.pop(k, None)
                j = _ARR_JOBS[key] = {"done": False, "t": time.time()}
                threading.Thread(target=_arr_job, args=(key, path, query), daemon=True).start()
        for _ in range(40):
            if j.get("done") or _ARR_JOBS.get(key, {}).get("done"):
                return _ARR_JOBS[key]["out"]
            time.sleep(0.5)
        return 202, json.dumps({"pending": True, "elapsed": int(time.time() - j["t"])}).encode(), "application/json"
    if method != "GET":
        with _ARR_CACHE_LOCK:
            for k in [k for k in _ARR_CACHE if k.startswith(f"/arr/{svc}/") and "MediaCover" not in k and "/Images/" not in k]:
                _ARR_CACHE.pop(k, None)
    elif not _ARR_NOCACHE.search(path):
        with _ARR_CACHE_LOCK:
            hit = _ARR_CACHE.get(key)
        if hit and hit[0] > time.time():
            return hit[1]
        if hit and hit[0] > time.time() - 6 * 3600:  # stale: answer now, refresh behind the scenes
            with _ARR_CACHE_LOCK:
                busy = key in _ARR_REFRESHING
                _ARR_REFRESHING.add(key)
            if not busy:
                threading.Thread(target=_arr_refresh, args=(key, path, query), daemon=True).start()
            return hit[1]
    out = _arr_fetch(method, path, query, body, ctype)
    if method == "GET" and out[0] == 200 and not _ARR_NOCACHE.search(path):
        ttl = 86400 if ("MediaCover" in path or "/Images/" in path) else 120
        with _ARR_CACHE_LOCK:
            if len(_ARR_CACHE) > 600:
                _ARR_CACHE.clear()
            _ARR_CACHE[key] = (time.time() + ttl, out)
    return out


_ARR_REFRESHING = set()


def _arr_refresh(key, path, query):
    try:
        out = _arr_fetch("GET", path, query, None, None)
        if out[0] == 200:
            with _ARR_CACHE_LOCK:
                _ARR_CACHE[key] = (time.time() + 120, out)
    finally:
        with _ARR_CACHE_LOCK:
            _ARR_REFRESHING.discard(key)


def _arr_fetch(method, path, query, body, ctype, timeout=90):
    m = re.fullmatch(r"/arr/([a-z]+)/(.+)", path)
    svc, rest = (m.group(1), m.group(2)) if m else ("", "")
    pat = _ARR_ALLOW.get(svc, {}).get(method)
    if not pat or not re.fullmatch(pat, rest):
        return 403, b'{"ok":false,"error":"not allowed"}', "application/json"
    pre, header = _ARR_KEY[svc]
    base = read_env_value(_ARR_ENV, pre + "_URL")
    if not base:
        return 404, b'{"ok":false,"error":"service not configured"}', "application/json"
    if svc == "jellyfin":
        key = read_env_value(_ARR_ENV, "JELLYFIN_API_KEY")
    else:
        key = read_env_value(_ARR_ENV, pre + "_KEY")
    req = urllib.request.Request(base + "/" + rest + (("?" + query) if query else ""), data=body if method in ("POST", "PUT") else None,
                                 method=method)
    if header:
        req.add_header(header, key)
    if ctype:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(8_000_000), r.headers.get("Content-Type", "application/json")
    except urllib.error.HTTPError as e:
        return e.code, e.read(100_000), e.headers.get("Content-Type", "application/json")
    except Exception as e:
        return 502, json.dumps({"ok": False, "error": type(e).__name__}).encode(), "application/json"



def arr_watch_loop():
    """nzb360-style "downloaded" notifications: every 2 min, Sonarr/Radarr imports since the last look become one
    hub event per show/movie (type media, url app:media)."""
    since = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    warm = ["/arr/sonarr/api/v3/series", "/arr/sonarr/api/v3/health", "/arr/sonarr/api/v3/diskspace", "/arr/radarr/api/v3/movie",
            "/arr/radarr/api/v3/health", "/arr/radarr/api/v3/qualityprofile", "/arr/radarr/api/v3/rootfolder",
            "/arr/radarr/api/v3/importlist/movie?includeRecommendations=true", "/arr/prowlarr/api/v1/indexer",
            "/arr/prowlarr/api/v1/health", "/arr/prowlarr/api/v1/indexerstats", "/arr/prowlarr/api/v1/indexerstatus"]
    while True:
        # Keep the slow services' common pages in the proxy cache so the app opens instantly.
        for w in warm:
            p, _, q = w.partition("?")
            threading.Thread(target=arr_proxy, args=("GET", p, q, None, None), daemon=True).start()
        time.sleep(120)
        now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        for svc, label in (("sonarr", "Sonarr"), ("radarr", "Radarr")):
            q = "date=" + urllib.parse.quote(since) + "&eventType=3" + ("&includeSeries=true&includeEpisode=true" if svc == "sonarr" else "&includeMovie=true")
            try:
                status, data, _ = arr_proxy("GET", f"/arr/{svc}/api/v3/history/since", q, None, None)
                if status != 200:
                    continue
                groups = {}
                for r in json.loads(data or b"[]"):
                    if svc == "sonarr":
                        name = (r.get("series") or {}).get("title") or "TV"
                        e = r.get("episode") or {}
                        groups.setdefault(name, []).append("S%02dE%02d" % (e.get("seasonNumber", 0), e.get("episodeNumber", 0)))
                    else:
                        m = r.get("movie") or {}
                        groups.setdefault(f"{m.get('title') or 'Movie'} ({m.get('year') or ''})", []).append(
                            ((r.get("quality") or {}).get("quality") or {}).get("name", ""))
                for name, items in groups.items():
                    body = (f"{len(items)} episodes: " if len(items) > 1 else "") + ", ".join(sorted(set(items)))[:300]
                    emit_event("media", f"Downloaded: {name}", body + f" · {label}", priority=2, url="app:media")
            except Exception:
                pass
        since = now
        try:  # the media disk is a sparse image bigger than its ZFS pool: warn early, at most once a day
            status, data, _ = arr_proxy("GET", "/arr/sonarr/api/v3/diskspace", "", None, None)
            if status == 200:
                for d in json.loads(data or b"[]"):
                    free, total = d.get("freeSpace", 0), d.get("totalSpace", 0)
                    if total > 100 << 30 and free < 100 << 30 and alert_allowed("disk:media", cooldown=86400):
                        emit_event("alert", "Media disk low: %.0f GB free" % (free / 2**30),
                                   f"{d.get('path')} has {free / 2**30:.0f} GB of {total / 2**40:.1f} TB left. Under ~60 GB the vault pool fills and the disk goes read-only.",
                                   priority=4, url="app:media", mute_key="disk:media")
                        break
        except Exception:
            pass

APPROVAL_MODES = (
    ("manual", "Ask every time", "Every risky command waits for your tap."),
    ("smart", "Smart", "A second model approves the safe ones; you only see the doubtful ones."),
    ("off", "Never ask", "Hermes runs everything without asking. Use with care."),
)


def hermes_config_path():
    return os.path.join(os.environ.get("HERMES_HOME") or globals().get("HERMES_HOME") or os.path.expanduser("~/.hermes"), "config.yaml")


def _approvals_mode_span(text):
    """(start, end, mode) of the 'mode:' line inside the top-level 'approvals:' block, or None."""
    in_block = False
    pos = 0
    for line in text.splitlines(True):
        if re.match(r"^approvals:\s*(#.*)?$", line):
            in_block = True
        elif in_block and line.strip() and not line.startswith((" ", "\t")):
            return None
        elif in_block:
            m = re.match(r"^(\s+mode:\s*)(['\"]?)([A-Za-z]+)\2(\s*(#.*)?)$", line.rstrip("\n"))
            if m:
                return pos + len(m.group(1)), pos + len(m.group(1)) + len(m.group(2)) * 2 + len(m.group(3)), m.group(3).lower()
        pos += len(line)
    return None


def approvals_get():
    try:
        with open(hermes_config_path(), encoding="utf-8") as f:
            span = _approvals_mode_span(f.read())
    except OSError:
        span = None
    mode = span[2] if span else "manual"
    if mode in ("false", "no", "never"):
        mode = "off"
    return {"mode": mode, "options": [{"value": v, "label": l, "description": d} for v, l, d in APPROVAL_MODES]}


def approvals_set(mode):
    """Rewrite approvals.mode in Hermes' config.yaml (one backup kept); the gateway re-reads it per command."""
    if mode not in [m[0] for m in APPROVAL_MODES]:
        raise ValueError("mode must be one of manual, smart, off")
    path = hermes_config_path()
    with open(path, encoding="utf-8") as f:
        text = f.read()
    span = _approvals_mode_span(text)
    if span is None:
        raise ValueError("config.yaml has no approvals.mode to change")
    if span[2] != mode:
        try:
            with open(path + ".pre-approvals", "w", encoding="utf-8") as f:
                f.write(text)
        except OSError:
            pass
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(text[:span[0]] + mode + text[span[1]:])
        os.replace(tmp, path)
    return approvals_get()


_wake_last = [0.0]


def wake_config_set(d):
    """POST /ops/wake/config {mac, host, port}: the machine Wake-on-LAN targets (app module setup); empty mac clears it."""
    path = os.path.join(os.environ.get("HUB_DIR") or "/opt/hermes-hub", "wake.json")
    mac = str(d.get("mac") or "").replace(":", "").replace("-", "").strip().upper()
    if not mac:
        try:
            os.remove(path)
        except OSError:
            pass
        return {"ok": True, "cleared": True}
    host = str(d.get("host") or "").strip()
    if not re.fullmatch(r"[0-9A-F]{12}", mac):
        raise ValueError("MAC address like AA:BB:CC:DD:EE:01")
    if not re.fullmatch(r"[A-Za-z0-9.:-]{1,253}", host):
        raise ValueError("host name or IP of the machine")
    port = int(d.get("port") or 22)
    with open(path, "w") as f:
        json.dump({"mac": ":".join(mac[i:i + 2] for i in range(0, 12, 2)), "host": host, "port": port}, f)
    return {"ok": True}


def wake_config():
    """{'mac','host','port'} of the machine to wake, from env or HUB_DIR/wake.json; None when unset."""
    try:
        with open(os.path.join(os.environ.get("HUB_DIR") or "/opt/hermes-hub", "wake.json")) as f:
            d = json.load(f)
    except (OSError, ValueError):
        d = {}
    mac = (os.environ.get("HUB_WAKE_MAC") or d.get("mac") or "").replace(":", "").replace("-", "").strip()
    host = os.environ.get("HUB_WAKE_HOST") or d.get("host") or ""
    if len(mac) != 12 or not host:
        return None
    try:
        bytes.fromhex(mac)
    except ValueError:
        return None
    return {"mac": mac, "host": host, "port": int(os.environ.get("HUB_WAKE_PORT") or d.get("port") or 22)}


def _tcp_open(host, port, timeout=0.6):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def wake_machine():
    """Send a Wake-on-LAN packet unless the target already answers; at most one packet per 20 s."""
    cfg = wake_config()
    if not cfg:
        return {"ok": False, "error": "no wake target configured"}
    if _tcp_open(cfg["host"], cfg["port"]):
        return {"ok": True, "state": "up"}
    if time.time() - _wake_last[0] < 20:
        return {"ok": True, "state": "waking"}
    _wake_last[0] = time.time()
    pkt = b"\xff" * 6 + bytes.fromhex(cfg["mac"]) * 16
    sk = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sk.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        net = cfg["host"].rsplit(".", 1)[0]
        for addr in (net + ".255", "255.255.255.255"):
            try:
                sk.sendto(pkt, (addr, 9))
            except OSError:
                pass
    finally:
        sk.close()
    return {"ok": True, "state": "waking"}


class Handler(BaseHTTPRequestHandler):
    server_version = "hermes-hub/1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, obj, status=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _bearer(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            return auth[7:].strip()
        return None

    def _authed(self):
        bearer = self._bearer()
        if is_authorized(bearer):
            return True
        # Apps before 7.43 asked for updates with the Codex/Claude bridge token of the active profile. The update
        # check and the APK are not secret, so those two routes also accept the bridge tokens (and nothing else does).
        return self.path.split("?", 1)[0] in ("/app/latest", "/app/apk") and is_bridge_token(bearer)

    def _read_body(self, limit=None):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if limit and length > limit:
            return None
        return self.rfile.read(length) if length else b""

    def _tailnet_device(self):
        """(who, host) when the caller is a device on this tailnet owned by this host's Tailscale user
        (checked with `tailscale whois`), else an error string. Requests through the public proxy never pass."""
        ip = self.client_address[0]
        try:
            a, b = (int(x) for x in ip.split(".")[:2])
            if not (a == 100 and 64 <= b <= 127):
                return "pair over Tailscale"
            who = json.loads(subprocess.run(["tailscale", "whois", "--json", ip], capture_output=True, text=True, timeout=5).stdout)
            me = json.loads(subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True, timeout=5).stdout)
            mine = me["User"][str(me["Self"]["UserID"])]["LoginName"]
            if who["UserProfile"]["LoginName"] != mine:
                return "not your device"
            return who, me["Self"]["TailscaleIPs"][0]
        except Exception:
            return "could not verify device"

    def _pair_cert(self):
        """Mutual-TLS client certificate for this phone: hub key AND one of the owner's tailnet devices."""
        if not self._authed():
            return self._json({"ok": False, "error": "unauthorized"}, 401)
        dev = self._tailnet_device()
        if isinstance(dev, str):
            return self._json({"ok": False, "error": dev}, 403)
        if not os.path.exists(os.path.join(MTLS_DIR, "ca.key")):
            return self._json({"ok": False, "error": "no device CA on this hub"}, 404)
        return self._json(issue_device_cert(dev[0].get("Node", {}).get("ComputedName", "")))

    def _pair(self):
        """Zero-typing app setup: only a device on this tailnet that belongs to the same Tailscale
        user as this host (checked with `tailscale whois`) gets the connection details."""
        dev = self._tailnet_device()
        if isinstance(dev, str):
            return self._json({"ok": False, "error": dev}, 403)
        who, host = dev
        out = {"ok": True, "endpoint": f"http://{host}:{urllib.parse.urlparse(HERMES_API).port or 8889}", "key": read_env_value(HERMES_ENV, "API_SERVER_KEY") or "",
               "hub": f"http://{host}:{LISTEN_PORT}", "device": who.get("Node", {}).get("ComputedName", "")}
        codex = read_env_value(os.path.expanduser("~/.hermes/secrets/codex-bridge.env"), "CODEX_BRIDGE_TOKEN")
        if codex:  # Codex remote (codex-rc): the app adds a "Codex" profile from this
            port = read_env_value(os.path.expanduser("~/.hermes/secrets/codex-bridge.env"), "CODEX_BRIDGE_PORT") or "8795"
            out["codex"] = {"endpoint": f"http://{host}:{port}", "key": codex}
        claude = read_env_value(os.path.expanduser("~/.hermes/secrets/claude-bridge.env"), "CLAUDE_BRIDGE_TOKEN")
        if claude:  # Claude Code bridge: the app's "Claude Code" harness pairs from this
            port = read_env_value(os.path.expanduser("~/.hermes/secrets/claude-bridge.env"), "CLAUDE_BRIDGE_PORT") or "8792"
            out["claude"] = {"endpoint": f"http://{host}:{port}", "key": claude}
        return self._json(out)

    def _server_profiles(self):
        """Profiles the gateway serves at /p/<name> with their own API_SERVER_KEY, for the app's
        one-tap "Add profile". Only the main key reaches this (is_authorized checks /v1), and a
        profile is listed only if its key works right now."""
        out = []
        try:
            names = sorted(os.listdir(PROFILES_DIR))
        except OSError:
            return out
        for name in names:
            if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
                continue
            key = read_env_value(os.path.join(PROFILES_DIR, name, ".env"), "API_SERVER_KEY")
            if not key:
                continue
            req = urllib.request.Request(f"{HERMES_API}/p/{name}/v1/capabilities",
                                         headers={"Authorization": f"Bearer {key}"})
            try:
                with urllib.request.urlopen(req, timeout=5) as resp:
                    if resp.status != 200:
                        continue
            except Exception:
                continue
            out.append({"name": name, "path": f"/p/{name}", "key": key})
        return out

    def _term(self, method, path, qs):
        """/term/*: shell sessions for the app's Terminal module. A root shell, so hub key AND one of the owner's own
        Tailscale devices; never through the public proxy (see term_sessions.py)."""
        import term_sessions as ts
        if not self._authed():
            self._read_body()
            return self._json({"ok": False, "error": "unauthorized"}, 401)
        body = {}
        if method == "POST":
            try:
                body = json.loads(self._read_body(100_000) or b"{}")
            except Exception:
                return self._json({"ok": False, "error": "bad json"}, 400)
        dev = self._tailnet_device()
        if isinstance(dev, str):
            return self._json({"ok": False, "error": "Terminal works over Tailscale only (" + dev + ")"}, 403)
        hosts = os.path.join(HUB_DIR, "terminal-hosts.txt")
        q = lambda k, d="": (qs.get(k, [d])[0] if qs else d)
        try:
            if path == "/term/targets":
                return self._json({"ok": True, "targets": ts.targets(hosts)})
            if path == "/term/list":
                return self._json({"ok": True, "sessions": ts.listing()})
            if path == "/term/open":
                sid = ts.open_session(str(body.get("target") or "hub"), hosts, int(body.get("rows") or 30), int(body.get("cols") or 80), str(body.get("name") or "")[:40], bool(body.get("persist", True)), str(body.get("resume") or ""))
                _audit("terminal open " + str(body.get("target") or "hub"), dev[0].get("Node", {}).get("ComputedName", "?"))
                return self._json({"ok": True, "id": sid})
            if path == "/term/read":
                data, nxt, alive = ts.get(q("id")).read(int(q("since", "0")), min(float(q("wait", "15")), 25.0))
                return self._json({"ok": True, "data": data.decode("utf-8", "replace"), "next": nxt, "alive": alive})
            if path == "/term/send" and method == "POST":
                ts.get(str(body.get("id"))).write(str(body.get("data", "")).encode("utf-8")[:65536])
                return self._json({"ok": True})
            if path == "/term/resize" and method == "POST":
                ts.get(str(body.get("id"))).resize(int(body.get("rows") or 30), int(body.get("cols") or 80))
                return self._json({"ok": True})
            if path == "/term/close" and method == "POST":
                ts.close_session(str(body.get("id")))
                return self._json({"ok": True})
        except KeyError as e:
            return self._json({"ok": False, "error": str(e).strip("'\"")}, 404)
        except (ValueError, RuntimeError) as e:
            return self._json({"ok": False, "error": str(e)}, 400)
        return self._json({"ok": False, "error": "not found"}, 404)

    def _media(self, method):
        """/media/...: media servers (Jellyfin, Emby, Plex, Ampache) behind one API, see media_servers.py."""
        import media_servers
        path, _, query = self.path.partition("?")
        body = self._read_body(100_000) if method == "POST" else None
        status, data, ctype = media_servers.route(method, path, query, body, lambda k: read_env_value(_ARR_ENV, k), _ARR_ENV)
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _arr(self, method):
        path, _, query = self.path.partition("?")
        body = self._read_body(2_000_000) if method in ("POST", "PUT") else None
        status, data, ctype = arr_proxy(method, path, query, body, self.headers.get("Content-Type"))
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_PUT(self):
        if not self._authed():
            self._read_body()
            return self._json({"ok": False, "error": "unauthorized"}, 401)
        if self.path.startswith("/arr/"):
            return self._arr("PUT")
        return self._json({"ok": False, "error": "not found"}, 404)

    def do_DELETE(self):
        if not self._authed():
            return self._json({"ok": False, "error": "unauthorized"}, 401)
        if self.path.startswith("/arr/"):
            return self._arr("DELETE")
        return self._json({"ok": False, "error": "not found"}, 404)

    def do_GET(self):
        from urllib.parse import urlparse, parse_qs
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        if path == "/health":
            return self._json({"ok": True, "version": "1"})

        if path == "/pair":
            return self._pair()

        if path == "/pair/cert":
            return self._pair_cert()

        if not self._authed():
            return self._json({"ok": False, "error": "unauthorized"}, 401)

        if path == "/events":
            since = int(qs.get("since", ["0"])[0] or "0")
            limit = min(int(qs.get("limit", ["50"])[0] or "50"), 500)
            latest = qs.get("latest", [""])[0] == "1"  # newest N (still returned oldest-first)
            with db() as conn:
                rows = conn.execute(
                    "SELECT * FROM events WHERE id > ? ORDER BY id " + ("DESC" if latest else "ASC") + " LIMIT ?",
                    (since, limit),
                ).fetchall()
            events = [dict(r) for r in (reversed(rows) if latest else rows)]
            return self._json({"events": events})

        m = re.match(r"^/jobs/([^/]+)/outputs$", path)
        if m:
            job_id = m.group(1)
            limit = min(int(qs.get("limit", ["10"])[0] or "10"), 100)
            job_dir = os.path.join(CRON_OUTPUT_DIR, job_id)
            outputs = []
            try:
                files = sorted(
                    (f for f in os.listdir(job_dir) if os.path.isfile(os.path.join(job_dir, f))),
                    key=lambda f: os.path.getmtime(os.path.join(job_dir, f)),
                    reverse=True,
                )[:limit]
                for fname in files:
                    fpath = os.path.join(job_dir, fname)
                    with open(fpath, "r", errors="replace") as f:
                        text = f.read(20000)
                    outputs.append({
                        "ts": int(os.path.getmtime(fpath)), "name": fname, "text": text,
                    })
            except FileNotFoundError:
                pass
            return self._json({"outputs": outputs})

        if path.startswith("/arr/"):
            return self._arr("GET")

        if path.startswith("/media/"):
            return self._media("GET")

        if path.startswith("/term/"):
            return self._term("GET", path, qs)

        if path == "/ops/wake/config":
            return self._json(dict(wake_config() or {}, ok=True))

        if path == "/profiles":
            return self._json({"profiles": self._server_profiles()})

        if path == "/search":
            q = (qs.get("q", [""])[0] or "").strip()
            limit = min(int(qs.get("limit", ["30"])[0] or "30"), 200)
            results = self._search(q, limit)
            return self._json({"results": results})

        if path == "/ops/services":
            return self._json(self._ops_services())

        if path == "/app/latest":
            d = app_latest()
            return self._json(d if d is not None else {"ok": False, "error": "not found"}, 200 if d is not None else 404)

        if path == "/app/apk":
            p = app_apk_path()
            if not p:
                if (app_latest() or {}).get("ready") is False:
                    return self._json({"error": "downloading"}, 503)
                return self._json({"ok": False, "error": "not found"}, 404)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(os.path.getsize(p)))
            self.end_headers()
            with open(p, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return

        if path == "/media":
            # Serves files Hermes attaches as MEDIA:/abs/path (and files the phone uploaded), any type but secrets.
            types = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif",
                     ".webp": "image/webp", ".pdf": "application/pdf", ".mp3": "audio/mpeg", ".ogg": "audio/ogg",
                     ".wav": "audio/wav", ".m4a": "audio/mp4", ".mp4": "video/mp4", ".webm": "video/webm",
                     ".mov": "video/quicktime", ".opus": "audio/ogg", ".flac": "audio/flac", ".txt": "text/plain",
                     ".md": "text/markdown", ".csv": "text/csv", ".html": "text/html", ".zip": "application/zip",
                     ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                     ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                     ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                     ".odt": "application/vnd.oasis.opendocument.text", ".epub": "application/epub+zip",
                     ".svg": "image/svg+xml", ".bmp": "image/bmp"}
            real = os.path.realpath(qs.get("path", [""])[0] or "")
            ext = os.path.splitext(real)[1].lower()
            ctype = types.get(ext) or mimetypes.guess_type(real)[0] or "application/octet-stream"
            low = real.lower()
            secret = any(x in low for x in ("/.ssh/", "/secrets/", "/.gnupg/", "/.aws/", "/.config/gcloud/", "/.env", "credential",
                                            "password", "token", "secret", "id_rsa", "id_ed25519", "/etc/", "auth.json", ".netrc",
                                            "/.hermes/config.yaml", "/.kube/", "/.docker/config")) \
                or ext in (".key", ".pem", ".p12", ".pfx", ".jks", ".keystore", ".kdbx", ".db", ".sqlite", ".sqlite3")
            if not os.path.isabs(real) or not os.path.isfile(real) or secret:
                return self._json({"ok": False, "error": "not found"}, 404)
            if os.path.getsize(real) > 25 * 1024 * 1024:
                return self._json({"ok": False, "error": "too large"}, 413)
            with open(real, "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if path == "/ops/alerts":
            return self._json(alert_overview())

        if path == "/ops/approvals":
            return self._json(approvals_get())

        if path == "/ops/summary":
            return self._json(ops_summary())

        if path == "/app/backup-addresses":
            # Other ways to reach this server (Tailscale IP, public URL), one per line; the app fills its
            # Backup addresses setting from this when the user hasn't set any.
            try:
                lines = [l.strip() for l in open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "backup-addresses.txt"))]
            except OSError:
                lines = []
            return self._json({"ok": True, "addresses": [l for l in lines if l and not l.startswith("#")]})

        if path == "/hermes/commands":
            try:
                return self._json(gw_commands())
            except GatewayError as e:
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/hermes/usage":
            out, code = gw_route(gw_usage, {"session_id": qs.get("session_id", [""])[0],
                                          "profile": qs.get("profile", [""])[0]})
            return self._json(out, code)

        if path == "/hermes/rollback":
            out, code = gw_route(gw_rollback, {"session_id": qs.get("session_id", [""])[0], "profile": qs.get("profile", [""])[0]})
            return self._json(out, code)

        if path == "/ops/infra":
            return self._json(infra())

        if path == "/ops/network":
            return self._json(_bg_cached(_net_cache, 60, _network_probe))

        if path == "/ops/backups":
            return self._json(_bg_cached(_bak_cache, 600, _backups_probe))

        if path == "/ops/updates":
            return self._json(_bg_cached(_upd_cache, 3600, _updates_probe))

        if path == "/ops/history":
            out, code = history_query((qs.get("key", [""])[0] or ""), (qs.get("range", ["24h"])[0] or "24h"))
            return self._json(out, code)

        if path == "/ops/history/keys":
            with db() as conn:
                return self._json({"ok": True, "keys": [r[0] for r in conn.execute("SELECT DISTINCT key FROM samples ORDER BY key")]})

        if path == "/ops/infra/guest/snapshots":
            out, code = guest_snapshots((qs.get("node", [""])[0] or ""), (qs.get("type", [""])[0] or ""), (qs.get("vmid", [""])[0] or ""))
            return self._json(out, code)

        if path == "/ops/sync":
            v = _bg_cached(_sync_cache, 300, syncthing)
            out, code = v if isinstance(v, tuple) else ({"ok": False, "error": "still loading"}, 503)
            return self._json(out, code)

        if path == "/ops/security":
            v = _bg_cached(_sec_cache, 300, crowdsec)
            out, code = v if isinstance(v, tuple) else ({"ok": False, "error": "still loading"}, 503)
            return self._json(out, code)

        if path == "/ops/infra/tasks":
            out, code = infra_tasks()
            return self._json(out, code)

        if path == "/ops/infra/task/log":
            out, code = infra_task_log((qs.get("node", [""])[0] or ""), (qs.get("upid", [""])[0] or ""))
            return self._json(out, code)

        if path == "/ops/infra/rrd":
            out, code = infra_rrd((qs.get("node", [""])[0] or ""), (qs.get("type", [""])[0] or ""), (qs.get("vmid", [""])[0] or ""))
            return self._json(out, code)

        if path == "/ops/infra/guest":
            out, code = infra_guest((qs.get("node", [""])[0] or ""), (qs.get("type", [""])[0] or ""), (qs.get("vmid", [""])[0] or ""))
            return self._json(out, code)

        if path == "/ops/infra/node":
            out, code = infra_node((qs.get("node", [""])[0] or ""))
            return self._json(out, code)

        if path == "/ops/logs":
            name = (qs.get("name", [""])[0] or "").strip()
            lines = min(int(qs.get("lines", ["200"])[0] or "200"), 2000)
            allowlist = load_allowlist()
            containers = self._docker_names()
            if name not in allowlist and name not in containers:
                return self._json({"ok": False, "error": "not allowlisted"}, 403)
            aq = parse_qs(parsed.query, keep_blank_values=True).get("after")
            if aq is not None:  # incremental follow; legacy shape below when absent
                ls, cur = logs_after(name, name in containers, aq[0], lines)
                return self._json({"ok": True, "lines": ls, "cursor": cur})
            if name in containers:
                out = subprocess.run(
                    ["docker", "logs", "--tail", str(lines), name],
                    capture_output=True, text=True, timeout=15,
                )
                text = (out.stdout or "") + (out.stderr or "")
            else:
                text = journal_tail(name, lines)
            return self._json({"lines": text.splitlines()})

        return self._json({"ok": False, "error": "not found"}, 404)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if not self._authed():
            self._read_body()
            return self._json({"ok": False, "error": "unauthorized"}, 401)

        if path.startswith("/arr/"):
            return self._arr("POST")

        if path.startswith("/media/"):
            return self._media("POST")

        if path.startswith("/term/"):
            return self._term("POST", path, {})

        if path == "/ops/infra/node/wake":
            try:
                data = json.loads(self._read_body(10_000) or b"{}")
            except Exception:
                return self._json({"ok": False, "error": "bad json"}, 400)
            out, code = node_wake(data)
            return self._json(out, code)

        if path in ("/ops/infra/guest/action", "/ops/infra/guest/snapshot"):
            try:
                data = json.loads(self._read_body(10_000) or b"{}")
            except Exception:
                return self._json({"ok": False, "error": "bad json"}, 400)
            out, code = (guest_action if path.endswith("action") else guest_snapshot)(data)
            return self._json(out, code)

        if path in ("/hermes/slash", "/hermes/rewind", "/hermes/rollback"):
            try:
                data = json.loads(self._read_body(100_000) or b"{}")
            except Exception:
                return self._json({"ok": False, "error": "bad json"}, 400)
            fn = {"/hermes/slash": gw_slash, "/hermes/rewind": gw_rewind, "/hermes/rollback": gw_rollback}[path]
            out, code = gw_route(fn, data)
            return self._json(out, code)

        if path == "/push/register":
            body = self._read_body(1_000_000)
            try:
                data = json.loads(body or b"{}")
            except Exception:
                return self._json({"ok": False, "error": "bad json"}, 400)
            endpoint = (data.get("endpoint") or "").strip()
            if not endpoint:
                return self._json({"ok": False, "error": "missing endpoint"}, 400)
            with db() as conn:
                conn.execute(
                    "INSERT OR IGNORE INTO push_endpoints (endpoint, created_ts) VALUES (?,?)",
                    (endpoint, int(time.time())),
                )
            return self._json({"ok": True})

        if path == "/decisions":
            # Optional forwarder to an external decisions API using the server's key (env-configured).
            if not (DECISIONS_URL and DECISIONS_API_KEY):
                return self._json({"ok": False, "error": "not configured"}, 503)
            body = self._read_body(200_000)
            try:
                data = json.loads(body or b"{}")
            except Exception:
                return self._json({"ok": False, "error": "bad json"}, 400)
            model = data.get("model") or ""
            if (DECISIONS_MODEL_PREFIX and not str(model).startswith(DECISIONS_MODEL_PREFIX)) \
                    or "state" not in data or not isinstance(data.get("questions"), dict):
                return self._json({"ok": False, "error": "need an allowed model, state and questions"}, 400)
            out = {"model": model, "state": data["state"], "questions": data["questions"]}
            if data.get("session_id"):
                out["session_id"] = str(data["session_id"])[:256]
            req = urllib.request.Request(DECISIONS_URL, data=json.dumps(out).encode(),
                                         headers={"Authorization": "Bearer " + DECISIONS_API_KEY, "Content-Type": "application/json"},
                                         method="POST")
            try:
                with urllib.request.urlopen(req, timeout=15) as resp:
                    return self._json(json.loads(resp.read() or b"{}"), resp.status)
            except urllib.error.HTTPError as e:
                try:
                    detail = json.loads(e.read() or b"{}")
                except Exception:
                    detail = {"ok": False, "error": "upstream HTTP %d" % e.code}
                return self._json(detail, e.code)
            except Exception as e:
                return self._json({"ok": False, "error": "upstream unreachable: %s" % type(e).__name__}, 502)

        if path == "/voice/live" or path.startswith("/voice/live/"):
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length < 0 or length > 16_000 * 2 * 30:
                self._read_body()
                return self._json({"ok": False, "error": "bad audio length"}, 413)
            body = self.rfile.read(length) if length else b""
            parts = path.split("/")  # ["", "voice", "live", id, action]
            try:
                if len(parts) == 3:
                    q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
                    return self._json({"ok": True, "id": LiveTake(q.get("language", ["en"])[0]).id})
                take = LiveTake.takes.get(parts[3]) if len(parts) == 5 else None
                if take is None:
                    return self._json({"ok": False, "error": "no such take"}, 404)
                if parts[4] == "audio":
                    return self._json(take.audio(body))
                if parts[4] == "stop":
                    if body:
                        take.audio(body)
                    return self._json({"ok": True, "text": take.stop()})
                return self._json({"ok": False, "error": "unknown action"}, 404)
            except Exception as e:
                return self._json({"ok": False, "error": f"speech server: {e}"}, 502)

        if path == "/voice/stt":
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length <= 0 or length > 16_000 * 2 * 300:  # 5 min of 16 kHz audio
                self._read_body()
                return self._json({"ok": False, "error": "bad audio length"}, 413)
            pcm = self.rfile.read(length)
            q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
            try:
                text = wyoming_stt(pcm, q.get("language", ["en"])[0], int(q.get("rate", ["16000"])[0]))
            except Exception as e:
                return self._json({"ok": False, "error": f"speech server: {e}"}, 502)
            return self._json({"ok": True, "text": text})

        if path == "/notify":
            body = self._read_body(1_000_000)
            try:
                data = json.loads(body or b"{}")
            except Exception:
                return self._json({"ok": False, "error": "bad json"}, 400)
            title = str(data.get("title") or "")
            btext = str(data.get("body") or "")
            type_ = data.get("type") or "info"
            if type_ not in ("alert", "job", "briefing", "proposal", "done", "info"):
                return self._json({"ok": False, "error": "bad type"}, 400)
            priority = int(data.get("priority") or 3)
            priority = max(1, min(5, priority))
            eid = emit_event(
                type_, title, btext, priority,
                session_id=data.get("session_id") or None,
                job_id=data.get("job_id") or None,
                url=data.get("url") or None,
            )
            return self._json({"ok": True, "id": eid})

        if path == "/files":
            length = int(self.headers.get("Content-Length", "0") or "0")
            max_bytes = 50 * 1024 * 1024
            if length > max_bytes:
                self.rfile.read(0)
                return self._json({"ok": False, "error": "too large"}, 413)
            data = self.rfile.read(length)
            fname = safe_filename(self.headers.get("X-Filename", "upload"))
            os.makedirs(INCOMING_DIR, mode=0o700, exist_ok=True)
            dest = os.path.join(INCOMING_DIR, fname)
            base, ext = os.path.splitext(dest)
            i = 1
            while os.path.exists(dest):
                dest = f"{base}_{i}{ext}"
                i += 1
            with open(dest, "wb") as f:
                f.write(data)
            return self._json({"ok": True, "path": dest})

        if path == "/ops/wake":
            return self._json(wake_machine())

        if path == "/ops/wake/config":
            try:
                return self._json(wake_config_set(json.loads(self._read_body(2_000) or b"{}")))
            except ValueError as e:
                return self._json({"ok": False, "error": str(e)}, 400)

        if path == "/ops/approvals":
            try:
                data = json.loads(self._read_body(2_000) or b"{}")
                return self._json(approvals_set(str(data.get("mode") or "").strip().lower()))
            except ValueError as e:
                return self._json({"ok": False, "error": str(e)}, 400)
            except OSError as e:
                return self._json({"ok": False, "error": "could not write config: %s" % e}, 500)

        if path in ("/ops/alerts/mute", "/ops/alerts/unmute"):
            try:
                data = json.loads(self._read_body(10_000) or b"{}")
            except Exception:
                return self._json({"ok": False, "error": "bad json"}, 400)
            key = str(data.get("key") or "").strip()
            if not key or len(key) > 200:
                return self._json({"ok": False, "error": "key required"}, 400)
            if path.endswith("/unmute"):
                return self._json({"ok": True, "removed": unmute_alert(key)})
            hours = data.get("hours", 24)
            try:
                hours = float(hours)
            except (TypeError, ValueError):
                return self._json({"ok": False, "error": "hours must be a number"}, 400)
            return self._json({"ok": True, "key": key, "until": mute_alert(key, min(hours, 24 * 365))})

        if path == "/ops/restart":
            body = self._read_body(100_000)
            try:
                data = json.loads(body or b"{}")
            except Exception:
                return self._json({"ok": False, "error": "bad json"}, 400)
            name = (data.get("name") or "").strip()
            if name not in load_allowlist():
                return self._json({"ok": False, "error": "not allowlisted"}, 403)
            if name in self._docker_names():
                # Containers named in the allow-list: restart, or start one that exited.
                action = data.get("action") or "restart"
                if action not in ("restart", "start"):
                    return self._json({"ok": False, "error": "bad action"}, 400)
                r = subprocess.run(["docker", action, name], capture_output=True, text=True, timeout=60)
                _audit(f"docker {name} {action}", "ok" if r.returncode == 0 else r.stderr.strip()[:200])
                st = subprocess.run(["docker", "inspect", "-f", "{{.State.Status}}", name], capture_output=True, text=True, timeout=10).stdout.strip()
                return self._json({"ok": r.returncode == 0, "state": st, "error": r.stderr.strip()[:200]})
            subprocess.run(["systemctl", "restart", name], timeout=30)
            time.sleep(1)
            state, _sub = systemctl_state(name)
            return self._json({"ok": True, "state": state})

        return self._json({"ok": False, "error": "not found"}, 404)

    # ---- helpers ----

    def _search(self, q, limit):
        if not q:
            return []
        results = []
        try:
            uri = f"file:{STATE_DB}?mode=ro"
            conn = sqlite3.connect(uri, uri=True, timeout=10)
            conn.row_factory = sqlite3.Row
            fts_q = q.replace('"', '""')
            rows = conn.execute(
                "SELECT m.id AS message_id, m.session_id, m.role, m.timestamp AS ts, "
                "s.title, snippet(messages_fts, 0, '', '', '...', 20) AS snip "
                "FROM messages_fts JOIN messages m ON m.id = messages_fts.rowid "
                "JOIN sessions s ON s.id = m.session_id "
                "WHERE messages_fts MATCH ? ORDER BY m.id DESC LIMIT ?",
                (f'"{fts_q}"', limit),
            ).fetchall()
            for r in rows:
                results.append({
                    "session_id": r["session_id"], "title": r["title"] or "",
                    "message_id": r["message_id"], "role": r["role"],
                    "snippet": (r["snip"] or "")[:160], "ts": r["ts"],
                })
            conn.close()
        except Exception:
            try:
                uri = f"file:{STATE_DB}?mode=ro"
                conn = sqlite3.connect(uri, uri=True, timeout=10)
                conn.row_factory = sqlite3.Row
                like = f"%{q}%"
                rows = conn.execute(
                    "SELECT m.id AS message_id, m.session_id, m.role, m.timestamp AS ts, "
                    "s.title, m.content FROM messages m JOIN sessions s ON s.id = m.session_id "
                    "WHERE m.content LIKE ? ORDER BY m.id DESC LIMIT ?",
                    (like, limit),
                ).fetchall()
                for r in rows:
                    content = r["content"] or ""
                    idx = content.lower().find(q.lower())
                    start = max(0, idx - 80)
                    snippet = content[start:start + 160]
                    results.append({
                        "session_id": r["session_id"], "title": r["title"] or "",
                        "message_id": r["message_id"], "role": r["role"],
                        "snippet": snippet, "ts": r["ts"],
                    })
                conn.close()
            except Exception:
                pass
        return results

    def _docker_names(self):
        try:
            if not os.path.exists("/usr/bin/docker") and not os.path.exists("/usr/local/bin/docker"):
                return []
            out = subprocess.run(
                ["docker", "ps", "-a", "--format", "{{.Names}}"],
                capture_output=True, text=True, timeout=10,
            ).stdout
            return [n for n in out.splitlines() if n.strip()]
        except Exception:
            return []

    def _ops_services(self):
        services = []
        for unit in load_allowlist():
            state, sub = systemctl_state(unit)
            try:
                out = subprocess.run(
                    ["systemctl", "show", unit, "--property=ActiveEnterTimestamp,Description",
                     "--no-page"], capture_output=True, text=True, timeout=10,
                ).stdout
                props = dict(line.split("=", 1) for line in out.strip().splitlines() if "=" in line)
                since = props.get("ActiveEnterTimestamp", "")
                desc = props.get("Description", "")
            except Exception:
                since, desc = "", ""
            services.append({
                "name": unit, "kind": "systemd", "state": state, "sub": sub,
                "since": since, "description": desc,
            })
        try:
            has_docker = os.path.exists("/usr/bin/docker") or os.path.exists("/usr/local/bin/docker")
        except Exception:
            has_docker = False
        if has_docker:
            try:
                out = subprocess.run(
                    ["docker", "ps", "-a", "--format",
                     "{{.Names}}\t{{.Status}}\t{{.Image}}"],
                    capture_output=True, text=True, timeout=10,
                ).stdout
                for line in out.splitlines():
                    parts = line.split("\t")
                    if len(parts) != 3:
                        continue
                    name, status, image = parts
                    services.append({
                        "name": name, "kind": "docker", "state": status,
                        "sub": "", "since": "", "description": image,
                    })
            except Exception:
                pass
        return {"services": services, "host": host_stats()}


def main():
    init_db()
    threading.Thread(target=cron_relay_loop, daemon=True).start()
    threading.Thread(target=ops_watcher_loop, daemon=True).start()
    threading.Thread(target=arr_watch_loop, daemon=True).start()
    if APP_REPO:
        threading.Thread(target=app_prefetch_loop, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", LISTEN_PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
