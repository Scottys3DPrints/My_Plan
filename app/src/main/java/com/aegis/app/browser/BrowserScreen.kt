package com.aegis.app.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aegis.app.engine.AegisEngine
import com.aegis.core.model.ContentInput
import com.aegis.core.model.RouteContext
import com.aegis.core.rules.Decision
import com.aegis.core.rules.Outcome
import com.aegis.core.util.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream

/**
 * The Aegis browser (§6, build order step 1).
 *
 * This is where category filtering is at full strength, because it is the one place where
 * Aegis controls the renderer and therefore sees what the page actually contains rather
 * than only the hostname TLS leaves visible. Three checkpoints, in order of how early they
 * can stop something:
 *
 * 1. **Every subresource request** is checked against the destination rules and the
 *    hostname classifier, on a background thread, before it is fetched.
 * 2. **Every completed page** is read — text, metadata, images — classified, and judged.
 * 3. **Individual images** on an otherwise allowed page are blurred where the rules say so.
 *
 * The blocked page is replaced with an explanation rather than an error, and the back
 * stack is trimmed so that "back" does not walk straight into it again.
 */
@Composable
fun BrowserScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()

    var address by remember { mutableStateOf("") }
    var displayedUrl by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Decision?>(null) }
    var noticeEntryId by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search or enter an address") },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onGo = {
                        val target = Urls.normaliseUserInput(address, SEARCH_TEMPLATE)
                        if (target.isNotBlank()) {
                            notice = null
                            webView?.loadUrl(target)
                        }
                    },
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Go),
            )
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload")
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        notice?.let { decision ->
            InterstitialCard(
                decision = decision,
                onProceed = {
                    noticeEntryId?.let(engine::noteProceededPastWarning)
                    notice = null
                },
                onBack = {
                    notice = null
                    if (webView?.canGoBack() == true) webView?.goBack() else webView?.loadUrl(BLANK_PAGE)
                },
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { viewContext ->
                WebView(viewContext).also { view ->
                    view.hardenSettings()
                    view.webViewClient = AegisWebViewClient(
                        engine = engine,
                        onStarted = { url ->
                            loading = true
                            displayedUrl = url
                            address = url
                        },
                        onFinished = { finishedView, url ->
                            loading = false
                            displayedUrl = url
                            scope.launch {
                                inspect(engine, finishedView, url) { decision, entryId ->
                                    notice = decision
                                    noticeEntryId = entryId
                                }
                            }
                        },
                    )
                    webView = view
                    view.loadUrl(HOME_PAGE)
                }
            },
        )
    }
}

@Composable
private fun InterstitialCard(
    decision: Decision,
    onProceed: () -> Unit,
    onBack: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = decision.explanation,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (decision.evidence.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = decision.evidence.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onBack) { Text("Go back") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onProceed) { Text("Continue anyway") }
            }
        }
    }
}

/**
 * Read the finished page and decide what to do with it.
 *
 * Extraction happens on the main thread because it is a WebView call; classification and
 * image sampling move off it immediately, because sampling fetches bytes.
 */
private suspend fun inspect(
    engine: AegisEngine,
    view: WebView,
    url: String,
    onNotice: (Decision, String?) -> Unit,
) {
    if (url.isBlank() || url == BLANK_PAGE) return

    val raw = evaluate(view, PAGE_EXTRACTION_SCRIPT) ?: return
    val page = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString(
            ExtractedPage.serializer(),
            unquote(raw),
        )
    }.getOrNull() ?: return

    val evaluated = withContext(Dispatchers.IO) {
        val signals = ImageSampler.sample(page.images, referer = url)
        engine.evaluatePage(
            ContentInput(
                url = page.url.ifBlank { url },
                title = page.title,
                text = page.text,
                metaKeywords = page.metaKeywords,
                imageSignals = signals,
                route = RouteContext.DIRECT,
            ),
        )
    }

    when (evaluated.decision.outcome) {
        Outcome.BLOCK -> {
            view.loadDataWithBaseURL(
                null,
                blockedPageHtml(evaluated.decision, Urls.host(url)),
                "text/html",
                "utf-8",
                null,
            )
            // Drop the blocked page out of history so "back" does not return to it.
            view.clearHistory()
        }

        Outcome.WARN -> {
            onNotice(evaluated.decision, evaluated.logEntryId)
            if (evaluated.decision.blurRefs.isNotEmpty()) {
                view.evaluateJavascript(blurScript(evaluated.decision.blurRefs), null)
            }
        }

        Outcome.ALLOW -> {
            if (evaluated.decision.blurRefs.isNotEmpty()) {
                view.evaluateJavascript(blurScript(evaluated.decision.blurRefs), null)
            }
        }
    }
}

