package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Workboard **ideas** (V5, `workboard:*`) — platform
 * `packages/contracts/src/ideas.ts`, routes `ideasRoutes.ts` mounted at
 * `/api/v1/ideas`.
 *
 * An idea is NOT a free-text note. It is a **saved workboard analysis**: a name,
 * an optional written thesis, and the exact backtest setup that produced it
 * (`state`). That is the whole model — there is no status, no tags, no author
 * field, no sort order, and no embedded asset identities: an ad-hoc source
 * carries bare `assetId` UUIDs with relative weights, and the symbols must be
 * resolved through `GET /assets/{id}` if the UI wants to show them.
 *
 * The complete surface is six routes and nothing else:
 *  - `GET    /ideas`               → [IdeaListResponse]
 *  - `POST   /ideas`               → 201 [IdeaResponse]
 *  - `GET    /ideas/{ideaId}`      → [IdeaResponse]
 *  - `PATCH  /ideas/{ideaId}`      → [IdeaResponse]
 *  - `DELETE /ideas/{ideaId}`      → 204
 *  - `POST   /ideas/{ideaId}/clone`→ 201 [IdeaResponse] (a friend's shared idea)
 *
 * `GET /ideas/{id}` is **owner-only**; a friend's shared idea is visible only as
 * the pointer in `GET /social/shared` (`{ideaId, name, owner, hasThesis}`) and
 * can be read in full only by cloning it into your own list.
 *
 * ### Why request bodies are hand-built JSON, not these DTOs
 * The write schemas are `.strict()` **discriminated unions**: the
 * `conglomerate` source branch permits `{kind, conglomerateId}` and nothing
 * else, the `adhoc` branch `{kind, positions}` and nothing else, and a benchmark
 * is exactly one of `{preset}` / `{assetId}` / `{conglomerateId}` — naming two
 * is a 400. Meanwhile `state.benchmark` is *required-present but nullable*, so
 * an omitted key is also a 400. The app's global `Json` has
 * `explicitNulls = false` (it must never send nulls to the rest of the API),
 * which cannot express "present and null". Rather than fight that with a second
 * serializer whose discriminator differs from every other DTO, writes compose
 * their body explicitly — see `IdeaWire` in the ideas repository, which is a
 * pure function and unit-tested for the exact key set per branch.
 *
 * These DTOs are therefore the **read** model: flat and tolerant, so a
 * `conglomerate` source decodes with `positions == null` and vice versa.
 */

/** Backtest ranges an idea can be saved with: `1Y` | `3Y` | `5Y` | `MAX`. */
val IDEA_RANGES = listOf("1Y", "3Y", "5Y", "MAX")

/** Weight-overflow handling: `clip` | `cash` | `redistribute`. */
val IDEA_MODES = listOf("clip", "cash", "redistribute")

/** Rebalance cadence: `none` | `monthly` | `quarterly` | `yearly`. */
val IDEA_REBALANCES = listOf("none", "monthly", "quarterly", "yearly")

/** Benchmark presets: S&P 500, DAX, MSCI World. */
val IDEA_BENCHMARK_PRESETS = listOf("^GSPC", "^GDAXI", "URTH")

/** `name` max length (server trims first). */
const val IDEA_NAME_MAX = 120

/** `thesis` max length. */
const val IDEA_THESIS_MAX = 4000

/** Max positions in an ad-hoc source. */
const val IDEA_ADHOC_MAX = 50

/** One ad-hoc position: an asset and its RELATIVE weight (normalised on read). */
@Serializable
data class IdeaPositionDto(
    val assetId: String = "",
    val weight: Double = 0.0,
)

/**
 * The idea's source, flattened for decoding: exactly one of the two branches is
 * populated, selected by [kind] (`conglomerate` | `adhoc`).
 */
@Serializable
data class IdeaSourceDto(
    val kind: String = "",
    val conglomerateId: String? = null,
    val positions: List<IdeaPositionDto>? = null,
)

/** The benchmark, flattened: at most one field is non-null. */
@Serializable
data class IdeaBenchmarkDto(
    /** One of [IDEA_BENCHMARK_PRESETS]. */
    val preset: String? = null,
    val assetId: String? = null,
    val conglomerateId: String? = null,
)

@Serializable
data class IdeaStateDto(
    val source: IdeaSourceDto = IdeaSourceDto(),
    /** One of [IDEA_RANGES]. */
    val range: String = "MAX",
    /** Present-but-nullable on the wire; null = no comparison line. */
    val benchmark: IdeaBenchmarkDto? = null,
    /** One of [IDEA_MODES]. */
    val mode: String = "clip",
    /** One of [IDEA_REBALANCES]. */
    val rebalance: String = "none",
)

@Serializable
data class IdeaDto(
    val id: String = "",
    /** The title. Trimmed, 1..[IDEA_NAME_MAX]. */
    val name: String = "",
    /** The written rationale. Nullable; up to [IDEA_THESIS_MAX] chars. */
    val thesis: String? = null,
    val state: IdeaStateDto = IdeaStateDto(),
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** `GET /ideas` — newest first (`created_at DESC`), unbounded, no paging. */
@Serializable
data class IdeaListResponse(
    val ideas: List<IdeaDto> = emptyList(),
)

/** Every single-idea route wraps: create, read, update and clone alike. */
@Serializable
data class IdeaResponse(
    val idea: IdeaDto = IdeaDto(),
)
