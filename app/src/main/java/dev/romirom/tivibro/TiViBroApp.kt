package dev.romirom.tivibro

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath

class TiViBroApp : Application(), SingletonImageLoader.Factory {
    /** Logos and TMDB pictures: a capped disk cache (50 MB) and a modest share of the heap on a 1.5 GB TV. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(50L shl 20).build() }
        .build()

    override fun onCreate() {
        super.onCreate()
        L10n.init(this)
        dev.romirom.tivibro.tvhome.TvHomeWorker.schedule(this)
    }
}
