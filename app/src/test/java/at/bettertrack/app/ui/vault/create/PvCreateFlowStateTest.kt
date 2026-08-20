package at.bettertrack.app.ui.vault.create

import at.bettertrack.app.vault.pv.custody.PV_MNEMONIC_WORDS
import at.bettertrack.app.vault.pv.custody.PvCustodyMode
import at.bettertrack.app.vault.pv.keys.pvIssueMnemonic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §21 creation ceremony as a machine: step order, interlocks, and the
 * one-word check.
 *
 * Everything the ceremony *promises* lives in these functions rather than in a
 * composable, so the promises are checkable. Three of them are load-bearing:
 *
 *  1. the acknowledgment cannot be skipped (§16 — it is the only place the user
 *     is told the phrase is unrecoverable);
 *  2. a wrong verify answer returns to the words with no counter and no
 *     lockout (§21 Q2 — "no 20 years waiting and lots of friction");
 *  3. a medium that cannot actually be used cannot be selected past (Drive is
 *     E5-gated, and an option that advances into a broken flow is worse than an
 *     option that says it is not ready).
 *
 * No test below names a word from any phrase; where words are needed they come
 * from a real mint and are referenced by index.
 */
class PvCreateFlowStateTest {

    private fun fixedPicker(index: Int) = PvVerifyIndexPicker { index }

    /** A state that has satisfied every interlock up to and including [upTo]. */
    private fun stateAt(step: PvCreateStep): PvCreateState = PvCreateState(
        step = step,
        name = "Household",
        medium = PvVaultMedium.SERVER,
        verifyIndex = 3,
        verifyPassed = true,
        acknowledged = true,
        custody = PvCustodyMode.WRAPPED,
    )

    // ── the path ────────────────────────────────────────────────────────────

    @Test
    fun `the ceremony is exactly the seven ruled steps, in order`() {
        assertEquals(
            listOf(
                PvCreateStep.NAME,
                PvCreateStep.MEDIA,
                PvCreateStep.WORDS,
                PvCreateStep.VERIFY,
                PvCreateStep.ACKNOWLEDGE,
                PvCreateStep.CUSTODY,
                PvCreateStep.DONE,
            ),
            pvCreatePath(),
        )
    }

    @Test
    fun `a satisfied step always advances to its successor`() {
        val path = pvCreatePath()
        path.dropLast(1).forEachIndexed { index, step ->
            val advanced = pvAdvance(stateAt(step))
            assertEquals("$step did not advance", path[index + 1], advanced.step)
        }
    }

    @Test
    fun `the last step has nowhere to go`() {
        val done = stateAt(PvCreateStep.DONE)
        assertFalse(pvCanAdvance(done))
        assertEquals(done, pvAdvance(done))
    }

    @Test
    fun `back walks the same path and closes the flow from the first step`() {
        assertNull("back on the first step means leaving", pvPrevious(PvCreateState()))
        assertEquals(PvCreateStep.WORDS, pvPrevious(stateAt(PvCreateStep.VERIFY))?.step)
        assertEquals(PvCreateStep.NAME, pvPrevious(stateAt(PvCreateStep.MEDIA))?.step)
        // The summary is the end; there is nothing to walk back into.
        val done = stateAt(PvCreateStep.DONE)
        assertEquals(done, pvPrevious(done))
    }

    @Test
    fun `going back clears whatever was half-typed into the verify field`() {
        val typed = stateAt(PvCreateStep.VERIFY).copy(verifyInput = "half")
        assertEquals("", pvPrevious(typed)?.verifyInput)
    }

    // ── the interlocks ──────────────────────────────────────────────────────

    @Test
    fun `an unnamed vault cannot leave the first step`() {
        assertFalse(pvCanAdvance(PvCreateState()))
        assertFalse(pvCanAdvance(PvCreateState(name = "   ")))
        assertTrue(pvCanAdvance(PvCreateState(name = " Household ")))
        assertEquals("Household", PvCreateState(name = " Household ").trimmedName)
    }

    @Test
    fun `the name is capped at the length the QR hint allows`() {
        val long = "x".repeat(200)
        assertEquals(PV_VAULT_NAME_MAX, PvCreateState(name = long).trimmedName.length)
    }

    @Test
    fun `no medium chosen means no advance`() {
        assertFalse(pvCanAdvance(PvCreateState(step = PvCreateStep.MEDIA, name = "a")))
    }

