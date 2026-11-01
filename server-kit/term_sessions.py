"""Interactive shell sessions for the app's Terminal module: a PTY per session, read by long-poll.

Shells run inside tmux (own socket, own server in its own systemd scope) when tmux is installed, so they outlive a hub
restart: the app re-opens the same id and lands back in the running shell. Without tmux a session lives in this process
only. An idle one is closed after IDLE_S. Targets are this host's shell ("hub") and the ssh
aliases listed (one per line) in terminal-hosts.txt next to hub.py. The routes in hub.py allow it only to the owner's own
Tailscale devices (it is a shell), never through the public proxy."""
import fcntl
import os
import pty
import re
import shlex
import shutil
import signal
import subprocess
import struct
import termios
import threading
import time

IDLE_S = 86_400  # a session nobody has read for a day is closed
MAX_SESSIONS = 12
KEEP = 262_144

TARGET = re.compile(r"^(?:[A-Za-z0-9._-]{1,64}@)?[A-Za-z0-9][A-Za-z0-9._-]{0,252}(?::[0-9]{1,5})?$")

SOCKET = "hermes"
CONF = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmux-hermes.conf")
# tmux is only a holder: no status bar, no prefix key (Ctrl-b reaches the program), the mouse goes to tmux so scrolling works
TMUX_CONF = '''set -g status off
set -g prefix None
unbind C-b
set -g mouse on
set -g escape-time 0
set -g history-limit 20000
set -g focus-events on
set -g set-clipboard on
set -g allow-passthrough on
set -g window-size latest
set -g default-terminal "xterm-256color"
'''
SID = re.compile(r"^[0-9a-f]{16}$")
STALE_S = 7 * 86_400  # a detached tmux shell nobody reopened for a week is cleaned up

_sessions = {}
_lock = threading.Lock()


def targets(hosts_file):
    out = ["hub"]
    try:
        for line in open(hosts_file, encoding="utf-8").read().splitlines():
            line = line.split("#", 1)[0].strip()
            if line and line.replace("-", "").replace("_", "").replace(".", "").isalnum() and line not in out:
                out.append(line)
    except OSError:
        pass
    return out


