package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.drive.BackupManifestPreview
import com.example.data.drive.BackupVersion
import com.example.data.drive.BackupVersionKind
import com.example.data.drive.DriveSyncState
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.BordeauxSoftContainer
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftRed
import com.example.ui.theme.SoftRedContainer
import com.example.viewmodel.BackupPreviewResult
import com.example.viewmodel.BackupVersionsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** O que a tela sabe sobre "ver o que tem dentro" de uma versão. */
private sealed class VersionPreviewUi {
    object Loading : VersionPreviewUi()
    data class Loaded(val preview: BackupManifestPreview) : VersionPreviewUi()
    data class Failed(val message: String) : VersionPreviewUi()
}

private val versionDateFormat get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

private fun versionTitle(kind: BackupVersionKind): String = when (kind) {
    BackupVersionKind.CURRENT -> "Backup atual"
    BackupVersionKind.CLOUD_BEFORE_OVERWRITE -> "Cópia da nuvem, guardada antes de ser substituída"
    BackupVersionKind.DEVICE_BEFORE_RESTORE -> "Cópia deste aparelho, guardada antes de uma restauração"
}

private fun formatVersionSize(bytes: Long): String =
    if (bytes < 1024L * 1024L) "${maxOf(1L, bytes / 1024)} KB"
    else String.format(Locale("pt", "BR"), "%.1f MB", bytes / (1024.0 * 1024.0))

private fun previewSummary(p: BackupManifestPreview): String = buildString {
    append("${p.glucoseCount} glicemias • ${p.medicationCount} remédios • ${p.appointmentCount} consultas • ")
    append("${p.documentCount} exames • ${p.vaccineCount} vacinas • ${p.feedingCount} refeições • ")
    append("${p.weightCount} pesos • ${p.reminderCount} lembretes")
    if (p.attachmentCount > 0) append(" • ${p.attachmentCount} anexo(s)")
}

/**
 * Tela cheia "Versões do backup": lista o backup atual e as cópias datadas que o app guardou no Drive,
 * deixa ver o que há dentro de cada uma e voltar para uma delas.
 */
