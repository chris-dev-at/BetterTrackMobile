/**
 * Domain-vector generator (S3/S4 plan §3.4 step 2).
 *
 * Imports the PINNED vendored `packages/domain` TypeScript sources and replays
 * the exact inputs the platform's vitest suites use, recording what the real TS
 * engine returns. The recorded values become the conformance oracle the Kotlin
 * port is replayed against with EXACT double equality.
 *
 * Expected values are never typed by hand — everything in `output` / `throws`
 * comes back from the vendored engine at full double precision.
 *
 * Run:  node --experimental-strip-types generate.ts
 */

import { copyFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  costBasisOverTime,
  dailyCloseSeries,
  deriveHoldings,
  netFlowsOverTime,
  QTY_EPSILON,
  rebasePerformance,
  reducePosition,
  timeWeightedReturn,
  valueOverTime,
  type CurrencyConverter,
  type HoldingAssetInput,
  type PricePoint,
  type Transaction,
  type ValueOverTimeAsset,
} from './vendor/domain/src/holdings.ts';
import {
  compareSeriesStats,
  computeContributions,
  computeSeriesStats,
  deflateSeries,
  indexAveragePctPerYear,
  toPerformanceSeries,
  type ComparisonMetricVector,
  type ComparisonSeriesInput,
  type ContributionInput,
  type Deflator,
  type StatSeriesPoint,
} from './vendor/domain/src/seriesStats.ts';
import { resolvePortfolioSetting } from './vendor/domain/src/settingsScope.ts';
import {
  applyCashMovement,
  CASH_MOVEMENT_KINDS,
  CASH_MOVEMENT_SIGN,
  cashBalance,
  cashBalanceOverTime,
  cashBalancesBySource,
  cashBySourceOverTime,
  EXTERNAL_CASH_MOVEMENT_KINDS,
  externalCashFlowsForTwr,
  floorCents,
  isExternalCashMovement,
  netWorthSeries,
  pairedTransferMovements,
  projectCashLedger,
  projectCashLedgerBySource,
  setBalanceDelta,
  setBalanceMovement,
  spendableAsOf,
  type CashLedgerEntry,
  type CashMovement,
  type CashMovementKind,
  type SourcedCashMovement,
} from './vendor/domain/src/cashLedger.ts';

import serverTwrParity from './vendor/fixtures/serverTwrParity.fixture.json' with { type: 'json' };

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(HERE, '..', '..', 'app', 'src', 'test', 'resources', 'domain-vectors');

// ---------------------------------------------------------------------------
// Vector plumbing
// ---------------------------------------------------------------------------

interface Vector {
  fn: string;
  case: string;
  input: unknown;
  output: unknown;
  throws: { name: string; message: string } | null;
}

interface Skip {
  fn: string;
  case: string;
  reason: string;
}

const vectors: Record<string, Vector[]> = {
  holdings: [],
  seriesStats: [],
  settingsScope: [],
  cashLedger: [],
  serverTwrParity: [],
};
const skips: Skip[] = [];

function skip(fn: string, name: string, reason: string): void {
  skips.push({ fn, case: name, reason });
}

/** Record a vector by CALLING the vendored engine — sync flavour. */
function emit(module: string, fn: string, name: string, input: unknown, run: () => unknown): void {
  let output: unknown = null;
  let thrown: { name: string; message: string } | null = null;
  try {
    output = run();
  } catch (err) {
    const e = err as Error;
    thrown = { name: e.name, message: e.message };
  }
  vectors[module]!.push({ fn, case: name, input, output, throws: thrown });
}

/** Record a vector by CALLING the vendored engine — async flavour. */
async function emitAsync(
  module: string,
  fn: string,
  name: string,
  input: unknown,
  run: () => Promise<unknown>,
): Promise<void> {
  let output: unknown = null;
  let thrown: { name: string; message: string } | null = null;
  try {
    output = await run();
  } catch (err) {
    const e = err as Error;
    thrown = { name: e.name, message: e.message };
  }
  vectors[module]!.push({ fn, case: name, input, output, throws: thrown });
}

// ---------------------------------------------------------------------------
// FX: the converters the suites use, declared as data so the Kotlin fake can
// reproduce them exactly (plan §3.4: "FX rate tables emitted alongside inputs").
// ---------------------------------------------------------------------------

type FxSpec =
  | { kind: 'identity' }
  | { kind: 'flat'; rates: Record<string, number> }
  | { kind: 'dated'; ratesByDate: Record<string, Record<string, number>> };

/** `stubConverter` in holdings.test.ts (default rates spelled out). */
const FX_STUB: FxSpec = { kind: 'flat', rates: { EUR: 1, USD: 0.9 } };
/** `identity` in dailySnapshotSeries.test.ts — every currency passes through. */
const FX_IDENTITY: FxSpec = { kind: 'identity' };

function makeConverter(spec: FxSpec): CurrencyConverter {
  if (spec.kind === 'identity') {
    return { toBase: (amount: number) => Promise.resolve(amount) };
  }
  if (spec.kind === 'flat') {
    // Mirrors holdings.test.ts `stubConverter`.
    return {
      toBase: (amount: number, currency: string) => {
        const rate = spec.rates[currency];
        if (rate === undefined) return Promise.reject(new Error(`no rate for ${currency}`));
        return Promise.resolve(amount * rate);
      },
    };
  }
  // Mirrors holdings.test.ts `datedStubConverter` and dailySnapshotSeries `fxConverter`:
  // EUR is identity; anything else REQUIRES opts.date and a known (currency, date).
  return {
    toBase: (amount: number, currency: string, opts?: { date?: string }) => {
      if (currency === 'EUR') return Promise.resolve(amount);
      const rate = opts?.date === undefined ? undefined : spec.ratesByDate[currency]?.[opts.date];
      if (rate === undefined) {
        return Promise.reject(new Error(`no rate for ${currency} on ${opts?.date ?? 'spot'}`));
      }
      return Promise.resolve(amount * rate);
    },
  };
}

// ---------------------------------------------------------------------------
// Test-suite helpers, transcribed verbatim from the vitest files
// ---------------------------------------------------------------------------

/** holdings.test.ts `tx()` */
function tx(
  over: Partial<Transaction> & Pick<Transaction, 'side' | 'quantity' | 'price'>,
): Transaction {
  return {
    assetId: over.assetId ?? 'A',
    side: over.side,
    quantity: over.quantity,
    price: over.price,
    fee: over.fee ?? 0,
    executedAt: over.executedAt ?? '2026-01-01T00:00:00Z',
    allowUncovered: over.allowUncovered,
    uncoveredEntryPrice: over.uncoveredEntryPrice,
  };
}

/** dailySnapshotSeries.test.ts `txn()` */
function txn(overrides: Partial<Transaction> & { executedAt: string }): Transaction {
  return { assetId: 'a1', side: 'buy', quantity: 1, price: 100, fee: 0, ...overrides };
}

/** dailySnapshotSeries.test.ts `asset()` */
function asset(assetId: string, currency: string, priceDates: readonly string[]): ValueOverTimeAsset {
  return { assetId, currency, prices: priceDates.map((date) => ({ date, close: 1 })) };
}

/** dailySnapshotSeries.test.ts `at()` */
const at = (day: string): string => `${day}T10:00:00.000Z`;

/** seriesStats.test.ts `pt()` */
function pt(date: string, value: number): StatSeriesPoint {
  return { date, value };
}

/** seriesStats.test.ts `vec()` */
function vec(over: Partial<ComparisonMetricVector> = {}): ComparisonMetricVector {
  return {
    totalReturnPct: 10,
    cagrPct: 8,
    maxDrawdownPct: -5,
    volatilityPct: 12,
    bestDayPct: 3,
    worstDayPct: -4,
    ...over,
  };
}

/** seriesStats.test.ts `input()` */
function cmpInput(id: string, over: Partial<ComparisonMetricVector> = {}): ComparisonSeriesInput {
  return { id, metrics: vec(over) };
}

// ===========================================================================
// holdings.ts
// ===========================================================================

function reduce(name: string, txns: Transaction[]): void {
  emit('holdings', 'reducePosition', name, { transactions: txns }, () => reducePosition(txns));
}

function genReducePosition(): void {
  // --- average-cost basis (BUY re-averages, fees capitalised) ---
  reduce('single buy, no fee', [tx({ side: 'buy', quantity: 10, price: 100 })]);
  reduce('single buy capitalises the fee', [tx({ side: 'buy', quantity: 10, price: 100, fee: 5 })]);
  reduce('two buys re-average with fees', [
    tx({ side: 'buy', quantity: 10, price: 100, fee: 5, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'buy', quantity: 5, price: 120, executedAt: '2026-01-02T00:00:00Z' }),
  ]);

  // --- SELL realizes P/L and leaves average cost unchanged ---
  reduce('realized P/L = qty*(price-avg) - fee; avg unchanged', [
    tx({ side: 'buy', quantity: 10, price: 100, fee: 5, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'buy', quantity: 5, price: 120, executedAt: '2026-01-02T00:00:00Z' }),
    tx({ side: 'sell', quantity: 4, price: 130, fee: 2, executedAt: '2026-01-03T00:00:00Z' }),
  ]);
  reduce('interleaved buy/sell: the running average drives realized P/L', [
    tx({ side: 'buy', quantity: 10, price: 100, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 5, price: 110, executedAt: '2026-01-02T00:00:00Z' }),
    tx({ side: 'buy', quantity: 5, price: 200, executedAt: '2026-01-03T00:00:00Z' }),
  ]);
  reduce('orders by executedAt, not input order; realization index points at the input row', [
    tx({ side: 'buy', quantity: 5, price: 200, executedAt: '2026-01-03T00:00:00Z' }),
    tx({ side: 'buy', quantity: 10, price: 100, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 5, price: 110, executedAt: '2026-01-02T00:00:00Z' }),
  ]);

  // --- precision (exact decimal cases, asserted with toBe upstream) ---
  reduce('buy with fee: avg = (8*100.25 + 2)/8 = 100.5 exactly', [
    tx({ side: 'buy', quantity: 8, price: 100.25, fee: 2 }),
  ]);
  reduce('re-average: (4*100.5 + 4*101.5 + 1)/8 = 101.125 exactly', [
    tx({ side: 'buy', quantity: 4, price: 100.5, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'buy', quantity: 4, price: 101.5, fee: 1, executedAt: '2026-01-02T00:00:00Z' }),
  ]);
  reduce('sell: realized = 4*(130.75-100.5) - 2.5 = 118.5 exactly; avg unchanged', [
    tx({ side: 'buy', quantity: 8, price: 100.25, fee: 2, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 4, price: 130.75, fee: 2.5, executedAt: '2026-01-02T00:00:00Z' }),
  ]);
  reduce('sell at avg cost: realized = -fee exactly', [
    tx({ side: 'buy', quantity: 2, price: 50.25, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 1, price: 50.25, fee: 1.25, executedAt: '2026-01-02T00:00:00Z' }),
  ]);
  reduce('handles 6-dp fractional quantities: closing lands on exactly 0', [
    tx({ side: 'buy', quantity: 0.123456, price: 81, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 0.123456, price: 90, executedAt: '2026-01-02T00:00:00Z' }),
  ]);

  // --- selling the whole position ---
  reduce('selling exactly the held quantity flattens to 0 and resets avg', [
    tx({ side: 'buy', quantity: 3.5, price: 10, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 3.5, price: 12, executedAt: '2026-01-02T00:00:00Z' }),
  ]);
  reduce('floating-point dust from a sell-everything clamps to exactly 0', [
    tx({ side: 'buy', quantity: 0.1, price: 10, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'buy', quantity: 0.2, price: 10, executedAt: '2026-01-02T00:00:00Z' }),
    tx({ side: 'sell', quantity: 0.3, price: 10, executedAt: '2026-01-03T00:00:00Z' }),
  ]);

  // --- negative-sell rejection (throws are pure data: input -> error name+message) ---
  reduce('rejects a sell that would push held quantity negative', [
    tx({ side: 'buy', quantity: 10, price: 100, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 11, price: 100, executedAt: '2026-01-02T00:00:00Z' }),
  ]);
  reduce('rejects selling with nothing held', [tx({ side: 'sell', quantity: 1, price: 100 })]);
  reduce('OversellError carries the requested and held quantities', [
    tx({ side: 'buy', quantity: 3.5, price: 10, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 4, price: 10, executedAt: '2026-01-02T00:00:00Z' }),
  ]);
  skip(
    'reducePosition',
    'OversellError carries the requested and held quantities (field assertions)',
    'asserts err.requested / err.held / err.assetId, not just that it threw — hand-ported in DomainHandPortedTest ("OversellError carries the requested and held quantities")',
  );
  reduce('rejects an oversell of one stored quantity unit (1e-8)', [
    tx({ side: 'buy', quantity: 5, price: 10, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 5 + 1e-8, price: 10, executedAt: '2026-01-02T00:00:00Z' }),
  ]);
  reduce('rejects an oversell after partial sells reduced the held quantity', [
    tx({ side: 'buy', quantity: 10, price: 10, executedAt: '2026-01-01T00:00:00Z' }),
    tx({ side: 'sell', quantity: 6, price: 10, executedAt: '2026-01-02T00:00:00Z' }),
    tx({ side: 'sell', quantity: 4.001, price: 10, executedAt: '2026-01-03T00:00:00Z' }),
  ]);
  reduce('allows a sell within QTY_EPSILON of the held quantity', [
    tx({ side: 'buy', quantity: 5, price: 10, executedAt: '2026-01-01T00:00:00Z' }),
    tx({
      side: 'sell',
      quantity: 5 + QTY_EPSILON / 2,
      price: 10,
      executedAt: '2026-01-02T00:00:00Z',
    }),
  ]);

  // --- uncovered sell (issue #369) ---
  reduce('uncovered: zero holding at sale-price basis -> 0 realized, closes at 0', [
    tx({ side: 'sell', quantity: 10, price: 100, allowUncovered: true }),
  ]);
  reduce('uncovered: zero-holding sell with a fee realizes exactly minus the fee', [
    tx({ side: 'sell', quantity: 4, price: 50, fee: 3, allowUncovered: true }),
  ]);
  reduce('uncovered: partial-cover sell splits covered gain from uncovered 0%', [
    tx({ side: 'buy', quantity: 2, price: 40, executedAt: '2026-01-01T00:00:00Z' }),
    tx({
      side: 'sell',
      quantity: 10,
      price: 100,
      allowUncovered: true,
      executedAt: '2026-01-02T00:00:00Z',
    }),
  ]);
  reduce('uncovered: uses a supplied entry price for the uncovered portion', [
    tx({ side: 'buy', quantity: 2, price: 40, executedAt: '2026-01-01T00:00:00Z' }),
    tx({
      side: 'sell',
      quantity: 10,
      price: 100,
      allowUncovered: true,
      uncoveredEntryPrice: 60,
      executedAt: '2026-01-02T00:00:00Z',
    }),
  ]);
  reduce('uncovered: still throws OversellError when the flag is absent', [
    tx({ side: 'sell', quantity: 1, price: 100 }),
  ]);
  reduce('uncovered: no shorts — a later buy rebuilds from 0, not a debt', [
    tx({
      side: 'sell',
      quantity: 10,
      price: 100,
      allowUncovered: true,
      executedAt: '2026-01-01T00:00:00Z',
    }),
    tx({ side: 'buy', quantity: 3, price: 20, executedAt: '2026-01-02T00:00:00Z' }),
  ]);
  reduce('uncovered: rejects a non-finite supplied entry price', [
    tx({ side: 'sell', quantity: 5, price: 100, allowUncovered: true, uncoveredEntryPrice: -1 }),
  ]);

  // --- chronological ordering across timestamp renderings (issue #218) ---
  reduce('a sell 500ms after its buy is valid even though it sorts first as a string', [
    tx({ side: 'sell', quantity: 5, price: 12, executedAt: '2026-01-05T10:00:00.500Z' }),
    tx({ side: 'buy', quantity: 5, price: 10, executedAt: '2026-01-05T10:00:00Z' }),
  ]);
  reduce('a sell truly 500ms before its buy oversells regardless of string order', [
    tx({ side: 'buy', quantity: 5, price: 10, executedAt: '2026-01-05T10:00:00.500Z' }),
    tx({ side: 'sell', quantity: 5, price: 12, executedAt: '2026-01-05T10:00:00Z' }),
  ]);
  reduce('compares instants across zone offsets, not string forms', [
    tx({ side: 'sell', quantity: 2, price: 11, executedAt: '2026-01-05T10:00:00.250Z' }),
    tx({ side: 'buy', quantity: 2, price: 10, executedAt: '2026-01-05T12:00:00+02:00' }),
  ]);
  reduce('the same instant rendered two ways ties, and input order breaks the tie', [
    tx({ side: 'buy', quantity: 1, price: 10, executedAt: '2026-01-05T10:00:00Z' }),
    tx({ side: 'sell', quantity: 1, price: 10, executedAt: '2026-01-05T10:00:00.000Z' }),
  ]);
  reduce('rejects an unparseable executedAt instead of mis-sorting it', [
    tx({ side: 'buy', quantity: 1, price: 10, executedAt: 'not-a-date' }),
  ]);

  // --- validation ---
  reduce('rejects a zero/negative quantity', [tx({ side: 'buy', quantity: 0, price: 100 })]);
  reduce('rejects a negative price', [tx({ side: 'buy', quantity: 1, price: -1 })]);
  reduce('rejects transactions spanning multiple assets', [
    tx({ assetId: 'A', side: 'buy', quantity: 1, price: 10 }),
    tx({ assetId: 'B', side: 'buy', quantity: 1, price: 10 }),
  ]);
}

