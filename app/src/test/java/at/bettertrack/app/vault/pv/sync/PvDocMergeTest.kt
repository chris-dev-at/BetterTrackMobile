package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultEntity
import at.bettertrack.app.vault.pv.envelope.PvCommonDoc
import at.bettertrack.app.vault.pv.envelope.PvHeaderDoc
import at.bettertrack.app.vault.pv.envelope.PvHeaderPortfolio
import at.bettertrack.app.vault.pv.envelope.PvKeySlot
import at.bettertrack.app.vault.pv.envelope.PvPortfolioDoc
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.envelope.PvVaultDoc
import at.bettertrack.app.vault.canonicalJson
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6's conflict rule, applied per doc.
 *
 * The entity rules themselves are `VaultMerge`'s and are already pinned by that
 * file's own suite; what is proven here is the part this round added — that the
 * **split into `header` + `common` + one doc per portfolio keeps them true**.
 * Three properties carry that, and the last two are what make "a lost CAS race
 * is safe to simply retry" a fact rather than a hope:
 *
 * 1. the entity rules still decide, unchanged, inside each doc;
 * 2. merging is **commutative** — two devices that meet the same pair in
 *    opposite order reach byte-identical documents;
 * 3. merging is **idempotent** — re-merging an already-merged pair changes
 *    nothing.
 *
 * Properties 2 and 3 are checked over seeded pseudo-random document pairs
 * (fixed seeds, so a failure is reproducible from the test output alone) rather
 * than over hand-picked examples, because the interesting inputs are the ones
 * nobody thought to write down.
 */
class PvDocMergeTest {

    private val mergedAt = "2026-09-02T12:00:00.000Z"

    private fun mergeAB(
        left: PvVaultDoc,
        leftVersion: Int,
        right: PvVaultDoc,
        rightVersion: Int,
        forceDivergent: Boolean = false,
    ) = mergePvDocs(left, leftVersion, right, rightVersion, DEVICE_A, mergedAt, forceDivergent)

    // ── 1. The entity rules still decide ────────────────────────────────────

    @Test
    fun `inside a portfolio doc the higher rev wins`() {
        val id = countedUuid("11", 1)
        val mine = portfolioDoc(transactions = listOf(entity(id, rev = 2, payload = 20)))
        val theirs = portfolioDoc(transactions = listOf(entity(id, rev = 5, payload = 50)))

        val merged = mergeAB(mine, 4, theirs, 4).document as PvPortfolioDoc

        assertEquals(1, merged.entities.getValue("transaction").size)
        assertEquals(5, merged.entities.getValue("transaction").single().rev)
    }

    @Test
    fun `a tombstone loses to a concurrent edit at the same rev`() {
        val id = countedUuid("11", 2)
        val deleted = portfolioDoc(
            transactions = listOf(
                entity(id, rev = 3, editedAt = "2026-09-01T12:00:00.000Z", deletedAt = "2026-09-01T12:00:00.000Z"),
            ),
        )
        // Edited EARLIER by the clock, and it still wins: rule 2 sits above
        // `editedAt` on purpose — a delete is cheap to redo, an edit that
        // vanished is data loss the user cannot even see.
        val edited = portfolioDoc(
            transactions = listOf(entity(id, rev = 3, editedAt = "2026-09-01T09:00:00.000Z", payload = 99)),
        )

        val merged = mergeAB(deleted, 2, edited, 2).document as PvPortfolioDoc

        assertEquals(null, merged.entities.getValue("transaction").single().deletedAt)
    }

    @Test
    fun `the merged version is max of the parents plus one`() {
        val a = portfolioDoc(transactions = listOf(entity(countedUuid("11", 3), rev = 1)))
        val b = portfolioDoc(transactions = listOf(entity(countedUuid("11", 4), rev = 1)))

        val merged = mergeAB(a, 4, b, 9)

        assertTrue(merged.divergent)
        assertEquals(10, merged.docVersion)
    }