@Composable
fun BackupVersionsDialog(
    state: BackupVersionsState,
    syncState: DriveSyncState,
    actionInProgress: Boolean,
    onReload: () -> Unit,
    onPreview: (fileId: String, onResult: (BackupPreviewResult) -> Unit) -> Unit,
    onRestore: (fileId: String, replaceExisting: Boolean, onComplete: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val previews = remember { mutableStateMapOf<String, VersionPreviewUi>() }
    var restoreTarget by remember { mutableStateOf<BackupVersion?>(null) }
    var replaceExisting by remember { mutableStateOf(true) }
    // null = nenhuma tentativa; true/false = resultado da última restauração feita nesta tela
    var lastRestoreOk by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { onReload() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("backup_versions_screen"),
            color = CreamOffWhite
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Versões do backup",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = BordeauxDark,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("backup_versions_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = BordeauxDark)
                    }
                }

                Text(
                    text = "O app guarda uma cópia datada antes de substituir a nuvem ou de restaurar dados, e nunca apaga essas cópias.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (actionInProgress) {
                    val message = when (syncState) {
                        is DriveSyncState.Restoring -> syncState.message
                        is DriveSyncState.Syncing -> syncState.message
                        else -> "Restaurando… isso pode levar alguns instantes."
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = BordeauxPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = message, style = MaterialTheme.typography.bodySmall, color = BordeauxPrimary)
                    }
                }

                when (lastRestoreOk) {
                    true -> if (!actionInProgress) {
                        val detail = when (syncState) {
                            is DriveSyncState.RestoreSuccess ->
                                "${syncState.recordsCount} registros e ${syncState.filesCount} arquivos restaurados."
                            is DriveSyncState.RestorePartial ->
                                "${syncState.recordsCount} registros restaurados; " +
                                    "${syncState.failedFiles.size} arquivo(s) não puderam ser baixados: " +
                                    syncState.failedFiles.joinToString(", ")
                            else -> ""
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("backup_versions_success"),
                            shape = RoundedCornerShape(10.dp),
                            color = BordeauxSoftContainer
                        ) {
                            Text(
                                text = "Versão restaurada. $detail Na próxima sincronização ela passa a ser o backup atual.",
                                style = MaterialTheme.typography.bodySmall,
                                color = BordeauxDark,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    false -> if (!actionInProgress) {
                        val reason = (syncState as? DriveSyncState.Error)?.message.orEmpty()
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("backup_versions_failure"),
                            shape = RoundedCornerShape(10.dp),
                            color = SoftRedContainer,
                            border = BorderStroke(1.dp, SoftRed.copy(alpha = 0.5f))
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Warning, null, tint = SoftRed, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Não foi concluído. Nada foi trocado neste aparelho. $reason".trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BordeauxDark
                                )
                            }
                        }
                    }
                    null -> Unit
                }

                when (state) {
                    is BackupVersionsState.Idle, is BackupVersionsState.Loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = BordeauxPrimary, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Carregando as versões…", color = GraySecondary)
                        }
                    }

                    is BackupVersionsState.Error -> {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SoftRed,
                                modifier = Modifier.testTag("backup_versions_error")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onReload,
                                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                                modifier = Modifier.testTag("backup_versions_retry_button")
                            ) {
                                Text("Tentar de novo", color = PureWhite)
                            }
                        }
                    }

                    is BackupVersionsState.Loaded -> {
                        if (state.versions.isEmpty()) {
                            Text(
                                text = "Ainda não há backup na nuvem.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = GraySecondary,
                                modifier = Modifier
                                    .padding(16.dp)
                                    .testTag("backup_versions_empty")
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(state.versions, key = { it.fileId }) { version ->
                                    VersionCard(
                                        version = version,
                                        preview = previews[version.fileId],
                                        actionInProgress = actionInProgress,
                                        onTogglePreview = {
                                            if (previews[version.fileId] != null) {
                                                previews.remove(version.fileId)
                                            } else {
                                                previews[version.fileId] = VersionPreviewUi.Loading
                                                onPreview(version.fileId) { result ->
                                                    previews[version.fileId] = when (result) {
                                                        is BackupPreviewResult.Loaded -> VersionPreviewUi.Loaded(result.preview)
                                                        is BackupPreviewResult.Failed -> VersionPreviewUi.Failed(result.message)
                                                    }
                                                }
                                            }
                                        },
                                        onRestoreClick = {
                                            replaceExisting = true
                                            restoreTarget = version
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    restoreTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = {
                Text(
                    text = "Restaurar esta versão?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
            },
            text = {
                Column {
                    Text(
                        text = "${versionTitle(target.kind)}" +
                            (target.createdAtMillis?.let { " — ${versionDateFormat.format(Date(it))}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = BordeauxDark
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Antes de mexer em qualquer coisa, o app guarda cópias datadas no Drive: dos dados deste aparelho " +
                            "(se houver) e, se esta não for a versão atual, do backup atual da nuvem. Nada é apagado da nuvem. " +
                            "Na próxima sincronização, esta versão passa a ser o backup atual. " +
                            "Se alguma cópia falhar, nada é restaurado.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GraySecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ReplaceOption(
                        selected = replaceExisting,
                        title = "Substituir os dados deste aparelho",
                        onSelect = { replaceExisting = true },
                        tag = "restore_option_replace"
                    )
                    ReplaceOption(
                        selected = !replaceExisting,
                        title = "Adicionar a este aparelho sem apagar o que já existe",
                        onSelect = { replaceExisting = false },
                        tag = "restore_option_add"
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fileId = target.fileId
                        val replace = replaceExisting
                        restoreTarget = null
                        lastRestoreOk = null
                        onRestore(fileId, replace) { ok ->
                            lastRestoreOk = ok
                            if (ok) onReload()
                        }
                    },
                    enabled = !actionInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                    modifier = Modifier.testTag("restore_version_confirm_button")
                ) {
                    Text("Restaurar", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) {
                    Text("Cancelar", color = BordeauxDark)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = CreamOffWhite
        )
    }
}

@Composable
private fun ReplaceOption(selected: Boolean, title: String, onSelect: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.bodySmall, color = BordeauxDark)
    }
}

@Composable
private fun VersionCard(
    version: BackupVersion,
    preview: VersionPreviewUi?,
    actionInProgress: Boolean,
    onTogglePreview: () -> Unit,
    onRestoreClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backup_version_item"),
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = versionTitle(version.kind),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BordeauxDark
            )
            val date = version.createdAtMillis?.let { versionDateFormat.format(Date(it)) } ?: "data desconhecida"
            val size = version.sizeBytes?.let { " • ${formatVersionSize(it)}" }.orEmpty()
            Text(
                text = "$date$size",
                style = MaterialTheme.typography.bodySmall,
                color = GraySecondary
            )

            when (preview) {
                null -> Unit
                is VersionPreviewUi.Loading -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = BordeauxPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lendo o conteúdo…", style = MaterialTheme.typography.bodySmall, color = GraySecondary)
                    }
                }
                is VersionPreviewUi.Failed -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = preview.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = SoftRed
                    )
                }
                is VersionPreviewUi.Loaded -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Paciente: ${preview.preview.profileName} • Salvo em: ${preview.preview.exportDate}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = BordeauxDark
                    )
                    Text(
                        text = previewSummary(preview.preview),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = BordeauxDark,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTogglePreview,
                    border = BorderStroke(1.dp, BordeauxPrimary.copy(alpha = 0.5f)),
                    modifier = Modifier.testTag("backup_version_preview_button")
                ) {
                    Text(
                        text = if (preview == null) "Ver o que tem dentro" else "Esconder",
                        fontSize = 12.sp,
                        color = BordeauxDark
                    )
                }
                Button(
                    onClick = onRestoreClick,
                    enabled = !actionInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                    modifier = Modifier.testTag("backup_version_restore_button")
                ) {
                    Text("Restaurar esta versão", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
