package com.alimuhammad.text2mp3

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alimuhammad.text2mp3.text.DocumentReader
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocumentReaderTest {

    private lateinit var ctx: Context
    private lateinit var dir: File

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        dir = File(ctx.cacheDir, "doctest").apply { mkdirs() }
    }

    private fun fileUri(name: String, write: (File) -> Unit): Uri {
        val f = File(dir, name)
        write(f)
        return Uri.fromFile(f)
    }

    private fun zip(name: String, entries: Map<String, String>): Uri = fileUri(name) { f ->
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((path, content) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    @Test fun readsPlainText() {
        val uri = fileUri("note.txt") { it.writeText("Just plain text.") }
        assertTrue(DocumentReader.extract(ctx, uri).contains("Just plain text"))
    }

    @Test fun stripsMarkdown() {
        val uri = fileUri("doc.md") { it.writeText("# Heading\n\nSome **bold** and a [link](http://x).") }
        val out = DocumentReader.extract(ctx, uri)
        assertTrue(out, out.contains("Heading"))
        assertTrue(out, out.contains("Some bold"))
        assertTrue(out, out.contains("link"))
        assertTrue(out, !out.contains("**"))
    }

    @Test fun readsHtml() {
        val uri = fileUri("page.html") { it.writeText("<html><body><h1>Title</h1><p>Body text.</p></body></html>") }
        val out = DocumentReader.extract(ctx, uri)
        assertTrue(out, out.contains("Title"))
        assertTrue(out, out.contains("Body text"))
    }

    @Test fun readsEpub() {
        val xhtml = "<html><body><p>Chapter one content.</p></body></html>"
        val uri = zip("book.epub", mapOf(
            "mimetype" to "application/epub+zip",
            "OEBPS/ch1.xhtml" to xhtml
        ))
        val out = DocumentReader.extract(ctx, uri)
        assertTrue(out, out.contains("Chapter one content"))
    }

    @Test fun readsDocx() {
        val docXml = "<w:document><w:body><w:p><w:r><w:t>Hello from DOCX.</w:t></w:r></w:p></w:body></w:document>"
        val uri = zip("doc.docx", mapOf("word/document.xml" to docXml))
        val out = DocumentReader.extract(ctx, uri)
        assertTrue(out, out.contains("Hello from DOCX"))
    }
}
