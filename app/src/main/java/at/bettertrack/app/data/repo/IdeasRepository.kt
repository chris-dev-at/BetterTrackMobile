package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.IDEA_ADHOC_MAX
import at.bettertrack.app.data.api.dto.IdeaDto
import at.bettertrack.app.data.api.parseApiError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Workboard **ideas** (V5, `workboard:*`) — a saved analysis: a name, an
 * optional thesis, and the backtest setup behind it.
 *
 * The app had no ideas surface at all before this, even though the workboard
 * seeds them, so this is the whole feature's data layer. It is deliberately
 * built only to what the six real routes offer — there is no status workflow,
 * no tagging and no per-idea asset metadata to invent from.
 */

// ── Domain models ───────────────────────────────────────────────────────────

/** Where an idea's positions come from. Exactly one branch is real. */
sealed interface IdeaSource {
    data class Conglomerate(val conglomerateId: String) : IdeaSource

    /** 1..[IDEA_ADHOC_MAX] positions with RELATIVE weights (normalised on read). */
    data class Adhoc(val positions: List<IdeaPosition>) : IdeaSource
}

data class IdeaPosition(val assetId: String, val weight: Double)

/** The comparison line, if any. Exactly one field is set. */
sealed interface IdeaBenchmark {
    data class Preset(val symbol: String) : IdeaBenchmark
    data class Asset(val assetId: String) : IdeaBenchmark
    data class Conglomerate(val conglomerateId: String) : IdeaBenchmark
}

data class IdeaState(
    val source: IdeaSource,
    val range: String,
    val benchmark: IdeaBenchmark?,
    val mode: String,
    val rebalance: String,
)

data class Idea(
    val id: String,
    val name: String,
    val thesis: String?,
    val state: IdeaState,
    val createdAt: String,
    val updatedAt: String,
) {
    /** Assets an ad-hoc idea references — the chips the detail screen resolves. */
    val assetIds: List<String>
        get() = (state.source as? IdeaSource.Adhoc)?.positions?.map { it.assetId }.orEmpty()
}

// ── Wire composition (pure — this is where contract fidelity lives) ─────────

/**
 * Builds the `.strict()` request bodies by hand.
 *
 * Three properties of the write contract make a hand-built body the honest
 * choice rather than a serializer trick:
 *  - each source branch permits **exactly** its own keys (`{kind,conglomerateId}`
 *    or `{kind,positions}`), so an omitted-but-emitted `"positions": null` is a
 *    400, not a tolerated null;
 *  - a benchmark is **one** of three single-key objects — naming two fails;
 *  - `state.benchmark` is *required-present and nullable*, so "no benchmark"
 *    must be an explicit `null`, which the app's global `Json`
 *    (`explicitNulls = false`) can never emit.
 *
 * Kept top-level and pure so the exact key set of every branch is asserted in
 * unit tests instead of discovered by a 400 on-device.
 */
object IdeaWire {

    fun source(source: IdeaSource): JsonObject = when (source) {
        is IdeaSource.Conglomerate -> buildJsonObject {
            put("kind", "conglomerate")
            put("conglomerateId", source.conglomerateId)
        }

        is IdeaSource.Adhoc -> buildJsonObject {
            put("kind", "adhoc")
            put(
                "positions",
                buildJsonArray {
                    source.positions.forEach { p ->
                        add(
                            buildJsonObject {
                                put("assetId", p.assetId)
                                put("weight", p.weight)
                            },
                        )
                    }
                },
            )
        }
    }

    fun benchmark(benchmark: IdeaBenchmark?): kotlinx.serialization.json.JsonElement = when (benchmark) {
        null -> JsonNull
        is IdeaBenchmark.Preset -> buildJsonObject { put("preset", benchmark.symbol) }
        is IdeaBenchmark.Asset -> buildJsonObject { put("assetId", benchmark.assetId) }
        is IdeaBenchmark.Conglomerate -> buildJsonObject { put("conglomerateId", benchmark.conglomerateId) }
    }

    fun state(state: IdeaState): JsonObject = buildJsonObject {
        put("source", source(state.source))
        put("range", state.range)
        put("benchmark", benchmark(state.benchmark))
        put("mode", state.mode)
        put("rebalance", state.rebalance)
    }