async function genDeriveHoldings(): Promise<void> {
  const run = async (
    name: string,
    transactions: Transaction[],
    assets: HoldingAssetInput[],
    fx: FxSpec,
  ): Promise<void> =>
    emitAsync('holdings', 'deriveHoldings', name, { transactions, assets, fx }, () =>
      deriveHoldings(transactions, assets, makeConverter(fx)),
    );

  await run(
    'derives qty, avg cost, market value, unrealized P/L and day change',
    [
      tx({ assetId: 'A', side: 'buy', quantity: 10, price: 100 }),
      tx({ assetId: 'B', side: 'buy', quantity: 5, price: 200 }),
    ],
    [
      { assetId: 'A', currency: 'EUR', quote: { price: 120, prevClose: 110 } },
      { assetId: 'B', currency: 'USD', quote: { price: 220, prevClose: 210 } },
    ],
    FX_STUB,
  );
  skip(
    'valueOverTime/deriveHoldings',
    'FX coalescing call counts',
    'vi.fn() toHaveBeenCalledTimes/-With assertions are interaction, not data — hand-ported in DomainHandPortedTest against a counting CurrencyConverter fake (valueOverTime x3, netFlowsOverTime, costBasisOverTime)',
  );
  await run(
    'handles a loss position (negative P/L and day change convert correctly)',
    [tx({ assetId: 'D', side: 'buy', quantity: 10, price: 100 })],
    [{ assetId: 'D', currency: 'EUR', quote: { price: 90, prevClose: 95 } }],
    FX_STUB,
  );
  await run(
    'keeps the open position when there is no quote (EUR figures null)',
    [tx({ assetId: 'A', side: 'buy', quantity: 4, price: 50 })],
    [{ assetId: 'A', currency: 'EUR', quote: null }],
    FX_STUB,
  );
  await run(
    'omits day change when prev close is missing but keeps unrealized P/L',
    [tx({ assetId: 'A', side: 'buy', quantity: 2, price: 50 })],
    [{ assetId: 'A', currency: 'EUR', quote: { price: 60 } }],
    FX_STUB,
  );
  await run(
    'includes a fully-closed position with realized P/L but null market figures',
    [
      tx({ assetId: 'A', side: 'buy', quantity: 5, price: 10, executedAt: '2026-01-01T00:00:00Z' }),
      tx({ assetId: 'A', side: 'sell', quantity: 5, price: 14, executedAt: '2026-01-02T00:00:00Z' }),
    ],
    [{ assetId: 'A', currency: 'EUR', quote: { price: 14, prevClose: 13 } }],
    FX_STUB,
  );
  await run(
    'throws when a transacted asset has no currency/quote input',
    [
      tx({ assetId: 'A', side: 'buy', quantity: 1, price: 10 }),
      tx({ assetId: 'Z', side: 'buy', quantity: 1, price: 10 }),
    ],
    [{ assetId: 'A', currency: 'EUR', quote: { price: 12 } }],
    FX_STUB,
  );
  await run(
    'preserves the asset input order and skips assets with no transactions',
    [tx({ assetId: 'B', side: 'buy', quantity: 1, price: 10 })],
    [
      { assetId: 'A', currency: 'EUR', quote: { price: 1 } },
      { assetId: 'B', currency: 'EUR', quote: { price: 12 } },
    ],
    FX_STUB,
  );
}

async function genValueOverTime(): Promise<void> {
  const run = async (
    name: string,
    transactions: Transaction[],
    assets: ValueOverTimeAsset[],
    today: string,
    fx: FxSpec,
  ): Promise<void> =>
    emitAsync('holdings', 'valueOverTime', name, { transactions, assets, today, fx }, () =>
      valueOverTime({ transactions, assets, today, converter: makeConverter(fx) }),
    );

  await run(
    'reconstructs a multi-asset, multi-currency series with a carried-forward custom asset',
    [
      tx({ assetId: 'A', side: 'buy', quantity: 10, price: 100, executedAt: '2026-01-01T00:00:00Z' }),
      tx({ assetId: 'C', side: 'buy', quantity: 2, price: 50, executedAt: '2026-01-03T00:00:00Z' }),
      tx({ assetId: 'X', side: 'buy', quantity: 1, price: 1000, executedAt: '2026-01-01T00:00:00Z' }),
    ],
    [
      {
        assetId: 'A',
        currency: 'EUR',
        prices: [
          { date: '2026-01-01', close: 100 },
          { date: '2026-01-02', close: 102 },
          { date: '2026-01-03', close: 101 },
          { date: '2026-01-04', close: 105 },
          { date: '2026-01-05', close: 110 },
        ],
      },
      {
        assetId: 'C',
        currency: 'USD',
        prices: [
          { date: '2026-01-03', close: 50 },
          { date: '2026-01-04', close: 52 },
          { date: '2026-01-05', close: 51 },
        ],
      },
      {
        assetId: 'X',
        currency: 'EUR',
        prices: [
          { date: '2026-01-01', close: 1000 },
          { date: '2026-01-04', close: 1200 },
        ],
      },
    ],
    '2026-01-05',
    FX_STUB,
  );
  await run(
    'carries a custom asset value forward between sparse points (step function)',
    [tx({ assetId: 'X', side: 'buy', quantity: 1, price: 1000, executedAt: '2026-01-01T00:00:00Z' })],
    [
      {
        assetId: 'X',
        currency: 'EUR',
        prices: [
          { date: '2026-01-01', close: 1000 },
          { date: '2026-01-03', close: 1500 },
        ],
      },
    ],
    '2026-01-05',
    FX_STUB,
  );
  await run(
    'values a position at zero on days before its first price point',
    [tx({ assetId: 'A', side: 'buy', quantity: 2, price: 10, executedAt: '2026-01-01T00:00:00Z' })],
    [
      {
        assetId: 'A',
        currency: 'EUR',
        prices: [
          { date: '2026-01-03', close: 10 },
          { date: '2026-01-04', close: 11 },
        ],
      },
    ],
    '2026-01-04',
    FX_STUB,
  );
  await run(
    'drops to zero after the position is fully sold',
    [
      tx({ assetId: 'A', side: 'buy', quantity: 4, price: 10, executedAt: '2026-01-01T00:00:00Z' }),
      tx({ assetId: 'A', side: 'sell', quantity: 4, price: 10, executedAt: '2026-01-03T00:00:00Z' }),
    ],
    [
      {
        assetId: 'A',
        currency: 'EUR',
        prices: [
          { date: '2026-01-01', close: 10 },
          { date: '2026-01-02', close: 12 },
          { date: '2026-01-03', close: 11 },
        ],
      },
    ],
    '2026-01-03',
    FX_STUB,
  );
  await run(
    'an uncovered sell never goes short: a later buy rebuilds from 0 (issue #369)',
    [
      tx({ assetId: 'A', side: 'buy', quantity: 2, price: 10, executedAt: '2026-01-01T00:00:00Z' }),
      tx({
        assetId: 'A',
        side: 'sell',
        quantity: 5,
        price: 10,
        allowUncovered: true,
        executedAt: '2026-01-02T00:00:00Z',
      }),
      tx({ assetId: 'A', side: 'buy', quantity: 4, price: 10, executedAt: '2026-01-03T00:00:00Z' }),
    ],
    [
      {
        assetId: 'A',
        currency: 'EUR',
        prices: [
          { date: '2026-01-01', close: 10 },
          { date: '2026-01-02', close: 10 },
          { date: '2026-01-03', close: 10 },
        ],
      },
    ],
    '2026-01-03',
    FX_STUB,
  );
  await run(
    "applies each day's historical FX rate — carry-forward with a mid-series sell",
    [
      tx({ assetId: 'C', side: 'buy', quantity: 2, price: 50, executedAt: '2026-01-01T00:00:00Z' }),
      tx({ assetId: 'C', side: 'sell', quantity: 1, price: 50, executedAt: '2026-01-03T00:00:00Z' }),
    ],
    [
      {
        assetId: 'C',
        currency: 'USD',
        prices: [
          { date: '2026-01-01', close: 50 },
          { date: '2026-01-04', close: 52 },
        ],
      },
    ],
    '2026-01-05',
    {
      kind: 'dated',
      ratesByDate: {
        USD: {
          '2026-01-01': 0.9,
          '2026-01-02': 0.85,
          '2026-01-03': 0.8,
          '2026-01-04': 0.75,
          '2026-01-05': 0.7,
        },
      },
    },
  );
  await run(
    'coalesces FX to one conversion per (currency, day) across same-currency assets',
    [
      tx({ assetId: 'C', side: 'buy', quantity: 1, price: 100, executedAt: '2026-01-01T00:00:00Z' }),
      tx({ assetId: 'D', side: 'buy', quantity: 2, price: 50, executedAt: '2026-01-01T00:00:00Z' }),
    ],
    [
      {
        assetId: 'C',
        currency: 'USD',
        prices: [
          { date: '2026-01-01', close: 100 },
          { date: '2026-01-02', close: 110 },
        ],
      },
      {
        assetId: 'D',
        currency: 'USD',
        prices: [
          { date: '2026-01-01', close: 50 },
          { date: '2026-01-02', close: 60 },
        ],
      },
    ],
    '2026-01-02',
    { kind: 'dated', ratesByDate: { USD: { '2026-01-01': 0.9, '2026-01-02': 0.8 } } },
  );
  await run(
    'throws on an invalid FX rate instead of producing a wrong value',
    [tx({ assetId: 'C', side: 'buy', quantity: 1, price: 10, executedAt: '2026-01-01T00:00:00Z' })],
    [{ assetId: 'C', currency: 'USD', prices: [{ date: '2026-01-01', close: 10 }] }],
    '2026-01-01',
    { kind: 'flat', rates: { EUR: 1, USD: 0 } },
  );
  await run(
    'rejects a malformed price point date even when it is the only point',
    [tx({ side: 'buy', quantity: 1, price: 10, executedAt: '2026-01-01T00:00:00Z' })],
    [{ assetId: 'A', currency: 'EUR', prices: [{ date: '2026-1-3', close: 10 }] }],
    '2026-01-05',
    FX_STUB,
  );
  // NOTE: the "non-finite price point close" case uses Number.NaN, which JSON
  // cannot carry — hand-ported instead (see skip below).
  skip(
    'valueOverTime',
    'rejects a non-finite price point close',
    'input carries Number.NaN, which JSON cannot represent — hand-ported in DomainHandPortedTest (non-finite input section)',
  );
  await run('returns an empty series when there are no transactions', [], [], '2026-01-05', FX_STUB);
  await run(
    'returns an empty series when the first transaction is after today',
    [tx({ side: 'buy', quantity: 1, price: 10, executedAt: '2026-02-01T00:00:00Z' })],
    [{ assetId: 'A', currency: 'EUR', prices: [{ date: '2026-02-01', close: 10 }] }],
    '2026-01-05',
    FX_STUB,
  );
  await run(
    'throws when a transaction references an asset with no price/currency input',
    [tx({ assetId: 'Z', side: 'buy', quantity: 1, price: 10 })],
    [],
    '2026-01-02',
    FX_STUB,
  );
}