    @Test
    fun `a medium that cannot be connected yet cannot be advanced past`() {
        // Drive is epic E5. The option is shown, disabled, with one honest line
        // — but if a future edit made it selectable, the interlock is the second
        // line of defence and this is what fails.
        val server = PvCreateState(step = PvCreateStep.MEDIA, medium = PvVaultMedium.SERVER)
        assertTrue(pvCanAdvance(server))
        listOf(PvVaultMedium.DRIVE, PvVaultMedium.BOTH).forEach { medium ->
            assertEquals(PV_DRIVE_CONNECTABLE, pvMediumAvailable(medium))
            assertEquals(
                PV_DRIVE_CONNECTABLE,
                pvCanAdvance(PvCreateState(step = PvCreateStep.MEDIA, medium = medium)),
            )
        }
        assertTrue("SERVER must always be available", pvMediumAvailable(PvVaultMedium.SERVER))
    }

    @Test
    fun `phone-only storage is not an option this build offers`() {
        // §22 reserves it without building it. An enum value with no flow behind
        // it is how a reserved feature becomes a silent promise.
        assertEquals(
            listOf(PvVaultMedium.SERVER, PvVaultMedium.DRIVE, PvVaultMedium.BOTH),
            PvVaultMedium.entries.toList(),
        )
    }

    @Test
    fun `reading the words is not a task to tick off`() {
        // §21 Q2 ruled against added friction; the verify step is the check.
        assertTrue(pvCanAdvance(PvCreateState(step = PvCreateStep.WORDS)))
    }

    @Test
    fun `the acknowledgment cannot be skipped`() {
        val unticked = stateAt(PvCreateStep.ACKNOWLEDGE).copy(acknowledged = false)
        assertFalse(pvCanAdvance(unticked))
        assertSame("an unticked acknowledgment advanced", PvCreateStep.ACKNOWLEDGE, pvAdvance(unticked).step)
    }

    @Test
    fun `there is no route from the words to the summary that misses the ack`() {
        // The stronger form of the rule: drive the machine from a fresh state,
        // answering every step except the acknowledgment, and prove it stalls.
        val issued = pvIssueMnemonic()
        var state = pvNewCreateState(fixedPicker(5))
        state = pvAdvance(state.copy(name = "Household"))
        state = pvAdvance(state.copy(medium = PvVaultMedium.SERVER))
        state = pvAdvance(state)
        state = pvSubmitVerify(state.copy(verifyInput = issued.words[5]), issued.words[5])
        assertEquals(PvCreateStep.ACKNOWLEDGE, state.step)
        repeat(5) { state = pvAdvance(state.copy(custody = PvCustodyMode.PLAIN)) }
        assertEquals("the machine walked past an unticked acknowledgment", PvCreateStep.ACKNOWLEDGE, state.step)
    }

    @Test
    fun `custody must be chosen before the summary`() {
        val nochoice = stateAt(PvCreateStep.CUSTODY).copy(custody = null)
        assertFalse(pvCanAdvance(nochoice))
        assertEquals(PvCreateStep.CUSTODY, pvAdvance(nochoice).step)
        PvCustodyMode.entries.forEach { mode ->
            assertEquals(PvCreateStep.DONE, pvAdvance(nochoice.copy(custody = mode)).step)
        }
    }

    @Test
    fun `the media choice survives to the summary`() {
        var state = pvNewCreateState(fixedPicker(0)).copy(name = "Household")
        state = pvAdvance(state)
        state = pvAdvance(state.copy(medium = PvVaultMedium.SERVER))
        assertEquals(PvVaultMedium.SERVER, state.medium)
        val summary = stateAt(PvCreateStep.DONE)
        assertEquals(PvVaultMedium.SERVER, summary.medium)
        assertEquals(PvCustodyMode.WRAPPED, summary.custody)
        assertEquals("Household", summary.trimmedName)
    }

    // ── §21 Q2: the one-word check ──────────────────────────────────────────

    @Test
    fun `the verify position is drawn, not fixed`() {
        val positions = (1..400).map { pvNewCreateState().verifyIndex }.toSet()
        assertTrue("the picker only ever produced ${positions.size} positions", positions.size > 6)
        assertTrue("a drawn position was outside the phrase", positions.all { it in 0 until PV_MNEMONIC_WORDS })
    }

