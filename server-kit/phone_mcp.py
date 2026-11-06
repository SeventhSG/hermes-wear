#!/usr/bin/env python3
"""phone MCP server (stdio): lets Hermes act on the user's Android phone from any channel without touching its
screen. Tools call the Companion hub (/phone/*), which pushes the action to the phone over FCM; the app runs it
through Android APIs and returns the result. No dependencies: newline-delimited JSON-RPC on stdin/stdout."""
import json, os, sys, urllib.request, urllib.error

HUB = os.environ.get("PHONE_HUB_URL", "http://127.0.0.1:8791")
ACTIONS = "- set_timer {seconds, label?}\n- set_alarm {hour 0-23, minute, label?, days? [1=Sun..7=Sat]}\n- show_alarms {}\n- remind {text, at 'YYYY-MM-DDTHH:MM' local}  (a reminder that alerts at that time)\n- dismiss {what timer|alarm}  (stops a ringing or running timer/alarm)\n- call_me {text}  (rings the user's phone like an incoming call from Hermes; when they answer, the text is spoken and the conversation continues by voice. Use sparingly: only for urgent or time-critical things)\n- call {number? | contact?}  (places the call directly when the user allowed direct calls; otherwise opens the dialer)\n- sms {number? | contact?, body}  (sends the text directly when allowed; otherwise opens messaging prefilled)\n- message {app whatsapp|telegram|viber|signal, contact? | number?, text}  (opens that chat with the text and presses Send\n- reply {id, text}  (answers a message from get_messages through that app's own notification reply; no screen steps)\n- email {to, subject?, body?}  (opens a compose screen; the user sends)\n- navigate {destination, mode? driving|walking|bicycling|transit}\n- map_search {query}\n- open_url {url}\n- web_search {query}\n- open_app {name? | package?}\n- list_app_actions {app?}  (what installed apps declare they can do: Assistant capabilities like actions.intent.CREATE_CALL and shortcuts, with params)\n- app_action {package, name, params? {key: value}}  (invoke one of those straight to the right screen)\n- calendar_event {title, start 'YYYY-MM-DDTHH:MM' local, end?, all_day?, location?, description?}  (saved directly when allowed, with a 10-minute reminder)\n- play_music {query}\n- media {command play|pause|toggle|next|previous}\n- flashlight {on true|false}\n- volume {level 0-100 | direction up|down|mute|unmute, stream? media|ring|alarm}\n- open_settings {page wifi|bluetooth|display|sound|battery|location|airplane|notifications|app|data}\n- dnd {mode on|off|priority|alarms} (requires Android notification policy access)\n- wifi {on true|false} (opens Android controls; screen control can tap switch)\n- bluetooth {on true|false} (opens Android controls; screen control can tap switch)\n- copy {text}\n- share {text}\n- camera {mode? photo|video}\n- capture {text}  (files a note into the user's capture inbox)\n- get_messages {}  (messages from the last 10 minutes in the chat apps the user opted in, each with an id)\n- get_calendar {days? 1-14}\n- find_contact {name}  (returns matching names and numbers)\n- list_apps {query?}\n- get_next_alarm {}\n- get_battery {}\n- get_location {}  (approximate)\n- get_clipboard {}"


def _key():
    try:
        for line in open(os.path.expanduser("~/.hermes/.env")):
            if line.startswith("API_SERVER_KEY="):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return os.environ.get("PHONE_HUB_KEY", "")


def hub(method, path, body=None, timeout=70):
    req = urllib.request.Request(HUB + path, data=json.dumps(body).encode() if body is not None else None, method=method,
                                 headers={"Authorization": "Bearer " + _key(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        try:
            return json.load(e)
        except Exception:
            return {"ok": False, "error": "HTTP %d" % e.code}
    except Exception as e:
        return {"ok": False, "error": str(e)}


TOOLS = [
    {"name": "phone_action",
     "description": "Do something on the user's Android phone directly through Android APIs (no screen, no app UI): "
                    "timers, alarms, reminders, calls, texts, WhatsApp/Telegram/Viber messages, replies to recent messages, "
                    "calendar, music, navigation, volume, flashlight, Do Not Disturb, and read-only queries (battery, location, "
                    "calendar, contacts, recent messages, clipboard). Sensitive actions (calls, texts, messages) may need the "
                    "user's OK on the phone; then the status is 'pending'. Actions and their args:\n" + ACTIONS,
     "inputSchema": {"type": "object", "properties": {
         "action": {"type": "string", "description": "action name, e.g. set_timer, call, get_battery"},
         "args": {"type": "object", "description": "the action's arguments, e.g. {\"seconds\": 300}"},
         "device": {"type": "string", "description": "optional phone name (see phone_devices); default is the user's main phone"}},
         "required": ["action"]}},
    {"name": "phone_devices", "description": "List the user's phones that can take phone_action requests.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "phone_result", "description": "Check the outcome of an earlier phone_action that came back 'pending' or 'sent' (e.g. the user approved it later).",
     "inputSchema": {"type": "object", "properties": {"id": {"type": "string", "description": "the id phone_action returned"}}, "required": ["id"]}},
]


def call(name, args):
    if name == "phone_devices":
        return hub("GET", "/phone/devices", timeout=10)
    if name == "phone_result":
        return hub("GET", "/phone/result?id=" + urllib.request.quote(str(args.get("id", ""))), timeout=10)
    if name == "phone_action":
        return hub("POST", "/phone/action", {"action": args.get("action", ""), "args": args.get("args") or {},
                                             "device": args.get("device", ""), "wait": 25})
    return {"ok": False, "error": "unknown tool " + name}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        mid, method = msg.get("id"), msg.get("method")
        if mid is None:
            continue  # notifications (initialized, cancelled)
        if method == "initialize":
            result = {"protocolVersion": msg.get("params", {}).get("protocolVersion", "2025-06-18"),
                      "capabilities": {"tools": {}}, "serverInfo": {"name": "phone", "version": "1.0"}}
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            p = msg.get("params", {})
            out = call(p.get("name", ""), p.get("arguments") or {})
            result = {"content": [{"type": "text", "text": json.dumps(out, ensure_ascii=False)}], "isError": not out.get("ok", False)}
        elif method == "ping":
            result = {}
        else:
            sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "method not found"}}) + "\n")
            sys.stdout.flush()
            continue
        sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": mid, "result": result}) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
