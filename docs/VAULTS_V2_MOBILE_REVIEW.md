# Mobile review — Vaults v2 design contract (platform PR #1173)

**Reviewer:** BetterTrack mobile
**Date:** 2026-08-08
**Contract reviewed:** `docs/VAULTS_V2_DESIGN.md` @ `a6cb247b` on branch `pr-1173` (121 lines)
**Reviewed against:** shipped app code at `main` @ `70693f0` — W-arc storage engine, BTVAULT1 crypto, Drive backend (W4), vault wizard (W5), server-vault mode (S5), ParanoidGate, sync engine vault mode, Room schema v10.

**Verdict: APPROVE-WITH-NEEDS — with two hard objections (O1, O2) that must be resolved in the contract text before P4 is scoped, and one design objection (O4) we ask the platform chief to rule on.**

The product shape is right and we want it. The crypto substrate reuse is correct and costs us nothing. But the contract as written is 100% prose — and three of its load-bearing mechanisms (per-portfolio CAS, the client-side v1→v2 split, the QR handoff) are specified at a level that cannot be implemented without inventing semantics that carry silent-data-loss risk. Paranoid mode's entire value proposition is that the user's data is safe; the places this contract is thin are exactly the places where thinness becomes lost money.

---

## 0. Scope check — what actually exists on `pr-1173`

Stated up front because it changes what "review" means here.

```
git show --stat pr-1173
a6cb247b docs: Vaults v2 design contract — per-portfolio paranoid as multi-vault wallets
 docs/VAULTS_V2_DESIGN.md | 121 +++++++++++++++++++++++++++++++++++++++++++++++
 1 file changed, 121 insertions(+)

git grep -l "keySlots\|kdfSalt" pr-1173
pr-1173:docs/VAULTS_V2_DESIGN.md      # ← the only file in the repo containing either token
```

Zero code, zero schema, zero SQL, zero tests, zero vectors. Every v2 artifact mobile would build against — `{vaultId}` routes, `keySlots[]`, per-portfolio CAS, the `btvault1:` QR grammar, the 60 s TTL, the 12-word list, the `vaults`/`vault_docs` tables, the migration — is prose.

The v1 substrate underneath is genuinely solid and test-pinned, and our existing `vault:sync` path (`GET`/`PUT /api/v1/vault`, `If-Match`/`If-None-Match`, `412 VAULT_PRECONDITION_FAILED`) is real and safe to keep building on. Nothing in this review disputes the substrate. Everything in this review is about the delta.

Consequence: this is a **design review, not an integration review**, and every item in §2 (Needs) is a precondition, not a nice-to-have. We cannot begin P4 against prose.

---

## 1. Blockers and objections

### O1 (BLOCKER) — Per-portfolio CAS blobs have no transactional story, and our engine has cross-portfolio state that has no portfolio to live in

The contract says (§2, line 45): *"Per-portfolio content blobs: `AES-GCM(K_c, portfolioDoc)`, individually CAS-versioned."* It says nothing about what happens when one logical operation spans two blobs. Four concrete facts from our shipped code make that gap a data-loss hazard rather than a design detail.

**1a. Two of our seven authored entity kinds have no portfolio home at all.**

`customAsset` is the *asset identity* record — despite its name it is where a Drive vault keeps **every asset it references**, not only user-invented ones. Its payload has no `portfolioId`:

`app/src/main/java/at/bettertrack/app/vault/VaultEntityGraph.kt:302-324` — members are `providerId, providerRef, ownerId, type, symbol, name, exchange, currency, meta, searchText`. No portfolio.
`app/src/main/java/at/bettertrack/app/vault/VaultEntityGraph.kt:327-333` — `customAssetValue` is `assetId, date, value`. No portfolio.

They are read globally by every projection, deliberately, while transactions/movements/sources beside them *are* filtered by portfolio:

- `app/src/main/java/at/bettertrack/app/data/storage/VaultProjection.kt:137` — `val assetRows = graph.live(VaultKinds.CUSTOM_ASSET)` (unfiltered)
- `app/src/main/java/at/bettertrack/app/data/storage/VaultProjection.kt:140` — `…TRANSACTION).filter { it.text("portfolioId") == portfolioId }` (filtered)
- `app/src/main/java/at/bettertrack/app/data/storage/VaultProjection.kt:324` — value points, unfiltered
- `app/src/main/java/at/bettertrack/app/data/storage/VaultPortfolioBackend.kt:519` — `buildMarketInputs` reads all `CUSTOM_ASSET_VALUE` rows globally to build the price series **that values every portfolio**

And a write in portfolio A mints the shared record that portfolio B later reuses — `app/src/main/java/at/bettertrack/app/sync/VaultOpExecutor.kt:384-405` (`ensureAsset`): a buy of AAPL in A creates the account-global `customAsset`; a later buy in B finds it and inherits its `currency`.

Split per portfolio, that becomes: **two blobs independently mint the same `assetId` with possibly different `currency`/`symbol`, in two separate CAS lineages that no merge will ever reconcile.** The contract's §2 portfolio-doc kind list ("transactions, dividends, cash sources/movements, portfolio settings, custom assets, standing orders") assigns `custom assets` to the portfolio doc — which is precisely the assignment that breaks. This is the single clearest split-breaker and it is not addressed anywhere in the document.

**1b. A transfer between two portfolios in one vault = two blobs, two CAS versions, no coordinator.**

Today a transfer is atomic because both legs are created inside one `VaultStore.mutate` block — one lock, one version bump:

`app/src/main/java/at/bettertrack/app/sync/VaultOpExecutor.kt:314-352` — one `transferId = context.newId()`, then two `graph.create(kind = VaultKinds.CASH_MOVEMENT, …)` calls carrying `transferId` and reciprocal `counterpartSourceId`.
`app/src/main/java/at/bettertrack/app/vault/VaultStore.kt:94-107` — the single-mutex read-modify-write, and the class invariant at `:19-31`: *"Every mutation bumps `vaultVersion`, atomically, under one lock. That counter is the compare-and-swap token every medium keys off … A write that changed entities without bumping it would make two different documents share a CAS token — after which a Drive push can overwrite another device's work while every check passes."*

