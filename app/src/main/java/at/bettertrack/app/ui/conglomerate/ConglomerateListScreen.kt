package at.bettertrack.app.ui.conglomerate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.Conglomerate
import at.bettertrack.app.data.repo.ConglomerateRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConglomerateListState {
    data object Loading : ConglomerateListState
    data class Loaded(val items: List<Conglomerate>) : ConglomerateListState
    data object OfflineState : ConglomerateListState
    data class Error(val message: String) : ConglomerateListState
}

class ConglomerateListViewModel(
    private val repo: ConglomerateRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline
    private val _state = MutableStateFlow<ConglomerateListState>(ConglomerateListState.Loading)
    val state: StateFlow<ConglomerateListState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            if (!isOnline.value) { _state.value = ConglomerateListState.OfflineState; return@launch }
            _state.value = ConglomerateListState.Loading
            _state.value = when (val r = repo.list()) {
                is BtResult.Ok -> ConglomerateListState.Loaded(r.value)
                is BtResult.Err -> if (r.error.isNetwork) ConglomerateListState.OfflineState
                else ConglomerateListState.Error(r.error.userMessage)
            }
        }
    }
}

@Composable
fun ConglomerateListScreen(
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val vm: ConglomerateListViewModel = viewModel {
        ConglomerateListViewModel(AppGraph.conglomerateRepository, AppGraph.connectivityMonitor)
    }
    val bt = BtTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()

    // Refresh when returning from the builder.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            ConglomerateListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = bt.gold)
            }

            ConglomerateListState.OfflineState -> BtEmptyState(
                icon = Icons.Outlined.Dashboard,
                title = stringResource(R.string.bt_requires_connection_title),
                message = stringResource(R.string.bt_conglo_requires_connection),
                modifier = Modifier.align(Alignment.Center),
            )

            is ConglomerateListState.Error -> BtErrorState(
                message = s.message,
                onRetry = { vm.load() },
                modifier = Modifier.align(Alignment.Center),
            )

            is ConglomerateListState.Loaded -> if (s.items.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    BtEmptyState(
                        icon = Icons.Outlined.Dashboard,
                        title = stringResource(R.string.bt_conglo_empty_title),
                        message = stringResource(R.string.bt_conglo_empty_message),
                        action = {
                            BtPrimaryButton(
                                text = stringResource(R.string.bt_conglo_create),
                                onClick = onCreate,
                                enabled = isOnline,
                            )
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = s.items.size, key = { s.items[it].id }) { i ->
                        ConglomerateRow(s.items[i], onClick = { onOpen(s.items[i].id) })
                    }
                }
            }
        }

        val fabCd = stringResource(R.string.bt_conglo_create)
        FloatingActionButton(
            onClick = onCreate,
            containerColor = if (isOnline) bt.gold else bt.border,
            contentColor = if (isOnline) bt.onGold else bt.textMuted,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).semantics { contentDescription = fabCd },
        ) { Icon(Icons.Outlined.Add, contentDescription = null) }
    }
}

@Composable
private fun ConglomerateRow(item: Conglomerate, onClick: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                    Spacer(Modifier.size(8.dp))
                    if (item.status == "draft") {
                        BtBadge(text = stringResource(R.string.bt_conglo_draft), kind = BtBadgeKind.Neutral)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (item.positionCount == 1) {
                        stringResource(R.string.bt_conglo_assets_one)
                    } else {
                        stringResource(R.string.bt_conglo_assets_n, item.positionCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}
