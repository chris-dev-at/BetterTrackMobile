package at.bettertrack.app.vault.pv

import at.bettertrack.app.vault.v2.VaultPassphraseCheck
import at.bettertrack.app.vault.v2.checkVaultPassphrase
import at.bettertrack.app.vault.v2.normalizeVaultPassphrase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The §13 QR seed-phrase transfer payload — **the one spec the web renderer and
 * this scanner are both built against**.
 *
 * ```
 * btvault1:m=<words>&v=<vaultId>[&n=<name>][&f=<fingerprint>]
 * ```
 *
 * ## Shape, and why it is this shape
 *
 * - **The version is the scheme prefix.** `btvault1:` is the version marker. An
 *   unknown prefix is REJECTED with an "update the app" reason and never
 *   best-effort parsed ([VaultQrRejection.UNSUPPORTED_VERSION]); inside
 *   `btvault1:` an unknown query key is IGNORED, which is what makes the format
 *   additively extensible; a missing required key is a reject.
 * - **`scheme:query`, deliberately no `//` authority.** Platform URL parsers
 *   disagree about authorities under a custom scheme, so everything after the
 *   first `:` is exactly one `application/x-www-form-urlencoded` query string,
 *   which every platform parses identically. This file therefore does its own
 *   form decoding rather than reaching for `java.net.URLDecoder`: Android's
 *   `URLDecoder` substitutes U+FFFD for a malformed escape while OpenJDK's
 *   throws, so the same bytes would behave differently on the device and in the
 *   unit test that is supposed to prove the device's behaviour.
 * - **`m` carries the WORDS, not entropy.** The BIP-39 checksum already rides in
 *   the words (the last word carries the 4 checksum bits), so a scanner
 *   validates integrity against the standard wordlist with no extra field; the
 *   words are what the user wrote down, so a generic QR reader shows a
 *   human-recoverable payload; and there is no entropy-encoding / endianness /
 *   checksum-recompute step where two implementations can silently diverge.
 * - **`v`** is the vault UUID, lowercase hyphenated.
 * - **`n`** is an optional display-name hint, ≤ 64 characters.
 * - **`f`** is the optional vault key fingerprint.
 *
 * ## The `f` bullet is a known spec defect — read this before "fixing" it
 *
 * §13's `f` bullet says the receiver can "pre-check the words against the
 * intended vault before any network fetch". **It cannot.** The fingerprint is
 * `base64url(HKDF-SHA256(K_c, "bettertrack-vault-fingerprint-v1"))[0..16]`, and
 * `K_c` is a random content key that is only recoverable by unwrapping
 * `keySlots[0]` out of the vault's **header document** — which is a network
 * fetch. Words alone derive `K_wrap`, never `K_c`, so there is nothing to
 * compare `f` against offline. The platform has acknowledged the wording defect;
 * the authoritative flow is the receiver's **fetch-then-compare**:
 *
 * ```
 * scan → offline validation (below) → fetch the vault header doc
 *      → prove the words decrypt it → compare f when present
 *      → only then persist the phrase to the endpoint keystore
 * ```
 *
 * `f` is therefore carried verbatim and used as a *confirmation* after the
 * fetch, never as a gate before it. A mis-scan can never store dead words
 * because nothing is stored until the decryption proof succeeds.
 *
 * ## Offline validation is exactly four things
 *
 * 1. the `btvault1:` prefix,
 * 2. both required keys (`m`, `v`) present,
 * 3. the BIP-39 checksum of `m` (via `vault.v2`'s audited
 *    [checkVaultPassphrase] — the wordlist is digest-pinned there and is not
 *    duplicated here),
 * 4. the UUID shape of `v`.
 *
 * Nothing else can be decided without the network, and the UI says so.
 *
 * ## Collision with the retired v2 QR
 *
 * `at.bettertrack.app.vault.v2.VaultQr` uses the SAME `btvault1:` prefix for a
 * completely different, code-wrapped JSON body. Board ask #83 is making the
 * platform discriminate the two properly; until that is answered this parser
 * tells them apart by body shape — a body that parses as JSON is a v2 code and
 * is rejected as [VaultQrRejection.LEGACY_CODE] ("this code is from an older
 * version"), never mistaken for a malformed v1 payload and never best-effort
 * parsed. The v2 file is deliberately left untouched.
 *
 * ## Threat-model note on the reasons
 *
 * [VaultQrRejection] is granular so tests and the four-point status display can
 * be precise, but the UI must NOT be: everything that could reveal something
 * about the phrase (a wrong checksum, a truncated payload, a corrupt escape)
 * collapses into one generic failure message — see
 * `at.bettertrack.app.ui.vault.qr.vaultQrRejectionMessage`. A shoulder-surfer
 * must not learn from the screen whether the code they just watched fail was
 * nearly right.
 */
object VaultQrContract {

    /** The version marker. Everything after it is one form-urlencoded query. */
    const val PREFIX: String = "btvault1:"

    /** Required — the 12 words, lowercase, NFKD, single-space separated. */
    const val KEY_MNEMONIC: String = "m"

    /** Required — the vault UUID, lowercase hyphenated. */
    const val KEY_VAULT_ID: String = "v"

    /** Optional — display-name hint. */
    const val KEY_NAME: String = "n"

    /** Optional — the vault key fingerprint; verifiable only AFTER the header fetch. */
    const val KEY_FINGERPRINT: String = "f"

    /**
     * §13's cap on `n`, measured in UTF-16 code units.
     *
     * Code units and not code points on purpose: the web renderer's natural
     * check is JavaScript's `String.prototype.length`, which counts UTF-16 code
     * units, and a sender and a receiver that disagree about what "64
     * characters" means is exactly the class of silent divergence this contract
     * exists to remove.
     */
    const val MAX_NAME_LENGTH: Int = 64

    /**
     * §13's display TTL in seconds. The sender blanks the code after this and
     * offers a manual re-show. (The 120 s window of the retired v2 handoff is
     * dead; it belonged to a code-wrapped payload with a different threat model.)
     */
    const val DISPLAY_TTL_SECONDS: Int = 60

    /** Lowercase hyphenated UUID. See [buildVaultQrPayload] for why case is strict. */
    val VAULT_ID_SHAPE: Regex =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** `btvault<n>:` for any decimal n — used only to tell "wrong version" from "not ours". */
    internal val SCHEME_FAMILY: Regex = Regex("^btvault[0-9]+:")
}

/**
 * A parsed, offline-validated transfer payload.
 *
 * [mnemonic] is always the NORMALIZED phrase (NFKD, lowercase, single spaces) —
 * the same bytes the KDF will see — so no call site has to remember to normalize
 * again. [name] and [fingerprint] are `null` when absent or blank.
 */
data class VaultQrPayload(
    val mnemonic: String,
    val vaultId: String,
    val name: String? = null,
    val fingerprint: String? = null,
)

/**
 * Why a scanned string was refused. Granular for tests and diagnostics; the UI
 * deliberately collapses most of these into one message (see the class KDoc).
 */
enum class VaultQrRejection {
    /** Not a BetterTrack transfer code at all (a URL, a Wi-Fi code, random text). */
    NOT_A_VAULT_CODE,

    /** `btvault2:` or later — a newer app made this. Never best-effort parsed. */
    UNSUPPORTED_VERSION,

    /** `btvault1:` carrying the retired v2 JSON body (prefix collision, board ask #83). */
    LEGACY_CODE,

    /** The query could not be decoded (bad percent escape, unparseable body). */
    MALFORMED,

    /** `m` or `v` missing or empty. */
    MISSING_REQUIRED_KEY,

    /** `m` is not 12 valid wordlist words with a valid BIP-39 checksum. */
    PHRASE_INVALID,

    /** `v` is not a lowercase hyphenated UUID. */
    VAULT_ID_INVALID,

    /** `n` exceeds [VaultQrContract.MAX_NAME_LENGTH]. */
    NAME_TOO_LONG,
}

/** The result of [parseVaultQrPayload]. Parsing NEVER throws — a camera feeds it arbitrary bytes. */
sealed interface VaultQrParseResult {
    data class Ok(val payload: VaultQrPayload) : VaultQrParseResult
    data class Failed(val reason: VaultQrRejection) : VaultQrParseResult
}

/**
 * Build the string a sender encodes into a QR.
 *
 * Unlike [parseVaultQrPayload] this **throws** on bad input, and the asymmetry
 * is deliberate: parse input comes from a camera and must degrade into a state,
 * while build input comes from our own unlocked vault and a bad value there is a
 * programming error that must not be encoded into a code someone then scans.
 *
 * The vault id is required to be lowercase because it is not just an identifier:
 * it is domain-separation input to the key derivation
 * (`HKDF(seed, "bettertrack-vault-wrap-v1:" + vaultId)`). Accepting an uppercase
 * id and quietly lowercasing it would derive different keys on the two sides of
 * the transfer, which is the silent-divergence failure this spec exists to
 * prevent — so the case is a hard contract, not a formatting preference.
 *
 * @param mnemonic the 12 words; normalized here, so any spacing/casing the
 *   caller holds is fine, but the words themselves must pass the BIP-39 check.
 * @param name optional display hint; blank is treated as absent.
 * @param fingerprint optional `key_fingerprint`; carried verbatim.
 */
fun buildVaultQrPayload(
    mnemonic: String,
    vaultId: String,
    name: String? = null,
    fingerprint: String? = null,
): String {
    val normalized = when (val check = checkVaultPassphrase(mnemonic)) {
        is VaultPassphraseCheck.Valid -> check.passphrase
        is VaultPassphraseCheck.Invalid ->
            throw IllegalArgumentException("The transfer QR needs 12 valid BIP-39 words.")
    }
    require(VaultQrContract.VAULT_ID_SHAPE.matches(vaultId)) {
        "The transfer QR needs a lowercase hyphenated vault UUID."
    }
    val trimmedName = name?.trim()?.takeIf { it.isNotEmpty() }
    require(trimmedName == null || trimmedName.length <= VaultQrContract.MAX_NAME_LENGTH) {
        "A vault name hint is at most ${VaultQrContract.MAX_NAME_LENGTH} characters."
    }
    val trimmedFingerprint = fingerprint?.trim()?.takeIf { it.isNotEmpty() }

    return buildString {
        append(VaultQrContract.PREFIX)
        appendPair(VaultQrContract.KEY_MNEMONIC, normalized)
        append('&')
        appendPair(VaultQrContract.KEY_VAULT_ID, vaultId)
        if (trimmedName != null) {
            append('&')
            appendPair(VaultQrContract.KEY_NAME, trimmedName)
        }
        if (trimmedFingerprint != null) {
            append('&')
            appendPair(VaultQrContract.KEY_FINGERPRINT, trimmedFingerprint)
        }
    }
}

/**
 * Parse a scanned string. Never throws; every failure is a
 * [VaultQrParseResult.Failed] with a reason.
 *
 * A success means the four offline checks passed and nothing more — in
 * particular it does NOT mean these words open that vault. That proof is the
 * receiver's fetch-then-compare step ([VaultHeaderProbe]).
 */
fun parseVaultQrPayload(value: String): VaultQrParseResult {
    if (!value.startsWith(VaultQrContract.PREFIX)) {
        // "btvault7:" is a code from a future app → say "update"; anything else
        // is simply not ours → say so, rather than blaming the app version.
        return VaultQrParseResult.Failed(
            if (VaultQrContract.SCHEME_FAMILY.containsMatchIn(value)) {
                VaultQrRejection.UNSUPPORTED_VERSION
            } else {
                VaultQrRejection.NOT_A_VAULT_CODE
            },
        )
    }

    val body = value.substring(VaultQrContract.PREFIX.length)

    // Prefix collision with the retired v2 handoff (board ask #83): its body is
    // a JSON object. Discriminate by body shape and name the version, so the
    // user is told to make a NEW code instead of rescanning a dead one.
    if (body.trimStart().startsWith("{")) {
        return VaultQrParseResult.Failed(
            if (parsesAsJsonObject(body)) VaultQrRejection.LEGACY_CODE else VaultQrRejection.MALFORMED,
        )
    }

    val fields = decodeFormQuery(body) ?: return VaultQrParseResult.Failed(VaultQrRejection.MALFORMED)

    // Unknown keys are ignored on purpose — that is the format's forward
    // compatibility, and the reason `f` could be added after the fact.
    val rawMnemonic = fields[VaultQrContract.KEY_MNEMONIC]
    val rawVaultId = fields[VaultQrContract.KEY_VAULT_ID]
    if (rawMnemonic.isNullOrBlank() || rawVaultId.isNullOrBlank()) {
        return VaultQrParseResult.Failed(VaultQrRejection.MISSING_REQUIRED_KEY)
    }

    val checked = checkVaultPassphrase(rawMnemonic)
    if (checked !is VaultPassphraseCheck.Valid) {
        // One reason for every phrase defect: word count, an off-list word and a
        // failed checksum must be indistinguishable downstream.
        return VaultQrParseResult.Failed(VaultQrRejection.PHRASE_INVALID)
    }
    // Guard the normalization itself: the phrase that leaves this function must
    // be byte-identical to what the KDF will hash, whatever the sender emitted.
    val mnemonic = normalizeVaultPassphrase(checked.passphrase)

    if (!VaultQrContract.VAULT_ID_SHAPE.matches(rawVaultId)) {
        return VaultQrParseResult.Failed(VaultQrRejection.VAULT_ID_INVALID)
    }

    val name = fields[VaultQrContract.KEY_NAME]?.takeIf { it.isNotBlank() }
    if (name != null && name.length > VaultQrContract.MAX_NAME_LENGTH) {
        return VaultQrParseResult.Failed(VaultQrRejection.NAME_TOO_LONG)
    }

    val fingerprint = fields[VaultQrContract.KEY_FINGERPRINT]?.takeIf { it.isNotBlank() }

    return VaultQrParseResult.Ok(
        VaultQrPayload(
            mnemonic = mnemonic,
            vaultId = rawVaultId,
            name = name?.trim(),
            fingerprint = fingerprint,
        ),
    )
}

// ── form-urlencoded, implemented here so both sides agree byte for byte ─────

/**
 * The `application/x-www-form-urlencoded` unreserved set: `A-Z a-z 0-9 * - . _`.
 *
 * This is the WHATWG urlencoded serializer's set — i.e. exactly what a web
 * renderer's `URLSearchParams.toString()` produces — so a payload built here and
 * a payload built there are the same string, not merely equivalent ones. (Java's
 * `URLEncoder` differs: it leaves `*` too but is documented only "loosely", and
 * its `+` handling is what we want but its decoder is not — see the class KDoc.)
 */
private fun isFormUnreserved(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '*' || c == '-' || c == '.' || c == '_'

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun StringBuilder.appendPair(key: String, value: String) {
    appendFormEncoded(key)
    append('=')
    appendFormEncoded(value)
}

private fun StringBuilder.appendFormEncoded(value: String) {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = (byte.toInt() and 0xff).toChar()
        when {
            isFormUnreserved(c) -> append(c)
            // Space becomes '+', the canonical form-urlencoded spelling. It is
            // also the shortest: eleven separators in a 12-word phrase are
            // eleven bytes instead of thirty-three, which matters for a code
            // that has to stay comfortably scannable.
            c == ' ' -> append('+')
            else -> {
                append('%')
                append(HEX_DIGITS[(byte.toInt() and 0xff) shr 4])
                append(HEX_DIGITS[byte.toInt() and 0x0f])
            }
        }
    }
}

/**
 * Decode one form-urlencoded query into its fields, or `null` when it is not
 * decodable at all.
 *
 * Duplicate keys: the FIRST wins, matching `URLSearchParams.get()`. Empty
 * segments (`a=1&&b=2`, a trailing `&`) are skipped rather than treated as an
 * error — every mainstream parser does that, and a stricter reading would reject
 * codes that decode identically everywhere else.
 */
private fun decodeFormQuery(body: String): Map<String, String>? {
    val out = LinkedHashMap<String, String>()
    for (segment in body.split('&')) {
        if (segment.isEmpty()) continue
        val eq = segment.indexOf('=')
        val rawKey = if (eq < 0) segment else segment.substring(0, eq)
        val rawValue = if (eq < 0) "" else segment.substring(eq + 1)
        val key = decodeFormComponent(rawKey) ?: return null
        val value = decodeFormComponent(rawValue) ?: return null
        if (key.isEmpty()) continue
        out.putIfAbsent(key, value)
    }
    return out
}

/**
 * `+` → space, `%XX` → byte, everything else verbatim; the collected bytes are
 * then read as UTF-8. Returns `null` on a truncated or non-hex escape instead of
 * throwing or silently substituting a replacement character.
 */
private fun decodeFormComponent(value: String): String? {
    if (value.isEmpty()) return ""
    val bytes = ByteArray(value.length)
    var out = 0
    var i = 0
    while (i < value.length) {
        when (val c = value[i]) {
            '+' -> {
                bytes[out++] = ' '.code.toByte()
                i++
            }
            '%' -> {
                if (i + 2 >= value.length) return null
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes[out++] = ((hi shl 4) or lo).toByte()
                i += 3
            }
            else -> {
                // A char outside Latin-1 can only appear if the QR carried raw
                // UTF-8 text unescaped; encode it back to its own bytes so the
                // UTF-8 read below is still correct.
                if (c.code < 0x80) {
                    bytes[out++] = c.code.toByte()
                    i++
                } else {
                    val encoded = c.toString().toByteArray(Charsets.UTF_8)
                    if (out + encoded.size > bytes.size) {
                        return decodeFormComponentSlow(value)
                    }
                    encoded.copyInto(bytes, out)
                    out += encoded.size
                    i++
                }
            }
        }
    }
    return String(bytes, 0, out, Charsets.UTF_8)
}

/** Multi-byte overflow fallback — same rules, growable buffer. */
private fun decodeFormComponentSlow(value: String): String? {
    val bytes = java.io.ByteArrayOutputStream(value.length * 2)
    var i = 0
    while (i < value.length) {
        when (val c = value[i]) {
            '+' -> { bytes.write(' '.code); i++ }
            '%' -> {
                if (i + 2 >= value.length) return null
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write((hi shl 4) or lo)
                i += 3
            }
            else -> { bytes.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

/** Lenient JSON probe — used ONLY to name the v2 prefix collision, never to parse it. */
private fun parsesAsJsonObject(body: String): Boolean = runCatching {
    Json.parseToJsonElement(body) is JsonObject
}.getOrDefault(false)
