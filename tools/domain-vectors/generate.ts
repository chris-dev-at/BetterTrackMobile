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

import {
  AT_AS_CUSTOM_PARAMS,
  AT_KEST_RATE,
  atYearTargetEur,
  COST_BASIS_STRATEGIES,
  costBasisStrategyForCountry,
  customCarryForYears,
  customYearOutcome,
  DE_KAPEST_RATE,
  DE_SOLI_RATE,
  DE_SPARER_PAUSCHBETRAG_EUR,
  deCarryPots,
  dePotCategoryForAssetType,
  deYearOutcome,
  FI_CAPITAL_INCOME_HIGH_RATE,
  FI_CAPITAL_INCOME_RATE,
  FI_HIGH_RATE_THRESHOLD_EUR,
  fiYearTargetEur,
  floorCents as taxFloorCents,
  initialCustomCarry,
  manualTaxEur,
  QTY_EPSILON as TAX_QTY_EPSILON,
  QTY_STORAGE_QUANTUM,
  realizedSellsEur,
  settleAtYear,
  settleCustomYear,
  settleDeYear,
  settleFiYear,
  SUPPORTED_TAX_COUNTRIES,
  TAX_COUNTRY_AT,
  TAX_COUNTRY_DE,
  TAX_COUNTRY_FI,
  TAX_MODES,
  TAX_YEAR_TIME_ZONE,
  taxMovementForDelta,
  viennaYearOf,
  type AtYearSettlementInput,
  type CostBasisStrategy,
  type CustomTaxableEvent,
  type CustomTaxParams,
  type DeTaxableEvent,
  type DeYearAggregates,
  type NewAtEvent,
  type TaxableTransaction,
} from './vendor/domain/src/tax.ts';
import {
  DE_TAX_FIXTURES,
  type DeTaxFixtureScenario,
} from './vendor/domain/src/__tests__/deTaxFixtures.ts';

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
  tax: [],
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
// tax.ts  (S5/S6 deferred port — plan §3.2 third row)
// ===========================================================================
//
// Sources replayed here, case for case:
//   src/__tests__/tax.test.ts            (52 it() cases)
//   src/__tests__/deTaxEngine.test.ts    (18)
//   src/__tests__/customTax.test.ts      (22)
//   src/__tests__/deTaxFixtures.test.ts  (15 — fixture-data consistency; skipped
//                                         here and hand-ported over the emitted
//                                         deTaxFixtures.json)
//
// Unlike the cashLedger section, no bespoke JSON reshaping is needed: every tax
// input and every tax result is already a plain JSON-safe object (no Map, no
// class instance, no observable member order), so the vitest inputs travel
// verbatim and the outputs are recorded as the engine returns them.

const taxEmit = (fn: string, name: string, input: unknown, run: () => unknown): void =>
  emit('tax', fn, name, input, run);

/** tax.test.ts `T()` */
function T(
  id: string,
  side: 'buy' | 'sell',
  quantity: number,
  priceEur: number,
  executedAt: string,
  feeEur = 0,
  assetId = 'asset-1',
): TaxableTransaction {
  return { id, assetId, side, quantity, priceEur, feeEur, executedAt };
}

/** deTaxEngine.test.ts `tx()` */
function dtx(
  id: string,
  side: 'buy' | 'sell',
  quantity: number,
  priceEur: number,
  executedAt: string,
  extra: Partial<TaxableTransaction> = {},
): TaxableTransaction {
  return {
    id,
    assetId: extra.assetId ?? 'asset-1',
    side,
    quantity,
    priceEur,
    feeEur: 0,
    executedAt,
    ...extra,
  };
}

/** customTax.test.ts `params()` */
const cparams = (overrides: Partial<CustomTaxParams> = {}): CustomTaxParams => ({
  ...AT_AS_CUSTOM_PARAMS,
  ...overrides,
});

const sells = (
  name: string,
  transactions: TaxableTransaction[],
  strategy?: CostBasisStrategy,
): void =>
  taxEmit(
    'realizedSellsEur',
    name,
    strategy === undefined ? { transactions } : { transactions, strategy },
    () => (strategy === undefined ? realizedSellsEur(transactions) : realizedSellsEur(transactions, strategy)),
  );

const settleAt = (name: string, input: AtYearSettlementInput): void =>
  taxEmit('settleAtYear', name, input, () => settleAtYear(input));

const settleFi = (name: string, input: AtYearSettlementInput): void =>
  taxEmit('settleFiYear', name, input, () => settleFiYear(input));

const settleDe = (name: string, input: Parameters<typeof settleDeYear>[0]): void =>
  taxEmit('settleDeYear', name, input, () => settleDeYear(input));

const settleCustom = (name: string, input: Parameters<typeof settleCustomYear>[0]): void =>
  taxEmit('settleCustomYear', name, input, () => settleCustomYear(input));

// --- constants -------------------------------------------------------------

function genTaxConstants(): void {
  taxEmit('TAX_CONSTANTS', 'the module constants, values and declaration order', {}, () => ({
    TAX_MODES: [...TAX_MODES],
    TAX_COUNTRY_AT,
    TAX_COUNTRY_DE,
    TAX_COUNTRY_FI,
    SUPPORTED_TAX_COUNTRIES: [...SUPPORTED_TAX_COUNTRIES],
    COST_BASIS_STRATEGIES: [...COST_BASIS_STRATEGIES],
    AT_KEST_RATE,
    DE_KAPEST_RATE,
    DE_SOLI_RATE,
    DE_SPARER_PAUSCHBETRAG_EUR,
    FI_CAPITAL_INCOME_RATE,
    FI_CAPITAL_INCOME_HIGH_RATE,
    FI_HIGH_RATE_THRESHOLD_EUR,
    TAX_YEAR_TIME_ZONE,
    QTY_EPSILON: TAX_QTY_EPSILON,
    QTY_STORAGE_QUANTUM,
    AT_AS_CUSTOM_PARAMS: { ...AT_AS_CUSTOM_PARAMS },
  }));

  taxEmit('initialCustomCarry', 'the empty carry', {}, () => initialCustomCarry());

  const country = (name: string, value: string | null | undefined): void =>
    taxEmit(
      'costBasisStrategyForCountry',
      name,
      value === undefined ? {} : { country: value },
      () => costBasisStrategyForCountry(value),
    );
  country('FI mandates FIFO', TAX_COUNTRY_FI);
  country('DE mandates FIFO', TAX_COUNTRY_DE);
  country('AT keeps the moving average', TAX_COUNTRY_AT);
  country('null keeps the moving average', null);
  country('undefined keeps the moving average', undefined);
  country('an unknown country keeps the moving average', 'US');
}

// --- floorCents (tax.ts's OWN copy — plan §3.3 rule 3) ---------------------

