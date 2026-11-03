package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.DriveSyncDecisionState
import com.example.data.drive.DriveSyncState
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxLight
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.BordeauxSoftContainer
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.DiscreetGold
import com.example.ui.theme.DiscreetGoldContainer
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftRed
import com.example.ui.theme.SoftRedContainer

/**
 * Diálogo de decisão exibido no nível da Activity quando a sincronização precisa
 * da escolha do usuário entre restaurar o backup da nuvem ou substituí-lo.
 */
@Composable
fun DriveSyncDecisionDialog(
    decisionState: DriveSyncDecisionState,
    syncState: DriveSyncState,
    actionInProgress: Boolean,
    errorMessage: String? = null,
    onConfirmRestore: () -> Unit,
    onConfirmOverwrite: () -> Unit,
    onDismissDecision: () -> Unit,
    onRetryCheck: () -> Unit
) {
    when (decisionState) {
        is DriveSyncDecisionState.None -> {
            // Nenhum diálogo ativo
        }

        is DriveSyncDecisionState.Checking -> {
            AlertDialog(
                onDismissRequest = { /* Bloqueado durante verificação */ },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = BordeauxPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Verificando a nuvem",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                    }
                },
                text = {
                    Text(
                        text = "Verificando o backup na nuvem…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GraySecondary
                    )
                },
                confirmButton = {},
                dismissButton = {},
                shape = RoundedCornerShape(16.dp),
                containerColor = CreamOffWhite
            )
        }

        is DriveSyncDecisionState.DecisionPending -> {
            val preview = decisionState.remotePreview
            val isLocalEmpty = decisionState.isLocalEmpty
            // O erro só aparece depois de uma tentativa feita neste diálogo (evita mostrar um erro antigo).
            var attempted by remember { mutableStateOf(false) }
            val failureMessage: String? = if (attempted && !actionInProgress) {
                errorMessage ?: (syncState as? DriveSyncState.Error)?.message
            } else null

            AlertDialog(
                onDismissRequest = {
                    if (!actionInProgress) {
                        onDismissDecision()
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(BordeauxSoftContainer, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = BordeauxPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "O que fazer com a nuvem?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = BordeauxDark
                            )
                            Text(
                                text = decisionState.accountEmail,
                                style = MaterialTheme.typography.labelSmall,
                                color = GraySecondary
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Esta conta já possui um prontuário salvo no Google Drive. Escolha como sincronizar os dados:",
                            style = MaterialTheme.typography.bodySmall,
                            color = BordeauxDark
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Cartão: O que há na Nuvem
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = PureWhite,
                            border = BorderStroke(1.dp, GrayBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = BordeauxPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Na nuvem (Google Drive):",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = BordeauxDark
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Paciente: ${preview.profileName} • Salvo em: ${preview.exportDate}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = GraySecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val summary = buildString {
                                    append("${preview.glucoseCount} glicemias • ")
                                    append("${preview.medicationCount} remédios • ")
                                    append("${preview.appointmentCount} consultas • ")
                                    append("${preview.documentCount} exames • ")
                                    append("${preview.vaccineCount} vacinas • ")
                                    append("${preview.feedingCount} refeições • ")
                                    append("${preview.weightCount} pesos • ")
                                    append("${preview.reminderCount} lembretes")
                                    if (preview.attachmentCount > 0) {
                                        append(" • ${preview.attachmentCount} anexo(s)")
                                    }
                                }
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = BordeauxDark,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Cartão: O que há neste aparelho
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = PureWhite,
                            border = BorderStroke(1.dp, GrayBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Devices,
                                        contentDescription = null,
                                        tint = DiscreetGold,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Neste aparelho:",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = BordeauxDark
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                if (isLocalEmpty) {
                                    Text(
                                        text = "Este aparelho está vazio (nenhum registro cadastrado).",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = GraySecondary
                                    )
                                } else {
                                    Text(
                                        text = "${decisionState.localRecordCount} registro(s) salvos neste aparelho.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = BordeauxDark,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Se restaurar a nuvem, o app guardará antes uma cópia datada dos dados deste aparelho no Drive para não perder nada.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 10.sp,
                                        color = GraySecondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Se substituir a nuvem, o backup que está lá agora não é apagado: ele vira uma cópia datada na mesma pasta do Drive.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            color = GraySecondary
                        )

                        if (failureMessage != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("decision_error_message"),
                                shape = RoundedCornerShape(10.dp),
                                color = SoftRedContainer,
                                border = BorderStroke(1.dp, SoftRed.copy(alpha = 0.5f))
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = SoftRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Não foi concluído. $failureMessage",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = BordeauxDark
                                    )
                                }
                            }
                        }

                        // Indicador de progresso se uma ação estiver rodando
                        AnimatedVisibility(visible = actionInProgress) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = BordeauxPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val progressMsg = when (syncState) {
                                        is DriveSyncState.Restoring -> syncState.message
                                        is DriveSyncState.Syncing -> syncState.message
                                        else -> "Processando cópia de segurança e sincronização…"
                                    }
                                    Text(
                                        text = progressMsg,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = BordeauxPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Botão 1: Restaurar da nuvem neste aparelho
                        Button(
                            onClick = {
                                attempted = true
                                onConfirmRestore()
                            },
                            enabled = !actionInProgress,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLocalEmpty) BordeauxPrimary else BordeauxPrimary.copy(alpha = 0.9f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("decision_restore_from_cloud_button")
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp), tint = PureWhite)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Restaurar da nuvem neste aparelho",
                                color = PureWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        // Botão 2: Substituir a nuvem pelos dados deste aparelho
                        OutlinedButton(
                            onClick = {
                                attempted = true
                                onConfirmOverwrite()
                            },
                            enabled = !actionInProgress,
                            border = BorderStroke(1.dp, if (actionInProgress) GrayBorder else BordeauxPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("decision_overwrite_cloud_button")
                        ) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (actionInProgress) GraySecondary else BordeauxPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Substituir a nuvem pelos dados deste aparelho",
                                color = if (actionInProgress) GraySecondary else BordeauxPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }

                        // Botão 3: Decidir depois
                        TextButton(
                            onClick = onDismissDecision,
                            enabled = !actionInProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("decision_dismiss_button")
                        ) {
                            Text(
                                text = "Decidir depois (sincronização continuará pausada)",
                                fontSize = 11.sp,
                                color = if (actionInProgress) GraySecondary else GraySecondary
                            )
                        }
                    }
                },
                dismissButton = {},
                shape = RoundedCornerShape(16.dp),
                containerColor = CreamOffWhite
            )
        }

        is DriveSyncDecisionState.Error -> {
            AlertDialog(
                onDismissRequest = onDismissDecision,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = SoftRed,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Erro ao verificar a nuvem",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                    }
                },
                text = {
                    Text(
                        text = decisionState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = GraySecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onRetryCheck,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                        modifier = Modifier.testTag("decision_error_retry_button")
                    ) {
                        Text("Tentar de novo", color = PureWhite)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onDismissDecision,
                        modifier = Modifier.testTag("decision_error_dismiss_button")
                    ) {
                        Text("Fechar", color = BordeauxDark)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = CreamOffWhite
            )
        }
    }
}

/**
 * Banner de aviso que alerta que a sincronização está pausada até a decisão do usuário.
 */
@Composable
fun SyncPausedBanner(
    onDecideNowClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("sync_paused_banner"),
        shape = RoundedCornerShape(12.dp),
        color = DiscreetGoldContainer,
        border = BorderStroke(1.dp, DiscreetGold.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PauseCircle,
                    contentDescription = null,
                    tint = BordeauxDark,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sincronização pausada",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "A sincronização está pausada até você decidir entre restaurar a nuvem ou substituí-la.",
                style = MaterialTheme.typography.bodySmall,
                color = BordeauxDark,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onDecideNowClick,
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sync_paused_decide_now_button")
            ) {
                Text(
                    text = "Decidir agora",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}
