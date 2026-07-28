package com.aegis.app.browser

import kotlinx.serialization.Serializable

/**
 * What the page told us about itself.
 *
 * Extracted in the page's own context by [PAGE_EXTRACTION_SCRIPT] and handed back as
 * JSON. This is the payload that makes our own browser worth building: unlike the network
 * filter, which sees a hostname through TLS and nothing else, here we have the rendered
 * text, the metadata and the images — which is the difference between blocking a domain
 * and recognising the one explicit thread on an otherwise ordinary forum.
 */
@Serializable
data class ExtractedPage(
    val url: String = "",
    val title: String = "",
    val text: String = "",
    val metaKeywords: List<String> = emptyList(),
    val images: List<ExtractedImage> = emptyList(),
)

@Serializable
data class ExtractedImage(
    val src: String = "",
    val alt: String = "",
    /** Fraction of the viewport this image occupies, 0..1. */
    val prominence: Float = 0f,
)

/**
 * Runs inside the page. Kept small, defensive, and free of anything that could be
 * observed by the page as a fingerprint of the user having a blocker installed.
 *
 * Text is capped: a classifier reading the first 20k characters reaches the same verdict
 * as one reading a megabyte, and the cap is what keeps this off the main thread's budget.
 */
const val PAGE_EXTRACTION_SCRIPT = """
(function () {
  try {
    var MAX_TEXT = 20000;
    var MAX_IMAGES = 24;

    var text = '';
    var body = document.body;
    if (body) {
      text = (body.innerText || body.textContent || '').replace(/\s+/g, ' ').trim();
      if (text.length > MAX_TEXT) text = text.substring(0, MAX_TEXT);
    }

    var keywords = [];
    var metas = document.getElementsByTagName('meta');
    for (var i = 0; i < metas.length && keywords.length < 40; i++) {
      var name = (metas[i].getAttribute('name') || metas[i].getAttribute('property') || '').toLowerCase();
      if (name === 'keywords' || name === 'description' || name === 'og:title' ||
          name === 'og:description' || name === 'og:site_name' || name === 'rating') {
        var content = metas[i].getAttribute('content');
        if (content) keywords.push(content.substring(0, 300));
      }
    }

    var viewport = Math.max(1, window.innerWidth * window.innerHeight);
    var images = [];
    var nodes = document.getElementsByTagName('img');
    for (var j = 0; j < nodes.length && images.length < MAX_IMAGES; j++) {
      var node = nodes[j];
      var src = node.currentSrc || node.src || '';
      if (!src || src.indexOf('data:') === 0) continue;
      var width = node.clientWidth || node.naturalWidth || 0;
      var height = node.clientHeight || node.naturalHeight || 0;
      var area = (width * height) / viewport;
      if (area < 0.01) continue;
      images.push({
        src: src,
        alt: (node.alt || node.title || '').substring(0, 200),
        prominence: Math.min(1, area)
      });
    }

    return JSON.stringify({
      url: document.location.href,
      title: document.title || '',
      text: text,
      metaKeywords: keywords,
      images: images
    });
  } catch (error) {
    return JSON.stringify({ url: document.location.href, title: '', text: '', metaKeywords: [], images: [] });
  }
})();
"""

/**
 * Injected when a page is allowed but carries images the rules say to blur (§3.1).
 *
 * Blur rather than remove: the layout survives, the page stays usable, and a false
 * positive costs the user a tap instead of a broken page. Tapping a blurred image clears
 * it — a filter that cannot be overridden on a single image is one that gets switched off
 * entirely the first time it is wrong.
 */
fun blurScript(sources: List<String>): String {
    val list = sources.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
    return """
(function () {
  try {
    var targets = [$list];
    if (!targets.length) return;
    var nodes = document.getElementsByTagName('img');
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      var src = node.currentSrc || node.src || '';
      if (targets.indexOf(src) === -1) continue;
      if (node.getAttribute('data-aegis-blurred')) continue;
      node.setAttribute('data-aegis-blurred', '1');
      node.style.filter = 'blur(28px)';
      node.style.cursor = 'pointer';
      node.addEventListener('click', function (event) {
        event.stopPropagation();
        event.preventDefault();
        this.style.filter = '';
        this.removeAttribute('data-aegis-blurred');
      }, { once: true, capture: true });
    }
  } catch (error) {}
})();
"""
}
