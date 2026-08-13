// ── :shared — the KMP module (KMP/iOS port, Phase 1) ───────────────────────
// Holds Kotlin that both the Android app and the iOS app compile from the SAME
// source. Phase 1 keeps it deliberately thin: the pure-domain seam plus the
// Compose Multiplatform UI the iOS executable renders. No Room, no Ktor, no
// settings/lifecycle libraries yet — those land in Phase 2.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The ANDROID target. MUST be the KMP-specific Android plugin: plain
    // `com.android.library` is rejected outright by AGP 9 when combined with
    // kotlin.multiplatform. See gradle/libs.versions.toml [plugins].
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Compose Multiplatform: the runtime/library half (dependency accessors)…
    alias(libs.plugins.compose.multiplatform)
    // …and the Kotlin COMPILER half. Both are required on a KMP module; the
    // JetBrains plugin does not imply the compiler plugin.
    alias(libs.plugins.compose.compiler)
    // kotlinx-serialization COMPILER plugin (Phase 2 DTO migration). The API DTO
    // package (261 @Serializable classes) moved into commonMain; @Serializable
    // does not compile without this plugin. Same plugin :app already applies, at
    // the SAME Kotlin version (catalog `kotlin-serialization`, version.ref=kotlin).
    alias(libs.plugins.kotlin.serialization)
    // KMP/iOS port, Phase 2 (Room -> :shared). KSP runs Room's annotation
    // processor per target (android + both iOS), generating BtDatabase_Impl and
    // the DAO/constructor implementations. The `androidx.room` Gradle plugin owns
    // the exported-schema directory (room {} below) the same way it does in :app.
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    // At Kotlin 2.3.20 the Android target of a KMP module is configured through
    // `kotlin { android { … } }`. (The older top-level `androidLibrary { }`
    // block still resolves but is deprecated at 2.3.x and warns.)
    android {
        namespace = "at.bettertrack.shared"
        // Match :app exactly so the two never disagree about the platform they
        // compile against — :app is compileSdk 37 / minSdk 28.
        compileSdk = 37
        minSdk = 28

        // The KMP Android plugin creates NO unit-test compilation unless asked —
        // unlike `com.android.library`, which always has one. Without this opt-in
        // `commonTest` compiles for iOS only and the JVM half of the conformance
        // suite silently does not exist. This creates the `androidHostTest`
        // source set (which commonTest feeds) and the task that runs it.
        withHostTestBuilder {}.configure {}
    }

    // Real devices (arm64) and the Apple-silicon simulator. No iosX64: this Mac
    // is Apple silicon, and an Intel-simulator target would only add dead build
    // time. Both targets share one `iosMain` source set via Kotlin's default
    // hierarchy template, so iOS code is written once.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // commonMain — compiled for BOTH platforms. It holds the pure domain
        // layer (at.bettertrack.app.domain.*). Phase 1 declared no dependencies
        // here; Phase 2 migrated the calculation engine down, whose JS-runtime
        // date shims need a multiplatform date/time library — hence the single
        // commonMain dependency below. It is pure Kotlin with no UI surface, so
        // it is independent of the Compose BOM reconciliation described further
        // down; it changes nothing about :app's shipping graph (kotlinx-datetime
        // is added to :shared, and :app reaches it only transitively).
        //
        // kotlinx-serialization-json is the SECOND commonMain dependency (Phase 2
        // DTO migration): the API DTO package's @Serializable classes now compile
        // here for both platforms. This is pinned to the SAME version :app already
        // declares directly (catalog `kotlinxSerializationJson` = 1.9.0), so :app
        // reaching it transitively via :shared changes nothing in :app's resolved
        // shipping graph — verified with :app:dependencies before/after. Pure data,
        // no Compose: it stays in commonMain and pulls in no UI.
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            // KMP/iOS port, Phase 2 (Room -> :shared): the @Database, its 18
            // entities and 13 DAOs now compile here for BOTH platforms. room-runtime
            // is the multiplatform artifact (2.8.4, the SAME version :app declares
            // directly); :app reaches it transitively via :shared at the identical
            // coordinate, so its resolved shipping graph is unchanged (the classic
            // Android SupportSQLite APIs live in room-runtime-android, pulled in on
            // the android target only). coroutines-core backs the DAO `Flow<>`
            // returns — 1.10.2, again the version :app already resolves.
            implementation(libs.androidx.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }

        // commonTest — the domain CONFORMANCE harness (Phase 2). It runs on BOTH
        // the Android/JVM target and iosSimulatorArm64, which is the whole point:
        // the 622 platform-generated vectors previously only ever replayed on the
        // JVM inside :app, so the Kotlin/Native arithmetic was unverified.
        //
        // Both dependencies are TEST-scoped on :shared and therefore invisible to
        // :app's shipping graph. kotlinx-serialization-json is used here purely as a
        // runtime JSON tree API (parseToJsonElement / JsonObject / buildJsonObject),
        // no @Serializable class in the harness. (The serialization COMPILER plugin
        // IS applied module-wide as of Phase 2 for the commonMain DTOs; it is inert
        // over this harness since there is nothing @Serializable to generate for.)
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
        }
        //
        // androidMain — the Compose RUNTIME only, and pinned by :app's own BOM.
        //
        // This is the load-bearing decision of Phase 1. On an Android target the
        // JetBrains `compose.*` accessors resolve to the ANDROIDX Compose
        // artifacts, so declaring the UI stack (foundation/material3/ui) in
        // commonMain would push a second, CMP-chosen androidx.compose version
        // into :app's dependency graph and silently move the shipping app off
        // its `composeBom` — CMP 1.10.3 pulls androidx.compose.runtime 1.10.5.
        // The Phase-1 gate is "the Android app is completely unharmed", so the
        // UI is scoped to iosMain below.
        //
        // androidMain cannot be left EMPTY, though: the Compose compiler plugin
        // is applied per-module, not per-target, so it also runs over the
        // Android compilation and aborts there with
        //   IncompatibleComposeRuntimeVersionException: The Compose Compiler
        //   requires the Compose Runtime to be on the class path
        // even though no androidMain/commonMain file is @Composable. The runtime
        // is therefore declared here — but via `androidx-compose-bom`, THE SAME
        // BOM :app resolves against, so the coordinate and version are byte-for-
        // byte what :app already had and no resolution result changes.
        //
        // When shared UI genuinely moves cross-platform (Phase 3+), promoting
        // the UI deps to commonMain has to be done together with reconciling the
        // BOM — it is not a free move.
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.runtime)
        }

        // iosMain — the Compose Multiplatform UI, iOS-only for now.
        //
        // These use the `compose.*` ACCESSORS from the JetBrains plugin rather
        // than explicit coordinates, on purpose, even though 1.10.3 marks them
        // deprecated ("Specify dependency directly" — four warnings at configure
        // time, expected, not a defect). The accessors know pairings that are
        // NOT derivable from the CMP version: `compose.material3` at CMP 1.10.3
        // resolves to org.jetbrains.compose.material3:material3:**1.9.0**, since
        // material3 versions on its own line. Hand-pinning that to 1.10.3 asks
        // for an artifact that does not exist. Revisit only when the deprecated
        // accessors are actually removed, and re-derive every pairing from a
        // real `:shared:dependencies` report at that time.
        iosMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // compose.ui carries ComposeUIViewController, the UIKit bridge the
            // iOS executable hosts its Compose content in.
            implementation(compose.ui)
            // KMP/iOS port, Phase 2 (Room -> :shared): the bundled SQLite driver
            // that backs the KMP Room database on Kotlin/Native. iOS-ONLY — it is
            // declared here, never in commonMain/androidMain, so nothing new lands
            // on :app's classpath (see the androidxSqlite version note). It pulls
            // androidx.sqlite (2.6.2) transitively for the Native targets only.
            implementation(libs.androidx.sqlite.bundled)
            // KMP/iOS port, Phase 2 (network layer, Option B): the Ktor Darwin
            // client that REPRODUCES Android's Retrofit/OkHttp session behaviours
            // for iOS (BtKtorApiClient + the two session-critical plugins). Ktor is
            // iOS-ONLY — declared here, never in commonMain/androidMain — so it never
            // reaches :app's classpath (Android keeps its Retrofit stack verbatim;
            // verified with `:app:dependencies` — no io.ktor on the runtime graph).
            // ktor-client-core carries createClientPlugin / MockEngine SPI / the
            // HttpClientCall+HttpResponseData used to synthesize the 304→200 replay.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
        }

        // iosTest — the three SESSION-INTEGRITY behavioural proofs. They exercise
        // the iOS Ktor client with Ktor's MockEngine (in-memory, no socket), so
        // they run on iosSimulatorArm64Test with no server. TEST-scoped on :shared
        // and iOS-only, so invisible to :app entirely. kotlin("test") is inherited
        // from commonTest via the default hierarchy; only MockEngine is added here.
        iosTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}

