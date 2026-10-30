package at.bettertrack.app.data.db

import androidx.room.InvalidationTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.lang.reflect.Proxy

/**
 * An inert Room for plain-JVM tests of repositories whose RESULT never comes
 * from Room (this module has no Robolectric). Every DAO is a dynamic proxy:
 * flows are empty, lists are empty, writes do nothing. A test that needs a DAO
 * to hold rows uses a hand-rolled fake instead.
 */
inline fun <reified T> inertDao(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ ->
    when {
        Flow::class.java.isAssignableFrom(method.returnType) -> emptyFlow<Any>()
        method.returnType == List::class.java -> emptyList<Any>()
        method.returnType == java.lang.Integer.TYPE -> 0
        method.returnType == java.lang.Long.TYPE -> 0L
        method.returnType == java.lang.Boolean.TYPE -> false
        // A suspend function returns Object: answer what its continuation
        // expects — Unit for a write, an empty list for a list read, null (no
        // row) for everything else.
        else -> inertSuspendResult(method)
    }
} as T

/** The completed result an inert suspend DAO call hands back (see [inertDao]). */
fun inertSuspendResult(method: java.lang.reflect.Method): Any? {
    val cont = method.genericParameterTypes.lastOrNull() as? java.lang.reflect.ParameterizedType
        ?: return Unit
    val arg = cont.actualTypeArguments.firstOrNull()
    val t = (arg as? java.lang.reflect.WildcardType)?.lowerBounds?.firstOrNull() ?: arg
    val raw = (t as? java.lang.reflect.ParameterizedType)?.rawType ?: t
    return when (raw) {
        Unit::class.java -> Unit
        List::class.java, java.util.List::class.java -> emptyList<Any>()
        java.lang.Integer::class.java -> 0
        java.lang.Long::class.java -> 0L
        java.lang.Boolean::class.java -> false
        else -> null
    }
}

/** [BtDatabase] with every DAO inert and no open helper (it is never opened). */
class InertDatabase : BtDatabase() {
    override fun portfolioDao(): PortfolioDao = inertDao()
    override fun holdingDao(): HoldingDao = inertDao()
    override fun transactionDao(): TransactionDao = inertDao()
    override fun portfolioHistoryDao(): PortfolioHistoryDao = inertDao()
    override fun cashDao(): CashDao = inertDao()
    override fun cashTagDao(): CashTagDao = inertDao()
    override fun customAssetDao(): CustomAssetDao = inertDao()
    override fun watchlistDao(): WatchlistDao = inertDao()
    override fun conglomerateDao(): ConglomerateDao = inertDao()
    override fun syncOpDao(): SyncOpDao = inertDao()
    override fun metaDao(): MetaDao = inertDao()
    override fun vaultDao(): VaultDao = inertDao()
    override fun priceCacheDao(): PriceCacheDao = inertDao()
    override fun pvVaultSyncDao(): PvVaultSyncDao = inertDao()
    override fun createInvalidationTracker(): InvalidationTracker = error("not opened in a JVM test")
    override fun clearAllTables() = Unit
}
