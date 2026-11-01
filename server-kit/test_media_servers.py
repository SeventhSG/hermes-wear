"""Media server adapters against canned server answers (no live Emby/Plex/Ampache needed)."""
import json
import unittest
from unittest import mock

import media_servers as ms


class FakeResponse:
    def __init__(self, body, ctype="application/json"):
        self.body = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.headers = {"Content-Type": ctype}

    def read(self, n=-1):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def serve(routes, seen):
    """urlopen stand-in: the first route whose key is in the URL answers; records (method, url, headers)."""
    def urlopen(req, timeout=0):
        seen.append((req.get_method(), req.full_url, dict(req.header_items())))
        for k, v in routes.items():
            if k in req.full_url:
                return FakeResponse(v)
        raise AssertionError("unexpected " + req.full_url)
    return urlopen


ENV = {"EMBY_URL": "http://emby:8096/emby", "EMBY_API_KEY": "ek", "PLEX_URL": "http://plex:32400", "PLEX_TOKEN": "pt",
       "AMPACHE_URL": "http://amp", "AMPACHE_KEY": "ak", "SONARR_URL": "http://s"}


def route(method, path, query="", body=None):
    ms._adapters.clear(); ms._cache.clear()
    status, data, _ = ms.route(method, path, query, body, ENV.get)
    return status, json.loads(data)


class MediaServers(unittest.TestCase):
    def test_modules_lists_only_configured(self):
        status, out = route("GET", "/media/modules")
        ids = [m["id"] for m in out["modules"]]
        self.assertEqual(ids, ["sonarr", "emby", "plex", "ampache"])
        self.assertIn("control", next(m for m in out["modules"] if m["id"] == "plex")["features"])
        self.assertEqual(route("GET", "/media/jellyfin/info")[0], 404)

    def test_emby_uses_jellyfin_api_with_token(self):
        seen = []
        latest = [{"Id": "a" * 32, "Type": "Episode", "SeriesId": "b" * 32, "SeriesName": "Show", "Name": "Pilot", "ParentIndexNumber": 1,
                   "IndexNumber": 2, "UserData": {"PlayedPercentage": 50, "Played": False}}]
        with mock.patch("urllib.request.urlopen", serve({"/Users/u1/Items/Latest": latest,
                                                         "/Users": [{"Id": "u1", "Policy": {"IsAdministrator": True}}]}, seen)):
            status, out = route("GET", "/media/emby/latest", "limit=5")
        self.assertEqual(status, 200)
        it = out["items"][0]
        self.assertEqual((it["title"], it["subtitle"], it["progress"]), ("Show", "S01E02 · Pilot", 0.5))
        self.assertIn("ref=" + "b" * 32, it["image"])
        self.assertTrue(seen[-1][1].startswith("http://emby:8096/emby/Users/u1/Items/Latest"))
        self.assertEqual(seen[-1][2].get("X-emby-token"), "ek")

    def test_plex_sessions_and_stop_only(self):
        seen = []
        sessions = {"MediaContainer": {"Metadata": [{"type": "episode", "grandparentTitle": "Show", "title": "Ep", "parentIndex": 2, "index": 3,
                                                     "grandparentThumb": "/library/metadata/9/thumb/1", "viewOffset": 60000, "duration": 120000,
                                                     "User": {"title": "adrian"}, "Player": {"product": "Plex Web", "title": "Chrome", "state": "paused"},
                                                     "Session": {"id": "s1"}}]}}
        with mock.patch("urllib.request.urlopen", serve({"/status/sessions/terminate": b"", "/status/sessions": sessions}, seen)):
            status, out = route("GET", "/media/plex/sessions")
            s = out["sessions"][0]
            self.assertEqual((s["title"], s["user"], s["paused"], s["position"], s["duration"]), ("Show S02E03", "adrian", True, 60.0, 120.0))
            self.assertEqual(route("POST", "/media/plex/control", "", json.dumps({"session": "s1", "cmd": "stop"}).encode())[0], 200)
            self.assertEqual(route("POST", "/media/plex/control", "", json.dumps({"session": "s1", "cmd": "playpause"}).encode())[0], 501)
        self.assertIn("sessionId=s1", seen[-1][1])
        self.assertEqual(seen[0][2].get("X-plex-token"), "pt")

    def test_plex_image_ref_is_validated(self):
        self.assertEqual(route("GET", "/media/plex/image", "ref=http://evil/x")[0], 400)

    def test_ampache_handshake_then_latest_albums(self):
        seen = []
        with mock.patch("urllib.request.urlopen", serve({"action=handshake": {"auth": "tok", "albums": 3, "server": "6.4"},
                                                         "action=stats": {"album": [{"id": 7, "name": "Album", "artist": {"name": "Band"}, "year": 2001}]}}, seen)):
            status, out = route("GET", "/media/ampache/latest", "limit=2")
        self.assertEqual(out["items"][0]["subtitle"], "Band · 2001")
        self.assertIn("auth=tok", seen[-1][1])
        self.assertIn("ref=album%3A7", out["items"][0]["image"])

    def test_unreachable_server_is_502_not_a_crash(self):
        def boom(req, timeout=0):
            raise OSError("down")
        with mock.patch("urllib.request.urlopen", boom):
            status, out = route("GET", "/media/plex/info")
        self.assertEqual(status, 502)
        self.assertIn("Plex unreachable", out["error"])

    def test_config_tests_then_saves_and_removes_keeping_other_lines(self):
        import os, tempfile
        d = tempfile.mkdtemp(); path = os.path.join(d, "arr.env")
        open(path, "w").write("# keep me\nSONARR_URL=http://s\nSONARR_KEY=k\n")
        env = lambda k: ms.read_env(path, k) if hasattr(ms, "read_env") else dict(l.split("=", 1) for l in open(path).read().splitlines() if "=" in l and not l.startswith("#")).get(k, "")
        with mock.patch.object(ms, "test", lambda *a: None):
            out = ms.configure({"id": "plex", "url": "http://plex:32400/", "key": "tok"}, env, path)
        self.assertEqual(out, {"ok": True, "saved": "plex"})
        text = open(path).read()
        self.assertIn("# keep me", text); self.assertIn("SONARR_KEY=k", text); self.assertIn("PLEX_URL=http://plex:32400\nPLEX_TOKEN=tok", text)
        self.assertEqual(oct(os.stat(path).st_mode & 0o777), "0o600")
        ms.configure({"id": "plex", "url": ""}, env, path)
        self.assertNotIn("PLEX", open(path).read())
        with self.assertRaises(ValueError):
            ms.configure({"id": "plex", "url": "http://x", "key": "a\nEVIL=1"}, env, path)
        with self.assertRaises(ValueError):
            ms.configure({"id": "nope", "url": "http://x"}, env, path)

    def test_catalog_never_includes_keys(self):
        cat = ms.catalog(ENV.get)
        self.assertTrue(any(m["id"] == "plex" and m["configured"] for m in cat))
        self.assertFalse(any("key" in m for m in cat))


if __name__ == "__main__":
    unittest.main()