    @Test
    fun `a strictly newer doc that already contains the older one is not a fork`() {
        val id = countedUuid("11", 5)
        val old = portfolioDoc(transactions = listOf(entity(id, rev = 1)))
        val new = portfolioDoc(transactions = listOf(entity(id, rev = 2)))

        val merged = mergeAB(new, 6, old, 3)

        assertFalse("a linear successor must not mint a generation", merged.divergent)
        assertEquals(6, merged.docVersion)
    }

    @Test
    fun `common docs merge their entities and keep the retirement proof`() {
        val mine = commonDoc(customAssets = listOf(entity(countedUuid("12", 1), rev = 1)))
        val theirs = commonDoc(customAssets = listOf(entity(countedUuid("12", 2), rev = 1)))

        val merged = mergeAB(mine, 2, theirs, 2).document as PvCommonDoc

        assertEquals(2, merged.entities.getValue("customAsset").size)
        assertEquals(mine.clientSecurity, merged.clientSecurity)
    }

    // ── Fail-closed edges ───────────────────────────────────────────────────

    @Test
    fun `two doc kinds never merge into one`() {
        val failure = assertThrows(VaultCryptoError::class.java) {
            mergeAB(portfolioDoc(), 1, commonDoc(), 1)
        }
        assertTrue("${failure.message}", failure.message!!.contains("cannot merge"))
    }

    @Test
    fun `a portfolio doc will not merge with another portfolio's doc`() {
        val failure = assertThrows(VaultCryptoError::class.java) {
            mergeAB(portfolioDoc(PORTFOLIO_A), 1, portfolioDoc(PORTFOLIO_B), 1)
        }
        assertTrue("${failure.message}", failure.message!!.contains(PORTFOLIO_B))
    }

    @Test
    fun `a diverging key slot fails closed rather than picking a side`() {
        val mine = headerDoc(slots = keySlots(KEY_ID))
        val theirs = headerDoc(
            slots = listOf(PvKeySlot(KEY_ID, PvVaultContract.KEY_SLOT_SEED_V1, "ZGlmZmVyZW50")),
        )
        val failure = assertThrows(VaultCryptoError::class.java) { mergeAB(mine, 1, theirs, 2) }
        assertTrue("${failure.message}", failure.message!!.contains("diverged"))
    }

    // ── The header rule this round had to decide ────────────────────────────

    @Test
    fun `two simultaneous move-ins both survive the header merge`() {
        val mine = headerDoc(portfolios = listOf(PvHeaderPortfolio(PORTFOLIO_A, "Main")))
        val theirs = headerDoc(portfolios = listOf(PvHeaderPortfolio(PORTFOLIO_B, "Pension")))

        val merged = mergeAB(mine, 3, theirs, 3).document as PvHeaderDoc

        assertEquals(
            listOf(PORTFOLIO_A, PORTFOLIO_B).sorted(),
            merged.portfolios.map { it.id },
        )
    }

    @Test
    fun `the header keeps the earliest creation record`() {
        val later = headerDoc(createdAt = "2026-08-05T08:00:00.000Z", createdBy = DEVICE_A)
        val earlier = headerDoc(createdAt = "2026-08-01T08:00:00.000Z", createdBy = DEVICE_B)

        val merged = mergeAB(later, 4, earlier, 2).document as PvHeaderDoc

        assertEquals("2026-08-01T08:00:00.000Z", merged.created.at)
    }

    @Test
    fun `the higher-versioned header name wins, and equal versions resolve deterministically`() {
        assertEquals(
            "Newer",
            (mergeAB(headerDoc(name = "Newer"), 5, headerDoc(name = "Older"), 2).document as PvHeaderDoc).name,
        )
        // Neither side is privileged at equal versions, so the order is the
        // values' own — which is what keeps the whole function commutative.
        val tie = mergeAB(headerDoc(name = "Alpha"), 3, headerDoc(name = "Beta"), 3).document as PvHeaderDoc
        assertEquals("Beta", tie.name)
    }

    // ── 2 + 3. Commutativity and idempotence, over seeded pairs ─────────────

    @Test
    fun `portfolio doc merges are commutative and idempotent over 200 seeded pairs`() {
        repeat(200) { seed ->
            val random = Random(seed.toLong())
            val left = portfolioDoc(transactions = randomEntities(random, "31"))
            val right = portfolioDoc(transactions = randomEntities(random, "31"))
            val leftVersion = 1 + random.nextInt(6)
            val rightVersion = 1 + random.nextInt(6)
            assertMergeLaws("portfolio seed $seed", left, leftVersion, right, rightVersion)
        }
    }

