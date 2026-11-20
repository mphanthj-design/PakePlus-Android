package com.app.pakeplus

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * On-device translation service used by the WebView bridge.
 * Translators are reused per source language and cached results avoid repeat work.
 */
class NativeVietnameseTranslator {
    data class TextItem(val id: String, val text: String)

    private val languageIdentifier = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    private val translationCache = object : LinkedHashMap<String, String>(600, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 500
    }
    private val downloadConditions = DownloadConditions.Builder().build()
    private var closed = false

    fun translateBatch(items: List<TextItem>, onComplete: (List<Pair<String, String>>) -> Unit) {
        if (closed || items.isEmpty()) {
            onComplete(emptyList())
            return
        }

        val results = arrayOfNulls<Pair<String, String>>(items.size)
        var remaining = items.size
        var completed = false
        val completionLock = Any()

        fun finish(index: Int, result: Pair<String, String>? = null) {
            var finalResults: List<Pair<String, String>>? = null
            synchronized(completionLock) {
                if (result != null) results[index] = result
                remaining--
                if (remaining == 0 && !completed) {
                    completed = true
                    finalResults = results.filterNotNull()
                }
            }
            finalResults?.let { onComplete(it) }
        }

        items.forEachIndexed { index, item ->
            val text = item.text.trim()
            if (text.isEmpty() || closed) {
                finish(index)
                return@forEachIndexed
            }

            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { languageTag ->
                    if (closed || languageTag == "und") {
                        finish(index)
                        return@addOnSuccessListener
                    }

                    val sourceLanguage = TranslateLanguage.fromLanguageTag(languageTag)
                    if (sourceLanguage == null || sourceLanguage == TranslateLanguage.VIETNAMESE) {
                        finish(index)
                        return@addOnSuccessListener
                    }

                    val cacheKey = sourceLanguage + ":" + text
                    val cached = synchronized(translationCache) { translationCache[cacheKey] }
                    if (cached != null) {
                        finish(index, item.id to cached)
                        return@addOnSuccessListener
                    }

                    val translator = synchronized(translators) {
                        translators.getOrPut(sourceLanguage) {
                            val options = TranslatorOptions.Builder()
                                .setSourceLanguage(sourceLanguage)
                                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                                .build()
                            Translation.getClient(options)
                        }
                    }

                    translator.downloadModelIfNeeded(downloadConditions)
                        .addOnSuccessListener {
                            translator.translate(text)
                                .addOnSuccessListener { translated ->
                                    if (!closed && translated.isNotBlank()) {
                                        synchronized(translationCache) { translationCache[cacheKey] = translated }
                                        finish(index, item.id to translated)
                                    } else {
                                        finish(index)
                                    }
                                }
                                .addOnFailureListener { error ->
                                    Log.w("NativeTranslation", "Text translation failed", error)
                                    finish(index)
                                }
                        }
                        .addOnFailureListener { error ->
                            Log.w("NativeTranslation", "Language model download failed for " + sourceLanguage, error)
                            finish(index)
                        }
                }
                .addOnFailureListener { error ->
                    Log.w("NativeTranslation", "Language identification failed", error)
                    finish(index)
                }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        languageIdentifier.close()
        synchronized(translators) {
            translators.values.forEach { it.close() }
            translators.clear()
        }
        synchronized(translationCache) { translationCache.clear() }
    }
}
