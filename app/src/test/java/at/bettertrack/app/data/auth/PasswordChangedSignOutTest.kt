package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.dto.TokenResponse
import at.bettertrack.app.data.db.LocalAccountData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Your password changed — sign in again." After a password change from the
 * phone the app ends its own session on purpose — this install's family
 * revoked, tokens dropped, a NAMED ledger reason, a login-screen notice — so it
 * behaves the same before platform PR-C (the phone kept its bearer) and after
 * it (every grant is revoked, the phone's included).
 */
class PasswordChangedSignOutTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private class MemoryLedger(initial: List<SignOutEvent> = emptyList()) : SignOutLedger {
        val rows = CopyOnWriteArrayList(initial)
        override fun record(event: SignOutEvent) = rows.add(0, event)
        override fun recent(): List<SignOutEvent> = rows.toList()
    }

    private class CountingLocalData : LocalAccountData {
        var logoutWipes = 0
        var fullWipes = 0
        override suspend fun onSessionEstablished(userId: String) = Unit
        override suspend fun wipeAll() { fullWipes++ }
        override suspend fun wipeForLogout() { logoutWipes++ }
    }

    private fun btApi(): BtApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BtApi::class.java)

    private class Rig(
        val repo: AuthRepository,
        val tokens: TokenManager,
        val api: FakeTokenApi,
        val store: FakeStore,
        val ledger: MemoryLedger,
        val local: CountingLocalData,
        val hooks: MutableList<String>,
    )

    private fun rig(
        answer: suspend (Int) -> Response<TokenResponse> = { error("no refresh expected") },
        ledger: MemoryLedger = MemoryLedger(),
        stored: AuthTokens? = testTokens(access = "bto_A1", refresh = "btr_R1"),
    ): Rig {
        val store = FakeStore(stored)
        val api = FakeTokenApi(answer)
        val tm = testManager(api, store)
        val local = CountingLocalData()
        val hooks = mutableListOf<String>()
        val repo = AuthRepository(
            tokenManager = tm,
            btApi = btApi(),
            store = store,
            json = testJson,
            webOrigin = "https://web.example.test",
            clientId = TEST_CLIENT,
            scope = CoroutineScope(Dispatchers.Unconfined),
            localAccountData = local,
            onBeforeLogout = { hooks += "deregister" },
            onAfterLogout = { hooks += "demote" },
            ledger = ledger,
        )
        return Rig(repo, tm, api, store, ledger, local, hooks)
    }

    // ── the reason → notice mapping ──────────────────────────────────────────

    private fun row(reason: SignOutReason) =
        SignOutEvent(at = 1L, reason = reason.name, trigger = SignOutTrigger.USER.name, caller = "t")

    @Test
    fun `only a password change gets the sign-in-again notice`() {
        assertEquals(LoginNotice.PASSWORD_CHANGED, loginNoticeFor(row(SignOutReason.PASSWORD_CHANGED)))
        SignOutReason.entries.filter { it != SignOutReason.PASSWORD_CHANGED }.forEach {
            assertNull(it.name, loginNoticeFor(row(it)))
        }
        assertNull("no history, no notice", loginNoticeFor(null))
        assertNull(
            "an unknown future reason says nothing",
            loginNoticeFor(SignOutEvent(1L, "SOMETHING_NEW", trigger = "USER", caller = "t")),
        )
    }

    @Test
    fun `the reason name is stable - it is persisted`() {
        assertEquals("PASSWORD_CHANGED", SignOutReason.PASSWORD_CHANGED.name)
    }

    // ── the sign-out itself ──────────────────────────────────────────────────

    @Test
    fun `a password change revokes this install, names the reason and shows the notice`() = runBlocking {
        val r = rig()
        assertTrue(r.repo.authState.value is AuthState.LoggedIn)
        assertNull(r.repo.loginNotice.value)

        r.repo.signOutAfterPasswordChange()

        assertEquals(listOf("btr_R1"), r.api.revoked.map { it.token })
        assertEquals("refresh_token", r.api.revoked.single().tokenTypeHint)
        assertEquals(0, server.requestCount) // revoke landed: no bearer fallback, no grant list
        val only = r.ledger.rows.single()
        assertEquals(SignOutReason.PASSWORD_CHANGED.name, only.reason)
        assertEquals(SignOutTrigger.USER.name, only.trigger)
        assertTrue(r.store.wiped)
        assertNull(r.store.tokens)
        assertEquals(AuthState.LoggedOut, r.repo.authState.value)
        assertEquals(LoginNotice.PASSWORD_CHANGED, r.repo.loginNotice.value)
    }

    @Test
    fun `it ends the session, it does not log out - data, mode and push token stay`() = runBlocking {
        val r = rig()
        r.repo.signOutAfterPasswordChange()
        assertEquals("the same person signs straight back in: keep the queue", 0, r.local.logoutWipes)
        assertEquals(0, r.local.fullWipes)
        assertEquals(emptyList<String>(), r.hooks)
    }

    /**
     * PR-C: the change already revoked every grant, so a request in flight
     * refreshes and is refused INVALID_GRANT while the app signs itself out.
     * That refusal is the consequence of the sign-out, not a second one.
     */
    @Test
    fun `a refresh refused mid sign-out is folded into it, not recorded as forced`() = runBlocking {
        val r = rig(answer = { refusedResponse(400, "INVALID_GRANT") })
        r.api.onRevoke = {
            r.tokens.refreshOn401("bto_A1") // a concurrent request's 401 path
            Response.success(Unit)
        }

        r.repo.signOutAfterPasswordChange()
        delay(50) // let any emission reach the collector

        assertEquals(1, r.api.calls.get())
        assertEquals(listOf(SignOutReason.PASSWORD_CHANGED.name), r.ledger.rows.map { it.reason })
        assertEquals(LoginNotice.PASSWORD_CHANGED, r.repo.loginNotice.value)
        assertEquals(listOf("btr_R1"), r.api.revoked.map { it.token })
    }

    @Test
    fun `a refusal that lands after the sign-out finished is folded too`() = runBlocking {
        val gate = CompletableDeferred<Response<TokenResponse>>()
        val started = CompletableDeferred<Unit>()
        val r = rig(answer = { started.complete(Unit); gate.await() })

        val inFlight = async(Dispatchers.Default) { r.tokens.refreshOn401("bto_A1") }
        withTimeout(2_000) { started.await() }
        r.repo.signOutAfterPasswordChange()
        gate.complete(refusedResponse(400, "INVALID_GRANT"))
        withTimeout(2_000) { inFlight.await() }
        delay(50)

        assertEquals(listOf(SignOutReason.PASSWORD_CHANGED.name), r.ledger.rows.map { it.reason })
        assertEquals(LoginNotice.PASSWORD_CHANGED, r.repo.loginNotice.value)
    }

    @Test
    fun `without a deliberate sign-out a refusal is still a forced sign-out`() = runBlocking {
        val r = rig(answer = { refusedResponse(400, "INVALID_GRANT") })
        r.tokens.refreshOn401("bto_A1")
        repeat(100) { if (r.ledger.rows.isEmpty()) delay(5) }

        assertEquals(listOf(SignOutReason.REFRESH_REJECTED.name), r.ledger.rows.map { it.reason })
        assertNull(r.repo.loginNotice.value)
        assertEquals(AuthState.LoggedOut, r.repo.authState.value)
    }

    @Test
    fun `the notice survives a restart and a later plain logout replaces it`() = runBlocking {
        val ledger = MemoryLedger(listOf(row(SignOutReason.PASSWORD_CHANGED)))
        // A new process, logged out: the newest row still explains the screen.
        val restarted = rig(ledger = ledger, stored = null)
        assertEquals(LoginNotice.PASSWORD_CHANGED, restarted.repo.loginNotice.value)

        val later = rig(ledger = ledger)
        later.repo.logout()
        assertNull(later.repo.loginNotice.value)
    }

    // ── the message, EN and DE ───────────────────────────────────────────────

    @Test
    fun `the login screen says it in both languages`() {
        assertEquals("Your password changed — sign in again.", string("", "bt_login_notice_password_changed"))
        assertEquals("Dein Passwort wurde geändert — melde dich neu an.", string("-de", "bt_login_notice_password_changed"))
        // The diagnostics screen names the reason too, in both languages.
        assertTrue(string("", "bt_diag_reason_password_changed").isNotBlank())
        assertTrue(string("-de", "bt_diag_reason_password_changed").isNotBlank())
    }

    private fun string(qualifier: String, name: String): String {
        val path = "src/main/res/values$qualifier/strings.xml"
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("strings.xml not found for values$qualifier")
        return Regex("""<string\s+name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?: error("$name missing from values$qualifier")
    }
}
