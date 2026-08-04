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
