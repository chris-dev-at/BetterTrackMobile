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

---

# Appendix — r2 verification (2026-08-08)

**Reviewed:** `docs/VAULTS_V2_DESIGN.md` revision 2 (253 lines) @ `d3f601c7` on branch `pr-1175`, **plus** the merged server code at `3e31fb6c` "[VAULT2-P2] Multi-vault server surface".

**Appendix verdict: r2 is a large and genuine win on the contract, but it is NOT yet a build spec.** Both my blockers are answered in text, objections O5–O7 are resolved (two of them better than I asked), and 11 of 12 questions get real rulings. But verification against the tree finds **six residuals, two of which are data-loss paths**, and it contradicts the board summary in one material way: the six vector families of §16 do not exist, and the v2 header format is not in code.

## A0. Delivery reality check — contract vs. tree

| r2 claim | Verified state |
|---|---|
| Server surface (routes, join/leave, CAS, caps, codes, migration claim) | **BUILT**, and well — 2017-line `apps/api/src/__tests__/vaultsV2.test.ts` |
| §9 `formatVersion: 2`, `kdfSalt`, `keySlots[]`, portfolio index, AAD composition | **NOT IN CODE.** `packages/contracts/src/vaults.ts` models every doc as opaque base64 (`ciphertextBase64Schema`, `:177-183`); `keySlots`/`kdfSalt` appear only in prose comments (`vaults.ts:45`, `:57`; `apps/api/src/data/schema.ts:3861`). `packages/contracts/src/vault.test.ts:251` still pins `formatVersion: 2` as **invalid** under the v1 schema |
| §10 QR (`qr:1`, `w`, PIN wrap, 120 s TTL) | **NOT IN CODE.** No `btvault1:` payload, no `w`, no PIN wrap, no TTL constant |
| §9 BIP39 wordlist | **NOT IN REPO.** `grep -i "bip39\|wordlist\|mnemonic"` over `pr-1175` hits **only** the design doc. No dependency |
| §16 six vector families in the shared location | **0 OF 6 EXIST.** `git diff --stat main...pr-1175 -- packages/domain` is **empty** — the package is untouched. `git diff main...pr-1175 -- apps/web/src/user/vault/vectors.ts vectors.fixture.json` is **empty** — the v1 vectors are byte-identical to main and have **not** relocated. Recovery kit still emits the v1 3-line layout (`apps/web/src/user/vault/recovery.ts:26`) |

So the crypto half of P4 — the half that must be byte-identical across clients — has neither a specification in code nor a single vector to pin. Treat §9/§10/§16 as unstarted.

## A1. Blocker 1 (per-portfolio CAS) — **RESOLVED in contract; 3 residual defects**

§8 delivers everything I asked: a per-vault `common` doc owning the account-scoped kinds; the **single-blob mutation rule** ("every mutation touches exactly one doc"); in-vault cross-portfolio transfers refused as one op in favour of a guided two-step; cross-vault transfers refused outright; an `unavailable` state that is never €0; and size caps (1/4/8 MB — real constants at `packages/contracts/src/vaults.ts:62-70`, enforced at the body parser, the repo, **and** a DB CHECK in `apps/api/drizzle/0087_vaults_v2.sql:66-71`). This is the right shape and it removes the distributed-transaction problem by construction. Ack.

Residual defects:

**A1.1 — `common` conflates document members with entity kinds.** §8 lists `clientSecurity`, `mirrorProvenance` and `mergeLog` alongside `customAsset` as things `common` "owns". In our contract those three are **document members**, not entity kinds — `app/src/main/java/at/bettertrack/app/vault/VaultContracts.kt:593-599`. An unknown key inside `entities` is fatal (`VaultContracts.kt:659-661`), so a `common` doc carrying them as kinds is rejected outright by our parser. Worse, the split forks our document schema rather than extending it: a schemaVersion-2 document **requires** `clientSecurity` (`VaultContracts.kt:685-690`, mirrored in the constructor at `:600-607`) while a schemaVersion-1 document **must not** carry it (`:604-606`). If `clientSecurity` lives only in `common`, per-portfolio docs can satisfy neither branch. **Need:** vector family (3) must pin the exact required-member set for each of the three doc kinds.

