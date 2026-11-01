"""Media servers as modules: one normalized API over Jellyfin, Emby, Plex and Ampache for the app's Media screen.

Each server is configured in the hub's arr.env (<ID>_URL plus its key) and only configured ones are listed.
Routes (hub, authenticated): GET /media/modules; GET /media/<id>/{info,sessions,resume,latest,counts,image};
POST /media/<id>/{control,refresh,played}. Items and sessions come back in one shape whatever the server:

  item    {id, title, subtitle, type, image, backdrop, progress (0-1, -1 none), played, unplayed}
  session {id, user, client, device, title, subtitle, image, backdrop, position, duration (s), paused}

`image`/`backdrop` are hub paths ("/media/<id>/image?ref=...") so artwork goes through the hub with the server's key.
Adding a server = one Adapter subclass + an entry in SERVERS.
"""
import json
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

# The *arr side of the Media screen (hub /arr proxy); listed in /media/modules when configured.
ARR = [("sonarr", "Sonarr", "tv"), ("radarr", "Radarr", "movies"), ("qbit", "qBittorrent", "downloads"), ("prowlarr", "Prowlarr", "indexers")]


class Unsupported(Exception):
    """The server has no such feature (Plex can't pause a remote player from the server side, Ampache has no resume)."""


class Adapter:
    name = ""
    features = ()

    def __init__(self, sid, base, key):
        self.sid, self.base, self.key = sid, base.rstrip("/"), key

    def img(self, ref, kind="Primary"):
        return f"/media/{self.sid}/image?" + urllib.parse.urlencode({"ref": ref, "kind": kind}) if ref else ""

    def get(self, path, query=None, raw=False, timeout=20):
        return self.call("GET", path, query, None, raw, timeout)

    def call(self, method, path, query=None, body=None, raw=False, timeout=20):
        url = self.base + path + ("?" + urllib.parse.urlencode(query) if query else "")
        req = urllib.request.Request(url, data=body, method=method, headers=self.headers())
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read(8_000_000)
            if raw:
                return data, r.headers.get("Content-Type", "application/octet-stream")
            return json.loads(data) if data.strip() else {}

    def headers(self):
        return {"Accept": "application/json"}

    # normalized operations; adapters override what their server supports
    def info(self): raise Unsupported()
    def sessions(self): return []
    def resume(self, limit): return []
    def latest(self, limit): return []
    def counts(self): return []
    def image(self, ref, kind, width): raise Unsupported()
    def control(self, session, cmd): raise Unsupported()
    def refresh(self): raise Unsupported()
    def played(self, item, on): raise Unsupported()


