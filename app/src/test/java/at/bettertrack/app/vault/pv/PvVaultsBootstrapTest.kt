package at.bettertrack.app.vault.pv

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.db.PvVaultSyncDao
import at.bettertrack.app.vault.FakeSharedPreferences
import at.bettertrack.app.vault.pv.custody.PvDeviceCustody
import at.bettertrack.app.vault.pv.custody.PvElapsedClock
import at.bettertrack.app.vault.pv.custody.PvEndpointKeystore
import at.bettertrack.app.vault.pv.sync.PvDocTransactions
import at.bettertrack.app.vault.pv.sync.PvVaultSyncRuntime
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Dormancy, proven by counting rather than by reading.**
 *
 * `PvSyncDisciplineTest` reads the sources and holds that only two files may name
 * the rail and that both carry the flag guard. That is the static half. This is
 * the dynamic one: with the flag off, the bootstrap is CALLED and must still
 * touch nothing.
 *
 * "Touch nothing" is asserted against recording proxies for the two dependencies
 * that would do observable work — the API client and the database DAO. Every
 * call through either is counted, and the count must be zero: no engine is
 * built, no cursor is read, no vault row is queried, nothing is published for
 * the WorkManager worker to find.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PvVaultsBootstrapTest {

    /** Records every call it is asked to make, and refuses to make any of them. */
    private class Recorder {
        val calls = mutableListOf<String>()

        fun <T> proxy(type: Class<T>): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                calls += "${type.simpleName}.${method.name}"
                error("${type.simpleName} must not be touched while the flag is off")
            },
        )!!
    }

    private val recorder = Recorder()

    private object DirectTransactions : PvDocTransactions {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private fun custody() = PvDeviceCustody(
        keystore = PvEndpointKeystore(FakeSharedPreferences()),
        kdfDispatcher = UnconfinedTestDispatcher(),
        clock = PvElapsedClock { 0L },
    )

    private fun start(): PvVaultsSession? = PvVaultsBootstrap.start(
        scope = TestScope(UnconfinedTestDispatcher()),
        api = recorder.proxy(BtApi::class.java),
        json = Json,
        dao = recorder.proxy(PvVaultSyncDao::class.java),
        transactions = DirectTransactions,
        custody = custody(),
        deviceId = { error("the device id must not be read while the flag is off") },
        hasSession = { error("the session must not be probed while the flag is off") },
    )

    @Test
    fun `the program flag is off in this build`() {
        // The premise of every other assertion here, and of the epic's whole
        // "behaviourally identical to a build without the code" promise.
        assertFalse(ParanoidVaultsFlags.enabled)
    }

    @Test
    fun `starting the rail with the flag off builds nothing and publishes nothing`() {
        assertNull("no session may exist", start())
        assertEquals("nothing may be asked of the API or the database", emptyList<String>(), recorder.calls)
        assertNull(
            "the scheduled worker must find no engine, exactly as in a build without this code",
            PvVaultSyncRuntime.engine(),
        )
        assertNull(PvVaultSyncRuntime.session.value)
    }

    @Test
    fun `stopping the rail is safe and leaves nothing behind`() {
        PvVaultsBootstrap.stop()
        assertNull(PvVaultSyncRuntime.engine())
        assertNull(PvVaultSyncRuntime.session.value)
        assertEquals(emptyList<String>(), recorder.calls)
    }
}