function genTaxFloorCents(): void {
  const run = (name: string, amountEur: number): void =>
    taxEmit('floorCents', name, { amountEur }, () => taxFloorCents(amountEur));

  // The 12 values of tax.test.ts's cashLedger-parity case, each as its own vector.
  run('parity case 0', 0);
  run('parity case 0.005', 0.005);
  run('parity case -0.005', -0.005);
  run('parity case 1.005', 1.005);
  run('parity case -1.005', -1.005);
  run('parity case 2.675', 2.675);
  run('parity case 100.004999', 100.004999);
  run('parity case 100.006', 100.006);
  run('parity case 8.61', 8.61);
  run('parity case 0.1 + 0.2', 0.1 + 0.2);
  run('parity case 123.456', 123.456);
  run('parity case -76.545', -76.545);

  // "floors down (never rounds up) despite float representation".
  run('floors down: 1.005 -> 1.00', 1.005);
  run('floors down: -1.005 -> -1.00', -1.005);
  run('floors down: 100.006 -> 100.00', 100.006);
  run('floors down: 0.1 + 0.2 -> 0.30', 0.1 + 0.2);

  // Plan §3.3 rule 3 demands every quantizer be probed on negatives and exact
  // halves. tax.ts has NO Math.round/trunc/toFixed at all — floorCents' single
  // Math.floor is the whole rounding surface — so these pin its edges directly.
  run('exact half cent, positive (0.005)', 0.005);
  run('exact half cent, negative (-0.005)', -0.005);
  run('exact half cent above a cent (0.015)', 0.015);
  run('exact half cent above a cent, negative (-0.015)', -0.015);
  run('exact half cent (2.005)', 2.005);
  run('exact half cent, negative (-2.005)', -2.005);
  run('exact half (0.045)', 0.045);
  run('exact half, negative (-0.045)', -0.045);
  run('classic float trap (2.675)', 2.675);
  run('classic float trap, negative (-2.675)', -2.675);
  run('negative that floors away entirely (-0.004)', -0.004);
  run('negative sub-cent residue (-100.006)', -100.006);
  run('the KESt of an awkward pool (0.275 * 33.33)', 0.275 * 33.33);
  run('the Soli of an awkward KapESt (0.055 * 336.1)', 0.055 * 336.1);
  run('a large magnitude (1234567.891)', 1234567.891);
  run('a large negative magnitude (-1234567.891)', -1234567.891);

  skip(
    'floorCents',
    'matches cashLedger.floorCents on the boundary cases exactly',
    "cross-module identity (tax.floorCents === cashLedger.floorCents on 12 values): a vector records ONE function's output, it cannot assert two ports agree — hand-ported in TaxHandPortedTest (\"tax floorCents matches the cashLedger floorCents on every boundary case\")",
  );
  skip(
    'floorCents',
    'rejects non-finite amounts',
    'inputs are Number.NaN / Infinity, which JSON cannot represent — hand-ported in TaxHandPortedTest ("floorCents rejects non-finite amounts")',
  );
  skip(
    'floorCents',
    'returns POSITIVE zero for a floored-away negative',
    'signed-zero identity: JSON carries no -0 (the generator refuses to emit one) — hand-ported in TaxHandPortedTest ("floorCents never returns negative zero")',
  );
}

// --- viennaYearOf ----------------------------------------------------------

function genViennaYearOf(): void {
  const run = (name: string, isoTimestamp: string): void =>
    taxEmit('viennaYearOf', name, { isoTimestamp }, () => viennaYearOf(isoTimestamp));

  // 23:30 UTC on Dec 31 is 00:30 Jan 1 in Vienna (CET, UTC+1).
  run('buckets 2025-12-31T23:30Z into the NEW Vienna year', '2025-12-31T23:30:00.000Z');
  run('buckets 2025-12-31T22:59:59Z into the old Vienna year', '2025-12-31T22:59:59.000Z');
  run('mid-year is unambiguous', '2026-07-15T12:00:00.000Z');
  run('summer time (CEST, UTC+2) at the boundary', '2026-06-30T22:30:00.000Z');
  run('a non-UTC offset is honoured', '2026-01-01T00:30:00+01:00');
  run('fails loud on unparseable timestamps', 'not-a-date');
}

// --- realizedSellsEur ------------------------------------------------------