class Jellyfin(Adapter):
    """Jellyfin (and Emby, which shares the API): token header, per-user Latest/Resume for the admin user."""
    name = "Jellyfin"
    features = ("sessions", "resume", "latest", "counts", "control", "refresh", "played")
    _user = None

    def headers(self):
        return {"Accept": "application/json", "X-Emby-Token": self.key}

    def user(self):
        if self._user is None:
            users = self.get("/Users")
            admin = next((u for u in users if (u.get("Policy") or {}).get("IsAdministrator")), users[0] if users else {})
            self._user = admin.get("Id", "")
        return self._user

    def item(self, i):
        ep = i.get("Type") == "Episode"
        art = i.get("SeriesId") if ep and i.get("SeriesId") else i.get("Id")
        ud = i.get("UserData") or {}
        sub = (f"S{i.get('ParentIndexNumber') or 0:02d}E{i.get('IndexNumber') or 0:02d} · {i.get('Name', '')}" if ep
               else str(i.get("ProductionYear")) if i.get("ProductionYear") else i.get("Type", ""))
        return {"id": i.get("Id", ""), "title": i.get("SeriesName") if ep else i.get("Name", ""), "subtitle": sub, "type": i.get("Type", "").lower(),
                "image": self.img(art), "backdrop": self.img(art, "Backdrop"),
                "progress": (ud.get("PlayedPercentage") or 0) / 100 if ud.get("PlayedPercentage") else -1,
                "played": bool(ud.get("Played")), "unplayed": ud.get("UnplayedItemCount") or 0}

    def info(self):
        i = self.get("/System/Info")
        return {"name": i.get("ServerName") or self.name, "version": i.get("Version", "")}

    def sessions(self):
        out = []
        for s in self.get("/Sessions"):
            np = s.get("NowPlayingItem")
            if not np:
                continue
            it, ps = self.item(np), s.get("PlayState") or {}
            ep = np.get("Type") == "Episode"
            out.append({"id": s.get("Id", ""), "user": s.get("UserName", ""), "client": s.get("Client", ""), "device": s.get("DeviceName", ""),
                        "title": f"{it['title']} {it['subtitle'].split(' · ')[0]}" if ep else it["title"],
                        "subtitle": np.get("Name", "") if ep else it["subtitle"], "image": it["image"], "backdrop": it["backdrop"],
                        "position": (ps.get("PositionTicks") or 0) / 1e7, "duration": (np.get("RunTimeTicks") or 0) / 1e7,
                        "paused": bool(ps.get("IsPaused"))})
        return out

    def resume(self, limit):
        return [self.item(i) for i in self.get(f"/Users/{self.user()}/Items/Resume", {"Limit": limit}).get("Items", [])]

    def latest(self, limit):
        return [self.item(i) for i in self.get(f"/Users/{self.user()}/Items/Latest", {"Limit": limit})]

    def counts(self):
        c = self.get("/Items/Counts")
        return [{"label": l, "n": c.get(k, 0)} for l, k in (("Series", "SeriesCount"), ("Episodes", "EpisodeCount"),
                                                             ("Movies", "MovieCount"), ("Albums", "AlbumCount")) if c.get(k)]

    def image(self, ref, kind, width):
        if not re.fullmatch(r"[0-9a-f]{32}", ref) or kind not in ("Primary", "Backdrop", "Thumb"):
            raise ValueError("bad image ref")
        return self.get(f"/Items/{ref}/Images/{kind}", {"maxWidth": width}, raw=True)

    def control(self, session, cmd):
        verb = {"playpause": "PlayPause", "stop": "Stop"}[cmd]
        self.call("POST", f"/Sessions/{urllib.parse.quote(session)}/Playing/{verb}", body=b"")

    def refresh(self):
        self.call("POST", "/Library/Refresh", body=b"")

    def played(self, item, on):
        if not re.fullmatch(r"[0-9a-f]{32}", item):
            raise ValueError("bad item id")
        self.call("POST" if on else "DELETE", f"/Users/{self.user()}/PlayedItems/{item}", body=b"" if on else None)


class Emby(Jellyfin):
    name = "Emby"


