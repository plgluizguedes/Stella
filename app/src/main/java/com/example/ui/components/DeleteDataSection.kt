package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftRed
import com.example.ui.theme.SoftRedContainer
import com.example.viewmodel.CloudFilesCountState
import com.example.viewmodel.DataDeletionKind
import com.example.viewmodel.StellaViewModel

/**
 * Seção "Privacidade e dados": três botões de apagar (nuvem, este aparelho, os dois), cada um com
 * 3 confirmações em sequência (informação, digitar a palavra, botão final). Cancelar em qualquer etapa
 * encerra tudo sem apagar nada. O andamento e o resultado aparecem em [DataDeletionStatusDialog].
 */
@Composable
fun DeleteDataSection(
    canDeleteCloud: Boolean,
    canDeleteDevice: Boolean,
    cloudFilesCount: CloudFilesCountState,
    localRecordCount: Int,
    localAttachmentCount: Int,
    onLoadCloudFilesCount: () -> Unit,
    onDeleteCloud: () -> Unit,
    onDeleteDevice: () -> Unit,
    onDeleteBoth: () -> Unit,
    modifier: Modifier = Modifier
) {
    var flowKind by remember { mutableStateOf<DataDeletionKind?>(null) }
    var step by remember { mutableIntStateOf(1) }
    var typed by remember { mutableStateOf("") }

    fun start(kind: DataDeletionKind) {
        typed = ""
        step = 1
        flowKind = kind
    }

    fun cancel() {
        flowKind = null
        step = 1
        typed = ""
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("delete_data_section"),
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SoftRed.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DeleteForever, null, tint = SoftRed, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Privacidade e dados",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
            }
            Text(
                text = "Ações que apagam dados de verdade e não podem ser desfeitas. Cada uma pede 3 confirmações.",
                style = MaterialTheme.typography.bodySmall,
                color = GraySecondary
            )

            DeleteButton(
                label = "Apagar dados da nuvem",
                enabled = canDeleteCloud,
                disabledReason = "Conecte uma conta do Google Drive e aguarde o fim da operação em andamento.",
                tag = "delete_cloud_button",
                onClick = { start(DataDeletionKind.CLOUD) }
            )
            DeleteButton(
                label = "Apagar tudo neste aparelho",
                enabled = canDeleteDevice,
                disabledReason = "Aguarde o fim da operação em andamento.",
                tag = "delete_device_button",
                onClick = { start(DataDeletionKind.DEVICE) }
            )
            DeleteButton(
                label = "Apagar aparelho e nuvem",
                enabled = canDeleteCloud,
                disabledReason = "Conecte uma conta do Google Drive e aguarde o fim da operação em andamento.",
                tag = "delete_both_button",
                onClick = { start(DataDeletionKind.BOTH) }
            )
        }
    }

    val kind = flowKind ?: return
    val involvesCloud = kind != DataDeletionKind.DEVICE

    // Confirmação 1: pede a contagem de arquivos na nuvem ao abrir.
    LaunchedEffect(kind) {
        if (involvesCloud) onLoadCloudFilesCount()
    }

    val title = when (kind) {
        DataDeletionKind.CLOUD -> "Apagar dados da nuvem?"
        DataDeletionKind.DEVICE -> "Apagar tudo neste aparelho?"
        DataDeletionKind.BOTH -> "Apagar aparelho e nuvem?"
    }
    val canProceedFinal = if (involvesCloud) canDeleteCloud else canDeleteDevice

    AlertDialog(
        onDismissRequest = { cancel() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = SoftRed, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BordeauxDark
                    )
                    Text(
                        text = "Confirmação $step de 3",
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (step) {
                    1 -> StepInfo(
                        kind = kind,
                        cloudFilesCount = cloudFilesCount,
                        localRecordCount = localRecordCount,
                        localAttachmentCount = localAttachmentCount,
                        onRetryCount = onLoadCloudFilesCount
                    )

                    2 -> {
                        Text(
                            text = "Para continuar, digite a palavra ${StellaViewModel.DELETE_CONFIRMATION_WORD} no campo abaixo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BordeauxDark
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            singleLine = true,
                            label = { Text(StellaViewModel.DELETE_CONFIRMATION_WORD) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("delete_confirmation_input")
                        )
                    }

                    else -> {
                        Text(
                            text = "Última chance. Ao tocar em \"Apagar definitivamente\" a exclusão começa e não há como desfazer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BordeauxDark
                        )
                        if (!canProceedFinal) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (involvesCloud) {
                                    "Agora não é possível apagar: há uma operação em andamento ou a conta do Google Drive foi desconectada."
                                } else {
                                    "Agora não é possível apagar: há uma operação em andamento."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = SoftRed,
                                modifier = Modifier.testTag("delete_final_blocked_reason")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                1 -> {
                    val ready = !involvesCloud || cloudFilesCount is CloudFilesCountState.Loaded
                    Button(
                        onClick = { step = 2 },
                        enabled = ready,
                        colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
                        modifier = Modifier.testTag("delete_step1_continue_button")
                    ) {
                        Text("Continuar", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                }

                2 -> Button(
                    onClick = { step = 3 },
                    enabled = StellaViewModel.isDeleteConfirmationText(typed),
                    colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
                    modifier = Modifier.testTag("delete_step2_continue_button")
                ) {
                    Text("Continuar", color = PureWhite, fontWeight = FontWeight.Bold)
                }

                else -> Button(
                    onClick = {
                        // Revalida na hora do toque: o estado pode ter mudado com o diálogo aberto.
                        if (canProceedFinal) {
                            cancel()
                            when (kind) {
                                DataDeletionKind.CLOUD -> onDeleteCloud()
                                DataDeletionKind.DEVICE -> onDeleteDevice()
                                DataDeletionKind.BOTH -> onDeleteBoth()
                            }
                        }
                    },
                    enabled = canProceedFinal,
                    colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
                    modifier = Modifier.testTag("delete_final_button")
                ) {
                    Text("Apagar definitivamente", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { cancel() },
                modifier = Modifier.testTag("delete_cancel_button")
            ) {
                Text("Cancelar", color = BordeauxDark)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = CreamOffWhite
    )
}

@Composable
private fun DeleteButton(
    label: String,
    enabled: Boolean,
    disabledReason: String,
    tag: String,
    onClick: () -> Unit
) {
    Column {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            border = BorderStroke(1.dp, if (enabled) SoftRed else SoftRed.copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
        ) {
            Text(label, color = if (enabled) SoftRed else GraySecondary, fontWeight = FontWeight.SemiBold)
        }
        if (!enabled) {
            Text(
                text = disabledReason,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = GraySecondary,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun StepInfo(
    kind: DataDeletionKind,
    cloudFilesCount: CloudFilesCountState,
    localRecordCount: Int,
    localAttachmentCount: Int,
    onRetryCount: () -> Unit
) {
    val involvesCloud = kind != DataDeletionKind.DEVICE
    val involvesDevice = kind != DataDeletionKind.CLOUD

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = SoftRedContainer,
        border = BorderStroke(1.dp, SoftRed.copy(alpha = 0.5f))
    ) {
        Text(
            text = "Esta ação é irreversível.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = BordeauxDark,
            modifier = Modifier
                .padding(10.dp)
                .testTag("delete_irreversible_notice")
        )
    }
    Spacer(modifier = Modifier.height(10.dp))

    if (involvesCloud) {
        Text(
            text = "Na nuvem (Google Drive)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = BordeauxDark
        )
        when (cloudFilesCount) {
            is CloudFilesCountState.Idle, is CloudFilesCountState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BordeauxPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Contando os arquivos…", style = MaterialTheme.typography.bodySmall, color = GraySecondary)
                }
            }

            is CloudFilesCountState.Error -> {
                Text(
                    text = cloudFilesCount.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftRed
                )
                TextButton(onClick = onRetryCount) { Text("Tentar de novo", color = BordeauxDark) }
            }

            is CloudFilesCountState.Loaded -> {
                val c = cloudFilesCount.count
                Text(
                    text = "${c.total} arquivo(s) do Stella serão apagados do seu Google Drive: " +
                        "${c.backupFiles} de backup, ${c.attachmentFiles} anexo(s) e ${c.otherFiles} outro(s). " +
                        "A pasta fica, vazia.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BordeauxDark,
                    modifier = Modifier.testTag("delete_cloud_count_text")
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }

    if (involvesDevice) {
        Text(
            text = "Neste aparelho",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = BordeauxDark
        )
        Text(
            text = "Serão apagados $localRecordCount registro(s), $localAttachmentCount anexo(s), a foto do perfil, " +
                "os exames e os lembretes (com seus alarmes).",
            style = MaterialTheme.typography.bodySmall,
            color = BordeauxDark
        )
        Spacer(modifier = Modifier.height(10.dp))
    }

    Text(
        text = when (kind) {
            DataDeletionKind.CLOUD ->
                "Depois de apagar, o Google Drive será desconectado. Os dados deste aparelho continuam."
            DataDeletionKind.DEVICE ->
                "A nuvem continua com o backup. A sincronização fica pausada até você decidir."
            DataDeletionKind.BOTH ->
                "Só será concluído se os dois forem apagados. Sem internet, NÃO será concluído e você terá que repetir depois."
        },
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = BordeauxDark
    )
}
