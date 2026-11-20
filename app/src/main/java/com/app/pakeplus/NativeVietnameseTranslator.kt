package com.app.pakeplus

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * On-device translation service. Model downloads and translations are serialized
 * per WebView batch to avoid starting many competing ML Kit tasks at once.
 */
class NativeVietnameseTranslator {
    data class TextItem(val id: String, val text: String)

    private val languageIdentifier = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    private val downloadedLanguages = mutableSetOf<String>()
    private val translationCache = object : LinkedHashMap<String, String>(600, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 500
    }
    // Allow model download on mobile data as well as Wi-Fi.
    private val downloadConditions = DownloadConditions.Builder().build()
    @Volatile private var closed = false

    fun translateBatch(items: List<TextItem>, onComplete: (List<Pair<String, String>>) -> Unit) {
        if (closed || items.isEmpty()) {
            onComplete(emptyList())
            return
        }

        val results = mutableListOf<Pair<String, String>>()
        var index = 0

        fun processNext() {
            if (closed || index >= items.size) {
                onComplete(results)
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
                    if (closed) {
                        processNext()
                        return@addOnSuccessListener
                    }

                    // Very short UI labels are often returned as "und".
                    // These two supported sites primarily present English UI.
                    val resolvedTag = if (languageTag == "und" && text.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                        TranslateLanguage.ENGLISH
                    } else {
                        languageTag
                    }
                    val sourceLanguage = TranslateLanguage.fromLanguageTag(resolvedTag)
                    if (sourceLanguage == null || sourceLanguage == TranslateLanguage.VIETNAMESE) {
                        processNext()
                        return@addOnSuccessListener
                    }

                    val cacheKey = sourceLanguage + ":" + text
                    val cached = synchronized(translationCache) { translationCache[cacheKey] }
                    if (cached != null) {
                        results.add(item.id to cached)
                        processNext()
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

                    fun translateText() {
                        if (closed) {
                            processNext()
                            return
                        }
                        translator.translate(text)
                            .addOnSuccessListener { translated ->
                                if (!closed && translated.isNotBlank()) {
                                    synchronized(translationCache) { translationCache[cacheKey] = translated }
                                    results.add(item.id to translated)
                                }
                                processNext()
                            }
                            .addOnFailureListener { error ->
                                Log.w("NativeTranslation", "Text translation failed", error)
                                processNext()
                            }
                    }

                    val isModelReady = synchronized(downloadedLanguages) {
                        downloadedLanguages.contains(sourceLanguage)
                    }
                    if (isModelReady) {
                        translateText()
                    } else {
                        translator.downloadModelIfNeeded(downloadConditions)
                            .addOnSuccessListener {
                                synchronized(downloadedLanguages) {
                                    downloadedLanguages.add(sourceLanguage)
                                }
                                translateText()
                            }
                            .addOnFailureListener { error ->
                                Log.w("NativeTranslation", "Language model download failed for $sourceLanguage", error)
                                processNext()
                            }
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
        synchronized(translators) {
            translators.values.forEach { it.close() }
            translators.clear()
        }
        synchronized(downloadedLanguages) { downloadedLanguages.clear() }
        synchronized(translationCache) { translationCache.clear() }
    }
}
