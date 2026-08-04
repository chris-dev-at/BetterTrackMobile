# S3 + S4 — Storage-backend abstraction, first-run wizard, and Drive-autonomous mode

**Architecture plan (2026-08-04, coordinator-commissioned).** Platform reference read at `origin/main` @ `439d0d5d`. Builder work packages W1–W6 at the bottom; every claim cites a path.

---

## 0. Executive summary

The single most important discovery: **the platform has already built a complete reference client for this exact feature** at `apps/web/src/user/vault/` in the platform monorepo (~40k lines incl. tests). It contains the envelope codec, the Argon2id/AES-GCM crypto with **published reproducible test vectors**, the Drive `appDataFolder` adapter, the CAS/merge engine, and the client money engine on top of `packages/domain`. S4 is therefore not a design problem — it is a **port with a conformance oracle**. Builders translate, never invent, and prove equality against fixtures rather than reason about correctness.

The second discovery that shapes S3: **the app's existing layering already has the seam**. `interface OpExecutor` (`app/src/main/java/at/bettertrack/app/sync/SyncEngine.kt:14`) and `interface PostSyncRefresher` (same file, :19) are exactly the write/read hooks a second storage backend needs, and the §7.1 doctrine — *screens read ONLY from Room, something else fills Room* — means a Drive backend can swap the **filler** without touching a single screen. `PortfolioRepository` is the only non-interface obstacle, and it is one class.

The architecture in one paragraph:

> Room's read-model tables stay the app's display contract, untouched. A new `PortfolioBackend` seam owns "how a refresh is satisfied and how a mutation is applied". `ServerPortfolioBackend` is today's `PortfolioRepository` network bodies moved verbatim. `VaultPortfolioBackend` derives the same tables from vault entities using the ported `packages/domain` engine plus a `MarketDataSource`, and persists the entity graph as a `BTVAULT1` envelope through a `DataHome` (Drive `appDataFolder` + local cache). A `StorageMode` (SERVER / DRIVE / BOTH) picked in a first-run wizard selects the backend; existing installs are silently grandfathered to SERVER and never see the wizard.

---

## 1. The storage-backend abstraction

### 1.1 What exists today (the constraint set)

| Layer | Today | Files |
|---|---|---|
| UI/VMs | Compose screens + VMs, read Room `Flow`s only | `ui/**` (12 files reference `PortfolioRepository`) |
| Repos | Concrete classes, network→Room | `data/repo/PortfolioRepository.kt` (793 lines), `MarketRepository.kt`, `WatchlistRepository.kt`, `ConglomerateRepository.kt`, `SocialRepository.kt`, `ChatRepository.kt`, `AlertsRepository.kt`, `NotificationRepository.kt`, `AccountRepository.kt` |
| Writes | UI → `SyncEngine.enqueue` → Room `sync_ops` → FIFO drain → `ApiOpExecutor` → `BtApi` → `afterDrain` refetch | `sync/SyncEngine.kt`, `sync/ApiOpExecutor.kt`, `sync/OpStore.kt`, `sync/SyncModels.kt` |
| Storage | Room v5, single-account, wiped on logout/account-switch | `data/db/BtDatabase.kt:36`, `data/db/AccountDataManager.kt:101` |
| Graph | Hand-wired lazy singletons | `di/AppGraph.kt` |
| Gate | `BtRoot` branches on `AuthState` | `ui/shell/BtRoot.kt:36-75` |

Crucially, **every number the UI renders is stored verbatim from the server** — `HoldingEntity` carries `marketValueEur`, `unrealizedPnlEur`, `dayChangePct`; `PortfolioTotals` is an `@Embedded` server payload; `PortfolioHistoryEntity.pointsJson`/`performanceJson` are opaque server JSON (`data/db/PortfolioEntities.kt:19-146`). That is the §7.1 rule and it is why the Drive backend can be a drop-in: it just has to *fill the same columns*.

### 1.2 The seam — recommended shape

**Do not introduce a `PortfolioStore` interface over the whole repository.** That would force type changes in 12 UI files and force the Drive implementation to reproduce server-shaped members (`loadMoreTransactions(cursor)`) that are meaningless locally. Instead:

**Keep `PortfolioRepository` concrete and injected into every VM exactly as today. Extract only its network half into a strategy.**

