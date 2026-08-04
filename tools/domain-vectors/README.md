# `tools/domain-vectors` — the domain-engine conformance oracle

Generator input only. **Nothing in here is shipped, compiled into the APK, or
referenced by app code.** `vendor/` is a read-only snapshot of the BetterTrack
platform monorepo pinned at the commit recorded in `PINNED_AT`.

## Why this exists

`docs/S3S4_STORAGE_PLAN.md` §3 mandates that Drive-autonomous mode compute money
with the platform's **audited** `packages/domain` engine, ported as a *literal*
translation — never a hand-written reimplementation. A literal port is only
worth anything if it is *proven* equal, so:

```
vendor/domain/src/*.ts   ──generate.ts──►  app/src/test/resources/domain-vectors/*.json
       (the oracle)         (real TS)              (machine-readable vectors)
                                                            │
                                                            ▼
                                          app/src/test/.../DomainVectorTest.kt
                                              replays them against the
                                          at.bettertrack.app.domain Kotlin port
                                            with EXACT Double equality (0.0)
```

Expected values are **never typed by hand**. `generate.ts` imports the vendored
TypeScript and records what it actually returns, so the JSON is the TS engine's
own output at full `double` precision.

## Detecting platform drift

The vendored snapshot is a *copy*, so it cannot silently follow the platform.
To check for drift, re-copy and diff:

```sh
SRC=/path/to/BetterTrack            # the platform monorepo
DST=tools/domain-vectors/vendor

git -C "$SRC" fetch origin
for f in $(git -C "$SRC" ls-tree -r --name-only origin/main packages/domain); do
  git -C "$SRC" show "origin/main:$f" | diff -u "$DST/domain/${f#packages/domain/}" - || echo "DRIFT: $f"
done
```

A non-empty diff means the platform moved. The fix is one command
(`npm run generate` below) followed by `./gradlew :app:testGithubDebugUnitTest` —
so drift surfaces as a **failing test, not as wrong money** (plan §6 risk 11).
Update `PINNED_AT` when you re-vendor.

## Regenerating the vectors

Requires only Node (built-in TypeScript type-stripping, Node ≥ 22). There is
**no `node_modules`, no lockfile, and nothing was installed into the platform
monorepo.** `package.json` here declares `"type": "module"` and a script alias;
it pulls no dependencies.

```sh
cd tools/domain-vectors
node --experimental-strip-types generate.ts     # or: npm run generate
```

Outputs, all overwritten in place:

| File | Contents |
|---|---|
| `app/src/test/resources/domain-vectors/holdings.json` | vectors for `reducePosition`, `deriveHoldings`, `dailyCloseSeries`, `valueOverTime`, `costBasisOverTime`, `netFlowsOverTime`, `timeWeightedReturn`, `rebasePerformance` |
| `app/src/test/resources/domain-vectors/seriesStats.json` | vectors for `computeSeriesStats`, `toPerformanceSeries`, `deflateSeries`, `indexAveragePctPerYear`, `computeContributions`, `compareSeriesStats` |
| `app/src/test/resources/domain-vectors/settingsScope.json` | vectors for `resolvePortfolioSetting` |
| `app/src/test/resources/domain-vectors/serverTwrParity.json` | the server-generated TWR golden, reshaped as `timeWeightedReturn` inputs |
| `app/src/test/resources/domain-vectors/MANIFEST.json` | per-module counts + every case the generator deliberately skipped, with a reason |

## Vector format (plan §3.4)

```jsonc
{
  "fn": "valueOverTime",
  "case": "carries a custom asset value forward between sparse points",
  "input": {
    "transactions": [ … ],
    "assets": [ … ],
    "today": "2026-01-05",
    // FX tables travel WITH the input so a deterministic Kotlin fake can
    // reproduce the exact converter the TS suite used:
    "fx": { "kind": "flat", "rates": { "EUR": 1, "USD": 0.9 } }
  },
  "output": [ { "date": "2026-01-01", "valueEur": 1000 } ],
  "throws": null          // or { "name": "OversellError", "message": "…" }
}
```

`fx.kind` is one of:

- `identity` — `toBase(amount) = amount`
- `flat` — `amount · rates[currency]`; an unknown currency rejects
- `dated` — `EUR` is identity, anything else **requires** `opts.date` and looks
  up `ratesByDate[currency][date]`; a missing pair rejects (so a port that asks
  for a spot rate, or the wrong day, fails loud instead of silently passing)

Doubles round-trip exactly: JS `JSON.stringify` emits the shortest string that
reparses to the identical double, and Kotlin's `String.toDouble()` correctly
rounds — so the JSON is not a precision-losing intermediary.

## What the generator deliberately does NOT emit

Interaction and identity assertions are not pure input→output data. They are
listed in `MANIFEST.json` with a reason and **hand-ported** as ordinary Kotlin
tests in `app/src/test/java/at/bettertrack/app/domain/DomainHandPortedTest.kt`:

- `vi.fn()` FX call-count / call-argument assertions (converter coalescing) —
  hand-ported against a *counting* Kotlin fake
- assertions on an error's fields beyond the fact that it threw
  (`OversellError.requested` / `.held` / `.assetId`)
- referential-identity checks (`expect(real).not.toBe(input)` — "returns a fresh
  array", meaningless in Kotlin where the port returns new lists by construction)
