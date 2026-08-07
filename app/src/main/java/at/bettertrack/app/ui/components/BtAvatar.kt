package at.bettertrack.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtTheme

/**
 * A person, drawn as the platform's curated avatar artwork.
 *
 * This used to render **initials on a hashed tint**. The web never did — its
 * `Avatar.tsx` always paints one of 16 curated SVGs, falling back to a
 * deterministic name-derived one — so the same account showed a grey "CW" on the
 * phone and a fox in the browser. Worse, the icon was already on the wire
 * (`SocialUserDto.profileIcon`) and the domain models threw it away.
 *
 * Now: [iconId] is the person's stored choice, and an absent or
 * not-yet-known-to-this-build id falls back to [defaultProfileIconIdFor], the
 * literal port of the web's hash. See [resolvedProfileIconId].
 *
 * The artwork is multicolour and carries its own tile colour, so it is **never
 * tinted** and needs no light/dark variant — the palettes were drawn to pass AA
 * on both substrates. The 64×64 rounded tile is clipped to a circle, matching the
 * web's `border-radius: 50%` on the same asset.
 *
 * Pass [gold] = true for the signed-in user / self chip: it adds a gold ring
 * *around* the artwork rather than recolouring it, because the identity of the
 * avatar and the fact that it is you are two different facts.
 */
@Composable
fun BtAvatar(
    name: String,
    modifier: Modifier = Modifier,
    iconId: String? = null,
    size: Dp = 40.dp,
    gold: Boolean = false,
) {
    val bt = BtTheme.colors
    val resolved = resolvedProfileIconId(iconId, name)
    Image(
        painter = painterResource(profileIconRes(resolved)!!),
        // Decorative: every call site pairs the avatar with the person's name as
        // adjacent text, so announcing it again would just double up.
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(if (gold) Modifier.border(1.5.dp, bt.gold, CircleShape) else Modifier),
    )
}
