package at.bettertrack.app.ui.paranoid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S6 P0-1: the paranoid explainer promises the web app, so the gate always
 * wires `onOpenWeb`. The URL it opens is built from the EFFECTIVE web origin
 * (which a debug build can override to a dev stack), so the join has to survive
 * hand-typed origins — that is what this pins.
 */
class ParanoidGateTest {

    @Test
    fun `the bare origin becomes a single trailing slash`() {
        assertEquals("https://app.bettertrack.at/", btWebUrl("https://app.bettertrack.at"))
        assertEquals("https://app.bettertrack.at/", btWebUrl("https://app.bettertrack.at/"))
        assertEquals("https://app.bettertrack.at/", btWebUrl("  https://app.bettertrack.at/  "))
    }

    @Test
    fun `a dev-stack origin with a port is preserved`() {
        assertEquals("http://10.0.2.2:8090/", btWebUrl("http://10.0.2.2:8090"))
        assertEquals("http://localhost:3000/portfolio", btWebUrl("http://localhost:3000", "/portfolio"))
    }

    @Test
    fun `there is exactly one slash at the seam`() {
        assertEquals("https://x.at/portfolio", btWebUrl("https://x.at/", "portfolio"))
        assertEquals("https://x.at/portfolio", btWebUrl("https://x.at", "/portfolio"))
        assertEquals("https://x.at/", btWebUrl("https://x.at", ""))
    }
}
