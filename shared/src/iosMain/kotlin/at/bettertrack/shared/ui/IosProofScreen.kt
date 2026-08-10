package at.bettertrack.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.domain.ResolvedSetting
import at.bettertrack.app.domain.SettingSource
import at.bettertrack.app.domain.jsNumberToString
import at.bettertrack.app.domain.resolvePortfolioSetting

/**
 * Compose Multiplatform version this module builds against. There is no runtime
 * constant for it, so it is restated here — keep in sync with
 * `composeMultiplatform` in gradle/libs.versions.toml. (The Kotlin version below
 * it is NOT restated: [KotlinVersion.CURRENT] is read from the stdlib actually
 * linked into this binary, so it cannot drift.)
 */
private const val COMPOSE_MULTIPLATFORM_VERSION = "1.10.3"

/**
 * One row of the proof: a per-portfolio setting resolved through the real
 * [resolvePortfolioSetting] cascade, with the inputs kept visible so a reader
 * can check the answer by hand from the screenshot.
 */
private class ScopeCase(
    val label: String,
    val override: String?,
    val userDefault: String?,
    val systemDefault: String,
) {
    val resolved: ResolvedSetting<String> =
        resolvePortfolioSetting(override, userDefault, systemDefault)
}

/**
 * The three reachable outcomes of the cascade, for one honest setting:
 * a portfolio's display currency. Every value on screen below is COMPUTED by
 * the shared function at render time — nothing is a literal.
 */
private val cases = listOf(
    ScopeCase(
        label = "Portfolio \"Pension\" overrides it",
        override = "CHF",
        userDefault = "USD",
        systemDefault = "EUR",
    ),
    ScopeCase(
        label = "Portfolio \"Trading\" inherits your default",
        override = null,
        userDefault = "USD",
        systemDefault = "EUR",
    ),
    ScopeCase(
        label = "New account — nothing set anywhere",
        override = null,
        userDefault = null,
        systemDefault = "EUR",
    ),
)

/** Phase-1 proof screen: shared BetterTrack domain code, rendered on iOS. */
@Composable
fun IosProofScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 22.dp),
        ) {
            Text(
                text = "BetterTrack",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "shared Kotlin, running on iOS",
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            FormatterProofCard()
            Spacer(Modifier.height(14.dp))
            CasesCard()
            Spacer(Modifier.height(14.dp))
            ProvenanceCard()
        }
    }
}

@Composable
private fun CasesCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "resolvePortfolioSetting()",
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "display currency = override ?? user ?? system",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cases.forEach { case ->
                Spacer(Modifier.height(12.dp))
                CaseRow(case)
            }
        }
    }
}

@Composable
private fun CaseRow(case: ScopeCase) {
    Column(Modifier.fillMaxWidth()) {
        Text(text = case.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            // The INPUTS, so the computed answer below is checkable by eye.
            text = "in:  override=${fmt(case.override)}  " +
                "user=${fmt(case.userDefault)}  system=${case.systemDefault}",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // COMPUTED by the shared function.
                text = case.resolved.value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            SourceChip(case.resolved.source)
        }
    }
}

private fun fmt(value: String?): String = value ?: "null"

/** Which layer won, straight off [SettingSource.wire] — the domain's own name. */
@Composable
private fun SourceChip(source: SettingSource) {
    val container = when (source) {
        SettingSource.PORTFOLIO -> MaterialTheme.colorScheme.primaryContainer
        SettingSource.USER -> MaterialTheme.colorScheme.secondaryContainer
        SettingSource.SYSTEM -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = container, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = "from ${source.wire}",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ProvenanceCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "Where this code lives",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            ProvenanceLine("module", ":shared / commonMain")
            ProvenanceLine("file", "SettingsScope.kt")
            ProvenanceLine("package", "at.bettertrack.app.domain")
            ProvenanceLine("also runs on", "Android, same source")
            ProvenanceLine("verified by", "DomainVectorTest (9)")
            Spacer(Modifier.height(6.dp))
            ProvenanceLine("Kotlin", KotlinVersion.CURRENT.toString())
            ProvenanceLine("Compose MP", COMPOSE_MULTIPLATFORM_VERSION)
            ProvenanceLine("runtime", "Kotlin/Native")
            ProvenanceLine("target", "iosSimulatorArm64")
        }
    }
}

@Composable
private fun ProvenanceLine(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = key,
            modifier = Modifier.width(104.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * One [jsNumberToString] byte-identity case. [expected] is the STRING produced by
 * running the identical input through the JVM/Android path (the audited source of
 * truth, == V8's `String(value)`); [actual] is computed on THIS device by the
 * migrated shared Kotlin/Native code. They must be equal to the byte.
 */
private class FmtCase(val label: String, val value: Double, val expected: String) {
    val actual: String = jsNumberToString(value)
    val pass: Boolean = actual == expected
}

private val fmtCases: List<FmtCase> = listOf(
    FmtCase("0.1", 0.1, "0.1"),
    FmtCase("0.2", 0.2, "0.2"),
    FmtCase("0.3", 0.3, "0.3"),
    FmtCase("100.0", 100.0, "100"),
    FmtCase("1e21", 1e21, "1e+21"),
    FmtCase("1e-7 (JS→exp)", 1e-7, "1e-7"),
    FmtCase("5e-324 min subnormal", Double.MIN_VALUE, "5e-324"),
    FmtCase("1.797…e308 max", Double.MAX_VALUE, "1.7976931348623157e+308"),
    FmtCase("123456789.123456789", 123456789.123456789, "123456789.12345679"),
    FmtCase("0.1+0.2 (17 sig digits)", 0.1 + 0.2, "0.30000000000000004"),
)

@Composable
private fun FormatterProofCard() {
    val passCount = fmtCases.count { it.pass }
    val allPass = passCount == fmtCases.size
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "jsNumberToString()  — vault number rendering",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "shared Kotlin/Native on iOS  vs  JVM source of truth",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                color = if (allPass) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = if (allPass) "PASS  $passCount / ${fmtCases.size} byte-identical"
                    else "FAIL  ${fmtCases.size - passCount} of ${fmtCases.size} differ",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            fmtCases.forEach { case ->
                Spacer(Modifier.height(7.dp))
                FmtRow(case)
            }
        }
    }
}

@Composable
private fun FmtRow(case: FmtCase) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (case.pass) "PASS" else "FAIL",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (case.pass) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(text = case.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        // want == got on every pass; show both so the equality is checkable by eye.
        Text(
            text = "jvm ${case.expected}   ios ${case.actual}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = if (case.pass) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
        )
    }
}