async function genCostBasisOverTime(): Promise<void> {
  const run = async (
    name: string,
    transactions: Transaction[],
    assets: ValueOverTimeAsset[],
    today: string,
    fx: FxSpec,
  ): Promise<void> =>
    emitAsync('holdings', 'costBasisOverTime', name, { transactions, assets, today, fx }, () =>
      costBasisOverTime({ transactions, assets, today, converter: makeConverter(fx) }),
    );

  await run('returns an empty series without transactions', [], [], '2026-01-05', FX_IDENTITY);
  await run(
    'returns an empty series when the first transaction is after today',
    [txn({ executedAt: at('2026-01-10') })],
    [asset('a1', 'EUR', ['2026-01-10'])],
    '2026-01-05',
    FX_IDENTITY,
  );
  await run(
    'capitalises the fee into the basis and carries it forward daily',
    [txn({ quantity: 2, price: 100, fee: 10, executedAt: at('2026-01-01') })],
    [asset('a1', 'EUR', ['2026-01-01'])],
    '2026-01-03',
    FX_IDENTITY,
  );
  await run(
    'gates each asset on its first known price, mirroring the value series',
    [txn({ quantity: 1, price: 100, executedAt: at('2026-01-01') })],
    [asset('a1', 'EUR', ['2026-01-03'])],
    '2026-01-04',
    FX_IDENTITY,
  );
  await run(
    'keeps the average on a partial sell and zeroes the basis on a full close',
    [
      txn({ quantity: 4, price: 100, executedAt: at('2026-01-01') }),
      txn({ side: 'sell', quantity: 2, price: 150, executedAt: at('2026-01-02') }),
      txn({ side: 'sell', quantity: 2, price: 150, executedAt: at('2026-01-03') }),
    ],
    [asset('a1', 'EUR', ['2026-01-01'])],
    '2026-01-04',
    FX_IDENTITY,
  );
  await run(
    're-averages a re-entry from scratch and books same-day interleaving at EOD',
    [
      txn({ quantity: 2, price: 100, executedAt: '2026-01-01T09:00:00.000Z' }),
      txn({ side: 'sell', quantity: 2, price: 120, executedAt: '2026-01-01T12:00:00.000Z' }),
      txn({ quantity: 1, price: 300, executedAt: '2026-01-01T15:00:00.000Z' }),
    ],
    [asset('a1', 'EUR', ['2026-01-01'])],
    '2026-01-02',
    FX_IDENTITY,
  );
  await run(
    'closes an acknowledged uncovered sell at exactly zero basis',
    [
      txn({ quantity: 1, price: 100, executedAt: at('2026-01-01') }),
      txn({
        side: 'sell',
        quantity: 5,
        price: 100,
        executedAt: at('2026-01-02'),
        allowUncovered: true,
      }),
    ],
    [asset('a1', 'EUR', ['2026-01-01'])],
    '2026-01-03',
    FX_IDENTITY,
  );
  await run(
    "converts each day at that day's historical FX rate and sums across assets",
    [
      txn({ assetId: 'eur', quantity: 1, price: 100, executedAt: at('2026-01-01') }),
      txn({ assetId: 'usd', quantity: 2, price: 100, executedAt: at('2026-01-01') }),
    ],
    [asset('eur', 'EUR', ['2026-01-01']), asset('usd', 'USD', ['2026-01-01'])],
    '2026-01-02',
    { kind: 'dated', ratesByDate: { USD: { '2026-01-01': 0.5, '2026-01-02': 0.8 } } },
  );
  await run(
    'fails loud on a transaction with no asset input',
    [txn({ executedAt: at('2026-01-01') })],
    [],
    '2026-01-02',
    FX_IDENTITY,
  );
}

function genDailyCloseSeries(): void {
  const run = (name: string, prices: PricePoint[], startDay: string, endDay: string): void =>
    emit('holdings', 'dailyCloseSeries', name, { prices, startDay, endDay }, () =>
      dailyCloseSeries(prices, startDay, endDay),
    );

  run(
    'expands sparse closes to one point per calendar day, carrying forward over gaps',
    [
      { date: '2026-01-01', close: 100 },
      { date: '2026-01-05', close: 110 },
    ],
    '2026-01-01',
    '2026-01-06',
  );
  run(
    'omits days before the first known close instead of inventing prices',
    [{ date: '2026-01-03', close: 50 }],
    '2026-01-01',
    '2026-01-04',
  );
  run(
    'sorts unsorted input and lets a later duplicate of a date win',
    [
      { date: '2026-01-02', close: 20 },
      { date: '2026-01-01', close: 10 },
      { date: '2026-01-02', close: 22 },
    ],
    '2026-01-01',
    '2026-01-02',
  );
  run('returns empty for no prices', [], '2026-01-01', '2026-01-05');
  run(
    'returns empty for an inverted window',
    [{ date: '2026-01-01', close: 1 }],
    '2026-01-05',
    '2026-01-01',
  );
  run('rejects a malformed price date', [{ date: '01.02.2026', close: 1 }], '2026-01-01', '2026-01-02');
  run('rejects a malformed startDay', [{ date: '2026-01-01', close: 1 }], 'nope', '2026-01-02');
  skip(
    'dailyCloseSeries',
    'rejects a non-finite close',
    'input carries Number.NaN, which JSON cannot represent — hand-ported in DomainHandPortedTest (non-finite input section)',
  );
}

async function genNetFlowsOverTime(): Promise<void> {
  const CCY: Record<string, string> = { A: 'EUR', U: 'USD' };
  const run = async (
    name: string,
    transactions: Transaction[],
    currencyByAsset: Record<string, string>,
    fx: FxSpec,
  ): Promise<void> =>
    emitAsync('holdings', 'netFlowsOverTime', name, { transactions, currencyByAsset, fx }, () =>
      netFlowsOverTime({
        transactions,
        currencyByAsset: new Map(Object.entries(currencyByAsset)),
        converter: makeConverter(fx),
      }),
    );

  await run(
    'signs flows correctly: buys (cost + fee) in, sells (proceeds - fee) out',
    [
      tx({ side: 'buy', quantity: 10, price: 100, fee: 5, executedAt: '2026-01-01T10:00:00Z' }),
      tx({ side: 'sell', quantity: 4, price: 110, fee: 3, executedAt: '2026-01-03T10:00:00Z' }),
    ],
    CCY,
    FX_STUB,
  );
  await run(
    'aggregates same-day transactions into one point and sorts ascending',
    [
      tx({ side: 'sell', quantity: 1, price: 50, fee: 0, executedAt: '2026-02-01T12:00:00Z' }),
      tx({ side: 'buy', quantity: 2, price: 100, fee: 0, executedAt: '2026-02-01T09:00:00Z' }),
      tx({ side: 'buy', quantity: 1, price: 10, fee: 0, executedAt: '2026-01-15T09:00:00Z' }),
    ],
    CCY,
    FX_STUB,
  );
  await run(
    "converts native flows at that day's historical rate, coalesced per (currency, day)",
    [
      tx({ assetId: 'U', side: 'buy', quantity: 10, price: 100, executedAt: '2026-01-01T10:00:00Z' }),
      tx({ assetId: 'U', side: 'buy', quantity: 5, price: 100, executedAt: '2026-01-01T15:00:00Z' }),
      tx({ assetId: 'U', side: 'buy', quantity: 10, price: 100, executedAt: '2026-01-02T10:00:00Z' }),
    ],
    CCY,
    { kind: 'dated', ratesByDate: { USD: { '2026-01-01': 0.9, '2026-01-02': 0.8 } } },
  );
  await run(
    'fails loud on a transaction whose asset has no currency input',
    [tx({ assetId: 'X', side: 'buy', quantity: 1, price: 1 })],
    CCY,
    FX_STUB,
  );
}

function genTimeWeightedReturn(): void {
  const run = (
    name: string,
    values: { date: string; valueEur: number }[],
    flows: { date: string; flowEur: number }[],
  ): void =>
    emit('holdings', 'timeWeightedReturn', name, { values, flows }, () =>
      timeWeightedReturn(values, flows),
    );

  run(
    'a deposit causes NO jump: buying more at the current price leaves the curve flat',
    [
      { date: '2026-01-01', valueEur: 1000 },
      { date: '2026-01-02', valueEur: 2000 },
      { date: '2026-01-03', valueEur: 2200 },
    ],
    [
      { date: '2026-01-01', flowEur: 1000 },
      { date: '2026-01-02', flowEur: 1000 },
    ],
  );
  run(
    'the issue-#125 scenario: investing more after a loss shows the loss, not the deposit',
    [
      { date: '2026-01-01', valueEur: 4000 },
      { date: '2026-01-02', valueEur: 3000 },
      { date: '2026-01-03', valueEur: 4000 },
    ],
    [
      { date: '2026-01-01', flowEur: 4000 },
      { date: '2026-01-03', flowEur: 1000 },
    ],
  );
  run(
    'chains market moves across a deposit multiplicatively',
    [
      { date: '2026-01-01', valueEur: 1000 },
      { date: '2026-01-02', valueEur: 1100 },
      { date: '2026-01-03', valueEur: 2200 },
      { date: '2026-01-04', valueEur: 2420 },
    ],
    [
      { date: '2026-01-01', flowEur: 1000 },
      { date: '2026-01-03', flowEur: 1100 },
    ],
  );
  run(
    'books a full liquidation final day correctly (outflows count end-of-day)',
    [
      { date: '2026-01-01', valueEur: 4000 },
      { date: '2026-01-02', valueEur: 0 },
      { date: '2026-01-03', valueEur: 0 },
    ],
    [
      { date: '2026-01-01', flowEur: 4000 },
      { date: '2026-01-02', flowEur: -3900 },
    ],
  );
  run(
    'captures the first day execution->close move (inflows count start-of-day)',
    [{ date: '2026-01-01', valueEur: 1040 }],
    [{ date: '2026-01-01', flowEur: 1000 }],
  );
  run(
    'fees drag performance (flows are gross of fee, value is not)',
    [{ date: '2026-01-01', valueEur: 1000 }],
    [{ date: '2026-01-01', flowEur: 1010 }],
  );
  run(
    'a PARTIAL withdrawal does not move the line either',
    [
      { date: '2026-01-01', valueEur: 1000 },
      { date: '2026-01-02', valueEur: 600 },
      { date: '2026-01-03', valueEur: 660 },
    ],
    [
      { date: '2026-01-01', flowEur: 1000 },
      { date: '2026-01-02', flowEur: -400 },
    ],
  );
  run(
    'invariance: lean portfolio on the market path',
    [
      { date: '2026-01-01', valueEur: 1000 },
      { date: '2026-01-02', valueEur: 1100 },
      { date: '2026-01-03', valueEur: 1100 },
      { date: '2026-01-04', valueEur: 1210 },
    ],
    [{ date: '2026-01-01', flowEur: 1000 }],
  );
  run(
    'invariance: topped-up portfolio on the same market path',
    [
      { date: '2026-01-01', valueEur: 1000 },
      { date: '2026-01-02', valueEur: 1100 },
      { date: '2026-01-03', valueEur: 6100 },
      { date: '2026-01-04', valueEur: 6710 },
    ],
    [
      { date: '2026-01-01', flowEur: 1000 },
      { date: '2026-01-03', flowEur: 5000 },
    ],
  );
  run(
    'treats a same-day inflow as invested from the open (daily-TWR flow-timing limit)',
    [
      { date: '2026-01-01', valueEur: 1000 },
      { date: '2026-01-02', valueEur: 6100 },
    ],
    [
      { date: '2026-01-01', flowEur: 1000 },
      { date: '2026-01-02', flowEur: 5000 },
    ],
  );
  run(
    'a zero-value day from missing price data links flat and recovers — never -100%',
    [
      { date: '2026-01-01', valueEur: 0 },
      { date: '2026-01-02', valueEur: 1000 },
      { date: '2026-01-03', valueEur: 1100 },
    ],
    [{ date: '2026-01-01', flowEur: 1000 }],
  );
  run(
    'seeds the basis from a pre-price inflow (issue #218)',
    [
      { date: '2026-01-05', valueEur: 0 },
      { date: '2026-01-06', valueEur: 0 },
      { date: '2026-01-07', valueEur: 1200 },
    ],
    [{ date: '2026-01-05', flowEur: 1000 }],
  );
  run(
    'accumulates several pre-price inflows into the basis',
    [
      { date: '2026-01-05', valueEur: 0 },
      { date: '2026-01-06', valueEur: 0 },
      { date: '2026-01-07', valueEur: 1800 },
    ],
    [
      { date: '2026-01-05', flowEur: 1000 },
      { date: '2026-01-06', flowEur: 500 },
    ],
  );
  run(
    'books a full pre-price liquidation against the seeded basis and resets it',
    [
      { date: '2026-01-05', valueEur: 0 },
      { date: '2026-01-06', valueEur: 0 },
      { date: '2026-01-07', valueEur: 0 },
    ],
    [
      { date: '2026-01-05', flowEur: 1000 },
      { date: '2026-01-06', flowEur: -450 },
    ],
  );
  run(
    'a mid-series zero-value gap does not swallow the move across it',
    [
      { date: '2026-01-01', valueEur: 1000 },
      { date: '2026-01-02', valueEur: 0 },
      { date: '2026-01-03', valueEur: 1100 },
    ],
    [{ date: '2026-01-01', flowEur: 1000 }],
  );
  run(
    're-entering after a full liquidation measures from the new money',
    [
      { date: '2026-01-01', valueEur: 1000 },
      { date: '2026-01-02', valueEur: 0 },
      { date: '2026-01-03', valueEur: 0 },
      { date: '2026-01-04', valueEur: 550 },
    ],
    [
      { date: '2026-01-01', flowEur: 1000 },
      { date: '2026-01-02', flowEur: -1000 },
      { date: '2026-01-04', flowEur: 500 },
    ],
  );
  run(
    'ignores flows outside the value window (future-dated transaction)',
    [{ date: '2026-01-01', valueEur: 1000 }],
    [
      { date: '2026-01-01', flowEur: 1000 },
      { date: '2026-02-01', flowEur: 500 },
    ],
  );
  run(
    'sorts unsorted value input',
    [
      { date: '2026-01-02', valueEur: 1100 },
      { date: '2026-01-01', valueEur: 1000 },
    ],
    [{ date: '2026-01-01', flowEur: 1000 }],
  );
  run('rejects a malformed value point date', [{ date: '01.02.2026', valueEur: 1 }], []);
  skip(
    'timeWeightedReturn',
    'rejects a non-finite value / a non-finite flow',
    'inputs carry Number.NaN and Number.POSITIVE_INFINITY, which JSON cannot represent — hand-ported in DomainHandPortedTest (non-finite input section)',
  );
}

