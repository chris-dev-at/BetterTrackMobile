package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wire-level tests of the mirrorchain **participation** repository, driven
 * through a real MockWebServer so the DTOs, the domain mapping and the enum
 * fallbacks are all exercised the way the device exercises them.
 *
 * The three things worth pinning down here are the ones a screen cannot recover
 * from: a null `userId` (deleted account, membership row survives), a null
 * `portfolioId` (the local copy is gone — never navigable), and `sync.percent`,
 * which is taken from the wire and clamped, never derived from
 * `appliedSeq/lastSeq` — a fresh chain has `lastSeq == 0` and that division is
 * the classic NaN.
 */
class MirrorchainWireTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: MirrorchainRepository

    // The app's production Json config (see di/AppGraph).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = MirrorchainRepository(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── chains ───────────────────────────────────────────────────────────────

    @Test
    fun `chains decode with a null portfolioId and keep the server's sync percent`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"chains":[
                  {"chainId":"c1","name":"Family","status":"active","portfolioId":null,
                   "role":"owner","memberCount":4,
                   "sync":{"appliedSeq":12,"lastSeq":12,"percent":100,"synced":true},
                   "createdAt":"2026-07-01T10:00:00.000Z"}
                ]}
                """.trimIndent(),
            ),
        )

        val chains = (repo.chains() as BtResult.Ok).value
        val chain = chains.single()
        assertEquals("c1", chain.chainId)
        assertEquals("Family", chain.name)
        assertEquals(MirrorChainStatus.Active, chain.status)
        // The local copy is gone: null must survive as null so no screen ever
        // treats it as navigable.
        assertNull(chain.portfolioId)
        assertEquals(MirrorRole.Owner, chain.role)
        assertEquals(4, chain.memberCount)
        assertEquals(100, chain.sync.percent)
        assertTrue(chain.sync.synced)
        assertEquals("/mirrorchain/chains", server.takeRequest().path)
    }

    @Test
    fun `a fresh chain reports 100 percent from the wire even though lastSeq is zero`() =
        runBlocking {
            // Deriving appliedSeq/lastSeq here would be 0/0 — a NaN. The server
            // already resolved it; the app only reads.
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"chains":[
                      {"chainId":"c2","name":"Fresh","status":"active","portfolioId":"p2",
                       "role":"member","memberCount":1,
                       "sync":{"appliedSeq":0,"lastSeq":0,"percent":100,"synced":true},
                       "createdAt":"2026-08-01T10:00:00.000Z"}
                    ]}
                    """.trimIndent(),
                ),
            )

            val sync = (repo.chains() as BtResult.Ok).value.single().sync
            assertEquals(0, sync.appliedSeq)
            assertEquals(0, sync.lastSeq)
            assertEquals(100, sync.percent)
            assertTrue(sync.synced)
        }

    @Test
    fun `an out-of-range percent is clamped into 0 to 100, not passed through`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"chains":[
                  {"chainId":"hi","name":"High","status":"active","portfolioId":"p",
                   "role":"member","memberCount":2,
                   "sync":{"appliedSeq":9,"lastSeq":8,"percent":112,"synced":false},
                   "createdAt":"2026-08-01T10:00:00.000Z"},
                  {"chainId":"lo","name":"Low","status":"active","portfolioId":"p",
                   "role":"member","memberCount":2,
                   "sync":{"appliedSeq":0,"lastSeq":8,"percent":-4,"synced":false},
                   "createdAt":"2026-08-01T10:00:00.000Z"}
                ]}
                """.trimIndent(),
            ),
        )

        val chains = (repo.chains() as BtResult.Ok).value
        assertEquals(100, chains[0].sync.percent)
        assertEquals(0, chains[1].sync.percent)
    }

    @Test
    fun `an unknown chain status degrades to active rather than dropping the chain`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"chains":[
                      {"chainId":"c3","name":"Future","status":"hibernating","portfolioId":"p3",
                       "role":"member","memberCount":2,
                       "sync":{"appliedSeq":1,"lastSeq":1,"percent":100,"synced":true},
                       "createdAt":"2026-08-01T10:00:00.000Z"}
                    ]}
                    """.trimIndent(),
                ),
            )

            val chain = (repo.chains() as BtResult.Ok).value.single()
            assertEquals(MirrorChainStatus.Active, chain.status)
        }

    // ── members ──────────────────────────────────────────────────────────────

    @Test
    fun `members decode with a null userId and an unknown role falls back to member`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"chainId":"c1","name":"Family","status":"active","role":"manager","memberCap":16,
                     "members":[
                       {"userId":null,"username":"ghost","profileIcon":null,"role":"member",
                        "joinedAt":"2026-07-01T10:00:00.000Z","isSelf":false,
                        "sync":{"appliedSeq":3,"lastSeq":9,"percent":33,"synced":false}},
                       {"userId":"u2","username":"me","profileIcon":"fox","role":"manager",
                        "joinedAt":"2026-07-02T10:00:00.000Z","isSelf":true,
                        "sync":{"appliedSeq":9,"lastSeq":9,"percent":100,"synced":true}},
                       {"userId":"u3","username":"future","profileIcon":null,"role":"archivist",
                        "joinedAt":"2026-07-03T10:00:00.000Z","isSelf":false,
                        "sync":{"appliedSeq":9,"lastSeq":9,"percent":100,"synced":true}}
                     ]}
                    """.trimIndent(),
                ),
            )

            val roster = (repo.members("c1") as BtResult.Ok).value
            assertEquals(16, roster.memberCap)
            assertEquals(MirrorRole.Manager, roster.myRole)

            // The account is gone but the membership row is not — it must still
            // render, so the null id has to survive the mapping.
            val ghost = roster.members[0]
            assertNull(ghost.userId)
            assertEquals("ghost", ghost.username)
            assertFalse(ghost.sync.synced)
            assertEquals(33, ghost.sync.percent)

            val self = roster.members[1]
            assertTrue(self.isSelf)
            assertEquals(MirrorRole.Manager, self.role)

            // An unmodelled role must land on the LEAST authority, never the most.
            assertEquals(MirrorRole.Member, roster.members[2].role)
            assertEquals("/mirrorchain/chains/c1/members", server.takeRequest().path)
        }

    @Test
    fun `a 404 on members surfaces the chain-not-found code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"error":{"code":"MIRROR_CHAIN_NOT_FOUND","message":"Chain not found"}}""",
            ),
        )

        val r = repo.members("gone")
        assertEquals(
            MirrorchainRepository.CODE_CHAIN_NOT_FOUND,
            (r as BtResult.Err).error.code,
        )
    }

    // ── activity ─────────────────────────────────────────────────────────────

    @Test
    fun `an activity page carries a nextCursor and passes it back as before`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"entries":[
                  {"seq":42,"kind":"tx_buy","actorUsername":"anna",
                   "summary":"anna bought 3 AAPL","createdAt":"2026-08-01T09:00:00.000Z"}
                ],"nextCursor":42}
                """.trimIndent(),
            ),
        )

        val page = (repo.activity("c1") as BtResult.Ok).value
        assertEquals(42, page.nextCursor)
        // The server pre-renders the sentence; the app shows it verbatim.
        assertEquals("anna bought 3 AAPL", page.entries.single().summary)
        assertEquals("tx_buy", page.entries.single().kind)
        val first = server.takeRequest().path
        assertTrue("no cursor on the first page: $first", first!!.contains("limit=30"))
        assertFalse("no cursor on the first page: $first", first.contains("before="))

        server.enqueue(MockResponse().setBody("""{"entries":[],"nextCursor":null}"""))
        val older = (repo.activity("c1", before = page.nextCursor) as BtResult.Ok).value
        // Null cursor = start of the log; the "Load older" affordance disappears.
        assertNull(older.nextCursor)
        assertTrue(older.entries.isEmpty())
        assertTrue(server.takeRequest().path!!.contains("before=42"))
    }

    @Test
    fun `an activity page with a missing nextCursor decodes as null, not zero`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"entries":[]}"""))

        val page = (repo.activity("c1") as BtResult.Ok).value
        assertNull(page.nextCursor)
    }

    // ── invites ──────────────────────────────────────────────────────────────

    @Test
    fun `invites split by direction and tolerate a null fromUsername`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"incoming":[
                   {"id":"i1","chainId":"c1","chainName":"Family","fromUsername":"anna",
                    "toUsername":"me","direction":"incoming","createdAt":"2026-08-01T10:00:00.000Z"},
                   {"id":"i2","chainId":"c2","chainName":"Orphan","fromUsername":null,
                    "toUsername":"me","direction":"incoming","createdAt":"2026-08-02T10:00:00.000Z"}
                 ],
                 "outgoing":[
                   {"id":"o1","chainId":"c3","chainName":"Mine","fromUsername":"me",
                    "toUsername":"bob","direction":"outgoing","createdAt":"2026-08-03T10:00:00.000Z"}
                 ]}
                """.trimIndent(),
            ),
        )

        val invites = (repo.invites() as BtResult.Ok).value
        assertEquals(2, invites.incoming.size)
        assertEquals("anna", invites.incoming[0].fromUsername)
        assertNull(invites.incoming[1].fromUsername)
        assertEquals(listOf("o1"), invites.outgoing.map { it.id })
        assertFalse(invites.isEmpty)
    }

    @Test
    fun `no invites in either direction reads as empty`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"incoming":[],"outgoing":[]}"""))

        assertTrue((repo.invites() as BtResult.Ok).value.isEmpty)
    }

    @Test
    fun `accept returns the NEW local portfolio id`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"chainId":"c1","portfolioId":"p-new"}"""),
        )

        assertEquals("p-new", (repo.accept("i1") as BtResult.Ok).value)
        assertEquals("/mirrorchain/invites/i1/accept", server.takeRequest().path)
    }

    @Test
    fun `the four invite refusals keep their codes so the card can answer each one`() =
        runBlocking {
            val cases = listOf(
                404 to MirrorchainRepository.CODE_INVITE_NOT_FOUND,
                409 to MirrorchainRepository.CODE_MEMBER_CAP,
                400 to MirrorchainRepository.CODE_NOT_FRIENDS,
                503 to MirrorchainRepository.CODE_BUSY,
            )
            cases.forEach { (status, code) ->
                server.enqueue(
                    MockResponse().setResponseCode(status)
                        .setBody("""{"error":{"code":"$code","message":"nope"}}"""),
                )
                val r = repo.accept("i1")
                assertEquals(code, (r as BtResult.Err).error.code)
                assertEquals(status, r.error.httpStatus)
            }
        }

    @Test
    fun `decline and leave both accept the ok acknowledgement`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(repo.decline("i1") is BtResult.Ok)
        assertEquals("/mirrorchain/invites/i1/decline", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(repo.leave("c1") is BtResult.Ok)
        assertEquals("/mirrorchain/chains/c1/leave", server.takeRequest().path)
    }

    // ── enum fallbacks, directly ─────────────────────────────────────────────

    @Test
    fun `role fromWire maps the three real roles and floors everything else`() {
        assertEquals(MirrorRole.Owner, MirrorRole.fromWire("owner"))
        assertEquals(MirrorRole.Manager, MirrorRole.fromWire("manager"))
        assertEquals(MirrorRole.Member, MirrorRole.fromWire("member"))
        // Least authority for anything unknown — an unmodelled role must never
        // be mistaken for a privileged one.
        assertEquals(MirrorRole.Member, MirrorRole.fromWire("superuser"))
        assertEquals(MirrorRole.Member, MirrorRole.fromWire(null))
        assertEquals(MirrorRole.Member, MirrorRole.fromWire(""))
        assertEquals(MirrorRole.Member, MirrorRole.fromWire("Owner")) // case-sensitive wire
    }

    @Test
    fun `status fromWire maps both states and defaults to active`() {
        assertEquals(MirrorChainStatus.Active, MirrorChainStatus.fromWire("active"))
        assertEquals(MirrorChainStatus.Dissolved, MirrorChainStatus.fromWire("dissolved"))
        assertEquals(MirrorChainStatus.Active, MirrorChainStatus.fromWire(null))
        assertEquals(MirrorChainStatus.Active, MirrorChainStatus.fromWire("archived"))
    }
}