**A1.2 — FRESH HOLE: putting `mergeLog` in `common` breaks per-document merge and can make `common` unparseable.** A merge record is `{mergedAt, parents: List<Int>, into: Int, deviceId}` (`VaultContracts.kt:399-404`) whose `parents`/`into` are bare `vaultVersion` integers (`:424-434`) with **no document identifier**. Sharing one log across N independently-versioned docs mixes N unrelated lineages with no way to tell them apart. And the cap of 20 is a **parse-time rejection, not a trim** (`VaultContracts.kt:674-676`): N portfolios merging concurrently overflow it and render `common` **unparseable — taking `clientSecurity` and `mirrorProvenance` down with it**, i.e. the whole vault. **Fix: `mergeLog` must stay per-document.** One-line change to §8.

**A1.3 — the two-step transfer must name its movement kinds.** §8 words the legs as "withdrawal" and "deposit", which is correct — those are exactly our two external kinds (`app/src/main/java/at/bettertrack/app/domain/CashLedger.kt:120-139`, `EXTERNAL_CASH_MOVEMENT_KINDS = listOf("deposit", "withdrawal")`). But the feature is *called* a transfer, and our own in-portfolio transfer uses `transfer_out`/`transfer_in`, which the audited engine defines as **never** external flows (enforced at `CashLedger.kt:947-948`, `:964-971`). If an implementer reaches for the transfer kinds, portfolio A's series drops with no external flow recorded, so `timeWeightedReturn` (`app/src/main/java/at/bettertrack/app/data/storage/VaultProjection.kt:195-196`) reads it as a **market loss**, and B's orphan leg reads as a **phantom gain** — silently, permanently, with nothing throwing (`CashLedger.kt:863-868` deliberately refuses to fail on a reshaped ledger). **Need: the contract states normatively that the legs are `withdrawal` and `deposit`.**

**A1.4 — `transferGroupId` is write-only in our codebase (cost note).** Nothing today groups by `transferId`; the only consumer reads `counterpartSourceId` (`app/src/main/java/at/bettertrack/app/ui/cash/CashScreen.kt:2905`, `:2910`) against a name map built from the **current portfolio's** sources (`:883`), so a cross-portfolio counterpart renders as a literal ellipsis forever. "Renders honestly as an unmatched withdrawal" is new UI work with no existing scaffolding.

## A2. Blocker 2 (v1→v2 split) — **RESOLVED in contract; 3 fresh holes, 2 of them data-loss**

§11's claim → write → verify → flip is the right protocol and it is genuinely built server-side: `apps/api/drizzle/0087_vaults_v2.sql:110-114` adds `migrating_by`, `migration_expires_at`, `migrated_to`; TTL is a real constant (`packages/contracts/src/vaults.ts:314`, 15 min); the claim is a single-statement CAS with **server clock authority** (`apps/api/src/data/repositories/vaultMigrationRepository.ts:99-119`), renew requires same-nonce-and-still-live (`:136-151`), and the flip is transactional and idempotent (`:158-196`). **Clock skew is a non-issue — ack**, the client never supplies a timestamp. §8's "locked = no writes" also removes the non-terminal-vault-write hazard I raised in O2c.

**A2.1 — DATA LOSS: deterministic doc ids do not make the write step idempotent, because the content key is random per client.** §11.2 rests on "deterministic doc identities … make every write idempotent on resume". That gives idempotent *addressing*, not idempotent *bytes*. Our content key is 32 CSPRNG bytes minted on device (`app/src/main/java/at/bettertrack/app/vault/VaultCrypto.kt:101-110`, `generateVaultKey`; called at `app/src/main/java/at/bettertrack/app/vault/VaultKeyCustody.kt:106`), and `keyId` is a random uuidv7 (`app/src/main/java/at/bettertrack/app/vault/VaultRekey.kt:163-167`). Two claim holders therefore write the *same* doc id under *different* K_c — mutually undecryptable ciphertext that CAS cannot distinguish from a legitimate retry. The loser's blob is opaque garbage under the winner's header, and there is no recovery path (`VaultKeyCustody.kt:48-54`). **Fix: the contract must fix K_c for the migration — derive it from the legacy vault key, which every claim holder already possesses — or bind every migration write to the claim nonce.**

