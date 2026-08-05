package at.bettertrack.app.data.storage

import at.bettertrack.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tripwire on the direct-provider adapter staying OFF (S3/S4 plan §6 risk 6).
 *
 * The same shape as the `OAuthConfig.ALERTS_SCOPES_ENABLED` guard: a flag whose
 * value is a decision someone else has to make gets a test asserting the current
 * answer, so flipping it is a deliberate act with a failing test attached rather
 * than a one-character diff nobody reviews.
 *
 * The decision here is the owner's, and it is not an engineering one: shipping
 * direct provider quotes in a Play-distributed app is a provider-ToS and
 * Data-Safety exposure. Plan §6 risk 6 says "do not ship a direct-provider
 * adapter by default" in those words. **When the owner answers, update this test
 * along with the flag — do not delete it.**
 */
class DirectProviderFlagTest {

    @Test
    fun `the direct provider flag is off`() {
        assertFalse(
            "DIRECT_PROVIDER_PRICES must stay false until the owner decides " +
                "(licensed provider or owner-run price proxy) — S3/S4 plan §6 risk 6",
            BuildConfig.DIRECT_PROVIDER_PRICES,
        )
    }

    @Test
    fun `the adapter reports itself disabled`() {
        assertFalse(DirectProviderMarketDataSource.enabled)
    }

    @Test
    fun `constructing it while disabled fails loudly rather than returning empty quotes`() {
        // Inert AND loud: a mis-wire must break at graph-construction time, not
        // silently feed absent prices into the money path where they would be
        // indistinguishable from the designed no-live-prices state.
        val failure = runCatching { DirectProviderMarketDataSource() }.exceptionOrNull()
        assertTrue("expected construction to be refused", failure is IllegalStateException)
        assertEquals(DirectProviderMarketDataSource.DISABLED_MESSAGE, failure?.message)
    }

    @Test
    fun `the disabled message names the pending decision`() {
        // The message is what a future maintainer will read first. It has to
        // point at the reason, not just say "disabled".
        val message = DirectProviderMarketDataSource.DISABLED_MESSAGE
        assertTrue(message.contains("owner decision pending"))
        assertTrue(message.contains("licensed provider"))
        assertTrue(message.contains("price proxy"))
    }
}