    @Test
    fun `the picker is injectable so the drawn position is pinnable`() {
        assertEquals(7, pvNewCreateState(fixedPicker(7)).verifyIndex)
        assertEquals(8, pvNewCreateState(fixedPicker(7)).verifyPosition)
    }

    @Test
    fun `the check forgives case and whitespace, exactly like the QR path`() {
        assertTrue(pvVerifyWord("bicycle", "bicycle"))
        assertTrue(pvVerifyWord("bicycle", "  BiCyCle  "))
        assertTrue(pvVerifyWord("bicycle", "\tbicycle\n"))
        assertFalse(pvVerifyWord("bicycle", "bicycles"))
        assertFalse(pvVerifyWord("bicycle", ""))
        assertFalse(pvVerifyWord("bicycle", "   "))
        // One word was asked for; two are not one.
        assertFalse(pvVerifyWord("bicycle", "bicycle bicycle"))
        assertFalse(pvVerifyWord("", "anything"))
    }

    @Test
    fun `the right word moves straight on to the acknowledgment`() {
        val issued = pvIssueMnemonic()
        val state = pvNewCreateState(fixedPicker(2)).copy(
            step = PvCreateStep.VERIFY,
            name = "Household",
            medium = PvVaultMedium.SERVER,
            verifyInput = issued.words[2].uppercase(),
        )
        val next = pvSubmitVerify(state, issued.words[2])
        assertEquals(PvCreateStep.ACKNOWLEDGE, next.step)
        assertTrue(next.verifyPassed)
        assertFalse(next.verifyMissed)
        assertEquals("the typed word was left in the state", "", next.verifyInput)
    }

    @Test
    fun `a wrong word goes back to the words and never locks anything out`() {
        val issued = pvIssueMnemonic()
        var state = pvNewCreateState(fixedPicker(4)).copy(
            step = PvCreateStep.VERIFY,
            name = "Household",
            medium = PvVaultMedium.SERVER,
        )
        // Ten wrong attempts in a row: the state after the tenth is identical to
        // the state after the first. No counter, no backoff, no dead end.
        var afterFirst: PvCreateState? = null
        repeat(10) {
            state = pvSubmitVerify(state.copy(step = PvCreateStep.VERIFY, verifyInput = "wrong"), issued.words[4])
            assertEquals(PvCreateStep.WORDS, state.step)
            assertTrue(state.verifyMissed)
            assertFalse(state.verifyPassed)
            if (afterFirst == null) afterFirst = state
        }
        assertEquals(afterFirst, state)
        // And the correct word still works afterwards — the loop is a loop.
        state = pvSubmitVerify(
            state.copy(step = PvCreateStep.VERIFY, verifyInput = issued.words[4]),
            issued.words[4],
        )
        assertEquals(PvCreateStep.ACKNOWLEDGE, state.step)
        assertFalse(state.verifyMissed)
    }

    @Test
    fun `the asked-for position does not move between attempts`() {
        val issued = pvIssueMnemonic()
        val start = pvNewCreateState(fixedPicker(9)).copy(step = PvCreateStep.VERIFY, verifyInput = "wrong")
        val missed = pvSubmitVerify(start, issued.words[9])
        assertEquals(
            "re-rolling the position on every miss would make the check a guessing game",
            9,
            missed.verifyIndex,
        )
    }

    @Test
    fun `submitting from any other step does nothing`() {
        val issued = pvIssueMnemonic()
        listOf(PvCreateStep.NAME, PvCreateStep.WORDS, PvCreateStep.ACKNOWLEDGE, PvCreateStep.DONE).forEach { step ->
            val state = stateAt(step).copy(verifyInput = issued.words[0])
            assertEquals(state, pvSubmitVerify(state, issued.words[0]))
        }
    }

    @Test
    fun `every word a real mint produces passes its own check`() {
        // The join between the keys package and this one: whatever `pvIssueMnemonic`
        // renders must be typeable back in. A normalisation mismatch here would
        // strand the user on the verify step with the correct paper in hand.
        repeat(32) {
            val issued = pvIssueMnemonic()
            issued.words.forEachIndexed { index, word ->
                assertTrue("position $index failed its own check", pvVerifyWord(word, word))
                assertTrue("position $index failed uppercased", pvVerifyWord(word, word.uppercase()))
            }
        }
    }
}
