package com.example.epubreader

import android.text.Html
import org.w3c.dom.Element
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class Chapter(val title: String, val text: String)

object EpubParser {

    fun parse(file: File): List<Chapter> {
        val zip = ZipFile(file)
        return try {
            val opfPath = findOpfPath(zip)
            val opfDir = opfPath.substringBeforeLast('/', "")
            val dbf = newDocBuilderFactory()
            val root = dbf.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(opfPath)))

            // manifest: id -> href, plus nav/ncx detection
            val manifest = HashMap<String, String>()
            var ncxId: String? = null
            var navHref: String? = null
            val items = root.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val el = items.item(i) as Element
                val id = el.getAttribute("id")
                val href = el.getAttribute("href")
                manifest[id] = href
                if (el.getAttribute("media-type") == "application/x-dtbncx+xml") ncxId = id
                if (el.getAttribute("properties").split(Regex("\\s+")).contains("nav")) navHref = href
            }
            // EPUB2: <spine toc="ncx-id"> points at the NCX
            val spineEl = root.getElementsByTagName("spine").item(0) as? Element
            if (spineEl != null && spineEl.getAttribute("toc").isNotBlank()) ncxId = spineEl.getAttribute("toc")

            // build a map of resolved chapter path -> title from the TOC
            val titles = HashMap<String, String>()
            if (ncxId != null) {
                val ncxHref = manifest[ncxId]
                if (ncxHref != null) {
                    val ncxPath = resolve(opfDir, ncxHref)
                    if (zip.getEntry(ncxPath) != null) titles.putAll(parseNcxTitles(zip, ncxPath))
                }
            }
            if (navHref != null) {
                val navPath = resolve(opfDir, navHref)
                if (zip.getEntry(navPath) != null) titles.putAll(parseNavTitles(zip, navPath))
            }

            // spine: reading order
            val chapters = ArrayList<Chapter>()
            val spine = root.getElementsByTagName("itemref")
            for (i in 0 until spine.length) {
                val el = spine.item(i) as Element
                val href = manifest[el.getAttribute("idref")] ?: continue
                val entryPath = resolve(opfDir, href).substringBefore('#')
                val entry = zip.getEntry(entryPath) ?: continue
                val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                val text = htmlToText(html)
                if (text.isNotBlank()) {
                    val title = titles[entryPath] ?: htmlTitle(html) ?: "Chapter ${chapters.size + 1}"
                    chapters.add(Chapter(title, text))
                }
            }
            chapters
        } finally {
            zip.close()
        }
    }

    /** EPUB2 NCX table of contents: navPoint -> {label text, content src}. */
    private fun parseNcxTitles(zip: ZipFile, ncxPath: String): Map<String, String> {
        val map = HashMap<String, String>()
        val ncxDir = ncxPath.substringBeforeLast('/', "")
        try {
            val dbf = newDocBuilderFactory()
            val root = dbf.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(ncxPath)))
            val navPoints = root.getElementsByTagName("navPoint")
            for (i in 0 until navPoints.length) {
                val np = navPoints.item(i) as Element
                val label = np.getElementsByTagName("text").item(0)?.textContent?.trim()
                val content = np.getElementsByTagName("content").item(0) as? Element
                val src = content?.getAttribute("src")
                if (!label.isNullOrBlank() && !src.isNullOrBlank()) {
                    map[resolve(ncxDir, src).substringBefore('#')] = label
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    /** EPUB3 nav document: <nav epub:type="toc"><a href="...">Title</a>. */
    private fun parseNavTitles(zip: ZipFile, navPath: String): Map<String, String> {
        val map = HashMap<String, String>()
        val navDir = navPath.substringBeforeLast('/', "")
        try {
            val dbf = newDocBuilderFactory()
            val root = dbf.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(navPath)))
            val links = root.getElementsByTagName("a")
            for (i in 0 until links.length) {
                val a = links.item(i) as Element
                val href = a.getAttribute("href")
                val text = a.textContent?.trim()
                if (!text.isNullOrBlank() && href.isNotBlank()) {
                    map[resolve(navDir, href).substringBefore('#')] = text
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    /** Best-effort title from the chapter's own HTML: <title> or first h1/h2. */
    private fun htmlTitle(html: String): String? {
        val titleTag = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
        val heading = Regex("(?is)<h[12][^>]*>(.*?)</h[12]>").find(html)?.groupValues?.get(1)
        val raw = titleTag?.takeIf { it.isNotBlank() } ?: heading?.takeIf { it.isNotBlank() } ?: return null
        return try {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString().trim()
        } catch (_: Exception) {
            raw.trim()
        }
    }

    private fun newDocBuilderFactory(): DocumentBuilderFactory {
        val dbf = DocumentBuilderFactory.newInstance()
        try {
            // Avoid trying to fetch external DTDs (many EPUBs reference XHTML DTDs)
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false)
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Exception) {
        }
        return dbf
    }

    private fun findOpfPath(zip: ZipFile): String {
        val entry = zip.getEntry("META-INF/container.xml")
            ?: throw Exception("Not a valid EPUB (missing META-INF/container.xml)")
        val dbf = newDocBuilderFactory()
        val root = dbf.newDocumentBuilder().parse(zip.getInputStream(entry))
        val nodes = root.getElementsByTagName("rootfile")
        if (nodes.length == 0) throw Exception("Invalid EPUB container.xml")
        val el = nodes.item(0) as Element
        return el.getAttribute("full-path")
    }

    private fun resolve(dir: String, href: String): String {
        var h = href
        try {
            h = URLDecoder.decode(href, "UTF-8")
        } catch (_: Exception) {
        }
        var path = if (h.startsWith("/")) h.removePrefix("/") else if (dir.isEmpty()) h else "$dir/$h"
        val parts = ArrayList<String>()
        for (p in path.split("/")) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    private fun htmlToText(html: String): String {
        val spanned = try {
            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        } catch (_: Exception) {
            Html.fromHtml(html)
        }
        return spanned.toString()
            .replace('\u00a0', ' ')
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
