package local.barksms;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class QueueDb extends SQLiteOpenHelper {
    private static QueueDb instance;

    static synchronized QueueDb get(Context context) {
        if (instance == null) instance = new QueueDb(context.getApplicationContext());
        return instance;
    }

    private QueueDb(Context context) { super(context, "outbox.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pending (_id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, body TEXT NOT NULL, received INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE seen (fingerprint TEXT PRIMARY KEY, at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE events (_id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, message TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported database upgrade");
    }

    synchronized boolean enqueue(String sender, String body, long received) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues identity = new ContentValues();
            identity.put("fingerprint", fingerprint(sender, body, received));
            identity.put("at", System.currentTimeMillis());
            if (db.insertWithOnConflict("seen", null, identity, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
                db.setTransactionSuccessful();
                return false;
            }
            ContentValues values = new ContentValues();
            values.put("sender", sender);
            values.put("body", body);
            values.put("received", received);
            db.insertOrThrow("pending", null, values);
            db.delete("seen", "at < ?", new String[]{Long.toString(System.currentTimeMillis() - 7 * 86400_000L)});
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    synchronized Item first() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id,sender,body,received,attempts,due FROM pending ORDER BY due,_id LIMIT 1", null)) {
            if (!c.moveToFirst()) return null;
            return new Item(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4), c.getLong(5));
        }
    }

    synchronized int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pending", null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    synchronized void sent(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("pending", "_id=?", new String[]{Long.toString(id)});
            event("短信已送达 Bark 服务端");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void failed(Item item, String reason) {
        ContentValues values = new ContentValues();
        values.put("attempts", item.attempts + 1);
        values.put("due", System.currentTimeMillis() + Policy.retryDelay(item.attempts + 1));
        getWritableDatabase().update("pending", values, "_id=?", new String[]{Long.toString(item.id)});
        event(reason + "，稍后重试");
    }

    synchronized void retryNow() {
        ContentValues values = new ContentValues();
        values.put("due", 0);
        getWritableDatabase().update("pending", values, null, null);
    }

    synchronized void event(String message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("at", System.currentTimeMillis());
        values.put("message", message);
        db.insertOrThrow("events", null, values);
        db.execSQL("DELETE FROM events WHERE _id NOT IN (SELECT _id FROM events ORDER BY _id DESC LIMIT 20)");
    }

    synchronized String recent() {
        StringBuilder result = new StringBuilder();
        java.text.DateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT at,message FROM events ORDER BY _id DESC LIMIT 8", null)) {
            while (c.moveToNext()) {
                if (result.length() > 0) result.append("\n\n");
                result.append(format.format(new java.util.Date(c.getLong(0)))).append("  ").append(c.getString(1));
            }
        }
        return result.length() == 0 ? "暂无记录" : result.toString();
    }

    private static String fingerprint(String sender, String body, long received) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((sender + "\u0000" + received + "\u0000" + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static final class Item {
        final long id, received, due;
        final String sender, body;
        final int attempts;
        Item(long id, String sender, String body, long received, int attempts, long due) {
            this.id = id; this.sender = sender; this.body = body;
            this.received = received; this.attempts = attempts; this.due = due;
        }
    }
}
