"""Offline unit tests for hub.py (stdlib unittest). No network, no ports, no ssh/pvesh/docker/systemctl."""
import importlib.util
import io
import json
import os
import shlex
import subprocess
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest import mock

_TMP = tempfile.TemporaryDirectory()
for _k, _v in {"HUB_DIR": _TMP.name, "HUB_DB": os.path.join(_TMP.name, "hub.db"),
               "OPS_ALLOWLIST": os.path.join(_TMP.name, "allow.txt"),
               "PVE_CONFIG": os.path.join(_TMP.name, "pve.json"),
               "NTFY_TOKEN_PATH": os.path.join(_TMP.name, "ntfy.token"),
               "HERMES_HOME": os.path.join(_TMP.name, "home"),
               "HERMES_RELAY_URL": "", "DECISIONS_URL": ""}.items():
    os.environ[_k] = _v
_spec = importlib.util.spec_from_file_location("hub", os.path.join(os.path.dirname(os.path.abspath(__file__)), "hub.py"))
hub = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(hub)
hub.init_db()


def host(storage=(), temps=(), node="pve"):
    return {"node": node, "storage": list(storage), "temps": [{"label": l, "c": c} for l, c in temps]}


def st(name, used, total=100, status=None):
    d = {"name": name, "used": used, "total": total}
    if status:
        d["status"] = status
    return d


class InfraProblems(unittest.TestCase):
    def test_healthy(self):
        pve = {"host": host([st("a", 50)], [("Package id 0", 60)]), "nodes": [{"node": "pve", "status": "online"}]}
        self.assertEqual(hub.infra_problems(pve), {})
        self.assertEqual(hub.infra_problems({}), {})

    def test_storage_levels(self):
        p = hub.infra_problems({"host": host([st("a", 90), st("b", 95), st("c", 89), st("d", 0, 0)])})
        self.assertEqual(set(p), {"storage:a:90", "storage:b:95"})

    def test_unreachable(self):
        p = hub.infra_problems({"host": host([st("n", 0, 0, "unreachable")])})
        self.assertIn("storage:n:down", p)

    def test_temp(self):
        self.assertIn("temp:pve", hub.infra_problems({"host": host(temps=[("Core 0", 85)])}))
        self.assertEqual(hub.infra_problems({"host": host(temps=[("Core 0", 84)])}), {})
        self.assertEqual(hub.infra_problems({"host": host(temps=[("nvme", 99)])}), {})  # label not CPU

    def test_offline_node(self):
        p = hub.infra_problems({"nodes": [{"node": "x", "status": "offline"}, {"node": "y", "status": "online"}]})
        self.assertEqual(list(p), ["node:x"])


class InfraWatch(unittest.TestCase):
    def setUp(self):
        hub._infra_seen.update(keys=None, guests={})
        self.ev = []
        self.pve = {"reachable": True, "host": host(), "guests": []}
        for p in (mock.patch.object(hub, "emit_event", lambda *a, **k: self.ev.append((a, k))),
                  mock.patch.object(hub, "infra", lambda: {"pve": self.pve})):
            p.start()
            self.addCleanup(p.stop)

    def test_first_pass_silent_then_alert_then_recover(self):
        hub.infra_watch()
        self.assertEqual(self.ev, [])
        self.pve["host"] = host([st("a", 91)])
        hub.infra_watch()
        self.assertEqual([e[0][0] for e in self.ev], ["alert"])
        hub.infra_watch()  # unchanged: no repeat
        self.assertEqual(len(self.ev), 1)
        self.pve["host"] = host()
        hub.infra_watch()
        self.assertEqual(len(self.ev), 2)
        self.assertTrue(self.ev[1][0][1].startswith("Recovered: "))

    def test_90_to_95_no_recovery(self):
        self.pve["host"] = host([st("a", 91)])
        hub.infra_watch()
        self.pve["host"] = host([st("a", 96)])
        hub.infra_watch()
        self.assertFalse(any(e[0][1].startswith("Recovered") for e in self.ev))

    def test_no_host_data_records_nothing(self):
        self.pve["host"] = None
        hub.infra_watch()
        self.assertIsNone(hub._infra_seen["keys"])
        self.assertEqual(self.ev, [])

    def test_guest_transitions(self):
        g = {"vmid": 100, "name": "web", "status": "running"}
        self.pve["guests"] = [g]
        hub.infra_watch()
        self.pve["guests"] = [dict(g, status="stopped")]
        hub.infra_watch()
        self.assertEqual(self.ev[-1][0][0], "alert")
        self.pve["guests"] = [dict(g, status="running")]
        hub.infra_watch()
        self.assertEqual(self.ev[-1][0][0], "info")
        self.assertEqual(len(self.ev), 2)


