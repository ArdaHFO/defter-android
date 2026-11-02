package dev.defter.app.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class StrictJsonTest {
    @Test fun commentsCannotHideDeepNestingFromTheGuard() {
        val nested = "[".repeat(2000) + "0" + "]".repeat(2000)
        listOf("{\"x\":/*\"*/$nested}", "{\"x\"://\"\n$nested}", "{\"x\":#\"\n$nested}", "{'x':$nested}", "{\"x\":$nested}").forEach(::rejected)
    }
    @Test fun trailingDocumentsDuplicateKeysAndLenientSyntaxAreRejected() {
        listOf("{}{}", "{\"x\":1,\"x\":2}", "{x:1}", "{\"x\":NaN}", "{\"x\":[1,]}", "{\"x\":'text'}").forEach(::rejected)
    }
    @Test fun quotesAndCommentCharactersInsideStringsAreOrdinaryText() {
        val json = StrictJson.objectFromText("{\"x\":\"Türkçe / * # ' \\\" [ ] { }\",\"number\":-1.25e2,\"ok\":true,\"none\":null}")
        assertEquals("Türkçe / * # ' \" [ ] { }", json.getString("x"))
        assertEquals(-125.0, json.getDouble("number"), 0.0)
    }
    private fun rejected(text: String) {
        try { StrictJson.objectFromText(text); fail("Invalid JSON accepted") } catch (_: IOException) { }
    }
}