function genRebasePerformance(): void {
  const run = (name: string, points: { date: string; pct: number }[]): void =>
    emit('holdings', 'rebasePerformance', name, { points }, () => rebasePerformance(points));

  run('re-bases by compounding, not subtraction', [
    { date: '2026-03-01', pct: 25 },
    { date: '2026-03-02', pct: 12.5 },
  ]);
  run('handles the empty slice', []);
  run('rejects a corrupt (<= -100%) base', [{ date: '2026-01-01', pct: -100 }]);
}

// ===========================================================================
// seriesStats.ts
// ===========================================================================

function genSeriesStats(): void {
  const stats = (name: string, series: StatSeriesPoint[]): void =>
    emit('seriesStats', 'computeSeriesStats', name, { series }, () => computeSeriesStats(series));

  stats('flat series (five days at 100)', [
    pt('2024-01-01', 100),
    pt('2024-01-02', 100),
    pt('2024-01-03', 100),
    pt('2024-01-04', 100),
    pt('2024-01-05', 100),
  ]);
  stats('monotonic-up series (100, 110, 121)', [
    pt('2024-01-01', 100),
    pt('2024-01-02', 110),
    pt('2024-01-03', 121),
  ]);
  stats('drawdown-then-recover (100, 80, 100)', [
    pt('2024-01-01', 100),
    pt('2024-01-02', 80),
    pt('2024-01-03', 100),
  ]);
  stats('CAGR over exactly 4 years (1461 days incl. one leap day)', [
    pt('2020-01-01', 100),
    pt('2024-01-01', 146.41),
  ]);
  stats('empty series', []);
  stats('zero first value', [pt('2024-01-01', 0), pt('2024-01-02', 50)]);
  stats('negative first value', [pt('2024-01-01', -5), pt('2024-01-02', 50)]);
  stats('single-point series', [pt('2024-01-01', 100)]);
  stats('skips the daily return over a non-positive PREVIOUS value mid-series', [
    pt('2024-01-01', 100),
    pt('2024-01-02', 0),
    pt('2024-01-03', 50),
  ]);
  stats('skips the daily return over a NEGATIVE previous value as well', [
    pt('2024-01-01', 100),
    pt('2024-01-02', -50),
    pt('2024-01-03', 25),
  ]);

  const perf = (name: string, series: StatSeriesPoint[]): void =>
    emit('seriesStats', 'toPerformanceSeries', name, { series }, () => toPerformanceSeries(series));

  perf('rebases to cumulative % from the first point', [
    pt('2024-01-01', 100),
    pt('2024-01-02', 110),
    pt('2024-01-03', 95),
  ]);
  perf('returns [] for an empty series', []);
  perf('zero base -> every point emitted as 0 %', [pt('2024-01-01', 0), pt('2024-01-02', 50)]);
  perf('negative base -> every point emitted as 0 %', [pt('2024-01-01', -10), pt('2024-01-02', 5)]);

  const defl = (name: string, series: StatSeriesPoint[], deflator: Deflator): void =>
    emit('seriesStats', 'deflateSeries', name, { series, deflator }, () =>
      deflateSeries(series, deflator),
    );

  const flatNominal = [
    pt('2024-01-01', 1000),
    pt('2024-07-01', 1000),
    pt('2025-01-01', 1000),
    pt('2025-07-01', 1000),
    pt('2026-01-01', 1000),
  ];
  defl('flat: slopes a flat nominal series downward at 10 %/yr over ~2 years', flatNominal, {
    kind: 'flat',
    pctPerYear: 10,
  });
  defl('flat: a 0 %/yr rate is the identity', flatNominal, { kind: 'flat', pctPerYear: 0 });
  defl('flat: returns [] for an empty series', [], { kind: 'flat', pctPerYear: 10 });

  const monthly = [
    { month: '2024-02', value: 101 },
    { month: '2024-04', value: 103 },
    { month: '2024-01', value: 100 },
  ];
  defl(
    'index: interpolates between anchors + extrapolates past the last one (#468)',
    [
      pt('2023-12-15', 500),
      pt('2024-01-15', 500),
      pt('2024-02-15', 500),
      pt('2024-03-15', 500),
      pt('2024-05-15', 500),
    ],
    { kind: 'index', monthly },
  );
  defl(
    'index: deflates a window whose whole span sits PAST the last anchor',
    [pt('2025-07-15', 1000), pt('2026-07-15', 1000)],
    {
      kind: 'index',
      monthly: [
        { month: '2023-01', value: 100 },
        { month: '2024-01', value: 105 },
        { month: '2025-01', value: 110 },
      ],
    },
  );
  defl(
    'index: a single-anchor index carries that level everywhere',
    [pt('2024-01-15', 500), pt('2024-06-15', 500), pt('2024-12-15', 500)],
    { kind: 'index', monthly: [{ month: '2024-01', value: 100 }] },
  );
  defl(
    'index: an empty monthly index returns the series unchanged',
    [pt('2024-01-01', 100), pt('2024-02-01', 120)],
    { kind: 'index', monthly: [] },
  );
  defl('index: returns [] for an empty series', [], { kind: 'index', monthly });
  skip(
    'deflateSeries',
    'returns a FRESH array / fresh points (not.toBe identity checks)',
    'referential-identity assertion; the Kotlin port returns new lists by construction, so there is nothing to replay',
  );

  const idxAvg = (name: string, monthlyIn: { month: string; value: number }[]): void =>
    emit('seriesStats', 'indexAveragePctPerYear', name, { monthly: monthlyIn }, () =>
      indexAveragePctPerYear(monthlyIn),
    );

  idxAvg('returns the CAGR from first to last anchor', [
    { month: '2020-01', value: 100 },
    { month: '2022-01', value: 121 },
  ]);
  idxAvg('reproduces a realistic HICP-style series (100 -> 137 over 10 y)', [
    { month: '2015-01', value: 100 },
    { month: '2025-01', value: 137 },
  ]);
  idxAvg('empty -> null', []);
  idxAvg('single-anchor -> null', [{ month: '2020-01', value: 100 }]);
  idxAvg('non-positive base -> null', [
    { month: '2020-01', value: 0 },
    { month: '2021-01', value: 105 },
  ]);

  const contrib = (name: string, inputs: ContributionInput[]): void =>
    emit('seriesStats', 'computeContributions', name, { inputs }, () =>
      computeContributions(inputs),
    );

  const contribInputs: ContributionInput[] = [
    { assetId: 'a', startValue: 1000, endValue: 1200, currentValue: 1300 },
    { assetId: 'b', startValue: 500, endValue: 450, currentValue: 480 },
    { assetId: 'c', startValue: 250, endValue: 300, currentValue: 0 },
  ];
  contrib('rows sum to the filtered total return and weights sum to 1', contribInputs);
  contrib('computes each row against the COMMON totals (hand-computed)', contribInputs);
  contrib('returns [] for empty input', []);
  contrib('zero current total -> all weights 0', [
    { assetId: 'a', startValue: 100, endValue: 150, currentValue: 0 },
  ]);
  contrib('start total within +-1e-9 of zero -> contributions 0', [
    { assetId: 'a', startValue: 100, endValue: 150, currentValue: 50 },
    { assetId: 'b', startValue: -100, endValue: -100, currentValue: 50 },
  ]);

  const cmp = (name: string, inputs: ComparisonSeriesInput[], baselineId: string): void =>
    emit('seriesStats', 'compareSeriesStats', name, { inputs, baselineId }, () =>
      compareSeriesStats(inputs, baselineId),
    );

  cmp(
    'for N=2 reproduces the V4-P7 basket-benchmark delta exactly',
    [
      cmpInput('basket', {
        totalReturnPct: 15,
        cagrPct: 11,
        maxDrawdownPct: -8,
        volatilityPct: 14,
        bestDayPct: 4,
        worstDayPct: -6,
      }),
      cmpInput('bench', {
        totalReturnPct: 9,
        cagrPct: 7,
        maxDrawdownPct: -5,
        volatilityPct: 10,
        bestDayPct: 3,
        worstDayPct: -4,
      }),
    ],
    'basket',
  );
  cmp(
    'the baseline series deltas against itself: every metric is 0',
    [cmpInput('a'), cmpInput('b', { totalReturnPct: 20 })],
    'a',
  );
  cmp(
    'compares three series in one call, preserving input order',
    [
      cmpInput('x', { totalReturnPct: 10 }),
      cmpInput('y', { totalReturnPct: 25 }),
      cmpInput('z', { totalReturnPct: 5 }),
    ],
    'x',
  );
  const rePick = [
    cmpInput('a', { totalReturnPct: 10 }),
    cmpInput('b', { totalReturnPct: 25 }),
    cmpInput('c', { totalReturnPct: 5 }),
  ];
  cmp('re-picking the baseline: vs a', rePick, 'a');
  cmp('re-picking the baseline: vs b', rePick, 'b');
  cmp(
    'a null metric on either side yields a null delta',
    [cmpInput('base', { cagrPct: null }), cmpInput('other', { cagrPct: 8, volatilityPct: null })],
    'base',
  );
  cmp('rejects an empty set', [], 'a');
  cmp('rejects an unknown baseline', [cmpInput('a'), cmpInput('b')], 'zzz');
  cmp('rejects duplicate ids', [cmpInput('a'), cmpInput('a')], 'a');
}

// ===========================================================================
// cashLedger.ts  (+ the cashBySourceOverTime half of dailySnapshotSeries.test.ts;
// W2 already covered that file's costBasisOverTime half under `holdings`)
// ===========================================================================

const cash = (fn: string, name: string, input: unknown, run: () => unknown): void =>
  emit('cashLedger', fn, name, input, run);

/** cashLedger.test.ts `mv()` */
function mv(kind: CashMovementKind, amountEur: number, occurredAt: string): CashMovement {
  return { kind, amountEur, occurredAt };
}

/** cashLedger.test.ts `smv()` */
function smv(
  sourceId: string,
  kind: CashMovementKind,
  amountEur: number,
  occurredAt: string,
): SourcedCashMovement {
  return { sourceId, kind, amountEur, occurredAt };
}

/** dailySnapshotSeries.test.ts `movement()` */
function sourced(
  overrides: Partial<SourcedCashMovement> & { occurredAt: string },
): SourcedCashMovement {
  return { kind: 'deposit', amountEur: 100, sourceId: 's1', ...overrides };
}

/** cashLedger.test.ts `mixedSequence()` */
function mixedSequence(): CashMovement[] {
  return [
    mv('deposit', 1000, '2026-01-05T09:00:00Z'),
    mv('buy', -400, '2026-01-06T10:00:00Z'),
    mv('sell_proceeds', 150, '2026-01-07T11:00:00Z'),
    mv('withdrawal', -200, '2026-01-08T12:00:00Z'),
  ];
}

// --- canonical JSON shapes -------------------------------------------------
//
// JSON.stringify renders a `Map` as `{}` and would carry whatever key ORDER the
// test helper happened to build a movement literal with, so every ledger value
// that travels in a vector goes through one of these shapers. The Kotlin
// encoders in DomainVectors.kt mirror them exactly, which is also what makes the
// Map ITERATION ORDER (plan §3.3 rule 4) an asserted property rather than an
// invisible one — an array of pairs has an order, a JSON object does not.

interface MovementJson {
  kind: string;
  amountEur: number;
  occurredAt: string;
  sourceId?: string;
}

function mJson(m: CashMovement): MovementJson {
  const out: MovementJson = { kind: m.kind, amountEur: m.amountEur, occurredAt: m.occurredAt };
  const sourceId = (m as SourcedCashMovement).sourceId;
  if (sourceId !== undefined) out.sourceId = sourceId;
  return out;
}

const msJson = (movements: readonly CashMovement[]): MovementJson[] => movements.map(mJson);

const entriesJson = (
  entries: readonly CashLedgerEntry[],
): { movement: MovementJson; balanceEur: number }[] =>
  entries.map((e) => ({ movement: mJson(e.movement), balanceEur: e.balanceEur }));

