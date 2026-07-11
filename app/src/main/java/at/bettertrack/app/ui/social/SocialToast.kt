package at.bettertrack.app.ui.social

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A toast payload the Social ViewModels can emit without holding a Context.
 *
 * The in-app language switch wraps each Activity's resources per-locale
 * (see [at.bettertrack.app.data.i18n.LocaleManager]); the Application context is
 * NOT re-wrapped, so resolving strings from a ViewModel would ignore that choice.
 * Emitting a resource reference and resolving it in the UI (against the
 * locale-wrapped activity resources) keeps VM-sourced toasts correctly localized.
 */
sealed interface SocialToast {
    /** An already-resolved string (e.g. a network error's userMessage). */
    data class Raw(val text: String) : SocialToast

    /** A plain string resource with optional positional format args. */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : SocialToast

    /** A quantity string; [count] both selects the plural and is a format arg (via [args]). */
    data class Quantity(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : SocialToast
}

/** Resolve against the current (locale-wrapped) composition resources. */
@Composable
fun SocialToast.resolve(): String = when (this) {
    is SocialToast.Raw -> text
    is SocialToast.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
    is SocialToast.Quantity -> pluralStringResource(id, count, *args.toTypedArray())
}