Worse, nothing today enforces that a transfer's two sources belong to the op's portfolio. `applyTransfer` never checks it, and source lookup is document-wide: `app/src/main/java/at/bettertrack/app/sync/VaultOpExecutor.kt:259-261` uses `graph.find(VaultKinds.CASH_SOURCE, it)`, which is a whole-document lookup (`VaultEntityGraph.kt:74`), not scoped to `op.portfolioId`. `CashTransferOpPayload` (`app/src/main/java/at/bettertrack/app/sync/SyncModels.kt:174-180`) carries only `fromSourceId`/`toSourceId` — no portfolio ids. So a cross-portfolio transfer is already *expressible* in our op format.

Under per-portfolio blobs that becomes a **two-blob distributed transaction with no coordinator**, and there is no cross-blob atomicity primitive anywhere in our codebase or on any medium we ship. On Drive it is strictly worse: Drive has no multi-file transaction at all, and our Drive CAS is already an *approximation* (observe → re-`GET` immediately before `PATCH` → upload → re-list → **download and byte-compare**): `app/src/main/java/at/bettertrack/app/vault/drive/DriveDataHome.kt:132-169` and `:378-384`. Two independent approximate-CAS objects means a half-landed transfer — money leaves source A's blob and never arrives in B's — is reachable through a plain mid-sync process death.

**1c. Four document-level artifacts have no per-portfolio meaning, and one of them *throws* on divergence.**

- `clientSecurity` — `app/src/main/java/at/bettertrack/app/vault/VaultMerge.kt:218-223` and `:237-247` raise `mergeDocumentInvalid("Vault retirement proof material diverged across replicas.")`. With N blobs there are N copies of one account-level proof object; every copy is a divergence candidate that hard-fails merge.
- `mirrorProvenance` — pruned against the *merged entity set*: `app/src/main/java/at/bettertrack/app/vault/VaultMerge.kt:135` → `MirrorProvenance.kt:128-142`. Provenance rows carry `portfolioId` **and** `chainId`/`membershipId`, i.e. they span portfolios inside one array. Pruning against a per-portfolio subset drops every entry whose row lives in another blob — and `MirrorProvenance.kt:124-126` states the consequence: *"the server rejects an entry naming no restored row — so a stale alias must never accumulate, or the account could no longer leave paranoid mode."* **That is a permanent trap: the user cannot exit paranoid mode.**
- `mergeLog` — one bounded history per document, cap 20 (`VaultMerge.kt:32`, `:275-298`).
- `vaultVersion` — one counter that is simultaneously the CAS token and half the projection cache key (`VaultProjection.kt:116-120`).

Also `documentDominatesParsed` (`VaultMerge.kt:205-234`), the short-circuit that stops version ping-pong, iterates every kind of one document against the other. A per-blob answer is not the document answer.

**1d. Our projection writer is whole-vault and will delete the other portfolios.**

`app/src/main/java/at/bettertrack/app/data/storage/VaultProjection.kt:236` — a derivation for *one* `portfolioId` emits rows for **all** portfolios, and `app/src/main/java/at/bettertrack/app/data/storage/VaultPortfolioBackend.kt:438-439` acts on that:

```kotlin
db.portfolioDao().upsertAll(projected.portfolios)
db.portfolioDao().deleteNotIn(projected.portfolios.map { it.id })
```

Deriving portfolio A from A's blob alone would prune every other portfolio's Room row. Same shape at `:443-446` for the shared custom-asset catalogue.

**1e. 19 of the 26 contract entity kinds are unauthored, and a splitter must place them blind.**

`app/src/main/java/at/bettertrack/app/vault/VaultContracts.kt:66-93` lists 26 kinds; we author 7 (`VaultEntityGraph.kt:41-49`) and carry the rest through opaquely. Several are plainly account-scoped — `expenseCategory`, `expenseRule`, `cashTag`, `cashRule`, `cashBudget`, `taxSetting`. And the document parser **fails closed** on an unknown kind: `VaultContracts.kt:659-661`. A split that guesses wrong makes the document invalid on the other client.

**What we need in the contract (not a preference — a precondition):** a named owner for every one of the 26 kinds; an explicit account-scoped doc (a "vault-common" blob) for the kinds that have no portfolio; and a written transactional rule for any operation spanning two blobs — either "refused at the UI/op layer" or a two-phase protocol with a defined recovery for each interruption point. See N6/Q1, Q2, Q3.

---

### O2 (BLOCKER) — The v1→v2 client-side split on unlock is under-specified in exactly the three ways that lose data

The contract says (§3, lines 73-76): *"the v1→v2 header upgrade happens client-side on next unlock (silent-upgrade precedent)."* Three unanswered questions, each with a concrete failure in our code.

**2a. Both clients race, and the doc does not say who wins.**

Web and mobile can both hold a valid passphrase and both unlock within the same minute. Each would read the v1 account doc, split it, and write a v2 header plus N portfolio blobs. Our server CAS protects a *single* blob against a stale writer; it does not protect a *set* of writes that two clients each believe they are creating for the first time. `If-None-Match: *` on the header makes one creator lose — but only after the loser has already written some portfolio blobs under its own split, which the winner's header does not index. The doc has no arbitration rule. **Needed: an explicit winner rule (server-assigned migration lease, or header-created-first-wins with a defined loser cleanup) written into the contract.**

**2b. The split is not idempotent if killed mid-way, and we have no cross-blob transaction to make it so.**

