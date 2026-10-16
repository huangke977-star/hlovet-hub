package xyz.hlovet.portal.prototype

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

internal fun normalizeNativeLocale(value: String?): String =
    if (value?.trim()?.lowercase(Locale.ROOT)?.let { it == "en" || it.startsWith("en-") } == true) "en-US" else "zh-CN"

internal object NativeI18n {
    private const val PREFERENCES = "native-i18n"
    private const val LOCALE_KEY = "locale"

    var locale by mutableStateOf(normalizeNativeLocale(Locale.getDefault().toLanguageTag()))
        private set

    fun load(context: Context) {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(LOCALE_KEY, null)
        locale = normalizeNativeLocale(stored ?: Locale.getDefault().toLanguageTag())
    }

    fun applyLocale(value: String?) {
        locale = normalizeNativeLocale(value ?: Locale.getDefault().toLanguageTag())
    }

    fun applyAndPersist(context: Context, value: String?) {
        val normalized = normalizeNativeLocale(value ?: Locale.getDefault().toLanguageTag())
        locale = normalized
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LOCALE_KEY, normalized)
            .apply()
    }

    fun phrase(zh: String, en: String): String = if (locale == "en-US") en else zh
}

internal fun nativePhrase(zh: String, en: String): String = NativeI18n.phrase(zh, en)

internal fun nativePhraseForLocale(locale: String?, zh: String, en: String): String =
    if (normalizeNativeLocale(locale) == "en-US") en else zh