```kotlin
// data/storage/PortfolioBackend.kt  (new)
interface PortfolioBackend {
    // ── projection refreshes: fill the SAME Room read-model tables ──
    suspend fun refreshPortfolios(): BtResult<Unit>
    suspend fun refreshPortfolioDetail(portfolioId: String): BtResult<Unit>
    suspend fun refreshTransactions(portfolioId: String): BtResult<String?>   // cursor
    suspend fun loadMoreTransactions(portfolioId: String, cursor: String): BtResult<String?>
    suspend fun refreshHistory(portfolioId: String, range: HistoryRange): BtResult<Unit>
    suspend fun refreshCash(portfolioId: String): BtResult<Unit>
    suspend fun refreshCustomAssets(): BtResult<Unit>
    suspend fun refreshValuePoints(assetId: String): BtResult<Unit>

    // ── direct mutations (today "online-only per §7.2") ──
    suspend fun createPortfolio(name: String): BtResult<String>
    suspend fun renamePortfolio(id: String, name: String): BtResult<Unit>
    suspend fun archivePortfolio(id: String): BtResult<Unit>
    suspend fun restorePortfolio(id: String): BtResult<Unit>
    suspend fun deletePortfolio(id: String): BtResult<Unit>
    suspend fun createCashSource(portfolioId: String, name: String, type: String): BtResult<Unit>
    suspend fun updateCashSource(portfolioId: String, sourceId: String, name: String?, type: String?): BtResult<Unit>
    suspend fun archiveCashSource(portfolioId: String, sourceId: String): BtResult<Unit>
    suspend fun restoreCashSource(portfolioId: String, sourceId: String): BtResult<Unit>
    suspend fun createCustomAsset(name: String, category: String, smoothing: Boolean,
                                  initial: CustomAssetInitialPurchase?, portfolioId: String?): BtResult<String>
    suspend fun updateCustomAsset(id: String, name: String?, category: String?, smoothing: Boolean?): BtResult<Unit>
    suspend fun deleteCustomAsset(id: String): BtResult<Unit>
    suspend fun putValuePoints(assetId: String, points: List<ValuePointEntity>): BtResult<Unit>
    suspend fun updateTransaction(portfolioId: String, txId: String,
                                  body: UpdateTransactionRequest, idempotencyKey: String?): BtResult<Unit>
    suspend fun deleteTransaction(portfolioId: String, txId: String, idempotencyKey: String?): BtResult<Unit>
}
```

`ServerPortfolioBackend` is produced by **moving the method bodies of `PortfolioRepository` lines 127–632 verbatim** (they already only touch `api`, `db`, `json`, `now` — no other state). `PortfolioRepository` keeps and does not change: the Room `Flow` reads (:56–78), selection meta KV (:85–108), cash-coupling defaults (:116–122), `afterDrain` (:488), `purgePortfolioCache` (:635), `resolveSelection` (:751), `parseIsoMs` (:762) — and delegates the rest one line each.

**Why this shape:** zero UI/VM churn on a live shipped app (regression surface ≈ 0), shared Room-read/selection logic written once, and the swap point is exactly where the modes actually differ. The platform's binding seam names (`PortfolioStore`, `DataHome`, `MarketDataSource` — `docs/paranoid-design.md` §11, line 794) are honoured: `PortfolioBackend` *is* their `PortfolioStore` with the pure-read half already satisfied by Room.

**Write path.** `OpExecutor` is already an interface. Introduce:

```kotlin
class ModeRoutingOpExecutor(
    private val mode: () -> StorageMode,
    private val api: ApiOpExecutor,
    private val vault: VaultOpExecutor,
) : OpExecutor { override suspend fun execute(op: SyncOp) = when {
        mode().writesToServer -> api.execute(op)
        else -> vault.execute(op) } }
```

so `SyncEngine` is still constructed once in `AppGraph` (`di/AppGraph.kt:349`) and mode switching needs no process restart. Two precise companion changes:

- `SyncEngine`'s session gate (`sync/SyncEngine.kt:117`, `hasSession = { tokenManager.hasTokens() }` at `di/AppGraph.kt:357`) becomes `{ mode().isDriveOnly || tokenManager.hasTokens() }` — otherwise Drive-mode drains no-op.
- Ops must not execute against the wrong backend after a switch. Add a `backendTag` column to `sync_ops` (Room v5→v6 migration; migrations are real, never destructive — `data/db/BtDatabase.kt:18`) and have the router dispatch on the op's own tag, not the current mode. A mode change leaves already-enqueued server ops routed to the server.