The split is N+1 writes. Our only atomicity is one process mutex plus one Room transaction: `app/src/main/java/at/bettertrack/app/vault/VaultStore.kt:94-107`; `app/src/main/java/at/bettertrack/app/data/db/VaultEntities.kt:177-181`, whose KDoc at `:172-176` says *"Anything less than atomic here can leave the working store holding half of one vault and half of another."* Kill the process after the header lands and before blob 3 lands, and the header's portfolio index names a blob that does not exist. There is no defined recovery. **Needed: the split must be specified as resumable and idempotent — write all portfolio blobs first, header last, with the header's presence as the sole commit point, and an explicit rule for orphaned blobs.** Also needed: what a client that reads a header naming a missing blob must do (refuse? treat as empty? — "treat as empty" would silently present a zeroed portfolio, which violates our honest-states law, see O5).

**2c. Our sync-op park/replay does NOT survive it — the vault arm is not idempotent.**

This is the finding I would most like the platform chief to read.

Our server arm is exactly-once via a persisted client UUID sent as the HTTP `Idempotency-Key` — `app/src/main/java/at/bettertrack/app/sync/SyncModels.kt:61-66` calls it *"the SOLE exactly-once mechanism"*; minted at `SyncEngine.kt:87`, sent at `data/api/BtApi.kt:501`, attached at `sync/ApiOpExecutor.kt:82,102-104,125,170`.

The **vault arm has no such mechanism**. `VaultOpExecutor` never reads `op.clientId` (zero occurrences in the file) and mints fresh ids on every apply — `app/src/main/java/at/bettertrack/app/sync/VaultOpExecutor.kt:182, 200, 274, 314-316`. This is safe *today* only because a vault apply is synchronous and terminal, which the file states outright at `:41-46`: *"there is no request, so there is no ambiguity, no idempotency key to replay and no backoff."*

Vaults v2 breaks that premise. A per-portfolio vault can be **locked while its portfolio still accepts writes** — that is already our model (`app/src/main/java/at/bettertrack/app/vault/VaultSyncCoordinator.kt:156-161`: locked is a *sync* state, the write already committed to Room). Add a migration window on top, and a vault op acquires a non-terminal outcome for the first time. A replay would then create **duplicate transactions and duplicate cash movements** — silent money corruption, in the one mode where there is no server copy to reconcile against.

Fix is ours to build (derive entity ids deterministically from `op.clientId`, or refuse enqueue while a vault is locked/migrating), but the contract must state which: see Q5.

**2d. Room cost, and a schema-safety hazard specific to this project.**

`vault_entities` has primary key `(kind, id)` — `app/src/main/java/at/bettertrack/app/data/db/VaultEntities.kt:50-53`. With N vaults per account that is no longer unique; the PK must become `(vaultId, kind, id)`. SQLite cannot `ALTER TABLE` a primary key, so this is a create/copy/drop/rename rebuild — on the one table our code calls the source of truth: `VaultEntities.kt:22-27` — *"If they are lost and the Drive push had not yet landed, the user's data is gone — there is no server to refetch from."*

And we do it without a safety net: `@Database(version = 10, exportSchema = false)` at `app/src/main/java/at/bettertrack/app/data/db/BtDatabase.kt:76-77`; there is no `app/schemas/` directory and no `room.schemaLocation` ksp arg, so no `MigrationTestHelper` verification is possible today. This project has **already shipped two different physical schemas under one `user_version` twice** — `BtDatabase.kt:14-22`, with both incidents documented at `:136-140` and `:253-260`. We will turn `exportSchema` on and add migration tests before attempting the rebuild; flagging it here because it is a real slice of the P4 estimate and a real risk to user data.

One adjacent trap for whoever implements the routing: **do not encode a vault id into `sync_ops.backendTag`.** `BackendTag.fromWire` silently degrades unknown values to `SERVER` — `app/src/main/java/at/bettertrack/app/data/storage/StorageMode.kt:63-71` — so a `backendTag = "vault:abc"` row read by an older or rolled-back build would route a **vault-destined money mutation to the BetterTrack API in cleartext**. That is a paranoid-mode privacy breach, not just a bug. A nullable `sync_ops.vaultId` column is the safe shape.

---

### O3 (OBJECTION, cheap to resolve) — `keySlots[]` as specified will make every shipped app report "your vault is corrupt"

Our header parser rejects unknown fields at three nesting levels, deliberately, because the header **is** the GCM AAD:

`app/src/main/java/at/bettertrack/app/vault/VaultEnvelope.kt:238-255` —
> *"`exactHeaderShape` — **unknown header fields are rejected**. Not decoration: the header is the AAD, so any field a client silently ignores is a field an attacker can use to make two clients disagree about what was authenticated. The check runs over the header, every wrapped key, and every wrapped key's kdf."*

The allow-list is `formatVersion, cipher, iv, keyId, wrappedKeys, vaultVersion, schemaVersion, deviceId, writeId, writtenAt` (`app/src/main/java/at/bettertrack/app/vault/VaultContracts.kt:287-298`, `:316-327`), and `wrappedKeys` is **mandatory and non-empty** (`VaultEnvelope.kt:249-250`, `VaultContracts.kt:350-353`). Additionally `formatVersion` is strict-equality-1 (`VaultContracts.kt:334-336`), not a range.

So a v2 header carrying `keySlots[]` + top-level `kdfSalt` + a portfolio index **while keeping `formatVersion: 1`** fails structurally and surfaces as `ENVELOPE_INVALID` — user-facing copy: the vault is corrupt. Not "update your app". This exact behaviour is pinned by a shipped test: `app/src/test/java/at/bettertrack/app/vault/VaultConformanceTest.kt:331-368`.

The one clean forward channel already exists and is also tested: bump `formatVersion` (or `schemaVersion`), which routes through the version gate at `VaultEnvelope.kt:129-137` → `UPDATE_REQUIRED` → read-only, non-destructive, "update the app" — `VaultConformanceTest.kt:377-433` proves the version gate wins over the shape check and that the original envelope is left untouched.

**Ask: state in the contract that the v2 header is `formatVersion: 2`.** Cheap, and it converts a "your data is corrupt" scare across the entire installed base into a correct update prompt.

Two more format traps in the same area:

