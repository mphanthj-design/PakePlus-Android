package com.app.pakeplus

import android.webkit.WebView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/** Native ML Kit integration for automatic translation of visible WebView text. */
class NativeVietnameseTranslator {
    private val languageIdentifier = LanguageIdentification.getClient()

    fun translatePage(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function () {
              var nodes = [];
              var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
                acceptNode: function(node) {
                  var text = (node.textValue || node.nodeValue || '').trim();
                  if (!text) return NodeFilter.FILTER_REJECT;
                  var parent = node.parentElement;
                  if (!parent) return NodeFilter.FILTER_REJECT;
                  var tag = parent.tagName ? parent.tagName.toLowerCase() : '';
                  if (['script','style','textarea','option','code','pre'].includes(tag)) return NodeFilter.FILTER_REJECT;
                  if (parent.closest('[aria-hidden="true"], [contenteditable="true"]')) return NodeFilter.FILTER_REJECT;
                  return NodeFilter.FILTER_ACCEPT;
                }
              });
              var node;
              while ((node = walker.nextNode())) nodes.push(node.nodeValue);
              window.JsBridgeNativeTranslationRequested(JSON.stringify(nodes));
            })();
            """.trimIndent(),
            null
        )
    }

    fun translateTexts(texts: List<String>, onResult: (String) -> Unit, onError: (Exception) -> Unit) {
        val sourceText = texts.joinToString("\n")
        if (sourceText.isBlank()) return

        languageIdentifier.identifyLanguage(sourceText)
            .addOnSuccessListener { sourceLanguage ->
                if (sourceLanguage == TranslateLanguage.VIETNAMESE || sourceLanguage == "und") {
                    onResult("[]")
                    return@addOnSuccessListener
                }

                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                    .build()

                val translator = Translation.getClient(options)
                val conditions = DownloadConditions.Builder()
                    .requireWifi()
                    .build()

                translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        translator.translate(sourceText)
                            .addOnSuccessListener { translated ->
                                onResult(translated)
                                translator.close()
                            }
                            .addOnFailureListener { error ->
                                onError(error)
                                translator.close()
                            }
                    }
                    .addOnFailureListener { error ->
                        onError(error)
                        translator.close()
                    }
            }
            .addOnFailureListener(onError)
    }

    fun close() {
        languageIdentifier.close()
    }
}
