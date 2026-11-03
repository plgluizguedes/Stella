package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.attachments.AddAttachmentResult
import com.example.data.attachments.AttachmentLimits
import com.example.data.model.Attachment
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.SoftRed
import com.example.ui.theme.SoftRedContainer
import kotlinx.coroutines.flow.Flow

/**
 * Tudo o que as telas de anexos de registros que já existem precisam do ViewModel, num pacote só
 * (as telas de saúde não recebem o ViewModel). Passar `null` esconde a parte de anexos.
 */
class AttachmentActions(
    val attachmentsFor: (ownerType: String, ownerId: Long) -> Flow<List<Attachment>>,
    val add: (ownerType: String, ownerId: Long, uri: String, label: String, onResult: (AddAttachmentResult) -> Unit) -> Unit,
    val remove: (attachmentId: Long, onDone: (Boolean) -> Unit) -> Unit,
    val openIntent: (Attachment) -> Intent?,
    val refreshBroken: () -> Unit,
    val allAttachments: List<Attachment>,
    val brokenIds: Set<Long>
) {
    fun countFor(ownerType: String, ownerId: Long): Int =
        allAttachments.count { it.ownerType == ownerType && it.ownerId == ownerId }
}

/** Clipe com a contagem de anexos (some quando é zero). */
@Composable
fun AttachmentCountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Row(modifier = modifier.testTag("attachment_count_badge"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.AttachFile, contentDescription = "Anexos", tint = BordeauxPrimary, modifier = Modifier.size(14.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = BordeauxPrimary
        )
    }
}

/**
 * Seção recolhível "Anexos (N)" de uma consulta ou de um exame/documento que já foi salvo.
 * A lista só é lida do banco quando a seção está aberta; a contagem do título vem de [AttachmentActions.allAttachments].
 */
@Composable
fun SavedAttachmentsSection(
    ownerType: String,
    ownerId: Long,
    actions: AttachmentActions,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val count = actions.countFor(ownerType, ownerId)

    Column(modifier = modifier.fillMaxWidth().testTag("saved_attachments_section")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp)
                .testTag("saved_attachments_header"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AttachFile, null, tint = BordeauxPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Anexos ($count)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = BordeauxDark,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Recolher anexos" else "Mostrar anexos",
                tint = GraySecondary
            )
        }
        if (expanded) {
            SavedAttachmentsBody(ownerType, ownerId, actions)
        }
    }
}

@Composable
private fun SavedAttachmentsBody(ownerType: String, ownerId: Long, actions: AttachmentActions) {
    val context = LocalContext.current
    val flow = remember(ownerType, ownerId) { actions.attachmentsFor(ownerType, ownerId) }
    val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var message by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<Attachment?>(null) }
    var newLabel by remember { mutableStateOf(AttachmentLimits.DEFAULT_LABEL) }
    val max = AttachmentLimits.MAX_FILES_PER_RECORD

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = (max - items.size).coerceAtLeast(0)
        val accepted = uris.take(room)
        message = if (uris.size > accepted.size) "Só cabem mais $room arquivo(s); os outros foram ignorados." else null
        val label = newLabel.trim().ifBlank { AttachmentLimits.DEFAULT_LABEL }
        accepted.forEach { uri ->
            actions.add(ownerType, ownerId, uri.toString(), label) { result ->
                if (result is AddAttachmentResult.Rejected) message = result.message
            }
        }
    }

    fun open(attachment: Attachment) {
        val intent = actions.openIntent(attachment)
        if (intent == null) {
            message = "Arquivo não encontrado neste aparelho."
            return
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Abrir com"))
        } catch (e: ActivityNotFoundException) {
            message = "Nenhum app para abrir este arquivo."
        } catch (e: Exception) {
            message = "Não foi possível abrir o arquivo."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) {
            Text(
                text = "Nenhum anexo neste registro.",
                style = MaterialTheme.typography.bodySmall,
                color = GraySecondary
            )
        }
        items.forEach { attachment ->
            val broken = attachment.id in actions.brokenIds
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CreamOffWhite,
                border = BorderStroke(1.dp, if (broken) SoftRed.copy(alpha = 0.6f) else GrayBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open(attachment) }
                    .testTag("saved_attachment_item")
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (attachment.mimeType == "application/pdf") Icons.Default.PictureAsPdf else Icons.Default.Image,
                        contentDescription = null,
                        tint = if (broken) SoftRed else BordeauxPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachment.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxPrimary
                        )
                        Text(
                            text = attachment.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = BordeauxDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatAttachmentSize(attachment.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = GraySecondary
                        )
                        if (broken) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, tint = SoftRed, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "arquivo ausente neste aparelho",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SoftRed,
                                    modifier = Modifier.testTag("attachment_missing_warning")
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { removeTarget = attachment },
                        modifier = Modifier.testTag("saved_attachment_remove_button")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Remover anexo", tint = GraySecondary.copy(alpha = 0.7f))
                    }
                }
            }
        }

        Text(
            text = "Rótulo dos novos arquivos:",
            style = MaterialTheme.typography.labelSmall,
            color = GraySecondary
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AttachmentLimits.SUGGESTED_LABELS.forEach { suggestion ->
                FilterChip(
                    selected = newLabel == suggestion,
                    onClick = { newLabel = suggestion },
                    label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BordeauxPrimary,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White
                    )
                )
            }
        }
        OutlinedTextField(
            value = newLabel,
            onValueChange = { newLabel = it.take(40) },
            label = { Text("Rótulo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = attachmentLimitsText(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = GraySecondary
        )
        OutlinedButton(
            onClick = { picker.launch(ATTACHMENT_PICKER_MIME_TYPES) },
            enabled = items.size < max,
            border = BorderStroke(1.dp, BordeauxPrimary.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("saved_add_attachments_button")
        ) {
            Text(text = "Adicionar arquivos (${items.size} de $max)", color = BordeauxDark)
        }
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = SoftRed,
                modifier = Modifier.testTag("saved_attachments_message")
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = {
                Text(
                    text = "Remover este anexo?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
            },
            text = {
                Text(
                    text = "“${target.displayName}” será removido do registro e o arquivo será apagado deste aparelho.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GraySecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        removeTarget = null
                        actions.remove(target.id) { ok ->
                            message = if (ok) null else "Não foi possível remover o anexo."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
                    modifier = Modifier.testTag("confirm_remove_attachment_button")
                ) {
                    Text("Remover", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancelar", color = BordeauxDark) }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = CreamOffWhite
        )
    }
}

/** Aviso de anexos cujo arquivo sumiu, para a aba de ajustes. */
@Composable
fun MissingAttachmentsNotice(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("missing_attachments_notice"),
        shape = RoundedCornerShape(12.dp),
        color = SoftRedContainer,
        border = BorderStroke(1.dp, SoftRed.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = SoftRed, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$count anexo(s) com arquivo ausente neste aparelho. Eles podem voltar ao restaurar um backup.",
                style = MaterialTheme.typography.bodySmall,
                color = BordeauxDark
            )
        }
    }
}
