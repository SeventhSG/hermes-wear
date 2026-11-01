package cloud.stormlabs.hermescompanion;

import android.app.AlarmManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Read-only phone queries Hermes can chain on. Each data type is gated twice: the user's opt-in in
 * Settings and Android's own runtime permission. A refusal is reported back to Hermes as such.
 */
final class PhoneQueries {
    private PhoneQueries() {}

    static DeviceActionRunner.Outcome run(Context c, DeviceActions.Action a) {
        SettingsStore store = new SettingsStore(c);
        switch (a.type) {
            case "get_calendar": {
                if (!store.allowCalendar()) return DeviceActionRunner.refused(a, "calendar access is off in Hermes settings");
                if (!granted(c, android.Manifest.permission.READ_CALENDAR)) return DeviceActionRunner.refused(a, "Android calendar permission not granted");
                return DeviceActionRunner.okResult(a, calendar(c, Math.max(1, Math.min(14, a.args.optInt("days", 1)))));
            }
            case "find_contact": {
                if (!store.allowContacts()) return DeviceActionRunner.refused(a, "contacts access is off in Hermes settings");
                if (!granted(c, android.Manifest.permission.READ_CONTACTS)) return DeviceActionRunner.refused(a, "Android contacts permission not granted");
                return DeviceActionRunner.okResult(a, contacts(c, a.str("name")));
            }
            case "list_apps": return DeviceActionRunner.okResult(a, apps(c, a.str("query")));
            case "get_next_alarm": {
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                AlarmManager.AlarmClockInfo next = am == null ? null : am.getNextAlarmClock();
                return DeviceActionRunner.okResult(a, next == null ? "No alarm set" : "Next alarm: " + fmt(next.getTriggerTime()));
            }
            case "get_battery": {
                Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (i == null) return DeviceActionRunner.okResult(a, "unknown");
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, i.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                return DeviceActionRunner.okResult(a, level + "%" + (charging ? ", charging" : ", not charging"));
            }
            case "get_location": {
                if (!store.shareLocation()) return DeviceActionRunner.refused(a, "location sharing is off in Hermes settings");
                String loc = DeviceContext.locationLine(c);
                return loc.isEmpty() ? DeviceActionRunner.refused(a, "no recent location available") : DeviceActionRunner.okResult(a, loc);
            }
            case "get_clipboard": {
                if (!store.allowClipboard()) return DeviceActionRunner.refused(a, "clipboard access is off in Hermes settings");
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = cm == null ? null : cm.getPrimaryClip();
                CharSequence t = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(c);
                String text = t == null ? "" : t.toString();
                if (text.length() > 4000) text = text.substring(0, 4000) + "…";
                return DeviceActionRunner.okResult(a, text.isEmpty() ? "Clipboard is empty" : text);
            }
            default: return DeviceActionRunner.fail(a, "Unsupported query");
        }
    }

    private static boolean granted(Context c, String perm) { return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private static String fmt(long ms) { return new SimpleDateFormat("EEE d MMM HH:mm", Locale.ENGLISH).format(new Date(ms)); }

    private static String calendar(Context c, int days) {
        long now = System.currentTimeMillis(), end = now + days * 86_400_000L;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(b, now);
        android.content.ContentUris.appendId(b, end);
        String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION};
        StringBuilder out = new StringBuilder();
        int n = 0;
        try (Cursor cur = c.getContentResolver().query(b.build(), cols, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (cur != null && cur.moveToNext() && n < 40) {
                n++;
                boolean allDay = cur.getInt(3) == 1;
                out.append("- ").append(allDay ? new SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(new Date(cur.getLong(1))) + " (all day)"
                        : fmt(cur.getLong(1)) + "–" + new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date(cur.getLong(2))))
                        .append(": ").append(cur.getString(0) == null ? "(no title)" : cur.getString(0));
                String loc = cur.getString(4);
                if (loc != null && !loc.isEmpty()) out.append(" @ ").append(loc);
                out.append('\n');
            }
        }
        return n == 0 ? "No events in the next " + days + " day(s)" : out.toString().trim();
    }

    private static String contacts(Context c, String name) {
        if (name.isEmpty()) return "No name given";
        Uri uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name));
        String[] cols = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE};
        StringBuilder out = new StringBuilder();
        int n = 0;
        try (Cursor cur = c.getContentResolver().query(uri, cols, null, null, null)) {
            while (cur != null && cur.moveToNext() && n < 8) {
                n++;
                CharSequence type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(c.getResources(), cur.getInt(2), "");
                out.append("- ").append(cur.getString(0)).append(": ").append(cur.getString(1)).append(" (").append(type).append(")\n");
            }
        }
        return n == 0 ? "No contact matches \"" + name + "\"" : out.toString().trim();
    }

    private static String apps(Context c, String query) {
        PackageManager pm = c.getPackageManager();
        List<String> names = new ArrayList<>();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String label = String.valueOf(ri.loadLabel(pm));
            if (q.isEmpty() || label.toLowerCase(Locale.ROOT).contains(q)) names.add(label + " (" + ri.activityInfo.packageName + ")");
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (names.size() > 150) names = names.subList(0, 150);
        return names.isEmpty() ? "No matching apps" : String.join("\n", names);
    }
}
