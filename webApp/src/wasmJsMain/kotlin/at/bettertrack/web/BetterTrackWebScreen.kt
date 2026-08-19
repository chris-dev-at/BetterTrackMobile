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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.domain.StatSeriesPoint
import at.bettertrack.app.domain.Transaction
import at.bettertrack.app.domain.TransactionSide
import at.bettertrack.app.domain.computeSeriesStats
import at.bettertrack.app.domain.jsNumberToString
import at.bettertrack.app.domain.reducePosition
import at.bettertrack.app.domain.viennaYearOf

// ── BetterTrack dark palette ────────────────────────────────────────────────
// The exact hexes from app/src/main/java/at/bettertrack/app/ui/theme/BtColors.kt
// (dark scheme). Copied as literals rather than shared because the real theme
// still lives in :app and moves to :shared as part of W1 — see docs/KMP_PLAN.md
// §14. Keeping them literal here means the bring-up screen looks like the
// product without pretending the theme is already ported.
private val Bg = Color(0xFF0A0D12)
private val Surface = Color(0xFF161B22)
private val SurfaceHigh = Color(0xFF1C222B)
private val Hairline = Color(0xFF232A34)
private val TextPrimary = Color(0xFFF4F6F8)
private val TextSecondary = Color(0xFFC7CDD5)
private val TextMuted = Color(0xFF8B949F)
private val Gold = Color(0xFFF6B82E)
private val GoldEmphasis = Color(0xFFFBBF24)
private val Gain = Color(0xFF34D399)
private val Loss = Color(0xFFFB7185)

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

@Composable
fun BetterTrackWebApp() {
    // Every value below is computed HERE, in the browser, by :shared's commonMain
    // engine — the same code the 622 conformance vectors gate on Android and iOS.
    val position = reducePosition(transactions)
    val marketValue = position.quantity * LAST_PRICE
    val costBasis = position.quantity * position.avgCost
    val unrealized = marketValue - costBasis
    val unrealizedPct = if (costBasis != 0.0) (marketValue / costBasis - 1) * 100 else 0.0
    val stats = computeSeriesStats(series)

    Box(
        modifier = Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header()

            Card("BMW.DE · Position") {
                ValueRow("Quantity", deAtQuantity(position.quantity))
                ValueRow("Ø cost", deAtMoney(position.avgCost))
                ValueRow("Last", deAtMoney(LAST_PRICE))
                Divider()
                ValueRow("Market value", deAtMoney(marketValue), emphasis = true)
                ValueRow(
                    "Unrealised",
                    deAtMoney(unrealized, showSign = true) + "   " + deAtPercent(unrealizedPct, showSign = true),
                    tint = directionTint(unrealized),
                )
                ValueRow(
                    "Realised",
                    deAtMoney(position.realizedPnl, showSign = true),
                    tint = directionTint(position.realizedPnl),
                )
            }

            Card("Portfolio series · 6 weeks") {
                ValueRow(
                    "Total return",
                    deAtPercent(stats.totalReturnPct, showSign = true),
                    tint = directionTint(stats.totalReturnPct),
                )
                ValueRow("CAGR", deAtPercent(stats.cagrPct, showSign = true), tint = directionTint(stats.cagrPct))
                ValueRow("Max drawdown", deAtPercent(stats.maxDrawdownPct), tint = directionTint(stats.maxDrawdownPct))
                ValueRow(
                    "Best day",
                    stats.bestDay?.let { "${it.date}   ${deAtPercent(it.returnPct, showSign = true)}" } ?: WEB_EM_DASH,
                    tint = Gain,
                )
                ValueRow(
                    "Worst day",
                    stats.worstDay?.let { "${it.date}   ${deAtPercent(it.returnPct, showSign = true)}" } ?: WEB_EM_DASH,
                    tint = Loss,
                )
            }

            // The byte-identity primitive itself, unmediated. jsNumberToString
            // calls `formatScientific` on EVERY value (it searches precisions
            // 1..17 for the shortest round trip), so these four rows are the
            // Kotlin/Wasm `actual` of the expect that feeds vault plaintext and
            // the GCM AAD header, printing its own output.
            Card("jsNumberToString · shared expect/actual") {
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
            Card("Tax.viennaYearOf · IANA zone rules") {
                MonoRow("2025-12-31T22:30:00Z", viennaYearOf("2025-12-31T22:30:00Z").toString())
                MonoRow("2025-12-31T23:30:00Z", viennaYearOf("2025-12-31T23:30:00Z").toString())
            }

            BasicText(
                text = "Kotlin/Wasm · Compose Multiplatform · :shared commonMain domain engine",
                style = TextStyle(color = TextMuted, fontSize = 11.sp),
            )
        }
    }
}

private fun directionTint(value: Double?): Color = when {
    value == null || value == 0.0 -> TextPrimary
    value > 0 -> Gain
    else -> Loss
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(Gold, RoundedCornerShape(3.dp)))
        Spacer(Modifier.size(9.dp))
        Column {
            BasicText(
                text = "BetterTrack",
                style = TextStyle(color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            )
            BasicText(
                text = "web bring-up — every number below was computed by the shared engine",
                style = TextStyle(color = TextMuted, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun Card(subject: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        BasicText(
            text = subject,
            style = TextStyle(color = GoldEmphasis, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        content()
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    tint: Color = TextPrimary,
    emphasis: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = label, style = TextStyle(color = TextSecondary, fontSize = 13.sp))
        BasicText(
            text = value,
            style = TextStyle(
                color = tint,
                fontSize = if (emphasis) 18.sp else 14.sp,
                fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun MonoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceHigh, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(text = label, style = TextStyle(color = TextMuted, fontSize = 12.sp))
        BasicText(text = value, style = TextStyle(color = TextPrimary, fontSize = 12.sp))
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}
