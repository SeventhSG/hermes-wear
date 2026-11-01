"""Terminal sessions: a real shell on a PTY, read back by offset."""
import os
import tempfile
import time
import unittest

import term_sessions as ts


class TermSessions(unittest.TestCase):
    def setUp(self):
        self.hosts = os.path.join(tempfile.mkdtemp(), "terminal-hosts.txt")

    def test_targets_are_hub_plus_clean_host_lines(self):
        with open(self.hosts, "w") as f: f.write("# kit\nkit\nbad host;rm\nnode-2.lan # comment\nkit\n")
        self.assertEqual(ts.targets(self.hosts), ["hub", "kit", "node-2.lan"])
        for bad in ("-oProxyCommand=x", "a b", "x;rm", "", "a@b@c", "h:12:3"):
            with self.assertRaises(ValueError):
                ts.open_session(bad, self.hosts)

    def test_ssh_command_line(self):
        self.assertEqual(ts.ssh_argv("kit")[-2:], ["--", "kit"])
        a = ts.ssh_argv("root@192.168.0.150:2222")
        self.assertEqual((a[-4:-2], a[-2:]), (["-p", "2222"], ["--", "root@192.168.0.150"]))

    def test_shell_echo_resize_and_close(self):
        sid = ts.open_session("hub", self.hosts, 24, 100, persist=False)
        s = ts.get(sid)
        s.write(b"echo hi-$((6*7)); stty size\n")
        out, nxt, got = b"", 0, time.time() + 8
        while b"24 100" not in out and time.time() < got:
            data, nxt, alive = s.read(nxt, 1)
            out += data
        self.assertIn(b"hi-42", out)
        self.assertIn(b"24 100", out)
        ts.close_session(sid)
        with self.assertRaises(KeyError):
            ts.get(sid)

    def test_exit_marks_session_dead(self):
        sid = ts.open_session("hub", self.hosts, persist=False)
        s = ts.get(sid); s.write(b"exit\n")
        end = time.time() + 8
        while s.alive and time.time() < end:
            s.read(0, 0.5)
        self.assertFalse(s.alive)
        ts.close_session(sid)

    def _read_until(self, s, needle, seconds=10):
        out, nxt, end = b"", 0, time.time() + seconds
        while needle not in out and time.time() < end:
            data, nxt, alive = s.read(nxt, 1)
            out += data
        return out

    @unittest.skipUnless(ts.have_tmux(), "tmux not installed")
    def test_tmux_shell_survives_a_hub_restart(self):
        from unittest import mock
        real = ts.shutil.which
        sock, conf = "hermes-test-%d" % os.getpid(), os.path.join(tempfile.mkdtemp(), "tmux.conf")
        with mock.patch.object(ts, "SOCKET", sock), mock.patch.object(ts, "CONF", conf), \
                mock.patch.object(ts.shutil, "which", lambda n: None if n == "systemd-run" else real(n)):
            try:
                sid = ts.open_session("hub", self.hosts, 24, 100)
                s = ts.get(sid)
                s.write(b"export KEEP=alive-$((6*7)); echo set\n")
                self.assertIn(b"\r\nset\r\n", self._read_until(s, b"\r\nset\r\n"))
                ts._sessions.clear()  # the hub process restarts: its sessions are gone, the tmux server is not
                s.close(end=False)
                with self.assertRaises(KeyError):
                    ts.get(sid)
                again = ts.open_session("hub", self.hosts, 24, 100, resume=sid)
                self.assertEqual(again, sid)
                s2 = ts.get(sid)
                s2.write(b"echo $KEEP-done\n")
                self.assertIn(b"alive-42-done", self._read_until(s2, b"alive-42-done"))
                ts.close_session(sid)  # closing the tab ends the shell for good
                time.sleep(0.5)
                self.assertNotEqual(ts._tmux("has-session", "-t", "hc-" + sid).returncode, 0)
            finally:
                subprocess_run = ts.subprocess.run
                subprocess_run(["tmux", "-L", sock, "kill-server"], capture_output=True)
                for sid_ in list(ts._sessions): ts.close_session(sid_)

    def test_resume_id_must_be_plain_hex(self):
        self.assertTrue(ts.SID.match("0123456789abcdef"))
        for bad in ("", "../../etc", "0123456789abcdeg", "0123456789abcdef0"):
            self.assertFalse(ts.SID.match(bad))


if __name__ == "__main__":
    unittest.main()