const balancesJson = (
  balances: ReadonlyMap<string, number>,
): { sourceId: string; balanceEur: number }[] =>
  [...balances.entries()].map(([sourceId, balanceEur]) => ({ sourceId, balanceEur }));

// --- floorCents ------------------------------------------------------------

function genFloorCents(): void {
  const run = (name: string, amountEur: number): void =>
    cash('floorCents', name, { amountEur }, () => floorCents(amountEur));

  run('no-op on values already expressible in whole cents (0)', 0);
  run('no-op on values already expressible in whole cents (100)', 100);
  run('no-op on values already expressible in whole cents (1234.56)', 1234.56);
  run('no-op on values already expressible in whole cents (-99.99)', -99.99);
  run('an exact cent whose decimal literal underflows survives (8.61)', 8.61);
  run('an exact cent whose decimal literal underflows survives (0.07)', 0.07);

  run('floors sub-cent EUR down, never up (100.006)', 100.006);
  run('floors sub-cent EUR down, never up (100.004)', 100.004);
  run('floors sub-cent EUR down, never up (0.005)', 0.005);
  run('floors sub-cent EUR down, never up (0.009999)', 0.009999);
  run('floors sub-cent EUR down, never up (1.005 is stored as 1.00499…)', 1.005);
  run('floors sub-cent EUR down, never up (2.675)', 2.675);

  run('truncates the magnitude toward zero for outflows (-100.006)', -100.006);
  run('truncates the magnitude toward zero for outflows (-0.005)', -0.005);
  run('truncates the magnitude toward zero for outflows (-1.005)', -1.005);

  run('sheds FP summation dust (0.1 + 0.2 - 0.3)', 0.1 + 0.2 - 0.3);
  run('a value a hair below a cent boundary still floors onto it (0.1 + 0.2)', 0.1 + 0.2);

  // "makes withdraw-all safe": the reported balance is the floored true balance,
  // and withdrawing it leaves a residue that itself floors to exactly 0.
  const trueBalance = cashBalance([mv('deposit', 100.006, '2026-01-01T00:00:00Z')]);
  cash(
    'cashBalance',
    'withdraw-all safety: the true (unrounded) balance of a sub-cent deposit',
    { movements: msJson([mv('deposit', 100.006, '2026-01-01T00:00:00Z')]) },
    () => cashBalance([mv('deposit', 100.006, '2026-01-01T00:00:00Z')]),
  );
  run('makes withdraw-all safe: the reported balance', trueBalance);
  run('makes withdraw-all safe: the residue after withdrawing it', trueBalance - floorCents(trueBalance));

  skip(
    'floorCents',
    'throws on a non-finite amount',
    'inputs are Number.POSITIVE_INFINITY / Number.NaN, which JSON cannot represent — hand-ported in CashLedgerHandPortedTest ("floorCents rejects a non-finite amount")',
  );
  skip(
    'floorCents',
    'returns POSITIVE zero for a floored-away negative (Object.is checks)',
    'signed-zero identity: JSON carries no -0 (the generator refuses to emit one), so `Object.is(floorCents(-0.005), 0)` cannot travel in a vector — hand-ported in CashLedgerHandPortedTest ("floorCents never returns negative zero")',
  );
}

// --- cashBalance -----------------------------------------------------------

function genCashBalance(): void {
  const run = (name: string, movements: CashMovement[]): void =>
    cash('cashBalance', name, { movements: msJson(movements) }, () => cashBalance(movements));

  run('is the sum of signed movements across a mixed sequence', mixedSequence());
  run('reconciles: current cash === sum of movements (mixed sequence)', mixedSequence());
  run('reconciles: current cash === sum of movements (two small deposits)', [
    mv('deposit', 0.1, '2026-01-05'),
    mv('deposit', 0.2, '2026-01-06'),
  ]);
  run('reconciles: current cash === sum of movements (decimal round trip)', [
    mv('deposit', 123.45, '2026-01-05T09:00:00Z'),
    mv('withdrawal', -23.45, '2026-01-05T10:00:00Z'),
    mv('buy', -100, '2026-01-06T09:00:00Z'),
    mv('sell_proceeds', 250.5, '2026-02-01T09:00:00Z'),
    mv('withdrawal', -250.5, '2026-02-02T09:00:00Z'),
  ]);
  run('is 0 for an empty ledger', []);
  run('does not enforce non-negativity — reconciliation is a pure sum', [
    mv('deposit', 100, '2026-01-05'),
    mv('buy', -150, '2026-01-06'),
  ]);
  run('rejects an unknown kind', [mv('jackpot' as CashMovementKind, 10, '2026-01-05')]);
  run('rejects a zero amount', [mv('deposit', 0, '2026-01-05')]);
  run('rejects a sign that contradicts the kind (deposit)', [mv('deposit', -10, '2026-01-05')]);
  run('rejects a sign that contradicts the kind (sell_proceeds)', [
    mv('sell_proceeds', -10, '2026-01-05'),
  ]);
  run('rejects a sign that contradicts the kind (withdrawal)', [
    mv('withdrawal', 10, '2026-01-05'),
  ]);
  run('rejects a sign that contradicts the kind (buy)', [mv('buy', 10, '2026-01-05')]);
  run('rejects an unparseable timestamp and names the movement index', [
    mv('deposit', 10, '2026-01-05'),
    mv('deposit', 10, 'yesterday-ish'),
  ]);
  run('a positive fee is rejected (fee is negative-only)', [
    mv('fee', 12.5, '2026-01-05T09:00:00Z'),
  ]);
  run('a negative fee is accepted', [mv('fee', -12.5, '2026-01-05T09:00:00Z')]);
  run('rolls the per-source union up to the portfolio balance', [
    smv('main', 'deposit', 1000, '2026-01-05T09:00:00Z'),
    smv('bank', 'deposit', 250, '2026-01-05T10:00:00Z'),
    smv('main', 'buy', -400, '2026-01-06T10:00:00Z'),
    smv('bank', 'withdrawal', -50, '2026-01-07T10:00:00Z'),
  ]);

  skip(
    'cashBalance',
    'rejects non-finite amounts',
    'inputs are Number.NaN / ±Number.POSITIVE_INFINITY, which JSON cannot represent — hand-ported in CashLedgerHandPortedTest ("cashBalance rejects non-finite amounts")',
  );
}

// --- applyCashMovement -----------------------------------------------------

function genApplyCashMovement(): void {
  const run = (name: string, balanceEur: number, movement: CashMovement): void =>
    cash('applyCashMovement', name, { balanceEur, movement: mJson(movement) }, () =>
      applyCashMovement(balanceEur, movement),
    );

  run('returns the balance after the movement (deposit)', 0, mv('deposit', 1000, '2026-01-05'));
  run('returns the balance after the movement (buy)', 1000, mv('buy', -400, '2026-01-05'));
  run(
    'returns the balance after the movement (withdrawal to exactly 0)',
    600,
    mv('withdrawal', -600, '2026-01-05'),
  );
  run('allows spending the balance down to exactly 0', 250, mv('buy', -250, '2026-01-05'));
  run(
    'throws the typed InsufficientCashError when a buy would overdraw',
    100,
    mv('buy', -150, '2026-01-05'),
  );
  run('throws when a withdrawal would overdraw', 100, mv('withdrawal', -100.01, '2026-01-05'));
  run(
    'carries the available balance, the movement, and the exact shortfall',
    100,
    mv('buy', -150, '2026-01-05T09:00:00Z'),
  );
  run(
    'InsufficientCashError is not a CashLedgerError — valid, just unaffordable',
    0,
    mv('withdrawal', -1, '2026-01-05'),
  );

  // "tolerates FP dust": the three-step chain, each step recorded with the exact
  // double the previous step returned.
  let balance = 0;
  const dustUp: [string, number, CashMovement][] = [];
  dustUp.push(['0.1 + 0.2 in, 0.3 out (step 1)', balance, mv('deposit', 0.1, '2026-01-05')]);
  balance = applyCashMovement(balance, mv('deposit', 0.1, '2026-01-05'));
  dustUp.push(['0.1 + 0.2 in, 0.3 out (step 2)', balance, mv('deposit', 0.2, '2026-01-06')]);
  balance = applyCashMovement(balance, mv('deposit', 0.2, '2026-01-06'));
  dustUp.push(['0.1 + 0.2 in, 0.3 out (step 3, does not throw)', balance, mv('buy', -0.3, '2026-01-07')]);
  for (const [name, b, m] of dustUp) run(`tolerates FP dust: ${name}`, b, m);

  balance = 0;
  const dustDown: [string, number, CashMovement][] = [];
  dustDown.push(['0.3 in, 0.1 + 0.2 out (step 1)', balance, mv('deposit', 0.3, '2026-01-05')]);
  balance = applyCashMovement(balance, mv('deposit', 0.3, '2026-01-05'));
  dustDown.push(['0.3 in, 0.1 + 0.2 out (step 2)', balance, mv('buy', -0.1, '2026-01-06')]);
  balance = applyCashMovement(balance, mv('buy', -0.1, '2026-01-06'));
  dustDown.push(['0.3 in, 0.1 + 0.2 out (step 3, does not throw)', balance, mv('buy', -0.2, '2026-01-07')]);
  for (const [name, b, m] of dustDown) run(`tolerates dust the other way: ${name}`, b, m);

  run('a real overdraft of one cent is NOT dust and throws', 0.3, mv('buy', -0.31, '2026-01-05'));
  run('rejects a negative starting balance', -1, mv('deposit', 10, '2026-01-05'));

  skip(
    'applyCashMovement',
    'rejects a non-finite starting balance',
    'inputs are Number.NaN / Number.POSITIVE_INFINITY, which JSON cannot represent — hand-ported in CashLedgerHandPortedTest ("applyCashMovement rejects a non-finite starting balance")',
  );
  skip(
    'applyCashMovement',
    'InsufficientCashError field + identity assertions',
    'asserts err.balanceEur / err.shortfallEur / err.name, `err.movement` REFERENTIAL identity, and `not.toBeInstanceOf(CashLedgerError)` — none of which is input→output data — hand-ported in CashLedgerHandPortedTest',
  );
}

// --- projectCashLedger -----------------------------------------------------

function genProjectCashLedger(): void {
  const run = (name: string, movements: CashMovement[]): void =>
    cash('projectCashLedger', name, { movements: msJson(movements) }, () =>
      entriesJson(projectCashLedger(movements)),
    );

  run('returns the running balance after every movement (balance-over-time)', mixedSequence());
  run('a sequence that stays >= 0 does not throw', mixedSequence());
  run('a sequence that dips negative does throw', [
    mv('deposit', 100, '2026-01-05'),
    mv('buy', -100, '2026-01-06'),
    mv('withdrawal', -1, '2026-01-07'),
  ]);
  run('final projected balance equals cashBalance', mixedSequence());
  run('replays by occurredAt, not input order', [
    mv('buy', -500, '2026-01-06T10:00:00Z'),
    mv('deposit', 1000, '2026-01-05T10:00:00Z'),
  ]);
  run('a buy dated before its funding deposit throws, wherever it is listed', [
    mv('deposit', 1000, '2026-01-06T10:00:00Z'),
    mv('buy', -500, '2026-01-05T10:00:00Z'),
  ]);
  run('breaks timestamp ties by input order (deposit first: accepted)', [
    mv('deposit', 100, '2026-01-05T10:00:00Z'),
    mv('buy', -100, '2026-01-05T10:00:00Z'),
  ]);
  run('breaks timestamp ties by input order (buy first: rejected)', [
    mv('buy', -100, '2026-01-05T10:00:00Z'),
    mv('deposit', 100, '2026-01-05T10:00:00Z'),
  ]);
  run('does not mutate the input array', [
    mv('buy', -500, '2026-01-06T10:00:00Z'),
    mv('deposit', 1000, '2026-01-05T10:00:00Z'),
  ]);
  run('returns [] for an empty ledger', []);
  run('validates every movement up front', [
    mv('deposit', 10, '2026-01-05'),
    mv('deposit', -1, '2026-01-06'),
  ]);
  run('a fee lowers the balance and is gated by the same no-negative rule', [
    mv('deposit', 100, '2026-01-05T09:00:00Z'),
    mv('fee', -2.5, '2026-01-06T09:00:00Z'),
  ]);
  run('an unfunded fee overdraws like any other outflow', [
    mv('fee', -2.5, '2026-01-06T09:00:00Z'),
  ]);
  run('per-source validity implies the portfolio-level union never dips negative', [
    smv('main', 'deposit', 500, '2026-01-05T09:00:00Z'),
    smv('main', 'transfer_out', -500, '2026-01-06T10:00:00Z'),
    smv('bank', 'transfer_in', 500, '2026-01-06T10:00:00Z'),
    smv('bank', 'withdrawal', -500, '2026-01-07T10:00:00Z'),
  ]);

  // spendableAsOf's `gateAccepts()` helper: the write-boundary gate's own verdict
  // on inserting a buy of `cost` at `at`, recorded as real projections.
  const gate = (name: string, movements: CashMovement[], cost: number, at: string): void =>
    run(`gate: ${name}`, [...movements, mv('buy', -cost, at)]);

  gate(
    'a 400 buy backdated before the deposit overdraws',
    [mv('deposit', 500, '2026-02-01T00:00:00.000Z')],
    400,
    '2025-06-01T00:00:00.000Z',
  );
  gate(
    'spending exactly the pre-existing balance is accepted',
    [mv('deposit', 500, '2025-01-01T00:00:00.000Z')],
    500,
    '2025-06-01T00:00:00.000Z',
  );
  gate(
    'one cent more than the pre-existing balance is rejected',
    [mv('deposit', 500, '2025-01-01T00:00:00.000Z')],
    500.01,
    '2025-06-01T00:00:00.000Z',
  );
  gate(
    'the later withdrawal binds the spend to 100 (accepted)',
    [
      mv('deposit', 1000, '2026-01-01T00:00:00.000Z'),
      mv('withdrawal', -900, '2026-01-02T00:00:00.000Z'),
    ],
    100,
    '2026-01-01T00:00:00.000Z',
  );
  gate(
    'the later withdrawal binds the spend to 100 (150 rejected)',
    [
      mv('deposit', 1000, '2026-01-01T00:00:00.000Z'),
      mv('withdrawal', -900, '2026-01-02T00:00:00.000Z'),
    ],
    150,
    '2026-01-01T00:00:00.000Z',
  );
  gate(
    'a same-instant deposit funds that instant’s buy',
    [mv('deposit', 400, '2025-06-01T00:00:00.000Z')],
    400,
    '2025-06-01T00:00:00.000Z',
  );
  gate(
    'mixed history: spendable is accepted exactly',
    [
      mv('deposit', 300, '2026-01-10T00:00:00.000Z'),
      mv('deposit', 500, '2026-03-01T00:00:00.000Z'),
      mv('withdrawal', -200, '2026-04-01T00:00:00.000Z'),
    ],
    300,
    '2026-02-01T00:00:00.000Z',
  );
  gate(
    'mixed history: one cent past spendable is rejected',
    [
      mv('deposit', 300, '2026-01-10T00:00:00.000Z'),
      mv('deposit', 500, '2026-03-01T00:00:00.000Z'),
      mv('withdrawal', -200, '2026-04-01T00:00:00.000Z'),
    ],
    300.01,
    '2026-02-01T00:00:00.000Z',
  );

  skip(
    'projectCashLedger',
    'does not mutate the input array (mutation assertion)',
    'asserts the CALLER’s array is untouched, which is a property of the port, not of its output — hand-ported in CashLedgerHandPortedTest ("projectCashLedger does not mutate the input list")',
  );
}

