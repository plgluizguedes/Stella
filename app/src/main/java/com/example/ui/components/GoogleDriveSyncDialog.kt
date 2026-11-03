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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
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
import com.example.data.drive.DriveConnectionStatus
import com.example.data.drive.DriveSyncState
import com.example.data.drive.GoogleDriveSyncManager
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
import com.example.ui.theme.SoftGreen
import com.example.ui.theme.SoftGreenContainer
import com.example.ui.theme.SoftRed
import com.example.ui.theme.SoftRedContainer

@Composable
fun GoogleDriveSyncCard(
    connectionStatus: DriveConnectionStatus,
    syncState: DriveSyncState,
    accountEmail: String?,
    lastSyncTimestamp: String?,
    hasPendingChanges: Boolean = false,
    isSyncPaused: Boolean = false,
    onSyncClick: () -> Unit,
    onConnectClick: () -> Unit,
    onVerifyClick: () -> Unit = onSyncClick,
    onConfigureFolderClick: () -> Unit = {},
    onSwitchAccountClick: () -> Unit = {},
    onDisconnectClick: () -> Unit = {},
    onDecideNowClick: () -> Unit = {},
    onBackupVersionsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDisconnectConfirmation by remember { mutableStateOf(false) }
    var showSwitchConfirmation by remember { mutableStateOf(false) }

    if (showDisconnectConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmation = false },
            title = {
                Text(
                    text = "Desconectar Google Drive?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
            },
            text = {
                Text(
                    text = "Ao desconectar, o aplicativo não sincronizará novos dados com esta conta Google. Os arquivos já salvos no seu Drive permanecerão preservados.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GraySecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirmation = false
                        onDisconnectClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
                    modifier = Modifier.testTag("confirm_disconnect_drive_button")
                ) {
                    Text("Desconectar", color = PureWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirmation = false }) {
                    Text("Cancelar", color = BordeauxDark)
                }
            }
        )
    }

    if (showSwitchConfirmation) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirmation = false },
            title = {
                Text(
                    text = "Trocar Conta Google?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
            },
            text = {
                Text(
                    text = "Deseja escolher outra conta Google para vincular ao prontuário da Stella?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GraySecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSwitchConfirmation = false
                        onSwitchAccountClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                    modifier = Modifier.testTag("confirm_switch_account_button")
                ) {
                    Text("Escolher Conta", color = PureWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchConfirmation = false }) {
                    Text("Cancelar", color = BordeauxDark)
                }
            }
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("google_drive_sync_card"),
        shape = RoundedCornerShape(16.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, BordeauxPrimary.copy(alpha = 0.35f)),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Card Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(BordeauxSoftContainer, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (connectionStatus) {
                                DriveConnectionStatus.CONFIGURED -> Icons.Default.CloudDone
                                DriveConnectionStatus.CONNECTING,
                                DriveConnectionStatus.VERIFYING,
                                DriveConnectionStatus.UNVERIFIED -> Icons.Default.CloudSync
                                DriveConnectionStatus.PERMISSION_MISSING, DriveConnectionStatus.ERROR -> Icons.Default.Warning
                                else -> Icons.Default.Cloud
                            },
                            contentDescription = null,
                            tint = when (connectionStatus) {
                                DriveConnectionStatus.CONFIGURED -> SoftGreen
                                DriveConnectionStatus.PERMISSION_MISSING, DriveConnectionStatus.ERROR -> SoftRed
                                else -> BordeauxPrimary
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "Backup no Google Drive",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                        val hasSavedFolder = connectionStatus == DriveConnectionStatus.CONFIGURED ||
                            connectionStatus == DriveConnectionStatus.VERIFYING ||
                            connectionStatus == DriveConnectionStatus.UNVERIFIED
                        Text(
                            text = if (hasSavedFolder) {
                                "Pasta: ${GoogleDriveSyncManager.DRIVE_FOLDER_NAME}"
                            } else {
                                "Nuvem Privada Google Drive"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connectionStatus == DriveConnectionStatus.CONFIGURED) BordeauxPrimary else GraySecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Dynamic Status Badge (Reflects REAL DriveConnectionStatus)
                val (badgeLabel, badgeBgColor, badgeTextColor, badgeDotColor) = when (connectionStatus) {
                    DriveConnectionStatus.CONFIGURED ->
                        listOf("CONECTADO", SoftGreenContainer, Color(0xFF1B4D20), SoftGreen)
                    DriveConnectionStatus.CONNECTING ->
                        listOf("CONECTANDO", DiscreetGoldContainer, BordeauxDark, DiscreetGold)
                    DriveConnectionStatus.VERIFYING ->
                        listOf("VERIFICANDO", DiscreetGoldContainer, BordeauxDark, DiscreetGold)
                    DriveConnectionStatus.UNVERIFIED ->
                        listOf("NÃO VERIFICADO", DiscreetGoldContainer, BordeauxDark, DiscreetGold)
                    DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE ->
                        listOf("PASTA PENDENTE", DiscreetGoldContainer, BordeauxDark, DiscreetGold)
                    DriveConnectionStatus.PERMISSION_MISSING ->
                        listOf("SEM PERMISSÃO", SoftRedContainer, SoftRed, SoftRed)
                    DriveConnectionStatus.ERROR ->
                        listOf("ERRO", SoftRedContainer, SoftRed, SoftRed)
                    DriveConnectionStatus.NOT_CONNECTED ->
                        listOf("NÃO CONECTADO", CreamOffWhite, GraySecondary, GraySecondary)
                }

                Surface(
                    modifier = Modifier.padding(start = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBgColor as Color,
                    border = BorderStroke(1.dp, (badgeDotColor as Color).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(badgeDotColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = badgeLabel as String,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeTextColor as Color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Information Box
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = CreamOffWhite,
                border = BorderStroke(1.dp, GrayBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Conta Google:", style = MaterialTheme.typography.labelSmall, color = GraySecondary)
                        Text(
                            text = accountEmail ?: "Nenhuma conta vinculada",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (accountEmail != null) BordeauxDark else GraySecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Status da Conexão:", style = MaterialTheme.typography.labelSmall, color = GraySecondary)
                        Text(
                            text = when (connectionStatus) {
                                DriveConnectionStatus.CONFIGURED -> "Autenticado & Pasta Verificada"
                                DriveConnectionStatus.CONNECTING -> "Conectando e autenticando..."
                                DriveConnectionStatus.VERIFYING -> "Verificando conexão com o Google Drive..."
                                DriveConnectionStatus.UNVERIFIED -> "Conexão não confirmada no momento"
                                DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE -> "Conta autenticada (pasta não localizada)"
                                DriveConnectionStatus.PERMISSION_MISSING -> "Permissão drive.file necessária"
                                DriveConnectionStatus.ERROR -> "Erro de acesso ao Google Drive"
                                DriveConnectionStatus.NOT_CONNECTED -> "Desconectado"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when (connectionStatus) {
                                DriveConnectionStatus.CONFIGURED -> Color(0xFF1B4D20)
                                DriveConnectionStatus.PERMISSION_MISSING, DriveConnectionStatus.ERROR -> SoftRed
                                DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, DriveConnectionStatus.VERIFYING, DriveConnectionStatus.UNVERIFIED -> BordeauxPrimary
                                else -> GraySecondary
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Destino na Nuvem:",
                            style = MaterialTheme.typography.labelSmall,
                            color = GraySecondary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        val hasConfiguredOrSavedFolder = connectionStatus == DriveConnectionStatus.CONFIGURED ||
                            connectionStatus == DriveConnectionStatus.VERIFYING ||
                            connectionStatus == DriveConnectionStatus.UNVERIFIED
                        Text(
                            text = if (hasConfiguredOrSavedFolder) {
                                "Google Drive / ${GoogleDriveSyncManager.DRIVE_FOLDER_NAME}"
                            } else {
                                "Não configurado"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasConfiguredOrSavedFolder) BordeauxLight else GraySecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Última Sincronização:", style = MaterialTheme.typography.labelSmall, color = GraySecondary)
                        Text(
                            text = lastSyncTimestamp ?: "Pendente de envio",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = GraySecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // State-specific helper banners & messages
            when (connectionStatus) {
                DriveConnectionStatus.NOT_CONNECTED -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = CreamOffWhite,
                        border = BorderStroke(1.dp, GrayBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Cloud, null, tint = BordeauxPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Vincule sua conta Google para salvar cópias de segurança do prontuário, glicemias, vacinas e fotos da Stella no Google Drive.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = BordeauxDark
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_drive_button")
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = PureWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Vincular Conta Google", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                }

                DriveConnectionStatus.CONNECTING -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = BordeauxPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Conectando ao Google Drive e configurando pasta...",
                            style = MaterialTheme.typography.bodySmall,
                            color = BordeauxPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                DriveConnectionStatus.VERIFYING -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .testTag("drive_verifying_indicator"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = BordeauxPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Verificando conexão com o Google Drive...",
                            style = MaterialTheme.typography.bodySmall,
                            color = BordeauxPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                DriveConnectionStatus.UNVERIFIED -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drive_unverified_banner"),
                        shape = RoundedCornerShape(8.dp),
                        color = DiscreetGoldContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CloudSync, null, tint = DiscreetGold, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Não foi possível confirmar a conexão com o Google Drive neste momento. A pasta configurada permanece preservada.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = BordeauxDark,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onVerifyClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("verify_drive_retry_button")
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp), tint = PureWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verificar Conexão Novamente", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(onClick = { showSwitchConfirmation = true }) {
                            Text("Trocar Conta Google", fontSize = 12.sp, color = BordeauxDark)
                        }
                        TextButton(onClick = { showDisconnectConfirmation = true }) {
                            Text("Desconectar", fontSize = 12.sp, color = SoftRed)
                        }
                    }
                }

                DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = DiscreetGoldContainer
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, tint = DiscreetGold, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Conta vinculada, mas a pasta '${GoogleDriveSyncManager.DRIVE_FOLDER_NAME}' ainda não foi criada/localizada no seu Drive.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = BordeauxDark,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onConfigureFolderClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("configure_drive_folder_button")
                    ) {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp), tint = PureWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Configurar Pasta no Google Drive", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showSwitchConfirmation = true },
                            modifier = Modifier.testTag("switch_account_pending_button")
                        ) {
                            Text("Trocar Conta Google", fontSize = 12.sp, color = BordeauxDark)
                        }
                        TextButton(
                            onClick = { showDisconnectConfirmation = true },
                            modifier = Modifier.testTag("disconnect_pending_button")
                        ) {
                            Text("Desconectar", fontSize = 12.sp, color = SoftRed)
                        }
                    }
                }

                DriveConnectionStatus.PERMISSION_MISSING -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = SoftRedContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = SoftRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "A permissão para criar arquivos no Google Drive (drive.file) foi negada ou revogada. Conceda a permissão para permitir salvar os dados.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = SoftRed,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grant_drive_permission_button")
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp), tint = PureWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Conceder Permissão do Drive", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(onClick = { showSwitchConfirmation = true }) {
                            Text("Trocar Conta Google", fontSize = 12.sp, color = BordeauxDark)
                        }
                        TextButton(onClick = { showDisconnectConfirmation = true }) {
                            Text("Desconectar", fontSize = 12.sp, color = SoftRed)
                        }
                    }
                }

                DriveConnectionStatus.ERROR -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = SoftRedContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = SoftRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            val errText = if (syncState is DriveSyncState.Error) {
                                syncState.message
                            } else {
                                "Falha na conexão ou autenticação com o Google Drive."
                            }
                            Text(
                                text = errText,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = SoftRed,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("retry_drive_connect_button")
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp), tint = PureWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tentar Conectar Novamente", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(onClick = { showSwitchConfirmation = true }) {
                            Text("Trocar Conta", fontSize = 12.sp, color = BordeauxDark)
                        }
                        TextButton(onClick = { showDisconnectConfirmation = true }) {
                            Text("Desconectar", fontSize = 12.sp, color = SoftRed)
                        }
                    }
                }

                DriveConnectionStatus.CONFIGURED -> {
                    // Sync Paused Banner (Decision Pending)
                    if (isSyncPaused) {
                        SyncPausedBanner(
                            onDecideNowClick = onDecideNowClick,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }

                    // Pending changes banner
                    if (hasPendingChanges && syncState is DriveSyncState.Idle) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = DiscreetGoldContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = DiscreetGold,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Há alterações locais pendentes de envio para o Google Drive.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BordeauxDark,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Sync State Banner
                    when (syncState) {
                        is DriveSyncState.Syncing -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = BordeauxPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = syncState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BordeauxPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        is DriveSyncState.Restoring -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = BordeauxPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = syncState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BordeauxPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        is DriveSyncState.Success -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = SoftGreenContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, null, tint = SoftGreen, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Salvo no Google Drive com sucesso em ${syncState.lastSyncDate}!",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = Color(0xFF1B4D20),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        is DriveSyncState.RestoreSuccess -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = SoftGreenContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, null, tint = SoftGreen, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${syncState.message} (${syncState.recordsCount} registros, ${syncState.filesCount} arquivos)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = Color(0xFF1B4D20),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        is DriveSyncState.RestorePartial -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = DiscreetGoldContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = DiscreetGold, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${syncState.message} (${syncState.recordsCount} registros, ${syncState.failedFiles.size} falha(s))",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = BordeauxDark,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        is DriveSyncState.Error -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = SoftRedContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = SoftRed, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = syncState.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = SoftRed
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        else -> Unit
                    }

                    // Primary Sync Action
                    Button(
                        onClick = onSyncClick,
                        enabled = !isSyncPaused,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSyncPaused) BordeauxPrimary.copy(alpha = 0.5f) else BordeauxPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_to_drive_button")
                    ) {
                        Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp), tint = PureWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSyncPaused) "Sincronização Pausada" else "Sincronizar Prontuário com o Drive Agora",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onBackupVersionsClick,
                        border = BorderStroke(1.dp, BordeauxPrimary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("backup_versions_button")
                    ) {
                        Text("Versões do backup", fontSize = 12.sp, color = BordeauxDark)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Secondary Account Management Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showSwitchConfirmation = true },
                            border = BorderStroke(1.dp, BordeauxPrimary.copy(alpha = 0.5f)),
                            modifier = Modifier.testTag("switch_drive_account_button")
                        ) {
                            Text("Trocar Conta Google", fontSize = 12.sp, color = BordeauxDark)
                        }

                        TextButton(
                            onClick = { showDisconnectConfirmation = true },
                            modifier = Modifier.testTag("disconnect_drive_button")
                        ) {
                            Text("Desconectar", fontSize = 12.sp, color = SoftRed)
                        }
                    }
                }
            }
        }
    }
}
