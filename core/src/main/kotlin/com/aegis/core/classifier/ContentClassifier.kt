package com.aegis.core.classifier

import com.aegis.core.model.Classification
import com.aegis.core.model.ContentInput

/**
 * The shared brain (§7).
 *
 * The browser feeds it full page content, the network filter feeds it hostnames and
 * whatever unencrypted signal it has, and the rules engine turns its verdicts into
 * allow / warn / block decisions.
 *
 * Every implementation must run entirely on-device. Nothing behind this interface is
 * permitted to make a network call — that is the whole privacy promise (§2.2), and it
 * is why the interface takes content in and returns a verdict, with no callbacks.
 */
fun interface ContentClassifier {
    fun classify(input: ContentInput): Classification
}
