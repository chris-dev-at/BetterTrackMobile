package at.bettertrack.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.ProfileSettingsResponse
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.rememberBtHaptics
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/** The server's cap on a bio. Mirrored here so the counter cannot drift from it. */
private const val PROFILE_BIO_MAX = 280

/**
 * Settings → Account → **Public profile**.
 *
 * The phone half of the web's profile panel: the public opt-in, its
 * acknowledgement, and the bio. Until now the app could only *echo* these two
 * fields back to the server when the user changed their avatar — it read them,
 * preserved them, and gave no way to edit them. That is exactly the gap the
 * owner's doctrine names: anything the server stores must be editable here too.
 *
 * ## The friction ladder, and where it applies
 *
 * Making a profile public is a §16 laddered action, so the warning and its
 * checkbox appear on the **off→on transition only** — that is the moment a
 * decision is being made, and re-asking on every later bio edit would train the
 * user to tick the box without reading it.
 *
 * The WIRE is a separate question: `acknowledgePublic` is sent on every call
 * that leaves the profile public, because the server re-checks it each time.
 * That asymmetry is deliberate and lives in
 * [at.bettertrack.app.data.account.AccountRepository.updateProfileVisibility].
 *
 * ## Why the save button is not a live toggle
 *
 * Everywhere else in Settings a switch writes immediately. Here it does not:
 * publishing a profile and writing a bio are one decision the user should be
 * able to review before it takes effect, and an instant-on switch beside an
 * unsaved bio would publish a page carrying the previous text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicProfileScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.accountRepository
    val scope = rememberCoroutineScope()
    val snackbar = LocalBtSnackbar.current
    val haptics = rememberBtHaptics()

    var server by remember { mutableStateOf<ProfileSettingsResponse?>(null) }
    var loadFailure by remember { mutableStateOf<BtMessage?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    // The draft. Seeded from the server read and never before it — a PUT built
    // from defaults would make a public profile private.
    var draftPublic by remember { mutableStateOf(false) }
    var draftBio by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var saveFailure by remember { mutableStateOf<BtMessage?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loaded = false
        when (val r = repo.socialProfile()) {
            is BtResult.Ok -> {
                server = r.value
                draftPublic = r.value.isPublic
                draftBio = r.value.bio.orEmpty()
                acknowledged = false
                loadFailure = null
            }

            is BtResult.Err -> loadFailure = r.error.asMessage()
        }
        loaded = true
    }

    val current = server
    val serverBio = current?.bio.orEmpty()
    val enabling = current != null && draftPublic && !current.isPublic
    val dirty = current != null &&
        (draftPublic != current.isPublic || draftBio.trim() != serverBio.trim())
    val canSave = dirty && !saving && (!enabling || acknowledged) && draftBio.length <= PROFILE_BIO_MAX

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_profile_dest),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                !loaded -> {
                    BtSkeleton(Modifier.fillMaxWidth().height(72.dp), shape = BtShapes.group)
                    BtSkeleton(Modifier.fillMaxWidth().height(120.dp), shape = BtShapes.card)
                }

                current == null -> BtScrollFill {
                    BtInlineError(message = loadFailure ?: BtMessage.generic, onRetry = { reload++ })
                }

                else -> {
                    BtGroup {
                        BtGroupRow(
                            icon = Icons.Outlined.Public,
                            title = stringResource(R.string.bt_profile_toggle),
                            subtitle = stringResource(
                                if (draftPublic) R.string.bt_profile_live
                                else R.string.bt_profile_off_hint,
                            ),
                            onClick = {
                                haptics.toggle(!draftPublic)
                                draftPublic = !draftPublic
                                // Withdrawing the choice withdraws the consent:
                                // an ack ticked, untoggled and re-toggled must be
                                // ticked again.
                                if (!draftPublic) acknowledged = false
                                saveFailure = null
                            },
                            trailing = {
                                Switch(
                                    checked = draftPublic,
                                    onCheckedChange = { on ->
                                        haptics.toggle(on)
                                        draftPublic = on
                                        if (!on) acknowledged = false
                                        saveFailure = null
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = bt.onGold,
                                        checkedTrackColor = bt.gold,
                                        checkedBorderColor = bt.gold,
                                        uncheckedThumbColor = bt.textMuted,
                                        uncheckedTrackColor = bt.surface,
                                        uncheckedBorderColor = bt.borderStrong,
                                    ),
                                )
                            },
                        )
                    }

                    if (current.isPublic && current.publicItemCount > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.bt_profile_public_items,
                                current.publicItemCount,
                                current.publicItemCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }

                    // The ladder rung. Only on the transition, and it gates the
                    // save button rather than merely warning beside it.
                    if (enabling) {
                        BtCard {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.WarningAmber,
                                        contentDescription = null,
                                        tint = bt.gold,
                                    )
                                    Text(
                                        text = stringResource(R.string.bt_profile_warning_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = bt.textPrimary,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.bt_profile_warning_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = bt.textSecondary,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(
                                            value = acknowledged,
                                            onValueChange = {
                                                haptics.toggle(it)
                                                acknowledged = it
                                            },
                                        ),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = acknowledged,
                                        onCheckedChange = null,
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = bt.gold,
                                            checkmarkColor = bt.onGold,
                                            uncheckedColor = bt.borderStrong,
                                        ),
                                    )
                                    Text(
                                        text = stringResource(R.string.bt_profile_acknowledge),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = bt.textPrimary,
                                    )
                                }
                            }
                        }
                    }

                    BtTextField(
                        value = draftBio,
                        onValueChange = {
                            // Hard cap at the field: the server truncates silently,
                            // and a bio that comes back shorter than the one typed
                            // is a surprise nobody asked for.
                            if (it.length <= PROFILE_BIO_MAX) draftBio = it
                            saveFailure = null
                        },
                        label = stringResource(R.string.bt_profile_bio),
                        supportingText = "${draftBio.length}/$PROFILE_BIO_MAX",
                        imeAction = ImeAction.Done,
                    )
                    Text(
                        text = stringResource(R.string.bt_profile_bio_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )

                    saveFailure?.let { BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp)) }

                    Spacer(Modifier.height(2.dp))
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_profile_save),
                        enabled = canSave,
                        loading = saving,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        onClick = {
                            saving = true
                            saveFailure = null
                            scope.launch {
                                val r = repo.updateProfileVisibility(
                                    current = current,
                                    isPublic = draftPublic,
                                    bio = draftBio,
                                )
                                when (r) {
                                    is BtResult.Ok -> {
                                        // Re-seed from the ECHO, not the draft: the
                                        // server trims and may refuse, and the screen
                                        // must show what is actually stored.
                                        server = r.value
                                        draftPublic = r.value.isPublic
                                        draftBio = r.value.bio.orEmpty()
                                        acknowledged = false
                                        snackbar.show(R.string.bt_profile_saved)
                                    }

                                    is BtResult.Err -> saveFailure = r.error.asMessage()
                                }
                                saving = false
                            }
                        },
                    )
                }
            }
        }
    }
}
