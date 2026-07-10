package at.bettertrack.app.ui.customassets

import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.ValuePointOpPayload
import at.bettertrack.app.ui.charts.BtLineInterpolation
import at.bettertrack.app.ui.portfolio.PendingUiStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Step-10 pure-logic tests (§6.4): value-point merge, chart map, pending decode. */
class CustomAssetLogicTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `merge replaces the same date and keeps sorted order`() {
        val existing = listOf(
            ValuePointEntity("a", "2026-01-01", 100.0),
            ValuePointEntity("a", "2026-03-01", 300.0),
        )
        val merged = mergeValuePoint(existing, "a", "2026-03-01", 350.0)
        assertEquals(2, merged.size)
        assertEquals(listOf("2026-01-01", "2026-03-01"), merged.map { it.date })
        assertEquals(350.0, merged.last().value, 1e-9)

        val added = mergeValuePoint(existing, "a", "2026-02-01", 200.0)
        assertEquals(listOf("2026-01-01", "2026-02-01", "2026-03-01"), added.map { it.date })
    }

    @Test
    fun `latest value is by date not insertion order`() {
        val points = listOf(
            ValuePointEntity("a", "2026-03-01", 300.0),
            ValuePointEntity("a", "2026-01-01", 100.0),
        )
        assertEquals(300.0, latestValue(points)!!, 1e-9)
        assertNull(latestValue(emptyList()))
    }

    @Test
    fun `step points map dates to epoch days ascending`() {
        val steps = toStepPoints(
            listOf(
                ValuePointEntity("a", "2026-01-02", 2.0),
                ValuePointEntity("a", "2026-01-01", 1.0),
            ),
        )
        assertEquals(2, steps.size)
        assertEquals(1.0, steps[0].value, 1e-9)
        assertEquals(steps[0].epochDay + 1, steps[1].epochDay)
    }

    @Test
    fun `smoothed asset draws a linear line, raw asset a step`() {
        assertEquals(BtLineInterpolation.Linear, chartInterpolation(true))
        assertEquals(BtLineInterpolation.Step, chartInterpolation(false))
    }

    @Test
    fun `pending value points decode for the matching asset only`() {
        fun op(id: Long, assetId: String, status: OpStatus = OpStatus.PENDING) = SyncOpEntity(
            id = id, clientId = "c$id", opType = OpType.CUSTOM_ASSET_VALUE_POINT.wire,
            portfolioId = null,
            payloadJson = json.encodeToString(
                ValuePointOpPayload.serializer(),
                ValuePointOpPayload(assetId, "2026-07-08", 500.0),
            ),
            status = status.wire, attemptCount = 0, nextAttemptAtMs = 0,
            serverError = null, serverResultJson = null, accountKey = "u",
            createdAtMs = 1, updatedAtMs = 1,
        )
        val rows = decodePendingValuePoints(
            listOf(op(1, "a"), op(2, "b"), op(3, "a", OpStatus.NEEDS_ATTENTION)),
            json,
            "a",
        )
        assertEquals(listOf(3L, 1L), rows.map { it.opId })
        assertEquals(PendingUiStatus.NEEDS_ATTENTION, rows[0].status)
        assertEquals(500.0, rows[1].value, 1e-9)
    }
}
