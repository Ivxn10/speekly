package com.example.watchtranslator

import com.google.mlkit.nl.translate.TranslateLanguage

data class Lang(val name: String, val tag: String, val ml: String, val flag: String)

val LANGS = listOf(
    Lang("Español", "es-MX", TranslateLanguage.SPANISH, "🇲🇽"),
    Lang("English", "en-US", TranslateLanguage.ENGLISH, "🇺🇸"),
    Lang("Français", "fr-FR", TranslateLanguage.FRENCH, "🇫🇷"),
    Lang("Deutsch", "de-DE", TranslateLanguage.GERMAN, "🇩🇪"),
    Lang("Português", "pt-BR", TranslateLanguage.PORTUGUESE, "🇧🇷"),
    Lang("Italiano", "it-IT", TranslateLanguage.ITALIAN, "🇮🇹"),
    Lang("日本語", "ja-JP", TranslateLanguage.JAPANESE, "🇯🇵"),
    Lang("中文", "zh-CN", TranslateLanguage.CHINESE, "🇨🇳"),
    Lang("한국어", "ko-KR", TranslateLanguage.KOREAN, "🇰🇷"),
)
