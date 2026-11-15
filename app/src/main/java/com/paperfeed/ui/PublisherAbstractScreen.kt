package com.paperfeed.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Publisher sites (ScienceDirect, Taylor & Francis, …) block scripted downloads but work in a
 * real browser engine. This screen opens the article page in a WebView and pulls the abstract
 * out of the page automatically. If a "verify you are human" check appears, the user can
 * complete it in place; if extraction fails they can select the abstract and tap "Use selection".
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublisherAbstractScreen(key: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Opening the publisher page…") }
    var loading by remember { mutableStateOf(true) }
    var pageLoads by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    val webView = remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(key) {
        val a = repo.getArticle(key)
        url = a?.link
        if (url == null) status = "This article has no link"
    }

    fun accept(text: String) {
        if (done) return
        done = true
        scope.launch {
            repo.saveAbstract(key, text)
            Toast.makeText(context, "Abstract saved", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    // Poll the page: abstracts are often injected by scripts after the load event.
    LaunchedEffect(pageLoads) {
        if (pageLoads == 0) return@LaunchedEffect
        repeat(25) {
            if (done) return@LaunchedEffect
            val wv = webView.value ?: return@LaunchedEffect
            wv.evaluateJavascript(EXTRACT_JS) { raw ->
                val text = decodeJsString(raw)
                if (text.length > 150) accept(text)
            }
            delay(1500)
        }
        if (!done) status = "Couldn't find the abstract automatically. Select it on the page, then tap “Use selection”."
    }

    DisposableEffect(Unit) {
        onDispose { webView.value?.destroy() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fetch abstract") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (loading && !done) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        Button(onClick = {
                            webView.value?.evaluateJavascript("window.getSelection().toString()") { raw ->
                                val sel = decodeJsString(raw)
                                if (sel.length > 40) accept(sel)
                                else Toast.makeText(context, "Long-press and select the abstract text first", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Use selection") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            webView.value?.evaluateJavascript(EXTRACT_JS) { raw ->
                                val text = decodeJsString(raw)
                                if (text.length > 150) accept(text)
                                else Toast.makeText(context, "No abstract found on this page yet", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Try again") }
                    }
                    // Fallback that always works: read it in Chrome, copy, come back and paste.
                    Row(Modifier.padding(top = 4.dp)) {
                        TextButton(onClick = { url?.let { openUrl(context, it) } }) { Text("Open in browser") }
                        TextButton(onClick = {
                            val clip = clipboardText(context)
                            if (clip != null && clip.length > 40) accept(clip)
                            else Toast.makeText(context, "Copy the abstract in your browser first", Toast.LENGTH_SHORT).show()
                        }) { Text("Paste copied abstract") }
                    }
                }
            }
            url?.let { link ->
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            // Some publishers (ScienceDirect) refuse pages to embedded WebViews; present as Chrome.
                            settings.userAgentString = settings.userAgentString
                                .replace("; wv)", ")")
                                .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
                            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            // WebViews announce themselves with an X-Requested-With header that bot filters block.
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    loading = true
                                    status = "Loading ${url?.substringAfter("://")?.substringBefore('/') ?: ""}…"
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                    status = "Looking for the abstract…"
                                    pageLoads++
                                }
                            }
                            loadUrl(link)
                            webView.value = this
                        }
                    },
                )
            }
        }
    }
}

private fun decodeJsString(raw: String?): String =
    if (raw.isNullOrBlank() || raw == "null") "" else runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault("")

/** Finds the abstract in common publisher layouts, then falls back to metadata tags. */
private const val EXTRACT_JS = """
(function(){
  function txt(e){ return e ? (e.innerText || e.textContent || '').trim() : ''; }
  var sels = [
    '#abstracts .abstract.author', 'div.abstract.author', '.abstract.author',
    'div.hlFld-Abstract', 'div.abstractSection', '.abstractInFull',
    'section#abstract', '#abstract', 'section.abstract', 'div.abstract',
    '.article-section__abstract', '#Abs1-content', '.c-article-section__content[id^=Abs]',
    'div[class*=abstract-content]', '[itemprop=description]', 'blockquote.abstract'
  ];
  for (var i = 0; i < sels.length; i++) {
    var els = document.querySelectorAll(sels[i]);
    for (var j = 0; j < els.length; j++) {
      var t = txt(els[j]);
      if (t.length > 150) return t;
    }
  }
  var metas = ['citation_abstract', 'dc.description', 'DC.Description', 'dcterms.abstract', 'og:description', 'description', 'twitter:description'];
  for (var k = 0; k < metas.length; k++) {
    var m = document.querySelector('meta[name="' + metas[k] + '"], meta[property="' + metas[k] + '"]');
    if (m && m.content && m.content.length > 200) return m.content;
  }
  return '';
})()
"""