function genRealizedSellsEur(): void {
  sells('realizes a simple round trip against the average cost', [
    T('b1', 'buy', 10, 100, '2026-01-01T10:00:00Z'),
    T('s1', 'sell', 10, 110, '2026-02-01T10:00:00Z'),
  ]);

  sells('capitalises buy fees into the basis and deducts sell fees from the gain', [
    T('b1', 'buy', 10, 100, '2026-01-01T10:00:00Z', 10),
    T('s1', 'sell', 5, 110, '2026-02-01T10:00:00Z', 5),
  ]);

  sells('re-averages on buys and leaves the average unchanged across sells', [
    T('b1', 'buy', 1, 100, '2026-01-01T10:00:00Z'),
    T('b2', 'buy', 1, 200, '2026-01-02T10:00:00Z'),
    T('s1', 'sell', 1, 180, '2026-01-03T10:00:00Z'),
    T('s2', 'sell', 1, 120, '2026-01-04T10:00:00Z'),
  ]);

  sells('replays chronologically regardless of input order', [
    T('s1', 'sell', 1, 180, '2026-01-03T10:00:00Z'),
    T('b2', 'buy', 1, 200, '2026-01-02T10:00:00Z'),
    T('b1', 'buy', 1, 100, '2026-01-01T10:00:00Z'),
  ]);
  sells("mixed sub-second precision sorts as time, not as strings ('.' < 'Z')", [
    T('b1', 'buy', 1, 100, '2026-01-01T10:00:00Z'),
    T('s1', 'sell', 1, 150, '2026-01-01T10:00:00.500Z'),
  ]);

  sells('handles fractional quantities and closes positions to exactly zero', [
    T('b1', 'buy', 0.1, 10, '2026-01-01T10:00:00Z'),
    T('b2', 'buy', 0.2, 10, '2026-01-02T10:00:00Z'),
    T('s1', 'sell', 0.3, 20, '2026-01-03T10:00:00Z'),
    T('b3', 'buy', 1, 50, '2026-02-01T10:00:00Z'),
    T('s2', 'sell', 1, 60, '2026-03-01T10:00:00Z'),
  ]);

  sells('tracks assets independently', [
    T('b1', 'buy', 1, 100, '2026-01-01T10:00:00Z', 0, 'A'),
    T('b2', 'buy', 1, 500, '2026-01-01T11:00:00Z', 0, 'B'),
    T('s1', 'sell', 1, 110, '2026-01-02T10:00:00Z', 0, 'A'),
    T('s2', 'sell', 1, 400, '2026-01-02T11:00:00Z', 0, 'B'),
  ]);

  sells('rejects an oversell — an inconsistent log must never price a basis', [
    T('b1', 'buy', 1, 100, '2026-01-01T10:00:00Z'),
    T('s1', 'sell', 2, 100, '2026-01-02T10:00:00Z'),
  ]);

  sells('rejects a zero quantity', [T('b1', 'buy', 0, 100, '2026-01-01T10:00:00Z')]);
  sells('rejects a negative price', [T('b1', 'buy', 1, -1, '2026-01-01T10:00:00Z')]);
  sells('rejects an unparseable executedAt', [T('b1', 'buy', 1, 100, 'garbage')]);
  sells('rejects a negative fee', [T('b1', 'buy', 1, 100, '2026-01-01T10:00:00Z', -1)]);
  sells('rejects an unknown transaction side', [
    { ...T('x1', 'buy', 1, 100, '2026-01-01T10:00:00Z'), side: 'short' as 'buy' },
  ]);

  // --- uncovered sell — allowUncovered (issue #369) ---
  sells('uncovered: basises the uncovered shares at the sale price -> 0 gain', [
    { ...T('s1', 'sell', 10, 100, '2026-02-01T10:00:00Z'), allowUncovered: true },
  ]);
  sells('uncovered: splits a partial-cover sell (covered at avg, uncovered at sale price)', [
    T('b1', 'buy', 2, 40, '2026-01-01T10:00:00Z'),
    { ...T('s1', 'sell', 10, 100, '2026-02-01T10:00:00Z'), allowUncovered: true },
  ]);
  sells('uncovered: uses a supplied EUR entry price for the uncovered portion', [
    T('b1', 'buy', 2, 40, '2026-01-01T10:00:00Z'),
    {
      ...T('s1', 'sell', 10, 100, '2026-02-01T10:00:00Z'),
      allowUncovered: true,
      uncoveredEntryPriceEur: 60,
    },
  ]);
  sells('uncovered: an explicit null entry price falls back to the sale price', [
    T('b1', 'buy', 2, 40, '2026-01-01T10:00:00Z'),
    {
      ...T('s1', 'sell', 10, 100, '2026-02-01T10:00:00Z'),
      allowUncovered: true,
      uncoveredEntryPriceEur: null,
    },
  ]);
  sells('uncovered: rejects a negative supplied entry price', [
    T('b1', 'buy', 2, 40, '2026-01-01T10:00:00Z'),
    {
      ...T('s1', 'sell', 10, 100, '2026-02-01T10:00:00Z'),
      allowUncovered: true,
      uncoveredEntryPriceEur: -1,
    },
  ]);
  sells('uncovered: marks a covered sell with uncoveredQuantity 0', [
    T('b1', 'buy', 10, 100, '2026-01-01T10:00:00Z'),
    T('s1', 'sell', 4, 110, '2026-02-01T10:00:00Z'),
  ]);
  sells('uncovered: closes at 0 and lets a later buy rebuild a clean average (no shorts)', [
    { ...T('s1', 'sell', 5, 100, '2026-01-01T10:00:00Z'), allowUncovered: true },
    T('b1', 'buy', 2, 50, '2026-02-01T10:00:00Z'),
    T('s2', 'sell', 2, 70, '2026-03-01T10:00:00Z'),
  ]);
  sells('uncovered: still rejects an oversell when the flag is absent', [
    T('b1', 'buy', 1, 100, '2026-01-01T10:00:00Z'),
    T('s1', 'sell', 2, 100, '2026-01-02T10:00:00Z'),
  ]);

  // --- storage-quantum shortfall waiver (#917) ---
  const quantumPair = (sellQuantity: number): TaxableTransaction[] => [
    T('b1', 'buy', 1.0, 100, '2026-01-01T10:00:00Z'),
    T('s1', 'sell', sellQuantity, 110, '2026-01-02T10:00:00Z'),
  ];
  sells('#917: waives a same-batch one-quantum shortfall', quantumPair(1.00000001));
  sells('#917: scales the envelope per contributing row — multi-buy drift (F1 shape)', [
    T('b1', 'buy', 0.1, 50, '2026-01-01T10:00:00Z'),
    T('b2', 'buy', 0.1, 50, '2026-01-02T10:00:00Z'),
    T('b3', 'buy', 0.1, 50, '2026-01-03T10:00:00Z'),
    T('b4', 'buy', 0.1, 50, '2026-01-04T10:00:00Z'),
    T('s1', 'sell', 0.40000002, 60, '2026-01-05T10:00:00Z'),
  ]);
  sells('#917: waives identically under the FIFO strategy', quantumPair(1.00000001), 'fifo');
  sells('#917: closes the position so a later buy rebuilds a clean average', [
    ...quantumPair(1.00000001),
    T('b2', 'buy', 1, 50, '2026-02-01T10:00:00Z'),
    T('s2', 'sell', 1, 70, '2026-03-01T10:00:00Z'),
  ]);
  sells('#917: fails closed beyond the per-row envelope (moving-average)', quantumPair(1.00000003));
  sells('#917: fails closed beyond the per-row envelope (fifo)', quantumPair(1.00000003), 'fifo');
  sells('#917: resets the envelope when a position closes exactly', [
    T('b1', 'buy', 1, 100, '2026-01-01T10:00:00Z'),
    T('s1', 'sell', 1, 110, '2026-01-02T10:00:00Z'),
    T('b2', 'buy', 1, 100, '2026-02-01T10:00:00Z'),
    T('s2', 'sell', 1.00000003, 110, '2026-02-02T10:00:00Z'),
  ]);
  sells('#917: a real oversell still throws regardless of the row count', [
    ...Array.from({ length: 100 }, (_, i) => T(`b${i}`, 'buy', 1, 100, '2026-01-01T10:00:00Z')),
    T('s1', 'sell', 101, 110, '2026-01-02T10:00:00Z'),
  ]);
}

// --- the AT engine ---------------------------------------------------------