**A2.2 — DATA LOSS: there is no claim-nonce precondition on v2 doc writes. Verified in the shipped code.** `writeDoc` (`apps/api/src/data/repositories/vaultRepository.ts:398-472`) checks ownership, backend, portfolio membership and CAS version — it never consults `migrating_by` (grep for `migratingBy`/`migratedTo` in that file returns nothing). Only the **flip** is serialized. So §11's "losers see the claim and wait" is an honour system, and a stale or returning claim holder can overwrite the committed vault's documents *after* another client flipped. Combined with A2.1 that is a concrete path to an unopenable vault. **Fix: reject doc writes while `migrating_by` is live and not yours (an `If-Claim: <nonce>` precondition).**

**A2.3 — UNADDRESSED: Drive-only vaults cannot migrate at all.** The claim is a CAS on the server-side legacy vault row, but `StorageMode.DRIVE` means there is **no account and never will be** — `app/src/main/java/at/bettertrack/app/data/storage/StorageMode.kt:47-52`; root gate `app/src/main/java/at/bettertrack/app/data/storage/StorageSurfaces.kt:213-218` and `app/src/main/java/at/bettertrack/app/ui/shell/BtRoot.kt:33-37` (*"A Drive-only user has no session and never will"*); session predicate `app/src/main/java/at/bettertrack/app/di/AppGraph.kt:645`; no server medium is ever constructed (`app/src/main/java/at/bettertrack/app/vault/server/ServerVaultConnection.kt:65-69`). Our vault identity is a locally-minted UUID the server has never seen (`app/src/main/java/at/bettertrack/app/vault/VaultStore.kt:69-76`, `:171-176`). §11 as written **excludes the entire Drive-only population — the mode the storage wizard exists to offer.** Need a client-local migration for Drive-only, which §13 already has the pattern for (copy → verify → marker → retire).

**A2.4 — the migration 409 envelope has no schema.** Claim/renew/flip conflicts return a top-level `{state: …}` beside `error` (`apps/api/src/services/vault/vaultService.ts:432-439`, `:452-457`, `:473-478`), but only `vaultVersionConflictResponseSchema` exists in contracts. Mobile would parse it blind. Add a zod schema beside the 412 one.

## A3. Objection 3 (keySlots / formatVersion) — **RESOLVED in text, UNBUILT in code**

§9 answers all four of my sub-asks: `formatVersion: 2` with a clean UPDATE_REQUIRED path; AAD input widened to include `formatVersion`, `vaultId` and slot index; the QR version member renamed to **`qr: 1`** (kills the `VAULT_DOCUMENT_VERSION` collision); a v2 recovery-kit layout. Good — that converts an installed-base "vault corrupt" scare into a correct update prompt. But per A0 there is no v2 header schema in code, so nothing is implementable or testable yet.

## A4. Objection 4 (QR) — **SUBSTANTIALLY RESOLVED; one residual security objection**

§10 adopts my option 2 *and* option 3 together: the payload is now `w = AES-GCM(KDF(pin), P)` with a 6-digit one-time PIN on a second screen, total TTL 120 s, FLAG_SECURE and recents exclusion mandated for native clients, screenshot warning on web. A screenshot alone no longer captures the secret. Real improvement — ack.

**A4.1 — RESIDUAL: the KDF is unspecified, and a 6-digit PIN alone is not enough against an offline attack.** §10 says literally `KDF(pin)` — no algorithm, no parameters — and there is nothing in code either. With a 6-digit PIN the search space is 10⁶, so **the KDF cost is the entire security margin of the scheme**. A photograph of the screen defeats FLAG_SECURE and yields `w` plus its GCM tag, i.e. an offline verification oracle. At Argon2id 64 MiB / t=3 (~0.35 s/guess) a full 10⁶ sweep is ≈97 CPU-hours — about an hour on a 100-core cloud box. The 120 s TTL provides **zero** protection here, because the attack runs offline against captured bytes; the TTL bounds the display, not the ciphertext's life. Ask, in preference order: (a) bind `w` to a receiver-supplied ephemeral public key so a captured QR is useless without the receiver's private key; (b) raise entropy — 10+ digits, or 6 words from the same BIP39 list — **and** state Argon2id parameters normatively; (c) make the unwrap single-use and server-mediated. In every case the KDF and its parameters must be in the contract.

