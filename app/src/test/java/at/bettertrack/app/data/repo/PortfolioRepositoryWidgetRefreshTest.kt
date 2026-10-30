package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.CustomAssetInitialPurchase
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.db.InertDatabase
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.data.storage.PortfolioBackend
import at.bettertrack.app.widget.BtWidgetRefreshReason
import at.bettertrack.app.widget.RecordingWidgetRefreshTrigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * **Every PortfolioRepository write that moves a cash-widget figure tells the
 * widgets — and only after it succeeded.**
 *
 * Behavioural, through the real repository over a recording backend: each cash
 * path is CALLED and the trigger is asserted, then the same call is repeated
 * against a failing backend and the trigger must stay silent. The reflective
 * guard at the bottom is what makes "a new mutation cannot forget it" true: a
 * public method added to the repository fails this test until it is sorted into
 * one of the three sets below.
 *
 * This module has no Robolectric, so Room is replaced by an inert
 * [BtDatabase] whose DAOs are dynamic proxies (empty flows, no-op writes). None
 * of the paths under test read Room for their result.
 */
class PortfolioRepositoryWidgetRefreshTest {

    /** The backend: records every call, answers Ok (or Err when [failing]). */
    private class Backend(var failing: Boolean = false) {
        val calls = mutableListOf<String>()

        val proxy: PortfolioBackend = Proxy.newProxyInstance(
            PortfolioBackend::class.java.classLoader,
            arrayOf(PortfolioBackend::class.java),
        ) { _, method, _ ->
            calls += method.name
            if (failing && !method.name.startsWith("refresh")) {
                BtResult.Err(BtApiError(httpStatus = 409, code = "TEST_REFUSED"))
            } else {
                when (method.name) {
                    "createPortfolio", "createCustomAsset" -> BtResult.Ok("new-id")
                    "refreshTransactions", "loadMoreTransactions" -> BtResult.Ok(null)
                    else -> BtResult.Ok(Unit)
                }
            }
        } as PortfolioBackend
    }

    private class Rig(failing: Boolean = false) {
        val backend = Backend(failing)
        /** Backend calls already made when the trigger fired (refetch-before-refresh). */
        val seenAtTrigger = mutableListOf<List<String>>()
        val widgets = RecordingWidgetRefreshTrigger { seenAtTrigger += backend.calls.toList() }
        val repo = PortfolioRepository(
            db = InertDatabase(),
            json = Json { ignoreUnknownKeys = true },
            backend = backend.proxy,
            widgetRefresh = widgets,
        )
    }

    /** Every cash-moving write, invoked. Keys must equal [CASH_WRITES]. */
    private val cashWrites: Map<String, suspend PortfolioRepository.() -> BtResult<*>> = mapOf(
        "updateCashMovement" to { updateCashMovement("p1", "m1", JsonObject(emptyMap()), "k") },
        "deleteCashMovement" to { deleteCashMovement("p1", "m1", "k") },
        "createCashSource" to { createCashSource("p1", "Savings", "bank") },
        "updateCashSource" to { updateCashSource("p1", "s1", "Renamed", null) },
        "archiveCashSource" to { archiveCashSource("p1", "s1") },
        "restoreCashSource" to { restoreCashSource("p1", "s1") },
        "updateTransaction" to { updateTransaction("p1", "t1", UpdateTransactionRequest(note = "x"), "k") },
        "deleteTransaction" to { deleteTransaction("p1", "t1", "k") },
        "createCustomAsset" to {
            createCustomAsset(
                name = "Flat",
                category = "real_estate",
                smoothing = false,
                initial = CustomAssetInitialPurchase(quantity = 1.0, price = 100.0, executedAt = "2026-10-29"),
                portfolioId = "p1",
            )
        },
        "archivePortfolio" to { archivePortfolio("p1") },
        "restorePortfolio" to { restorePortfolio("p1") },
        "deletePortfolio" to { deletePortfolio("p1") },
    )

    @Test
    fun `every cash write tells the cash widgets exactly once, for its portfolio`() = runBlocking {
        assertEquals("the invoked set IS the declared set", CASH_WRITES, cashWrites.keys)
        cashWrites.forEach { (name, call) ->
            val rig = Rig()
            val r = rig.repo.call()

            assertTrue("$name should succeed, was $r", r is BtResult.Ok)
            assertEquals("$name → one cash refresh", 1, rig.widgets.cash.size)
            val c = rig.widgets.cash.single()
            assertEquals(name, "p1", c.portfolioId)
            assertEquals(name, BtWidgetRefreshReason.REPOSITORY_WRITE, c.reason)
            assertTrue("$name must not also claim an investment change", rig.widgets.investments.isEmpty())
        }
    }