function genAtEngine(): void {
  const target = (name: string, poolEur: number): void =>
    taxEmit('atYearTargetEur', name, { poolEur }, () => atYearTargetEur(poolEur));

  target('the flat rate on the pool, cent-quantized (450)', 450);
  target('the flat rate on the pool, cent-quantized (350)', 350);
  target('clamps a net-loss year to exactly zero (-100)', -100);
  target('clamps a net-loss year to exactly zero (0)', 0);
  target('floors the tax due to whole cents (0.02 -> 0.00)', 0.02);
  target('floors the tax due to whole cents (0.01 -> 0.00)', 0.01);
  target('floors the tax due to whole cents (0.5 -> 0.13)', 0.5);
  target('an awkward pool floors, never rounds up (33.33)', 33.33);

  settleAt('owner example, step 1: +450 gain from an empty year', {
    existingGainsEur: [],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [{ kind: 'sell_gain', amountEur: 450 }],
  });
  settleAt('owner example, step 2: -100 loss refunds down to 27.5 % x 350', {
    existingGainsEur: [450],
    existingDividendsEur: [],
    heldEur: 123.75,
    newEvents: [{ kind: 'sell_gain', amountEur: -100 }],
  });
  settleAt('loss first: nothing to refund', {
    existingGainsEur: [],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [{ kind: 'sell_gain', amountEur: -100 }],
  });
  settleAt('loss first: later gains taxed on the net only', {
    existingGainsEur: [-100],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [{ kind: 'sell_gain', amountEur: 450 }],
  });
  settleAt('a refund never exceeds what the year holds', {
    existingGainsEur: [100],
    existingDividendsEur: [],
    heldEur: 27.5,
    newEvents: [{ kind: 'sell_gain', amountEur: -500 }],
  });
  settleAt('taxes dividends at the flat rate inside the same pool', {
    existingGainsEur: [],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [{ kind: 'dividend', amountEur: 100 }],
  });
  settleAt('a prior same-year loss offsets dividend tax too (one pool)', {
    existingGainsEur: [-100],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [{ kind: 'dividend', amountEur: 60 }],
  });
  settleAt('attributes per-event marginal deltas within one batch', {
    existingGainsEur: [],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [
      { kind: 'sell_gain', amountEur: 450 },
      { kind: 'sell_gain', amountEur: -100 },
    ],
  });
  settleAt('posts a correction when re-shaped history no longer matches the held tax', {
    existingGainsEur: [300],
    existingDividendsEur: [],
    heldEur: 123.75,
    newEvents: [],
  });
  settleAt('lands on exact cents even for awkward pools', {
    existingGainsEur: [],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [{ kind: 'sell_gain', amountEur: 33.33 }],
  });
  settleAt('existing dividends join the pool in list order', {
    existingGainsEur: [10.11],
    existingDividendsEur: [20.22, 30.33],
    heldEur: 0,
    newEvents: [],
  });
  settleAt('rejects a zero-amount dividend event', {
    existingGainsEur: [],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [{ kind: 'dividend', amountEur: 0 }],
  });
  settleAt('rejects a negative-amount dividend event', {
    existingGainsEur: [],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [{ kind: 'dividend', amountEur: -10 }],
  });
  settleAt('rejects an unknown event kind', {
    existingGainsEur: [],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [{ kind: 'bogus' as 'dividend', amountEur: 1 }],
  });
  settleAt('rejects a non-positive existing dividend', {
    existingGainsEur: [],
    existingDividendsEur: [0],
    heldEur: 0,
    newEvents: [],
  });

  skip(
    'settleAtYear',
    'rejects a NaN event amount / an infinite heldEur',
    'inputs are Number.NaN / Infinity, which JSON cannot represent — hand-ported in TaxHandPortedTest ("settleAtYear rejects non-finite input")',
  );
  skip(
    'atYearTargetEur',
    'rejects a non-finite pool',
    'input is Number.NaN, which JSON cannot represent — hand-ported in TaxHandPortedTest ("year targets reject a non-finite pool")',
  );

  const mv = (name: string, deltaEur: number): void =>
    taxEmit('taxMovementForDelta', name, { deltaEur }, () => taxMovementForDelta(deltaEur));
  mv('maps a positive delta to a withholding (negative amount)', 123.75);
  mv('maps a negative delta to a refund (positive amount)', -27.5);
  mv('posts nothing for a zero delta', 0);
  mv('a one-cent withholding', 0.01);
  mv('a one-cent refund', -0.01);
  skip(
    'taxMovementForDelta',
    'rejects a non-finite delta',
    'input is Number.NaN, which JSON cannot represent — hand-ported in TaxHandPortedTest ("taxMovementForDelta rejects a non-finite delta")',
  );

  const manual = (
    name: string,
    input: { taxAmountEur?: number | null; taxRatePct?: number | null; baseEur: number },
  ): void => taxEmit('manualTaxEur', name, input, () => manualTaxEur(input));
  manual('records the entered amount as-is', { taxAmountEur: 12.34, baseEur: 999 });
  manual('floors the entered amount (12.345 -> 12.34)', { taxAmountEur: 12.345, baseEur: 999 });
  manual('applies a rate to a positive base', { taxRatePct: 27.5, baseEur: 100 });
  manual('a loss base records EUR 0.00', { taxRatePct: 27.5, baseEur: -100 });
  manual('returns null when nothing was entered', { baseEur: 100 });
  manual('explicit nulls also mean "nothing entered"', {
    taxAmountEur: null,
    taxRatePct: null,
    baseEur: 100,
  });
  manual('rejects both an amount and a rate', { taxAmountEur: 1, taxRatePct: 1, baseEur: 100 });
  manual('rejects a negative amount', { taxAmountEur: -1, baseEur: 100 });
  manual('rejects a rate above 100', { taxRatePct: 101, baseEur: 100 });
  manual('rejects a negative rate', { taxRatePct: -1, baseEur: 100 });
  manual('a zero rate records EUR 0.00', { taxRatePct: 0, baseEur: 100 });
  manual('a 100 % rate is admissible', { taxRatePct: 100, baseEur: 100 });
  manual('floors the rate result (33.33 % of 100)', { taxRatePct: 33.33, baseEur: 100 });
  skip(
    'manualTaxEur',
    'rejects a non-finite base',
    'input is Number.NaN, which JSON cannot represent — hand-ported in TaxHandPortedTest ("manualTaxEur rejects a non-finite base")',
  );
}

// --- the FI engine (#635) --------------------------------------------------

function genFiEngine(): void {
  const target = (name: string, poolEur: number): void =>
    taxEmit('fiYearTargetEur', name, { poolEur }, () => fiYearTargetEur(poolEur));

  target('30 % to EUR 30,000, 34 % above (40,000)', 40_000);
  target('exactly at the threshold: base rate only (30,000)', 30_000);
  target('below the threshold: flat 30 % (1,000)', 1_000);
  target('clamps a net-loss year to exactly zero (-500)', -500);
  target('clamps a net-loss year to exactly zero (0)', 0);
  target('floors to whole cents (0.03 -> 0.00)', 0.03);
  target('floors to whole cents (0.5 -> 0.15)', 0.5);
  target('one cent above the threshold', 30_000.01);

  settleFi('a marginal gain crossing the threshold is taxed at 34 % on the excess', {
    existingGainsEur: [25_000],
    existingDividendsEur: [],
    heldEur: 7_500,
    newEvents: [{ kind: 'sell_gain', amountEur: 10_000 }],
  });
  settleFi('a same-year loss refunds down to the shrunken progressive target', {
    existingGainsEur: [35_000],
    existingDividendsEur: [],
    heldEur: 10_700,
    newEvents: [{ kind: 'sell_gain', amountEur: -5_000 }],
  });
  settleFi('a loss-first year parks at EUR 0.00 and later gains tax only the net', {
    existingGainsEur: [],
    existingDividendsEur: [],
    heldEur: 0,
    newEvents: [
      { kind: 'sell_gain', amountEur: -1_000 },
      { kind: 'sell_gain', amountEur: 1_500 },
      { kind: 'dividend', amountEur: 500 },
    ],
  });
  settleFi('reconciles reshaped history like the AT settlement (signed correction)', {
    existingGainsEur: [1_000],
    existingDividendsEur: [],
    heldEur: 500,
    newEvents: [],
  });
}

// --- the DE engine (#576 / #580) -------------------------------------------

/** deTaxEngine.test.ts `taxablesOf()` */
function taxablesOf(scenario: DeTaxFixtureScenario): TaxableTransaction[] {
  return scenario.transactions.map((t) => ({
    id: t.id,
    assetId: t.assetId,
    side: t.side,
    quantity: t.quantity,
    priceEur: t.priceEur,
    feeEur: t.feeEur,
    executedAt: t.executedAt,
  }));
}

interface DeYearEvent {
  id: string;
  ms: number;
  year: number;
  event: DeTaxableEvent;
}