// --- cashBalanceOverTime ---------------------------------------------------

function genCashBalanceOverTime(): void {
  const run = (name: string, movements: CashMovement[]): void =>
    cash('cashBalanceOverTime', name, { movements: msJson(movements) }, () =>
      cashBalanceOverTime(movements),
    );

  run('emits one point per movement day with that day’s closing balance', mixedSequence());
  run('collapses same-day movements to the last balance of the day', [
    mv('deposit', 1000, '2026-01-05T09:00:00Z'),
    mv('buy', -400, '2026-01-05T15:00:00Z'),
    mv('withdrawal', -100, '2026-01-07T09:00:00Z'),
  ]);
  run('is sparse — days without movements produce no point', mixedSequence());
  run('rejects negative-dipping histories like the projection', [
    mv('withdrawal', -1, '2026-01-05'),
  ]);
  run('returns [] for an empty ledger', []);
}

// --- spendableAsOf ---------------------------------------------------------

function genSpendableAsOf(): void {
  const run = (name: string, movements: CashMovement[], occurredAt: string): void =>
    cash('spendableAsOf', name, { movements: msJson(movements), occurredAt }, () =>
      spendableAsOf(movements, occurredAt),
    );

  run(
    'is 0 when the cash was only deposited AFTER the buy date',
    [mv('deposit', 500, '2026-02-01T00:00:00.000Z')],
    '2025-06-01T00:00:00.000Z',
  );
  run(
    'is the balance already present when the deposit predates the buy',
    [mv('deposit', 500, '2025-01-01T00:00:00.000Z')],
    '2025-06-01T00:00:00.000Z',
  );
  run(
    'takes the running MINIMUM from the buy instant on — a later withdrawal binds',
    [
      mv('deposit', 1000, '2026-01-01T00:00:00.000Z'),
      mv('withdrawal', -900, '2026-01-02T00:00:00.000Z'),
    ],
    '2026-01-01T00:00:00.000Z',
  );
  run(
    'counts a same-instant deposit as available (credits before debits)',
    [mv('deposit', 400, '2025-06-01T00:00:00.000Z')],
    '2025-06-01T00:00:00.000Z',
  );
  run(
    'a buy dated at/after the newest movement yields the current balance',
    [
      mv('deposit', 1000, '2026-01-01T00:00:00.000Z'),
      mv('buy', -300, '2026-01-05T00:00:00.000Z'),
    ],
    '2026-07-01T00:00:00.000Z',
  );
  run('is 0 for an empty ledger', [], '2025-06-01T00:00:00.000Z');
  run(
    'matches the write-boundary gate across a mixed history',
    [
      mv('deposit', 300, '2026-01-10T00:00:00.000Z'),
      mv('deposit', 500, '2026-03-01T00:00:00.000Z'),
      mv('withdrawal', -200, '2026-04-01T00:00:00.000Z'),
    ],
    '2026-02-01T00:00:00.000Z',
  );
}

// --- the TWR classifier ----------------------------------------------------

function genTwrClassification(): void {
  cash('CASH_MOVEMENT_KINDS', 'the declared kinds, in declaration order', {}, () => [
    ...CASH_MOVEMENT_KINDS,
  ]);
  cash(
    'CASH_MOVEMENT_SIGN',
    'the required sign of EVERY kind, in declaration order',
    {},
    () =>
      Object.entries(CASH_MOVEMENT_SIGN).map(([kind, sign]) => ({ kind, sign })),
  );
  cash('EXTERNAL_CASH_MOVEMENT_KINDS', 'exactly deposit + withdrawal', {}, () => [
    ...EXTERNAL_CASH_MOVEMENT_KINDS,
  ]);

  for (const kind of CASH_MOVEMENT_KINDS) {
    cash(
      'isExternalCashMovement',
      `pins the classification of every kind (${kind})`,
      { kind },
      () => isExternalCashMovement(kind),
    );
  }

  const run = (name: string, movements: CashMovement[]): void =>
    cash('externalCashFlowsForTwr', name, { movements: msJson(movements) }, () =>
      externalCashFlowsForTwr(movements),
    );

  run('deposit-then-buy-from-cash yields exactly ONE external flow: the deposit', [
    mv('deposit', 1000, '2026-01-05T09:00:00Z'),
    mv('buy', -1000, '2026-01-06T10:00:00Z'),
  ]);
  run('returns only deposits/withdrawals; buy and sell_proceeds are internal', mixedSequence());
  run('keeps the FlowPoint sign convention and sorts ascending', [
    mv('withdrawal', -200, '2026-01-08'),
    mv('deposit', 1000, '2026-01-05'),
  ]);
  run('nets same-day external flows into one point', [
    mv('deposit', 1000, '2026-01-05T09:00:00Z'),
    mv('deposit', 500, '2026-01-05T11:00:00Z'),
    mv('withdrawal', -300, '2026-01-05T15:00:00Z'),
  ]);
  run('is a pure classifier — it does not enforce solvency', [
    mv('withdrawal', -100, '2026-01-05'),
  ]);
  run('returns [] for an empty ledger', []);
  run('returns [] when there are no external movements', [
    mv('sell_proceeds', 150, '2026-01-05'),
    mv('buy', -150, '2026-01-06'),
  ]);
  run('validates movements like the rest of the engine', [mv('deposit', -10, '2026-01-05')]);
  run('a fee never enters the TWR flow series', [
    mv('deposit', 1000, '2026-01-05T09:00:00Z'),
    mv('fee', -10, '2026-01-06T09:00:00Z'),
    mv('withdrawal', -10, '2026-01-07T09:00:00Z'),
  ]);
  run('none of the three tax kinds is ever an external flow', [
    mv('dividend', 50, '2026-01-07T10:00:00Z'),
    mv('tax_withholding', -13.75, '2026-01-08T10:00:00Z'),
    mv('tax_refund', 5, '2026-01-08T11:00:00Z'),
  ]);
}

// --- compositions with holdings.timeWeightedReturn -------------------------
//
// Every composition case in the suite runs the ledger classifier / net-worth
// builder and feeds the result to `timeWeightedReturn`. Each stage is emitted as
// its own vector against the REAL intermediate the TS produced, so the chain is
// covered end to end without inventing a composite entry point.

function genLedgerTwrCompositions(): void {
  const flows = (name: string, movements: CashMovement[]): { date: string; flowEur: number }[] => {
    cash('externalCashFlowsForTwr', name, { movements: msJson(movements) }, () =>
      externalCashFlowsForTwr(movements),
    );
    return externalCashFlowsForTwr(movements) as { date: string; flowEur: number }[];
  };
  const twr = (
    name: string,
    values: { date: string; valueEur: number }[],
    flowPoints: { date: string; flowEur: number }[],
  ): void =>
    cash('timeWeightedReturn', name, { values, flows: flowPoints }, () =>
      timeWeightedReturn(values, flowPoints),
    );
  const netWorth = (
    name: string,
    holdingsValues: { date: string; valueEur: number }[],
    movements: CashMovement[],
    today: string,
  ): { date: string; valueEur: number }[] => {
    cash(
      'netWorthSeries',
      name,
      { holdingsValues, movements: msJson(movements), today },
      () => netWorthSeries({ holdingsValues, movements, today }),
    );
    return netWorthSeries({ holdingsValues, movements, today });
  };

  // --- the `fee` kind DRAGS the return ---
  {
    const feeFreeValues = [
      { date: '2026-01-05', valueEur: 1000 },
      { date: '2026-01-06', valueEur: 1000 },
      { date: '2026-01-07', valueEur: 1100 },
    ];
    const feePaidValues = [
      { date: '2026-01-05', valueEur: 1000 },
      { date: '2026-01-06', valueEur: 990 },
      { date: '2026-01-07', valueEur: 1089 },
    ];
    const deposit = mv('deposit', 1000, '2026-01-05T09:00:00Z');
    const freeFlows = flows('fee drag: the fee-free ledger’s external flows', [deposit]);
    const paidFlows = flows('fee drag: the fee-charged ledger’s external flows', [
      deposit,
      mv('fee', -10, '2026-01-06T09:00:00Z'),
    ]);
    twr('fee drag: the fee-free portfolio earns the pure market return', feeFreeValues, freeFlows);
    twr('fee drag: the fee-charged portfolio earns strictly less', feePaidValues, paidFlows);

    const sameValues = feePaidValues;
    const withdrawalFlows = flows(
      'fee vs withdrawal: the same 10 EUR booked as a withdrawal IS external',
      [deposit, mv('withdrawal', -10, '2026-01-06T09:00:00Z')],
    );
    twr(
      'fee vs withdrawal: as a withdrawal the drag is divided back out (the misreport)',
      sameValues,
      withdrawalFlows,
    );
    twr('fee vs withdrawal: as a fee the curve reads honestly', sameValues, paidFlows);
  }

  // --- TWR neutrality of cash-funded buys ---
  {
    const values = [
      { date: '2026-01-05', valueEur: 1000 },
      { date: '2026-01-06', valueEur: 1000 },
      { date: '2026-01-07', valueEur: 1100 },
    ];
    const movements = [
      mv('deposit', 1000, '2026-01-05T09:00:00Z'),
      mv('buy', -1000, '2026-01-06T10:00:00Z'),
    ];
    const f = flows('TWR neutrality: a cash-funded buy is not an external flow', movements);
    twr('TWR neutrality: the performance curve is unaffected by cash -> stock', values, f);
    twr('TWR neutrality counterfactual: misclassifying the buy corrupts the curve', values, [
      { date: '2026-01-05', flowEur: 1000 },
      { date: '2026-01-06', flowEur: -1000 },
    ]);
  }

  // --- dividends and tax settlements are TWR-internal ---
  {
    const holdingsValues = [
      { date: '2026-01-06', valueEur: 1000 },
      { date: '2026-01-07', valueEur: 1000 },
      { date: '2026-01-08', valueEur: 1000 },
    ];
    const funded: CashMovement[] = [
      mv('deposit', 1000, '2026-01-05T09:00:00Z'),
      mv('buy', -1000, '2026-01-06T10:00:00Z'),
    ];
    const perfFor = (label: string, movements: CashMovement[]): void => {
      const points = netWorth(`${label} (net worth)`, holdingsValues, movements, '2026-01-08');
      const f = flows(`${label} (external flows)`, movements);
      twr(`${label} (performance)`, points, f);
    };

    perfFor('a dividend MOVES the line: income the holdings generated', [
      ...funded,
      mv('dividend', 50, '2026-01-07T10:00:00Z'),
    ]);
    {
      const movements = [...funded, mv('dividend', 50, '2026-01-07T10:00:00Z')];
      const points = netWorthSeries({ holdingsValues, movements, today: '2026-01-08' });
      twr('counterfactual: classifying that dividend as a deposit erases the income', points, [
        ...externalCashFlowsForTwr(movements),
        { date: '2026-01-07', flowEur: 50 },
      ]);
    }
    perfFor('withheld tax drags the line — the curve reads NET of tax', [
      ...funded,
      mv('dividend', 50, '2026-01-07T10:00:00Z'),
      mv('tax_withholding', -13.75, '2026-01-08T10:00:00Z'),
    ]);
    perfFor('a tax refund lifts the line back, symmetrically', [
      ...funded,
      mv('dividend', 50, '2026-01-07T10:00:00Z'),
      mv('tax_withholding', -13.75, '2026-01-07T11:00:00Z'),
      mv('tax_refund', 13.75, '2026-01-08T10:00:00Z'),
    ]);
  }

  // --- transfer legs are invisible to both curves ---
  {
    const before = [
      smv('main', 'deposit', 1000, '2026-01-05T09:00:00Z'),
      smv('bank', 'deposit', 200, '2026-01-05T09:30:00Z'),
    ];
    const legs = pairedTransferMovements({
      fromSourceId: 'main',
      toSourceId: 'bank',
      amountEur: 500,
      occurredAt: '2026-01-06T10:00:00Z',
    });
    const after = [...before, legs.outgoing, legs.incoming];
    const today = '2026-01-07';
    const beforeFlows = flows('transfer legs: external flows BEFORE the transfer', before);
    const afterFlows = flows('transfer legs: external flows AFTER the transfer (identical)', after);
    const beforeValues = netWorth(
      'transfer legs: net worth BEFORE the transfer',
      [],
      before,
      today,
    );
    const afterValues = netWorth(
      'transfer legs: net worth AFTER the transfer (identical)',
      [],
      after,
      today,
    );
    twr('transfer legs: performance BEFORE the transfer', beforeValues, beforeFlows);
    twr('transfer legs: performance AFTER the transfer (identical)', afterValues, afterFlows);
  }

  // --- netWorthSeries composes with timeWeightedReturn ---
  {
    const holdingsValues = [
      { date: '2026-01-06', valueEur: 1000 },
      { date: '2026-01-07', valueEur: 1100 },
    ];
    const movements = [
      mv('deposit', 1000, '2026-01-05T09:00:00Z'),
      mv('buy', -1000, '2026-01-06T10:00:00Z'),
    ];
    const points = netWorth(
      'composes with timeWeightedReturn (net worth)',
      holdingsValues,
      movements,
      '2026-01-07',
    );
    const f = flows('composes with timeWeightedReturn (external flows)', movements);
    twr('composes with timeWeightedReturn: deposit and conversion both link flat', points, f);
  }
}

