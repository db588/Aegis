package uk.co.logicscience.aegis.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Covers BlocklistManager.parseBlocklistContent only. It never touches the
 * database, so a stub Context (just enough to satisfy AppDatabase.getDatabase's
 * eager `context.applicationContext` read at construction time) is sufficient —
 * no Robolectric or a real SQLite connection required.
 */
class BlocklistManagerTest {

    private lateinit var manager: BlocklistManager

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        manager = BlocklistManager(context)
    }

    private fun parse(content: String): Set<String> = runBlocking {
        manager.parseBlocklistContent(content)
    }

    @Test
    fun `hosts format lines with 0_0_0_0 extract the domain`() {
        val result = parse("0.0.0.0 ads.example.com\n0.0.0.0 tracker.example.com")
        assertEquals(setOf("ads.example.com", "tracker.example.com"), result)
    }

    @Test
    fun `hosts format lines with 127_0_0_1 extract the domain`() {
        val result = parse("127.0.0.1 ads.example.com")
        assertEquals(setOf("ads.example.com"), result)
    }

    @Test
    fun `bare domain lines are kept as-is`() {
        val result = parse("example.com\nsub.example.org")
        assertEquals(setOf("example.com", "sub.example.org"), result)
    }

    @Test
    fun `comment lines starting with hash or bang are ignored`() {
        val result = parse(
            """
            # this is a comment
            ! so is this
            example.com
            """.trimIndent()
        )
        assertEquals(setOf("example.com"), result)
    }

    @Test
    fun `blank lines are ignored`() {
        val result = parse("example.com\n\n\nsub.example.org")
        assertEquals(setOf("example.com", "sub.example.org"), result)
    }

    @Test
    fun `malformed lines are dropped`() {
        val result = parse(
            """
            not a domain
            -leadingdash.com
            trailingdash-.com
            0.0.0.0
            example.com
            """.trimIndent()
        )
        assertEquals(setOf("example.com"), result)
    }

    @Test
    fun `domains are lowercased and deduplicated`() {
        val result = parse("Example.COM\nexample.com\n0.0.0.0 EXAMPLE.COM")
        assertEquals(setOf("example.com"), result)
    }
}