/** deTaxEngine.test.ts `engineEventsOf()` */
function engineEventsOf(scenario: DeTaxFixtureScenario): DeYearEvent[] {
  const realizations = new Map(
    realizedSellsEur(taxablesOf(scenario), 'fifo').map((r) => [r.id, r]),
  );
  const scenarioSells: DeYearEvent[] = scenario.transactions
    .filter((t) => t.side === 'sell')
    .map((t) => {
      const realization = realizations.get(t.id);
      if (!realization) throw new Error(`No realization for sell ${t.id}`);
      return {
        id: t.id,
        ms: Date.parse(t.executedAt),
        year: viennaYearOf(t.executedAt),
        event: {
          kind: 'sell_gain' as const,
          category: t.category,
          amountEur: realization.realizedPnlEur,
        },
      };
    });
  const dividends: DeYearEvent[] = scenario.dividends.map((d) => ({
    id: d.id,
    ms: Date.parse(d.executedAt),
    year: viennaYearOf(d.executedAt),
    event: { kind: 'dividend' as const, amountEur: d.grossEur },
  }));
  return [...scenarioSells, ...dividends].sort((a, b) => a.ms - b.ms);
}

function genDeFixtureEngine(): void {
  for (const scenario of DE_TAX_FIXTURES) {
    const txs = taxablesOf(scenario);
    sells(`${scenario.id}: FIFO realizations, per lot consumption`, txs, 'fifo');
    sells(`${scenario.id}: the moving-average strategy on the same log`, txs, 'moving-average');

    const events = engineEventsOf(scenario);
    scenario.expectedYears.forEach((year, i) => {
      // The pot chain entering this year: every earlier listed year's events.
      const priorYearEvents = scenario.expectedYears
        .slice(0, i)
        .map((prior) => events.filter((e) => e.year === prior.year).map((e) => e.event));
      taxEmit(
        'deCarryPots',
        `${scenario.id}/${year.year}: pots carried into the year`,
        { priorYearEvents },
        () => deCarryPots(priorYearEvents),
      );

      const yearEvents = events.filter((e) => e.year === year.year);

      // Step by step: each event settles alone against the events before it.
      const existing: DeTaxableEvent[] = [];
      let held = 0;
      year.steps.forEach((step, j) => {
        const input = {
          aktienPotInEur: year.aktienPotInEur,
          sonstigePotInEur: year.sonstigePotInEur,
          // Snapshot: `existing` keeps growing and the vector holds this object.
          existingEvents: [...existing],
          heldEur: held,
          newEvents: [yearEvents[j]!.event],
        };
        settleDe(`${scenario.id}/${year.year}/${step.eventId}: settlement step`, input);
        existing.push(yearEvents[j]!.event);
        held = settleDeYear(input).heldAfterEur;
      });

      // The same year settled as ONE batch — same deltas, same final target.
      settleDe(`${scenario.id}/${year.year}: whole-year batch`, {
        aktienPotInEur: year.aktienPotInEur,
        sonstigePotInEur: year.sonstigePotInEur,
        existingEvents: [],
        heldEur: 0,
        newEvents: yearEvents.map((e) => e.event),
      });

      // The year-end state (allowance, base, KapESt, Soli, pot-outs).
      settleDe(`${scenario.id}/${year.year}: year-end state`, {
        aktienPotInEur: year.aktienPotInEur,
        sonstigePotInEur: year.sonstigePotInEur,
        existingEvents: yearEvents.map((e) => e.event),
        heldEur: year.totalTaxEur,
        newEvents: [],
      });
    });
  }

  skip(
    'realizedSellsEur',
    'the moving-average strategy reproduces the stated divergent P/L — never the FIFO one',
    "the case's load-bearing assertion is an INEQUALITY between the engine output and a fixture literal (`expect(fifoPnl).not.toBe(maPnl)`); both engine outputs travel as vectors, the inequality against the fixtures is hand-ported in DeTaxFixturesHandPortedTest",
  );
}