private suspend fun evaluate(view: WebView, script: String): String? =
    suspendCancellableCoroutine { continuation ->
        try {
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resumeWith(Result.success(result))
            }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWith(Result.success(null))
        }
    }

/** `evaluateJavascript` hands back a JSON *string literal*, so it needs unwrapping once. */
private fun unquote(raw: String): String {
    if (raw.length < 2 || !raw.startsWith("\"")) return raw
    return runCatching { Json.decodeFromString(String.serializer(), raw) }.getOrDefault(raw)
}

private class AegisWebViewClient(
    private val engine: AegisEngine,
    private val onStarted: (String) -> Unit,
    private val onFinished: (WebView, String) -> Unit,
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        onStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        onFinished(view, url)
    }

    /**
     * The earliest possible checkpoint: every request the page makes, before it is made.
     *
     * Runs on a background thread, so it can consult the engine directly. Returning a
     * non-null response cancels the fetch — this is what stops a tracker or an embedded
     * frame from a walled destination loading at all, rather than blocking the page after
     * it has already rendered.
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val host = request.url?.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host.isEmpty()) return null

        val route = if (request.isForMainFrame) RouteContext.DIRECT else RouteContext.REDIRECT
        val verdict = engine.evaluateHost(host, route)
        if (!verdict.decision.isBlocked) return null

        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked by Aegis",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.hardenSettings() {
    settings.apply {
        // Required: the classifier reads the rendered page, and most of the web does not
        // render without it.
        javaScriptEnabled = true
        domStorageEnabled = true

        // Everything below is off because nothing here needs it, and each one is a way
        // for a page to reach past the browser.
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setGeolocationEnabled(false)
        databaseEnabled = false
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = userAgentString.replace("; wv", "")
    }

    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    isVerticalScrollBarEnabled = true
}

private fun blockedPageHtml(decision: Decision, host: String): String {
    val evidence = decision.evidence.joinToString("") { "<li>${escape(it)}</li>" }
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { font-family: system-ui, sans-serif; margin: 0; padding: 40px 24px;
                 background: #f8f6f3; color: #1c1a17; line-height: 1.5; }
          @media (prefers-color-scheme: dark) { body { background: #14120f; color: #f3efe9; } }
          h1 { font-size: 22px; margin: 0 0 8px; }
          .host { color: #6b655c; font-size: 14px; margin-bottom: 20px; word-break: break-all; }
          .why { margin-top: 24px; padding: 16px; border-radius: 12px; background: rgba(127,127,127,0.12); }
          .why h2 { font-size: 13px; text-transform: uppercase; letter-spacing: .06em; margin: 0 0 8px; }
          ul { margin: 0; padding-left: 18px; font-size: 14px; }
        </style></head><body>
          <h1>${escape(decision.cause?.headline ?: "Blocked")}</h1>
          <div class="host">${escape(host)}</div>
          <div>${escape(decision.explanation)}</div>
          ${if (evidence.isBlank()) "" else "<div class=\"why\"><h2>Why</h2><ul>$evidence</ul></div>"}
        </body></html>
    """.trimIndent()
}

private fun escape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private const val HOME_PAGE = "https://duckduckgo.com/"
private const val BLANK_PAGE = "about:blank"
private const val SEARCH_TEMPLATE = "https://duckduckgo.com/?q=%s"