class Plex(Adapter):
    """Plex: X-Plex-Token, JSON with Accept. The server can stop a stream (Plex Pass) but not pause a remote player."""
    name = "Plex"
    features = ("sessions", "resume", "latest", "counts", "control", "refresh", "played")

    def headers(self):
        return {"Accept": "application/json", "X-Plex-Token": self.key}

    def meta(self, path, query=None):
        return (self.get(path, query).get("MediaContainer") or {}).get("Metadata") or []

    def item(self, m):
        ep = m.get("type") == "episode"
        thumb = m.get("grandparentThumb") if ep and m.get("grandparentThumb") else m.get("thumb", "")
        dur, off = m.get("duration") or 0, m.get("viewOffset") or 0
        sub = (f"S{m.get('parentIndex') or 0:02d}E{m.get('index') or 0:02d} · {m.get('title', '')}" if ep
               else str(m.get("year")) if m.get("year") else m.get("type", ""))
        return {"id": str(m.get("ratingKey", "")), "title": m.get("grandparentTitle") if ep else m.get("title", ""), "subtitle": sub,
                "type": m.get("type", ""), "image": self.img(thumb), "backdrop": self.img(m.get("grandparentArt") or m.get("art") or thumb),
                "progress": off / dur if dur and off else -1, "played": (m.get("viewCount") or 0) > 0,
                "unplayed": max(0, (m.get("leafCount") or 0) - (m.get("viewedLeafCount") or 0))}

    def info(self):
        c = self.get("/").get("MediaContainer") or {}
        return {"name": c.get("friendlyName") or self.name, "version": c.get("version", "")}

    def sessions(self):
        out = []
        for m in self.meta("/status/sessions"):
            it = self.item(m)
            ep = m.get("type") == "episode"
            player = m.get("Player") or {}
            out.append({"id": str((m.get("Session") or {}).get("id", "")), "user": (m.get("User") or {}).get("title", ""),
                        "client": player.get("product", ""), "device": player.get("title", ""),
                        "title": f"{it['title']} {it['subtitle'].split(' · ')[0]}" if ep else it["title"],
                        "subtitle": m.get("title", "") if ep else it["subtitle"], "image": it["image"], "backdrop": it["backdrop"],
                        "position": (m.get("viewOffset") or 0) / 1000, "duration": (m.get("duration") or 0) / 1000,
                        "paused": player.get("state") == "paused"})
        return out

    def resume(self, limit):
        return [self.item(m) for m in self.meta("/library/onDeck")[:limit]]

    def latest(self, limit):
        return [self.item(m) for m in self.meta("/library/recentlyAdded", {"X-Plex-Container-Start": 0, "X-Plex-Container-Size": limit})]

    def counts(self):
        out = []
        for d in (self.get("/library/sections").get("MediaContainer") or {}).get("Directory") or []:
            c = self.get(f"/library/sections/{d.get('key')}/all", {"X-Plex-Container-Start": 0, "X-Plex-Container-Size": 0}).get("MediaContainer") or {}
            out.append({"label": d.get("title", ""), "n": c.get("totalSize", c.get("size", 0))})
        return out

    def image(self, ref, kind, width):
        if not re.fullmatch(r"/library/metadata/\d+/(thumb|art)/\d+", ref):
            raise ValueError("bad image ref")
        return self.get("/photo/:/transcode", {"url": ref, "width": width, "height": width * 3 // 2, "minSize": 1}, raw=True)

    def control(self, session, cmd):
        if cmd != "stop":
            raise Unsupported()
        self.get("/status/sessions/terminate", {"sessionId": session, "reason": "Stopped from Hermes Companion"}, raw=True)

    def refresh(self):
        self.get("/library/sections/all/refresh", raw=True)

    def played(self, item, on):
        if not item.isdigit():
            raise ValueError("bad item id")
        self.get("/:/scrobble" if on else "/:/unscrobble", {"key": item, "identifier": "com.plexapp.plugins.library"}, raw=True)


class Ampache(Adapter):
    """Ampache (music): JSON API with an API key; a handshake gives a session token that lasts about an hour."""
    name = "Ampache"
    features = ("sessions", "latest", "counts")
    _auth, _auth_t, _hello = "", 0.0, {}

    def api(self, action, **params):
        if not self._auth or time.time() - self._auth_t > 1800:
            self._hello = self.get("/server/json.server.php", {"action": "handshake", "auth": self.key, "version": "6.0.0"})
            if "auth" not in self._hello:
                raise urllib.error.URLError((self._hello.get("error") or {}).get("errorMessage", "handshake refused"))
            self._auth, self._auth_t = self._hello["auth"], time.time()
        return self.get("/server/json.server.php", dict(params, action=action, auth=self._auth))

    def item(self, a, kind="album"):
        artist = a.get("artist") or a.get("albumartist") or {}
        return {"id": str(a.get("id", "")), "title": a.get("name") or a.get("title", ""),
                "subtitle": " · ".join(x for x in ((artist.get("name") if isinstance(artist, dict) else str(artist)), str(a.get("year") or "")) if x and x != "0"),
                "type": kind, "image": self.img(f"{kind}:{a.get('id')}"), "backdrop": "", "progress": -1, "played": False, "unplayed": 0}

    def info(self):
        self.api("ping")
        return {"name": self.name, "version": self._hello.get("server") or self._hello.get("api", "")}

    def sessions(self):
        out = []
        for s in self.api("now_playing").get("now_playing") or []:
            song = s.get("song") if isinstance(s.get("song"), dict) else s
            album = song.get("album") or {}
            artist = song.get("artist") or {}
            out.append({"id": str(s.get("id", song.get("id", ""))), "user": (s.get("user") or {}).get("username", "") if isinstance(s.get("user"), dict) else str(s.get("user") or ""),
                        "client": str(s.get("agent") or s.get("client") or ""), "device": "",
                        "title": song.get("title") or song.get("name", ""), "subtitle": " · ".join(x for x in (artist.get("name", ""), album.get("name", "")) if x),
                        "image": self.img(f"album:{album.get('id')}") if album.get("id") else "", "backdrop": "",
                        "position": 0, "duration": song.get("time") or 0, "paused": False})
        return out

    def latest(self, limit):
        return [self.item(a) for a in self.api("stats", type="album", filter="newest", limit=limit).get("album") or []]

    def counts(self):
        self.api("ping")
        return [{"label": l, "n": self._hello.get(k, 0)} for l, k in (("Artists", "artists"), ("Albums", "albums"), ("Songs", "songs"), ("Playlists", "playlists")) if self._hello.get(k)]

    def image(self, ref, kind, width):
        m = re.fullmatch(r"(album|artist|song|playlist):(\d+)", ref)
        if not m:
            raise ValueError("bad image ref")
        self.api("ping")
        return self.get("/server/json.server.php", {"action": "get_art", "auth": self._auth, "type": m.group(1), "id": m.group(2)}, raw=True)


# id -> (adapter, key variable in arr.env). Emby and Jellyfin keep their own ids so both can be configured at once.
SERVERS = {"jellyfin": (Jellyfin, "JELLYFIN_API_KEY"), "emby": (Emby, "EMBY_API_KEY"), "plex": (Plex, "PLEX_TOKEN"), "ampache": (Ampache, "AMPACHE_KEY")}

# *arr key variables (qBittorrent has none: the hub reaches it on a whitelisted subnet).
ARR_KEYS = {"sonarr": ("SONARR_KEY", "api/v3/system/status"), "radarr": ("RADARR_KEY", "api/v3/system/status"),
            "prowlarr": ("PROWLARR_KEY", "api/v1/system/status"), "qbit": ("", "api/v2/app/version")}

_adapters, _lock = {}, threading.Lock()
_cache = {}


def configured(env):
    """[(id, name, kind)] for every module with a URL in arr.env. `env(name)` reads one variable."""
    out = [(i, n, k) for i, n, k in ARR if env(i.upper() + "_URL")]
    out += [(i, a.name, "server") for i, (a, keyvar) in SERVERS.items() if env(i.upper() + "_URL") and env(keyvar)]
    return out


def adapter(sid, env):
    if sid not in SERVERS:
        return None
    cls, keyvar = SERVERS[sid]
    base, key = env(sid.upper() + "_URL"), env(keyvar)
    if not base or not key:
        return None
    with _lock:
        a = _adapters.get(sid)
        if a is None or a.base != base.rstrip("/") or a.key != key:
            a = _adapters[sid] = cls(sid, base, key)
        return a


def catalog(env):
    """Every module the hub supports, configured or not, for the app's setup screen. Never includes keys."""
    out = [{"id": i, "name": n, "kind": k, "configured": bool(env(i.upper() + "_URL")), "needs_key": bool(ARR_KEYS[i][0])} for i, n, k in ARR]
    out += [{"id": i, "name": a.name, "kind": "server", "configured": bool(env(i.upper() + "_URL") and env(kv)), "needs_key": True}
            for i, (a, kv) in SERVERS.items()]
    return out


def test(sid, url, key):
    """Raises unless the service answers with this URL and key (so a typo never gets saved)."""
    if sid in SERVERS:
        SERVERS[sid][0](sid, url, key).info()
        return
    keyvar, probe = ARR_KEYS[sid]
    req = urllib.request.Request(url.rstrip("/") + "/" + probe, headers={"X-Api-Key": key} if keyvar else {})
    with urllib.request.urlopen(req, timeout=15) as r:
        r.read(1000)


def write_env(path, updates):
    """Sets (value) or removes (None) variables in an env file, keeping every other line; written 0600 atomically."""
    import os
    try:
        lines = open(path).read().splitlines()
    except OSError:
        lines = ["# media modules for the hub (/arr and /media) - chmod 600"]
    out, done = [], set()
    for line in lines:
        name = line.split("=", 1)[0].strip() if "=" in line and not line.lstrip().startswith("#") else None
        if name in updates:
            done.add(name)
            if updates[name] is not None:
                out.append(f"{name}={updates[name]}")
            continue
        out.append(line)
    out += [f"{k}={v}" for k, v in updates.items() if k not in done and v is not None]
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write("\n".join(out) + "\n")
    os.replace(tmp, path)


def configure(payload, env, env_path):
    """POST /media/config {id, url, key}: test, then save; an empty url removes the module."""
    sid, url, key = str(payload.get("id", "")), str(payload.get("url", "")).strip(), str(payload.get("key", "")).strip()
    keyvar = SERVERS[sid][1] if sid in SERVERS else ARR_KEYS[sid][0] if sid in ARR_KEYS else None
    if keyvar is None:
        raise ValueError("unknown module")
    if any(c in url + key for c in "\r\n\0"):
        raise ValueError("bad characters")
    if not url:
        write_env(env_path, {sid.upper() + "_URL": None, **({keyvar: None} if keyvar else {})})
        return {"ok": True, "removed": sid}
    if not re.fullmatch(r"https?://[^\s/]+(/[^\s]*)?", url):
        raise ValueError("the URL must start with http:// or https://")
    if keyvar and not key:
        raise ValueError("this service needs its API key")
    test(sid, url, key)
    write_env(env_path, {sid.upper() + "_URL": url.rstrip("/"), **({keyvar: key} if keyvar else {})})
    with _lock:
        _adapters.pop(sid, None)
    _cache.clear()
    return {"ok": True, "saved": sid}


def route(method, path, query, body, env, env_path=None):
    """(status, bytes, content type) for /media/...; `env` reads arr.env."""
    def js(obj, status=200):
        return status, json.dumps(obj).encode(), "application/json"

    if path == "/media/config":
        if method == "GET":
            return js({"ok": True, "modules": catalog(env)})
        try:
            return js(configure(json.loads(body or b"{}"), env, env_path))
        except (ValueError, KeyError) as e:
            return js({"ok": False, "error": str(e)}, 400)
        except Exception as e:
            return js({"ok": False, "error": f"not saved: the service didn't answer ({type(e).__name__})"}, 400)
    if path == "/media/modules" and method == "GET":
        mods = []
        for i, n, k in configured(env):
            mod = {"id": i, "name": n, "kind": k}
            if k == "server":
                mod["features"] = list(SERVERS[i][0].features)
            mods.append(mod)
        return js({"ok": True, "modules": mods})
    m = re.fullmatch(r"/media/([a-z]+)/([a-z]+)", path)
    a = adapter(m.group(1), env) if m else None
    if a is None:
        return js({"ok": False, "error": "media server not configured"}, 404)
    op, q = m.group(2), urllib.parse.parse_qs(query or "")
    arg = lambda k, d="": (q.get(k) or [d])[0]
    try:
        payload = json.loads(body or b"{}") if method == "POST" else {}
        if method == "GET" and op == "image":
            key = (a.sid, arg("ref"), arg("kind", "Primary"), arg("w", "360"))
            hit = _cache.get(key)
            if hit and time.time() - hit[0] < 3600:
                return 200, hit[1], hit[2]
            data, ctype = a.image(arg("ref"), arg("kind", "Primary"), max(64, min(1280, int(arg("w", "360") or 360))))
            if len(_cache) > 400:
                _cache.clear()
            _cache[key] = (time.time(), data, ctype)
            return 200, data, ctype
        limit = max(1, min(60, int(arg("limit", "16") or 16)))
        if method == "GET" and op in ("info", "sessions", "resume", "latest", "counts"):
            key = (a.sid, op, limit)
            hit = _cache.get(key)
            if op != "sessions" and hit and time.time() - hit[0] < 30:  # libraries change slowly; who is playing never cached
                return js(hit[1])
            out = {"info": lambda: dict(a.info(), ok=True), "sessions": lambda: {"ok": True, "sessions": a.sessions()},
                   "resume": lambda: {"ok": True, "items": a.resume(limit)}, "latest": lambda: {"ok": True, "items": a.latest(limit)},
                   "counts": lambda: {"ok": True, "counts": a.counts()}}[op]()
            _cache[key] = (time.time(), out, "")
            return js(out)
        if method == "POST" and op == "control":
            a.control(str(payload.get("session", "")), str(payload.get("cmd", "")))
        elif method == "POST" and op == "refresh":
            a.refresh()
        elif method == "POST" and op == "played":
            a.played(str(payload.get("id", "")), bool(payload.get("played", True)))
            _cache.clear()
        else:
            return js({"ok": False, "error": "unknown media operation"}, 404)
        return js({"ok": True})
    except Unsupported:
        return js({"ok": False, "error": f"{a.name} can't do that"}, 501)
    except (ValueError, KeyError) as e:
        return js({"ok": False, "error": str(e) or "bad request"}, 400)
    except urllib.error.HTTPError as e:
        return js({"ok": False, "error": f"{a.name} answered {e.code}"}, 502)
    except Exception as e:
        return js({"ok": False, "error": f"{a.name} unreachable: {type(e).__name__}"}, 502)