**Semantic note (binding for builders):** in Drive mode the outbound queue stops being a *network* queue and becomes a *local-apply journal*. `VaultOpExecutor` applies the op to the vault entity graph and returns `Success` synchronously (no network). Domain-rule refusals (`OversellError`, insufficient cash) map to `ExecResult.Rejected` and therefore surface through the **existing** needs-attention / Pending-sync UI (`ui/sync/PendingSyncScreen.kt`) with no new UI. What replaces "pending sync" is a **vault-sync chip**: "Backed up to Drive · 2 min ago" / "Not signed in to Google — saved on this device" (mirrors the web's `ui/VaultSyncChip.tsx`).

### 1.3 Market data seam

`MarketRepository` (`data/repo/MarketRepository.kt`) mixes two concerns: market data (search/quote/history) and workboard-watchlist membership. Split the first out behind the platform's binding interface (`apps/web/src/lib/marketDataSource.ts`):

```kotlin
interface MarketDataSource {
    suspend fun quote(assetId: String): BtResult<MarketValue<AssetSnapshot>>
    suspend fun history(assetId: String, range: AssetRange): BtResult<MarketValue<List<PricePoint>>>
    suspend fun dailyCloses(assetId: String): BtResult<MarketValue<List<PricePoint>>>
    suspend fun search(query: String): BtResult<MarketValue<SearchOutcome>>
    suspend fun fx(from: String, to: String, date: String? = null): BtResult<MarketFxValue>
}
```
Implementations: `ApiMarketDataSource` (today's bodies, verbatim), `NoLivePricesMarketDataSource` (the honest default for Drive-only), and later, behind a flag, a direct-provider one (§6 risk 6). Add a Room `price_cache` table so valuation works offline and `dailyCloseSeries` has inputs.

### 1.4 Mode model, switching, attachment

```kotlin
// data/storage/StorageMode.kt
enum class StorageMode { UNSET, SERVER, DRIVE, BOTH }
```
persisted in **`DevicePrefs`-style plain SharedPreferences, not the Room meta KV** — because `data/prefs/DevicePrefs.kt:15-21` documents exactly the right rationale: it must survive logout and carry no secrets. The mode must survive `AccountDataManager.wipeAll()` (`data/db/AccountDataManager.kt:101`), which `clearAllTables()`.

**Switching / attaching:**

| Transition | Mechanism |
|---|---|
| SERVER → BOTH | Connect Drive + create passphrase → project current Room read models into vault entities → write envelope → **verified round trip** (read back, decrypt, compare `writeId`) → only then record the mode. Literal §5 sequence, `docs/paranoid-design.md:266`. |
| DRIVE → BOTH ("attach an account later") | OAuth login as today → user chooses **"Upload my Drive data to BetterTrack"** or **"Use the server data, keep Drive as backup"**. The upload is *free*: replay each vault transaction/cash movement as a normal `SyncOp` through the **existing** `ApiOpExecutor`, which already carries per-op `Idempotency-Key` (`sync/ApiOpExecutor.kt:77`) — so an interrupted import resumes exactly-once. This is the single biggest reason to keep the queue as the write path. |
| BOTH → DRIVE | Promotion: last mirror becomes the live vault (entities start taking real `rev`s). Server account simply logs out; server data is untouched. |
| BOTH → SERVER | Remove Drive medium: best-effort delete the appdata file, tell the user if it failed (it is their own ciphertext in their own Drive) — §5 rule 2. |
| Removing the last medium | Never offered (§5 rule 3). |

### 1.5 What "both" means — **recommendation: server-authoritative + client-encrypted Drive mirror. Not dual-write.**

Concretely: the server stays the calculator and the source of truth exactly as today; after each successful drain/refresh the app additionally projects its Room read models into a `BTVAULT1` envelope and CAS-writes it to Drive. The mirror is a **restore/promotion source, never a merge source, while the account is server-attached.**

Why this and not dual-write:

1. **Two authoritative domains cannot be merged at entity granularity.** The server *derives* money the client did not author: AT/DE `tax_withholding` movements on gains (seeded on the dev backend per PLATFORM_ASKS part 2), EUR conversion of native-currency price+fee at server FX (verified in Step 8 device E2E, `docs/TODO.md:64`), backdated `settleCashAsOfToday` cash-leg re-dating (`sync/SyncModels.kt:113`). A client that also authors those rows produces guaranteed, unresolvable divergence.
2. **The platform's own `{server, drive}` media set is a different thing.** In `packages/contracts/src/vault.ts:101-116` both media are *blind blob stores holding identical ciphertext* — that requires paranoid mode, and `vault:sync` over bearer is explicitly still in flight ("🔄 Gap in flight — `vault:sync`", `PLATFORM_ASKS.md` addendum). The app literally cannot implement the real media set today.
3. **The mirror is not throwaway** — it is precisely the artifact `docs/paranoid-design.md:266` §5 requires to promote to Drive-only, and precisely what an owner asking for "my data in both places" means in plain language.
4. **It is describable in one sentence**: *"Your data lives on BetterTrack. A private, encrypted copy is also kept in your Google Drive — only your passphrase can open it."*

Honest limitation for the UI and the board: the mirror carries only the entity kinds the app models (`portfolio`, `transaction`, `cashSource`, `cashMovement`, `customAsset`, `customAssetValue`), not the full `VAULT_ENTITY_KINDS` set — label it **"Portfolio backup"**, not "vault". When `vault:sync` ships, BOTH upgrades to the real media set (S5).

---

## 2. The Drive adapter

### 2.1 Encryption: **yes — BTVAULT1 from day one. Recommended, not optional.**

1. **Byte-compat is cheap now and impossible to retrofit.** A user's Drive vault must be readable by the web PWA. `apps/web/src/user/vault/drive/driveDataHome.ts` writes `appProperties {vaultVersion, formatVersion}` on a `.btenc` file in `appDataFolder`; a plaintext-JSON app would fork that format permanently.
2. **There is a published conformance oracle:** `apps/web/src/user/vault/vectors.fixture.json` publishes `passphrase → kekBase64`, fixed VK, fixed KDF salt, exact `headerBytesBase64` and `envelopeBase64` for the initial write, plus wrong-passphrase, tampered-envelope, `update-required`, passphrase-change, rotation, and rollback cases. A Kotlin port can be proven byte-identical before any UI exists.
3. **Cleartext contradicts the mandate** (`docs/paranoid-design.md` §3/§6).
4. Cost is bounded: AES-GCM via JCA; Keystore patterns exist (`data/applock/AppLockCrypto.kt`); the lock/idle UX exists (`data/applock/AppLockController.kt`); the strong-acknowledgment friction pattern exists (blocking public-link tick in `ui/social/AudiencePickerSheet.kt`). Only new dependency: Argon2id (e.g. `com.lambdapioneer.argon2kt`) — verify against `vectors.fixture.json.kekBase64` FIRST (W3 gate).

### 2.2 Envelope — exact byte contract

From `apps/web/src/user/vault/envelope.ts` + `crypto.ts`:

```
bytes = "BTVAULT1" (8 ASCII) ‖ uint32 BE headerLength ‖ headerJson(UTF-8) ‖ ciphertext
```
- Header fields exactly the 10 in `packages/contracts/src/vault.ts:531-542`; **unknown header fields are rejected** (`exactHeaderShape`) — reproduce this strict check.
- **AAD = the exact received header bytes** (not a re-serialization). Cross-client *reads* are member-order agnostic; only our own writes must be self-consistent.
- Content: `AES-256-GCM(VK, iv=12B fresh CSPRNG, plaintext = rawDeflate(utf8(JSON(document))), aad = headerBytes)`, tag 128-bit appended. `deflateSync` from `fflate` = **raw DEFLATE** → Kotlin `java.util.zip.Deflater(level, nowrap=true)`.
- KEK: `Argon2id(passphrase, salt, m=65536 KiB, t=3, p=1, hashLength=32)`; params validated strictly.
- `wrappedVk` = base64(`iv12 ‖ AES-GCM(KEK, iv12, VK, aad=utf8(keyId))`).
- Newer `formatVersion`/`schemaVersion` ⇒ **`update-required`, read-only, never destructive parsing**.
- Write `schemaVersion: 1` documents (`vaultDocumentV1Schema`, `vault.ts:1239`); read v1 **and** v2. Open question §6.2 re v2/`clientSecurity`.

### 2.3 appDataFolder layout

One file, per `driveDataHome.ts`:

- **Name:** `bettertrack-vault-<base64url(SHA-256("bettertrack-drive-vault-account-v1:" + accountId))>.btenc`, created with `parents: ["appDataFolder"]`.
- **Metadata:** `appProperties = { vaultVersion: "<n>", formatVersion: "1" }`; queried with `fields=id,name,size,modifiedTime,headRevisionId,appProperties`.
- **Upload:** `uploadType=multipart` (`multipart/related`: metadata JSON part + `application/octet-stream` envelope part).
- **Scope:** `https://www.googleapis.com/auth/drive.appdata` **only** — least privilege is binding. Never request broader Drive scope.
- **Duplicate replicas:** reference adapter scans up to 100 same-name objects and converges. v1 Android: implement *detection* + "use the highest readable version, never delete the others"; defer convergence but keep the method names.
- `accountId` for a server-less user is a contract gap → open question §6.1.

### 2.4 Document model from `VAULT_ENTITY_KINDS`

One generic Room table, not 26 typed ones:

```kotlin
@Entity(tableName = "vault_entities", primaryKeys = ["kind","id"])
data class VaultEntityRow(
    val kind: String,          // VAULT_ENTITY_KINDS member
    val id: String,            // uuidv7
    val rev: Int,              // monotonic per entity
    val editedAt: String,      // ISO instant
    val editedBy: String,      // deviceId uuid
    val deletedAt: String?,    // tombstone, retained ≥180 days
    val dataJson: String,      // opaque payload
)
@Entity(tableName = "vault_meta") // vaultVersion, deviceId, keyId, lastWriteId, mergeLog, driveFileId, lastSyncAtMs
```
mirroring `vaultEntityMetaSchema` (`vault.ts:604-611`). First Drive cut kinds: `portfolio`, `transaction`, `cashSource`, `cashMovement`, `customAsset`, `customAssetValue`, `portfolioSetting`. Reserved (opaque JSON tolerates them): `dividend`, `taxSetting`, `standingOrder*`, `import*`, `*Snapshot*`, `expense*`, `cash{Tag,Budget,Rule}*`.

**Room is the working store; Drive is the durable sync target.** Every write: local commit → recompute projections → enqueue a coalesced Drive push. Reads never wait on Drive.

### 2.5 Projection pipeline

```
vault_entities ──► domain engine (ported) ──► holdings / portfolios.totals /
   + price_cache        + MarketDataSource      cash_sources.balanceEur /
   + fx                                          portfolio_history.{points,performance}Json
                                                 ──► existing Compose screens, unchanged
```
Cache derived series keyed by `(vaultVersion, priceWatermark, range)`; any vault edit bumps `vaultVersion` (`docs/paranoid-design.md:764` §10).

### 2.6 CAS / conflict / offline

Port `apps/web/src/user/vault/merge.ts` (425 lines) literally. Binding rules (`docs/paranoid-design.md:215` §4):

1. Per entity `id`: higher `rev` wins → tie: later `editedAt` → tie: lexicographically higher `editedBy`. Total determinism.
2. **Tombstone vs concurrent edit ⇒ the edit wins.**
3. Merged `vaultVersion = max(parents)+1`; record in `mergeLog` (cap 20); CAS-push normally — rules are commutative and idempotent.
4. Whole-blob fallback on unreadable candidates: highest readable version wins, corrupt bytes kept locally for a restore picker, **never silently discarded**.

Drive's approximated CAS: hold observed `(vaultVersion, formatVersion, headRevisionId)`, re-`GET` immediately before `PATCH`, any movement ⇒ `conflict` → merge path, never force-overwrite.

**Offline:** local writes always succeed; push queue coalescing (one pending envelope). Google tokens ~1 h; re-mint may need a gesture — "Sign in to Google to sync", **never a silent stall**. Airplane-mode Drive-only must be fully functional.

### 2.7 Key handling

- VK: 32-byte CSPRNG, generated on device, never leaves it unwrapped.
- Passphrase **required**, explicitly *not* the login password (plain sentence in the wizard). Local strength check.
- Unwrapped VK cached in `EncryptedSharedPreferences` (`data/auth/SecureStore.kt:106` pattern) behind the **existing** app lock; "Lock vault" one tap; auto-lock reuses the existing PIN idle timer — one mental model.
- **Recovery kit**: `bettertrack-recovery-kit.txt` (VK base64 + `keyId` + `formatVersion` + instructions) via SAF/share-sheet, with mandatory "I have stored my recovery kit safely" tick.
- **Lost key ⇒ lost data** acknowledgment at the strong friction rung (reuse the blocking-checkbox pattern).
- Argon2id at m=64 MiB off the main thread with a visible spinner; measure on the Note20 (risk §6.7).

---

## 3. The domain-engine port

### 3.1 Inventory of `packages/domain` (at `origin/main` @ `439d0d5d`)

| Module | Lines | Exported surface (abbrev.) |
|---|---:|---|
| `src/holdings.ts` | 1092 | `reducePosition`, `deriveHoldings`(async), `dailyCloseSeries`, `valueOverTime`, `costBasisOverTime`, `netFlowsOverTime`, `timeWeightedReturn`, `rebasePerformance`, `OversellError`, `CurrencyConverter`, `QTY_EPSILON`, `VALUE_EPSILON` |
| `src/cashLedger.ts` | 893 | `CASH_MOVEMENT_KINDS`/`_SIGN`, `floorCents`, `cashBalance`, `applyCashMovement`, `projectCashLedger`, `spendableAsOf`, `cashBalanceOverTime`, `cashBalancesBySource`, `projectCashLedgerBySource`, `cashBySourceOverTime`, `pairedTransferMovements`, `setBalanceDelta`, `setBalanceMovement`, `netWorthSeries`, `isExternalCashMovement`, `externalCashFlowsForTwr`, `InsufficientCashError` |
| `src/tax.ts` | 1399 | AT/DE/FI/custom engines (deferred) |
| `src/seriesStats.ts` | 457 | `computeSeriesStats`, `toPerformanceSeries`, `deflateSeries`, `indexAveragePctPerYear`, `computeContributions`, `compareSeriesStats`, `COMPARISON_METRICS` |
| `src/settingsScope.ts` | 45 | `resolvePortfolioSetting` |
| **src total** | **3888** | |

Tests: 6598 lines, **329 `it()` cases** across 10 files. Package imports nothing external (one internal type import). **The port is mechanical by construction.**

Two golden fixtures on the web side, directly reusable:
- `apps/web/src/user/vault/engine/serverTwrParity.fixture.json` — TWR vectors *generated by the real server pipeline* (highest-value conformance asset).
- `apps/web/src/user/vault/engine/clientMoney.fixture.json` — fixed VK + real encrypted envelope: "client computes correct stats from encrypted fixture data", end to end.

### 3.2 Scope split — port core now, defer tax

| Port in S4 | ~TS lines | ~Kotlin est. | Tests in scope |
|---|---:|---:|---:|
| `holdings` + `seriesStats` + `settingsScope` | 1594 | ~1900 | 112 cases + half of `dailySnapshotSeries` |
| `cashLedger` | 893 | ~1050 | 97 cases + other half |
| **subtotal** | **2487** | **~2950** | **~216 cases** |
| `tax` (**deferred to S5/S6**) | 1399 | ~1700 | 107 cases + 1109-line fixture |

Ship Drive mode with `taxMode = 'none'` and a designed "Tax modes aren't available in Drive mode yet" state (`'none'` is a legitimate vault state, `tax.ts:60`).

### 3.3 Literal-translation rules (verbatim in every builder brief)

1. **Translate line-for-line; never restructure.** Preserve *operation order* in every arithmetic expression — IEEE-754 addition is not associative; vectors compare exact doubles.
2. TS `number` → Kotlin `Double`. No `BigDecimal`, no `Float`.
3. **Rounding traps.** `floorCents` exists twice (`cashLedger.ts:202`, `tax.ts:174`) — port both separately. JS `Math.round(-0.5) === -0`; Kotlin differs. Hand-check every `Math.round`/`trunc`/`toFixed` against a negative and exact-half vector.
4. **Iteration order.** JS objects/Maps are insertion-ordered *except* integer-like string keys (ascending numeric first). `tax.ts` keys by year — integer-like! Use `LinkedHashMap` and reproduce the original traversal order wherever it feeds a floating-point accumulation.
5. **Sorting.** `Array.prototype.sort` is stable and defaults to *string* comparison. Kotlin: a bare `.sort()` on non-strings becomes `sortedWith(compareBy { it.toString() })`.
6. `async` → `suspend`; rejections → typed exceptions matching TS error classes (`OversellError`, `InsufficientCashError`, `CashLedgerError`, `TaxComputationError`).
7. Epsilons are contract: `QTY_EPSILON = 1e-9`, `VALUE_EPSILON = 1e-9`, `CASH_EPSILON = 1e-9`, `CASH_DECIMALS = 2`, `QTY_STORAGE_QUANTUM = 1e-8`. Copy, never re-derive.
8. Dates: ISO strings stay strings; day extraction uses the same substring logic (`holdings.ts:459 dayOf`), not `LocalDate` re-parsing, unless the TS itself parses.

### 3.4 Test-vector extraction + replay

**The monorepo stays untouched.**

1. **Vendor a pinned snapshot** into `tools/domain-vectors/` — `packages/domain/src/**` + the two web fixtures, `PINNED_AT` = `439d0d5d`. Generator input, not shipped code; re-copy + diff detects drift.
2. **Generate machine-readable vectors** (vitest/tsx script) → `app/src/test/resources/domain-vectors/<module>.json`: `{fn, case, input, output, throws}`; FX rate tables emitted alongside inputs for a deterministic Kotlin fake.
3. **Replay in JUnit** — one data-driven runner per module, `assertEquals(expected, actual, 0.0)` — exact. Any legitimate non-exact case gets an explicit commented tolerance + board note.
4. **Hand-port** the non-pure-data cases: `vi.fn()` FX-coalescing assertions (call counts/args), error paths, `dailySnapshotSeries` interaction tests — Kotlin tests with a counting fake.
5. **Two end-to-end gates:** `serverTwrParity.fixture.json` through Kotlin TWR; `clientMoney.fixture.json` decrypt + derived numbers assert.

### 3.5 Already-local vs newly-ported

**Already computed locally (advisory-only, in `ui/`):** cash previews/validation (`ui/cash/CashLogic.kt`), trade total + oversell warning + backdated-cash check (`ui/portfolio/TransactionFormLogic.kt`), holding weights (`ui/portfolio/PortfolioFormat.kt`), donut folds, value-point merge (`ui/customassets/CustomAssetLogic.kt`), chart resampling, formatting.

**Newly ported (server is the calculator today):** `reducePosition` (avg-cost + oversell), `deriveHoldings` + `CurrencyConverter` (market values, PnL, day change), totals roll-up + `cashBalance`, `cashBalancesBySource`, `dailyCloseSeries` + `valueOverTime` + `netWorthSeries` (history points), `netFlowsOverTime` + `timeWeightedReturn` + `rebasePerformance` (performance), `projectCashLedger` + `spendableAsOf` + `pairedTransferMovements` + `setBalanceDelta/Movement` + `applyCashMovement` (ledger integrity), `computeSeriesStats` family, `resolvePortfolioSetting`, `costBasisOverTime`.

**Doctrine note:** the project rule "the server is the only calculator" is amended for Drive mode by explicit owner mandate (2026-08-04 holiday-sprint drop) + platform sanction (`docs/paranoid-design.md` §10): *in server mode the server is the only calculator; in Drive mode the ported audited engine is — never a hand-written one.* Update the §7.1 comments as builders touch them.

---

## 4. First-run wizard + migration/edge cases

### 4.1 Gate placement

`ui/shell/BtRoot.kt:36` switches on `AuthState`. Insert a **storage gate above it**:

```
StorageMode.UNSET                    → StorageSetupWizard
StorageMode.SERVER | BOTH            → existing AuthState branch (unchanged)
StorageMode.DRIVE                    → VaultUnlockGate → BtApp()
```
`VaultUnlockGate` mirrors the existing `AppLockScreen` gate (`BtRoot.kt:62-73`).

### 4.2 Wizard skeleton

1. **Where should your data live?** Three cards, plain language, each honestly naming what is **not** available (absent, not greyed): *BetterTrack account* / *Google Drive only* / *Both* ("BetterTrack is your main home; an encrypted backup also goes to your Drive").
2. **Server path** → today's `LoginScreen`, unchanged.
3. **Drive path** → a. Google Sign-In (`drive.appdata` only, least-privilege one-liner); b. vault passphrase + confirm ("This is a new secret, not your BetterTrack password"); c. recovery kit → mandatory "stored safely" tick; d. **blocking acknowledgment** "lost passphrase + lost kit = unrecoverable — not by BetterTrack, not by anyone"; e. first portfolio → envelope written → verified round trip.
4. **Both** → server login, then 3b–3e, then initial mirror write.
5. **Settings → "Where your data lives"**: add/remove medium, change passphrase, new recovery kit, lock vault, Drive status.
6. **Login screen gains** "Use without an account (Google Drive)".

### 4.3 Existing users never see the wizard

```
if (storageMode == UNSET &&
    (tokenManager.hasTokens() || secureStore.loadUser() != null || db.meta[KEY_OWNER] != null))
        storageMode = SERVER
```
(`KEY_OWNER` set for any DB that ever held a session — `data/db/AccountDataManager.kt:86`; catches logged-out users whose caches survive per `AuthRepository.kt:279-287`.) Going forward, set a persistent `everSignedIn` device flag on each successful login.

### 4.4 Edge cases (designed answers)

| Case | Answer |
|---|---|
| "Logout" in Drive mode | No logout — offer **"Lock vault"** / **"Disconnect Google Drive"** (local stays) / destructive **"Delete everything on this device"** behind type-to-confirm (reuse `ui/settings/DeleteAccountScreen.kt` pattern). |
| Logout in BOTH | `AuthRepository.logout()` wipes ALL today (`AuthRepository.kt:238` → `AccountDataManager.kt:103`) — **would destroy the vault**. Make mode-aware: wipe server caches + server-tagged ops only, keep `vault_entities`/`vault_meta`, demote to DRIVE. Unit-test this rule. |
| Google token expired | Writes land locally; chip "Sign in to Google to sync"; re-mint on next gesture. Never block a write. |
| Drive quota full (403 `storageQuotaExceeded`) | "Your Google Drive is full — changes saved on this device." Retry next push. |
| Drive file deleted externally | Local holds a vault ⇒ local authoritative; re-create at local `vaultVersion`. **Never** wipe local on absent-remote. |
| Two devices, same Google account | Full §4 merge; lost CAS race re-merges. |
| Newer `schemaVersion` | Read-only + "Update the app to open this vault". |
| Corrupt blob | Highest readable version wins; corrupt bytes retained for restore picker; Drive revisions = recovery net. |
| Wrong passphrase | "That passphrase doesn't open this vault." Local rate-limit (Argon2 cost is most of the defence). |
| Attach server later | §1.4 — import replays vault entities through the existing idempotent op queue. |
| Paranoid server account logs in | Global interceptor (S2a) + wizard routes to "use Drive mode here until vault sync ships". Needs `privacyMode` on `MeResponse` (ask #39.1). |
| App lock + vault lock | One timer, one mental model — vault follows the existing PIN idle-lock minutes. |

### 4.5 Feature availability by mode (drives "absent, not greyed")

| Surface | SERVER | BOTH | DRIVE |
|---|:--:|:--:|:--:|
| Portfolio / holdings / transactions / cash / custom assets | ✅ | ✅ | ✅ (local engine) |
| History graph + performance | ✅ | ✅ | ✅ (derived) |
| Search / asset pages / quotes | ✅ | ✅ | ⚠️ degraded (§6.6) |
| Watchlists | ✅ | ✅ | ⚠️ device-local only (§6.3) |
| Conglomerates / backtest / allocate | ✅ | ✅ | ❌ absent |
| Social / sharing / chat / friend groups | ✅ | ✅ | ❌ absent |
| Alerts / notifications / push | ✅ | ✅ | ❌ absent |
| Tax modes | ✅ | ✅ | ❌ absent (mode `none`) |
| App lock | ✅ | ✅ | ✅ (+ vault lock) |

---

## 5. Staging — six builder-sized work packages (app shippable after each)

### W1 — Storage-mode core + backend seam *(no behaviour change)*
`StorageMode` + `StorageModeStore` (SharedPreferences, survives logout) + grandfathering; extract `PortfolioBackend` with `ServerPortfolioBackend` = today's bodies **moved verbatim**; `MarketDataSource` + `ApiMarketDataSource`; `ModeRoutingOpExecutor`; mode-aware `hasSession`; Room v5→v6 (`sync_ops.backendTag`); AppGraph rewiring; mode-aware logout wipe rule; `SessionInitializer` → `sessionReady: StateFlow<Boolean>`.
**Done when:** mode hard-wired SERVER; all unit tests green + `StorageModeGateTest` + `LogoutWipeRuleTest`; on-device smoke vs dev backend shows zero behavioural difference; debug-menu row prints active mode + backend.

### W2 — Domain port A: holdings + seriesStats + settingsScope + vector harness
Vendor `tools/domain-vectors/` pinned @ `439d0d5d`; vector generator + JUnit replay runner; port the three modules literally into `at.bettertrack.app.domain`; hand-port converter-interaction + error cases.
**Done when:** every generated vector replays with **exact** `Double` equality; `serverTwrParity.fixture.json` passes through Kotlin TWR; port is pure Kotlin (JVM-testable, zero Android deps); ~112 replayed + hand-ported cases green.

### W3 — Domain port B: cashLedger + vault crypto/format
Port `cashLedger.ts` (+ its half of `dailySnapshotSeries`); `BTVAULT1` codec, Argon2id KEK, AES-GCM, raw-deflate, strict header validation, `update-required`; port `merge.ts`.
**Done when:** `vectors.fixture.json` replays **byte-identical** (kek first, then header/envelope, wrong-passphrase, tamper, update-required, passphrase-change, rotation, rollback); `clientMoney.fixture.json` decrypts; cashLedger vectors green; merge tests cover all four §4 rules.

### W4 — Drive medium + vault backend end-to-end *(debug-only mode flag)*
Google Sign-In (`drive.appdata` only) + `DriveDataHome` (find/create/multipart/appProperties/approx-CAS/duplicate detection) + `LocalDataHome`; Room v6→v7 (`vault_entities`, `vault_meta`, `price_cache`); `VaultOpExecutor`; `VaultPortfolioBackend` + projection writer; coalescing `VaultSyncWorker` + sync chip; key custody.
**Done when (device gates, real Google account):** create portfolio → buy → deposit → transfer → custom-asset point **all in airplane mode** with correct net worth from cached prices; network back → one Drive file with correct `appProperties`; force-stop + relaunch → identical state; **uninstall + reinstall → sign-in + passphrase → full restore**; two-device edit converges; wrong passphrase / quota-full / token-expired all render designed states. Flag off ⇒ release build unchanged.

### W5 — Wizard, mode switching, attachment, surface gating
Wizard + friction ladder + recovery kit; grandfathering verified on a real APK upgrade-in-place; login-screen affordance; Settings → "Where your data lives"; per-mode gating (absent, not greyed); BOTH mirror writer; Drive→server attach/import over the op queue.
**Done when:** fresh install → wizard, all three branches complete; in-place upgrade never shows the wizard and loses nothing; SERVER→BOTH and DRIVE→BOTH verified round trips; BOTH logout keeps the vault; all three modes smoke-pass.

### W6 — Market data in Drive mode + honest degradation
`NoLivePricesMarketDataSource` + designed empty/stale states; **manual price entry** for any asset (reuse value-point machinery); opt-in "Use BetterTrack for prices only" toggle ("BetterTrack would see which assets you look up, never what you own"); direct-provider adapter scaffolded but OFF behind a build flag pending the board/owner answer.
**Done when:** Drive-only with no prices shows correct cash + custom-asset + manually-priced net worth, never a €0 lie; toggle clearly reversible; status chips unambiguous.

*(Deferred, tracked under S5/S6: `tax.ts` port, standing-order materialization (UUIDv5 ids), Drive replica convergence, `vault:sync` server medium.)*

---

## 6. Risks and open questions (board asks filed as #40)

1. **Drive file-name selector for account-less clients** — `driveVaultFileName(accountId)` hashes the BetterTrack account id, which a Drive-only user lacks. Proposal: local `vaultAccountId` UUID in prefs + inside the document; on attach, keep it (Drive rename is a metadata PATCH) or record a mapping. Needs platform blessing so web can find app files.
2. **schemaVersion: may a Drive-only writer permanently write v1?** v2 mandates `clientSecurity`, contract-documented as "browser-only proof material" for server-medium retirement — meaningless for Drive-only. Mixed-client implications if web writes v2.
3. **No watchlist/conglomerate/alert kinds in `VAULT_ENTITY_KINDS`** — add a `watchlist` kind, or bless "watchlists are device-local in Drive-only mode"? (Interim: device-local, labelled.)
4. **`vault:sync` timing** — until it ships, BOTH = labelled "Portfolio backup" mirror, not the real media set. Acceptable? Will `vault:sync` change the envelope or only transport?
5. **`privacyMode` on `MeResponse`** — filed (#39.1); now also a wizard requirement (route paranoid accounts to Drive mode).
6. **Provider ToS for direct market data — biggest non-engineering risk.** Yahoo-direct is a platform-documented non-goal; Play distribution raises ToS/Data-Safety exposure. **Do not ship a direct-provider adapter by default.** Owner decision needed: licensed provider or owner-run price proxy?
7. **Argon2id cost on device** — measure m=64 MiB/t=3 on the Note20; params NOT negotiable (web compat); fallback is UX, not weaker KDF. Verify the Android Argon2 binding against `kekBase64` as W3's first commit.
8. **Google Cloud setup is an owner action:** OAuth client for `at.bettertrack.app` with release+debug SHA-1s in project `bettertrackapp-c6996`; Play Data-Safety update (Drive access, on-device encryption). Gates W4's device test.
9. **Room is single-account by construction** (`AccountDataManager.resolveOwnerAction`) — Drive adds a Google-account identity axis. Owner key = `drive:<googleAccountIdHash>` in DRIVE, `<btUserId>` in SERVER, BT id wins in BOTH. Unit-test alongside `OwnerGateTest`.
10. **Doctrine amendment** — "server is the only calculator" reworded per the owner mandate (see §3.5); §7.1 comments updated as touched.
11. **Snapshot drift** — `packages/domain` pinned @ `439d0d5d`; ask the platform chief to ping the board on any `packages/domain` change; regenerating vectors is one command, so drift = failing tests, not wrong money.