function genDeUnitCases(): void {
  // --- FIFO strategy unit tests ---
  sells(
    'FIFO: pro-rates buy fees into the lot and consumes partial lots oldest-first',
    [
      dtx('b1', 'buy', 4, 10, '2024-01-05T12:00:00.000Z', { feeEur: 2 }),
      dtx('b2', 'buy', 6, 20, '2024-02-05T12:00:00.000Z', { feeEur: 3 }),
      dtx('s1', 'sell', 5, 30, '2024-06-05T12:00:00.000Z', { feeEur: 1 }),
      dtx('s2', 'sell', 5, 8, '2024-09-05T12:00:00.000Z'),
    ],
    'fifo',
  );
  sells(
    'FIFO: keeps per-asset lot queues independent',
    [
      dtx('a-buy', 'buy', 10, 100, '2024-01-05T12:00:00.000Z', { assetId: 'a' }),
      dtx('b-buy', 'buy', 10, 1, '2024-01-06T12:00:00.000Z', { assetId: 'b' }),
      dtx('a-sell', 'sell', 10, 150, '2024-05-05T12:00:00.000Z', { assetId: 'a' }),
    ],
    'fifo',
  );
  sells(
    'FIFO: throws on an unacknowledged oversell, exactly like the moving average',
    [
      dtx('b1', 'buy', 5, 10, '2024-01-05T12:00:00.000Z'),
      dtx('s1', 'sell', 6, 10, '2024-02-05T12:00:00.000Z'),
    ],
    'fifo',
  );
  sells(
    'FIFO: an acknowledged uncovered sell releases real lots, the rest takes the supplied basis',
    [
      dtx('b1', 'buy', 2, 100, '2024-01-05T12:00:00.000Z'),
      dtx('s1', 'sell', 5, 50, '2024-03-05T12:00:00.000Z', {
        allowUncovered: true,
        uncoveredEntryPriceEur: 30,
      }),
      dtx('b2', 'buy', 1, 10, '2024-05-05T12:00:00.000Z'),
      dtx('s2', 'sell', 1, 25, '2024-06-05T12:00:00.000Z'),
    ],
    'fifo',
  );
  sells(
    'FIFO: an uncovered sell without an entry price books 0 gain on the uncovered portion',
    [
      dtx('b1', 'buy', 1, 100, '2024-01-05T12:00:00.000Z'),
      dtx('s1', 'sell', 3, 60, '2024-03-05T12:00:00.000Z', { allowUncovered: true }),
    ],
    'fifo',
  );
  sells(
    'FIFO: clamps float dust when fractional sells close the position',
    [
      dtx('b1', 'buy', 0.3, 10, '2024-01-05T12:00:00.000Z'),
      dtx('s1', 'sell', 0.1, 12, '2024-02-05T12:00:00.000Z'),
      dtx('s2', 'sell', 0.2, 12, '2024-03-05T12:00:00.000Z'),
    ],
    'fifo',
  );
  sells(
    'FIFO: the position is closed afterwards — another sell must be an oversell',
    [
      dtx('b1', 'buy', 0.3, 10, '2024-01-05T12:00:00.000Z'),
      dtx('s1', 'sell', 0.1, 12, '2024-02-05T12:00:00.000Z'),
      dtx('s2', 'sell', 0.2, 12, '2024-03-05T12:00:00.000Z'),
      dtx('s3', 'sell', 0.1, 12, '2024-04-05T12:00:00.000Z'),
    ],
    'fifo',
  );

  const strategyLog = [
    dtx('b1', 'buy', 100, 100, '2024-01-10T12:00:00.000Z'),
    dtx('b2', 'buy', 100, 200, '2024-03-15T12:00:00.000Z'),
    dtx('s1', 'sell', 100, 180, '2024-06-20T12:00:00.000Z'),
    dtx('s2', 'sell', 50, 210, '2024-11-05T12:00:00.000Z'),
  ];
  sells('strategy default: omitting the argument is the pre-V5-P4 replay', strategyLog);
  sells('strategy default: the explicit moving-average replay', strategyLog, 'moving-average');
  sells('strategy default: the FIFO replay genuinely differs (#576 S2)', strategyLog, 'fifo');
  skip(
    'realizedSellsEur',
    'defaults to the moving average: omitting the strategy is the pre-V5-P4 replay',
    'the case asserts an EQUALITY between two calls (`realizedSellsEur(log)` deep-equals `realizedSellsEur(log, "moving-average")`); both travel as vectors, the equality between them is hand-ported in TaxHandPortedTest',
  );

  // --- Pot classification & guards ---
  const potOf = (name: string, assetType: string): void =>
    taxEmit(
      'dePotCategoryForAssetType',
      name,
      { assetType },
      () => dePotCategoryForAssetType(assetType),
    );
  potOf('classifies a stock as Aktien', 'stock');
  for (const type of ['etf', 'index', 'fx', 'commodity', 'crypto', 'custom']) {
    potOf(`classifies ${type} as Sonstige`, type);
  }

  const outcome = (name: string, agg: DeYearAggregates): void =>
    taxEmit('deYearOutcome', name, agg, () => deYearOutcome(agg));
  const baseAgg: DeYearAggregates = {
    aktienPotInEur: 0,
    sonstigePotInEur: 0,
    aktienSalePnlEur: 0,
    sonstigeSalePnlEur: 0,
    dividendsEur: 0,
  };
  outcome('an all-zero year', { ...baseAgg });
  outcome('rejects a negative Aktien pot in', { ...baseAgg, aktienPotInEur: -1 });
  outcome('rejects a negative dividends sum', { ...baseAgg, dividendsEur: -5 });
  outcome('the one-directional cross-offset consumes an Aktien gain', {
    ...baseAgg,
    aktienSalePnlEur: 2000,
    sonstigeSalePnlEur: -1500,
  });
  outcome('an Aktien loss NEVER offsets Sonstige income (the ring-fence)', {
    ...baseAgg,
    aktienSalePnlEur: -1500,
    dividendsEur: 2000,
  });
  outcome('the allowance is consumed before the rate applies', {
    ...baseAgg,
    aktienSalePnlEur: 1000,
  });
  outcome('the Soli floors, never rounds up (base 1344.42 x 4)', {
    ...baseAgg,
    aktienSalePnlEur: 1000 + 1344.42 * 4,
  });
  skip(
    'deYearOutcome',
    'rejects a NaN pot',
    'input is Number.NaN, which JSON cannot represent — hand-ported in TaxHandPortedTest ("deYearOutcome rejects non-finite aggregates")',
  );

  const deSettleGuard = (name: string, event: DeTaxableEvent): void =>
    settleDe(name, {
      aktienPotInEur: 0,
      sonstigePotInEur: 0,
      existingEvents: [],
      heldEur: 0,
      newEvents: [event],
    });
  deSettleGuard('rejects a zero-amount dividend', { kind: 'dividend', amountEur: 0 });
  deSettleGuard('rejects a negative dividend', { kind: 'dividend', amountEur: -10 });
  deSettleGuard('rejects an unknown pot category', {
    kind: 'sell_gain',
    category: 'weird' as never,
    amountEur: 10,
  });
  deSettleGuard('rejects an unknown DE event kind', {
    kind: 'nope',
    amountEur: 10,
  } as never as DeTaxableEvent);

  settleDe('reconciles held drift as a correction before new events (backdated re-shape)', {
    aktienPotInEur: 0,
    sonstigePotInEur: 0,
    existingEvents: [{ kind: 'sell_gain', category: 'aktien', amountEur: -400 }],
    heldEur: 50,
    newEvents: [],
  });

  const lossYear: DeTaxableEvent[] = [
    { kind: 'sell_gain', category: 'aktien', amountEur: -800 },
    { kind: 'sell_gain', category: 'sonstige', amountEur: -300 },
  ];
  const pots = (name: string, priorYearEvents: DeTaxableEvent[][]): void =>
    taxEmit('deCarryPots', name, { priorYearEvents }, () => deCarryPots(priorYearEvents));
  pots('no prior years: both pots empty', []);
  pots('a loss year fills both pots', [lossYear]);
  pots('an interleaved event-less year changes nothing (indefinite carry)', [lossYear, []]);
}

// --- the custom rule-built engine (#584) -----------------------------------

/** The eight AT-parity inputs of customTax.test.ts, in suite order. */
const AT_PARITY_INPUTS: ReadonlyArray<readonly [string, AtYearSettlementInput]> = [
  [
    'owner example: +450 gain',
    {
      existingGainsEur: [],
      existingDividendsEur: [],
      heldEur: 0,
      newEvents: [{ kind: 'sell_gain', amountEur: 450 }],
    },
  ],
  [
    'owner example: -100 loss after the gain',
    {
      existingGainsEur: [450],
      existingDividendsEur: [],
      heldEur: 123.75,
      newEvents: [{ kind: 'sell_gain', amountEur: -100 }],
    },
  ],
  [
    'loss first: nothing to refund',
    {
      existingGainsEur: [],
      existingDividendsEur: [],
      heldEur: 0,
      newEvents: [{ kind: 'sell_gain', amountEur: -100 }],
    },
  ],
  [
    'loss first: later gains taxed on the net only',
    {
      existingGainsEur: [-100],
      existingDividendsEur: [],
      heldEur: 0,
      newEvents: [{ kind: 'sell_gain', amountEur: 450 }],
    },
  ],
  [
    'a refund never exceeds what the year holds',
    {
      existingGainsEur: [100],
      existingDividendsEur: [],
      heldEur: 27.5,
      newEvents: [{ kind: 'sell_gain', amountEur: -500 }],
    },
  ],
  [
    'taxes dividends at the flat rate inside the same pool',
    {
      existingGainsEur: [],
      existingDividendsEur: [],
      heldEur: 0,
      newEvents: [{ kind: 'dividend', amountEur: 100 }],
    },
  ],
  [
    'a prior same-year loss offsets dividend tax too',
    {
      existingGainsEur: [-100],
      existingDividendsEur: [],
      heldEur: 0,
      newEvents: [{ kind: 'dividend', amountEur: 60 }],
    },
  ],
  [
    'attributes per-event marginal deltas within one batch',
    {
      existingGainsEur: [],
      existingDividendsEur: [],
      heldEur: 0,
      newEvents: [
        { kind: 'sell_gain', amountEur: 450 },
        { kind: 'sell_gain', amountEur: -100 },
      ],
    },
  ],
  [
    'posts a correction when re-shaped history no longer matches the held tax',
    {
      existingGainsEur: [300],
      existingDividendsEur: [],
      heldEur: 123.75,
      newEvents: [],
    },
  ],
  [
    'lands on exact cents even for awkward pools',
    {
      existingGainsEur: [],
      existingDividendsEur: [],
      heldEur: 0,
      newEvents: [{ kind: 'sell_gain', amountEur: 33.33 }],
    },
  ],
];