    /** `POST /ideas` — `thesis` is omitted entirely when blank. */
    fun createBody(name: String, thesis: String?, state: IdeaState): JsonObject = buildJsonObject {
        put("name", name.trim())
        val t = thesis?.trim()
        if (!t.isNullOrEmpty()) put("thesis", t)
        put("state", state(state))
    }

    /**
     * `PATCH /ideas/{id}` — a genuine partial update.
     *
     * `thesis` has three distinct meanings and all three are reachable: absent
     * leaves it untouched, `null` clears it, a string replaces it. [clearThesis]
     * is what distinguishes "the user emptied the field" from "the user didn't
     * touch it", which a nullable string alone cannot express.
     */
    fun updateBody(
        name: String? = null,
        thesis: String? = null,
        clearThesis: Boolean = false,
        state: IdeaState? = null,
    ): JsonObject = buildJsonObject {
        name?.let { put("name", it.trim()) }
        when {
            clearThesis -> put("thesis", JsonNull)
            thesis != null -> put("thesis", thesis.trim())
        }
        state?.let { put("state", state(it)) }
    }
}

class IdeasRepository(
    private val api: BtApi,
    private val json: Json,
) {

    /** My ideas, newest first. Owner-scoped — a friend's shared idea is not here. */
    suspend fun ideas(): BtResult<List<Idea>> =
        when (val r = apiCall(json) { api.ideas() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.ideas.map { it.toDomain() })
            is BtResult.Err -> r
        }

    /** One idea — owner-only; someone else's answers 404. */
    suspend fun idea(ideaId: String): BtResult<Idea> =
        when (val r = apiCall(json) { api.idea(ideaId) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.idea.toDomain())
            is BtResult.Err -> r
        }

    suspend fun create(name: String, thesis: String?, state: IdeaState): BtResult<Idea> =
        when (val r = apiCall(json) { api.createIdea(IdeaWire.createBody(name, thesis, state)) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.idea.toDomain())
            is BtResult.Err -> r
        }

    suspend fun update(
        ideaId: String,
        name: String? = null,
        thesis: String? = null,
        clearThesis: Boolean = false,
        state: IdeaState? = null,
    ): BtResult<Idea> {
        val body = IdeaWire.updateBody(name, thesis, clearThesis, state)
        return when (val r = apiCall(json) { api.updateIdea(ideaId, body) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.idea.toDomain())
            is BtResult.Err -> r
        }
    }

    /** 204. */
    suspend fun delete(ideaId: String): BtResult<Unit> =
        try {
            val resp = api.deleteIdea(ideaId)
            if (resp.isSuccessful) {
                BtResult.Ok(Unit)
            } else {
                BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
            }
        } catch (_: IOException) {
            BtResult.Err(
                BtApiError(0, BtApiError.Codes.NETWORK, "No connection. Check your network and try again."),
            )
        }

    /** Copy a friend's shared idea into my own list — the only non-owner read. */
    suspend fun clone(ideaId: String): BtResult<Idea> =
        when (val r = apiCall(json) { api.cloneIdea(ideaId) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.idea.toDomain())
            is BtResult.Err -> r
        }

    private fun IdeaDto.toDomain(): Idea {
        val src = state.source
        val source: IdeaSource = when {
            src.kind == "adhoc" -> IdeaSource.Adhoc(
                src.positions.orEmpty().map { IdeaPosition(it.assetId, it.weight) },
            )
            // Anything else with a conglomerate id is treated as the
            // conglomerate branch; an unmodelled future kind degrades to an
            // empty ad-hoc list, which renders as "no positions" rather than
            // crashing a screen.
            src.conglomerateId != null -> IdeaSource.Conglomerate(src.conglomerateId)
            else -> IdeaSource.Adhoc(emptyList())
        }
        val bench = state.benchmark
        val benchmark: IdeaBenchmark? = when {
            bench == null -> null
            bench.preset != null -> IdeaBenchmark.Preset(bench.preset)
            bench.assetId != null -> IdeaBenchmark.Asset(bench.assetId)
            bench.conglomerateId != null -> IdeaBenchmark.Conglomerate(bench.conglomerateId)
            else -> null
        }
        return Idea(
            id = id,
            name = name,
            thesis = thesis,
            state = IdeaState(source, state.range, benchmark, state.mode, state.rebalance),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    companion object {
        /** 400 — create/update named a conglomerate the caller doesn't own. */
        const val CODE_CONGLOMERATE_NOT_FOUND = "IDEA_CONGLOMERATE_NOT_FOUND"
    }
}
