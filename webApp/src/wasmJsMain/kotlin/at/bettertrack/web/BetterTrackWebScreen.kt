package at.bettertrack.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.bettertrack.app.data.auth.LoginPhase
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.domain.StatSeriesPoint
import at.bettertrack.app.domain.Transaction
import at.bettertrack.app.domain.TransactionSide
import at.bettertrack.app.domain.computeSeriesStats
import at.bettertrack.app.domain.jsNumberToString
import at.bettertrack.app.domain.reducePosition
import at.bettertrack.app.domain.viennaYearOf
import at.bettertrack.app.ui.auth.BtLoginScreen
import at.bettertrack.app.ui.auth.LoginStrings
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.theme.BetterTrackTheme
import at.bettertrack.app.ui.theme.BtIcons
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

// ── The inputs. Fixed, so every number on screen is hand-checkable ──────────

/**
 * One asset's transaction log. `reducePosition` re-averages on BUY (capitalising
 * the fee) and realizes on SELL, so the expected outcome is arithmetic anyone can
 * redo on paper:
 *   buy 1 → avg = (12·78.40 + 1.95) / 12          = 78.5625
 *   buy 2 → avg = (12·78.5625 + 8·84.10 + 1.95)/20 = 80.875
 *   sell  → realized = 5·(96.25 − 80.875) − 1.95   = 74.925, qty left = 15
 */
private val transactions = listOf(
    Transaction("BMW.DE", TransactionSide.BUY, 12.0, 78.40, 1.95, "2026-02-03T09:15:00Z"),
    Transaction("BMW.DE", TransactionSide.BUY, 8.0, 84.10, 1.95, "2026-04-17T10:02:00Z"),
    Transaction("BMW.DE", TransactionSide.SELL, 5.0, 96.25, 1.95, "2026-06-05T14:40:00Z"),
)

/** Last quote, native currency (EUR here, so no FX step is involved). */
private const val LAST_PRICE = 91.30

/** A weekly portfolio-value series for the stats block. */
private val series = listOf(
    StatSeriesPoint("2026-06-30", 12480.00),
    StatSeriesPoint("2026-07-07", 12735.50),
    StatSeriesPoint("2026-07-14", 12180.25),
    StatSeriesPoint("2026-07-21", 12902.00),
    StatSeriesPoint("2026-07-28", 13344.75),
    StatSeriesPoint("2026-08-04", 13010.60),
    StatSeriesPoint("2026-08-11", 13588.40),
)

/** The dev backend this build points at; stated the way the login screen states it. */
private const val WEB_SERVER_HOST = "mobile.bettertrack.at"

/**
 * The browser shell (web port, Phase W1).
 *
 * W0's page proved the *engine* runs in a browser. This one proves the
 * **product** does: everything from here down — the colour tables, the type
 * ramp, the shapes, the icon set, the wordmark, the primary button, the login
 * screen itself — is `:shared/commonMain` code, the same source `:app`
 * compiles. Nothing on this page re-implements a token or a layout.
 *
 * Two login screens are shown side by side, one forced [BtThemeMode.Dark] and
 * one forced [BtThemeMode.Light], because the most expensive thing to get wrong
 * in this move is the dual colour table (`BtColors`: "one token set, two value
 * tables" — light is where `gold` becomes `goldInk` and the hairline reappears).
 * Rendering both at once from one composable is the proof that both tables
 * travelled and that `BetterTrackTheme` still maps them onto Material.
 *
 * Below them, a proof panel keeps W0's engine numbers — now printed in the real
 * type ramp, through the seeded locale — plus a digit-column check that fails
 * visibly if the embedded typeface did not reach the canvas.
 *
 * @param locale seeded from `navigator.language` at startup. See [BtWebLocale].
 * @param brandGlyph the embedded BT mark; see [rememberBtBrandGlyph].
 */
@Composable
fun BetterTrackWebApp(locale: BtWebLocale, brandGlyph: Painter) {
    // The page itself is painted in the DARK table — this is a demo host, not a
    // product screen, and the product is dark by default. Both login panes below
    // carry their own theme regardless of this one.
    BetterTrackTheme(mode = BtThemeMode.Dark) {
        val bt = BtTheme.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bt.bg)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 940.dp).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ShellHeader(locale)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoginPane(BtThemeMode.Dark, "BtDarkColors", locale, brandGlyph)
                    LoginPane(BtThemeMode.Light, "BtLightColors", locale, brandGlyph)
                }
                EnginePanel(locale)
            }
        }
    }
}

/**
 * One framed login screen at a fixed theme.
 *
 * The frame is 390×760dp — a phone, so the thumb-zone layout the screen was
 * designed for is what gets rendered rather than a stretched desktop version.
 * What a real desktop width looks like is a W4 question; guessing at it here
 * would invent a layout the design has never seen.
 */
