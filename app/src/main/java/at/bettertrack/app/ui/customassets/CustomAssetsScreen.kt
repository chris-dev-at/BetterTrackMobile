package at.bettertrack.app.ui.customassets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.CustomAssetEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class CustomAssetsViewModel(
    private val repo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    val assets: StateFlow<List<CustomAssetEntity>> = repo.customAssets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { refresh() }

    /** Pull the authoritative custom-asset list (#387) so zero-holding assets appear. */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            repo.refreshCustomAssets()
            _refreshing.value = false
        }
    }

    fun createAsset(name: String, category: String, smoothing: Boolean, onDone: (Boolean) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val r = repo.createCustomAsset(name, category, smoothing, initial = null, portfolioId = null)
            if (r is BtResult.Err) _error.value = r.error.userMessage
            _busy.value = false
            onDone(r is BtResult.Ok)
        }
    }

    fun clearError() {
        _error.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomAssetsScreen(
    onBack: () -> Unit,
    onOpenAsset: (String) -> Unit,
) {
    val vm: CustomAssetsViewModel = viewModel {
        CustomAssetsViewModel(AppGraph.portfolioRepository, AppGraph.connectivityMonitor)
    }
    val bt = BtTheme.colors
    val assets by vm.assets.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    var createOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.bt_custom_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = bt.textPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                if (!isOnline) OfflineBanner(asOfMs = dataAgeMs)

                if (assets.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BtEmptyState(
                            icon = Icons.Outlined.Category,
                            title = stringResource(R.string.bt_custom_empty_title),
                            message = stringResource(R.string.bt_custom_empty_message),
                            action = {
                                BtPrimaryButton(
                                    text = stringResource(R.string.bt_custom_create),
                                    onClick = { createOpen = true },
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
                        items(count = assets.size, key = { assets[it].id }) { i ->
                            CustomAssetRow(assets[i], onClick = { onOpenAsset(assets[i].id) })
                        }
                    }
                }
            }

            val fabCd = stringResource(R.string.bt_custom_create)
            FloatingActionButton(
                onClick = { createOpen = true },
                containerColor = if (isOnline) bt.gold else bt.border,
                contentColor = if (isOnline) bt.onGold else bt.textMuted,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .semantics { contentDescription = fabCd },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
            }
        }
    }

    if (createOpen) {
        CustomAssetDialog(
            title = stringResource(R.string.bt_custom_create_title),
            confirmLabel = stringResource(R.string.bt_switcher_create_action),
            initialName = "",
            initialCategory = "other",
            initialSmoothing = false,
            busy = busy,
            error = error,
            onConfirm = { name, cat, smoothing ->
                vm.createAsset(name, cat, smoothing) { ok -> if (ok) createOpen = false }
            },
            onDismiss = {
                createOpen = false
                vm.clearError()
            },
        )
    }
}

@Composable
private fun CustomAssetRow(asset: CustomAssetEntity, onClick: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = asset.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = categoryLabel(asset.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }
    }
}

/** Shared create/edit dialog for a custom asset (name + category chips). */
@Composable
fun CustomAssetDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialCategory: String,
    initialSmoothing: Boolean,
    busy: Boolean,
    error: String?,
    onConfirm: (name: String, category: String, smoothing: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var name by rememberSaveable { mutableStateOf(initialName) }
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    var smoothing by rememberSaveable { mutableStateOf(initialSmoothing) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.bt_switcher_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    colors = dialogFieldColors(),
                )
                Text(
                    text = stringResource(R.string.bt_custom_category),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CUSTOM_ASSET_CATEGORIES.forEach { c ->
                        BtChip(
                            text = categoryLabel(c),
                            selected = category == c,
                            enabled = !busy,
                            onClick = { category = c },
                        )
                    }
                }
                // Value-smoothing toggle (V3-P2) — mirrors the web CustomInvestmentDialog.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { smoothing = !smoothing }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = smoothing,
                        onCheckedChange = { smoothing = it },
                        enabled = !busy,
                        colors = CheckboxDefaults.colors(
                            checkedColor = bt.gold,
                            uncheckedColor = bt.borderStrong,
                            checkmarkColor = bt.onGold,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.bt_custom_smoothing_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = bt.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.bt_custom_smoothing_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                }
                if (error != null) {
                    Text(text = error, style = MaterialTheme.typography.bodySmall, color = bt.loss)
                }
            }
        },
        confirmButton = {
            BtPrimaryButton(
                text = confirmLabel,
                onClick = { onConfirm(name, category, smoothing) },
                enabled = name.trim().isNotEmpty() && !busy,
                loading = busy,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

@Composable
fun categoryLabel(category: String?): String = when (category) {
    "stock" -> stringResource(R.string.bt_custom_cat_stock)
    "etf" -> stringResource(R.string.bt_custom_cat_etf)
    "crypto" -> stringResource(R.string.bt_custom_cat_crypto)
    "commodity" -> stringResource(R.string.bt_custom_cat_commodity)
    "cash_like" -> stringResource(R.string.bt_custom_cat_cash_like)
    "other", null -> stringResource(R.string.bt_custom_cat_other)
    else -> category.replaceFirstChar { it.uppercase() }
}

@Composable
fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BtTheme.colors.gold,
    unfocusedBorderColor = BtTheme.colors.borderStrong,
    errorBorderColor = BtTheme.colors.loss,
    focusedLabelColor = BtTheme.colors.gold,
    unfocusedLabelColor = BtTheme.colors.textMuted,
    focusedTextColor = BtTheme.colors.textPrimary,
    unfocusedTextColor = BtTheme.colors.textPrimary,
    cursorColor = BtTheme.colors.gold,
)