// --- netWorthSeries --------------------------------------------------------

function genNetWorthSeries(): void {
  const run = (
    name: string,
    holdingsValues: { date: string; valueEur: number }[],
    movements: CashMovement[],
    today: string,
  ): void =>
    cash(
      'netWorthSeries',
      name,
      { holdingsValues, movements: msJson(movements), today },
      () => netWorthSeries({ holdingsValues, movements, today }),
    );

  run('returns an empty series when there are neither holdings values nor movements', [], [], '2026-01-10');
  run(
    'is the identity on the holdings curve when the ledger is empty',
    [
      { date: '2026-01-05', valueEur: 1000 },
      { date: '2026-01-06', valueEur: 1100 },
    ],
    [],
    '2026-01-06',
  );
  run(
    'renders a cash-only portfolio as a dense daily curve through today',
    [],
    [
      mv('deposit', 1000, '2026-01-05T09:00:00Z'),
      mv('withdrawal', -250, '2026-01-07T09:00:00Z'),
    ],
    '2026-01-09',
  );
  run(
    'equals holdings value + end-of-day cash balance on every day',
    [
      { date: '2026-01-07', valueEur: 400 },
      { date: '2026-01-08', valueEur: 400 },
      { date: '2026-01-09', valueEur: 250 },
      { date: '2026-01-10', valueEur: 250 },
    ],
    [
      mv('deposit', 1000, '2026-01-05T09:00:00Z'),
      mv('buy', -400, '2026-01-07T10:00:00Z'),
      mv('sell_proceeds', 150, '2026-01-09T11:00:00Z'),
      mv('withdrawal', -200, '2026-01-10T12:00:00Z'),
    ],
    '2026-01-10',
  );
  run(
    'a deposit/withdrawal moves the curve by exactly its amount; a cash-funded buy does not',
    [
      { date: '2026-01-06', valueEur: 500 },
      { date: '2026-01-07', valueEur: 500 },
    ],
    [
      mv('deposit', 1000, '2026-01-05T09:00:00Z'),
      mv('buy', -500, '2026-01-06T10:00:00Z'),
      mv('withdrawal', -200, '2026-01-07T09:00:00Z'),
    ],
    '2026-01-07',
  );
  run(
    'aggregates several same-day movements into one end-of-day balance',
    [],
    [
      mv('deposit', 300, '2026-01-05T09:00:00Z'),
      mv('deposit', 700, '2026-01-05T15:00:00Z'),
      mv('withdrawal', -100, '2026-01-05T18:00:00Z'),
    ],
    '2026-01-05',
  );
  run(
    'ignores movements dated after the series end',
    [{ date: '2026-01-05', valueEur: 100 }],
    [mv('deposit', 1000, '2026-02-01T09:00:00Z')],
    '2026-01-05',
  );
  run(
    'only future-dated movements and no holdings leaves nothing plottable',
    [],
    [mv('deposit', 1000, '2026-02-01T09:00:00Z')],
    '2026-01-05',
  );
  run(
    'display path: renders a ledger that dips negative instead of throwing',
    [],
    [
      mv('withdrawal', -200, '2026-01-05T09:00:00Z'),
      mv('deposit', 1000, '2026-01-06T09:00:00Z'),
    ],
    '2026-01-06',
  );
  run('fails loud on a malformed today', [], [], 'not-a-day');
  run(
    'fails loud on a movement whose sign contradicts its kind',
    [],
    [mv('deposit', -5, '2026-01-05')],
    '2026-01-05',
  );
  run(
    'a fee still counts toward net worth — the money really left the account',
    [],
    [
      mv('deposit', 1000, '2026-01-05T09:00:00Z'),
      mv('fee', -10, '2026-01-06T09:00:00Z'),
    ],
    '2026-01-06',
  );

  skip(
    'netWorthSeries',
    'fails loud on a non-finite holdings value',
    'input carries Number.NaN, which JSON cannot represent — hand-ported in CashLedgerHandPortedTest ("netWorthSeries rejects a non-finite holdings value")',
  );
}

// --- cash sources ----------------------------------------------------------

function genCashSources(): void {
  const balances = (name: string, movements: SourcedCashMovement[]): void =>
    cash('cashBalancesBySource', name, { movements: msJson(movements) }, () =>
      balancesJson(cashBalancesBySource(movements)),
    );

  balances('sums each source independently', [
    smv('main', 'deposit', 1000, '2026-01-05T09:00:00Z'),
    smv('bank', 'deposit', 250, '2026-01-05T10:00:00Z'),
    smv('main', 'buy', -400, '2026-01-06T10:00:00Z'),
    smv('bank', 'withdrawal', -50, '2026-01-07T10:00:00Z'),
  ]);
  balances('omits sources without movements (empty ledger)', []);
  balances('fails loud on an empty sourceId', [
    { kind: 'deposit', amountEur: 1, occurredAt: '2026-01-05', sourceId: '' },
  ]);
  balances('a fee belongs to exactly one source and never pairs', [
    { ...mv('deposit', 100, '2026-01-05T09:00:00Z'), sourceId: 'bank' },
    { ...mv('fee', -4, '2026-01-06T09:00:00Z'), sourceId: 'bank' },
    { ...mv('deposit', 50, '2026-01-05T09:00:00Z'), sourceId: 'main' },
  ]);

  const project = (name: string, movements: SourcedCashMovement[]): void =>
    cash('projectCashLedgerBySource', name, { movements: msJson(movements) }, () =>
      [...projectCashLedgerBySource(movements).entries()].map(([sourceId, entries]) => ({
        sourceId,
        entries: entriesJson(entries),
      })),
    );

  project('rejects a source overdraft even when another source holds plenty', [
    smv('main', 'deposit', 10_000, '2026-01-05T09:00:00Z'),
    smv('bank', 'deposit', 100, '2026-01-05T10:00:00Z'),
    smv('bank', 'withdrawal', -150, '2026-01-06T10:00:00Z'),
  ]);
  project('projects each source chronologically and independently', [
    smv('bank', 'transfer_in', 300, '2026-01-06T10:00:00Z'),
    smv('main', 'deposit', 1000, '2026-01-05T09:00:00Z'),
    smv('main', 'transfer_out', -300, '2026-01-06T10:00:00Z'),
    smv('main', 'buy', -500, '2026-01-07T10:00:00Z'),
  ]);
  project('per-source validity implies portfolio-level validity', [
    smv('main', 'deposit', 500, '2026-01-05T09:00:00Z'),
    smv('main', 'transfer_out', -500, '2026-01-06T10:00:00Z'),
    smv('bank', 'transfer_in', 500, '2026-01-06T10:00:00Z'),
    smv('bank', 'withdrawal', -500, '2026-01-07T10:00:00Z'),
  ]);
  project('solvency is per source: "main" cannot cover a fee charged to "bank"', [
    { ...mv('deposit', 1, '2026-01-05T09:00:00Z'), sourceId: 'main' },
    { ...mv('fee', -4, '2026-01-06T09:00:00Z'), sourceId: 'bank' },
  ]);

  skip(
    'projectCashLedgerBySource',
    'the rejection carries balanceEur / shortfallEur / movement.sourceId',
    'asserts the InsufficientCashError’s FIELDS (and that the offending movement kept its source attribution), not merely that it threw — hand-ported in CashLedgerHandPortedTest',
  );
}

// --- transfers & set-balance ----------------------------------------------

function genTransfersAndSetBalance(): void {
  const transfer = (
    name: string,
    fromSourceId: string,
    toSourceId: string,
    amountEur: number,
    occurredAt: string,
  ): void =>
    cash(
      'pairedTransferMovements',
      name,
      { fromSourceId, toSourceId, amountEur, occurredAt },
      () => {
        const legs = pairedTransferMovements({ fromSourceId, toSourceId, amountEur, occurredAt });
        return { outgoing: mJson(legs.outgoing), incoming: mJson(legs.incoming) };
      },
    );

  transfer(
    'builds mirrored double-entry legs sharing the timestamp',
    'main',
    'bank',
    500,
    '2026-02-01T12:00:00Z',
  );
  transfer('floors the magnitude to whole cents', 'a', 'b', 10.005, '2026-02-01T12:00:00Z');
  transfer('rejects a same-source transfer', 'a', 'a', 10, '2026-02-01T12:00:00Z');
  transfer('rejects a zero amount', 'a', 'b', 0, '2026-02-01T12:00:00Z');
  transfer('rejects a negative amount', 'a', 'b', -5, '2026-02-01T12:00:00Z');
  transfer('rejects a sub-cent amount that floors away', 'a', 'b', 0.001, '2026-02-01T12:00:00Z');
  transfer('rejects an empty fromSourceId', '', 'b', 10, '2026-02-01T12:00:00Z');
  transfer('rejects a malformed occurredAt', 'a', 'b', 10, 'not-a-date');
  transfer(
    'transfer legs are internal: the pair cancels in every roll-up',
    'main',
    'bank',
    500,
    '2026-01-06T10:00:00Z',
  );

  {
    const legs = pairedTransferMovements({
      fromSourceId: 'main',
      toSourceId: 'bank',
      amountEur: 500,
      occurredAt: '2026-02-01T12:00:00Z',
    });
    const pair = [legs.outgoing, legs.incoming];
    cash('cashBalance', 'a transfer pair cancels to exactly 0', { movements: msJson(pair) }, () =>
      cashBalance(pair),
    );
  }

  skip(
    'pairedTransferMovements',
    'rejects a NaN amount',
    'input is Number.NaN, which JSON cannot represent — hand-ported in CashLedgerHandPortedTest ("pairedTransferMovements rejects a non-finite amount")',
  );

  const delta = (name: string, currentBalanceEur: number, targetBalanceEur: number): void =>
    cash('setBalanceDelta', name, { currentBalanceEur, targetBalanceEur }, () =>
      setBalanceDelta(currentBalanceEur, targetBalanceEur),
    );

  delta('computes the owner example exactly: 123.45 -> 200.00 is +76.55', 123.45, 200.0);
  delta('is symmetric for negative deltas: 200.00 -> 123.45 is -76.55', 200.0, 123.45);
  delta('floors both operands to cents before differencing (0 -> 100.006)', 0, 100.006);
  delta('floors both operands to cents before differencing (100.004 -> 100.004)', 100.004, 100.004);
  delta('setting a balance to 0.00 withdraws everything', 123.45, 0);
  delta('rejects a negative target', 100, -1);

  skip(
    'setBalanceDelta',
    'rejects a non-finite target and a non-finite current balance',
    'inputs are Number.POSITIVE_INFINITY / Number.NaN, which JSON cannot represent — hand-ported in CashLedgerHandPortedTest ("setBalanceDelta rejects non-finite operands")',
  );

  const setBalance = (
    name: string,
    sourceId: string,
    currentBalanceEur: number,
    targetBalanceEur: number,
    occurredAt: string,
  ): void =>
    cash(
      'setBalanceMovement',
      name,
      { sourceId, currentBalanceEur, targetBalanceEur, occurredAt },
      () => {
        const movement = setBalanceMovement({
          sourceId,
          currentBalanceEur,
          targetBalanceEur,
          occurredAt,
        });
        return movement === null ? null : mJson(movement);
      },
    );

  setBalance('builds a normal deposit for a positive delta', 'bank', 123.45, 200.0, '2026-03-01T08:00:00Z');
  setBalance('builds a withdrawal for a negative delta', 'bank', 200.0, 123.45, '2026-03-01T08:00:00Z');
  setBalance('records nothing when the target equals the current balance', 'bank', 200.0, 200.0, '2026-03-01T08:00:00Z');
  setBalance('set-balance deltas are external flows', 'bank', 0, 500, '2026-03-01T08:00:00Z');

  {
    const movement = setBalanceMovement({
      sourceId: 'bank',
      currentBalanceEur: 0,
      targetBalanceEur: 500,
      occurredAt: '2026-03-01T08:00:00Z',
    })!;
    cash(
      'externalCashFlowsForTwr',
      'a set-balance deposit is an external flow like any other',
      { movements: msJson([movement]) },
      () => externalCashFlowsForTwr([movement]),
    );
    cash(
      'isExternalCashMovement',
      'a set-balance movement’s kind is external',
      { kind: movement.kind },
      () => isExternalCashMovement(movement.kind),
    );
  }
}

// --- cashBySourceOverTime (the cashLedger half of dailySnapshotSeries.test.ts)