## A5. Objection 5 (locked rows / aggregates / gates) — **RESOLVED, two better than asked; one scope clarification needed**

§12 gives the fourth coverage state `lockedExcluded`; nets render as **sum-of-visible plus a mandatory "+ N locked portfolios" qualifier**, never a bare total, with identical arithmetic on both clients (answers Q7); and account-scoped list surfaces get a **per-vault lock chip** rather than all-or-nothing (answers Q8 better than my either/or). §8's `unavailable` answers Q6 exactly as asked. This closes the `PriceCoverage`-certifies-a-short-total hole (`app/src/main/java/at/bettertrack/app/ui/prices/PriceStates.kt:43-58`).

**A5.1 — "locked = no reads" needs a scope ruling.** We accept **no writes while locked** — it simplifies idempotency and we will gate at `VaultStore.mutate` (`app/src/main/java/at/bettertrack/app/vault/VaultStore.kt:93-107`). But "no reads" collides with our architecture: reads come from a **plaintext Room working store** (`app/src/main/java/at/bettertrack/app/data/db/VaultEntities.kt:22-33`, *"Room is the working store … Reads never wait on Drive"*; rows are verbatim JSON at `:68-69`). Enforcing no-reads-at-rest means encrypting the working store — a data-at-rest redesign, not a policy flag, and not in P4's scope. Note also that no-writes-while-locked contradicts our shipped local-first guarantee (`docs/S3S4_STORAGE_PLAN.md:222`, *"local writes always succeed … Airplane-mode Drive-only must be fully functional"*) and the coordinator's contract (`app/src/main/java/at/bettertrack/app/vault/VaultSyncCoordinator.kt:54-58`); in BOTH mode today the app is fully usable with the vault locked. **Ask: read §8's "no reads" as "no plaintext rendering while locked" (UI-level).**

## A6. Objection 6 (raw custody) — **RESOLVED**

§10: *"the raw-storage opt-in is platform-optional — a platform with stronger native custody (Android Keystore) MAY decline to offer raw storage."* Exactly the ask; Android declines. Two notes, neither blocking: §2 lines 49-50 still carry the old "raw opt-in behind an explicit warning" text (superseded by the r2-wins clause at line 127, but it will mislead a vector author); and my secondary ask — that devices wrap `K_c` rather than `P` — is unaddressed, with §2's diagram still showing D wrapping P. Moot for us since we keep wrapping the VK (`app/src/main/java/at/bettertrack/app/vault/VaultKeyCustody.kt:112-116`).

## A7. Objection 7 (Drive rescope) — **RESOLVED**

§13 gives per-vault naming `btv2.{vaultId}.{header|common|p.{portfolioId}}` and a **copy → verify → marker → retire** rename migration, resumable by re-listing — precisely the shape I asked for, and it protects the orphaning risk in `app/src/main/java/at/bettertrack/app/vault/drive/DriveVaultFileName.kt:40-48`. Note §8's single-blob mutation rule is what makes an N-document layout tolerable on Drive at all, given our CAS there is an approximation (`app/src/main/java/at/bettertrack/app/vault/drive/DriveDataHome.kt:132-169`, `:378-384`). Residual: the new names and the marker need a `vaultId`, which Drive-only installs do not have server-side (ties to A2.3); and discovery becomes N name-queries against a 100-file scan cap (`DriveDataHome.kt:234-241`, `:498`). Cost, not objection.

## A8. FRESH HOLE — §13's `both` reconcile rule is data loss

§13: *"reconcile = highest (version, then updatedAt) wins."* That is **last-writer-wins on a whole document**, and it is not what our engine does. Ours is a merge: `app/src/main/java/at/bettertrack/app/vault/VaultSyncCoordinator.kt:62-67` — *"**Conflict = merge, never overwrite**"* — with a per-entity union (`app/src/main/java/at/bettertrack/app/vault/VaultMerge.kt:98-106`, `:254-255`) and a five-level tie-break in which document version never appears: `rev → live-vs-tombstone → editedAt → editedBy → canonical content` (`VaultMerge.kt:172-198`).