// ── Room exported schema (KMP/iOS port, Phase 2) ────────────────────────────
// The @Database now lives in :shared, so Room's compiler exports the schema JSON
// from HERE. `shared/schemas/at.bettertrack.app.data.db.BtDatabase/10.json` must
// reproduce the golden v10 identityHash (a9fab166f6bcb1451ac240972a08a408) the
// app committed under app/schemas — moving the @Database does not change the
// schema, so the hash is unchanged. Kept next to the module so it travels with
// :shared, exactly as :app's room {} block did.
room {
    schemaDirectory("$projectDir/schemas")
}

// Room's KSP processor runs once per target compilation: the android target
// generates the SupportSQLite-backed BtDatabase_Impl (the one :app + the
// migration regression suite consume), and each iOS target generates the
// SQLiteDriver-backed one. There is no commonMain KSP configuration — Room codegen
// is inherently per-platform.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

// ── Domain conformance fixtures: JSON -> Kotlin source (Phase 2) ────────────
//
// Kotlin/Native has no `javaClass.getResourceAsStream`, so the vector fixtures
// cannot be loaded from a test-resources directory the way :app's JVM-only
// suite loaded them. They are therefore CODE-GENERATED into commonTest sources.
//
// The JSON under `app/src/test/resources/domain-vectors/` stays the single
// source of truth — it is exactly what `tools/domain-vectors/generate.ts`
// writes, and the Kotlin below is derived from it, never hand-maintained.
//
// THE 64 KB TRAP: a JVM class file stores every string literal as a
// CONSTANT_Utf8 entry whose length field is an unsigned 16-bit count of
// MODIFIED-UTF8 bytes — a hard 65535-byte ceiling per literal. commonTest also
// compiles for the Android/JVM target, so a naive `const val TAX_JSON =
// "<177 KB>"` fails to compile outright. Every fixture is therefore emitted as
// a `listOf(chunk, chunk, ...)` joined at runtime. Modified UTF-8 spends at
// most 3 bytes per Kotlin Char (a surrogate PAIR costs 6 bytes, i.e. 3 per
// char), so a 16000-char chunk can never exceed 48000 bytes.
//
// The split and the Kotlin escaping are not TRUSTED, they are CHECKED: the
// generator records the source file's character count and an FNV-1a 64 hash of
// it, and DomainVectorFixtureTest re-derives both from the REJOINED string on
// every target.

