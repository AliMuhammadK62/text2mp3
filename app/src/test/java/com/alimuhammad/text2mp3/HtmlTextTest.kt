package com.alimuhammad.text2mp3

import com.alimuhammad.text2mp3.text.HtmlText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test fun stripsTagsAndKeepsText() {
        val out = HtmlText.toPlainText("<p>Hello <b>world</b></p>")
        assertTrue(out, out.contains("Hello world"))
        assertFalse(out, out.contains("<"))
    }

    @Test fun removesScriptAndStyle() {
        val out = HtmlText.toPlainText("<style>p{color:red}</style><p>Visible</p><script>evil()</script>")
        assertTrue(out, out.contains("Visible"))
        assertFalse(out, out.contains("evil"))
        assertFalse(out, out.contains("color"))
    }

    @Test fun decodesEntities() {
        val out = HtmlText.toPlainText("Tom &amp; Jerry &#65;&#x42; &mdash; end")
        assertTrue(out, out.contains("Tom & Jerry AB"))
        assertTrue(out, out.contains("—"))
    }

    @Test fun blockElementsBecomeLineBreaks() {
        val out = HtmlText.toPlainText("<h1>Title</h1><p>Body</p>")
        assertTrue(out, out.contains("Title"))
        assertTrue(out, out.contains("Body"))
        assertTrue(out, out.indexOf("Title") < out.indexOf("Body"))
    }
}
