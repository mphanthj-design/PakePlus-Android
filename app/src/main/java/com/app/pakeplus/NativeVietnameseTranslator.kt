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

        val output = mutableListOf<Pair<String, String>>()
        var index = 0

        fun processNext() {
            if (closed || index >= items.size) {
                onComplete(output)
                return
            }

            val item = items[index++]
            val text = item.text.trim()
            if (text.isEmpty()) {
                processNext()
                return
            }

            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { languageTag ->
                    if (closed || languageTag == "und") {
                        processNext()
                        return@addOnSuccessListener
                    }

                    val sourceLanguage = TranslateLanguage.fromLanguageTag(languageTag)
                    if (sourceLanguage == null || sourceLanguage == TranslateLanguage.VIETNAMESE) {
                        processNext()
                        return@addOnSuccessListener
                    }

                    val cacheKey = sourceLanguage + ":" + text
                    val cached = synchronized(translationCache) { translationCache[cacheKey] }
                    if (cached != null) {
                        output.add(item.id to cached)
                        processNext()
                        return@addOnSuccessListener
                    }

                    val translator = translators.getOrPut(sourceLanguage) {
                        val options = TranslatorOptions.Builder()
                            .setSourceLanguage(sourceLanguage)
                            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                            .build()
                        Translation.getClient(options)
                    }

                    translator.downloadModelIfNeeded(downloadConditions)
                        .addOnSuccessListener {
                            translator.translate(text)
                                .addOnSuccessListener { translated ->
                                    if (!closed && translated.isNotBlank()) {
                                        synchronized(translationCache) { translationCache[cacheKey] = translated }
                                        output.add(item.id to translated)
                                    }
                                    processNext()
                                }
                                .addOnFailureListener { error ->
                                    Log.w("NativeTranslation", "Text translation failed", error)
                                    processNext()
                                }
                        }
                        .addOnFailureListener { error ->
                            Log.w("NativeTranslation", "Language model download failed for " + sourceLanguage, error)
                            processNext()
                        }
                }
                .addOnFailureListener { error ->
                    Log.w("NativeTranslation", "Language identification failed", error)
                    processNext()
                }
        }

        processNext()
    }

    fun close() {
        if (closed) return
        closed = true
        languageIdentifier.close()
        translators.values.forEach { it.close() }
        translators.clear()
        synchronized(translationCache) { translationCache.clear() }
    }
}