Our plan's "highest readable version wins" is explicitly **rule 4, the fallback for unreadable/corrupt bytes only** (`docs/S3S4_STORAGE_PLAN.md:211-220`, *"corrupt bytes kept locally for a restore picker, never silently discarded"*). §13 promotes our degenerate fallback to the primary path.

Concrete loss, grounded in the code: both devices hold a doc at version 5. The phone books a trade offline → local version 6 (`VaultStore.kt:104`). The web books a cash movement → its own version 6, pushed to the server medium first. The phone pushes with `ifVersion = 5`, gets a conflict, and today reconciles by merge (`VaultSyncCoordinator.kt:229`, `:360-374`) with `forceDivergent` true (`:369`) — the union carries **both** entities at version 7. Under §13, the two sides are both at version 6, so `updatedAt` decides and **the entire loser document is discarded**: a trade or a cash movement that never existed on any replica, the winner chosen by clock skew between a phone and a browser. The same rule inverts our rule 2 (edit beats tombstone — `VaultMerge.kt:186-188`, rationale at `:168-170`: *"an edit that vanished is data loss the user cannot even see"*) and would silently drop the loser's `mirrorProvenance` (`VaultMerge.kt:124-126`: *"a merge must never be the step that loses an identity map"*).

**Fix — one sentence:** §13 must say that `both` reconciles by the §4 per-entity merge rules, with `(version, updatedAt)` used **only** as the whole-blob fallback for undecryptable candidates.

## A9. Needs (a)–(f)

| Need | Verdict |
|---|---|
| (a) N1 format version + naming | **RESOLVED in text** (§9); **unbuilt in code** (A0) |
| (b) N2 vectors | **UNADDRESSED IN FACT.** §16 promises six families "produced by the platform hardening pass and published"; `packages/domain` is untouched and the v1 vectors have not moved. 0/6. We cannot self-generate (`tools/domain-vectors/README.md:155-158`) — this gates P4's crypto track entirely |
| (c) N3 endpoints | **RESOLVED AND BUILT, beyond the ask.** Routes at `apps/api/src/http/routes/vaultsRoutes.ts` (`:181` list, `:257` create, `:273` patch, `:290` delete, `:339`–`:373` the six doc surfaces, `:200`–`:231` migration); join/leave at `apps/api/src/http/routes/portfolioRoutes.ts:734`/`:773`, join genuinely one transaction with a zero-cleartext purge probe that aborts (`apps/api/src/data/repositories/vaultRepository.ts:513-534`); bearer allowlist extended as a second default-closed list covering all six doc surfaces plus list (`apps/api/src/http/middleware/bearerAuth.ts:58-66`). **412 carries `currentVersion` as a TOP-LEVEL SIBLING of `error`**, not inside `error.details` — `apps/api/src/http/errorHandler.ts:25-32` spreads the envelope first; constructed at `apps/api/src/services/vault/vaultService.ts:128-134`. Three residuals: A2.4; the bearer `GET /vaults` returns a narrower body than a session GET (`vaultsRoutes.ts:183-192`) so we must branch on auth mode; **and the v1 412 regression below** |
| (d) N4 constants | **PARTIAL.** Real constants: migration TTL 15 min (`vaults.ts:314`), size caps (`:62-70`). Doc-only: QR TTL 120 s. Doc-only and **absent from the repo**: the BIP39 wordlist. **Unaddressed:** the `keySlots` slot format and its wrap AAD |
| (e) N5 error codes | **RESOLVED AND EXCEEDED**, with a caveat. Ten canonical codes exist with real EN+DE strings (`apps/web/src/i18n/messages/en.json:3774-3792` and `de.json` same range). But **three of the ten are never thrown by the server** — `VAULT_LOCKED_WRITE_REFUSED`, `VAULT_CROSS_BLOB_REFUSED`, `VAULT_FORMAT_UPDATE_REQUIRED` are client-enforced, while §15 presents all ten as wire codes. And **seven codes beyond the ten are real and thrown**: `VAULT_PRECONDITION_REQUIRED` (428 on every PUT without a precondition — matches our discipline, ack), `VAULT_NAME_TAKEN`, `VAULT_PORTFOLIO_ALREADY_VAULTED`, `VAULT_JOIN_BLOCKED`, `VAULT_RESTORE_INVALID`, `VAULT_ID_TAKEN`, `VAULT_PORTFOLIO_NOT_VAULTED`. We must catalogue all 17, not 10 (`app/src/main/java/at/bettertrack/app/data/api/BtErrorCopy.kt:66-317` + both `strings.xml`) |
| (f) N6 twelve questions | 11 resolved, 1 answered wrongly — see A10 |