- **`keySlots[]` is not a widening of `wrappedKeys[]`, it is a restructure.** Platform v1 `wrappedKeys[]` (`packages/contracts/src/vault.ts:514-556` on `pr-1173`) is a multi-entry list supporting *passphrase change/rotation of one key*, each entry carrying its **own** `kdf` including its own `salt`. The v2 design hoists a single `kdfSalt` to the header and repurposes slots for multi-*principal* wrapping. Different shape, different semantics, same-ish name. Must be pinned by vectors before either client writes code.
- **`"v":2` in the QR payload is ambiguous.** `VAULT_FORMAT_VERSION = 1` but `VAULT_DOCUMENT_VERSION = 2` already (`packages/contracts/src/vault.ts:55-59`). `"v":2` could mean either. Nothing in code disambiguates it. Name the field.
- **Our recovery kit is `formatVersion`-locked to 1** — `app/src/main/java/at/bettertrack/app/vault/VaultRecovery.kt:104-109` and `:131-136`. A v2 vault's kit needs its own version story or existing kits stop importing.

**Ack embedded here:** the *array shape itself* costs us nothing — our header already carries `wrappedKeys: List<VaultWrappedKey>` (`VaultContracts.kt:292`) and re-validates the KDF profile on **every** wrapper (`VaultCrypto.kt:438-462`). Designing the sharing hook now as an array is correct and we support it.

---

### O4 (OBJECTION — needs a platform-chief ruling) — The QR payload carries the raw passphrase; a 60 s *on-screen* TTL is not a mitigation

Contract §2, lines 51-53: payload `btvault1:{"v":2,"vaultId":…,"name":…,"p":…}` where `p` is the passphrase, *"rendered only after re-auth, on-screen max 60 s, never transmitted."*

Our position: **the re-auth gate and the "never transmitted" property are good; the 60 s on-screen TTL is not a security control, and shipping the raw `p` as the only handoff is a risk we would rather not carry.** A 60 s timer bounds *display duration*, and nothing else. It does not bound:

- **Screenshots.** One tap captures the full vault secret into the device gallery, which on a default Android install is synced to Google Photos. A cloud-backed screenshot of a 12-word paranoid-vault passphrase is the precise outcome paranoid mode exists to prevent, and it is one accidental button-combo away.
- **Shoulder surfing / an ambient camera.** A QR is machine-readable at a distance and off a photograph, unlike typed words.
- **Screen recording and accessibility/screen-reader capture** by any other app with the relevant grant.
- **Duration of the secret.** The passphrase is long-lived and unrotatable-in-practice; a 60 s exposure of an eternal secret is a 60 s window on a forever credential.

Note this handoff is also strictly *more* powerful than what our device storage holds today: we persist the wrapped **vault key**, never the passphrase (see O6). The QR would be the only place in our stack where the passphrase itself leaves the user's head.

**What we would prefer** (any of these, in descending order of preference; we are not dogmatic about which):

1. **Receiver-initiated key handoff.** The *receiving* device displays a QR containing an ephemeral public key + nonce; the sending device scans it, wraps the **content key `K_c`** (not the passphrase) to that ephemeral key, and returns it out-of-band or via a second QR. The raw passphrase never enters any display buffer, and the transferred capability is revocable by rekey. This is more work for the platform but it is the honest design for a feature whose selling point is that secrets never leave the user.
2. **Wrap the payload under a short numeric PIN** shown separately on the sending device and typed on the receiver. A photographed QR is then useless on its own. Cheap, and it defeats the screenshot and shoulder-surf cases in one move.
3. **If the raw `p` ships anyway**: make it contract-mandatory that the QR screen sets a screenshot block (`FLAG_SECURE` on Android, equivalent on web), that the screen is excluded from the recents/task-switcher preview, and that the copy tells the user plainly that anyone who photographs this owns the vault forever. We will implement all three regardless; we want them in the contract so web and mobile behave identically.

We can live with option 3 if the chief rules that way — this is an objection with a stated position, not a veto. But we want the ruling recorded rather than inherited by default.

**Separately, and independent of the ruling: QR *scanning* is a genuinely new capability for this app**, and it has policy weight:

