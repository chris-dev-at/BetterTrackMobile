package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.AuthInterceptor
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.TokenAuthenticator
import at.bettertrack.app.data.api.dto.TwoFactorStepUpCredential
import at.bettertrack.app.data.auth.FakeStore
import at.bettertrack.app.data.auth.FakeTokenApi
import at.bettertrack.app.data.auth.rotatedResponse
import at.bettertrack.app.data.auth.testJson
import at.bettertrack.app.data.auth.testManager
import at.bettertrack.app.data.auth.testTokens
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Wire tests for the client side of platform #2101 (two-factor step-up), across
 * all six factor mutations, driven through the REAL [AuthInterceptor] +
 * [TokenAuthenticator] so "no refresh, no sign-out" is measured, not assumed.
 *
 * The facts guarded:
 *  - the first try carries NO `stepUp` member (today's strict server rejects it);
 *  - only `400 STEP_UP_REQUIRED` prompts, and one confirm is one re-send, with
 *    the original fields intact and `stepUp.password` added;
 *  - cancel sends nothing more;
 *  - a wrong password (`401 INVALID_CREDENTIALS`) re-prompts and never touches
 *    the token rail (no refresh, no bearer retry, no wipe);
 *  - a genuine dead bearer on the first call still refreshes exactly as before.
 */
class TwoFactorStepUpWireTest {

    private class Case(
        val name: String,
        val path: String,
        /** The body fields the call carries without a step-up. */
        val baseFields: Map<String, String>,
        val okBody: String,
        val call: suspend AccountRepository.(stepUpPassword: String?) -> BtResult<*>,
    )

    private val cases = listOf(
        Case("totp enroll", "/auth/2fa/enroll", emptyMap(), """{"otpauthUri":"otpauth://totp/x","secret":"ABCDEF"}""") {
            twoFactorEnroll(it)
        },
        Case("totp confirm", "/auth/2fa/confirm", mapOf("code" to "123456"), """{"recoveryCodes":null}""") {
            twoFactorConfirm(" 123456 ", it)
        },
        Case("email enroll", "/auth/2fa/email/enroll", emptyMap(), """{"ok":true}""") {
            twoFactorEmailEnroll(it)
        },
        Case("email confirm", "/auth/2fa/email/confirm", mapOf("code" to "654321"), """{"recoveryCodes":null}""") {
            twoFactorEmailConfirm("654321", it)
        },
        Case("email disable", "/auth/2fa/email/disable", emptyMap(), """{"ok":true}""") {
            twoFactorEmailDisable(it)
        },
        Case("recovery codes", "/auth/2fa/recovery-codes", emptyMap(), """{"recoveryCodes":["aaaa-bbbb"]}""") {
            regenerateRecoveryCodes(it)
        },
    )

    /** Scripted prompt: answers in order (`null` = Cancel), records every reason it was opened with. */
    private class ScriptedPrompter(vararg answers: String?) : StepUpPrompter {
        private val queue = ArrayDeque(answers.toList())
        val reasons = mutableListOf<StepUpPromptReason>()
        var finished = 0
        override suspend fun requestPassword(reason: StepUpPromptReason): String? {
            reasons += reason
            check(queue.isNotEmpty()) { "prompt opened more often than scripted: $reasons" }
            return queue.removeFirst()
        }
        override fun finished() { finished++ }
    }

    private class Rig(val server: MockWebServer, val repo: AccountRepository, val tokenApi: FakeTokenApi, val store: FakeStore)

    private fun forEachCase(block: suspend Rig.(Case) -> Unit) = runBlocking {
        for (case in cases) {
            val server = MockWebServer().apply { start() }
            try {
                val store = FakeStore(testTokens(access = "A0", refresh = "R0"))
                val tokenApi = FakeTokenApi { rotatedResponse("A1", "R1") }
                val tm = testManager(tokenApi, store)
                val client = OkHttpClient.Builder()
                    .addInterceptor(AuthInterceptor(tm))
                    .authenticator(TokenAuthenticator(tm))
                    .build()
                val api = Retrofit.Builder()
                    .baseUrl(server.url("/"))
                    .client(client)
                    .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
                    .build()
                    .create(BtApi::class.java)
                Rig(server, AccountRepository(api, testJson), tokenApi, store).block(case)
            } finally {
                server.shutdown()
            }
        }
    }

    private fun Rig.enqueue(code: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun Rig.error(code: Int, apiCode: String) =
        enqueue(code, """{"error":{"code":"$apiCode","message":"x"}}""")

    private fun Rig.take(): RecordedRequest =
        checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "expected another request" }

    private fun RecordedRequest.bodyJson(): JsonObject? {
        val text = body.readUtf8()
        return if (text.isEmpty()) null else testJson.parseToJsonElement(text).jsonObject
    }

    private fun Rig.assertTokenRailUntouched(case: Case) {
        assertEquals("${case.name}: no token refresh", 0, tokenApi.calls.get())
        assertFalse("${case.name}: no sign-out", store.wiped)
        assertEquals("${case.name}: the session is kept", "A0", store.tokens?.accessToken)
    }

    @Test
    fun `the first try of every call carries no stepUp member`() = forEachCase { case ->
        enqueue(200, case.okBody)
        val prompter = ScriptedPrompter()

        val outcome = withStepUp(prompter) { repo.(case.call)(it) }

        assertTrue("${case.name}: $outcome", outcome is StepUpOutcome.Done && outcome.result is BtResult.Ok)
        assertEquals("${case.name}: no prompt", emptyList<StepUpPromptReason>(), prompter.reasons)
        assertEquals(1, server.requestCount)
        val req = take()
        assertEquals(case.path, req.path)
        assertNull("${case.name}: the bare call is not reauth-exempt", req.getHeader("X-Bt-No-Reauth"))
        val keys = req.bodyJson()?.keys.orEmpty()
        assertFalse("${case.name}: stepUp sent up front: $keys", "stepUp" in keys)
        assertEquals("${case.name}: today's body, unchanged", case.baseFields.keys, keys)
    }

    @Test
    fun `STEP_UP_REQUIRED prompts once and re-sends the same call once with stepUp password`() = forEachCase { case ->
        error(400, "STEP_UP_REQUIRED")
        enqueue(200, case.okBody)
        val prompter = ScriptedPrompter("correct horse")

        val outcome = withStepUp(prompter) { repo.(case.call)(it) }

        assertTrue("${case.name}: $outcome", outcome is StepUpOutcome.Done && outcome.result is BtResult.Ok)
        assertEquals(listOf(StepUpPromptReason.REQUIRED), prompter.reasons)
        assertEquals("${case.name}: prompt closed", 1, prompter.finished)
        assertEquals("${case.name}: exactly one re-send", 2, server.requestCount)
        assertFalse("stepUp" in take().bodyJson()?.keys.orEmpty())
        val retry = take()
        assertEquals(case.path, retry.path)
        assertEquals("1", retry.getHeader("X-Bt-No-Reauth"))
        val body = checkNotNull(retry.bodyJson())
        assertEquals(case.baseFields.keys + "stepUp", body.keys)
        case.baseFields.forEach { (k, v) -> assertEquals("${case.name}.$k intact", v, body[k]?.jsonPrimitive?.content) }
        val stepUp = body.getValue("stepUp").jsonObject
        assertEquals("app sends the password only", setOf("password"), stepUp.keys)
        assertEquals("correct horse", stepUp.getValue("password").jsonPrimitive.content)
        assertTokenRailUntouched(case)
    }

    @Test
    fun `cancel sends nothing more and is not an error`() = forEachCase { case ->
        error(400, "STEP_UP_REQUIRED")
        val prompter = ScriptedPrompter(null)

        val outcome = withStepUp(prompter) { repo.(case.call)(it) }

        assertEquals("${case.name}", StepUpOutcome.Cancelled, outcome)
        assertEquals(1, prompter.finished)
        assertEquals("${case.name}: no second request", 1, server.requestCount)
        assertTokenRailUntouched(case)
    }

    @Test
    fun `a wrong password re-prompts without refresh, extra request or sign-out`() = forEachCase { case ->
        error(400, "STEP_UP_REQUIRED")
        error(401, "INVALID_CREDENTIALS")
        enqueue(200, case.okBody)
        val prompter = ScriptedPrompter("wrong", "right")

        val outcome = withStepUp(prompter) { repo.(case.call)(it) }

        assertTrue("${case.name}: $outcome", outcome is StepUpOutcome.Done && outcome.result is BtResult.Ok)
        assertEquals(
            listOf(StepUpPromptReason.REQUIRED, StepUpPromptReason.WRONG_PASSWORD),
            prompter.reasons,
        )
        // bare + one per confirm: the 401 was NOT re-sent with a refreshed bearer.
        assertEquals("${case.name}: one request per user action", 3, server.requestCount)
        take()
        val wrong = take()
        val right = take()
        assertEquals("wrong", wrong.bodyJson()!!.getValue("stepUp").jsonObject.getValue("password").jsonPrimitive.content)
        assertEquals("right", right.bodyJson()!!.getValue("stepUp").jsonObject.getValue("password").jsonPrimitive.content)
        assertEquals("Bearer A0", right.getHeader("Authorization"))
        assertTokenRailUntouched(case)
    }

    @Test
    fun `a wrong password then cancel ends with no extra request`() = forEachCase { case ->
        error(400, "STEP_UP_REQUIRED")
        error(401, "INVALID_CREDENTIALS")
        val prompter = ScriptedPrompter("wrong", null)

        val outcome = withStepUp(prompter) { repo.(case.call)(it) }

        assertEquals(StepUpOutcome.Cancelled, outcome)
        assertEquals(2, server.requestCount)
        assertTokenRailUntouched(case)
    }

    @Test
    fun `STEP_UP_PASSWORD_REQUIRED re-prompts with its own reason`() = forEachCase { case ->
        error(400, "STEP_UP_REQUIRED")
        error(401, "STEP_UP_PASSWORD_REQUIRED")
        val prompter = ScriptedPrompter("pw", null)

        val outcome = withStepUp(prompter) { repo.(case.call)(it) }

        assertEquals(StepUpOutcome.Cancelled, outcome)
        assertEquals(
            listOf(StepUpPromptReason.REQUIRED, StepUpPromptReason.PASSWORD_REQUIRED),
            prompter.reasons,
        )
        assertEquals(2, server.requestCount)
        assertTokenRailUntouched(case)
    }

    @Test
    fun `any other 400 keeps today's error handling and never prompts`() = forEachCase { case ->
        error(400, "VALIDATION_ERROR")
        val prompter = ScriptedPrompter()

        val outcome = withStepUp(prompter) { repo.(case.call)(it) }

        val result = (outcome as StepUpOutcome.Done).result
        assertTrue(result is BtResult.Err)
        assertEquals("VALIDATION_ERROR", (result as BtResult.Err).error.code)
        assertEquals(emptyList<StepUpPromptReason>(), prompter.reasons)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a dead bearer on the bare call still refreshes and retries exactly as before`() = forEachCase { case ->
        error(401, "API_KEY_INVALID")
        enqueue(200, case.okBody)
        val prompter = ScriptedPrompter()

        val outcome = withStepUp(prompter) { repo.(case.call)(it) }

        assertTrue("${case.name}: $outcome", outcome is StepUpOutcome.Done && outcome.result is BtResult.Ok)
        assertEquals(emptyList<StepUpPromptReason>(), prompter.reasons)
        assertEquals("${case.name}: one refresh", 1, tokenApi.calls.get())
        take()
        assertEquals("Bearer A1", take().getHeader("Authorization"))
        assertFalse(store.wiped)
    }

    @Test
    fun `a step-up the app cannot satisfy points to the web`() = forEachCase { case ->
        error(400, "STEP_UP_REQUIRED")
        error(400, "STEP_UP_REQUIRED")
        val prompter = ScriptedPrompter("pw")

        val outcome = withStepUp(prompter) { repo.(case.call)(it) }

        assertEquals(StepUpOutcome.Unavailable, outcome)
        assertEquals(1, prompter.finished)
        assertEquals(2, server.requestCount)
        assertTokenRailUntouched(case)
    }

    @Test
    fun `the step-up credential never prints its password`() {
        assertFalse("hunter2" in TwoFactorStepUpCredential("hunter2").toString())
    }
}
