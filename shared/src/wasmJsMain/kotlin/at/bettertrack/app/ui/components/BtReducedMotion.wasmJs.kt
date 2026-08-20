package at.bettertrack.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Web: the browser's own accessibility switch, `prefers-reduced-motion`.
 *
 * Same user intent as Android's animator duration scale, read from where a
 * browser keeps it. Sampled once per composition with [remember], exactly as
 * the Android actual does — a user who flips the OS setting mid-session gets
 * the new behaviour on the next screen, which is the behaviour Android has had
 * all along.
 */
@Composable
actual fun rememberReducedMotion(): Boolean = remember { prefersReducedMotion() }

/**
 * `window.matchMedia(...)` through a `js(...)` body rather than a
 * `kotlinx-browser` dependency: one boolean is not worth an artifact, and the
 * call has to survive a host that runs the module outside a document (the
 * Node-based conformance harness never reaches this file, but the guard costs
 * nothing).
 */
private fun prefersReducedMotion(): Boolean =
    js("(typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)")
