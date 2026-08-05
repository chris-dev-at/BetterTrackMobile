package at.bettertrack.app.vault.server

import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.storage.AssetMarketData
import at.bettertrack.app.data.storage.VaultProjectionInputs
import at.bettertrack.app.data.storage.VaultProjector
import at.bettertrack.app.domain.CurrencyConverter
import at.bettertrack.app.domain.HoldingQuote
import at.bettertrack.app.domain.PricePoint
import at.bettertrack.app.vault.DataHomeBytes
import at.bettertrack.app.vault.DataHomeMedium
import at.bettertrack.app.vault.VaultEntityGraph
import at.bettertrack.app.vault.VaultKinds
import at.bettertrack.app.vault.decodeVaultEnvelope
import at.bettertrack.app.vault.decryptVaultDocument
import at.bettertrack.app.vault.deriveVaultKek
import at.bettertrack.app.vault.text
import at.bettertrack.app.vault.unwrapVaultKey
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * **The S5 payoff, against a vault this app did not write.**
 *
 * Every earlier vault test in this repo closes a loop the app itself produced:
 * `VaultTestEnvelopes` encrypts with our codec and our tests decrypt with the
 * same codec, so a shared misreading of the format would pass all of them. Even
 * `clientMoney.fixture.json` — genuinely the platform's — is a *fixture* the
 * platform generated for conformance, not a vault a human made.
 *
 * `paranoidServerVault.fixture.json` is neither. It is the literal response body
 * of `GET /api/v1/vault` on the dev backend's paranoid account (board tick
 * "PARANOID TEST ACCOUNT LIVE", 2026-08-05), whose ciphertext was produced by the
 * **web enable-wizard in a browser**: browser Argon2id, browser WebCrypto
 * AES-GCM, browser fflate. So this file is the first place where the whole
 * ported stack is checked against an artefact of the real product:
 *
 * ```
 * live GET /vault bytes
 *   ──ServerVaultDataHome (S5)──► version 2, taken from the ETag
 *   ──deriveVaultKek / unwrapVaultKey / decryptVaultDocument (W3)──► document
 *   ──VaultEntityGraph (W3)──► entities
 *   ──VaultProjector (W4)──► the Room rows the Compose screens read
 * ```
 *
 * ## What the numbers are checked against
 *
 * Not against a recording of what this engine produced — that would only prove
 * the engine is deterministic. Every expectation below was derived by hand from
 * the account's posted contents (deposit 25 000 → AAPL 10 @ 180.50 fee 1.50 →
 * MSFT 6 @ 390 fee 1.50 → gainful AAPL sell 4 @ 245 fee 1.50 → KESt −62.31 →
 * tagged withdrawal −750) and the derivation is written out at each assertion.
 * Where a decimal is not representable as a `Double`, both the exact IEEE-754
 * result **and** the human decimal are asserted, the latter at `1e-9` — about
 * 5e-14 relative, which is representation slack and nothing else. No assertion
 * here has a tolerance wide enough to absorb a cent.
 *
 * ## Why the passphrase is not in this repo
 *
 * The fixture carries the **KEK** — `argon2id(passphrase, salt‖params)` exactly
 * as the browser computed it — rather than the passphrase itself, so no account
 * credential is committed. That keeps the AES-GCM key-unwrap, the content
 * decryption and everything downstream fully offline and always-on. The one link
 * that genuinely needs the secret, `passphrase → KEK`, is
 * [theBrowsersArgon2idIsByteIdenticalToOurs], which runs when the operator
 * supplies `BT_PARANOID_VAULT_PASSPHRASE` from the board and is skipped
 * otherwise. It was run green on 2026-08-05.
 */
class ParanoidServerVaultE2ETest {

