package at.bettertrack.app.ui.customassets

import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.ValuePointOpPayload
import at.bettertrack.app.ui.charts.StepPoint
import at.bettertrack.app.ui.portfolio.PendingUiStatus
import at.bettertrack.app.ui.portfolio.pendingUiStatus
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Pure logic behind the Step-10 custom-asset screens (§6.4): value-point
 * merging, step-chart mapping, and pending value-point decoding. Unit-tested.
 */

/** The categories the platform accepts. */
val CUSTOM_ASSET_CATEGORIES = listOf(
    "real_estate", "vehicle", "collectible", "cash", "unlisted_stock", "other",
)

/**
 * Merge a new (date,value) into an existing point set, keyed by date — the
 * same read-merge-write the queue executor performs (idempotent replace of a
 * day's value). Sorted ascending by date.
 */
fun mergeValuePoint(
    existing: List<ValuePointEntity>,
    assetId: String,
    date: String,
    value: Double,
): List<ValuePointEntity> =
    (existing.filter { it.date != date } + ValuePointEntity(assetId, date, value))
        .sortedBy { it.date }

/** Latest recorded value (by date); null when there are no points. */
fun latestValue(points: List<ValuePointEntity>): Double? =
    points.maxByOrNull { it.date }?.value

/** Map value points to step-chart points (epoch-day x axis). */
fun toStepPoints(points: List<ValuePointEntity>): List<StepPoint> =
    points.sortedBy { it.date }.mapNotNull { p ->
        val day = try {
            LocalDate.parse(p.date).toEpochDay()
        } catch (_: Exception) {
            return@mapNotNull null
        }
        StepPoint(day, p.value)
    }

// ── Pending value-point ops (§7.4) ──────────────────────────────────────────

data class PendingValuePoint(
    val opId: Long,
    val customAssetId: String,
    val date: String,
    val value: Double,
    val status: PendingUiStatus,
    val serverError: String?,
)

/** Open queued value points for one custom asset, newest first. */
fun decodePendingValuePoints(
    ops: List<SyncOpEntity>,
    json: Json,
    customAssetId: String,
): List<PendingValuePoint> = ops
    .asSequence()
    .filter { it.opType == OpType.CUSTOM_ASSET_VALUE_POINT.wire }
    .filter { it.status != OpStatus.DONE.wire }
    .mapNotNull { op ->
        val p = try {
            json.decodeFromString(ValuePointOpPayload.serializer(), op.payloadJson)
        } catch (_: Exception) {
            return@mapNotNull null
        }
        if (p.customAssetId != customAssetId) return@mapNotNull null
        val status = OpStatus.fromWire(op.status)?.let(::pendingUiStatus) ?: return@mapNotNull null
        PendingValuePoint(op.id, p.customAssetId, p.date, p.value, status, op.serverError)
    }
    .sortedByDescending { it.opId }
    .toList()

/** Category display key → user label resolved in Compose (see screen). */
fun categoryLabelKey(category: String?): String = category ?: "other"