/** customTax.test.ts `expectAtParity()` — the custom side of one parity input. */
function atParityCustomInput(
  input: AtYearSettlementInput,
): Parameters<typeof settleCustomYear>[0] {
  return {
    params: AT_AS_CUSTOM_PARAMS,
    carry: initialCustomCarry(),
    existingEvents: [
      ...input.existingGainsEur.map(
        (amountEur): CustomTaxableEvent => ({ kind: 'sell_gain', amountEur }),
      ),
      ...input.existingDividendsEur.map(
        (amountEur): CustomTaxableEvent => ({ kind: 'dividend', amountEur }),
      ),
    ],
    heldEur: input.heldEur,
    newEvents: input.newEvents as readonly CustomTaxableEvent[] as CustomTaxableEvent[],
  };
}

function genCustomEngine(): void {
  // Both sides of every AT-parity case travel as vectors; the equality BETWEEN
  // them is a relation, so it is hand-ported (see the skip below).
  for (const [name, input] of AT_PARITY_INPUTS) {
    settleAt(`AT parity (AT side): ${name}`, input);
    settleCustom(`AT parity (custom side): ${name}`, atParityCustomInput(input));
  }
  skip(
    'settleCustomYear',
    'custom-as-AT parity (the required expressibility test)',
    'the suite\'s point is an EQUALITY between two engines (settleCustomYear with AT_AS_CUSTOM_PARAMS === settleAtYear) over 10 inputs; both sides travel as vectors, the equality is hand-ported in TaxHandPortedTest ("AT_AS_CUSTOM_PARAMS reproduces settleAtYear output for output")',
  );

  const yearOutcome = (
    name: string,
    params: CustomTaxParams,
    carry: ReturnType<typeof initialCustomCarry>,
    events: CustomTaxableEvent[],
  ): void =>
    taxEmit(
      'customYearOutcome',
      name,
      { params, carry, events },
      () => customYearOutcome(params, carry, events),
    );

  const carryYears = (
    name: string,
    params: CustomTaxParams,
    priorYearEvents: CustomTaxableEvent[][],
  ): void =>
    taxEmit(
      'customCarryForYears',
      name,
      { params, priorYearEvents },
      () => customCarryForYears(params, priorYearEvents),
    );

  // hard Jan-1 reset with carry off
  yearOutcome('AT params: a net-loss year leaves a clean carry', AT_AS_CUSTOM_PARAMS, initialCustomCarry(), [
    { kind: 'sell_gain', amountEur: -400 },
  ]);
  settleCustom("AT params: year 2's gain is taxed in full — no cross-year offset", {
    params: AT_AS_CUSTOM_PARAMS,
    carry: initialCustomCarry(),
    existingEvents: [],
    heldEur: 0,
    newEvents: [{ kind: 'sell_gain', amountEur: 200 }],
  });

  // lossOffset off
  const offsetEvents: CustomTaxableEvent[] = [
    { kind: 'sell_gain', amountEur: 100 },
    { kind: 'sell_gain', amountEur: -80 },
    { kind: 'sell_gain', amountEur: 50 },
  ];
  settleCustom('lossOffset off: a loss neither refunds nor shrinks the pool', {
    params: cparams({ ratePct: 10, lossOffset: false }),
    carry: initialCustomCarry(),
    existingEvents: [],
    heldEur: 0,
    newEvents: offsetEvents,
  });
  settleCustom('lossOffset on: the same events land on 10 % x (100 - 80 + 50)', {
    params: cparams({ ratePct: 10 }),
    carry: initialCustomCarry(),
    existingEvents: [],
    heldEur: 0,
    newEvents: offsetEvents,
  });
  yearOutcome(
    'lossOffset off: a loss-only year accrues no carry even with carryForward on',
    cparams({ lossOffset: false, carryForward: true }),
    initialCustomCarry(),
    [{ kind: 'sell_gain', amountEur: -500 }],
  );

  // refund off
  settleCustom('refund off: a loss after a taxed gain posts no refund movement', {
    params: cparams({ refund: false }),
    carry: initialCustomCarry(),
    existingEvents: [],
    heldEur: 0,
    newEvents: [
      { kind: 'sell_gain', amountEur: 450 },
      { kind: 'sell_gain', amountEur: -100 },
    ],
  });
  settleCustom('refund off: later gains withhold again only past the ratchet', {
    params: cparams({ refund: false }),
    carry: initialCustomCarry(),
    existingEvents: [],
    heldEur: 0,
    newEvents: [
      { kind: 'sell_gain', amountEur: 450 },
      { kind: 'sell_gain', amountEur: -100 },
      { kind: 'sell_gain', amountEur: 200 },
    ],
  });
  settleCustom('refund off: a history-reshape correction stays signed', {
    params: cparams({ refund: false }),
    carry: initialCustomCarry(),
    existingEvents: [{ kind: 'sell_gain', amountEur: 300 }],
    heldEur: 123.75,
    newEvents: [],
  });
  settleCustom('refund off: after a reshape correction, new events ratchet from the corrected base', {
    params: cparams({ refund: false }),
    carry: initialCustomCarry(),
    existingEvents: [{ kind: 'sell_gain', amountEur: 300 }],
    heldEur: 123.75,
    newEvents: [
      { kind: 'sell_gain', amountEur: -100 },
      { kind: 'sell_gain', amountEur: 200 },
    ],
  });

  // yearReset off — one cumulative pool across years
  const resetOffCarry = cparams({ yearReset: false, carryForward: true });
  carryYears('yearReset off: a year-1 loss survives Jan 1', resetOffCarry, [
    [{ kind: 'sell_gain', amountEur: -100 }],
  ]);
  settleCustom('yearReset off: the year-1 loss offsets a year-2 gain through the carry', {
    params: resetOffCarry,
    carry: customCarryForYears(resetOffCarry, [[{ kind: 'sell_gain', amountEur: -100 }]]),
    existingEvents: [],
    heldEur: 0,
    newEvents: [{ kind: 'sell_gain', amountEur: 450 }],
  });

  const resetOff = cparams({ yearReset: false });
  carryYears('yearReset off: year 1 attributes its own tax to cumulativeHeld', resetOff, [
    [{ kind: 'sell_gain', amountEur: 400 }],
  ]);
  settleCustom("yearReset off: a later-year loss refunds prior years' tax", {
    params: resetOff,
    carry: customCarryForYears(resetOff, [[{ kind: 'sell_gain', amountEur: 400 }]]),
    existingEvents: [],
    heldEur: 0,
    newEvents: [{ kind: 'sell_gain', amountEur: -200 }],
  });

  const resetOffNoRefund = cparams({ yearReset: false, refund: false });
  carryYears('yearReset off + refund off: year 1', resetOffNoRefund, [
    [{ kind: 'sell_gain', amountEur: 400 }],
  ]);
  settleCustom('yearReset off + refund off: the cumulative regime still never refunds', {
    params: resetOffNoRefund,
    carry: customCarryForYears(resetOffNoRefund, [[{ kind: 'sell_gain', amountEur: 400 }]]),
    existingEvents: [],
    heldEur: 0,
    newEvents: [
      { kind: 'sell_gain', amountEur: -200 },
      { kind: 'sell_gain', amountEur: 300 },
    ],
  });

  // yearReset on + carryForward on — a loss pot survives the boundary
  const potCarry = cparams({ carryForward: true });
  carryYears('carryForward on: year 1 parks 300, year 2 consumes part of it', potCarry, [
    [{ kind: 'sell_gain', amountEur: -300 }],
    [{ kind: 'sell_gain', amountEur: 100 }],
  ]);
  settleCustom('carryForward on: the pot offsets before the rate applies', {
    params: potCarry,
    carry: customCarryForYears(potCarry, [
      [{ kind: 'sell_gain', amountEur: -300 }],
      [{ kind: 'sell_gain', amountEur: 100 }],
    ]),
    existingEvents: [],
    heldEur: 0,
    newEvents: [{ kind: 'dividend', amountEur: 500 }],
  });
  carryYears('carryForward on: an empty year passes the pot through unchanged', potCarry, [
    [{ kind: 'sell_gain', amountEur: -150 }],
    [],
  ]);

  // cost-basis seam
  const seamLog: TaxableTransaction[] = [
    {
      id: 'b1',
      assetId: 'A',
      side: 'buy',
      quantity: 1,
      priceEur: 100,
      feeEur: 0,
      executedAt: '2026-01-05T10:00:00Z',
    },
    {
      id: 'b2',
      assetId: 'A',
      side: 'buy',
      quantity: 1,
      priceEur: 200,
      feeEur: 0,
      executedAt: '2026-02-05T10:00:00Z',
    },
    {
      id: 's1',
      assetId: 'A',
      side: 'sell',
      quantity: 1,
      priceEur: 300,
      feeEur: 0,
      executedAt: '2026-03-05T10:00:00Z',
    },
  ];
  sells('cost-basis seam: FIFO consumes the oldest lot (gain 200)', seamLog, 'fifo');
  sells('cost-basis seam: moving average basis 150 (gain 150)', seamLog, 'moving-average');
  settleCustom('cost-basis seam: 10 % of the FIFO gain', {
    params: cparams({ ratePct: 10 }),
    carry: initialCustomCarry(),
    existingEvents: [],
    heldEur: 0,
    newEvents: [{ kind: 'sell_gain', amountEur: realizedSellsEur(seamLog, 'fifo')[0]!.realizedPnlEur }],
  });
  settleCustom('cost-basis seam: 10 % of the moving-average gain', {
    params: cparams({ ratePct: 10 }),
    carry: initialCustomCarry(),
    existingEvents: [],
    heldEur: 0,
    newEvents: [
      { kind: 'sell_gain', amountEur: realizedSellsEur(seamLog, 'moving-average')[0]!.realizedPnlEur },
    ],
  });

  // validation fails loud
  const guardBase = {
    carry: initialCustomCarry(),
    existingEvents: [] as CustomTaxableEvent[],
    heldEur: 0,
    newEvents: [] as CustomTaxableEvent[],
  };
  settleCustom('rejects a rate above 100', { ...guardBase, params: cparams({ ratePct: 101 }) });
  settleCustom('rejects a negative rate', { ...guardBase, params: cparams({ ratePct: -1 }) });
  settleCustom('rejects an unknown cost-basis strategy', {
    ...guardBase,
    params: cparams({ costBasis: 'lifo' as unknown as CostBasisStrategy }),
  });
  settleCustom('rejects a zero-amount dividend', {
    ...guardBase,
    params: cparams(),
    newEvents: [{ kind: 'dividend', amountEur: 0 }],
  });
  settleCustom('rejects an unknown custom event kind', {
    ...guardBase,
    params: cparams(),
    newEvents: [{ kind: 'bogus' as 'dividend', amountEur: 1 }],
  });
  settleCustom('rejects a negative carry pot', {
    ...guardBase,
    params: cparams(),
    carry: { potEur: -1, cumulativePoolEur: 0, cumulativeHeldEur: 0 },
  });
  skip(
    'settleCustomYear',
    'rejects a NaN rate / an infinite heldEur',
    'inputs are Number.NaN / Infinity, which JSON cannot represent — hand-ported in TaxHandPortedTest ("settleCustomYear rejects non-finite input")',
  );
}