class GuestGuard(unittest.TestCase):
    def setUp(self):
        self.allow = os.path.join(_TMP.name, "pve-actions.txt")
        if os.path.exists(self.allow):
            os.remove(self.allow)
        self.guests = [{"vmid": 100, "name": "hubbox.lan"}, {"vmid": 101, "name": "other"}]
        for p in (mock.patch.object(hub, "ACTIONS_ALLOW", self.allow),
                  mock.patch.object(hub, "infra", lambda: {"pve": {"guests": self.guests}}),
                  mock.patch.object(hub.socket, "gethostname", lambda: "hubbox")):
            p.start()
            self.addCleanup(p.stop)

    def test_bad_params(self):
        for a in (("pve", "vm", "101"), ("bad node!", "lxc", "101"), ("", "lxc", "101"), ("pve", "lxc", "x1"), (None, "lxc", "1")):
            self.assertEqual(hub._guest_guard(*a)[1], 400, a)

    def test_own_guest(self):
        self.assertEqual(hub._guest_guard("pve", "lxc", 100)[1], 403)
        self.guests[0]["name"] = "hubbox"
        self.assertEqual(hub._guest_guard("pve", "lxc", "100")[1], 403)

    def test_allowlist(self):
        open(self.allow, "w").write("# c\n101  # other\n")
        self.assertIsNone(hub._guest_guard("pve", "lxc", 101))
        self.assertEqual(hub._guest_guard("pve", "lxc", 102)[1], 403)

    def test_ok(self):
        self.assertIsNone(hub._guest_guard("pve", "qemu", "101"))


class GuestValidation(unittest.TestCase):
    def setUp(self):
        self.calls = []
        for p in (mock.patch.object(hub, "_guest_guard", lambda *a: None),
                  mock.patch.object(hub, "_audit", lambda *a: None),
                  mock.patch.object(hub, "_pvesh", lambda *a, **k: self.calls.append(a) or "UPID:x")):
            p.start()
            self.addCleanup(p.stop)

    def snap(self, **kw):
        return hub.guest_snapshot(dict({"node": "pve", "type": "lxc", "vmid": 101, "op": "create", "name": "snap1"}, **kw))

    def test_bad_snapshot_names(self):
        for n in ("", "1abc", "a", "a b", "a;id", "x" * 41, "../x"):
            self.assertEqual(self.snap(name=n)[1], 400, n)
        self.assertEqual(self.snap(op="nuke")[1], 400)
        self.assertEqual(self.calls, [])

    def test_snapshot_ok(self):
        self.assertEqual(self.snap(description="d")[1], 200)
        self.assertIn("--description", self.calls[0])
        self.assertEqual(self.snap(op="delete")[1], 200)
        self.assertEqual(self.calls[1][0], "delete")

    def test_bad_action(self):
        r = hub.guest_action({"node": "pve", "type": "lxc", "vmid": 1, "action": "destroy"})
        self.assertEqual(r[1], 400)
        self.assertEqual(self.calls, [])
        self.assertEqual(hub.guest_action({"node": "pve", "type": "lxc", "vmid": 1, "action": "stop"})[1], 200)


class PveCmd(unittest.TestCase):
    def test_ssh_quoting(self):
        json.dump({"ssh": "root@h", "key": "/k"}, open(hub.PVE_CONF, "w"))
        with mock.patch("shutil.which", return_value=None):
            c = hub.pve_cmd(["pvesh", "get", "a'; id; '"])
        self.assertEqual(c[0], "ssh")
        self.assertEqual(shlex.split(c[-1]), ["pvesh", "get", "a'; id; '"])

    def test_local(self):
        with mock.patch("shutil.which", return_value="/usr/bin/pvesh"):
            self.assertEqual(hub.pve_cmd(["pvesh", "x"]), ["pvesh", "x"])


class History(unittest.TestCase):
    def test_avg_vs_min(self):
        rows = [(0, 1), (10, 3), (100, 5)]
        self.assertEqual(hub.history_downsample(rows, 60), [[0, 2.0], [60, 5.0]])
        self.assertEqual(hub.history_downsample(rows, 60, True), [[0, 1], [60, 5]])
        self.assertEqual(hub.history_downsample([], 60), [])

    def test_query(self):
        now = int(time.time())
        with hub.db() as c:
            c.execute("DELETE FROM samples")
            c.executemany("INSERT INTO samples (ts,key,value) VALUES (?,?,?)",
                          [(now - i * 60, "pve.cpu", i % 7) for i in range(1000)] +
                          [(now - 30, "up:x", 1), (now - 20, "up:x", 0)])
        r, code = hub.history_query("pve.cpu", "24h")
        self.assertEqual(code, 200)
        self.assertLessEqual(len(r["points"]), 120)
        r, _ = hub.history_query("up:x", "24h")
        self.assertEqual(min(p[1] for p in r["points"]), 0)
        self.assertEqual(hub.history_query("k", "1y")[1], 400)
        self.assertEqual(hub.history_query("", "24h")[1], 400)


