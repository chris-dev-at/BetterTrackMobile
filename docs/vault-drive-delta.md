# Drive data home — upstream delta the app has not ported (vault pass 2026-10-30, item 5)

**Status: DOCUMENTED, NOT PORTED.** `app/.../vault/drive/DriveDataHome.kt` (+ `DriveVaultFileName.kt`) is the port of the OLD
`apps/web/src/user/vault/driveDataHome.ts` (1,048 lines, vendored at `7389f82`). The ef73d282 re-pin moved it to
`apps/web/src/user/vault/drive/driveDataHome.ts` and rewrote it to 1,616 lines (`git diff -M 7389f82 HEAD --
tools/domain-vectors/vendor/web-vault/` → `{ => drive}/driveDataHome.ts | 776 +++---`). The only piece already
ported is `hasRevokedGrant` + its `driveFetch` branch (item 3, `c9bd299`). Per-symbol numbers below come from
`tools/domain-vectors/drive-segment-diff.py` (usage in its header); a doc comment counts toward the symbol above it.

## What changed, in one paragraph

The vault moved out of the hidden `appDataFolder` into ONE visible **"BetterTrack Vaults"** folder per owner
(paranoid final ruling 2026-08-20: "visible drive.file folder"), so objects are no longer found by `name` +
`spaces=appDataFolder` but by **appProperties digests only** (`ownerDigest`, `formatVersion`, and for the new
envelope-v2 **doc address** also `vaultDigest` + `docKind`; `docId` deliberately not on the object, resolved by
downloading candidates and matching the authenticated header incl. `accountBinding`). Listing is **paged**
(`nextPageToken`, 100/page, hard ceiling 1,000 objects per address, repeated-token guard), trashed objects are
absent, the home **caches the last resolved file id** (and folder id) to skip the list on reads, and prefetched
bodies are reused so a doc read is one download. Folder creation has a create-then-reconcile race resolution
(lowest id wins, the loser deletes its empty folder). A 400/401 `invalid_grant`/`invalid_token` envelope is
`revoked` (ported). **Scope consequence for the app:** a visible folder needs `drive.file`, not `drive.appdata` —
`GoogleAuthProvider.kt` binds the app to `DRIVE_APPDATA_SCOPE` and says "never widen it"; the port therefore needs
an owner-visible scope decision first (and the Google OAuth client for `at.bettertrack.app` still does not exist).

## Function-by-function (TS @ef73d282 vs TS @7389f82 → Kotlin today)

