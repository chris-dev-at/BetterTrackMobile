package at.bettertrack.app.vault

import java.util.zip.Inflater
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A **literal** Kotlin port of the raw-DEFLATE compressor of
 * [fflate 0.8.3](https://github.com/101arrowz/fflate) — `deflateSync(data)` with
 * default options (level 6, no dictionary, no zlib/gzip wrapper).
 *
 * ### Why a port instead of `java.util.zip.Deflater`
 *
 * The BetterTrack platform's web client encrypts the vault document as
 * `AES-256-GCM(rawDeflate(json))`, and the published `BTVAULT1` fixtures must be
 * reproducible **byte for byte** by this app. `Deflater(level, nowrap = true)`
 * cannot do that: zlib and fflate make different LZ77 match and Huffman-block
 * choices, so every level 0..9 crossed with every strategy produces different
 * bytes (e.g. on the `clientMoney` fixture zlib level 6 emits 979 bytes where
 * fflate emits 1010). DEFLATE is a *family* of valid encodings of the same data;
 * byte identity is only achievable by reproducing the exact encoder.
 *
 * ### How to read this file
 *
 * Every function carries a `// fflate index.ts:<lines>` marker pointing at the
 * pinned vendored source in `tools/domain-vectors/vendor/fflate/index.ts`. The
 * translation is deliberately line-for-line: names, operation order and even the
 * odd-looking expressions are preserved, because *any* deviation changes the
 * output bytes. Do not "clean up" or "optimise" anything here.
 *
 * ### JavaScript semantics emulated on purpose
 *
 *  * `Uint8Array`  -> [ByteArray], every read masked `and 0xFF`, every write `.toByte()`.
 *  * `Uint16Array` -> [IntArray], every write masked `and 0xFFFF`.
 *  * `Int32Array`  -> [IntArray] (Kotlin `Int` is already 32-bit wrapping).
 *  * `>>>` -> `ushr`, `>>` -> `shr`.
 *  * JS `/` is **float** division. Every `/` in the original was checked and is
 *    reproduced with the matching Kotlin form; the ones that are genuinely float
 *    (`Math.ceil(s / 7000)`, `Math.ceil(plvl / 3)`, the `Math.log(...) * 1.5`
 *    memory-level formula) go through [Double].
 *  * Reading past the end of a typed array yields `undefined`, which compares
 *    unequal to every number, coerces to `0` inside `^` / `<<` / `|`, and is
 *    falsy. Writing past the end is a silent no-op. Each site where that
 *    actually happens is marked `// JS OOB` below and emulated explicitly.
 *  * `Array.prototype.sort` is **stable** (V8 TimSort) and the comparators here
 *    tie on equal frequency, so the Kotlin side must use a stable sort as well —
 *    [MutableList.sortWith] is TimSort, an `IntArray` sort would not be.
 *  * `subarray` returns a **view** (aliases the backing store), `slice` returns a
 *    **copy**. Each occurrence is called out where it matters.
 *
 * Pure Kotlin/JVM: no Android imports, no third-party dependencies.
 */
object RawDeflate {

    // -----------------------------------------------------------------------
    // Public surface
    // -----------------------------------------------------------------------

    /**
     * Raw DEFLATE, byte-identical to fflate 0.8.3 `deflateSync(data)`.
     *
     * fflate index.ts:1455 `deflateSync` -> index.ts:1005 `dopt`.
     */
    fun deflate(data: ByteArray): ByteArray {
        // dopt: st = { l: 1 }; no dictionary.
        val st = DeflateState(l = 1)
        // dopt line 1017:
        //   opt.level == null ? 6 : opt.level
        //   opt.mem == null ? (st.l ? Math.ceil(Math.max(8, Math.min(13, Math.log(dat.length))) * 1.5) : 20) : …
        // `Math.log` is the NATURAL log and the whole expression is float maths.
        // `Math.log(0)` is -Infinity, which `Math.max(8, …)` clamps to 8 — Kotlin's
        // `ln(0.0)` is likewise -Infinity, so the empty input needs no special case.
        // (`kotlin.math.ln` is qualified so it can never be confused with this
        // object's own `ln`, the Huffman depth-assignment helper.)
        val plvl = ceil(max(8.0, min(13.0, kotlin.math.ln(data.size.toDouble()))) * 1.5).toInt()
        return dflt(data, 6, plvl, 0, 0, st)
    }

    /**
     * Raw INFLATE. Intentionally **not** a port: any conforming inflater reads any
     * valid DEFLATE stream, so only the compressor's bit choices had to be
     * reproduced. Delegating here also means the round-trip tests check the port
     * against an independent implementation rather than against itself.
     */
    fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(data)
            val out = java.io.ByteArrayOutputStream(max(64, data.size * 4))
            val buf = ByteArray(16384)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break
                } else {
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            inf.end()
        }
    }

    // -----------------------------------------------------------------------
    // Constant tables — fflate index.ts:16..121
    // -----------------------------------------------------------------------

    /** fixed length extra bits — index.ts:19 */
    private val fleb = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
        /* unused */ 0, 0, /* impossible */ 0,
    )

    /** fixed distance extra bits — index.ts:22 */
    private val fdeb = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12,
        13, 13, /* unused */ 0, 0,
    )

    /** code length index map — index.ts:25 */
    private val clim = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /** `{ b, r }` of [freb]: base values (u16) and the reverse index map (i32). */
    private class Freb(val b: IntArray, val r: IntArray)

    /**
     * get base, reverse index map from extra bits — fflate index.ts:28..41
     *
     * JS trap: the first iteration evaluates `eb[i - 1]` == `eb[-1]` == `undefined`,
     * and `1 << undefined` is `1 << 0` == 1. Reproduced with the explicit `if`.
     */
    private fun freb(eb: IntArray, startIn: Int): Freb {
        var start = startIn
        val b = IntArray(31) // u16
        for (i in 0 until 31) {
            start += 1 shl (if (i == 0) 0 else eb[i - 1]) // JS OOB: eb[-1] -> undefined -> 0
            b[i] = start and 0xFFFF
        }
        // numbers here are at max 18 bits
        val r = IntArray(b[30])
        for (i in 1 until 30) {
            for (j in b[i] until b[i + 1]) {
                r[j] = ((j - b[i]) shl 5) or i
            }
        }
        return Freb(b, r)
    }

    // index.ts:43..46
    private val fl: IntArray
    private val revfl: IntArray
    private val fd: IntArray
    private val revfd: IntArray

    /** map of value to reverse (assuming 16 bits) — index.ts:49..56 */
    private val rev = IntArray(32768)

    /** fixed length tree — index.ts:110 */
    private val flt = ByteArray(288)

    /** fixed distance tree — index.ts:116 */
    private val fdt = ByteArray(32)

    /** fixed length map — index.ts:119 (only the `r == 0` map is needed to compress) */
    private val flm: IntArray

    /** fixed distance map — index.ts:121 */
    private val fdm: IntArray

    /** empty — index.ts:612 */
    private val et = ByteArray(0)

    /** deflate options `(nice << 13) | chain` — index.ts:609 */
    private val deo = intArrayOf(
        65540, 131080, 131088, 131104, 262176, 1048704, 1048832, 2114560, 2117632,
    )

    init {
        val flr = freb(fleb, 2)
        fl = flr.b
        revfl = flr.r
        // we can ignore the fact that the other numbers are wrong; they never happen anyway
        fl[28] = 258
        revfl[258] = 28
        val fdr = freb(fdeb, 0)
        fd = fdr.b
        revfd = fdr.r

        for (i in 0 until 32768) {
            // reverse table algorithm from SO
            var x = ((i and 0xAAAA) shr 1) or ((i and 0x5555) shl 1)
            x = ((x and 0xCCCC) shr 2) or ((x and 0x3333) shl 2)
            x = ((x and 0xF0F0) shr 4) or ((x and 0x0F0F) shl 4)
            rev[i] = ((((x and 0xFF00) shr 8) or ((x and 0x00FF) shl 8)) shr 1) and 0xFFFF
        }

        for (i in 0 until 144) flt[i] = 8
        for (i in 144 until 256) flt[i] = 9
        for (i in 256 until 280) flt[i] = 7
        for (i in 280 until 288) flt[i] = 8
        for (i in 0 until 32) fdt[i] = 5

        flm = hMap(flt, flt.size, 9, 0)
        fdm = hMap(fdt, fdt.size, 5, 0)
    }

    // -----------------------------------------------------------------------
    // Huffman code construction
    // -----------------------------------------------------------------------

    /**
     * create huffman tree from u8 "map": index -> code length for code index
     * — fflate index.ts:61..107
     *
     * `mb` (max bits) must be at most 15. [cdLen] is the effective length of [cd]
     * (fflate passes `Uint8Array` views whose logical length can be shorter than
     * the backing array; here every caller passes the real length).
     */
    private fun hMap(cd: ByteArray, cdLen: Int, mb: Int, r: Int): IntArray {
        val s = cdLen
        // index
        var i = 0
        // u16 "map": index -> # of codes with bit length = index
        val l = IntArray(mb)
        // length of cd must be 288 (total # of codes)
        while (i < s) {
            val c = cd[i].toInt() and 0xFF
            if (c != 0) l[c - 1] = (l[c - 1] + 1) and 0xFFFF
            ++i
        }
        // u16 "map": index -> minimum code for bit length = index
        val le = IntArray(mb)
        i = 1
        while (i < mb) {
            le[i] = ((le[i - 1] + l[i - 1]) shl 1) and 0xFFFF
            ++i
        }
        val co: IntArray
        if (r != 0) {
            // Only the inflater needs the reversed map; kept for fidelity with the
            // original, never called from the compressor.
            co = IntArray(1 shl mb)
            val rvb = 15 - mb
            i = 0
            while (i < s) {
                val cdi = cd[i].toInt() and 0xFF
                if (cdi != 0) {
                    val sv = (i shl 4) or cdi
                    val free = mb - cdi
                    var v = (le[cdi - 1].also { le[cdi - 1] = (it + 1) and 0xFFFF }) shl free
                    val m = v or ((1 shl free) - 1)
                    while (v <= m) {
                        co[rev[v] shr rvb] = sv and 0xFFFF
                        ++v
                    }
                }
                ++i
            }
        } else {
            co = IntArray(s)
            i = 0
            while (i < s) {
                val cdi = cd[i].toInt() and 0xFF
                if (cdi != 0) {
                    // `le[cd[i] - 1]++` — post-increment: the OLD value indexes `rev`.
                    val old = le[cdi - 1]
                    le[cdi - 1] = (old + 1) and 0xFFFF
                    co[i] = (rev[old] shr (15 - cdi)) and 0xFFFF
                }
                ++i
            }
        }
        return co
    }

    /** `HuffNode` — index.ts:408..417. `s == -1` marks an internal node. */
    private class HuffNode(
        @JvmField val s: Int,
        @JvmField val f: Int,
        @JvmField val l: HuffNode? = null,
        @JvmField val r: HuffNode? = null,
    )

    /** `{ t, l }` of [hTree]: the code-length table (u8) and the max bit length. */
    private class Tree(val t: ByteArray, val l: Int)

    /**
     * creates code lengths from a frequency table — fflate index.ts:420..489
     *
     * [d] is the `Uint16Array` frequency table, [dLen] its length.
     *
     * Two traps here:
     *  * `const t2 = t.slice()` is a **copy of the array** taken *before* `t.sort`,
     *    holding the *same* node objects. `t` is then sorted and partly overwritten
     *    with internal nodes, so `t2` keeps the leaves in ascending symbol order.
     *  * both `sort` calls must be **stable**: the comparators tie whenever two
     *    symbols share a frequency (and, in the second sort, also a length), and
     *    V8's sort keeps the original order for ties.
     */
    private fun hTree(d: IntArray, dLen: Int, mb: Int): Tree {
        // Need extra info to make a tree
        val t = ArrayList<HuffNode>()
        for (i in 0 until dLen) {
            if (d[i] != 0) t.add(HuffNode(i, d[i]))
        }
        val s = t.size
        val t2 = ArrayList(t) // JS `t.slice()` — a COPY of the array, same node objects
        if (s == 0) return Tree(et, 0)
        if (s == 1) {
            val v = ByteArray(t[0].s + 1)
            v[t[0].s] = 1
            return Tree(v, 1)
        }
        t.sortWith(compareBy<HuffNode> { it.f }) // stable, like V8's TimSort
        // after i2 reaches last ind, will be stopped
        // freq must be greater than largest possible number of symbols
        t.add(HuffNode(-1, 25001))
        var l = t[0]
        var r = t[1]
        var i0 = 0
        var i1 = 1
        var i2 = 2
        t[0] = HuffNode(-1, l.f + r.f, l, r)
        // efficient algorithm from UZIP.js
        // i0 is lookbehind, i2 is lookahead
        while (i1 != s - 1) {
            l = if (t[i0].f < t[i2].f) t[i0++] else t[i2++]
            r = if (i0 != i1 && t[i0].f < t[i2].f) t[i0++] else t[i2++]
            t[i1++] = HuffNode(-1, l.f + r.f, l, r)
        }
        var maxSym = t2[0].s
        for (i in 1 until s) {
            if (t2[i].s > maxSym) maxSym = t2[i].s
        }
        // code lengths (u16)
        val tr = IntArray(maxSym + 1)
        // max bits in tree
        var mbt = ln(t[i1 - 1], tr, 0)
        if (mbt > mb) {
            // more algorithms from UZIP.js
            //  ind    debt
            var i = 0
            var dt = 0
            //    left            cost
            val lft = mbt - mb
            val cst = 1 shl lft
            // `tr[b.s] - tr[a.s] || a.f - b.f` — descending length, then ascending
            // frequency; stable for full ties.
            t2.sortWith(compareByDescending<HuffNode> { tr[it.s] }.thenBy { it.f })
            while (i < s) {
                val i2v = t2[i].s
                if (tr[i2v] > mb) {
                    dt += cst - (1 shl (mbt - tr[i2v]))
                    tr[i2v] = mb and 0xFFFF
                } else {
                    break
                }
                ++i
            }
            dt = dt shr lft
            while (dt > 0) {
                val i2v = t2[i].s
                if (tr[i2v] < mb) {
                    // `1 << (mb - tr[i2]++ - 1)` — the shift uses the OLD value.
                    val old = tr[i2v]
                    tr[i2v] = (old + 1) and 0xFFFF
                    dt -= 1 shl (mb - old - 1)
                } else {
                    ++i
                }
            }
            while (i >= 0 && dt != 0) {
                val i2v = t2[i].s
                if (tr[i2v] == mb) {
                    tr[i2v] = (tr[i2v] - 1) and 0xFFFF
                    ++dt
                }
                --i
            }
            mbt = mb
        }
        // `new u8(tr)` — u16 -> u8, truncating; lengths are <= 15 so nothing is lost
        val out = ByteArray(tr.size)
        for (i in tr.indices) out[i] = tr[i].toByte()
        return Tree(out, mbt)
    }

    /** get the max length and assign length codes — fflate index.ts:491..495 */
    private fun ln(n: HuffNode, l: IntArray, d: Int): Int =
        if (n.s == -1) {
            max(ln(n.l!!, l, d + 1), ln(n.r!!, l, d + 1))
        } else {
            l[n.s] = d and 0xFFFF
            d // JS: the value of the assignment expression, i.e. the un-truncated `d`
        }

    /** `{ c, n }` of [lc]; [cLen] is the logical length of the `cl.subarray(0, cli)` view. */
    private class LcResult(val c: IntArray, val cLen: Int, val n: Int)

    /**
     * length codes generation (run-length encoding of a code-length table)
     * — fflate index.ts:498..527
     *
     * Two JS traps:
     *  * `c[i]` is read at `i == s`, which is out of bounds whenever the table has
     *    no trailing zero. `undefined == <number>` is false (so the run breaks) but
     *    `undefined == undefined` is true — modelled with the sentinel `-1`, which
     *    behaves identically under `==`.
     *  * that same `undefined` can then be *written* into the `Uint16Array`, where
     *    it becomes `0` (`ToUint16(NaN)`). Handled inside `w`.
     */
    private fun lc(c: ByteArray, cLenIn: Int): LcResult {
        var s = cLenIn
        // Note that the semicolon was intentional
        while (s != 0) {
            --s
            if ((c[s].toInt() and 0xFF) != 0) break
        }
        ++s
        val cl = IntArray(s) // u16
        //  ind      num         streak
        var cli = 0
        var cln = if (cLenIn > 0) c[0].toInt() and 0xFF else -1 // JS OOB: -1 === undefined
        var cls = 1
        fun w(v: Int) {
            // A written `undefined` lands in the Uint16Array as 0; no legitimate
            // value is ever negative, so this is exactly the JS behaviour.
            cl[cli++] = (if (v < 0) 0 else v) and 0xFFFF
        }
        for (i in 1..s) {
            val ci = if (i < cLenIn) c[i].toInt() and 0xFF else -1 // JS OOB
            if (ci == cln && i != s) {
                ++cls
            } else {
                if (cln <= 0 && cls > 2) { // `!cln` is true for 0 and for undefined
                    while (cls > 138) {
                        w(32754)
                        cls -= 138
                    }
                    if (cls > 2) {
                        w(if (cls > 10) ((cls - 11) shl 5) or 28690 else ((cls - 3) shl 5) or 12305)
                        cls = 0
                    }
                } else if (cls > 3) {
                    w(cln)
                    --cls
                    while (cls > 6) {
                        w(8304)
                        cls -= 6
                    }
                    if (cls > 2) {
                        w(((cls - 3) shl 5) or 8208)
                        cls = 0
                    }
                }
                // `while (cls--) w(cln)` — the post-decrement leaves `cls` at -1 in JS,
                // but it is unconditionally reset to 1 on the next line, so 0 is fine.
                while (cls != 0) {
                    --cls
                    w(cln)
                }
                cls = 1
                cln = ci
            }
        }
        // `cl.subarray(0, cli)` is a VIEW; callers only read it, so the array plus
        // its logical length is carried instead of copying.
        return LcResult(cl, cli, s)
    }

    /**
     * calculate the length of output from tree, code lengths — fflate index.ts:530..534
     *
     * Iterates over `cl.length`; every call site has `cl.length <= cf.length`, so
     * `cf[i]` is always in bounds (a JS `undefined` here would poison the sum with NaN).
     */
    private fun clen(cf: IntArray, cl: ByteArray, clLen: Int): Int {
        var l = 0
        for (i in 0 until clLen) l += cf[i] * (cl[i].toInt() and 0xFF)
        return l
    }

    // -----------------------------------------------------------------------
    // Bit writing
    // -----------------------------------------------------------------------

    /**
     * starting at p, write the minimum number of bits that can hold v to d
     * — fflate index.ts:392..397
     *
     * `(p / 8) | 0` is a float division truncated toward zero; `p` is never
     * negative, so integer division is identical. Writing past the end of a JS
     * typed array is a silent no-op — hence the bounds guards.
     */
    private fun wbits(d: ByteArray, p: Int, vIn: Int) {
        val v = vIn shl (p and 7)
        val o = p / 8
        if (o < d.size) d[o] = (d[o].toInt() or v).toByte()
        if (o + 1 < d.size) d[o + 1] = (d[o + 1].toInt() or (v shr 8)).toByte()
    }

    /**
     * starting at p, write the minimum number of bits (>8) that can hold v to d
     * — fflate index.ts:400..406
     */
    private fun wbits16(d: ByteArray, p: Int, vIn: Int) {
        val v = vIn shl (p and 7)
        val o = p / 8
        if (o < d.size) d[o] = (d[o].toInt() or v).toByte()
        if (o + 1 < d.size) d[o + 1] = (d[o + 1].toInt() or (v shr 8)).toByte()
        if (o + 2 < d.size) d[o + 2] = (d[o + 2].toInt() or (v shr 16)).toByte()
    }

    /** get end of byte — fflate index.ts:145. `p >= 0`, so `((p + 7) / 8) | 0` is integer division. */
    private fun shft(p: Int): Int = (p + 7) / 8

    /**
     * writes a fixed (stored) block; returns the new bit pos — fflate index.ts:538..548
     *
     * The original takes `dat.subarray(bs, bs + bl)`, a read-only VIEW; the view is
     * passed here as `dat` + `[ds, de)` so nothing is copied.
     */
    private fun wfblk(out: ByteArray, pos: Int, dat: ByteArray, ds: Int, de: Int): Int {
        // no need to write 00 as type: TypedArray defaults to 0
        val s = de - ds
        val o = shft(pos + 2)
        out[o] = (s and 255).toByte()
        out[o + 1] = (s shr 8).toByte()
        out[o + 2] = ((out[o].toInt() and 0xFF) xor 255).toByte()
        out[o + 3] = ((out[o + 1].toInt() and 0xFF) xor 255).toByte()
        for (i in 0 until s) out[o + i + 4] = dat[ds + i]
        return (o + 4 + s) * 8
    }

    /**
     * writes a block — fflate index.ts:551..606
     *
     * Chooses between a stored, fixed-Huffman and dynamic-Huffman block by
     * computing all three encoded lengths, exactly as fflate does. Returns the new
     * bit position.
     */
    @Suppress("LongParameterList")
    private fun wblk(
        dat: ByteArray,
        out: ByteArray,
        final: Int,
        syms: IntArray,
        lf: IntArray,
        df: IntArray,
        eb: Int,
        li: Int,
        bs: Int,
        bl: Int,
        pIn: Int,
    ): Int {
        var p = pIn
        wbits(out, p++, final)
        lf[256] = (lf[256] + 1) and 0xFFFF
        val dltT = hTree(lf, 288, 15)
        val dlt = dltT.t
        val mlb = dltT.l
        val ddtT = hTree(df, 32, 15)
        val ddt = ddtT.t
        val mdb = ddtT.l
        val lcl = lc(dlt, dlt.size)
        val lclt = lcl.c
        val lcltLen = lcl.cLen
        val nlc = lcl.n
        val lcd = lc(ddt, ddt.size)
        val lcdt = lcd.c
        val lcdtLen = lcd.cLen
        val ndc = lcd.n
        val lcfreq = IntArray(19) // u16
        for (i in 0 until lcltLen) {
            val k = lclt[i] and 31
            lcfreq[k] = (lcfreq[k] + 1) and 0xFFFF
        }
        for (i in 0 until lcdtLen) {
            val k = lcdt[i] and 31
            lcfreq[k] = (lcfreq[k] + 1) and 0xFFFF
        }
        val lctT = hTree(lcfreq, 19, 7)
        val lct = lctT.t
        val mlcb = lctT.l
        // JS OOB: `lct` can be shorter than 19; `lct[oob]` is `undefined`, which is
        // falsy here and coerces to 0 when written with `wbits` below.
        fun lctAt(i: Int): Int = if (i < lct.size) lct[i].toInt() and 0xFF else 0
        var nlcc = 19
        while (nlcc > 4 && lctAt(clim[nlcc - 1]) == 0) --nlcc
        val flen = (bl + 5) shl 3
        val ftlen = clen(lf, flt, flt.size) + clen(df, fdt, fdt.size) + eb
        val dtlen = clen(lf, dlt, dlt.size) + clen(df, ddt, ddt.size) + eb + 14 + 3 * nlcc +
            clen(lcfreq, lct, lct.size) +
            2 * lcfreq[16] + 3 * lcfreq[17] + 7 * lcfreq[18]
        if (bs >= 0 && flen <= ftlen && flen <= dtlen) {
            return wfblk(out, p, dat, bs, bs + bl) // `dat.subarray(bs, bs + bl)` — a VIEW
        }
        val lm: IntArray
        val ll: ByteArray
        val dm: IntArray
        val dl: ByteArray
        // `1 + (dtlen < ftlen)` — the boolean coerces to 0/1: 01 fixed, 10 dynamic
        wbits(out, p, 1 + (if (dtlen < ftlen) 1 else 0))
        p += 2
        if (dtlen < ftlen) {
            lm = hMap(dlt, dlt.size, mlb, 0)
            ll = dlt
            dm = hMap(ddt, ddt.size, mdb, 0)
            dl = ddt
            val llm = hMap(lct, lct.size, mlcb, 0)
            wbits(out, p, nlc - 257)
            wbits(out, p + 5, ndc - 1)
            wbits(out, p + 10, nlcc - 4)
            p += 14
            for (i in 0 until nlcc) wbits(out, p + 3 * i, lctAt(clim[i]))
            p += 3 * nlcc
            for (it in 0 until 2) {
                val clct = if (it == 0) lclt else lcdt
                val clctLen = if (it == 0) lcltLen else lcdtLen
                for (i in 0 until clctLen) {
                    val len = clct[i] and 31
                    wbits(out, p, llm[len])
                    p += lct[len].toInt() and 0xFF
                    if (len > 15) {
                        wbits(out, p, (clct[i] shr 5) and 127)
                        p += clct[i] shr 12
                    }
                }
            }
        } else {
            lm = flm
            ll = flt
            dm = fdm
            dl = fdt
        }
        for (i in 0 until li) {
            val sym = syms[i]
            if (sym > 255) {
                val len = (sym shr 18) and 31
                wbits16(out, p, lm[len + 257])
                p += ll[len + 257].toInt() and 0xFF
                if (len > 7) {
                    wbits(out, p, (sym shr 23) and 31)
                    p += fleb[len]
                }
                val dst = sym and 31
                wbits16(out, p, dm[dst])
                p += dl[dst].toInt() and 0xFF
                if (dst > 3) {
                    wbits16(out, p, (sym shr 5) and 8191)
                    p += fdeb[dst]
                }
            } else {
                wbits16(out, p, lm[sym])
                p += ll[sym].toInt() and 0xFF
            }
        }
        wbits16(out, p, lm[256])
        return p + (ll[256].toInt() and 0xFF)
    }

    // -----------------------------------------------------------------------
    // The compressor
    // -----------------------------------------------------------------------

    /**
     * `DeflateState` — fflate index.ts:614..629. Only the fields the one-shot path
     * touches are carried; the streaming fields exist so the port keeps the shape
     * of the original.
     */
    private class DeflateState(
        @JvmField var h: IntArray? = null, // head (u16)
        @JvmField var p: IntArray? = null, // prev (u16)
        @JvmField var i: Int = 0, // index
        @JvmField var z: Int = 0, // end index
        @JvmField var w: Int = 0, // wait index
        @JvmField var r: Int = 0, // remainder byte info
        @JvmField var l: Int = 0, // last chunk
    )

    /**
     * compresses data into a raw DEFLATE buffer — fflate index.ts:632..749
     *
     * `w = o.subarray(pre, o.length - post)` is a VIEW: writes to `w` land in `o`.
     * With `pre == post == 0` (the only shape `deflateSync` uses) the view is the
     * whole buffer, so `w` is `o` itself here; anything else is rejected rather
     * than silently mis-aliased.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun dflt(dat: ByteArray, lvl: Int, plvl: Int, pre: Int, post: Int, st: DeflateState): ByteArray {
        require(pre == 0 && post == 0) { "RawDeflate only implements the raw (pre=post=0) envelope" }
        val s = if (st.z != 0) st.z else dat.size
        // `Math.ceil(s / 7000)` — FLOAT division inside the ceiling.
        val o = ByteArray(pre + s + 5 * (1 + ceil(s.toDouble() / 7000.0).toInt()) + post)
        // writing to this writes to the output buffer
        val w = o // subarray VIEW; identical to `o` because pre == post == 0
        val lst = st.l
        var pos = st.r and 7
        // JS reads past the end of `dat` as `undefined`: never equal to a number,
        // and 0 inside `^` / `<<`. Two accessors keep the two behaviours apart.
        fun datCmp(i: Int): Int = if (i >= 0 && i < dat.size) dat[i].toInt() and 0xFF else -1
        fun datNum(i: Int): Int = if (i >= 0 && i < dat.size) dat[i].toInt() and 0xFF else 0
        if (lvl != 0) {
            if (pos != 0) w[0] = (st.r shr 3).toByte()
            val opt = deo[lvl - 1]
            val n = opt shr 13
            val c = opt and 8191
            val msk = (1 shl plvl) - 1
            //    prev 2-byte val map    curr 2-byte val map
            val prev = st.p ?: IntArray(32768)
            val head = st.h ?: IntArray(msk + 1)
            // `Math.ceil(plvl / 3)` — FLOAT division: plvl 13 gives 5, not 4.
            val bs1 = ceil(plvl.toDouble() / 3.0).toInt()
            val bs2 = 2 * bs1
            fun hsh(i: Int): Int = (datNum(i) xor (datNum(i + 1) shl bs1) xor (datNum(i + 2) shl bs2)) and msk
            // 24576 is an arbitrary number of maximum symbols per block
            // 424 buffer for last block
            val syms = IntArray(25000)
            // length/literal freq   distance freq
            val lf = IntArray(288)
            val df = IntArray(32)
            //  l/lcnt  exbits  index          l/lind  waitdx          blkpos
            // (`lc` shadows the module-level function in the original; renamed here)
            var lcnt = 0
            var eb = 0
            var i = st.i
            var li = 0
            var wi = st.w
            var bs = 0
            while (i + 2 < s) {
                // hash value
                val hv = hsh(i)
                // index mod 32768    previous index mod
                var imod = i and 32767
                var pimod = head[hv]
                prev[imod] = pimod and 0xFFFF
                head[hv] = imod and 0xFFFF
                // We always should modify head and prev, but only add symbols if
                // this data is not yet processed ("wait" for wait index)
                if (wi <= i) {
                    // bytes remaining
                    val rem = s - i
                    if ((lcnt > 7000 || li > 24576) && (rem > 423 || lst == 0)) {
                        pos = wblk(dat, w, 0, syms, lf, df, eb, li, bs, i - bs, pos)
                        li = 0
                        lcnt = 0
                        eb = 0
                        bs = i
                        for (j in 0 until 286) lf[j] = 0
                        for (j in 0 until 30) df[j] = 0
                    }
                    //  len    dist   chain
                    var l = 2
                    var d = 0
                    var ch = c
                    var dif = (imod - pimod) and 32767
                    if (rem > 2 && hv == hsh(i - dif)) {
                        val maxn = min(n, rem) - 1
                        val maxd = min(32767, i)
                        // max possible length
                        // not capped at dif because decompressors implement "rolling" index population
                        val ml = min(258, rem)
                        // `while (dif <= maxd && --ch && imod != pimod)` — `--ch` must only
                        // run when the first test passed, hence the unrolled form.
                        while (true) {
                            if (dif > maxd) break
                            if (--ch == 0) break
                            if (imod == pimod) break
                            if (datCmp(i + l) == datCmp(i + l - dif)) { // JS OOB: dat[i + l] may be past the end
                                var nl = 0
                                while (nl < ml && datCmp(i + nl) == datCmp(i + nl - dif)) ++nl
                                if (nl > l) {
                                    l = nl
                                    d = dif
                                    // break out early when we reach "nice" (we are satisfied enough)
                                    if (nl > maxn) break
                                    // now, find the rarest 2-byte sequence within this
                                    // length of literals and search for that instead.
                                    val mmd = min(dif, nl - 2)
                                    var md = 0
                                    for (j in 0 until mmd) {
                                        val ti = (i - dif + j) and 32767
                                        val pti = prev[ti]
                                        val cd = (ti - pti) and 32767
                                        if (cd > md) {
                                            md = cd
                                            pimod = ti
                                        }
                                    }
                                }
                            }
                            // check the previous match
                            imod = pimod
                            pimod = prev[imod]
                            dif += (imod - pimod) and 32767
                        }
                    }
                    // d will be nonzero only when a match was found
                    if (d != 0) {
                        // store both dist and len data in one int32
                        // Make sure this is recognized as a len/dist with 28th bit (2^28)
                        syms[li++] = 268435456 or (revfl[l] shl 18) or revfd[d]
                        val lin = revfl[l] and 31
                        val din = revfd[d] and 31
                        eb += fleb[lin] + fdeb[din]
                        lf[257 + lin] = (lf[257 + lin] + 1) and 0xFFFF
                        df[din] = (df[din] + 1) and 0xFFFF
                        wi = i + l
                        ++lcnt
                    } else {
                        val b = dat[i].toInt() and 0xFF
                        syms[li++] = b
                        lf[b] = (lf[b] + 1) and 0xFFFF
                    }
                }
                ++i
            }
            i = max(i, wi)
            while (i < s) {
                val b = dat[i].toInt() and 0xFF
                syms[li++] = b
                lf[b] = (lf[b] + 1) and 0xFFFF
                ++i
            }
            pos = wblk(dat, w, lst, syms, lf, df, eb, li, bs, i - bs, pos)
            if (lst == 0) {
                st.r = (pos and 7) or ((w[pos / 8].toInt() and 0xFF) shl 3)
                // shft(pos) now 1 less if pos & 7 != 0
                pos -= 7
                st.h = head
                st.p = prev
                st.i = i
                st.w = wi
            }
        } else {
            var i = st.w
            while (i < s + lst) {
                // end
                var e = i + 65535
                if (e >= s) {
                    // write final block
                    w[pos / 8] = lst.toByte()
                    e = s
                }
                pos = wfblk(w, pos + 1, dat, i, e)
                i += 65535
            }
            st.i = s
        }
        // `slc(o, 0, …)` — a COPY, not a view
        return o.copyOfRange(0, pre + shft(pos) + post)
    }
}