/**
 * The platform commit the vectors were generated from, pinned here as well as
 * inside MANIFEST.json so that dropping in a differently-pinned fixture set
 * fails the build instead of silently re-baselining the money.
 */
val pinnedPlatformCommit = "cb530f7e30a2ce3502e708f4b05711d1d0bde685"

/** The fixture files embedded into commonTest, by basename (without `.json`). */
val embeddedVectorFixtures = listOf(
    "MANIFEST",
    "holdings",
    "seriesStats",
    "settingsScope",
    "cashLedger",
    "tax",
    "serverTwrParity",
    "serverTwrParity.fixture",
)

abstract class GenerateVectorFixtures : DefaultTask() {

    /** `app/src/test/resources/domain-vectors` — the single source of truth. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val vectorsDir: DirectoryProperty

    @get:Input
    abstract val fixtureNames: ListProperty<String>

    @get:Input
    abstract val pinnedCommit: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Max characters per emitted string literal; see the 64 KB note above. */
    private val chunkChars = 16000

    @TaskAction
    fun generate() {
        val dir = vectorsDir.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val pkgDir = File(out, "at/bettertrack/app/domain/vectors")
        pkgDir.mkdirs()

        val manifest = File(dir, "MANIFEST.json").readText(Charsets.UTF_8)
        val pinned = Regex("\"pinnedAt\"\\s*:\\s*\"([^\"]+)\"").find(manifest)?.groupValues?.get(1)
            ?: throw GradleException("MANIFEST.json carries no pinnedAt")
        if (pinned != pinnedCommit.get()) {
            throw GradleException(
                "domain vectors are pinned at platform commit $pinned but shared/build.gradle.kts " +
                    "expects ${pinnedCommit.get()}. Re-pin deliberately — the vectors ARE the money contract.",
            )
        }

        val emitted = fixtureNames.get().map { name ->
            val file = File(dir, "$name.json")
            if (!file.isFile) throw GradleException("missing vector fixture: $file")
            val text = file.readText(Charsets.UTF_8)
            val obj = objectNameFor(name)
            File(pkgDir, "$obj.kt").writeText(renderFixture(obj, name, text), Charsets.UTF_8)
            Triple(name, obj, text)
        }
        File(pkgDir, "GeneratedVectorFixtures.kt")
            .writeText(renderRegistry(emitted, pinned), Charsets.UTF_8)
        logger.lifecycle(
            "generateVectorFixtures: embedded ${emitted.size} fixtures " +
                "(${emitted.sumOf { it.third.length }} chars) pinned at $pinned",
        )
    }

    /** `serverTwrParity.fixture` -> `FixtureServerTwrParityFixture`. */
    private fun objectNameFor(name: String): String =
        "Fixture" + name.split('.', '-', '_')
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    /**
     * Split on character boundaries, never between a high and a low surrogate —
     * a chunk that ended mid-pair would still rejoin correctly, but each half
     * would be an unpaired surrogate in its own literal, which some encoders
     * mangle. Cheap to avoid, so avoid it.
     */
    private fun chunk(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(i + chunkChars, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            out.add(text.substring(i, end))
            i = end
        }
        return if (out.isEmpty()) listOf("") else out
    }