// --- deTaxFixtures.test.ts: fixture-data consistency ------------------------

function skipDeFixtureShapeSuite(): void {
  const reason =
    'deTaxFixtures.test.ts asserts the hand-computed FIXTURE DATA is internally consistent (ids unique, no oversell in the log, proceeds = qty·price − fee, year aggregates reconcile with the per-event inputs, the §16 year-target formula, pot chaining, settlement steps summing to the year target) — it is not a call into the engine, so a {fn, case, input, output} vector cannot express it. The fixture data is emitted verbatim as deTaxFixtures.json and the whole suite is hand-ported in DeTaxFixturesHandPortedTest.';
  const cases: ReadonlyArray<readonly [string, string]> = [
    ['DE tax fixture catalog', 'contains the eight mandated scenarios with unique ids'],
    ['DE tax fixture catalog', 'documents every scenario with statute references'],
    ['per scenario', 'has valid, coherent inputs (dates parse; amounts sane; ids unique)'],
    ['per scenario', 'never sells more units than were bought before the sell'],
    ['per scenario', 'states exactly one expected realization per sell, with matching category'],
    ['per scenario', 'year aggregates reconcile with the per-event inputs'],
    ['per scenario', 'follows the researched year-target formula (pots, cross-offset, allowance, floors)'],
    ['per scenario', 'chains pots across consecutive listed years'],
    ['per scenario', 'settlement steps cover the year events chronologically and chain to the target'],
    ['acceptance pins (#576)', 'the FIFO scenario provably differs from moving average in total'],
    ['acceptance pins (#576)', 'the allowance scenario exhausts EUR 1,000 partially, then fully'],
    ['acceptance pins (#576)', 'the ring-fence scenario taxes the dividend while the Aktien loss carries out'],
    ['acceptance pins (#576)', 'a refund-of-already-withheld step exists and stays within what was withheld'],
    ['acceptance pins (#576)', 'pots carry across the year boundary while the allowance resets'],
    ['acceptance pins (#576)', 'Soli is 5.5 % of the (floored) KapESt, floored — never rounded up'],
  ];
  for (const [fn, name] of cases) skip(`deTaxFixtures/${fn}`, name, reason);
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
  genTaxConstants();
  genTaxFloorCents();
  genViennaYearOf();
  genRealizedSellsEur();
  genAtEngine();
  genFiEngine();
  genDeFixtureEngine();
  genDeUnitCases();
  genCustomEngine();
  skipDeFixtureShapeSuite();
  genServerTwrParity();

  mkdirSync(OUT_DIR, { recursive: true });

  // The DE fixture set (#576) verbatim: 8 hand-computed scenarios whose
  // internal consistency deTaxFixtures.test.ts asserts. Those 15 cases are
  // fixture-DATA assertions, not engine calls, so they are hand-ported in
  // Kotlin against this file rather than replayed as {fn, input, output}.
  writeFileSync(
    join(OUT_DIR, 'deTaxFixtures.json'),
    `${JSON.stringify({ source: 'packages/domain/src/__tests__/deTaxFixtures.ts', scenarios: DE_TAX_FIXTURES }, null, 1)}\n`,
  );

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