function genCashBySourceOverTime(): void {
  const run = (name: string, movements: SourcedCashMovement[], endDay: string): void =>
    cash('cashBySourceOverTime', name, { movements: msJson(movements), endDay }, () =>
      cashBySourceOverTime(movements, endDay).map((p) => ({
        date: p.date,
        balances: balancesJson(p.balances),
      })),
    );

  run('returns an empty series without movements at all', [], '2026-01-05');
  run(
    'returns an empty series when every movement is after endDay',
    [sourced({ occurredAt: at('2026-02-01') })],
    '2026-01-05',
  );
  run(
    'carries each source’s EOD balance forward daily',
    [
      sourced({ occurredAt: at('2026-01-01'), amountEur: 100, sourceId: 'main' }),
      sourced({ occurredAt: at('2026-01-03'), amountEur: 50, sourceId: 'bank' }),
    ],
    '2026-01-04',
  );
  run(
    'nets same-day movements to one EOD figure and moves a transfer between sources',
    [
      sourced({ occurredAt: '2026-01-01T09:00:00.000Z', amountEur: 100, sourceId: 'main' }),
      sourced({
        kind: 'transfer_out',
        occurredAt: '2026-01-01T12:00:00.000Z',
        amountEur: -40,
        sourceId: 'main',
      }),
      sourced({
        kind: 'transfer_in',
        occurredAt: '2026-01-01T12:00:00.000Z',
        amountEur: 40,
        sourceId: 'bank',
      }),
    ],
    '2026-01-01',
  );

  const cashLeg: SourcedCashMovement[] = [
    sourced({ occurredAt: at('2026-01-01'), amountEur: 123.45, sourceId: 'main' }),
    sourced({
      kind: 'withdrawal',
      occurredAt: at('2026-01-02'),
      amountEur: -23.45,
      sourceId: 'main',
    }),
    sourced({ occurredAt: at('2026-01-02'), amountEur: 10, sourceId: 'bank' }),
  ];
  run('sums per day to exactly the net-worth curve’s cash leg', cashLeg, '2026-01-03');
  cash(
    'netWorthSeries',
    'the net-worth curve the per-source split must sum to, day for day',
    { holdingsValues: [], movements: msJson(cashLeg), today: '2026-01-03' },
    () => netWorthSeries({ holdingsValues: [], movements: cashLeg, today: '2026-01-03' }),
  );

  run('fails loud on a malformed endDay', [], 'nope');
  run(
    'fails loud on a movement whose sign contradicts its kind',
    [sourced({ occurredAt: at('2026-01-01'), amountEur: -5 })],
    '2026-01-02',
  );
}

// ===========================================================================
// settingsScope.ts
// ===========================================================================

/**
 * TS distinguishes `null` from `undefined`; Kotlin has one absent value. The
 * vectors therefore carry an explicit `present` flag, and BOTH TS absentees map
 * to Kotlin `null` — which is exactly what `resolvePortfolioSetting`'s
 * `!== null && !== undefined` guard collapses to.
 */
type Layer = { present: false; undefinedInTs: boolean } | { present: true; value: unknown };

const ABSENT_NULL: Layer = { present: false, undefinedInTs: false };
const ABSENT_UNDEF: Layer = { present: false, undefinedInTs: true };
const set = (value: unknown): Layer => ({ present: true, value });

function layerToTs(layer: Layer): unknown {
  if (layer.present) return layer.value;
  return layer.undefinedInTs ? undefined : null;
}

function genSettingsScope(): void {
  const SYSTEM = { mode: 'none' };
  const run = (name: string, override: Layer, userDefault: Layer, systemDefault: unknown): void =>
    emit(
      'settingsScope',
      'resolvePortfolioSetting',
      name,
      { override, userDefault, systemDefault },
      () => resolvePortfolioSetting(layerToTs(override), layerToTs(userDefault), systemDefault),
    );

  run(
    'takes the portfolio override when it is set (highest precedence)',
    set({ mode: 'override' }),
    set({ mode: 'user' }),
    SYSTEM,
  );
  run('falls back to the user default when no override is set', ABSENT_NULL, set({ mode: 'user' }), SYSTEM);
  run('falls back to the system default when neither is set', ABSENT_NULL, ABSENT_NULL, SYSTEM);
  run('treats undefined like null at both layers', ABSENT_UNDEF, ABSENT_UNDEF, SYSTEM);
  run('undefined override with a user default', ABSENT_UNDEF, set({ mode: 'user' }), SYSTEM);
  run('honours a falsy-but-present override value (0)', set(0), set(5), 9);
  run("honours a falsy-but-present override value ('')", set(''), set('u'), 's');
  run('honours a falsy-but-present override value (false)', set(false), set(true), true);
  run('honours a falsy-but-present user default when the override is unset', ABSENT_NULL, set(0), 9);
}

// ===========================================================================
// serverTwrParity.fixture.json — the golden gate (plan §3.4 step 5)
// ===========================================================================

/**
 * The fixture was generated by the REAL server pipeline
 * (`apps/api/src/__tests__/vaultClientTwrParity.test.ts`) and is consumed on the
 * web side by `clientMoney.test.ts`. Both sides ultimately funnel the portfolio's
 * daily net-worth series and its external flows through
 * `timeWeightedReturn` — which is exactly the function this work package ports.
 *
 * This reshapes each fixture scenario into that function's (values, flows) inputs
 * and records the fixture's OWN published `twrPct` as the expected output, so the
 * Kotlin assertion is against the server-audited numbers rather than against a
 * re-run of the TS. Each reshaping is verified here against the vendored TS
 * engine before it is written out (see `check` below) — if a scenario ever stops
 * reproducing, generation fails loudly instead of emitting a weakened vector.
 *
 * Derivation of the (values, flows) for each scenario:
 *  - value(d) = holdings(d) + cash(d); holdings(d) = quantity * close(d), with
 *    the final day valued at `quoteToday`.
 *  - `sinceInceptionMax`: no cash at all; the buy is not cash-settled, so its
 *    gross cost (qty*price + fee) is the single external inflow on the buy day.
 *  - `splitDateCashBuy`: the buy IS cash-settled but its cash leg is dated 3 days
 *    later, so the engine emits split-date COMPENSATORS — +1005 on the buy day
 *    and -1005 on the settlement day — which is what keeps the double-counted
 *    stretch neutral. Cash falls 2000 -> 995 on the settlement day.
 *  - `internalCashFeeDrag`: the buy is NOT cash-settled (the fee movement carries
 *    no transactionId), so the gross cost is an external inflow; the `fee` cash
 *    movement is INTERNAL for TWR, so it never appears in `flows` — it only drags
 *    the value curve via cash 2000 -> 1900.
 */
function genServerTwrParity(): void {
  // Synthetic ISO dates; only ordering matters to timeWeightedReturn.
  const dates = (n: number): string[] =>
    Array.from({ length: n }, (_, i) => `2026-07-${String(19 + i).padStart(2, '0')}`);

  const emitParity = (
    name: string,
    values: { date: string; valueEur: number }[],
    flows: { date: string; flowEur: number }[],
    expected: number[],
  ): void => {
    // Gate: the vendored TS must reproduce the server's published vector exactly
    // before we hand it to the Kotlin port as a golden.
    const actual = timeWeightedReturn(values, flows).map((p) => p.pct);
    if (actual.length !== expected.length) {
      throw new Error(`serverTwrParity/${name}: length ${actual.length} != ${expected.length}`);
    }
    actual.forEach((v, i) => {
      if (!Object.is(v, expected[i])) {
        throw new Error(
          `serverTwrParity/${name}: point ${i} TS gave ${v}, fixture publishes ${expected[i]}`,
        );
      }
    });
    vectors['serverTwrParity']!.push({
      fn: 'timeWeightedReturn',
      case: name,
      input: { values, flows },
      output: expected.map((pct, i) => ({ date: values[i]!.date, pct })),
      throws: null,
    });
  };

  {
    // 8 points: the buy day (dayOffset -7) through today. No cash at all, so
    // value(d) is purely the holding, and the last day is marked to `quoteToday`.
    const sim = serverTwrParity.sinceInceptionMax;
    const d = dates(8);
    const values = [
      ...sim.closes.map((c, i) => ({ date: d[i]!, valueEur: sim.buy.quantity * c })),
      { date: d[7]!, valueEur: sim.buy.quantity * sim.quoteToday },
    ];
    emitParity(
      'sinceInceptionMax — no cash; the buy is the only external inflow',
      values,
      [{ date: d[0]!, flowEur: sim.buy.quantity * sim.buy.price + sim.buy.fee }],
      sim.twrPct,
    );
  }

  const split = serverTwrParity.splitDateCashBuy;
  {
    const d = dates(9);
    const values = [
      { date: d[0]!, valueEur: split.depositEur },
      ...split.closes.map((c, i) => ({
        date: d[i + 1]!,
        // the linked buy movement lands on dayOffset -5 == index 3
        valueEur: split.buy.quantity * c + (i + 1 >= 3 ? split.depositEur + split.linkedBuyMovement.amountEur : split.depositEur),
      })),
      {
        date: d[8]!,
        valueEur: split.buy.quantity * split.quoteToday + split.depositEur + split.linkedBuyMovement.amountEur,
      },
    ];
    const gross = split.buy.quantity * split.buy.price + split.buy.fee;
    emitParity(
      'splitDateCashBuy — deposit + split-date cash-buy compensators',
      values,
      [
        { date: d[0]!, flowEur: split.depositEur },
        { date: d[1]!, flowEur: gross },
        { date: d[3]!, flowEur: -gross },
      ],
      split.twrPct,
    );
  }

  const feeCase = serverTwrParity.internalCashFeeDrag;
  {
    const d = dates(9);
    const cashAfterFee = feeCase.depositEur - feeCase.cashFee.amountEur;
    const mk = (cashFrom3: number): { date: string; valueEur: number }[] => [
      { date: d[0]!, valueEur: feeCase.depositEur },
      ...feeCase.closes.map((c, i) => ({
        date: d[i + 1]!,
        valueEur: feeCase.buy.quantity * c + (i + 1 >= 3 ? cashFrom3 : feeCase.depositEur),
      })),
      { date: d[8]!, valueEur: feeCase.buy.quantity * feeCase.quoteToday + cashFrom3 },
    ];
    const gross = feeCase.buy.quantity * feeCase.buy.price + feeCase.buy.fee;
    const flows = [
      { date: d[0]!, flowEur: feeCase.depositEur },
      { date: d[1]!, flowEur: gross },
    ];
    emitParity(
      'internalCashFeeDrag — a `fee` is INTERNAL: it drags the curve, never divides out',
      mk(cashAfterFee),
      flows,
      feeCase.twrPct,
    );
    emitParity(
      'internalCashFeeDrag — the same ledger WITHOUT the fee ends higher',
      mk(feeCase.depositEur),
      flows,
      feeCase.twrPctWithoutTheFee,
    );
  }
}

// ===========================================================================
// Emit
// ===========================================================================

/** JSON cannot carry -0; catch it rather than let it silently become +0. */
function assertNoNegativeZero(node: unknown, path: string): void {
  if (typeof node === 'number') {
    if (Object.is(node, -0)) throw new Error(`negative zero at ${path} would not survive JSON`);
    if (!Number.isFinite(node)) throw new Error(`non-finite ${node} at ${path} would not survive JSON`);
    return;
  }
  if (Array.isArray(node)) {
    node.forEach((v, i) => assertNoNegativeZero(v, `${path}[${i}]`));
    return;
  }
  if (node !== null && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) assertNoNegativeZero(v, `${path}.${k}`);
  }
}

async function main(): Promise<void> {
  genReducePosition();
  await genDeriveHoldings();
  await genValueOverTime();
  await genCostBasisOverTime();
  genDailyCloseSeries();
  await genNetFlowsOverTime();
  genTimeWeightedReturn();
  genRebasePerformance();
  genSeriesStats();
  genFloorCents();
  genCashBalance();
  genApplyCashMovement();
  genProjectCashLedger();
  genCashBalanceOverTime();
  genSpendableAsOf();
  genTwrClassification();
  genLedgerTwrCompositions();
  genNetWorthSeries();
  genCashSources();
  genTransfersAndSetBalance();
  genCashBySourceOverTime();
  genSettingsScope();
  genServerTwrParity();

  mkdirSync(OUT_DIR, { recursive: true });

  // The raw server-generated golden, copied byte-identically so the Kotlin
  // golden gate reads the platform's own artifact rather than a reshaping of it.
  copyFileSync(
    join(HERE, 'vendor', 'fixtures', 'serverTwrParity.fixture.json'),
    join(OUT_DIR, 'serverTwrParity.fixture.json'),
  );

  const counts: Record<string, number> = {};
  const byFn: Record<string, Record<string, number>> = {};
  for (const [module, list] of Object.entries(vectors)) {
    assertNoNegativeZero(list, module);
    counts[module] = list.length;
    byFn[module] = {};
    for (const v of list) byFn[module]![v.fn] = (byFn[module]![v.fn] ?? 0) + 1;
    writeFileSync(
      join(OUT_DIR, `${module}.json`),
      `${JSON.stringify({ module, vectors: list }, null, 1)}\n`,
    );
  }

  const manifest = {
    generatedFrom: 'tools/domain-vectors/vendor (see PINNED_AT)',
    pinnedAt: 'cb530f7e30a2ce3502e708f4b05711d1d0bde685',
    note: 'Regenerate with: node --experimental-strip-types tools/domain-vectors/generate.ts',
    counts,
    byFunction: byFn,
    totalVectors: Object.values(counts).reduce((a, b) => a + b, 0),
    skipped: skips,
  };
  writeFileSync(join(OUT_DIR, 'MANIFEST.json'), `${JSON.stringify(manifest, null, 1)}\n`);

  console.log('vectors written to', OUT_DIR);
  for (const [m, c] of Object.entries(counts)) {
    console.log(`  ${m.padEnd(16)} ${String(c).padStart(4)}  ${JSON.stringify(byFn[m])}`);
  }
  console.log(`  ${'TOTAL'.padEnd(16)} ${String(manifest.totalVectors).padStart(4)}`);
  console.log(`  skipped (hand-ported): ${skips.length}`);
}

await main();