    /**
     * Escape to PURE ASCII Kotlin source. Emitting `\uXXXX` for every non-ASCII
     * character removes source-encoding from the trust chain entirely: the
     * generated file is byte-identical under any charset the compiler might
     * assume. `$` must be escaped too — Kotlin string templates.
     */
    private fun escape(s: String): String = buildString(s.length + s.length / 2) {
        for (c in s) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '$' -> append("\\$")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c >= ' ' && c <= '~' -> append(c)
                else -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
        }
    }

    /** FNV-1a 64. Reproduced character-for-character in the commonTest harness. */
    private fun fnv1a64(s: String): Long {
        var h = -3750763034362895579L // 0xcbf29ce484222325
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h
    }

    /** `Long.MIN_VALUE` has no negative literal form in Kotlin. */
    private fun longLiteral(v: Long): String =
        if (v == Long.MIN_VALUE) "Long.MIN_VALUE" else "${v}L"

    private fun header(source: String) = buildString {
        appendLine("// GENERATED by :shared:generateVectorFixtures - DO NOT EDIT.")
        appendLine("// Source of truth: app/src/test/resources/domain-vectors/$source")
        appendLine("// Regenerate the JSON with tools/domain-vectors/generate.ts, never this file.")
        appendLine()
        appendLine("package at.bettertrack.app.domain.vectors")
        appendLine()
    }

    private fun renderFixture(obj: String, name: String, text: String): String = buildString {
        append(header("$name.json"))
        appendLine("@Suppress(\"MaxLineLength\", \"ktlint\")")
        appendLine("internal object $obj {")
        appendLine("    const val NAME: String = \"$name\"")
        appendLine("    const val CHARS: Int = ${text.length}")
        appendLine("    const val HASH: Long = ${longLiteral(fnv1a64(text))}")
        appendLine()
        appendLine("    // Each literal stays under the JVM's 65535-byte CONSTANT_Utf8 ceiling.")
        appendLine("    private val CHUNKS: List<String> = listOf(")
        chunk(text).forEach { appendLine("        \"${escape(it)}\",") }
        appendLine("    )")
        appendLine()
        appendLine("    val text: String by lazy { CHUNKS.joinToString(\"\") }")
        appendLine("}")
    }

    private fun renderRegistry(
        emitted: List<Triple<String, String, String>>,
        pinned: String,
    ): String = buildString {
        append(header("(all fixtures)"))
        appendLine("internal object GeneratedVectorFixtures {")
        appendLine("    const val PINNED_AT: String = \"$pinned\"")
        appendLine()
        appendLine("    val NAMES: List<String> = listOf(")
        emitted.forEach { appendLine("        \"${it.first}\",") }
        appendLine("    )")
        appendLine()
        appendLine("    fun text(name: String): String = when (name) {")
        emitted.forEach { appendLine("        \"${it.first}\" -> ${it.second}.text") }
        appendLine("        else -> error(\"no embedded fixture named '\$name'\")")
        appendLine("    }")
        appendLine()
        appendLine("    fun chars(name: String): Int = when (name) {")
        emitted.forEach { appendLine("        \"${it.first}\" -> ${it.second}.CHARS") }
        appendLine("        else -> error(\"no embedded fixture named '\$name'\")")
        appendLine("    }")
        appendLine()
        appendLine("    fun hash(name: String): Long = when (name) {")
        emitted.forEach { appendLine("        \"${it.first}\" -> ${it.second}.HASH") }
        appendLine("        else -> error(\"no embedded fixture named '\$name'\")")
        appendLine("    }")
        appendLine("}")
    }
}

val generateVectorFixtures = tasks.register<GenerateVectorFixtures>("generateVectorFixtures") {
    group = "build"
    description = "Embeds the domain conformance vector JSON into commonTest Kotlin sources."
    // A plain relative path, NOT `project(":app")`: :shared must not reach into
    // another project's model (project isolation / configuration cache).
    vectorsDir.set(layout.projectDirectory.dir("../app/src/test/resources/domain-vectors"))
    fixtureNames.set(embeddedVectorFixtures)
    pinnedCommit.set(pinnedPlatformCommit)
    outputDir.set(layout.buildDirectory.dir("generated/domainVectors/kotlin"))
}

// `flatMap` carries the task dependency, so the generator always runs before any
// commonTest compilation on any target.
kotlin.sourceSets.named("commonTest") {
    kotlin.srcDir(generateVectorFixtures.flatMap { it.outputDir })
}
