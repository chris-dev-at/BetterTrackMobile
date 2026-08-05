package at.bettertrack.app.ui.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.WatchlistBoard
import at.bettertrack.app.data.repo.WatchlistRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.btPressScale
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Suppress("OPT_IN_USAGE")
class BoardPickerViewModel(
    private val watchlist: WatchlistRepository,
    private val assetId: String,
) : ViewModel() {

    val boards: StateFlow<List<WatchlistBoard>> = watchlist.boards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** boardId → whether [assetId] is already on it (drives the check state). */
    val memberships: StateFlow<Map<String, Boolean>> = watchlist.boards
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    list.map { b -> watchlist.items(b.id).map { items -> b.id to items.any { it.assetId == assetId } } },
                ) { pairs -> pairs.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _busy = MutableStateFlow(false)

    /**
     * The refusal from the last add/remove, or null when the last one landed.
     *
     * Both calls return a [BtResult] and both results were discarded, so a
     * server-side refusal (or a dropped connection) left the row's check exactly
     * where it was with nothing said. Silence is the worst possible answer here:
     * the check state IS the feedback, so a failure that changes nothing is
     * indistinguishable from a tap that never registered.
     */
    private val _failure = MutableStateFlow<BtMessage?>(null)
    val failure: StateFlow<BtMessage?> = _failure.asStateFlow()

    /** The board whose toggle failed — what Retry re-runs. */
    private var lastFailedBoardId: String? = null

    fun toggle(boardId: String, asset: MarketAsset) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _failure.value = null
            val inList = memberships.value[boardId] == true
            val result = if (inList) {
                watchlist.removeAsset(boardId, asset.id)
            } else {
                watchlist.addAsset(boardId, asset)
            }
            if (result is BtResult.Err) {
                lastFailedBoardId = boardId
                _failure.value = result.asMessage()
            }
            _busy.value = false
        }
    }

    /** Re-run the toggle that failed — the same board, the same asset, same intent. */
    fun retryFailed(asset: MarketAsset) {
        val boardId = lastFailedBoardId ?: return
        toggle(boardId, asset)
    }
}

/**
 * "Add to watchlist" list-picker (§6.6) — every add flow (search row, asset
 * page) opens this. General (the real list) is one tap; other named lists are a
 * choose-list. A filled check shows current membership; tapping toggles it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardPickerSheet(
    asset: MarketAsset,
    onDismiss: () -> Unit,
) {
    val vm: BoardPickerViewModel = viewModel(key = "boardpicker-${asset.id}") {
        BoardPickerViewModel(AppGraph.watchlistRepository, asset.id)
    }
    val bt = BtTheme.colors
    val boards by vm.boards.collectAsStateWithLifecycle()
    val memberships by vm.memberships.collectAsStateWithLifecycle()
    val failure by vm.failure.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.bt_watchlist_add_to),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            Text(
                text = "${asset.symbol} · ${asset.name}",
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(12.dp))
            if (boards.isEmpty()) {
                // The lists come from Room, so this is usually one frame — but a
                // sheet that opens completely blank reads as broken, and the
                // placeholder costs nothing.
                repeat(2) {
                    BtSkeleton(
                        Modifier.fillMaxWidth().height(54.dp),
                        shape = at.bettertrack.app.ui.theme.BtShapes.card,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            boards.forEach { board ->
                val inList = memberships[board.id] == true
                BoardRow(board, inList) { vm.toggle(board.id, asset) }
                Spacer(Modifier.height(8.dp))
            }
            val toggleFailure = failure
            if (toggleFailure != null) {
                BtInlineError(
                    message = toggleFailure,
                    // Honest retry: the row did not move, so re-running the same
                    // toggle is exactly the tap the user already made.
                    onRetry = { vm.retryFailed(asset) },
                )
            }
        }
    }
}

@Composable
private fun BoardRow(board: WatchlistBoard, inList: Boolean, onClick: () -> Unit) {
    val bt = BtTheme.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = at.bettertrack.app.ui.theme.BtShapes.card,
        color = bt.bgAlt,
        border = androidx.compose.foundation.BorderStroke(1.dp, bt.border),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().btPressScale(interaction, pressedScale = 0.985f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (inList) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (inList) bt.gold else bt.textMuted,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(board.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = bt.textPrimary, modifier = Modifier.weight(1f))
            if (board.isDefault) {
                BtBadge(text = stringResource(R.string.bt_watchlist_default), kind = BtBadgeKind.Gold)
            } else if (!inList) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}
