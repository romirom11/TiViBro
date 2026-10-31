package dev.romirom.tivibro

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.annotation.ArrayRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * The app's own language, independent of the system's: «Як у системі» (default) or one of [Lang]. The activity
 * wraps its base context with [wrap]; code without a localized context of its own (the view model, the LAN page, the
 * data layer) reads strings through [str] / [plural], which follow the same choice.
 */
object L10n {
    /**
     * The supported interface languages, in the order the Language sheet lists them. Each option is shown by its own
     * [nativeName] whatever the current UI language is. To add a language: an entry here (its name is what Prefs
     * stores, so never rename one) and res/values-<tag>/strings.xml. [SYSTEM] is the only one without a tag.
     */
    enum class Lang(val tag: String?, val nativeName: String?) {
        SYSTEM(null, null),
        UK("uk", "Українська"),
        EN("en", "English"),
    }

    /** System languages without a translation of their own that read Ukrainian more easily than English. */
    private val CLOSE_TO_UK = setOf("ru", "be")

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

    /** «Як у системі»: the system's language if supported (Ukrainian for Russian, Belarusian), English otherwise. */
    private fun systemLang(): Lang {
        val sys = configLocale(Resources.getSystem().configuration).language
        val code = if (sys in CLOSE_TO_UK) "uk" else sys
        return Lang.entries.firstOrNull { it.tag == code } ?: Lang.EN
    }

    fun localeFor(l: Lang): Locale = Locale.forLanguageTag((if (l == Lang.SYSTEM) systemLang() else l).tag!!)

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