    @Test
    fun `writes that refetch the scope tell the widgets only AFTER the refetch, so the fast path reads fresh rows`() =
        runBlocking {
            val refetching = listOf(
                "updateCashMovement", "deleteCashMovement", "updateTransaction", "deleteTransaction", "createCustomAsset",
            )
            refetching.forEach { name ->
                val rig = Rig()
                rig.repo.(cashWrites.getValue(name))()
                val seen = rig.seenAtTrigger.single()
                assertTrue("$name: cash refetched before the trigger, saw $seen", "refreshCash" in seen)
                assertTrue("$name: totals refetched before the trigger, saw $seen", "refreshPortfolioDetail" in seen)
            }
        }

    @Test
    fun `a refused write tells nobody`() = runBlocking {
        cashWrites.forEach { (name, call) ->
            val rig = Rig(failing = true)
            val r = rig.repo.call()

            assertTrue("$name should fail, was $r", r is BtResult.Err)
            assertTrue("$name refused → no refresh, was ${rig.widgets.calls}", rig.widgets.calls.isEmpty())
        }
    }

    @Test
    fun `investment-only writes keep the cash widgets out of it`() = runBlocking {
        val writes: Map<String, suspend PortfolioRepository.() -> BtResult<*>> = mapOf(
            "renamePortfolio" to { renamePortfolio("p1", "New name") },
            "updateCustomAsset" to { updateCustomAsset("c1", "Flat 2", null, null) },
            "deleteCustomAsset" to { deleteCustomAsset("c1") },
            "putValuePoints" to { putValuePoints("c1", listOf(ValuePointEntity("c1", "2026-10-29", 1.0))) },
            "createCustomAsset(no initial buy)" to {
                createCustomAsset("Art", "collectible", false, initial = null, portfolioId = null)
            },
        )
        writes.forEach { (name, call) ->
            val rig = Rig()
            rig.repo.call()
            assertTrue("$name must not refresh the cash widgets, was ${rig.widgets.calls}", rig.widgets.cash.isEmpty())
            assertEquals("$name → one investments call", 1, rig.widgets.investments.size)
        }
    }

    @Test
    fun `every public method is sorted - a new mutation fails here until someone decides`() {
        val declared = PortfolioRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
            .map { it.name }
            .filterNot { it.startsWith("get") } // Kotlin property getters (flows)
            .toSet()
        val sorted = CASH_WRITES + INVESTMENT_WRITES + NO_WIDGET_EFFECT
        assertEquals(
            "classify these in PortfolioRepositoryWidgetRefreshTest: ${declared - sorted}; " +
                "stale entries: ${sorted - declared}",
            sorted,
            declared,
        )
    }

    private companion object {
        val CASH_WRITES = setOf(
            "updateCashMovement", "deleteCashMovement",
            "createCashSource", "updateCashSource", "archiveCashSource", "restoreCashSource",
            "updateTransaction", "deleteTransaction",
            "createCustomAsset",
            "archivePortfolio", "restorePortfolio", "deletePortfolio",
        )

        val INVESTMENT_WRITES = setOf(
            "renamePortfolio", "updateCustomAsset", "deleteCustomAsset", "putValuePoints",
        )

        /**
         * Reads, refreshes (they fill Room; they change nothing on the server),
         * local selection/preference writes, and the drain hook (the ENGINE
         * tells the widgets about drained ops — see SyncEngineTest).
         */
        val NO_WIDGET_EFFECT = setOf(
            "holdings", "transactions", "transactionsForAsset", "cashMovements", "history",
            "selectPortfolio", "selectedPortfolioIdNow", "portfoliosNow", "portfolioKindsNow",
            "setPortfolioKind", "migrateLocalKinds", "defaultSelection",
            "cashCouplingDefault", "setCashCouplingDefault",
            "refreshPortfolios", "refreshPortfolioDetail", "refreshTransactions", "loadMoreTransactions",
            "refreshHistory", "refreshCash", "cashSources", "customAsset", "valuePoints",
            "refreshValuePoints", "refreshCustomAssets", "afterDrain", "createPortfolio",
        )
    }
}
