package at.bettertrack.app.data.announcements

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The typed tree (contract §3.2) and its four normative tolerance rules. */
class BodyDocTest {

    private fun parse(raw: String) = parseBodyDoc(Json.parseToJsonElement(raw))

    @Test
    fun `every node type of the grammar maps`() {
        val doc = parse(
            """{"v":1,"blocks":[
              {"t":"h","level":2,"c":[{"t":"text","v":"Head"}]},
              {"t":"h","level":3,"c":[{"t":"text","v":"Sub"}]},
              {"t":"p","c":[{"t":"text","v":"a"},{"t":"br"},{"t":"strong","c":[{"t":"em","c":[{"t":"text","v":"b"}]}]},{"t":"code","v":"x_y"},
                            {"t":"link","href":"https://bettertrack.at/terms","c":[{"t":"text","v":"terms"}]}]},
              {"t":"ul","items":[[{"t":"text","v":"one"}],[{"t":"text","v":"two"}]]},
              {"t":"ol","start":3,"items":[[{"t":"text","v":"three"}]]}
            ]}""",
        )!!
        assertEquals(5, doc.blocks.size)
        assertEquals(BodyDoc.Block.Heading(2, listOf(BodyDoc.Inline.Text("Head"))), doc.blocks[0])
        assertEquals(3, (doc.blocks[1] as BodyDoc.Block.Heading).level)
        val p = doc.blocks[2] as BodyDoc.Block.Paragraph
        assertEquals(BodyDoc.Inline.Break, p.content[1])
        assertEquals(BodyDoc.Inline.Strong(listOf(BodyDoc.Inline.Emphasis(listOf(BodyDoc.Inline.Text("b"))))), p.content[2])
        assertEquals(BodyDoc.Inline.Code("x_y"), p.content[3])
        val link = p.content[4] as BodyDoc.Inline.Link
        assertEquals(LinkTarget.External("https://bettertrack.at/terms"), link.target)
        assertEquals(2, (doc.blocks[3] as BodyDoc.Block.Bullets).items.size)
        assertEquals(3, (doc.blocks[4] as BodyDoc.Block.Numbered).start)
        assertEquals("Head\n\nSub\n\na\nbx_yterms\n\n- one\n- two\n\n3. three", doc.plainText())
    }

    @Test
    fun `rule 3 - an unknown block is skipped`() {
        val doc = parse("""{"v":1,"blocks":[{"t":"table","rows":[]},{"t":"p","c":[{"t":"text","v":"kept"}]}]}""")!!
        assertEquals(listOf(BodyDoc.Block.Paragraph(listOf(BodyDoc.Inline.Text("kept")))), doc.blocks)
    }

    @Test
    fun `rule 3 - an unknown inline renders its children, else its value, else nothing`() {
        val doc = parse(
            """{"v":1,"blocks":[{"t":"p","c":[
                {"t":"mark","c":[{"t":"text","v":"kids"}]},
                {"t":"emoji","v":"value"},
                {"t":"spacer"}
            ]}]}""",
        )!!
        assertEquals(
            listOf(BodyDoc.Inline.Text("kids"), BodyDoc.Inline.Text("value")),
            (doc.blocks.single() as BodyDoc.Block.Paragraph).content,
        )
    }

    @Test
    fun `rule 4 - a newer version is ignored so the plain body renders`() {
        assertNull(parse("""{"v":2,"blocks":[{"t":"p","c":[{"t":"text","v":"x"}]}]}"""))
        // …and a missing or malformed tree is equally "absent".
        assertNull(parseBodyDoc(null))
        assertNull(parse("""{"v":1}"""))
        assertNull(parse("""[1,2]"""))
    }

    @Test
    fun `rule 2 - a link to anything but the three shapes renders as its text`() {
        val doc = parse("""{"v":1,"blocks":[{"t":"p","c":[{"t":"link","href":"javascript:alert(1)","c":[{"t":"text","v":"click"}]}]}]}""")!!
        val link = (doc.blocks.single() as BodyDoc.Block.Paragraph).content.single() as BodyDoc.Inline.Link
        assertNull(link.target)
        assertEquals("click", doc.plainText())
    }

    @Test
    fun `href classification`() {
        assertEquals(LinkTarget.External("https://bettertrack.at/x?y=1"), classifyHref("https://bettertrack.at/x?y=1"))
        assertEquals(LinkTarget.Mail("mailto:support@bettertrack.at"), classifyHref("mailto:support@bettertrack.at"))
        assertEquals(LinkTarget.AppPath("/control/account"), classifyHref("/control/account"))
        listOf(
            "http://bettertrack.at",
            "javascript:alert(1)",
            "data:text/html,x",
            "//evil.example",
            "https://user:pw@bettertrack.at",
            "https://",
            "mailto:a@b.at,c@d.at",
            "/path with space",
            "ftp://x",
            "",
            null,
        ).forEach { assertNull("must refuse: $it", classifyHref(it)) }
    }

    @Test
    fun `a plain body becomes paragraphs with hard breaks`() {
        val doc = BodyDoc.fromPlain("one\r\ntwo\n\n\n three \n\n")
        assertEquals(2, doc.blocks.size)
        assertEquals(
            BodyDoc.Block.Paragraph(listOf(BodyDoc.Inline.Text("one"), BodyDoc.Inline.Break, BodyDoc.Inline.Text("two"))),
            doc.blocks[0],
        )
        assertTrue(BodyDoc.fromPlain("").blocks.isEmpty())
    }

    @Test
    fun `absurd nesting is flattened rather than recursed`() {
        var inner = """{"t":"text","v":"deep"}"""
        repeat(40) { inner = """{"t":"strong","c":[$inner]}""" }
        val doc = parse("""{"v":1,"blocks":[{"t":"p","c":[$inner]}]}""")!!
        assertEquals("deep", doc.plainText())
    }
}