class Summary(unittest.TestCase):
    def build(self, h, nodes, guests, svcs):
        inf = {"host": {}, "pve": {"host": h, "nodes": nodes, "guests": guests}}
        with mock.patch.object(hub, "infra", lambda: inf), \
             mock.patch.object(hub.Handler, "_ops_services", lambda self: {"services": svcs}):
            return hub._summary_build()

    def test_svc_bad(self):
        self.assertTrue(hub._svc_bad({"kind": "docker", "state": "Up 1h (unhealthy)"}))
        self.assertTrue(hub._svc_bad({"kind": "docker", "state": "Exited (1) 2m ago"}))
        self.assertFalse(hub._svc_bad({"kind": "docker", "state": "Exited (0) 2m ago"}))
        self.assertFalse(hub._svc_bad({"kind": "systemd", "state": "active"}))
        self.assertTrue(hub._svc_bad({"kind": "systemd", "state": "failed"}))

    def test_rollup(self):
        h = {"node": "pve", "cpu_pct": 5, "mem_total": 100, "mem_avail": 50, "root_used": 1, "root_total": 10,
             "temps": [{"label": "Tctl", "c": 82}], "storage": [st("a", 92), st("b", 10)]}
        s = self.build(h, [{"node": "pve", "status": "online"}, {"node": "n2", "status": "offline"}],
                       [{"status": "running"}, {"status": "stopped"}],
                       [{"name": n, "kind": "systemd", "state": "failed"} for n in "abc"])
        self.assertEqual((s["guests_running"], s["guests_total"], s["nodes_online"], s["services_bad"]), (1, 2, 1, 3))
        self.assertEqual(s["worst_storage"], {"name": "a", "pct": 92.0})
        self.assertEqual(s["problems"], ["storage a 92%", "temp 82C", "failed: a, b +1", "offline: n2"])

    def test_healthy(self):
        s = self.build(None, [], [], [])
        self.assertEqual(s["problems"], [])


class Updates(unittest.TestCase):
    def test_apt(self):
        out = ("openssl/jammy-security 3.0.2 amd64 [upgradable from: 3.0.1]\n"
               "curl/jammy 8.1 amd64 [upgradable from: 8.0]\nweird/line\n==\nR\n")
        r = hub._apt_info(lambda c: out)
        self.assertEqual((r["packages"], r["security"], r["reboot_required"]), (3, 1, True))
        self.assertEqual(r["sample"][0], "openssl 3.0.1 → 3.0.2")
        self.assertEqual(r["sample"][2], "weird")

    def test_apt_empty(self):
        r = hub._apt_info(lambda c: "\n==\n")
        self.assertEqual((r["packages"], r["reboot_required"], r["sample"]), (0, False, []))


class Backups(unittest.TestCase):
    def test_age(self):
        now = time.time()
        tasks = [{"id": "100", "starttime": int(now - 2 * 86400), "status": "OK"},
                 {"id": "100", "starttime": int(now - 5 * 86400), "status": "old"},
                 {"id": "bad", "starttime": 1}]
        inf = {"pve": {"nodes": [{"node": "pve"}], "guests": [{"vmid": 100, "name": "a"}, {"vmid": 101, "name": "b"}]}}
        with mock.patch.object(hub, "infra", lambda: inf), mock.patch.object(hub, "pve_cmd", lambda c: c), \
             mock.patch.object(hub, "pve_get", lambda *a: [{"id": "j", "all": 1, "enabled": 0}]), \
             mock.patch.object(hub.subprocess, "run", lambda *a, **k: SimpleNamespace(stdout=json.dumps(tasks))):
            r = hub._backups_probe()
        g = {x["vmid"]: x for x in r["guests"]}
        self.assertEqual((g[100]["last_status"], g[100]["age_days"]), ("OK", 2.0))
        self.assertIsNone(g[101]["age_days"])
        self.assertFalse(r["jobs"][0]["enabled"])
        self.assertTrue(r["jobs"][0]["all"])


class Logs(unittest.TestCase):
    def run_logs(self, stdout, *a):
        with mock.patch.object(hub.subprocess, "run", lambda *x, **k: SimpleNamespace(stdout=stdout)):
            return hub.logs_after(*a)

    def test_docker_cursor(self):
        out = "2024-01-01T00:00:01Z one\n2024-01-01T00:00:02Z two\ncontinued\n"
        lines, cur = self.run_logs(out, "c", True, "", 10)
        self.assertEqual(lines, ["one", "two", "continued"])
        self.assertEqual(cur, "2024-01-01T00:00:02Z")
        lines, cur2 = self.run_logs(out + "2024-01-01T00:00:03Z three\n", "c", True, cur, 10)
        self.assertEqual(lines, ["three"])
        self.assertEqual(cur2, "2024-01-01T00:00:03Z")

    def test_journal_cursor(self):
        e = [{"MESSAGE": "hi", "__CURSOR": "c1", "__REALTIME_TIMESTAMP": "1000000", "SYSLOG_IDENTIFIER": "u"},
             {"MESSAGE": [104, 105], "__CURSOR": "c2", "__REALTIME_TIMESTAMP": "2000000"}]
        lines, cur = self.run_logs("\n".join(json.dumps(x) for x in e) + "\nnot json\n", "u", False, "", 5)
        self.assertEqual(cur, "c2")
        self.assertTrue(lines[0].endswith("u: hi") and lines[1].endswith("u: hi"))

    def test_error(self):
        def boom(*a, **k):
            raise OSError
        with mock.patch.object(hub.subprocess, "run", boom):
            self.assertEqual(hub.logs_after("u", False, "cur", 5), ([], "cur"))


