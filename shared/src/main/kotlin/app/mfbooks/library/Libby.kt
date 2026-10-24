package app.mfbooks.library

import java.net.URLDecoder
import org.json.JSONObject

/** Libby openbook.json → file name → chapters. `nav.toc[].path` is `file.mp3#seconds`. */
fun parseOpenbookJson(text: String): Map<String, List<RawChapter>> {
    val toc = runCatching { JSONObject(text.removePrefix("﻿")).getJSONObject("nav").getJSONArray("toc") }.getOrNull()
        ?: return emptyMap()
    val out = LinkedHashMap<String, MutableList<RawChapter>>()
    for (i in 0 until toc.length()) {
        val e = toc.optJSONObject(i) ?: continue
        val path = e.optString("path").takeIf { it.isNotBlank() } ?: continue
        val file = path.substringBefore('#').substringAfterLast('/').let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
        val sec = path.substringAfter('#', "").toDoubleOrNull() ?: 0.0
        out.getOrPut(file) { ArrayList() } += RawChapter(e.optString("title").trim(), Math.round(sec * 1000))
    }
    return out.mapValues { (_, v) -> v.sortedBy { it.startMs } }
}