def ssh_argv(target):
    """ssh to `target` (alias, host, user@host or user@host:port); passwords and host-key questions go through the terminal."""
    userhost, _, port = target.partition(":") if target.count(":") == 1 else (target, "", "")
    argv = ["ssh", "-tt", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=30", "-o", "StrictHostKeyChecking=accept-new"]
    if port:
        argv += ["-p", port]
    return argv + ["--", userhost]


def have_tmux():
    return shutil.which("tmux") is not None


def _tmux(*args, timeout=10):
    return subprocess.run(["tmux", "-L", SOCKET, "-u", "-f", CONF, *args], capture_output=True, text=True, timeout=timeout)


def _tmux_attach_argv(sid, target, rows, cols):
    """Make sure the tmux shell `hc-<sid>` exists (started in its own scope, so a hub restart does not take it down), then attach to it."""
    if not os.path.exists(CONF):
        with open(CONF, "w", encoding="utf-8") as f:
            f.write(TMUX_CONF)
    name = "hc-" + sid
    if _tmux("has-session", "-t", name).returncode != 0:
        inner = "exec /bin/bash -l" if target == "hub" else "exec " + shlex.join(ssh_argv(target))
        make = ["tmux", "-L", SOCKET, "-u", "-f", CONF, "new-session", "-d", "-s", name, "-x", str(max(1, min(cols, 400))), "-y", str(max(1, min(rows, 200))), inner]
        if shutil.which("systemd-run"):
            make = ["systemd-run", "--scope", "--quiet", "--collect", "--unit=hermes-tmux-" + sid[:8]] + make
        env = dict(os.environ, TERM="xterm-256color", LANG="C.UTF-8")
        subprocess.run(make, capture_output=True, timeout=15, env=env)
    return ["tmux", "-L", SOCKET, "-u", "-f", CONF, "attach-session", "-t", name]


def kill_tmux(sid):
    try:
        _tmux("kill-session", "-t", "hc-" + sid)
    except (OSError, subprocess.SubprocessError):
        pass


def _gc_tmux():
    """Remove detached shells nobody has touched for STALE_S that this process is not serving."""
    try:
        out = _tmux("list-sessions", "-F", "#{session_name} #{session_activity} #{session_attached}").stdout
    except (OSError, subprocess.SubprocessError):
        return
    now = time.time()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[0].startswith("hc-") and parts[2] == "0" and now - int(parts[1]) > STALE_S and parts[0][3:] not in _sessions:
            kill_tmux(parts[0][3:])


class Session:
    def __init__(self, target, rows, cols, name="", sid=None):
        self.target, self.name, self.base, self.buf, self.cond = target, name or target, 0, bytearray(), threading.Condition()
        self.alive, self.used, self.born = True, time.time(), time.time()
        self.tmux = sid
        if sid:
            argv = _tmux_attach_argv(sid, target, rows, cols)
        else:
            argv = ["/bin/bash", "-l"] if target == "hub" else ssh_argv(target)
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            os.environ.update(TERM="xterm-256color", LANG="C.UTF-8")
            os.execvp(argv[0], argv)
        self.resize(rows, cols)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        while True:
            try:
                data = os.read(self.fd, 8192)
            except OSError:
                data = b""
            with self.cond:
                if not data:
                    self.alive = False
                else:
                    self.buf += data
                    if len(self.buf) > KEEP:
                        drop = len(self.buf) - KEEP
                        del self.buf[:drop]
                        self.base += drop
                self.cond.notify_all()
            if not data:
                try:
                    os.waitpid(self.pid, 0)
                except OSError:
                    pass
                return

    def read(self, since, wait):
        """(bytes from offset `since`, next offset, alive). Waits up to `wait` seconds for something new."""
        self.used = time.time()
        end = self.used + wait
        with self.cond:
            while self.alive and since >= self.base + len(self.buf) and time.time() < end:
                self.cond.wait(end - time.time())
            since = max(since, self.base)
            data = bytes(self.buf[since - self.base:])
            end_at = self.base + len(self.buf)
            # never hand out half of a multi-byte character: the rest arrives with the next read
            for back in range(1, min(4, len(data)) + 1):
                b = data[-back]
                if b & 0xC0 == 0x80:
                    continue
                need = 4 if b >= 0xF0 else 3 if b >= 0xE0 else 2 if b >= 0xC0 else 1
                if need > back and self.alive:
                    data, end_at = data[:-back], end_at - back
                break
            return data, end_at, self.alive

    def write(self, data):
        self.used = time.time()
        while data:
            n = os.write(self.fd, data)
            data = data[n:]

    def resize(self, rows, cols):
        try:
            fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", max(1, min(rows, 200)), max(1, min(cols, 400)), 0, 0))
        except OSError:
            pass

    def close(self, end=True):
        if self.tmux and end:
            kill_tmux(self.tmux)
        if self.alive:
            try:
                os.kill(self.pid, signal.SIGHUP)
            except OSError:
                pass
        try:
            os.close(self.fd)
        except OSError:
            pass


_last_gc = 0.0


def _reap():
    global _last_gc
    now = time.time()
    for sid, s in list(_sessions.items()):
        if now - s.used > IDLE_S or (not s.alive and now - s.used > 60):
            _sessions.pop(sid, None).close(end=False)  # an idle tmux shell keeps running; the app can reattach by id
    if now - _last_gc > 3600:
        _last_gc = now
        if have_tmux():
            _gc_tmux()


def open_session(target, hosts_file, rows=30, cols=80, name="", persist=True, resume=""):
    """A new shell, or with `resume` (an id handed out earlier) the same tmux shell again after a hub restart."""
    if target != "hub" and not TARGET.match(target):
        raise ValueError("not a host name")
    with _lock:
        _reap()
        if resume in _sessions:
            return resume
        if len(_sessions) >= MAX_SESSIONS:
            raise RuntimeError("too many open terminals")
        use_tmux = persist and have_tmux()
        sid = resume if use_tmux and SID.match(resume or "") else os.urandom(8).hex()
        _sessions[sid] = Session(target, rows, cols, name, sid if use_tmux else None)
        return sid


def get(sid):
    s = _sessions.get(sid)
    if not s:
        raise KeyError("no such terminal")
    return s


def close_session(sid):
    with _lock:
        s = _sessions.pop(sid, None)
    if s:
        s.close()


def listing():
    with _lock:
        _reap()
        return [{"id": sid, "target": s.target, "name": s.name, "alive": s.alive, "born": int(s.born)} for sid, s in _sessions.items()]
