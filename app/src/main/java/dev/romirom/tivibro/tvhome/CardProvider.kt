package dev.romirom.tivibro.tvhome

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the card pictures [TvHome] draws for programmes without one, to the launcher, which can't read the app's
 * files. Read-only, and only files of its own directory whose names it made (hex + .jpg): nothing else can be reached.
 */
class CardProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!NAME.matches(name) || uri.pathSegments.size != 2 || uri.pathSegments[0] != "card") throw FileNotFoundException()
        val f = File(dir(ctx), name)
        if (!f.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0

    companion object {
        private val NAME = Regex("""[0-9a-f]{24}\.jpg""")

        fun dir(ctx: Context) = File(ctx.cacheDir, "cards").apply { mkdirs() }
        fun uri(ctx: Context, name: String): Uri = Uri.parse("content://${ctx.packageName}.cards/card/$name")

        /** Drops pictures no programme uses any more (and stray temporaries), keeping [keep] and anything Watch Next may
         *  still show for a day. */
        fun prune(ctx: Context, keep: Set<String>) {
            val now = System.currentTimeMillis()
            dir(ctx).listFiles()?.forEach { f -> if (f.name !in keep && now - f.lastModified() > 24 * 3600_000L) f.delete() }
        }
    }
}