    private lateinit var fake: FakeVaultServer
    private lateinit var home: ServerVaultDataHome

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val fixture by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/paranoidServerVault.fixture.json")
            ?: error("vault-vectors/paranoidServerVault.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun base64(field: String): ByteArray =
        Base64.getDecoder().decode(fixture.getValue(field).jsonPrimitive.content)

    /** The exact bytes the live server returned. */
    private val envelope: ByteArray by lazy { base64("envelopeBase64") }

    @Before
    fun setUp() {
        fake = FakeVaultServer()
        fake.start()
        fake.privacyMode = "paranoid"
        fake.seed(envelope, version = 2)
        home = ServerVaultDataHome(
            client = OkHttpClient(),
            apiBase = fake.apiBase(),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() = fake.shutdown()

    // ── S5: the bytes come back off the wire intact ─────────────────────────

    @Test
    fun theAdapterReadsTheRealVaultAndTakesVersionTwoFromTheEtag() = runTest {
        val read = home.read()

        assertTrue("a live paranoid vault reads as bytes, not as absent", read is DataHomeBytes)
        read as DataHomeBytes
        assertTrue("the adapter must not touch the ciphertext", envelope.contentEquals(read.envelope))
        assertEquals("ETag: \"2\" on the live GET", 2, read.info.version)
        assertEquals("Content-Length on the live GET", 3345L, read.info.sizeBytes)
        assertEquals(DataHomeMedium.SERVER, read.info.medium)
        assertEquals(
            "no metadata header exists on the main GET, so the timestamp is the envelope's own",
            "2026-08-05T08:44:09.000Z",
            read.info.updatedAt,
        )
    }

    /**
     * The header is the AES-GCM AAD, so a client that re-serialises it before
     * authenticating rejects every producer whose JSON member order differs from
     * its own. This vault came out of a browser; that it opens at all is the
     * evidence that our AAD really is the bytes off the wire.
     */
    @Test
    fun theBrowserWrittenEnvelopeCarriesTheKdfProfileWeExpect() {
        val header = decodeVaultEnvelope(envelope).header

        assertEquals("BTVAULT1", 1, header.formatVersion)
        assertEquals("A256GCM", header.cipher)
        assertEquals("the vault version the ETag agreed with", 2, header.vaultVersion)
        assertEquals("web upgrades v1 vaults to v2 on unlock", 2, header.schemaVersion)

        val wrapped = header.wrappedKeys.single { it.keyId == header.keyId }
        assertEquals("argon2id", wrapped.kdf.alg)
        assertEquals(65536, wrapped.kdf.m)
        assertEquals(3, wrapped.kdf.t)
        assertEquals(1, wrapped.kdf.p)
    }

    // ── W3: a browser-produced vault opens with the ported stack ────────────

    @Test
    fun theW3StackDecryptsAVaultProducedByTheWebWizard() {
        val decrypted = decryptVaultDocument(envelope, vaultKey())

        assertEquals(2, decrypted.document.schemaVersion)
        assertEquals(2, decrypted.header.vaultVersion)
        // Reply #41 item 2: web's unlock path commits a retirement-proof keypair
        // back as `clientSecurity`, and a v1-only client meeting it is bounced
        // read-only. Reading it here is the proof the app is not that client.
        val clientSecurity = decrypted.document.clientSecurity
        assertNotNull("a web-touched vault carries clientSecurity", clientSecurity)
        assertNotNull(
            "and inside it the Ed25519 retirement proof",
            clientSecurity!!["retirementProof"],
        )

        val entities = decrypted.document.entities
        assertEquals("one portfolio", 1, entities.getValue(VaultKinds.PORTFOLIO).size)
        assertEquals("two buys and one sell", 3, entities.getValue(VaultKinds.TRANSACTION).size)
        assertEquals(
            "deposit, two buys, sell proceeds, KESt, withdrawal",
            6,
            entities.getValue(VaultKinds.CASH_MOVEMENT).size,
        )
        assertEquals("AAPL and MSFT", 2, entities.getValue(VaultKinds.CUSTOM_ASSET).size)
        assertTrue(
            "every kind in a real vault is one this build knows",
            entities.keys.all { it in at.bettertrack.app.vault.VaultContract.ENTITY_KINDS },
        )
    }

    /**
     * The passphrase → KEK hop, the only step whose input cannot live in the
     * repo. Supply the board's vault passphrase as `BT_PARANOID_VAULT_PASSPHRASE`
     * to run it; without it the test is skipped rather than silently weakened.
     *
     * A pass means our BouncyCastle Argon2id, on the salt and parameters this
     * browser chose, produces the same 32 bytes the browser's WASM Argon2id did.
     */
    @Test
    fun theBrowsersArgon2idIsByteIdenticalToOurs() {
        val passphrase = System.getenv(PASSPHRASE_ENV)
        Assume.assumeTrue(
            "set $PASSPHRASE_ENV (board value) to verify the Argon2id hop",
            !passphrase.isNullOrEmpty(),
        )

        val header = decodeVaultEnvelope(envelope).header
        val wrapped = header.wrappedKeys.single { it.keyId == header.keyId }

        // The stored-KEK comparison target was removed with the vendored KEK; the
        // proof is now direct: our Argon2id output must UNWRAP the browser's own
        // wrapped key (AES-GCM over keyId AAD) — a wrong KEK cannot do that.
        unwrapVaultKey(wrapped, header.keyId, deriveVaultKek(passphrase!!, wrapped.kdf))
    }

    // ── W4: the derived Room numbers ────────────────────────────────────────

    /**
     * Every cash movement in this vault is already denominated in EUR, so this
     * balance is the one headline number no FX or quote assumption can move:
     * `25000 − 1543.40 − 1966.97 + 843.95 − 62.31 − 750`.
     */
    @Test
    fun derivesTheBoardPostedCashBalance() {
        val source = project().cashSources.single()

        assertEquals("Main", source.name)
        assertTrue(source.isMain)
        assertNull("an active source carries no archive stamp", source.archivedAt)
        assertEquals("the board's 21,521.27 EUR", 21_521.27, source.balanceEur, 1e-9)
        assertEquals("and its exact IEEE-754 sum", 21_521.269999999997, source.balanceEur, 0.0)
    }

    /**
     * Fees capitalise into the average, in the asset's native currency:
     * AAPL `(10 × 180.50 + 1.50) / 10 = 180.65`, and selling 4 of 10 leaves the
     * average untouched while realising `4 × (245 − 180.65) − 1.50 = 255.90`.
     * MSFT `(6 × 390 + 1.50) / 6 = 390.25`, never sold, so nothing realised.
     */
    @Test
    fun derivesTheHoldingsTheAccountDescribes() {
        val holdings = project().holdings.associateBy { it.assetSymbol }
        assertEquals("only open positions are held rows", setOf("AAPL", "MSFT"), holdings.keys)

        val aapl = holdings.getValue("AAPL")
        assertEquals("Apple Inc.", aapl.assetName)
        assertEquals("native currency survives the projection", "USD", aapl.assetCurrency)
        assertEquals("10 bought, 4 sold", 6.0, aapl.quantity, 0.0)
        assertEquals("(10 × 180.50 + 1.50) / 10", 180.65, aapl.avgCost, 0.0)
        assertEquals("4 × (245 − 180.65) − 1.50, in USD", 255.90, aapl.realizedPnl, 1e-9)
        assertEquals(255.89999999999998, aapl.realizedPnl, 0.0)
        assertEquals("6 × 180.65 × 0.9", 975.5100000000001, aapl.costBasisEur!!, 0.0)
        assertEquals("6 × 250 × 0.9", 1350.0, aapl.marketValueEur!!, 0.0)

        val msft = holdings.getValue("MSFT")
        assertEquals("Microsoft Corporation", msft.assetName)
        assertEquals("bought once, never sold", 6.0, msft.quantity, 0.0)
        assertEquals("(6 × 390 + 1.50) / 6", 390.25, msft.avgCost, 0.0)
        assertEquals("nothing was sold, so nothing is realised", 0.0, msft.realizedPnl, 0.0)
        assertEquals("6 × 390.25 × 0.9", 2107.35, msft.costBasisEur!!, 0.0)
        assertEquals("6 × 400 × 0.9", 2160.0, msft.marketValueEur!!, 0.0)

        assertTrue(
            "both are platform assets, so neither is a user-created one",
            holdings.values.none { it.assetIsCustom },
        )
    }

    @Test
    fun derivesThePortfolioTotalsTheOverviewScreenReads() {
        val totals = project().portfolios.single().totals
            ?: throw AssertionError("the projected portfolio must carry totals")

        assertEquals("1350 + 2160", 3510.0, totals.marketValueEur, 0.0)
        assertEquals("975.51 + 2107.35", 3082.86, totals.investedEur, 0.0)
        assertEquals("the ledger balance, unchanged by the projection", 21_521.269999999997, totals.cashEur, 0.0)
        assertEquals("3510 + 21 521.27", 25_031.27, totals.totalValueEur, 1e-9)
        assertEquals(25_031.269999999997, totals.totalValueEur, 0.0)
        assertEquals("3510 − 3082.86; cash is never a gain", 427.14, totals.unrealizedPnlEur, 1e-9)
        assertEquals("(250−240)×6×0.9 + (400−380)×6×0.9", 162.0, totals.dayChangeEur, 0.0)
    }

    /**
     * The reason this account was provisioned in AT tax mode: KESt is a **cash
     * movement**, not a transaction fee and not a deduction folded into the
     * proceeds. It must reach the ledger as its own negative row, linked to the
     * sell that caused it, or the balance is right by luck and the tax year is
     * unreportable.
     */
    @Test
    fun keepsTheKestWithholdingAsItsOwnLedgerRow() {
        val projected = project()
        val movements = projected.cashMovements.associateBy { it.kind }
        assertEquals(
            "the six posted movements, no synthesised extras",
            setOf("deposit", "buy", "sell_proceeds", "tax_withholding", "withdrawal"),
            movements.keys,
        )
        assertEquals("two buys share one kind", 6, projected.cashMovements.size)

        val kest = movements.getValue("tax_withholding")
        assertEquals("KESt leaves the account", -62.31, kest.amountEur, 0.0)
        assertNotNull("and points back at the sell that triggered it", kest.transactionId)
        assertEquals(
            "the same sell the proceeds came from",
            movements.getValue("sell_proceeds").transactionId,
            kest.transactionId,
        )

        val withdrawal = movements.getValue("withdrawal")
        assertEquals(-750.0, withdrawal.amountEur, 0.0)
        assertNull("a plain withdrawal has no parent transaction", withdrawal.transactionId)
    }

    @Test
    fun carriesTheLedgerIdentityIntoTheTransactionRows() {
        val transactions = project().transactions.sortedBy { it.executedAtMs }
        assertEquals(3, transactions.size)

        val sell = transactions.single { it.side == "sell" }
        assertEquals("AAPL", sell.assetSymbol)
        assertEquals(4.0, sell.quantity, 0.0)
        assertEquals(245.0, sell.price, 0.0)
        assertEquals(1.5, sell.fee, 0.0)
        assertEquals(
            "the ms sort key agrees with the ISO string it came from",
            java.time.OffsetDateTime.parse(sell.executedAt).toInstant().toEpochMilli(),
            sell.executedAtMs,
        )
        assertTrue("the buys precede the sell", transactions.last() === sell)
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    /**
     * Unwraps the content key. The KEK is NOT vendored (coordinator ruling:
     * no derived credential material in repo files) — it is re-derived from the
     * board-public passphrase supplied as [PASSPHRASE_ENV]; without it every
     * decrypt-dependent test in this class is SKIPPED, never silently weakened.
     */
    private fun vaultKey(): ByteArray {
        val passphrase = System.getenv(PASSPHRASE_ENV)
        Assume.assumeTrue(
            "set $PASSPHRASE_ENV (board value) to run the live-vault decrypt chain",
            !passphrase.isNullOrEmpty(),
        )
        val header = decodeVaultEnvelope(envelope).header
        val wrapped = header.wrappedKeys.single { it.keyId == header.keyId }
        return unwrapVaultKey(wrapped, header.keyId, deriveVaultKek(passphrase!!, wrapped.kdf))
    }

    private val graph: VaultEntityGraph by lazy {
        VaultEntityGraph(decryptVaultDocument(envelope, vaultKey()).document.entities)
    }

    private val portfolioId: String by lazy { graph.live(VaultKinds.PORTFOLIO).single().id }

    private fun assetId(symbol: String): String =
        graph.live(VaultKinds.CUSTOM_ASSET).single { it.text("symbol") == symbol }.id

    /**
     * A flat USD→EUR rate and round quotes, so every EUR expectation stays
     * hand-derivable. The engine's FX is date-aware; a constant converter keeps
     * this test about the port rather than about a rate table.
     */
    private val converter = object : CurrencyConverter {
        override suspend fun toBase(amount: Double, currency: String, date: String?, base: String?): Double =
            when (currency) {
                "EUR" -> amount
                "USD" -> amount * 0.9
                else -> error("unsupported FX: $currency")
            }
    }

    private fun project() = runBlocking {
        val inputs = VaultProjectionInputs(
            today = TODAY,
            market = mapOf(
                assetId("AAPL") to AssetMarketData(
                    prices = listOf(PricePoint("2026-08-04", 240.0), PricePoint(TODAY, 250.0)),
                    quote = HoldingQuote(price = 250.0, prevClose = 240.0),
                ),
                assetId("MSFT") to AssetMarketData(
                    prices = listOf(PricePoint("2026-08-04", 380.0), PricePoint(TODAY, 400.0)),
                    quote = HoldingQuote(price = 400.0, prevClose = 380.0),
                ),
            ),
            converter = converter,
            syncedAtMs = 1_754_300_000_000L,
        )
        VaultProjector(json).project(graph, portfolioId, inputs, listOf(HistoryRange.MAX))
    }

    private companion object {
        const val TODAY = "2026-08-05"
        const val PASSPHRASE_ENV = "BT_PARANOID_VAULT_PASSPHRASE"
    }
}