    @Test
    fun `common doc merges are commutative and idempotent over 200 seeded pairs`() {
        repeat(200) { seed ->
            val random = Random(10_000L + seed)
            val left = commonDoc(customAssets = randomEntities(random, "32"))
            val right = commonDoc(customAssets = randomEntities(random, "32"))
            val leftVersion = 1 + random.nextInt(6)
            val rightVersion = 1 + random.nextInt(6)
            assertMergeLaws("common seed $seed", left, leftVersion, right, rightVersion)
        }
    }

    @Test
    fun `header doc merges are commutative and idempotent over 200 seeded pairs`() {
        repeat(200) { seed ->
            val random = Random(20_000L + seed)
            val left = randomHeader(random)
            val right = randomHeader(random)
            val leftVersion = 1 + random.nextInt(6)
            val rightVersion = 1 + random.nextInt(6)
            assertMergeLaws("header seed $seed", left, leftVersion, right, rightVersion)
        }
    }

    private fun assertMergeLaws(
        what: String,
        left: PvVaultDoc,
        leftVersion: Int,
        right: PvVaultDoc,
        rightVersion: Int,
    ) {
        val forward = mergeAB(left, leftVersion, right, rightVersion)
        val backward = mergeAB(right, rightVersion, left, leftVersion)

        assertEquals(
            "$what: merging is not commutative",
            canonicalJson(forward.document.toJson()),
            canonicalJson(backward.document.toJson()),
        )
        assertEquals("$what: merged versions disagree", forward.docVersion, backward.docVersion)

        // Re-merging the successor with either parent must change nothing: the
        // successor already dominates both, so the §6 short-circuits keep the
        // version too.
        listOf(left to leftVersion, right to rightVersion).forEach { (parent, version) ->
            val again = mergeAB(forward.document, forward.docVersion, parent, version)
            assertEquals(
                "$what: merging is not idempotent against a parent",
                canonicalJson(forward.document.toJson()),
                canonicalJson(again.document.toJson()),
            )
            assertEquals("$what: an idempotent re-merge minted a generation", forward.docVersion, again.docVersion)
            assertFalse("$what: an idempotent re-merge reported a fork", again.divergent)
        }
    }

    // ── Seeded generators ───────────────────────────────────────────────────

    private val instants = listOf(
        "2026-08-30T08:00:00.000Z",
        "2026-08-30T08:00:00.100Z",
        "2026-08-31T23:59:59.999Z",
        // Seconds omitted: legal in this contract, and the padding path in
        // `parseInstant` only runs when a fraction is missing on one side.
        "2026-09-01T10:00Z",
    )

    private fun randomEntities(random: Random, group: String): List<VaultEntity> {
        val count = random.nextInt(5)
        return (0 until count).map {
            val id = countedUuid(group, random.nextInt(4))
            entity(
                id = id,
                rev = 1 + random.nextInt(3),
                editedAt = instants[random.nextInt(instants.size)],
                editedBy = if (random.nextBoolean()) DEVICE_A else DEVICE_B,
                deletedAt = if (random.nextInt(4) == 0) instants[random.nextInt(instants.size)] else null,
                payload = random.nextInt(5),
            )
        }
    }

    private fun randomHeader(random: Random): PvHeaderDoc {
        val roster = (0 until random.nextInt(4)).map {
            PvHeaderPortfolio(
                id = countedUuid("41", random.nextInt(4)),
                name = listOf("Main", "Pension", "Kids", "Crypto")[random.nextInt(4)],
            )
        }
        return headerDoc(
            name = listOf("Household", "Family", "Alpha", "Zulu")[random.nextInt(4)],
            // Distinct ids never collide, so the fail-closed branch is exercised
            // by its own test above rather than randomly tripped here.
            portfolios = roster.distinctBy { it.id },
            createdAt = instants[random.nextInt(instants.size)],
            createdBy = if (random.nextBoolean()) DEVICE_A else DEVICE_B,
        )
    }
}
