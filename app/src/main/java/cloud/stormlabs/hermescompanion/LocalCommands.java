package cloud.stormlabs.hermescompanion;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google-Assistant-style fast path: simple phone commands ("set a timer for 5 minutes", "call Mom",
 * "flashlight on") run on the phone at once instead of waiting seconds for the model. Anything that
 * isn't clearly one of these goes to Hermes as usual. English only; no side effects here.
 */
final class LocalCommands {
    private LocalCommands() {}

    private static final String NUM = "(\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five|fifty|sixty|ninety|half an?)";
    private static final Pattern FILLER = Pattern.compile("^(?:hey |ok |okay )?(?:hermes[,.!]?\\s+)?(?:please\\s+|can you\\s+|could you\\s+|would you\\s+)*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMER = Pattern.compile("^(?:set |start )?(?:a |an )?(?:timer (?:for )?" + NUM + " (second|sec|minute|min|hour|hr)s?(?: and " + NUM + " (second|sec|minute|min)s?)?|" + NUM + " (second|sec|minute|min|hour|hr)s? timer)(?: (?:for|called|named) (.+))?$");
    private static final Pattern ALARM = Pattern.compile("^(?:set (?:an |the )?alarm (?:for |at )?|wake me (?:up )?at |alarm (?:for |at )?)(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.)?(?: tomorrow)?$");
    private static final Pattern FLASH = Pattern.compile("^(?:turn |switch )?(on|off) (?:the )?(?:flashlight|torch)$|^(?:turn |switch )?(?:the )?(?:flashlight|torch) (on|off)$");
    private static final Pattern TOGGLE = Pattern.compile("^(?:turn |switch )?(on|off) (?:the )?(wi-?fi|bluetooth|do not disturb|dnd)$|^(?:turn |switch )?(?:the )?(wi-?fi|bluetooth|do not disturb|dnd) (on|off)$");
    private static final Pattern VOLUME = Pattern.compile("^(?:turn (?:the )?volume (up|down)|volume (up|down)|(mute|unmute)(?: the phone| volume)?|set (?:the )?volume to (\\d{1,3})(?: ?%| percent)?)$");
    private static final Pattern DISMISS = Pattern.compile("^(?:stop|dismiss|cancel|turn off|silence|shut up)(?: the| my)? (timers?|alarms?)$");
    private static final Pattern MEDIA = Pattern.compile("^(pause|stop|resume|play)(?: the)?(?: music| song| playback)?$|^(next|skip)(?: song| track)?$|^(previous|last)(?: song| track)$");
    private static final Pattern CALL = Pattern.compile("^(?:call|phone|ring|dial) (.+?)(?: on (?:the )?(?:phone|mobile))?$");
    private static final Pattern TEXT = Pattern.compile("^(?:send (?:a )?)?(?:text|sms|message) (?:to )?(.+?)(?: on (whatsapp|telegram|viber|signal))?(?:,)? (?:saying|that says|that|telling (?:them|him|her))[:,]? (.+)$");
    private static final Pattern APP_MSG = Pattern.compile("^(whatsapp|telegram|viber|signal) (.+?)(?:,)? (?:saying|that says|that)[:,]? (.+)$");
    private static final Pattern NAVIGATE = Pattern.compile("^(?:navigate|directions|get directions|take me|drive|walk|go) to (.+?)(?: by (car|foot|bike|transit|walking|driving))?$");
    private static final Pattern OPEN = Pattern.compile("^(?:open|launch|start) (?:the )?(.+?)(?: app)?$");
    private static final Pattern PLAY = Pattern.compile("^play (.+?)(?: on spotify| on youtube music)?$");
    private static final Pattern REMIND = Pattern.compile("^remind me (?:to (.+?) )?(?:at (\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm)?|in " + NUM + " (minute|min|hour|hr)s?)(?: to (.+))?$");

    /** One device action for a plain command, or null to let Hermes handle it. */
    static DeviceActions.Action parse(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty() || t.length() > 160 || t.contains("\n")) return null;
        t = FILLER.matcher(t).replaceFirst("");
        t = t.replaceAll("[.!?]+$", "").trim().toLowerCase(Locale.ROOT);
        String keep = raw.trim().replaceAll("\\s+", " ").replaceAll("[.!?]+$", ""); // original casing for names and text
        try {
            Matcher m;
            if ((m = TIMER.matcher(t)).matches()) {
                long seconds = m.group(1) != null ? seconds(m.group(1), m.group(2)) + (m.group(3) != null ? seconds(m.group(3), m.group(4)) : 0)
                        : seconds(m.group(5), m.group(6));
                if (seconds <= 0 || seconds > 86_399) return null;
                JSONObject o = new JSONObject().put("seconds", seconds);
                if (m.group(7) != null) o.put("label", m.group(7));
                return new DeviceActions.Action("set_timer", o);
            }
            if ((m = ALARM.matcher(t)).matches()) {
                int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                String ampm = m.group(3);
                if (ampm != null && ampm.startsWith("p") && h < 12) h += 12;
                if (ampm != null && ampm.startsWith("a") && h == 12) h = 0;
                if (h > 23 || min > 59) return null;
                return new DeviceActions.Action("set_alarm", new JSONObject().put("hour", h).put("minute", min));
            }
            if ((m = FLASH.matcher(t)).matches())
                return new DeviceActions.Action("flashlight", new JSONObject().put("on", "on".equals(m.group(1) != null ? m.group(1) : m.group(2))));
            if ((m = TOGGLE.matcher(t)).matches()) {
                String state = m.group(1) != null ? m.group(1) : m.group(4), what = m.group(2) != null ? m.group(2) : m.group(3);
                if (what.startsWith("wi")) return new DeviceActions.Action("wifi", new JSONObject().put("on", "on".equals(state)));
                if (what.equals("bluetooth")) return new DeviceActions.Action("bluetooth", new JSONObject().put("on", "on".equals(state)));
                return new DeviceActions.Action("dnd", new JSONObject().put("mode", state));
            }
            if ((m = VOLUME.matcher(t)).matches()) {
                JSONObject o = new JSONObject();
                if (m.group(4) != null) o.put("level", Math.min(100, Integer.parseInt(m.group(4))));
                else o.put("direction", m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
                return new DeviceActions.Action("volume", o);
            }
            if ((m = DISMISS.matcher(t)).matches())
                return new DeviceActions.Action("dismiss", new JSONObject().put("what", m.group(1).startsWith("timer") ? "timer" : "alarm"));
            if ((m = MEDIA.matcher(t)).matches()) {
                String cmd = m.group(1) != null ? (m.group(1).equals("stop") ? "pause" : m.group(1).equals("resume") ? "play" : m.group(1))
                        : m.group(2) != null ? "next" : "previous";
                return new DeviceActions.Action("media", new JSONObject().put("command", cmd));
            }
            if ((m = APP_MSG.matcher(t)).matches())
                return contactAction("message", tail(keep, m.group(2)), new JSONObject().put("app", m.group(1)).put("text", tail(keep, m.group(3))));
            if ((m = TEXT.matcher(t)).matches()) {
                String app = m.group(2);
                return app == null ? contactAction("sms", tail(keep, m.group(1)), new JSONObject().put("body", tail(keep, m.group(3))))
                        : contactAction("message", tail(keep, m.group(1)), new JSONObject().put("app", app).put("text", tail(keep, m.group(3))));
            }
            if ((m = CALL.matcher(t)).matches()) {
                String who = tail(keep, m.group(1));
                if (who.matches("(?i)(it|that|this|them|me|later|back|off|out|up|in)(\\b.*)?")) return null; // "call it a day", "call me back"
                return contactAction("call", who, new JSONObject());
            }
            if ((m = REMIND.matcher(t)).matches()) {
                String what = m.group(1) != null ? tail(keep, m.group(1)) : m.group(7) != null ? tail(keep, m.group(7)) : "";
                if (what.isEmpty()) return null;
                Calendar c = Calendar.getInstance();
                if (m.group(2) != null) {
                    int h = Integer.parseInt(m.group(2)), min = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                    if ("pm".equals(m.group(4)) && h < 12) h += 12;
                    if ("am".equals(m.group(4)) && h == 12) h = 0;
                    if (h > 23 || min > 59) return null;
                    c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0);
                    if (c.getTimeInMillis() < System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
                } else c.add(Calendar.SECOND, (int) seconds(m.group(5), m.group(6)));
                String at = String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                        c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
                return new DeviceActions.Action("remind", new JSONObject().put("text", what).put("at", at));
            }
            if ((m = NAVIGATE.matcher(t)).matches()) {
                String mode = m.group(2) == null ? (t.startsWith("walk") ? "walking" : "driving")
                        : m.group(2).startsWith("foot") || m.group(2).startsWith("walk") ? "walking" : m.group(2).equals("bike") ? "bicycling"
                        : m.group(2).equals("transit") ? "transit" : "driving";
                return new DeviceActions.Action("navigate", new JSONObject().put("destination", tail(keep, m.group(1))).put("mode", mode));
            }
            if ((m = PLAY.matcher(t)).matches() && !m.group(1).matches("(music|the music|something|a game|it)"))
                return new DeviceActions.Action("play_music", new JSONObject().put("query", tail(keep, m.group(1))));
            if ((m = OPEN.matcher(t)).matches() && !m.group(1).contains(" and ") && m.group(1).split(" ").length <= 3)
                return new DeviceActions.Action("open_app", new JSONObject().put("name", tail(keep, m.group(1))));
        } catch (JSONException | NumberFormatException e) { return null; }
        return null;
    }

    /** Spoken confirmation for a fast-path action that ran. */
    static String confirmation(DeviceActions.Action a, DeviceActionRunner.Outcome o) {
        if (!o.ok) return o.message;
        switch (a.type) {
            case "set_timer": {
                long s = a.args.optLong("seconds");
                return "Timer set for " + (s >= 3600 ? s / 3600 + " h " + (s % 3600) / 60 + " min" : s >= 60 ? s / 60 + " min" + (s % 60 > 0 ? " " + s % 60 + " s" : "") : s + " seconds") + ".";
            }
            case "set_alarm": return String.format(Locale.ROOT, "Alarm set for %02d:%02d.", a.args.optInt("hour"), a.args.optInt("minute"));
            default: return o.message.endsWith(".") ? o.message : o.message + ".";
        }
    }

    /** contact or number for call / sms / message. */
    private static DeviceActions.Action contactAction(String type, String who, JSONObject o) throws JSONException {
        String w = who.replaceFirst("(?i)^(my |the )", "").trim();
        if (w.isEmpty() || w.split(" ").length > 4) return null;
        if (w.replaceAll("[\\s()+-]", "").matches("\\d{3,}")) o.put("number", w); else o.put("contact", w);
        return new DeviceActions.Action(type, o);
    }

    /** The lower-cased match {@code part}, but with the user's original casing. */
    private static String tail(String original, String part) {
        int i = original.toLowerCase(Locale.ROOT).lastIndexOf(part);
        return i < 0 ? part : original.substring(i, i + part.length());
    }

    private static long seconds(String n, String unit) {
        double v;
        switch (n) {
            case "a": case "an": case "one": v = 1; break;
            case "half a": case "half an": v = 0.5; break;
            case "two": v = 2; break; case "three": v = 3; break; case "four": v = 4; break; case "five": v = 5; break;
            case "six": v = 6; break; case "seven": v = 7; break; case "eight": v = 8; break; case "nine": v = 9; break;
            case "ten": v = 10; break; case "eleven": v = 11; break; case "twelve": v = 12; break; case "fifteen": v = 15; break;
            case "twenty": v = 20; break; case "thirty": v = 30; break; case "forty": v = 40; break; case "forty-five": v = 45; break;
            case "fifty": v = 50; break; case "sixty": v = 60; break; case "ninety": v = 90; break;
            default: v = Double.parseDouble(n);
        }
        return Math.round(v * (unit.startsWith("h") ? 3600 : unit.startsWith("m") ? 60 : 1));
    }
}
