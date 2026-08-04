package at.bettertrack.app.sync

import at.bettertrack.app.data.storage.BackendTag
import at.bettertrack.app.vault.FakeVaultDao
import at.bettertrack.app.vault.VaultKinds
import at.bettertrack.app.vault.VaultPayloads
import at.bettertrack.app.vault.VaultStore
import at.bettertrack.app.vault.decimal
import at.bettertrack.app.vault.testVaultStore
import at.bettertrack.app.vault.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VaultOpExecutor] apply semantics — the Drive-mode write path (S3/S4 plan
 * §1.2 "Semantic note", §5 W4).
 *
 * Two properties are worth more than the rest and most of this file is about
 * them:
 *
 * 1. **A domain refusal is [ExecResult.Rejected], never an exception and never a
 *    partial apply.** `Rejected` means *provably not applied*, so the existing
 *    needs-attention UI can show the engine's own sentence and the queue keeps
 *    draining. A thrown refusal would kill the drain and block every op behind
 *    it; a partial apply would leave the vault describing a trade the engine had
 *    just declared impossible.
 * 2. **Nothing is written when an op is refused** — including `vaultVersion`,
 *    which is the CAS token every medium compares against.
 */
class VaultOpExecutorTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val portfolioId = "018f0000-0000-7000-8000-000000000102"
    private val sourceId = "018f0000-0000-7000-8000-000000000103"
    private val assetId = "018f0000-0000-7000-8000-000000000104"

    private var appliedVersions = mutableListOf<Int>()

    private fun executor(
        store: VaultStore,
        rate: Double? = 1.0,
    ) = VaultOpExecutor(
        store = store,
        json = json,
        toEur = { amount, currency, _ ->
            when {
                currency == "EUR" -> amount
                rate == null -> null
                else -> amount * rate
            }
        },
        onApplied = { version -> appliedVersions += version },
    )

    /** A vault holding one portfolio, one cash source and (optionally) some cash. */
    private suspend fun seededStore(depositEur: Double? = null): VaultStore {
        val store = testVaultStore(FakeVaultDao())
        store.mutate { graph, context ->
            graph.create(
                VaultKinds.PORTFOLIO,
                portfolioId,
                VaultPayloads.portfolio(userId = null, name = "Vault"),
                context.now,
                context.deviceId,
            )
            graph.create(
                VaultKinds.CASH_SOURCE,
                sourceId,
                VaultPayloads.cashSource(portfolioId, "Main", "cash", isMain = true, createdAt = context.now),
                context.now,
                context.deviceId,
            )
            graph.create(
                VaultKinds.CUSTOM_ASSET,
                assetId,
                VaultPayloads.customAsset(null, "stock", "EURA", "Euro Asset", "EUR"),
                context.now,
                context.deviceId,
            )
            if (depositEur != null) {
                graph.create(
                    VaultKinds.CASH_MOVEMENT,
                    "018f0000-0000-7000-8000-0000000000d1",
                    VaultPayloads.cashMovement(
                        portfolioId = portfolioId,
                        sourceId = sourceId,
                        kind = "deposit",
                        amountEur = depositEur,
                        executedAt = "2026-07-01T08:00:00.000Z",
                        createdAt = "2026-07-01T08:00:00.000Z",
                    ),
                    context.now,
                    context.deviceId,
                )
            }
        }
        return store
    }

    private fun op(
        type: OpType,
        payload: String,
        portfolio: String? = portfolioId,
    ) = SyncOp(
        id = 1,
        clientId = "018f0000-0000-7000-8000-0000000000ff",
        type = type,
        portfolioId = portfolio,
        payloadJson = payload,
        status = OpStatus.PENDING,
        attemptCount = 0,
        nextAttemptAtMs = 0,
        serverError = null,
        serverResultJson = null,
        accountKey = "drive:test",
        createdAtMs = 0,
        updatedAtMs = 0,
        backendTag = BackendTag.VAULT,
    )

    // ── Happy paths ─────────────────────────────────────────────────────────

    @Test
    fun appliesABuyToTheVaultAndReturnsSuccessSynchronously() = runBlocking {
        val store = seededStore()
        val versionBefore = store.vaultVersion()
        val result = executor(store).execute(
            op(
                OpType.TX_BUY,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(assetId, "buy", 10.0, 100.0, 5.0, "2026-07-20T10:00:00.000Z"),
                ),
            )
        )

        val success = result as? ExecResult.Success ?: throw AssertionError("got $result")
        assertNotNull("the created id is reported like the server's would be", success.serverResultJson)
        assertTrue(success.serverResultJson!!.contains("transactionIds"))

        val graph = store.snapshot().graph
        val transaction = graph.live(VaultKinds.TRANSACTION).single()
        assertEquals("buy", transaction.text("side"))
        // Money is written as a decimal STRING, spelled the way the platform does.
        assertEquals("10", transaction.data["quantity"].toString().trim('"'))
        assertEquals(10.0, transaction.decimal("quantity")!!, 0.0)
        assertEquals(100.0, transaction.decimal("price")!!, 0.0)
        assertEquals("no cash leg without payFromCash", 0, graph.live(VaultKinds.CASH_MOVEMENT).size)
        // The CAS token advances by exactly one per applied op, and the applied
        // version is handed to the projection/push hook so it can key its cache.
        assertEquals(listOf(versionBefore + 1), appliedVersions)
        assertEquals(versionBefore + 1, store.vaultVersion())
    }

    @Test
    fun writesTheLinkedCashLegForAPayFromCashBuy() = runBlocking {
        val store = seededStore(depositEur = 2_000.0)
        val result = executor(store).execute(
            op(
                OpType.TX_BUY,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(
                        assetId, "buy", 10.0, 100.0, 5.0, "2026-07-20T10:00:00.000Z",
                        payFromCash = true,
                    ),
                ),
            )
        )
        assertTrue(result is ExecResult.Success)

        val graph = store.snapshot().graph
        val movement = graph.live(VaultKinds.CASH_MOVEMENT).single { it.text("kind") == "buy" }
        assertEquals("cost + fee leaves cash, as a negative", -1005.0, movement.decimal("amountEur")!!, 0.0)
        assertEquals(
            "the cash leg points back at its trade",
            graph.live(VaultKinds.TRANSACTION).single().id,
            movement.text("transactionId"),
        )
    }

    @Test
    fun appliesADepositAndAWithdrawalToTheRightSource() = runBlocking {
        val store = seededStore()
        val executor = executor(store)
        executor.execute(
            op(OpType.CASH_DEPOSIT, json.encodeToString(CashOpPayload.serializer(), CashOpPayload(500.0))),
        )
        executor.execute(
            op(OpType.CASH_WITHDRAW, json.encodeToString(CashOpPayload.serializer(), CashOpPayload(200.0))),
        )

        val movements = store.snapshot().graph.live(VaultKinds.CASH_MOVEMENT)
        assertEquals(2, movements.size)
        assertEquals(500.0, movements.first { it.text("kind") == "deposit" }.decimal("amountEur")!!, 0.0)
        assertEquals(
            "an outflow is stored NEGATIVE, per CASH_MOVEMENT_SIGN",
            -200.0,
            movements.first { it.text("kind") == "withdrawal" }.decimal("amountEur")!!,
            0.0,
        )
        assertTrue("every movement lands on the main source", movements.all { it.text("sourceId") == sourceId })
    }

    @Test
    fun writesBothLegsOfATransferWithASharedTransferId() = runBlocking {
        val store = seededStore(depositEur = 1_000.0)
        val secondSource = "018f0000-0000-7000-8000-0000000000b2"
        store.mutate { graph, context ->
            graph.create(
                VaultKinds.CASH_SOURCE,
                secondSource,
                VaultPayloads.cashSource(portfolioId, "Savings", "bank", isMain = false, createdAt = context.now),
                context.now,
                context.deviceId,
            )
        }

        val result = executor(store).execute(
            op(
                OpType.CASH_TRANSFER,
                json.encodeToString(
                    CashTransferOpPayload.serializer(),
                    CashTransferOpPayload(sourceId, secondSource, 300.0, "2026-07-21T10:00:00.000Z"),
                ),
            )
        )
        assertTrue(result is ExecResult.Success)

        val legs = store.snapshot().graph.live(VaultKinds.CASH_MOVEMENT)
            .filter { it.text("transferId") != null }
        assertEquals(2, legs.size)
        assertEquals("both legs share one transfer id", 1, legs.mapNotNull { it.text("transferId") }.distinct().size)
        assertEquals(
            "double entry: the pair sums to exactly zero",
            0.0,
            legs.sumOf { it.decimal("amountEur")!! },
            0.0,
        )
    }

    /**
     * The value-point op is merged BY DATE, exactly like the server's
     * full-replace PUT — which is what makes replaying it after a merge safe.
     */
    @Test
    fun mergesValuePointsByDateInsteadOfStackingThem() = runBlocking {
        val store = seededStore()
        val executor = executor(store)
        val payload = { value: Double ->
            json.encodeToString(ValuePointOpPayload.serializer(), ValuePointOpPayload(assetId, "2026-07-20", value))
        }

        executor.execute(op(OpType.CUSTOM_ASSET_VALUE_POINT, payload(100.0)))
        executor.execute(op(OpType.CUSTOM_ASSET_VALUE_POINT, payload(120.0)))

        val points = store.snapshot().graph.live(VaultKinds.CUSTOM_ASSET_VALUE)
        assertEquals("one point per date", 1, points.size)
        assertEquals(120.0, points.single().decimal("value")!!, 0.0)
    }

    /**
     * An offline buy of an asset the vault has never seen. The queue's
     * display-only identity snapshot (§7.4) is exactly the material needed to
     * mint the asset row — which is what those fields exist for.
     */
    @Test
    fun mintsTheAssetIdentityFromTheQueuedSnapshotWhenItIsUnknown() = runBlocking {
        val store = seededStore()
        val unknownAsset = "018f0000-0000-7000-8000-0000000009aa"
        executor(store).execute(
            op(
                OpType.TX_BUY,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(
                        unknownAsset, "buy", 1.0, 10.0, 0.0, "2026-07-20T10:00:00.000Z",
                        assetSymbol = "NEW", assetName = "Newly met", assetCurrency = "USD",
                    ),
                ),
            )
        )

        val asset = store.snapshot().graph.find(VaultKinds.CUSTOM_ASSET, unknownAsset)
            ?: throw AssertionError("the asset identity should have been minted")
        assertEquals("NEW", asset.text("symbol"))
        assertEquals("USD", asset.text("currency"))
    }

    // ── Domain refusals → Rejected ──────────────────────────────────────────

    @Test
    fun mapsAnOversellToRejectedAndWritesNothing() = runBlocking {
        val store = seededStore()
        val executor = executor(store)
        executor.execute(
            op(
                OpType.TX_BUY,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(assetId, "buy", 3.0, 100.0, 0.0, "2026-07-20T10:00:00.000Z"),
                ),
            )
        )
        val versionBefore = store.vaultVersion()

        val result = executor.execute(
            op(
                OpType.TX_SELL,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(assetId, "sell", 10.0, 120.0, 0.0, "2026-07-21T10:00:00.000Z"),
                ),
            )
        )

        val rejected = result as? ExecResult.Rejected ?: throw AssertionError("got $result")
        assertTrue(
            "the engine's own sentence reaches the needs-attention UI: ${rejected.message}",
            rejected.message.contains("only 3 held"),
        )
        assertEquals("still one transaction", 1, store.snapshot().graph.live(VaultKinds.TRANSACTION).size)
        assertEquals("the CAS token did not move", versionBefore, store.vaultVersion())
    }

    @Test
    fun allowsAnAcknowledgedUncoveredSell() = runBlocking {
        val store = seededStore()
        val result = executor(store).execute(
            op(
                OpType.TX_SELL,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(
                        assetId, "sell", 5.0, 120.0, 0.0, "2026-07-21T10:00:00.000Z",
                        allowUncovered = true,
                    ),
                ),
            )
        )
        assertTrue("the ticked acknowledgment is honoured", result is ExecResult.Success)
    }

    @Test
    fun mapsInsufficientCashToRejectedAndWritesNothing() = runBlocking {
        val store = seededStore(depositEur = 100.0)
        val versionBefore = store.vaultVersion()

        val result = executor(store).execute(
            op(OpType.CASH_WITHDRAW, json.encodeToString(CashOpPayload.serializer(), CashOpPayload(500.0))),
        )

        val rejected = result as? ExecResult.Rejected ?: throw AssertionError("got $result")
        assertTrue(
            "the shortfall is named: ${rejected.message}",
            rejected.message.contains("Insufficient cash"),
        )
        assertEquals("only the seeded deposit remains", 1, store.snapshot().graph.live(VaultKinds.CASH_MOVEMENT).size)
        assertEquals(versionBefore, store.vaultVersion())
    }

    @Test
    fun refusesAPayFromCashBuyTheSourceCannotFund() = runBlocking {
        val store = seededStore(depositEur = 100.0)
        val result = executor(store).execute(
            op(
                OpType.TX_BUY,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(
                        assetId, "buy", 10.0, 100.0, 5.0, "2026-07-20T10:00:00.000Z",
                        payFromCash = true,
                    ),
                ),
            )
        )
        assertTrue("got $result", result is ExecResult.Rejected)
        assertEquals(
            "neither the trade nor its cash leg was written",
            0,
            store.snapshot().graph.live(VaultKinds.TRANSACTION).size,
        )
    }

    /**
     * Platform #378, client-side: the trade keeps its (past) date, but when the
     * cash was short *then* and suffices *now*, the withdrawal leg is dated
     * today. Without this, entering last month's purchases in any order produces
     * refusals the user cannot act on.
     */
    @Test
    fun datesABackdatedCashLegTodayWhenTheFlagIsSet() = runBlocking {
        val store = seededStore(depositEur = 2_000.0) // deposited 2026-07-01
        val result = executor(store).execute(
            op(
                OpType.TX_BUY,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(
                        assetId, "buy", 1.0, 100.0, 0.0,
                        // BEFORE the funding deposit.
                        executedAt = "2026-06-01T10:00:00.000Z",
                        payFromCash = true,
                        settleCashAsOfToday = true,
                    ),
                ),
            )
        )
        assertTrue("got $result", result is ExecResult.Success)

        val graph = store.snapshot().graph
        assertEquals(
            "the trade keeps its real date",
            "2026-06-01T10:00:00.000Z",
            graph.live(VaultKinds.TRANSACTION).single().text("executedAt"),
        )
        assertEquals(
            "the cash leg settles today instead",
            "2026-08-04T12:00:00.000Z",
            graph.live(VaultKinds.CASH_MOVEMENT).single { it.text("kind") == "buy" }.text("executedAt"),
        )
    }

    @Test
    fun refusesABackdatedBuyWithoutTheFlag() = runBlocking {
        val store = seededStore(depositEur = 2_000.0)
        val result = executor(store).execute(
            op(
                OpType.TX_BUY,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(
                        assetId, "buy", 1.0, 100.0, 0.0,
                        executedAt = "2026-06-01T10:00:00.000Z",
                        payFromCash = true,
                    ),
                ),
            )
        )
        assertTrue("got $result", result is ExecResult.Rejected)
    }

    // ── Honest refusals rather than guessed numbers ─────────────────────────

    /**
     * Plan §1.3: with no FX rate a foreign-currency cash leg genuinely cannot be
     * expressed in EUR. The refusal is a sentence the user can act on; a guessed
     * rate would be a wrong number on the money path.
     */
    @Test
    fun refusesAForeignCurrencyCashLegWhenNoRateIsAvailable() = runBlocking {
        val store = seededStore(depositEur = 10_000.0)
        val usdAsset = "018f0000-0000-7000-8000-0000000005aa"
        store.mutate { graph, context ->
            graph.create(
                VaultKinds.CUSTOM_ASSET,
                usdAsset,
                VaultPayloads.customAsset(null, "etf", "USDB", "Dollar Basket", "USD"),
                context.now,
                context.deviceId,
            )
        }

        val result = executor(store, rate = null).execute(
            op(
                OpType.TX_BUY,
                json.encodeToString(
                    TxOpPayload.serializer(),
                    TxOpPayload(usdAsset, "buy", 5.0, 40.0, 1.0, "2026-07-20T10:00:00.000Z", payFromCash = true),
                ),
            )
        )
        val rejected = result as? ExecResult.Rejected ?: throw AssertionError("got $result")
        assertTrue("names the currency: ${rejected.message}", rejected.message.contains("USD"))
    }

    @Test
    fun rejectsAMalformedPayloadInsteadOfCrashingTheDrain() = runBlocking {
        val result = executor(seededStore()).execute(op(OpType.TX_BUY, "{not json at all"))
        assertTrue("got $result", result is ExecResult.Rejected)
    }

    @Test
    fun rejectsACashOpWithNoPortfolioScope() = runBlocking {
        val result = executor(seededStore()).execute(
            op(
                OpType.CASH_DEPOSIT,
                json.encodeToString(CashOpPayload.serializer(), CashOpPayload(50.0)),
                portfolio = null,
            ),
        )
        assertTrue("got $result", result is ExecResult.Rejected)
    }

    // ── Routing ─────────────────────────────────────────────────────────────

    /**
     * The router dispatches on the op's OWN persisted tag, so work queued for one
     * backend never lands on the other after a mode switch.
     */
    @Test
    fun theRouterSendsVaultTaggedOpsHereAndServerTaggedOpsAway() = runBlocking {
        val store = seededStore()
        var serverCalls = 0
        val router = ModeRoutingOpExecutor(
            server = object : OpExecutor {
                override suspend fun execute(op: SyncOp): ExecResult {
                    serverCalls++
                    return ExecResult.Success(null)
                }
            },
            vault = executor(store),
        )

        val deposit = json.encodeToString(CashOpPayload.serializer(), CashOpPayload(25.0))
        router.execute(op(OpType.CASH_DEPOSIT, deposit))
        assertEquals("a vault op never reaches the API executor", 0, serverCalls)
        assertEquals(1, store.snapshot().graph.live(VaultKinds.CASH_MOVEMENT).size)

        router.execute(op(OpType.CASH_DEPOSIT, deposit).copy(backendTag = BackendTag.SERVER))
        assertEquals(1, serverCalls)
        assertEquals("a server op never touches the vault", 1, store.snapshot().graph.live(VaultKinds.CASH_MOVEMENT).size)
    }

    @Test
    fun tombstonesAreRetainedSoADeleteCanPropagate() = runBlocking {
        val store = seededStore()
        executor(store).execute(
            op(OpType.CASH_DEPOSIT, json.encodeToString(CashOpPayload.serializer(), CashOpPayload(50.0))),
        )
        val movementId = store.snapshot().graph.live(VaultKinds.CASH_MOVEMENT).single().id

        store.mutate { graph, context ->
            graph.tombstone(VaultKinds.CASH_MOVEMENT, movementId, context.now, context.deviceId)
        }

        val graph = store.snapshot().graph
        assertEquals("gone from the live view", 0, graph.live(VaultKinds.CASH_MOVEMENT).size)
        val tombstone = graph.all(VaultKinds.CASH_MOVEMENT).single()
        assertNotNull("but the row survives, carrying its deletion", tombstone.deletedAt)
        assertTrue("with a bumped rev so it wins the merge", tombstone.rev > 0)
        assertNull("a live row would have no deletedAt", graph.live(VaultKinds.CASH_MOVEMENT).firstOrNull())
    }
}