class AppUpdates(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self._old, hub.APP_DIR = hub.APP_DIR, self.d.name

    def tearDown(self):
        hub.APP_DIR = self._old
        self.d.cleanup()

    def write(self, **kw):
        with open(os.path.join(self.d.name, "latest.json"), "w") as f:
            json.dump(kw, f)

    def test_absent(self):
        self.assertIsNone(hub.app_latest())
        self.assertIsNone(hub.app_apk_path())

    def test_latest_and_apk(self):
        self.write(versionCode=40, file="a.apk")
        self.assertEqual(hub.app_latest()["versionCode"], 40)
        self.assertIsNone(hub.app_apk_path())
        open(os.path.join(self.d.name, "a.apk"), "wb").close()
        self.assertEqual(hub.app_apk_path(), os.path.join(self.d.name, "a.apk"))

    def test_bad_names(self):
        for n in ("../x.apk", "a/b.apk", "..", "", None, 5):
            self.write(file=n)
            self.assertIsNone(hub.app_apk_path(), n)


REL = {"tag_name": "v7.6.0", "body": "x" * 1200 + "\nversionCode: 76", "assets": [
    {"id": 1, "name": "notes.txt", "size": 5}, {"id": 2, "name": "HermesCompanion-7.6.0.apk", "size": 99, "url": "u"}]}


class GithubApp(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self.old = (hub.APP_DIR, hub.APP_REPO)
        hub.APP_DIR, hub.APP_REPO = self.d.name, "o/r"
        hub._gh.update(ts=0, rel=None, busy=set())
        self.started = []
        self.p = [mock.patch.object(hub.threading, "Thread", lambda **k: SimpleNamespace(start=lambda: self.started.append(k))),
                  mock.patch.dict(os.environ, {"GITHUB_TOKEN": "t"})]
        for p in self.p:
            p.start()

    def tearDown(self):
        for p in self.p:
            p.stop()
        hub.APP_DIR, hub.APP_REPO = self.old
        self.d.cleanup()

    def rel(self, rel=REL):
        r = mock.MagicMock()
        r.__enter__.return_value = io.BytesIO(json.dumps(rel).encode())
        return mock.patch.object(hub.urllib.request, "urlopen", return_value=r)

    def test_metadata_not_ready(self):
        with self.rel() as u:
            d = hub.app_latest()
        self.assertEqual(u.call_args[0][0].get_header("Authorization"), "Bearer t")
        self.assertEqual((d["versionName"], d["size"], d["ready"], len(d["notes"])), ("7.6.0", 99, False, 1000))
        self.assertNotIn("sha256", d)
        self.assertEqual(self.started[0]["args"][0]["id"], 2)  # the .apk asset, download kicked off
        self.assertIsNone(hub.app_apk_path())

    def test_ready_and_cache(self):
        b = os.path.join(self.d.name, "gh-2")
        json.dump({"sha256": "ab", "size": 99, "versionCode": 76}, open(b + ".json", "w"))
        open(b + ".apk", "wb").close()
        with self.rel() as u:
            d = hub.app_latest()
            hub.app_latest()
        self.assertEqual(u.call_count, 1)  # 10 min cache
        self.assertEqual((d["ready"], d["sha256"], d["versionCode"]), (True, "ab", 76))
        self.assertEqual(hub.app_apk_path(), b + ".apk")
        self.assertFalse(self.started)

    def test_version_code_from_body(self):
        with mock.patch.object(hub.subprocess, "run", side_effect=OSError), mock.patch.dict(os.environ, {"AAPT2": "/x"}):
            self.assertEqual(hub._apk_version_code("f", REL["body"]), 76)
            self.assertIsNone(hub._apk_version_code("f", "none"))

    def test_token_from_gh(self):
        with mock.patch.dict(os.environ, {}, clear=True), \
                mock.patch.object(hub.subprocess, "run", return_value=SimpleNamespace(stdout="tok\n")) as r:
            self.assertEqual(hub._gh_token(), "tok")
        self.assertEqual(r.call_args[0][0], ["gh", "auth", "token"])

    def test_fallback_local(self):
        json.dump({"versionCode": 40}, open(os.path.join(self.d.name, "latest.json"), "w"))
        with mock.patch.object(hub.urllib.request, "urlopen", side_effect=OSError):
            self.assertEqual(hub.app_latest()["versionCode"], 40)
        with self.rel({"tag_name": "v1", "assets": []}):
            hub._gh["ts"] = 0
            self.assertEqual(hub.app_latest()["versionCode"], 40)
        hub.APP_REPO = ""
        self.assertEqual(hub.app_latest()["versionCode"], 40)



class NodeWakeTest(unittest.TestCase):
    def test_guards_and_wake(self):
        self.assertEqual(hub.node_wake({"node": "bad node; rm"})[1], 400)
        cfg = SimpleNamespace(returncode=0, stdout="description: x\n", stderr="")
        with mock.patch.object(hub, "pve_cmd", side_effect=lambda c: c), mock.patch.object(hub.subprocess, "run", return_value=cfg):
            self.assertEqual(hub.node_wake({"node": "pve2"})[1], 409)  # no MAC configured
        cfg.stdout = "wakeonlan: aa:bb:cc:dd:ee:02\n"
        with mock.patch.object(hub, "pve_cmd", side_effect=lambda c: c), mock.patch.object(hub.subprocess, "run", return_value=cfg), \
                mock.patch.object(hub, "_pvesh", return_value="") as pvesh, mock.patch.object(hub, "_audit"):
            self.assertEqual(hub.node_wake({"node": "pve2"}), ({"ok": True}, 200))
            pvesh.assert_called_once_with("create", "/nodes/pve2/wakeonlan")



class CertTest(unittest.TestCase):
    def test_problems(self):
        items = [{"host": "ok.example", "days": 60, "error": ""}, {"host": "soon.example", "days": 9, "error": ""},
                 {"host": "gone.example", "days": -2, "error": ""}, {"host": "bad.example", "days": None, "error": "invalid: self-signed certificate"},
                 {"host": "down.example", "days": None, "error": "unreachable: TimeoutError"}]
        p = hub.cert_problems(items)
        self.assertEqual(set(p), {"cert:soon.example", "cert:gone.example", "cert:bad.example"})
        self.assertIn("expires in 9 days", p["cert:soon.example"])
        self.assertIn("expired", p["cert:gone.example"])



class RrdTest(unittest.TestCase):
    def test_points_and_guard(self):
        rows = [{"time": 1}] + [{"time": 60 * i, "cpu": 0.5, "mem": 1.0, "maxmem": 4.0, "netin": 100, "netout": 50} for i in range(1, 241)]
        pts = hub.rrd_points(rows, width=120)
        self.assertEqual(len(pts), 120)
        self.assertEqual(pts[0][1:], [50.0, 25.0, 100, 50])
        self.assertEqual(hub.infra_rrd("bad node", "lxc", "1")[1], 400)
        self.assertEqual(hub.infra_rrd("n", "lxc", "x")[1], 400)



class TaskLogTest(unittest.TestCase):
    def test_guards(self):
        self.assertEqual(hub.infra_task_log("pve2", "UPID:x; rm -rf /")[1], 400)
        self.assertEqual(hub.infra_task_log("bad node", "UPID:n:1:2:3:vzstart:140:root@pam:")[1], 400)
        with mock.patch.object(hub, "_pvesh", return_value=[{"n": 1, "t": "starting"}, {"n": 2, "t": "TASK OK"}]) as p:
            self.assertEqual(hub.infra_task_log("n", "UPID:n:1:2:3:vzstart:140:root@pam:"), ({"ok": True, "lines": ["starting", "TASK OK"]}, 200))
            self.assertEqual(p.call_args[0][1], "/nodes/n/tasks/UPID:n:1:2:3:vzstart:140:root@pam:/log")



class CrowdsecTest(unittest.TestCase):
    def test_summary_flags_blind_parsing(self):
        m = {"acquisition": {"file:/var/log/syslog": {"reads": 10, "unparsed": 10}},
             "bouncers": {"fw": {"": {"processed": {}}, "CAPI": {"active_decisions": {"ip": 6878}, "dropped": {"packet": 2460}},
                                 "lists:tor-exit-nodes": {"active_decisions": {"ip": 1998}, "dropped": {"packet": 98}}}}}
        s = hub.crowdsec_summary(m, [{"scenario": "crowdsecurity/ssh-bf"}])
        self.assertEqual((s["blocked_ips"], s["dropped_packets"], s["blind"], s["alerts_24h"]), (8876, 2558, True, 1))
        self.assertEqual(s["lists"][0]["name"], "CAPI")
        m["acquisition"]["file:/var/log/nginx/access.log"] = {"reads": 5, "parsed": 5}
        self.assertFalse(hub.crowdsec_summary(m, [])["blind"])



class DigestTest(unittest.TestCase):
    def test_once_a_day_at_the_hour_and_silent_when_clean(self):
        d = tempfile.TemporaryDirectory()
        at8 = time.mktime((2026, 10, 2, 8, 5, 0, 0, 0, -1))
        with mock.patch.object(hub, "DIGEST_STAMP", os.path.join(d.name, "s")), mock.patch.object(hub, "DIGEST_HOUR", 8), \
                mock.patch.object(hub, "digest_lines", return_value=["Backup job x writes to y"]), mock.patch.object(hub, "emit_event") as emit:
            hub.morning_digest(at8 - 3600)          # 07:05: not yet
            hub.morning_digest(at8)                 # 08:05: sends
            hub.morning_digest(at8 + 600)           # same day again: no
            self.assertEqual(emit.call_count, 1)
            self.assertIn("1 open problem", emit.call_args[0][1])
        with mock.patch.object(hub, "DIGEST_STAMP", os.path.join(d.name, "t")), mock.patch.object(hub, "DIGEST_HOUR", 8), \
                mock.patch.object(hub, "digest_lines", return_value=[]), mock.patch.object(hub, "emit_event") as emit:
            hub.morning_digest(at8)
            emit.assert_not_called()


class AlertPolicy(unittest.TestCase):
    def setUp(self):
        with hub.db() as conn:
            conn.execute("DELETE FROM alert_state")
        try:
            os.remove(hub.mutes_path())
        except OSError:
            pass

    def test_same_alert_with_changing_numbers_is_sent_once_per_cooldown(self):
        a = hub.alert_lines("\U0001f3e5 SYSTEM HEALTH ALERT\n\U0001f534 High load: 4.17\nTime: 20261014 12:35:24 EEST")
        b = hub.alert_lines("\U0001f3e5 SYSTEM HEALTH ALERT\n\U0001f534 High load: 8.82\nTime: 20261014 13:08:23 EEST")
        self.assertEqual(a, b)
        self.assertTrue(hub.alert_allowed("job:x", a, now=1000))
        self.assertFalse(hub.alert_allowed("job:x", b, now=1000 + 1800))
        self.assertFalse(hub.alert_allowed("job:x", b, now=1000 + 6 * 3600 - 1))
        self.assertTrue(hub.alert_allowed("job:x", b, now=1000 + 6 * 3600 + 1))     # a reminder after the cooldown

    def test_a_new_or_worse_problem_goes_out_at_once(self):
        warn = hub.alert_lines("\U0001f7e1 Disk: 83% full")
        worse = hub.alert_lines("\U0001f534 Disk: 91% full")
        extra = warn + hub.alert_lines("\U0001f534 Memory: 95% used")
        self.assertTrue(hub.alert_allowed("job:d", warn, now=0))
        self.assertTrue(hub.alert_allowed("job:d", worse, now=10))
        self.assertTrue(hub.alert_allowed("job:d", extra, now=20))
        self.assertFalse(hub.alert_allowed("job:d", extra, now=30))

    def test_state_survives_a_restart_of_the_process(self):
        self.assertTrue(hub.alert_allowed("disk:media", cooldown=86400, now=100))
        # a new process only has the database: still suppressed
        self.assertFalse(hub.alert_allowed("disk:media", cooldown=86400, now=200))

    def test_mutes_by_key_and_prefix_expire_and_can_be_removed(self):
        hub.mute_alert("guest:android-build-pve", 24)
        hub.mute_alert("infra:*", 1)
        self.assertTrue(hub.alert_muted("guest:android-build-pve"))
        self.assertTrue(hub.alert_muted("infra:temp:pve"))
        self.assertFalse(hub.alert_muted("guest:other"))
        self.assertFalse(hub.alert_allowed("guest:android-build-pve", now=1))
        self.assertTrue(hub.unmute_alert("guest:android-build-pve"))
        self.assertFalse(hub.alert_muted("guest:android-build-pve"))
        self.assertFalse(hub.unmute_alert("never"))
        self.assertEqual({}, {k: v for k, v in hub.load_mutes(now=time.time() + 7200).items()})   # both expired by then

    def test_overview_lists_state_and_mutes(self):
        hub.alert_allowed("job:z", ["x"], now=5)
        hub.mute_alert("job:q", 2)
        o = hub.alert_overview()
        self.assertEqual("job:z", o["state"][0]["key"])
        self.assertEqual("job:q", o["mutes"][0]["key"])

    def test_alert_markers_need_whole_words(self):
        self.assertTrue(hub.CRON_ALERT_RE.search("\U0001f3e5 SYSTEM HEALTH ALERT"))
        self.assertTrue(hub.CRON_ALERT_RE.search("backup FAILED at 03:00"))
        self.assertFalse(hub.CRON_ALERT_RE.search("see ALERTS_FILE and ERRORS=0"))


class AlertedInfraWatch(unittest.TestCase):
    def setUp(self):
        hub._infra_seen.update(keys=None, guests={})
        with hub.db() as conn:
            conn.execute("DELETE FROM alert_state")
        try:
            os.remove(hub.mutes_path())
        except OSError:
            pass
        self.ev = []
        self.pve = {"reachable": True, "host": host(), "guests": []}
        for p in (mock.patch.object(hub, "emit_event", lambda *a, **k: self.ev.append((a, k))),
                  mock.patch.object(hub, "infra", lambda: {"pve": self.pve})):
            p.start()
            self.addCleanup(p.stop)

    def guest(self, status):
        self.pve["guests"] = [{"vmid": 103, "name": "builder", "status": status}]
        hub.infra_watch()

    def test_a_guest_that_keeps_stopping_alerts_once_not_every_time(self):
        self.guest("running")
        self.guest("stopped"); self.guest("running"); self.guest("stopped")
        stops = [e for e in self.ev if e[0][0] == "alert"]
        self.assertEqual(1, len(stops))
        self.assertEqual("guest:builder", stops[0][1]["mute_key"])

    def test_a_muted_guest_is_silent_when_it_stops_and_starts(self):
        hub.mute_alert("guest:builder", 24)
        self.guest("running"); self.guest("stopped"); self.guest("running")
        self.assertEqual([], self.ev)

    def test_temperature_alert_holds_until_clearly_cooler(self):
        hub.infra_watch()                                    # first pass records state
        self.pve["host"] = host(temps=[("Core 0", 86)]); hub.infra_watch()
        self.pve["host"] = host(temps=[("Core 0", 83)]); hub.infra_watch()    # 83: still the same incident
        self.pve["host"] = host(temps=[("Core 0", 86)]); hub.infra_watch()    # flaps up again: nothing new
        alerts = [e for e in self.ev if e[0][0] == "alert"]
        recovered = [e for e in self.ev if e[0][0] == "info"]
        self.assertEqual(1, len(alerts))
        self.assertEqual([], recovered)
        self.pve["host"] = host(temps=[("Core 0", 70)]); hub.infra_watch()
        self.assertEqual(1, len([e for e in self.ev if e[0][0] == "info"]))


class BridgeTokenOta(unittest.TestCase):
    def test_bridge_tokens_are_recognised_from_the_secrets_files(self):
        home = tempfile.TemporaryDirectory()
        self.addCleanup(home.cleanup)
        os.makedirs(os.path.join(home.name, "secrets"))
        with open(os.path.join(home.name, "secrets", "codex-bridge.env"), "w") as f:
            f.write("CODEX_BRIDGE_TOKEN=codex-secret\nCODEX_BRIDGE_PORT=8795\n")
        with open(os.path.join(home.name, "secrets", "claude-bridge.env"), "w") as f:
            f.write("CLAUDE_BRIDGE_TOKEN='claude-secret'\n")
        with mock.patch.dict(os.environ, {"HERMES_HOME": home.name}):
            self.assertTrue(hub.is_bridge_token("codex-secret"))
            self.assertTrue(hub.is_bridge_token("claude-secret"))
            self.assertFalse(hub.is_bridge_token("nope"))
            self.assertFalse(hub.is_bridge_token(""))
            self.assertFalse(hub.is_bridge_token(None))

    def test_only_the_update_routes_accept_a_bridge_token(self):
        handler = hub.Handler.__new__(hub.Handler)
        handler.headers = {"Authorization": "Bearer codex-secret"}
        with mock.patch.object(hub, "is_authorized", return_value=False), mock.patch.object(hub, "is_bridge_token", return_value=True):
            for path, ok in (("/app/latest", True), ("/app/latest?x=1", True), ("/app/apk", True), ("/ops/services", False), ("/events", False), ("/hermes/slash", False)):
                handler.path = path
                self.assertEqual(ok, handler._authed(), path)


class HermesUsage(unittest.TestCase):
    def gateway(self, usage):
        patcher = mock.patch.object(hub, "_Gateway")
        gateway = patcher.start().return_value.__enter__.return_value
        self.addCleanup(patcher.stop)
        gateway.resume.return_value = {"session_id": "runtime-session"}
        gateway.call.return_value = usage
        return gateway

    def test_context_and_tokens_use_resumed_session_and_profile(self):
        gateway = self.gateway({"context_used": 5000, "context_max": 128000,
                                "input": 6000, "output": 120, "reasoning": 80, "total": 6120})
        data, code = hub.gw_usage({"session_id": "stored-session", "profile": "work"})
        self.assertEqual(code, 200)
        gateway.resume.assert_called_once_with("stored-session", "work")
        gateway.call.assert_called_once_with("session.usage", {"session_id": "runtime-session"}, timeout=20)
        self.assertEqual(data["context"], {"used_tokens": 5000, "window_tokens": 128000,
                                           "percent": 3.90625, "source": "last_turn"})
        self.assertEqual(data["session"], {"input_tokens": 6000, "output_tokens": 120,
                                           "reasoning_tokens": 80, "total_tokens": 6120})
        self.assertNotIn("limits", data)

    def test_absent_fields_and_estimated_context(self):
        gateway = self.gateway({})
        data, code = hub.gw_usage({"session_id": "S"})
        self.assertEqual(code, 200)
        self.assertEqual(set(data), {"as_of"})
        gateway.call.return_value = {"context_used": 150, "context_max": 100, "context_estimated": True}
        data, _ = hub.gw_usage({"session_id": "S"})
        self.assertEqual(data["context"]["source"], "estimate")
        self.assertEqual(data["context"]["percent"], 100)

    def test_missing_session_does_not_open_gateway_and_rpc_errors_are_502(self):
        gateway = self.gateway({})
        self.assertEqual(hub.gw_usage({})[1], 400)
        gateway.resume.assert_not_called()
        gateway.call.side_effect = hub.GatewayError("unavailable")
        data, code = hub.gw_route(hub.gw_usage, {"session_id": "S"})
        self.assertEqual(code, 502)
        self.assertEqual(data["error"], "unavailable")

    def test_busy_gateway_does_not_queue_another_resume(self):
        gateway = self.gateway({})
        with mock.patch.object(hub, "_gw_lock") as lock:
            lock.acquire.return_value = False
            data, code = hub.gw_route(hub.gw_usage, {"session_id": "S"})
            lock.acquire.assert_called_once_with(timeout=2)
            lock.release.assert_not_called()
        self.assertEqual(code, 502)
        self.assertIn("busy", data["error"])
        gateway.resume.assert_not_called()

    def test_http_route_passes_query_and_requires_auth(self):
        handler = hub.Handler.__new__(hub.Handler)
        handler.path = "/hermes/usage?session_id=stored-session&profile=work"
        handler._json = mock.Mock()
        handler._authed = mock.Mock(return_value=True)
        with mock.patch.object(hub, "gw_usage", return_value=({"as_of": 123}, 200)) as usage:
            handler.do_GET()
            usage.assert_called_once_with({"session_id": "stored-session", "profile": "work"})
            handler._json.assert_called_once_with({"as_of": 123}, 200)
            handler._json.reset_mock()
            handler._authed.return_value = False
            handler.do_GET()
            self.assertEqual(usage.call_count, 1)
            self.assertEqual(handler._json.call_args.args[1], 401)


class ApprovalsMode(unittest.TestCase):
    CONFIG = "model:\n  default: x\napprovals:\n  mode: smart   # how risky commands are handled\n  timeout: 60\nother:\n  mode: nope\n"

    def setUp(self):
        self.home = tempfile.TemporaryDirectory()
        self.addCleanup(self.home.cleanup)
        self.cfg = os.path.join(self.home.name, "config.yaml")
        with open(self.cfg, "w") as f:
            f.write(self.CONFIG)
        p = mock.patch.dict(os.environ, {"HERMES_HOME": self.home.name})
        p.start()
        self.addCleanup(p.stop)

    def test_reads_the_mode_and_offers_three_choices(self):
        got = hub.approvals_get()
        self.assertEqual("smart", got["mode"])
        self.assertEqual(["manual", "smart", "off"], [o["value"] for o in got["options"]])

    def test_changes_only_the_approvals_mode_line_and_keeps_a_backup(self):
        self.assertEqual("off", hub.approvals_set("off")["mode"])
        text = open(self.cfg).read()
        self.assertEqual(self.CONFIG.replace("mode: smart", "mode: off", 1), text)
        self.assertEqual(self.CONFIG, open(self.cfg + ".pre-approvals").read())
        self.assertIn("mode: nope", text)

    def test_rejects_unknown_modes_and_a_config_without_the_block(self):
        with self.assertRaises(ValueError):
            hub.approvals_set("yolo")
        with open(self.cfg, "w") as f:
            f.write("model:\n  default: x\n")
        with self.assertRaises(ValueError):
            hub.approvals_set("off")
        self.assertEqual("manual", hub.approvals_get()["mode"])


class WakeMachine(unittest.TestCase):
    def setUp(self):
        hub._wake_last[0] = 0.0
        self.home = tempfile.TemporaryDirectory()
        self.addCleanup(self.home.cleanup)
        p = mock.patch.dict(os.environ, {"HUB_DIR": self.home.name, "HUB_WAKE_MAC": "", "HUB_WAKE_HOST": ""})
        p.start()
        self.addCleanup(p.stop)

    def configure(self, **extra):
        with open(os.path.join(self.home.name, "wake.json"), "w") as f:
            json.dump(dict({"mac": "AA:BB:CC:DD:EE:01", "host": "192.168.1.150", "port": 10300}, **extra), f)

    def test_nothing_is_sent_without_a_configured_target(self):
        with mock.patch.object(hub.socket, "socket") as sock:
            self.assertFalse(hub.wake_machine()["ok"])
        sock.assert_not_called()

    def test_a_machine_that_already_answers_is_not_woken(self):
        self.configure()
        with mock.patch.object(hub, "_tcp_open", return_value=True), mock.patch.object(hub.socket, "socket") as sock:
            self.assertEqual("up", hub.wake_machine()["state"])
        sock.assert_not_called()

    def test_a_sleeping_machine_gets_one_magic_packet_then_waits_20_seconds(self):
        self.configure()
        with mock.patch.object(hub, "_tcp_open", return_value=False), mock.patch.object(hub.socket, "socket") as sock:
            self.assertEqual("waking", hub.wake_machine()["state"])
            self.assertEqual("waking", hub.wake_machine()["state"])
        sent = sock.return_value.sendto.call_args_list
        self.assertEqual(2, len(sent))  # subnet broadcast + limited broadcast, from the first call only
        pkt, addr = sent[0][0]
        self.assertEqual(b"\xff" * 6 + bytes.fromhex("AABBCCDDEE01") * 16, pkt)
        self.assertEqual(("192.168.1.255", 9), addr)

    def test_a_bad_mac_is_ignored(self):
        self.configure(mac="nonsense")
        self.assertFalse(hub.wake_machine()["ok"])


if __name__ == "__main__":
    unittest.main()


class VoiceStt(unittest.TestCase):
    """The https fallback speaks Wyoming to the speech server; a socketpair stands in for it."""

    def test_audio_is_sent_in_chunks_and_the_transcript_comes_back(self):
        import socket, threading
        mine, theirs = socket.socketpair()
        got = []

        def server():
            f = theirs.makefile("rb")
            while True:
                head = json.loads(f.readline())
                if head.get("payload_length"):
                    f.read(head["payload_length"])
                got.append(head["type"])
                if head["type"] == "audio-stop":
                    data = json.dumps({"text": "hello there"}).encode()
                    theirs.sendall(json.dumps({"type": "transcript", "data_length": len(data)}).encode() + b"\n" + data)
                    return

        t = threading.Thread(target=server, daemon=True); t.start()
        with mock.patch.object(hub.socket, "create_connection", return_value=mine):
            self.assertEqual(hub.wyoming_stt(b"\0" * 70000, "en"), "hello there")
        t.join(5)
        self.assertEqual(got, ["transcribe", "audio-start", "audio-chunk", "audio-chunk", "audio-chunk", "audio-stop"])

    def test_a_live_take_returns_partials_while_audio_arrives_and_the_final_on_stop(self):
        import socket, threading
        mine, theirs = socket.socketpair()

        def server():
            f = theirs.makefile("rb")
            while True:
                head = json.loads(f.readline())
                if head.get("payload_length"):
                    f.read(head["payload_length"])
                if head["type"] == "audio-chunk":
                    d = json.dumps({"text": "hello"}).encode()
                    theirs.sendall(json.dumps({"type": "transcript-partial", "data_length": len(d)}).encode() + b"\n" + d)
                if head["type"] == "audio-stop":
                    d = json.dumps({"text": "hello there"}).encode()
                    theirs.sendall(json.dumps({"type": "transcript", "data_length": len(d)}).encode() + b"\n" + d)
                    return

        threading.Thread(target=server, daemon=True).start()
        with mock.patch.object(hub.socket, "create_connection", return_value=mine):
            take = hub.LiveTake("en")
        self.assertIs(hub.LiveTake.takes[take.id], take)
        take.audio(b"\0" * 8000)
        for _ in range(50):
            if take.partial:
                break
            time.sleep(0.02)
        self.assertEqual(take.audio(b"")["partial"], "hello")
        self.assertEqual(take.stop(), "hello there")
        self.assertNotIn(take.id, hub.LiveTake.takes)
