package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.attachments.AttachmentLimits
import com.example.data.attachments.StageResult
import com.example.data.attachments.StagedAttachment
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.SoftRed
import java.util.Locale

/** Texto do tamanho de um arquivo ("320 KB", "2,4 MB"). Usado também pelas telas de anexos já salvos. */
internal fun formatAttachmentSize(bytes: Long): String =
    if (bytes < 1024L * 1024L) "${maxOf(1L, bytes / 1024)} KB"
    else String.format(Locale("pt", "BR"), "%.1f MB", bytes / (1024.0 * 1024.0))

internal val ATTACHMENT_PICKER_MIME_TYPES = arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp")

/** Linha única com os limites, montada a partir de [AttachmentLimits] (nunca escreva os números à mão). */
internal fun attachmentLimitsText(): String =
    "PDF, JPG, PNG ou WebP • até ${AttachmentLimits.MAX_FILE_BYTES / (1024 * 1024)} MB cada • " +
        "até ${AttachmentLimits.MAX_FILES_PER_RECORD} arquivos"

/**
 * Anexos "preparados" de um registro que ainda está sendo preenchido: o arquivo já foi copiado para o
 * armazenamento privado do app, mas o registro ainda não foi salvo. Quem abre o diálogo cria um destes,
 * entrega [items] ao salvar, ou chama [discardAll] se o diálogo for fechado sem salvar.
 */
class StagedAttachmentsState {
    val items = mutableStateListOf<StagedAttachment>()

    /** Quantos arquivos estão sendo copiados agora. */
    var pending by mutableIntStateOf(0)

    /** Aviso para mostrar embaixo do botão (recusa, duplicado, limite). */
    var message by mutableStateOf<String?>(null)

    /** Lista para salvar: rótulo em branco vira o rótulo padrão. */
    fun toSave(): List<StagedAttachment> =
        items.map { if (it.label.isBlank()) it.copy(label = AttachmentLimits.DEFAULT_LABEL) else it }

    fun discardAll(onDiscard: (StagedAttachment) -> Unit) {
        items.toList().forEach(onDiscard)
        items.clear()
    }
}

@Composable
fun StagedAttachmentsSection(
    state: StagedAttachmentsState,
    onStage: (uri: String, label: String, onResult: (StageResult) -> Unit) -> Unit,
    onDiscard: (StagedAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    val max = AttachmentLimits.MAX_FILES_PER_RECORD
    val used = state.items.size + state.pending
    val remaining = max - used

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = (max - state.items.size - state.pending).coerceAtLeast(0)
        val accepted = uris.take(room)
        state.message = if (uris.size > accepted.size) {
            "Só cabem mais $room arquivo(s); os outros foram ignorados."
        } else null
        accepted.forEach { uri ->
            state.pending += 1
            onStage(uri.toString(), AttachmentLimits.DEFAULT_LABEL) { result ->
                state.pending = (state.pending - 1).coerceAtLeast(0)
                when (result) {
                    is StageResult.Staged -> {
                        val file = result.attachment
                        if (state.items.any { it.sha256 == file.sha256 }) {
                            // Mesmo conteúdo já está na lista: não descarta (o arquivo é compartilhado).
                            state.message = "Este arquivo já foi adicionado."
                        } else {
                            state.items.add(file)
                        }
                    }
                    is StageResult.Rejected -> state.message = result.message
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("staged_attachments_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AttachFile, null, tint = BordeauxPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Anexos (${state.items.size} de $max)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = BordeauxDark
            )
        }
        Text(
            text = attachmentLimitsText(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = GraySecondary
        )

        state.items.forEachIndexed { index, item ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CreamOffWhite,
                border = BorderStroke(1.dp, GrayBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("staged_attachment_item")
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (item.mimeType == "application/pdf") Icons.Default.PictureAsPdf else Icons.Default.Image,
                            contentDescription = null,
                            tint = BordeauxPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = BordeauxDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatAttachmentSize(item.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = GraySecondary
                            )
                        }
                        IconButton(
                            onClick = {
                                state.items.remove(item)
                                onDiscard(item)
                            },
                            modifier = Modifier.testTag("staged_attachment_remove_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remover anexo", tint = GraySecondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AttachmentLimits.SUGGESTED_LABELS.forEach { suggestion ->
                            FilterChip(
                                selected = item.label == suggestion,
                                onClick = { state.items[index] = item.copy(label = suggestion) },
                                label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BordeauxPrimary,
                                    selectedLabelColor = androidx.compose.ui.graphics.Color.White
                                )
                            )
                        }
                    }
                    OutlinedTextField(
                        value = item.label,
                        onValueChange = { state.items[index] = item.copy(label = it.take(40)) },
                        label = { Text("Rótulo") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { picker.launch(ATTACHMENT_PICKER_MIME_TYPES) },
            enabled = remaining > 0,
            border = BorderStroke(1.dp, BordeauxPrimary.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("add_attachments_button")
        ) {
            Text(
                text = if (state.pending > 0) "Copiando arquivos…" else "Adicionar arquivos",
                color = BordeauxDark
            )
        }
        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = SoftRed,
                modifier = Modifier.testTag("staged_attachments_message")
            )
        }
    }
}