@Composable
private fun LoginPane(
    mode: BtThemeMode,
    caption: String,
    locale: BtWebLocale,
    brandGlyph: Painter,
) {
    val captionColor = BtTheme.colors.textMuted
    val captionStyle = BtTheme.type.numberCaption
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BetterTrackTheme(mode = mode) {
            val bt = BtTheme.colors
            Box(
                modifier = Modifier
                    .width(390.dp)
                    .height(760.dp)
                    .background(bt.bg, BtShapes.group)
                    .border(1.dp, bt.border, BtShapes.group),
            ) {
                BtLoginScreen(
                    phase = LoginPhase.Idle,
                    strings = webLoginStrings(locale),
                    brandGlyph = brandGlyph,
                    // Material's `Icons.Outlined.Settings` has no artifact this
                    // stack can consume, so the web takes the app's OWN gear from
                    // the Origin set that moved with the theme. See the W1 record
                    // in docs/KMP_PLAN.md §14 — this is the one glyph where the
                    // web and Android renders differ today.
                    settingsIcon = BtIcons.Settings,
                    onLogin = {},
                    onNeedAccount = {},
                    onForgotPassword = {},
                    serverLine = "Server: $WEB_SERVER_HOST",
                    // The pre-login sheet reads SharedPreferences and switches
                    // server origins; both are W5/W6. The gear is present and
                    // pressable — it simply has nothing to open yet.
                    settingsSheet = {},
                )
            }
        }
        Text(
            text = caption,
            style = captionStyle,
            color = captionColor,
            modifier = Modifier.width(390.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The login screen's copy, EN/DE, hard-coded HERE and nowhere else.
 *
 * This is the one sanctioned place for literal user-facing text in the whole
 * port: `:shared` never sees a string literal, and W2 deletes this function when
 * the 2984 keys move to compose-resources. Until then the copy is a transcript
 * of the app's own `res/values` and `res/values-de`.
 */
private fun webLoginStrings(locale: BtWebLocale): LoginStrings = when (locale) {
    BtWebLocale.DE_AT -> LoginStrings(
        edition = "App",
        tagline = "Dein Portfolio. Klar, ruhig, jederzeit.",
        loginButton = "Mit BetterTrack anmelden",
        needAccount = "Noch kein Konto?",
        forgotPassword = "Passwort vergessen?",
        useWithoutAccount = "Ohne Konto verwenden",
        back = "Zurück",
        settingsLabel = "Einstellungen",
        errorMessage = { "Anmeldung fehlgeschlagen. Bitte erneut versuchen." },
    )
    BtWebLocale.EN -> LoginStrings(
        edition = "App",
        tagline = "Your portfolio. Clear, calm, always with you.",
        loginButton = "Login with BetterTrack",
        needAccount = "Need an account?",
        forgotPassword = "Forgot password?",
        useWithoutAccount = "Use without an account",
        back = "Back",
        settingsLabel = "Settings",
        errorMessage = { "Sign-in failed. Please try again." },
    )
}

/** Wordmark + what the browser told us, stated rather than assumed. */
@Composable
private fun ShellHeader(locale: BtWebLocale) {
    val bt = BtTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Wordmark(edition = "Web")
        Text(
            text = "navigator.language = \"${navigatorLanguage().ifEmpty { "—" }}\"  →  ${locale.tag}" +
                "   ·   shared ui/theme   ·   shared login screen   ·   embedded Roboto",
            style = BtTheme.type.numberCaption,
            color = bt.textMuted,
        )
    }
}

/**
 * The engine + typography proof.
 *
 * Everything printed here goes through `:shared/commonMain` twice: the VALUE
 * comes from the domain engine the 622 conformance vectors gate, and the STYLE
 * comes from `BtTheme.type`, whose money styles all carry `tnum`.
 */
@Composable
private fun EnginePanel(locale: BtWebLocale) {
    val position = reducePosition(transactions)
    val marketValue = position.quantity * LAST_PRICE
    val costBasis = position.quantity * position.avgCost
    val unrealized = marketValue - costBasis
    val unrealizedPct = if (costBasis != 0.0) (marketValue / costBasis - 1) * 100 else 0.0
    val stats = computeSeriesStats(series)
    val bt = BtTheme.colors

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            modifier = Modifier.width(462.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProofCard("BMW.DE · Position") {
                Text(
                    text = webMoney(marketValue, locale),
                    style = BtTheme.type.moneyHero,
                    color = bt.textPrimary,
                )
                ValueRow("Quantity", webQuantity(position.quantity, locale))
                ValueRow("Ø cost", webMoney(position.avgCost, locale))
                ValueRow("Last", webMoney(LAST_PRICE, locale))
                Hairline()
                ValueRow(
                    "Unrealised",
                    webMoney(unrealized, locale, showSign = true) + "   " +
                        webPercent(unrealizedPct, locale, showSign = true),
                    tint = directionTint(unrealized),
                )
                ValueRow(
                    "Realised",
                    webMoney(position.realizedPnl, locale, showSign = true),
                    tint = directionTint(position.realizedPnl),
                )
            }

            ProofCard("Portfolio series · 6 weeks") {
                ValueRow(
                    "Total return",
                    webPercent(stats.totalReturnPct, locale, showSign = true),
                    tint = directionTint(stats.totalReturnPct),
                )
                ValueRow(
                    "CAGR",
                    webPercent(stats.cagrPct, locale, showSign = true),
                    tint = directionTint(stats.cagrPct),
                )
                ValueRow(
                    "Max drawdown",
                    webPercent(stats.maxDrawdownPct, locale),
                    tint = directionTint(stats.maxDrawdownPct),
                )
                ValueRow(
                    "Best day",
                    stats.bestDay?.let {
                        "${it.date}   ${webPercent(it.returnPct, locale, showSign = true)}"
                    } ?: WEB_EM_DASH,
                    tint = bt.gain,
                )
                ValueRow(
                    "Worst day",
                    stats.worstDay?.let {
                        "${it.date}   ${webPercent(it.returnPct, locale, showSign = true)}"
                    } ?: WEB_EM_DASH,
                    tint = bt.loss,
                )
            }
        }

        Column(
            modifier = Modifier.width(462.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The digit-column check, and the honest version of it.
            //
            // Every money style in BtTypography carries `tnum`, and the point of
            // that is a column of numbers whose digits line up. These four rows
            // must therefore end on ONE vertical edge — they do, which is what
            // says the embedded Roboto reached the canvas rather than some
            // fallback face with proportional figures.
            //
            // The two rows under the hairline drop `tnum` deliberately, and they
            // line up TOO. That is not a failure of the check, it is a fact about
            // the typeface: Roboto's default lining figures are already tabular,
            // so `tnum` is belt-and-braces here rather than the thing doing the
            // work. Stated rather than quietly cropped out, because a reader who
            // compares the two blocks and sees no difference deserves the reason.
            ProofCard("Digit column · BtTheme.type (tnum)") {
                listOf("1111111111", "0000000000", "1234567890", "8888888888").forEach {
                    Text(text = it, style = BtTheme.type.moneyMedium, color = bt.textPrimary)
                }
                Hairline()
                Text(
                    text = "same digits, tnum off — Roboto's figures are tabular anyway:",
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                )
                listOf("1111111111", "1234567890").forEach {
                    Text(
                        text = it,
                        style = BtTheme.type.moneyMedium.copy(fontFeatureSettings = null),
                        color = bt.textSecondary,
                    )
                }
            }

            // The byte-identity primitive itself, unmediated. jsNumberToString
            // calls `formatScientific` on EVERY value (it searches precisions
            // 1..17 for the shortest round trip), so these four rows are the
            // Kotlin/Wasm `actual` of the expect that feeds vault plaintext and
            // the GCM AAD header, printing its own output.
            ProofCard("jsNumberToString · shared expect/actual") {
                MonoRow("0.1 + 0.2", jsNumberToString(0.1 + 0.2))
                MonoRow("1 / 3", jsNumberToString(1.0 / 3.0))
                MonoRow("1.23e-7", jsNumberToString(0.000000123))
                MonoRow("2^53", jsNumberToString(9007199254740992.0))
            }

            // The Vienna tax-year boundary. Both instants are 30 minutes apart
            // and land in DIFFERENT tax years only if the IANA zone database is
            // really loaded (CET = UTC+1 in December): 23:30Z on 31 Dec is
            // already 00:30 on 1 Jan in Vienna. Without the zone rules this row
            // does not print a wrong answer — it crashes the wasm module.
            ProofCard("Tax.viennaYearOf · IANA zone rules") {
                MonoRow("2025-12-31T22:30:00Z", viennaYearOf("2025-12-31T22:30:00Z").toString())
                MonoRow("2025-12-31T23:30:00Z", viennaYearOf("2025-12-31T23:30:00Z").toString())
            }
        }
    }
}

@Composable
private fun directionTint(value: Double?): Color = when {
    value == null || value == 0.0 -> BtTheme.colors.textPrimary
    value > 0 -> BtTheme.colors.gain
    else -> BtTheme.colors.loss
}

@Composable
private fun ProofCard(subject: String, content: @Composable () -> Unit) {
    val bt = BtTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bt.surface, BtShapes.card)
            .border(1.dp, bt.border, BtShapes.card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(text = subject, style = BtTheme.type.numberCaption, color = bt.goldEmphasis)
        content()
    }
}

@Composable
private fun ValueRow(label: String, value: String, tint: Color = BtTheme.colors.textPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = BtTheme.type.moneySmall, color = BtTheme.colors.textSecondary)
        Text(text = value, style = BtTheme.type.moneySmall, color = tint)
    }
}

@Composable
private fun MonoRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bt.surfaceHigh, BtShapes.cardSmall)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = BtTheme.type.numberCaption, color = bt.textMuted)
        Text(text = value, style = BtTheme.type.numberCaption, color = bt.textPrimary)
    }
}

@Composable
private fun Hairline() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(BtTheme.colors.border))
}
