package cloud.stormlabs.hermescompanion;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Local record of phone activity. Clearing this log also removes chat action cards. */
public final class ActivityLog extends SQLiteOpenHelper {
    private static final String TABLE = "activity";

    public static final class Entry {
        public final long id, ts;
        public final String kind, title, detail, sessionId, replyKey;
        public final boolean ok;
        Entry(Cursor c) {
            id = c.getLong(0); ts = c.getLong(1); kind = c.getString(2); title = c.getString(3);
            detail = c.getString(4); ok = c.getInt(5) != 0; sessionId = c.getString(6); replyKey = c.getString(7);
        }
    }

    private ActivityLog(Context c) { super(c.getApplicationContext(), "activity_log.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE activity (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, "
                + "kind TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, ok INTEGER NOT NULL, "
                + "session_id TEXT, reply_key TEXT)");
        db.execSQL("CREATE INDEX activity_session ON activity(session_id, id DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public static void log(Context c, String kind, String title, String detail, boolean ok, String sessionId) {
        log(c, kind, title, detail, ok, sessionId, null);
    }

    static void log(Context c, String kind, String title, String detail, boolean ok, String sessionId, String replyKey) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis()); v.put("kind", kind); v.put("title", title);
        v.put("detail", detail); v.put("ok", ok ? 1 : 0); v.put("session_id", sessionId); v.put("reply_key", replyKey);
        try (ActivityLog db = new ActivityLog(c)) { db.getWritableDatabase().insertOrThrow(TABLE, null, v); }
    }

    public static List<Entry> recent(Context c, int limit) { return read(c, null, limit); }
    public static List<Entry> forSession(Context c, String sessionId, int limit) { return read(c, sessionId, limit); }

    private static List<Entry> read(Context c, String sessionId, int limit) {
        List<Entry> result = new ArrayList<>();
        if (limit <= 0) return result;
        try (ActivityLog db = new ActivityLog(c);
             Cursor rows = db.getReadableDatabase().query(TABLE, null, sessionId == null ? null : "session_id=?",
                     sessionId == null ? null : new String[]{sessionId}, null, null, "id DESC", String.valueOf(limit))) {
            while (rows.moveToNext()) result.add(new Entry(rows));
        }
        return result;
    }

    public static void clear(Context c) {
        try (ActivityLog db = new ActivityLog(c)) { db.getWritableDatabase().delete(TABLE, null, null); }
    }
}
