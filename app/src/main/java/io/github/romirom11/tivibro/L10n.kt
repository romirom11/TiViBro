package io.github.romirom11.tivibro

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.annotation.ArrayRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * The app's own language, independent of the system's: «Як у системі» (default), Ukrainian or English. The activity
 * wraps its base context with [wrap]; code without a localized context of its own (the view model, the LAN page, the
 * data layer) reads strings through [str] / [plural], which follow the same choice.
 */
object L10n {
    enum class Lang { SYSTEM, UK, EN }

    private const val PREFS = "tivibro"
    private const val KEY = "lang"
    @Volatile private var res: Context? = null

    fun init(app: Context) {
        res = localized(app.applicationContext)
    }

    fun lang(c: Context): Lang = runCatching {
        Lang.valueOf(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "")
    }.getOrDefault(Lang.SYSTEM)

    fun setLang(c: Context, l: Lang) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, l.name).commit()
        init(c)
    }

    /** The locale strings are shown in right now. */
    val locale: Locale get() = res?.let { configLocale(it.resources.configuration) } ?: Locale.getDefault()

    /** «Як у системі»: Ukrainian for a Ukrainian (or Russian, Belarusian) system, English for anything else. */
    private fun systemLocale(): Locale {
        val sys = configLocale(Resources.getSystem().configuration)
        return if (sys.language in setOf("uk", "ru", "be")) Locale("uk") else Locale.ENGLISH
    }

    fun localeFor(l: Lang): Locale = when (l) {
        Lang.SYSTEM -> systemLocale()
        Lang.UK -> Locale("uk")
        Lang.EN -> Locale.ENGLISH
    }

    @Suppress("DEPRECATION")
    private fun configLocale(c: Configuration): Locale = if (Build.VERSION.SDK_INT >= 24) c.locales[0] else c.locale

    private fun localized(base: Context): Context {
        val conf = Configuration(base.resources.configuration)
        conf.setLocale(localeFor(lang(base)))
        return base.createConfigurationContext(conf)
    }

    /** For Activity.attachBaseContext. */
    fun wrap(base: Context): Context = localized(base)

    private fun c(): Context = res ?: error("L10n.init() was not called")

    fun str(@StringRes id: Int, vararg args: Any): String = if (args.isEmpty()) c().getString(id) else c().getString(id, *args)
    fun plural(@PluralsRes id: Int, n: Int): String = c().resources.getQuantityString(id, n, n)
    fun array(@ArrayRes id: Int): Array<String> = c().resources.getStringArray(id)
    /** For code that also runs in unit tests, where there are no resources. */
    fun strOr(@StringRes id: Int, fallback: String): String = res?.getString(id) ?: fallback
}

fun str(@StringRes id: Int, vararg args: Any): String = L10n.str(id, *args)
fun plural(@PluralsRes id: Int, n: Int): String = L10n.plural(id, n)