| TS symbol | Δ lines | what changed upstream | Kotlin today | port note |
|---|---|---|---|---|
| `FILE_FIELDS` | +1 −1 | adds `trashed` | `FILE_FIELDS` const | add `trashed` |
| `DUPLICATE_SCAN_LIMIT` | GONE | replaced by paging | `DUPLICATE_SCAN_LIMIT` as `pageSize`, one page | replace by the loop in `findFile` |
| `DRIVE_LIST_PAGE_SIZE`, `DRIVE_ADDRESS_OBJECT_LIMIT` | NEW | 100 per page, 1,000 objects per address | — | constants |
| `DRIVE_VAULT_DOC_FILE_CONTEXT`, `DRIVE_OWNER_CONTEXT`, `DRIVE_VAULT_DIGEST_CONTEXT` | NEW | `bettertrack-drive-vault-v2:` / `-owner-v1:` / `-vault-id-v1:` hash contexts | only the v1 file-name context | constants; vectors needed (see bottom) |
| `DRIVE_FOLDER_NAME`, `_MIME`, `_MARKER` | NEW | "BetterTrack Vaults", folder MIME, `folderMarker = bettertrack-vaults-v1` appProperty | — | constants |
| `DriveFile` / `ValidDriveFile` | +1 / +3 | `trashed`; valid file carries `ownerDigest`, `vaultDigest`, `docKind` | `ValidDriveFile` (id, version, formatVersion, size, updatedAt, headRevisionId) | extend |
| `DriveAddress` | NEW (+12) | `{mode: legacy\|doc, accountId, vaultId, docId, docKind, fileName, ownerDigest, vaultDigest, accountBinding}` | — | new data class |
| `DriveFileResult` | +11 −1 | `ok` may carry prefetched `bodies` by file id | sealed `DriveFileResult` | add the map |
| `DriveReplicaCycle` | +1 −1 | comment only ("same-address") | `DriveReplicaCycle` (converge is a stub) | none |
| `DriveDataHomeOptions` | +10 −3 | optional `vaultId`+`docId`+`docKind` (all or none), `tokens` gains `markRevoked`, `folderId` seam | constructor (`accountId`, `auth`, bases) | add the doc address + folder seam |
| `createDriveDataHome` | +10 −1 | validates the all-or-none doc address, computes `addressPromise`, holds `cachedFolderId` / `cachedFileId` | class init | port the caches with the invalidation rules |
| `read()` | +2 | tries `readCachedFile()` before the list | list → download | port after `readCachedFile` |
| `write()` | +2 −1 | `inspectOutgoing(envelope, address)` (address-aware) | `write` | follows `inspectOutgoing` |
| `info()` | +8 | (segment absorbs `readCachedFile`'s doc comment) | `info` | none |
| `readCachedFile` | NEW (+21) | by-id read of the cached file; metadata blip falls through to the list; doc mode re-checks the address | — | new |
| `observeReplicas` | +3 −1 | reuses prefetched bodies | `observeReplicas` | small |
| `deleteObservedFile` | +7 −1 | reuses prefetched bodies on the re-list | `delete()` (different shape) | re-align with the TS cycle |
| `findFile` | +109 −47 | appProperties filter query (`ownerDigest`, `formatVersion`, `vaultDigest`, `docKind`, `trashed = false`), paging with ceiling + repeated-token guard, `nextPageToken` in the field mask, trashed-in-flight skipped, doc mode downloads candidates and keeps header matches, sets `cachedFileId` | name query in `appDataFolder`, one page | the core of the port |
| `getFile` | +5 −3 | 404/trashed → `forgetIfCached`; address-aware validation | `getFile` | small |
| `forgetIfCached` | NEW (+26) | drops `cachedFileId` when the id went absent (segment includes `fileMatchesAddress`'s long doc comment) | — | new |
| `fileMatchesAddress` | NEW (+41) | downloads a candidate, `inspectVaultDocEnvelope`, matches vaultId/docId/docKind/accountBinding → match (bytes kept) / skip / corrupt | — | needs the envelope-v2 doc inspector in Kotlin (pv `PvEnvelopeContract` has the header) |
| `download` | +26 −20 | optional prefetched bytes; address-aware `inspectEnvelope` | `download` | follows |
| `upload` | +15 | (mostly the reconciliation path; folder ensured in `sendUpload`) | `upload` | follows |
| `sendUpload` | +47 −18 | new file → `ensureFolder` + `parents: [folderId]`; doc mode appProperties `{ownerDigest, vaultDigest, docKind, docVersion, formatVersion}`, legacy `{ownerDigest, vaultVersion, formatVersion}`; failed create drops `cachedFolderId`; sets `cachedFileId` | `parents: [appDataFolder]`, `{vaultVersion, formatVersion}` | the write half of the port |
| `ensureFolder` | NEW (+21) | cached → list → create → re-list, lowest id wins, loser discarded if empty | — | new |
| `listFolders` | NEW (+47) | `mimeType = folder and ownerDigest and folderMarker and trashed = false` | — | new |
| `createFolder` | NEW (+43) | POST folder with `appProperties {ownerDigest, folderMarker}` | — | new |
| `discardEmptyFolder` | NEW (+17) | deletes the losing folder only if empty | — | new |
| `convergeDuplicateFiles` | +1 −1 | `inspectOutgoing(…, address)` | `converge` stub | unchanged gap |
| `driveFetch` | +22 −2 | `hasRevokedGrant` on 400/401 before the 401 branch | **ported** (item 3) | done |
| `hasRevokedGrant` | NEW (+17) | explicit `invalid_grant`/`invalid_token` only | **ported** + vector replay | done |
| `validateFile` | +20 −5 | takes the address; trashed → absent; version from `docVersion` (doc) or `vaultVersion` (legacy); `formatVersion` per mode; `ownerDigest` (+ `vaultDigest`, `docKind`) must match; no name check | name + `vaultVersion` checks | rewrite |
| `driveVaultFileName` | +10 −1 | optional `vaultId`+`docId` (both or neither) → `bettertrack-drive-vault-v2:<account>:<vault>:<doc>` | `driveVaultFileName(accountId)` | extend; vectors |
| `driveOwnerDigest`, `driveVaultDigest`, `sha256Base64Url`, `appPropertyFilter` | NEW | SHA-256 base64url of the owner / vault contexts; the Drive `appProperties has {…}` query term | — | new |
| `driveAddress` | NEW (+38) | builds the legacy or doc address (fileName, digests, `accountBinding = sha256b64url(VAULT_ACCOUNT_BINDING_INFO_PREFIX + accountId)`) | — | new |
| `inspectOutgoing` | +34 −1 | doc mode: `inspectVaultDocEnvelope`, header must match the address, version = `docVersion`; legacy unchanged | `inspectEnvelopeBytes` (v1 only) | rewrite |
| `inspectEnvelope` | +7 −2 | address-aware, formatVersion per mode | in `download` | follows |
| unchanged | 0 | `DRIVE_API`, `DRIVE_UPLOAD_API`, `DRIVE_VAULT_FILE_CONTEXT/PREFIX`, `DriveDeleteResult`, `DriveReplicaVerifier`, `DriveDataHome`, `fixedReplicaCycle`, `blockedDelete`, `deletePending`, `driveFileFailure`, `duplicateConflict`, `compareDriveFiles`, `sameFileSet`, `sameReadableObservationSet`, `sameConvergenceSet`, `sameDriveFile`, `base64url`, `malformedMetadata`, `cloneReadResult`, `corrupt`, `transport`, `tokenFailure`, `httpFailure`, `headersObject` | — | — |

## Before the next pass starts

1. **Owner decision:** the `drive.file` scope (visible folder) vs the app's `drive.appdata` binding; and the Google OAuth client (still an owner action).
2. **Vectors first:** `driveVaultFileName` (doc form), `driveOwnerDigest`, `driveVaultDigest`, the `accountBinding` digest and `validateFile`'s accept/refuse table should be generated from the vendored TS (pattern: `tools/domain-vectors/generate-drive-revoked-grant.mjs`) before any Kotlin is written.
3. **Upstream tests to mirror:** `apps/web/src/user/vault/drive/driveDataHome.test.ts` @ef73d282 (not vendored yet; vendor it beside the source).
4. **`identity-mismatch`** (item 3 added the reason) is produced by the web's Drive identity verification (`drive/driveIdentity.ts`, `driveConnectionRegistry.ts`) — part of this same port.
