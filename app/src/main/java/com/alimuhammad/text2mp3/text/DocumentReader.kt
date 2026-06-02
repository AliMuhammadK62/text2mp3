package com.alimuhammad.text2mp3.text

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.alimuhammad.text2mp3.PiperException
import java.io.InputStream
import java.util.zip.ZipInputStream

object DocumentReader {

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    @Throws(PiperException::class)
    fun extract(ctx: Context, uri: Uri): String {

        val name = (displayName(ctx, uri) ?: uri.lastPathSegment ?: "").lowercase()
        val mime = ctx.contentResolver.getType(uri) ?: ""
        return try {
            when {
                name.endsWith(".pdf") || mime == "application/pdf" -> extractPdf(ctx, uri)
                name.endsWith(".epub") || mime == "application/epub+zip" -> extractEpub(ctx, uri)
                name.endsWith(".docx") ||
                    mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    extractDocx(ctx, uri)
                name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xml") ||
                    mime == "text/html" || mime == "text/xml" ->
                    HtmlText.toPlainText(readText(ctx, uri))
                name.endsWith(".md") || name.endsWith(".markdown") ->
                    stripMarkdown(readText(ctx, uri))
                else -> readText(ctx, uri)
            }
        } catch (e: PiperException) {
            throw e
        } catch (e: Exception) {
            throw PiperException("Could not read document: ${e.message}", e)
        }
    }

    private fun readText(ctx: Context, uri: Uri): String {
        val text = ctx.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw PiperException("The file is empty or could not be opened.")
        if (text.isBlank()) throw PiperException("The file contains no readable text.")
        return text
    }

    private val MD_RE = listOf(
        Regex("""^#{1,6}\s+""", RegexOption.MULTILINE) to "",
        Regex("""(\*\*|__)(.*?)\1""") to "$2",
        Regex("""(\*|_)(.*?)\1""") to "$2",
        Regex("""`{1,3}([^`]*)`{1,3}""") to "$1",
        Regex("""!\[[^\]]*]\([^)]*\)""") to "",
        Regex("""\[([^\]]+)]\([^)]*\)""") to "$1",
        Regex("""^>\s?""", RegexOption.MULTILINE) to "",
        Regex("""^[-*+]\s+""", RegexOption.MULTILINE) to ""
    )
    private fun stripMarkdown(md: String): String {
        var s = md
        for ((re, rep) in MD_RE) s = re.replace(s, rep)
        return s.trim()
    }

    private fun extractEpub(ctx: Context, uri: Uri): String {
        val chapters = sortedMapOf<String, String>()
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if (!entry.isDirectory && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) {
                        chapters[entry.name] = HtmlText.toPlainText(zip.readBytes().toString(Charsets.UTF_8))
                    }
                    entry = zip.nextEntry
                }
            }
        } ?: throw PiperException("Could not open the EPUB.")
        val text = chapters.values.filter { it.isNotBlank() }.joinToString("\n\n")
        if (text.isBlank()) throw PiperException("No readable chapters found in the EPUB.")
        return text
    }

    private fun extractDocx(ctx: Context, uri: Uri): String {
        var xml: String? = null
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        xml = zip.readBytes().toString(Charsets.UTF_8); break
                    }
                    entry = zip.nextEntry
                }
            }
        } ?: throw PiperException("Could not open the DOCX.")
        val doc = xml ?: throw PiperException("DOCX is missing word/document.xml.")

        val withBreaks = doc
            .replace(Regex("""</w:p>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<w:br\s*/>""", RegexOption.IGNORE_CASE), "\n")
        val text = HtmlText.toPlainText(withBreaks)
        if (text.isBlank()) throw PiperException("No readable text found in the DOCX.")
        return text
    }

    private fun extractPdf(ctx: Context, uri: Uri): String {

        PdfBoxInit.ensure(ctx)
        val stream: InputStream = ctx.contentResolver.openInputStream(uri)
            ?: throw PiperException("Could not open the PDF.")
        stream.use { input ->
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                if (doc.isEncrypted) throw PiperException("This PDF is encrypted/password-protected.")
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                val text = stripper.getText(doc)
                if (text.isBlank()) {
                    throw PiperException("No selectable text in this PDF (it may be a scan — try OCR).")
                }
                return text
            }
        }
    }
}

private object PdfBoxInit {
    @Volatile private var done = false
    fun ensure(ctx: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
            done = true
        }
    }
}
