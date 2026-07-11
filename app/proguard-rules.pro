# ═════════════════════════════════════════════════════════════════════════════
# BetterTrack App — R8 keep rules (Step 19)
#
# Deliberately MINIMAL. Every major library here ships its own consumer R8 rules
# inside its AAR/JAR (META-INF/proguard or *.pro), so we do NOT re-declare them:
#   • kotlinx.serialization 1.9  → generic @Serializable serializer rules
#   • Retrofit 2.11 / OkHttp 4.12 → interface + platform rules
#   • Room 2.8, WorkManager 2.11 → generated-code + worker rules
#   • Firebase Messaging 34 / Compose / Navigation → their own rules
# We add ONLY what OUR code needs on top of those, plus a couple of well-known
# safety nets. Verified on-device against prod (login, tx incl. uncovered sell,
# cash, chat/WS, alerts, notifications, settings) with logcat clean of
# ClassNotFoundException / MissingFieldException / SerializationException.
# ═════════════════════════════════════════════════════════════════════════════

# ── kotlinx.serialization (belt-and-suspenders over the bundled rules) ────────
# A decode crash in a release build is the single highest-risk R8 failure for
# this app, so we explicitly protect OUR OWN serialized model set. It is small
# (API DTOs, sync-queue payloads AND the type-safe navigation routes — Navigation
# Compose serializes those; stripping them silently breaks navigation), and it
# is decoded from the network, so keeping it whole is the safe, cheap call.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,InnerClasses

# Keep every @Serializable type's fields + its generated companion/serializer.
-keepclassmembers @kotlinx.serialization.Serializable class at.bettertrack.app.** {
    <fields>;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the synthetic $$serializer classes (and the descriptor classes they name).
-keep,includedescriptorclasses class at.bettertrack.app.**$$serializer { *; }
# Serializable objects expose a static INSTANCE the serializer reads.
-keepclassmembers @kotlinx.serialization.Serializable class at.bettertrack.app.** {
    public static ** INSTANCE;
}

# ── WorkManager ──────────────────────────────────────────────────────────────
# The sync worker is instantiated reflectively by the default WorkerFactory;
# keep its class + the (Context, WorkerParameters) constructor.
-keep class at.bettertrack.app.sync.SyncWorker { <init>(...); }

# ── Optional transitive deps we never ship (OkHttp/BouncyCastle probes) ───────
# These are compile-time optional in OkHttp; without them R8 emits missing-class
# warnings that would fail the build though the code paths are never reached.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
