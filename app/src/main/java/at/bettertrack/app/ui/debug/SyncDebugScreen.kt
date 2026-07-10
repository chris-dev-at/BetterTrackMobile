package at.bettertrack.app.ui.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.VersionResponse
import at.bettertrack.app.data.api.dto.formatApiBuiltAtDate
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.shell.formatAsOf
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Step-5 debug screen (spec Step 5: "debug screen showing queue contents and a
 * manual drain now"): live outbound-queue contents with per-op status/error,
 * a parameterizable test op, manual drain, and the connectivity/data-age
 * readout. Debug-only reachable (via the component gallery). The user-facing
 * Pending-sync screen is Step 8 (§7.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncDebugScreen(onClose: () -> Unit, onOpenPendingSync: () -> Unit = {}) {
    val bt = BtTheme.colors
    val controller = AppGraph.syncDebugController
    val scope = rememberCoroutineScope()

    val ops by controller.ops.collectAsStateWithLifecycle(initialValue = emptyList())
    val portfolios by controller.portfolios.collectAsStateWithLifecycle(initialValue = emptyList())
    val online by controller.isOnline.collectAsStateWithLifecycle()
    val dataAgeMs by controller.dataAgeMs.collectAsStateWithLifecycle(initialValue = null)

    var showEnqueueDialog by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var testDataResult by remember { mutableStateOf<String?>(null) }
    var showApiCheck by remember { mutableStateOf(false) }
    var testPortfolioId by remember { mutableStateOf<String?>(null) }

    // Default the test-data portfolio selection to the E2E throwaway if present.
    LaunchedEffect(portfolios) {
        if (testPortfolioId == null || portfolios.none { it.id == testPortfolioId }) {
            testPortfolioId = (
                portfolios.firstOrNull { it.name == TEST_PORTFOLIO_NAME && it.archivedAt == null }
                    ?: portfolios.firstOrNull { it.archivedAt == null }
                )?.id
        }
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text("Sync queue", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { BtBadge("DEBUG", kind = BtBadgeKind.Gold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    online = online,
                    dataAgeMs = dataAgeMs,
                    pending = ops.count { it.status == OpStatus.PENDING.wire },
                    inFlight = ops.count { it.status == OpStatus.IN_FLIGHT.wire },
                    needsAttention = ops.count { it.status == OpStatus.NEEDS_ATTENTION.wire },
                    done = ops.count { it.status == OpStatus.DONE.wire },
                )
            }
            item {
                // Public GET /version — the running build of the live server (fail-soft).
                val apiBuild by produceState<VersionResponse?>(initialValue = null) {
                    value = (AppGraph.buildInfoRepository.apiBuild() as? BtResult.Ok)?.value
                }
                BtCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("API build (live server)", style = MaterialTheme.typography.labelMedium, color = bt.textMuted)
                        val info = apiBuild
                        val line = if (info == null) {
                            "—"
                        } else {
                            val sc = info.shortCommit.ifBlank { info.commit.take(7) }
                            val d = formatApiBuiltAtDate(info.builtAt)
                            if (d.isBlank()) sc else "$sc · $d"
                        }
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary, fontFamily = FontFamily.Monospace)
                        info?.commit?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = bt.textMuted, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BtPrimaryButton(
                        text = "Drain now",
                        onClick = { controller.drainNow() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BtSecondaryButton(
                            text = "Enqueue test op",
                            onClick = { showEnqueueDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        )
                        BtSecondaryButton(
                            text = if (refreshing) "Refreshing…" else "Refresh portfolios",
                            onClick = {
                                scope.launch {
                                    refreshing = true
                                    controller.refreshPortfolios()
                                    refreshing = false
                                }
                            },
                            enabled = !refreshing,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        )
                    }
                    // Step 8: the user-facing §7.4 screen, reachable from here too.
                    BtSecondaryButton(
                        text = "Open Pending-sync screen",
                        onClick = onOpenPendingSync,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    )
                }
            }
            item {
                TestDataCard(
                    portfolios = portfolios,
                    selectedId = testPortfolioId,
                    onSelect = { testPortfolioId = it },
                    result = testDataResult,
                    onCreate = {
                        scope.launch {
                            testDataResult = "Creating…"
                            testDataResult = controller.createTestPortfolio(TEST_PORTFOLIO_NAME)
                        }
                    },
                    onApiCheck = { showApiCheck = true },
                    onCleanup = {
                        val pid = testPortfolioId ?: return@TestDataCard
                        scope.launch {
                            testDataResult = "Cleaning up…"
                            testDataResult = controller.cleanupTestData(pid)
                        }
                    },
                )
            }
            item {
                Text(
                    text = "OUTBOUND QUEUE (FIFO)",
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (ops.isEmpty()) {
                item {
                    BtCard(modifier = Modifier.fillMaxWidth()) {
                        BtEmptyState(
                            title = "Queue is empty",
                            message = "Enqueue a test op to watch it drain.",
                        )
                    }
                }
            } else {
                items(count = ops.size, key = { ops[it].id }) { index ->
                    OpCard(
                        op = ops[index],
                        onRetry = { scope.launch { controller.retry(ops[index].id) } },
                        onDiscard = { scope.launch { controller.discard(ops[index].id) } },
                    )
                }
            }
        }
    }

    if (showEnqueueDialog) {
        EnqueueTestOpDialog(
            portfolios = portfolios,
            prefillAssetId = { controller.anyKnownAssetId() },
            onDismiss = { showEnqueueDialog = false },
            onEnqueue = { portfolioId, assetId, side, qty, price, note, payFromCash ->
                scope.launch {
                    controller.enqueueTestOp(portfolioId, assetId, side, qty, price, note, payFromCash)
                }
                showEnqueueDialog = false
            },
        )
    }

    if (showApiCheck) {
        val pid = testPortfolioId
        ApiCheckDialog(
            portfolioName = portfolios.firstOrNull { it.id == pid }?.name ?: "—",
            fetch = {
                if (pid == null) {
                    at.bettertrack.app.data.api.BtResult.Ok(emptyList())
                } else {
                    controller.fetchLiveTransactions(pid)
                }
            },
            onDismiss = { showApiCheck = false },
        )
    }
}

/** Name of the throwaway E2E portfolio (test-data hygiene, Step 5). */
private const val TEST_PORTFOLIO_NAME = "ZZ App Test"

@Composable
private fun TestDataCard(
    portfolios: List<PortfolioEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    result: String?,
    onCreate: () -> Unit,
    onApiCheck: () -> Unit,
    onCleanup: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "TEST DATA (E2E)",
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
                letterSpacing = 1.2.sp,
            )
            if (portfolios.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    portfolios.filter { it.archivedAt == null }.forEach { p ->
                        BtChip(
                            text = p.name,
                            selected = selectedId == p.id,
                            onClick = { onSelect(p.id) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BtSecondaryButton(
                    text = "Create \"$TEST_PORTFOLIO_NAME\"",
                    onClick = onCreate,
                    modifier = Modifier.weight(1.2f),
                )
                BtSecondaryButton(
                    text = "API check",
                    onClick = onApiCheck,
                    enabled = selectedId != null,
                    modifier = Modifier.weight(0.8f),
                )
            }
            BtSecondaryButton(
                text = "Clean up test data (delete tx + archive)",
                onClick = onCleanup,
                enabled = selectedId != null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (result != null) {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.goldEmphasis,
                )
            }
        }
    }
}

@Composable
private fun ApiCheckDialog(
    portfolioName: String,
    fetch: suspend () -> at.bettertrack.app.data.api.BtResult<List<at.bettertrack.app.data.api.dto.TransactionDto>>,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var state by remember {
        mutableStateOf<at.bettertrack.app.data.api.BtResult<List<at.bettertrack.app.data.api.dto.TransactionDto>>?>(null)
    }
    LaunchedEffect(Unit) { state = fetch() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text("Live API — $portfolioName") },
        text = {
            when (val s = state) {
                null -> Text("Fetching GET /transactions…")

                is at.bettertrack.app.data.api.BtResult.Err ->
                    Text("Failed: HTTP ${s.error.httpStatus} ${s.error.code} — ${s.error.userMessage}", color = bt.loss)

                is at.bettertrack.app.data.api.BtResult.Ok -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "${s.value.size} transaction(s) on the server:",
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                    )
                    if (s.value.isEmpty()) {
                        Text("(none)", color = bt.textMuted)
                    }
                    s.value.take(8).forEach { tx ->
                        Text(
                            text = "${tx.id.take(8)}… ${tx.side} ${tx.quantity} × ${tx.price} " +
                                "${tx.asset.symbol}\n  asset: ${tx.assetId}\n  note: ${tx.note ?: "—"}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = bt.textSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = bt.gold) }
        },
    )
}

@Composable
private fun StatusCard(
    online: Boolean,
    dataAgeMs: Long?,
    pending: Int,
    inFlight: Int,
    needsAttention: Int,
    done: Int,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (online) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                    contentDescription = if (online) "Connectivity: online" else "Connectivity: offline",
                    tint = if (online) bt.gain else bt.loss,
                    modifier = Modifier.width(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (online) "Online" else "Offline",
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                BtBadge(
                    text = if (online) "CONNECTED" else "NO NETWORK",
                    kind = if (online) BtBadgeKind.Gain else BtBadgeKind.Loss,
                )
            }
            StatusRow("Portfolio data as of", dataAgeMs?.let(::formatAsOf) ?: "never synced")
            StatusRow(
                "Queue",
                "$pending pending · $inFlight in flight · $needsAttention needs attention · $done done",
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = bt.textSecondary)
    }
}

@Composable
private fun OpCard(
    op: SyncOpEntity,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    val bt = BtTheme.colors
    val status = OpStatus.fromWire(op.status)
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = opTypeLabel(op.opType),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                StatusBadge(status)
            }
            Text(
                text = "#${op.id} · ${op.clientId.take(8)} · ${op.attemptCount} attempt(s)" +
                    " · queued ${formatTime(op.createdAtMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Text(
                text = op.payloadJson,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = bt.textSecondary,
                maxLines = 3,
            )
            if (status == OpStatus.NEEDS_ATTENTION && op.serverError != null) {
                Text(
                    text = op.serverError,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetry) { Text("Retry", color = bt.gold) }
                    TextButton(onClick = onDiscard) { Text("Discard", color = bt.loss) }
                }
            }
            if (status == OpStatus.PENDING && op.nextAttemptAtMs > System.currentTimeMillis()) {
                Text(
                    text = "Backing off — next attempt ${formatTime(op.nextAttemptAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.goldEmphasis,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: OpStatus?) {
    when (status) {
        OpStatus.PENDING -> BtBadge("PENDING", kind = BtBadgeKind.Neutral)
        OpStatus.IN_FLIGHT -> BtBadge("IN FLIGHT", kind = BtBadgeKind.Gold)
        OpStatus.NEEDS_ATTENTION -> BtBadge("NEEDS ATTENTION", kind = BtBadgeKind.Loss)
        OpStatus.DONE -> BtBadge("DONE", kind = BtBadgeKind.Gain)
        null -> BtBadge("UNKNOWN", kind = BtBadgeKind.Neutral)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnqueueTestOpDialog(
    portfolios: List<PortfolioEntity>,
    prefillAssetId: suspend () -> String?,
    onDismiss: () -> Unit,
    onEnqueue: (
        portfolioId: String,
        assetId: String,
        side: String,
        quantity: Double,
        price: Double,
        note: String?,
        payFromCash: Boolean,
    ) -> Unit,
) {
    val bt = BtTheme.colors
    var selectedPortfolioId by remember {
        mutableStateOf(portfolios.firstOrNull { it.archivedAt == null }?.id)
    }
    var assetId by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("buy") }
    var quantity by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("1.00") }
    var note by remember { mutableStateOf("BT app test op") }
    var payFromCash by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (assetId.isBlank()) assetId = prefillAssetId() ?: ""
    }

    val qtyValue = quantity.replace(',', '.').toDoubleOrNull()
    val priceValue = price.replace(',', '.').toDoubleOrNull()
    val valid = selectedPortfolioId != null && assetId.isNotBlank() &&
        qtyValue != null && qtyValue > 0 && priceValue != null && priceValue >= 0

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = bt.gold,
        unfocusedBorderColor = bt.borderStrong,
        focusedLabelColor = bt.gold,
        unfocusedLabelColor = bt.textMuted,
        focusedTextColor = bt.textPrimary,
        unfocusedTextColor = bt.textPrimary,
        cursorColor = bt.gold,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text("Enqueue test op") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Portfolio",
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                )
                if (portfolios.isEmpty()) {
                    Text(
                        "No portfolios cached — run \"Refresh portfolios\" first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.loss,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        portfolios.filter { it.archivedAt == null }.forEach { p ->
                            BtChip(
                                text = p.name,
                                selected = selectedPortfolioId == p.id,
                                onClick = { selectedPortfolioId = p.id },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BtChip(text = "Buy", selected = side == "buy", onClick = { side = "buy" })
                    BtChip(text = "Sell", selected = side == "sell", onClick = { side = "sell" })
                }
                OutlinedTextField(
                    value = assetId,
                    onValueChange = { assetId = it },
                    label = { Text("Asset id (UUID)") },
                    singleLine = true,
                    colors = fieldColors,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Asset id" },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Quantity") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text("Price €") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Pay from cash (buys)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textSecondary,
                    )
                    Switch(
                        checked = payFromCash,
                        onCheckedChange = { payFromCash = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = bt.gold,
                            checkedThumbColor = bt.onGold,
                            uncheckedTrackColor = bt.surface,
                            uncheckedThumbColor = bt.textMuted,
                            uncheckedBorderColor = bt.borderStrong,
                        ),
                        modifier = Modifier.semantics { contentDescription = "Pay from cash" },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onEnqueue(
                        selectedPortfolioId ?: return@TextButton,
                        assetId.trim(),
                        side,
                        qtyValue ?: return@TextButton,
                        priceValue ?: return@TextButton,
                        note.takeIf { it.isNotBlank() },
                        payFromCash,
                    )
                },
            ) {
                Text("Enqueue", color = if (valid) bt.gold else bt.textMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = bt.textSecondary) }
        },
    )
}

private fun opTypeLabel(wire: String): String = when (OpType.fromWire(wire)) {
    OpType.TX_BUY -> "Buy transaction"
    OpType.TX_SELL -> "Sell transaction"
    OpType.CASH_DEPOSIT -> "Cash deposit"
    OpType.CASH_WITHDRAW -> "Cash withdrawal"
    OpType.CASH_TRANSFER -> "Cash transfer"
    OpType.CUSTOM_ASSET_VALUE_POINT -> "Custom-asset value point"
    null -> wire
}

private fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