**New finding — v1 412 regression, and it hurts us today.** The same commit that built `EnvelopeApiError` for v2 **deleted** the v1 route's only conflict-version hint:

```diff
apps/api/src/http/routes/vaultRoutes.ts  @@ -485,8 +509,6 @@
         case 'precondition_failed':
-          if (result.currentVersion !== null)
-            res.setHeader('ETag', vaultEtag(result.currentVersion));
           throw preconditionFailed();
```

Our shipped app is on the v1 route and will be for all of P4. Every v1 CAS loss now costs an extra `GET /vault` that was previously free. `EnvelopeApiError` exists two files away — please apply it to the v1 route too.

## A10. The twelve questions

| Q | Verdict |
|---|---|
| Q1 kind ownership | **PARTIAL.** §8 names 12 for `common`; the other 14 of our 26 (`VaultContracts.kt:66-93`) fall to `portfolio` only by implication, and the server's leave-restore schema restricts to **9** portfolio-scoped kinds (`packages/contracts/src/vaults.ts:379-389`). Three different partitions (26/12/9), none normative. Vector family (3) was to pin it and does not exist. Also three of §8's twelve are document members, not kinds (A1.1) |
| Q2 in-vault cross-portfolio transfer | **RESOLVED** (refused as one op; guided two-step). Residual A1.3 |
| Q3 cross-vault transfer | **RESOLVED** — refused at UI and op layer in v2 |
| Q4 split race / resume | **RESOLVED in text**; fresh holes A2.1, A2.2, A2.3 |
| Q5 writes while locked | **RESOLVED** — no writes, inline unlock, no queued writes. Clarification A5.1 |
| Q6 missing/undecryptable blob | **RESOLVED** — `unavailable`, never €0, banner names doc + version, rest of vault usable. Exactly as asked |
| Q7 net worth with a locked vault | **RESOLVED** — §12 sum-of-visible + mandatory qualifier, identical on both clients |
| Q8 account-scoped surfaces | **RESOLVED, better than asked** — per-vault lock chip, not all-or-nothing |
| Q9 Drive naming + rename migration | **RESOLVED** (§13). Residual: Drive-only `vaultId` (A2.3) |
| Q10 `both` semantics | **ANSWERED, AND THE ANSWER IS WRONG** — see A8 |
| Q11 metadata leak | **RESOLVED** — §14 accepted by design, stated in the explainer, padding noted as future work |
| Q12 clientSecurity / mirrorProvenance scope | **RESOLVED in principle** — per-vault, divergence within one vault only, leave/disable consults only the owning vault's provenance. Residual: modelled as kinds not members (A1.1), and under §13's LWW the divergence rules would never run at all (A8) |

## A11. Effort, revised

Net movement is small. §8's single-blob rule and "locked = no writes" **remove** the cross-blob transaction design and the vault-op idempotency project (−4 d); §11 still requires honouring `op.clientId` on replay (+1 d); new work appears for the two-step transfer UI with honest unmatched-leg rendering (+2 d), the `unavailable` state and banner (+1 d), BIP39 generation/checksum/NFKD (+1 d), and the two-screen PIN QR flow with FLAG_SECURE (+1 d). Drive and Room are as scoped.

**≈60 builder-days (12–13 weeks single-threaded).** But roughly **12 of those days cannot start**: the v2 header format is not in code and 0 of 6 vector families exist, so the crypto track is schedule-blocked regardless of the estimate. The server track (routes, join/leave, CAS client, migration client) is unblocked today and is where P4 should begin.
