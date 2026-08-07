package at.bettertrack.app.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.BT_PROFILE_ICONS

/**
 * The curated profile-icon artwork — the app half of the platform's shared avatar
 * set (`apps/web/src/user/components/profileIcons.tsx`).
 *
 * Until B2-C the app shipped **no artwork**: the picker mapped the 16 wire ids
 * onto Material glyphs, two of them colliding (fox and panda both `Pets`), and
 * every social surface rendered grey initials where the web renders a fox. The 16
 * `ic_bt_avatar_*` drawables are literal ports of the web's own renderers — same
 * monorepo, drawn in-house, no icon dependency and no licence.
 *
 * The artwork is **multicolour and theme-independent by design** (`profileIcons
 * .tsx:19-21`: *"Colours pass AA contrast against both light and dark tiles at
 * every size"*), so one drawable serves both modes and NOTHING here is ever
 * tinted.
 */

/**
 * Drawable for a wire icon id, or `null` when this build has never heard of it.
 *
 * **The null branch is load-bearing.** `PROFILE_ICON_IDS` is append-only on the
 * platform, so an id newer than this build WILL arrive — from the user's own
 * account the moment they pick one on the web, if nothing else. Callers fall back
 * to [defaultProfileIconIdFor] rather than rendering a hole.
 */
@DrawableRes
fun profileIconRes(id: String?): Int? = when (id) {
    "astronaut" -> R.drawable.ic_bt_avatar_astronaut
    "fox" -> R.drawable.ic_bt_avatar_fox
    "panda" -> R.drawable.ic_bt_avatar_panda
    "robot" -> R.drawable.ic_bt_avatar_robot
    "star" -> R.drawable.ic_bt_avatar_star
    "wave" -> R.drawable.ic_bt_avatar_wave
    "mountain" -> R.drawable.ic_bt_avatar_mountain
    "leaf" -> R.drawable.ic_bt_avatar_leaf
    "flame" -> R.drawable.ic_bt_avatar_flame
    "bolt" -> R.drawable.ic_bt_avatar_bolt
    "moon" -> R.drawable.ic_bt_avatar_moon
    "planet" -> R.drawable.ic_bt_avatar_planet
    "ghost" -> R.drawable.ic_bt_avatar_ghost
    "crown" -> R.drawable.ic_bt_avatar_crown
    "compass" -> R.drawable.ic_bt_avatar_compass
    "anchor" -> R.drawable.ic_bt_avatar_anchor
    else -> null
}

/**
 * Translated name for one icon id — the picker's `contentDescription`, closing
 * the TalkBack gap the old grid documented and left open.
 *
 * Unknown ids have no string (same append-only reason as [profileIconRes]); the
 * caller falls back to the generic picker label.
 */
@StringRes
fun profileIconLabelRes(id: String?): Int? = when (id) {
    "astronaut" -> R.string.bt_profile_icon_astronaut
    "fox" -> R.string.bt_profile_icon_fox
    "panda" -> R.string.bt_profile_icon_panda
    "robot" -> R.string.bt_profile_icon_robot
    "star" -> R.string.bt_profile_icon_star
    "wave" -> R.string.bt_profile_icon_wave
    "mountain" -> R.string.bt_profile_icon_mountain
    "leaf" -> R.string.bt_profile_icon_leaf
    "flame" -> R.string.bt_profile_icon_flame
    "bolt" -> R.string.bt_profile_icon_bolt
    "moon" -> R.string.bt_profile_icon_moon
    "planet" -> R.string.bt_profile_icon_planet
    "ghost" -> R.string.bt_profile_icon_ghost
    "crown" -> R.string.bt_profile_icon_crown
    "compass" -> R.string.bt_profile_icon_compass
    "anchor" -> R.string.bt_profile_icon_anchor
    else -> null
}

/**
 * The deterministic avatar a user with no stored choice gets — a **literal port**
 * of `profileIcons.tsx:230-237`:
 *
 * ```js
 * const source = seed.length > 0 ? seed : 'user';
 * let hash = 0;
 * for (let i = 0; i < source.length; i += 1) {
 *   hash = (hash * 31 + source.charCodeAt(i)) % PROFILE_ICON_IDS.length;
 * }
 * ```
 *
 * Two properties make this port exact rather than merely similar, and both are
 * load-bearing — get either wrong and the same person shows a different avatar on
 * the phone than on the web:
 *
 * 1. **The modulo is INSIDE the loop.** `hash` therefore never leaves `[0,16)`,
 *    `hash * 31 + code` never exceeds `15*31 + 65535`, and the arithmetic stays
 *    far below both JS's 2^53 exact-integer range and Kotlin's `Int` range. No
 *    overflow, so no divergence — this would NOT hold with the modulo hoisted out.
 * 2. **Iteration is over UTF-16 code units.** JS `charCodeAt` yields code units
 *    and `String.length` counts them; Kotlin's `Char` and `String.length` are the
 *    same UTF-16 code units. Astral characters (emoji) therefore contribute two
 *    identical iterations on both sides.
 *
 * Cross-checked against a Node run of the web function in `BtProfileIconTest`.
 */
fun defaultProfileIconIdFor(seed: String): String {
    val source = if (seed.isNotEmpty()) seed else "user"
    var hash = 0
    for (ch in source) {
        hash = (hash * 31 + ch.code) % BT_PROFILE_ICONS.size
    }
    return BT_PROFILE_ICONS[hash]
}

/**
 * The icon actually rendered for a person: their stored choice when this build
 * knows it, otherwise the deterministic name-derived default. Never returns an
 * id without artwork.
 */
fun resolvedProfileIconId(iconId: String?, name: String): String =
    if (profileIconRes(iconId) != null) iconId!! else defaultProfileIconIdFor(name)
