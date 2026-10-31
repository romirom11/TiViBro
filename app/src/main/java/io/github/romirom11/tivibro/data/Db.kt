package io.github.romirom11.tivibro.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** «Continue watching» positions, per playlist ([pl]: its id; rows from before playlists were plural are «0»). The
 *  programme guide used to live here too (`prog`, ~380 MB); it is read from epg.team's blobs now (see [Guide]). */
class Db(private val ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 3) {
    private var vacuum = false

    init {
        // the WAL only made sense for reading the guide while an import wrote it; it also kept the import's size
        setWriteAheadLoggingEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("DROP TABLE IF EXISTS prog") // its index goes with it
            vacuum = true
        }
        if (old < 3) {
            db.execSQL("ALTER TABLE mark RENAME TO mark2")
            db.execSQL(CREATE)
            db.execSQL("INSERT INTO mark(pl, ch, s, e, t, pos, at) SELECT '0', ch, s, e, t, pos, at FROM mark2")
            db.execSQL("DROP TABLE mark2")
        }
    }

    /** VACUUM can't run inside the upgrade's transaction: give the freed pages back to the file system here. */
    override fun onOpen(db: SQLiteDatabase) {
        if (!vacuum) return
        vacuum = false
        db.execSQL("VACUUM")
    }

    /**
     * Version 1 held the guide next to the marks. Dropping that table walks and frees its ~90k pages (over a minute of
     * disk work on the emulator, worse on a TV); copying the few marks out and deleting the file is instant. Call once,
     * off the main thread, before anything else; [onUpgrade] remains the fallback.
     */
    fun dropOldGuide() {
        val f = ctx.getDatabasePath(NAME)
        if (!f.exists()) return
        val keep = ArrayList<ContentValues>()
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (db.version != 1) return
            db.rawQuery("SELECT ch, s, e, t, pos, at FROM mark", null).use { c ->
                while (c.moveToNext()) keep += ContentValues().apply {
                    put("ch", c.getString(0)); put("s", c.getLong(1)); put("e", c.getLong(2)); put("t", c.getString(3)); put("pos", c.getLong(4)); put("at", c.getLong(5))
                }
            }
        }
        SQLiteDatabase.deleteDatabase(f) // with its -wal, -shm and -journal
        val db = writableDatabase // onCreate
        db.beginTransaction()
        try {
            for (v in keep) db.insert("mark", null, v)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    data class MarkRow(val ch: String, val p: Programme, val pos: Long, val at: Long)

    fun marks(pl: String): List<MarkRow> =
        readableDatabase.rawQuery("SELECT ch, s, e, t, pos, at FROM mark WHERE pl = ? ORDER BY at DESC LIMIT 200", arrayOf(pl)).use { c ->
            List(c.count) {
                c.moveToNext()
                MarkRow(c.getString(0), Programme(c.getLong(1), c.getLong(2), c.getString(3), ""), c.getLong(4), c.getLong(5))
            }
        }

    fun saveMark(pl: String, m: Mark) {
        writableDatabase.insertWithOnConflict("mark", null, ContentValues().apply {
            put("pl", pl); put("ch", m.ch.id); put("s", m.p.start); put("e", m.p.end); put("t", m.p.title); put("pos", m.pos); put("at", m.at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteMark(pl: String, ch: String, start: Long) {
        writableDatabase.delete("mark", "pl = ? AND ch = ? AND s = ?", arrayOf(pl, ch, start.toString()))
    }

    fun deleteMarks(pl: String) {
        writableDatabase.delete("mark", "pl = ?", arrayOf(pl))
    }

    companion object {
        private const val NAME = "tivibro.db"
        private const val CREATE = "CREATE TABLE mark(pl TEXT NOT NULL DEFAULT '0', ch TEXT NOT NULL, s INTEGER NOT NULL, e INTEGER NOT NULL, " +
            "t TEXT NOT NULL, pos INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(pl, ch, s))"
    }
}