- We ship `com.google.zxing:core` **encode-only**, for TOTP enrollment: `gradle/libs.versions.toml:71-73`, `app/build.gradle.kts:307-309`, renderer `app/src/main/java/at/bettertrack/app/ui/components/BtQrCode.kt:62-76`. Displaying a QR is therefore free.
- There is **no scanner, no CameraX, no ML Kit, and no `android.permission.CAMERA`** in any of our three manifests (`app/src/main/AndroidManifest.xml`, `app/src/github/…`, `app/src/debug/…` — declared permissions are `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `USE_BIOMETRIC`).
- Adding `CAMERA` is a *dangerous* runtime permission and a **Play Data Safety form change** (`docs/PLAY_RELEASE_CHECKLIST.md` is the gate). Our manifest already carries a deliberate `AD_ID` removal noting *"the app has no ads and no analytics SDK, and the Data Safety form says so"* — we do not add a camera grant casually.
- Dependency choice is constrained by our single-activity law: `zxing-android-embedded` ships its own Activity and is out; CameraX `ImageAnalysis` + `zxing core`'s `MultiFormatReader` keeps both the law and the existing dependency. ML Kit's bundled model costs several MB.

---

### O5 (OBJECTION) — "Locked rows everywhere money renders" is right, but our existing partial-total machinery will silently certify a short total as complete

We strongly agree with the *intent* — a locked vaulted portfolio rendering `0,00 €`, or silently dropping out of a total, is exactly the class of bug three separate packages in this codebase were built to prevent (`app/src/main/java/at/bettertrack/app/ui/prices/PriceStates.kt:92-95` — *"every available number would be `0` — the €0 lie in its purest form"*; `app/src/main/java/at/bettertrack/app/data/api/ParanoidMode.kt:18-20`). The objection is that the contract asserts the requirement without acknowledging that **account-level aggregates become partially-unknown**, and that our current honesty machinery gets this case actively wrong.

The sharpest site: `PriceCoverage` counts **priced vs unpriced holdings** — `app/src/main/java/at/bettertrack/app/ui/prices/PriceStates.kt:43-58`, where `complete: Boolean get() = unpriced == 0`. A locked vault contributes **zero holdings**, so `complete` returns `true` while the total is short, and the caveat line disappears. `netWorthState(...)` at `:117-125` and the hero sum at `app/src/main/java/at/bettertrack/app/ui/home/HomeLogic.kt:140-142` inherit that.

Related sites that go quietly wrong: `HomeLogic.kt:144-145` (a percentage derived from a partial sum), `HomeLogic.kt:219-236` (`homeMovers` — a locked vault's assets vanish from "today's biggest movers" without a word), and `HomeLogic.kt:248-258` (`mergeAcrossPortfolios` — one asset held in both a visible and a locked portfolio renders a **partial position presented as the user's whole holding**, with no partiality signal on that path at all).

Critically, we must **not** reuse the existing vocabulary. `HomeLogic.kt:89-95` already defines `partial` with the rule *"The renderer MUST show the 'across N of M portfolios' line whenever this is set; a partial sum with nothing next to it is the lie."* But "not synced yet" resolves by **waiting** and "locked" resolves by **typing a passphrase** — different states, different affordances, different copy. A fourth honest state is required (`PartiallyLocked(eur, lockedVaults)` or equivalent) with its own copy and a tap-to-unlock affordance.

**Ask of platform: a product ruling on what the account net-worth hero shows when one vault is locked and another portfolio is visible** — sum-of-visible-with-a-lock-caveat, or no total at all until everything is unlocked. Web and mobile must agree; this is user-visible arithmetic. See Q7.

**Also unresolved by the contract: three of our thirteen gated routes cannot answer "which portfolio?"** Our gate is a global boolean today (`app/src/main/java/at/bettertrack/app/data/api/ParanoidMode.kt:32-41`; `app/src/main/java/at/bettertrack/app/ui/paranoid/ParanoidGate.kt:58-62`) applied at 13 route sites in `app/src/main/java/at/bettertrack/app/ui/shell/AppShell.kt` (`:1426, 1530, 1558, 1579, 1599, 1604, 1609, 1810, 1826, 1832, 1842, 1855, 1866`). Of those:

- `PortfolioTabRoute` (`:1426`) carries **no** portfolio id and hosts both the selected portfolio and the account-wide Overview. It cannot be gated as a unit any more — it becomes inline row-level rendering, a rewrite rather than a wrapper swap.
- `HoldingDetailRoute` (`:1530`) carries `holdingId` only (`app/src/main/java/at/bettertrack/app/navigation/BtRoutes.kt:46`) — the gate would need an async holding→portfolio lookup before it can decide, i.e. it stops being a pure read.
- `CashTagsRoute` / `CashRulesRoute` / `TaxSettingsRoute` are **account-scoped by design** — `BtRoutes.kt:78-83`: *"Tags and rules are per USER, not per portfolio (a label means the same thing in every ledger the account owns), so neither route carries a portfolio id."* Locked iff *any* vault is locked, or iff *all*? No correct answer exists without a product decision. See Q8.

And the per-portfolio signal **does not exist at either end**: `PortfolioEntity` has no vault column (`app/src/main/java/at/bettertrack/app/data/db/PortfolioEntities.kt:20-43`), our paranoid state is fed by an account-scoped `/auth/me` `privacyMode` scalar plus a *subject-less* 403 interceptor that never reads the request URL (`ParanoidMode.kt:90-106`). Per-portfolio paranoid needs a new wire contract, not just app work. See N3.

---

### O6 (OBJECTION, narrow) — "Raw opt-in" device storage is a new weakening; we decline to ship it on Android, and the contract's storage model is weaker than ours

Contract §2, line 49: *"Device storage of `P`: password-wrapped by default; **raw opt-in** behind an explicit warning."*

What we ship today is already strictly stronger, and it stores a different thing:

| Item | Where | Form |
|---|---|---|
| `vault_key_id`, `vault_kdf_salt` | `EncryptedSharedPreferences` file `bt_vault_custody` | inside Keystore-encrypted prefs |
| `vault_wrapped_vk` | same | Argon2id-KEK-wrapped, AES-GCM, AAD = `keyId` |
| unwrapped vault key | **RAM only**, `@Volatile`, zeroed on `lock()` | — |
| **the passphrase itself** | **nowhere** | never persisted |

`app/src/main/java/at/bettertrack/app/vault/VaultKeyCustody.kt:304-308` (pref keys), `:112-116` (write), `:69-71` (in-memory), `:224-228` (`lock()` zeroes), `:331-342` (`MasterKey` AES256_GCM + `AES256_SIV`/`AES256_GCM` prefs schemes). Argon2id at 64 MiB / t=3 is called out at `:26-30` as *"not negotiable … baked into every vault the web client has ever written."*

Two consequences:

1. **We store a wrapped *key*, the contract stores a wrapped *passphrase*.** A compromised wrapped-key blob yields today's content key; a compromised passphrase yields the vault **and every future rekey of it**. We would prefer the contract mandate wrapping `K_c`, not `P`, wherever a device persists anything. If `P` must be stored to support the QR handoff re-wrap, say so explicitly and scope it.
2. **"Raw opt-in" is a new capability that only makes users less safe.** There is no raw storage option, no "remember passphrase", and no biometric vault unlock in our build today (biometrics exist only for the app PIN: `app/src/main/java/at/bettertrack/app/ui/settings/SecurityScreen.kt:204-225`). We will implement password-wrapped-by-default; **we ask that raw opt-in be marked platform-optional so mobile can decline it.** If the owner wants it on Android specifically, we would want that as a separate explicit instruction.

Also greenfield for us, and fine, but it must be costed: the **12-word generated passphrase** does not exist on either side today. Our wizard takes free text with `MIN_PASSPHRASE_LENGTH = 10` and a heuristic strength meter (`app/src/main/java/at/bettertrack/app/ui/storage/StorageWizardState.kt:111`, `:122-149`, `:158-159`), and the platform has **no wordlist anywhere** — a grep for `bip39|wordlist|mnemonic` across `pr-1173` returns only an unrelated breached-password denylist. See N4.

---

### O7 (OBJECTION) — Per-vault Drive rescoping will orphan every existing Drive vault unless the contract specifies a rename migration

Our Drive layout is not a folder — `appDataFolder` is one flat namespace with no directory structure and no listing the user can inspect, so **the file name is the entire selector**: `app/src/main/java/at/bettertrack/app/vault/drive/DriveVaultFileName.kt:20-24`. The name is:

```
bettertrack-vault-<base64url(SHA-256("bettertrack-drive-vault-account-v1:" + accountId))>.btenc
```
`app/src/main/java/at/bettertrack/app/vault/drive/DriveVaultFileName.kt:40-48`

The `accountId` is a locally-minted UUID kept forever precisely because of this — `app/src/main/java/at/bettertrack/app/vault/VaultStore.kt:71-75`: *"re-deriving it would rename the Drive object and orphan the vault."*

Any per-vault naming changes the hashed input, so **every existing user's Drive file no longer matches the name we look for**. Discovery is a `files.list` query by exact name (`DriveDataHome.kt:234-241`) and `validateFile` rejects any file whose name differs (`:498`). The user's data would still be in Drive, invisible to the app — indistinguishable from loss, in the mode where Drive *is* the only copy.

**Ask: the contract must specify the v1→v2 Drive migration as a metadata rename with a recorded old→new mapping, executed before any v2 write, and resumable.** Related structural work on our side (costed, not objections): `fileName` is computed once in the `DriveDataHome` constructor (`:94-98`) and cached as a singleton (`app/src/main/java/at/bettertrack/app/di/AppGraph.kt:731-742`) — N vaults means N instances and a keyed cache; and the per-medium CAS cursor special-cases Drive with an unsuffixed key (`app/src/main/java/at/bettertrack/app/vault/VaultSyncCoordinator.kt:611-614`), which exists so an already-synced Drive install does not re-push — whatever v2 keying we choose must preserve that for vault #1.

One more, ours to fix but worth the platform knowing: we have **one** coordinator, **one** push mutex (`VaultSyncCoordinator.kt:101-103`) and **one** unique WorkManager chain with `ExistingWorkPolicy.REPLACE` (`app/src/main/java/at/bettertrack/app/vault/VaultSyncWorker.kt:73`, `:81`). With N vaults, the second vault's scheduled push **cancels** the first's, and a slow Drive on vault A stalls vault B. Multi-vault requires re-architecting that loop.

---

## 2. Needs — what mobile requires from platform BEFORE P4

### N1 — Format version and naming, in the contract text
- State that the v2 header is **`formatVersion: 2`** (see O3). Without it, every shipped client reports "vault corrupt" instead of "update your app".
- Disambiguate the QR `"v"` field against `VAULT_FORMAT_VERSION` (1) and `VAULT_DOCUMENT_VERSION` (already 2) — `packages/contracts/src/vault.ts:55-59`.
- State the relationship between `keySlots[]` and today's `wrappedKeys[]`: replacement or coexistence, and where the per-entry `kdf`/`salt` goes now that `kdfSalt` is hoisted.
- Version story for the **recovery kit** (ours is `formatVersion`-locked to 1: `VaultRecovery.kt:104-109`, `:131-136`).

### N2 — Conformance vector coverage list (exact)

We **cannot generate vault vectors** — we replay the platform's published oracle byte-for-byte. `tools/domain-vectors/README.md:155-158`: *"there is no generator here. The platform already publishes a hand-authored conformance oracle, so the app replays it directly."* Our current pin is `fc970e8a` (`tools/domain-vectors/PINNED_AT:1-6`), and 18 conformance tests run against it (`app/src/test/java/at/bettertrack/app/vault/VaultConformanceTest.kt`).

Required before P4 starts:

1. **v2 header derive/wrap/unwrap** — fixed passphrase, fixed `kdfSalt`, fixed slot, canonical `headerBytesBase64` + `envelopeBase64`, plus the negative cases we already pin at v1 (wrong secret → `authentication-failed`, tampered → reject, update-required).
2. **Multi-slot `keySlots[]`** — canonical bytes for the single-slot case *and* at least one two-slot case, so member-order and serialization are pinned before shared vaults exist.
3. **Per-portfolio doc split** — a canonical assignment for **all 26 entity kinds** (`VaultContracts.kt:66-93`), including the account-scoped ones, and a vector showing a two-portfolio vault's exact blob set.
4. **Migration transcript** — one v1 account document in, the exact v2 header + N portfolio blobs out, byte-exact. This is the vector we care about most: it is the only way both clients provably agree on a split that runs unattended on user data.
5. **Recovery-kit v2 bytes.**
6. **QR payload canonical string** — exact JSON member order and encoding for a fixed input.

**Plus a relocation ask:** vault vectors currently live in `apps/web/src/user/vault/vectors.ts` + `vectors.fixture.json`, not in `packages/domain`. §7 of the contract says *"`packages/domain` stays pure and shared"* — but today mobile must vendor crypto vectors out of the **web app**. Please move vault vectors into a shared package as part of this arc.

### N3 — Endpoint shapes
- Exact `{vaultId}`-scoped header/blob `GET`/`PUT` paths, auth scope, and whether `vault:sync` bearer covers them (today's allowlist is default-closed and enumerates literal paths: `apps/api/src/http/middleware/bearerAuth.ts:41-46`).
- `POST /portfolios/{id}/vault` join and the leave/disable reverse: request/response bodies, and the transactional guarantee on the server side of each.
- **CAS conflict response must carry the current version.** Today the repository computes it and the route throws it away — `apps/api/src/data/repositories/paranoidVaultRepository.ts:152` returns `{ status: 'precondition_failed'; currentVersion: number | null }`, and `apps/api/src/http/routes/vaultRoutes.ts:510-511` is `case 'precondition_failed': throw preconditionFailed();` with no `details` and no `ETag` on the 412. Mobile must issue a second `GET` to learn the winner. Per-portfolio CAS multiplies conflicts by the number of portfolios, on links that drop. Please add `details: { currentVersion }` or an `ETag` on the 412. Cheap server-side, materially better on mobile.
- **How the per-portfolio wire contract exposes vault membership**: `/auth/me`'s `privacyMode` is an account scalar today. We need the set of vaulted portfolio ids (and their vault ids) on a response we already fetch, or per-portfolio in the portfolio list.
- Blob size caps: v1 is 16 MiB total (`VAULT_MAX_BYTES_DEFAULT`). Per-blob cap, sum cap, or both?

### N4 — Contract constants
- **QR TTL**: 60 s must be a shared constant. None exists — the vault contract has `VAULT_SERVER_CANDIDATE_TTL_MS`, `VAULT_RETIRED_PURGE_CHALLENGE_TTL_MS`, `VAULT_RETIRED_SERVER_MIN_RETENTION_MS`, but nothing for the QR. Without it web and mobile will drift.
- **The 12-word list**: does not exist anywhere on `pr-1173`. We need the wordlist itself in a shared package plus: BIP39 or custom, language set, word count, normalization (NFKD?), case rule, separator, and whether there is a checksum. Cross-client handoff is not implementable without this.
- **Slot format**: the `keySlots[]` entry schema, where `kdf` params live, and the AAD for a slot wrap (ours is `utf8(keyId)` today — `VaultCrypto.kt:208`).
- **Vault name constraints**: max length, allowed characters, uniqueness scope. It is cleartext and it goes in the QR.

### N5 — Error codes for our catalog
Our catalog holds exactly **224** codes (`app/src/main/java/at/bettertrack/app/data/api/BtErrorCopy.kt:66-317`, verified count), and every addition needs an entry plus copy in **both** `values/strings.xml` and `values-de/strings.xml` (`BtErrorCopy.kt:25-27`; `BtErrorCopyTest`/`StringParityTest` enforce it). Requested, in the platform's existing `VAULT_*` convention (`packages/contracts/src/vault.ts:1552-1567`):

`VAULT_NOT_FOUND_BY_ID` · `VAULT_PORTFOLIO_NOT_IN_VAULT` · `VAULT_PORTFOLIO_ALREADY_VAULTED` · `VAULT_HEADER_STALE` · `VAULT_BLOB_NOT_FOUND` (header indexes a missing blob) · `VAULT_MIGRATION_IN_PROGRESS` · `VAULT_MIGRATION_CONFLICT` · `VAULT_JOIN_CONFLICT` · `VAULT_LEAVE_CONFLICT` · `VAULT_BACKEND_CONFIG_CONFLICT`

Not a hard blocker for our start — an unknown code degrades gracefully to `ParkReason.Unmapped` (`BtErrorCopy.kt:416-421`) — but we need the list to write copy, and we need it in both languages before ship.

### N6 — Ambiguities, as questions (we are not guessing on any of these)

- **Q1.** Which document owns each account-scoped entity kind — `customAsset`, `customAssetValue`, `cashTag`, `cashRule`, `cashBudget`, `expenseCategory`, `expenseRule`, `expenseBudget`, `taxSetting`? Is there a per-vault "common" doc? (O1a, O1e)
- **Q2.** Cross-portfolio transfer **within one vault**: allowed, and if so what is the transactional protocol across two CAS'd blobs, with a defined recovery at each interruption point? (O1b)
- **Q3.** Cross-**vault** transfer (portfolio in vault A → portfolio in vault B, different passphrases, possibly one locked): refused at the UI, refused at the op layer, or supported? Our op format currently does not even carry the second portfolio id (`SyncModels.kt:174-180`).
- **Q4.** v1→v2 split: which client wins a simultaneous unlock, how is the loser's partial work cleaned up, and what is the commit point that makes the split resumable? (O2a, O2b)
- **Q5.** May a vaulted portfolio accept writes while its vault is **locked**? Today ours does (locked is a sync state — `VaultSyncCoordinator.kt:156-161`). If yes under v2, the vault op path acquires a non-terminal outcome and we must add an idempotency key to it. (O2c)
- **Q6.** What must a client do when the header's portfolio index names a blob it cannot fetch or decrypt? Refuse the whole vault, or show that one portfolio as unavailable? "Treat as empty" is not acceptable to us — it renders a €0 lie.
- **Q7.** Account net-worth aggregate with one vault locked and another portfolio visible: sum-of-visible with a lock caveat, or no total at all? Web and mobile must render the same arithmetic. (O5)
- **Q8.** Account-scoped surfaces (cash tags, cash rules, tax settings): locked iff *any* vault is locked, iff *all*, or never? (O5)
- **Q9.** Drive per-vault file naming, and the rename migration for existing single-file vaults. (O7)
- **Q10.** For `backends: both` — is it the same blob set mirrored to two media with independent CAS per medium (our current model), or something else?
- **Q11.** Does the server learn which portfolios share a vault (via `portfolios.vaultId`)? That is a real metadata leak — grouping plus per-blob sizes and write timings. Accepted-by-design, or should vault membership be ciphertext-only?
- **Q12.** Do vaulted portfolios keep the retirement-proof / `clientSecurity` machinery, and is it per-vault or per-account? Our merge **throws** on divergence (`VaultMerge.kt:218-223`), and a stale `mirrorProvenance` alias permanently blocks leaving paranoid mode (`MirrorProvenance.kt:124-126`).

---

## 3. Acks — what is clean, and what we say yes to

1. **Reusing the BTVAULT1 substrate.** Argon2id (m=65536, t=3, p=1, Argon2 version 0x13), AES-256-GCM with a 128-bit tag, header-as-AAD, our literal fflate port — all unchanged. Our byte-identical crypto (`VaultCrypto.kt:137-152`, `RawDeflate.kt`) and all 18 conformance tests survive a v2 that only changes header shape and doc granularity. This is the single best decision in the document.
2. **`keySlots[]` designed now, built later.** Our header already carries an array of wrapped keys (`VaultContracts.kt:292`) and re-validates the KDF profile on every element (`VaultCrypto.kt:438-462`). Designing the sharing hook as an array up front is correct and costs us nothing beyond the version bump in O3.
3. **`If-Match` CAS widened rather than replaced.** We already mandate a precondition on every PUT and refuse to send one that does not advance the version (`app/src/main/java/at/bettertrack/app/vault/server/ServerVaultDataHome.kt:196-242`, `:201-208`). `{vaultId}`-scoping is a path change, not a discipline change — good.
4. **Mode transitions stay session-only.** Matches the shipped platform guard (`apps/api/src/http/routes/vaultRoutes.ts:110-121` — a bearer cannot PUT unless already paranoid) and matches our own separation. Keep it.
5. **Server never parses ciphertext, no server-side recovery, sizes capped.** Unchanged and correct.
6. **Per-portfolio paranoid as the product shape.** This is a genuine win and the right call. Our "absent, not greyed" doctrine (`AppShell.kt:251-258`) fits it naturally, and it removes the all-or-nothing cliff that makes account-level paranoid a hard sell.
7. **Multiple vaults with separate passphrases and per-vault backend sets.** First-class multi-vault is the right ambition, and our wizard's safety interlocks (`StorageWizardState.kt:193-216` — kit after passphrase, blocking acknowledgment before any key is created, no back once key material commits) port to a per-vault wizard cleanly.
8. **Generated 12-word passphrase.** A real improvement over our current free-text ≥10-character field (`StorageWizardState.kt:111`). We want it — conditional on the wordlist contract in N4.
9. **Locked rows everywhere money renders.** The right requirement, and aligned with our house doctrine against the €0 lie. We have the funnel precedent (`app/src/main/java/at/bettertrack/app/ui/format/BtNumberFormat.kt:141`, discreet mode) to build it on. Our objection in O5 is about aggregates, not about this rule.
10. **The explainer page** (`/vault/how-it-works`) — what the server sees, what a breach yields, what a stolen device yields in both storage modes, lost-words consequence. Exactly the honesty bar we hold ourselves to. We will mirror it.
11. **QR re-auth gating and "never transmitted."** Both correct. Our objection in O4 is narrowly about the raw `p` and the screenshot surface, not about these two properties.

---

## 4. Effort estimate — mobile P4 as scoped

Assumes the NEEDS in §2 have landed (contract text + vectors + endpoints + constants). **P4 cannot start before N1, N2 and N3.**

| Work package | Builder-days |
|---|---|
| v2 format: `formatVersion: 2` parser path, `keySlots[]`, hoisted `kdfSalt`, recovery-kit v2, re-pin + port new vectors | 4 |
| **Storage engine split**: `VaultStore` keyed by vault, per-blob CAS, document-level artifacts (`clientSecurity`/`mirrorProvenance`/`mergeLog`) rehomed, merge rework, projection writer rescoped off `deleteNotIn` | 12 |
| Multi-vault custody: keyed prefs, N lock states, N Argon2id unlocks, per-vault rekey + recovery kit | 3 |
| Drive rescope + rename migration of existing vaults (resumable, mapping recorded), N `DriveDataHome` instances, duplicate scan | 4 |
| Sync engine: `sync_ops.vaultId`, per-portfolio routing, N coordinators / N work chains, **idempotent vault op executor** (clientId-derived ids) | 5 |
| v1→v2 migration: resumable split, race arbitration, rehearsal on real data | 4 |
| Room v10→v11: `vaults` table, `portfolios.vaultId`, `vault_entities` PK rebuild, **turn on `exportSchema` + migration tests first** | 3 |
| ParanoidGate → per-portfolio: 13 route sites, `PortfolioTabRoute` inline rewrite, holding→portfolio resolution | 5 |
| Locked-row rendering (~28 render sites) + new `PartiallyLocked` aggregate state + EN/DE copy | 5 |
| Vault list, per-portfolio settings section, storage wizard folded into vault creation | 4 |
| QR: display (free-ish, 1) + scanner (CameraX + zxing decode + runtime permission + Data Safety update) (3) | 4 |
| Test + device QA passes (paranoid E2E, migration rehearsal, conflict/kill-mid-split matrix) | 5 |
| **Total** | **≈ 58 builder-days** |

Call it **11–13 weeks single-threaded**, of which the engine split (12 d) and the combined migration + sync-idempotency work (12 d) are the risk-carrying half. Roughly 20 of those days are avoidable if the contract answers Q1/Q2 by **refusing** cross-blob operations and giving account-scoped kinds a dedicated vault-common doc — that is the single highest-leverage simplification available to the platform, and we would advocate for it.

---

## 5. One-paragraph summary for the board

Mobile approves the product shape of Vaults v2 and endorses the substrate reuse — our BTVAULT1 crypto, conformance discipline and CAS model all survive intact. We raise two blocking objections. First, per-portfolio CAS blobs have no transactional story, and our engine holds cross-portfolio state (the shared asset catalogue, retirement-proof material, mirror provenance) that has no portfolio to live in; a two-blob transfer or a mid-sync death is currently a path to half-moved money, and a mishandled `mirrorProvenance` split permanently traps an account in paranoid mode. Second, the client-side v1→v2 split is unspecified on client-race, resumability, and idempotency — and our vault-side op executor is not replay-safe, so a non-terminal vault write would duplicate transactions. We also object, with a stated position and a proposed alternative, to shipping the raw passphrase in the QR payload behind only a 60-second on-screen timer, and we ask that "raw opt-in" device storage be platform-optional so Android can decline it. Everything else we need is a precondition list: `formatVersion: 2` in the contract text, six named vector families relocated out of `apps/web`, `{vaultId}` endpoint shapes with `currentVersion` on the 412, the QR TTL and the 12-word wordlist as shared constants, ten new error codes, and answers to twelve written questions. As scoped, P4 is ≈58 builder-days and cannot start until those land.
