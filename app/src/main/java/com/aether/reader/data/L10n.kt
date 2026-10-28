package com.aether.reader.data

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object L10n {
    @Volatile
    private var activeLanguage: String = "ko"

    val language: String get() = activeLanguage
    val isKorean: Boolean get() = activeLanguage == "ko"

    fun init(context: Context, prefs: Prefs) {
        val lang = prefs.getEffectiveLanguage(context)
        activeLanguage = lang
        applyLocale(context, lang)
    }

    fun setLanguage(context: Context, lang: String, prefs: Prefs) {
        val validLang = if (lang.equals("en", ignoreCase = true)) "en" else "ko"
        activeLanguage = validLang
        prefs.language = validLang
        applyLocale(context, validLang)
    }

    fun applyLocale(context: Context, lang: String): Context {
        val locale = if (lang == "en") Locale.ENGLISH else Locale.KOREAN
        Locale.setDefault(locale)
        val res = context.resources
        val config = Configuration(res.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            return context
        }
    }

    /** Returns [ko] when active language is Korean, or [en] when English. */
    inline fun t(ko: String, en: String): String {
        return if (isKorean) ko else en
    }
}
