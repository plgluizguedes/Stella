package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.BordeauxSoftContainer
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.DiscreetGold
import com.example.ui.theme.DiscreetGoldContainer
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftRed
import com.example.ui.theme.SoftRedContainer
import com.example.viewmodel.DataDeletionKind
import com.example.viewmodel.DataDeletionState

/**
 * Mostra o andamento e o resultado de "apagar a nuvem", "apagar este aparelho" ou "apagar os dois".
 * Um só diálogo, no nível da Activity. [DataDeletionState.Done] só aparece quando TUDO o que o botão
 * prometeu foi mesmo apagado; qualquer outra coisa é [DataDeletionState.Failed] (não concluído).
 */
@Composable
fun DataDeletionStatusDialog(
    state: DataDeletionState,
    onDismiss: () -> Unit
) {
    when (state) {
        is DataDeletionState.Idle -> Unit

        is DataDeletionState.Running -> {
            AlertDialog(
                onDismissRequest = { /* sem como fechar enquanto apaga */ },
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = BordeauxPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Apagando…",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BordeauxDark,
                            modifier = Modifier.testTag("data_deletion_running_message")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Não feche o app até terminar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraySecondary
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {},
                shape = RoundedCornerShape(16.dp),
                containerColor = CreamOffWhite,
                modifier = Modifier.testTag("data_deletion_running_dialog")
            )
        }

        is DataDeletionState.Done -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = BordeauxPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Apagado",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                    }
                },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BordeauxDark,
                            modifier = Modifier.testTag("data_deletion_done_message")
                        )
                        if (state.warnings.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("data_deletion_warnings"),
                                shape = RoundedCornerShape(10.dp),
                                color = DiscreetGoldContainer,
                                border = BorderStroke(1.dp, DiscreetGold.copy(alpha = 0.6f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "Avisos (não impediram a exclusão):",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = BordeauxDark
                                    )
                                    state.warnings.forEach { warning ->
                                        Text(
                                            text = "• $warning",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp,
                                            color = BordeauxDark
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                        modifier = Modifier.testTag("data_deletion_done_ok_button")
                    ) {
                        Text("Ok", color = PureWhite)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = CreamOffWhite
            )
        }

        is DataDeletionState.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
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
                            text = "Não foi concluído",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                    }
                },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BordeauxDark,
                            modifier = Modifier.testTag("data_deletion_failed_message")
                        )
                        if (state.offline) {
                            Spacer(modifier = Modifier.height(10.dp))
                            FailureNote(
                                text = "Sem internet — não foi concluído. Repita quando estiver online.",
                                tag = "data_deletion_offline_note"
                            )
                        }
                        if (state.cloudDeleted) {
                            Spacer(modifier = Modifier.height(10.dp))
                            FailureNote(
                                text = "A nuvem já foi apagada, mas o aparelho não. Repita \"Apagar aparelho e nuvem\" agora para concluir. " +
                                    "(A conta continua conectada de propósito.)",
                                tag = "data_deletion_cloud_deleted_note"
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Para tentar de novo, vá em Perfil › Lembretes & Ajustes › Privacidade e dados.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraySecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                        modifier = Modifier.testTag("data_deletion_failed_close_button")
                    ) {
                        Text("Fechar", color = PureWhite)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = CreamOffWhite
            )
        }
    }
}

@Composable
private fun FailureNote(text: String, tag: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        shape = RoundedCornerShape(10.dp),
        color = SoftRedContainer,
        border = BorderStroke(1.dp, SoftRed.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = BordeauxDark,
            modifier = Modifier.padding(10.dp)
        )
    }
}
