package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.util.RefreshRateManager
import com.example.util.RefreshRateMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import com.example.data.drive.DriveConnectionStatus
import com.example.viewmodel.DriveSyncDecisionState
import com.example.data.drive.DriveSyncState
import com.example.ui.components.BackupVersionsDialog
import com.example.ui.components.DataDeletionStatusDialog
import com.example.ui.components.DriveSyncDecisionDialog
import com.example.viewmodel.DataDeletionState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.ExamDocument
import com.example.data.model.Medication
import com.example.data.model.VaccinePreventionRecord
import com.example.ui.components.AddAppointmentDialog
import com.example.ui.components.AddExamDialog
import com.example.ui.components.AttachmentActions
import com.example.ui.components.AddFeedingDialog
import com.example.ui.components.AddGlucoseDialog
import com.example.ui.components.AddMedicationDialog
import com.example.ui.components.AddReminderDialog
import com.example.ui.components.AddVaccineDialog
import com.example.ui.components.AddWeightDialog
import com.example.ui.components.GlobalSearchDialog
import com.example.ui.components.MedicalReportDialog
import com.example.ui.components.RecordAdministrationDialog
import com.example.ui.screens.AddOrEditVaccineDialog
import com.example.ui.screens.GlucoseScreen
import com.example.ui.screens.HealthHubScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MedicationsScreen
import com.example.ui.screens.ProfileAndMoreScreen
import com.example.ui.screens.VaccinationHistoryScreen
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxLight
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.BordeauxSoftContainer
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.DiscreetGold
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PureWhite
import com.example.viewmodel.StellaViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val savedRefreshRate = RefreshRateManager.getSavedMode(this)
        RefreshRateManager.applyToWindow(this, savedRefreshRate)
        setContent {
            MyApplicationTheme {
                StellaApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StellaApp(viewModel: StellaViewModel = viewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val glucoseRecords by viewModel.glucoseList.collectAsStateWithLifecycle()
    val medications by viewModel.medicationsList.collectAsStateWithLifecycle()
    val administrations by viewModel.administrationsList.collectAsStateWithLifecycle()
    val appointments by viewModel.appointmentsList.collectAsStateWithLifecycle()
    val documents by viewModel.examDocumentsList.collectAsStateWithLifecycle()
    val feedingRecords by viewModel.feedingList.collectAsStateWithLifecycle()
    val weightRecords by viewModel.weightList.collectAsStateWithLifecycle()
    val vaccines by viewModel.vaccinesList.collectAsStateWithLifecycle()
    val reminders by viewModel.remindersList.collectAsStateWithLifecycle()
    val driveSyncState by viewModel.driveSyncState.collectAsStateWithLifecycle()
    val driveConnectionStatus by viewModel.driveConnectionStatus.collectAsStateWithLifecycle()
    val driveAccountEmail by viewModel.driveAccountEmail.collectAsStateWithLifecycle()
    val lastSyncTimestamp by viewModel.lastSyncTimestamp.collectAsStateWithLifecycle()
    val hasPendingChanges by viewModel.hasPendingChanges.collectAsStateWithLifecycle()
    val recoverableAuthIntent by viewModel.driveSyncManager.recoverableAuthIntent.collectAsStateWithLifecycle()

    val syncDecisionState by viewModel.syncDecisionState.collectAsStateWithLifecycle()
    val decisionActionInProgress by viewModel.decisionActionInProgress.collectAsStateWithLifecycle()
    val uiErrorMessage by viewModel.uiErrorMessage.collectAsStateWithLifecycle()
    val dataDeletion by viewModel.dataDeletion.collectAsStateWithLifecycle()
    val backupVersions by viewModel.backupVersions.collectAsStateWithLifecycle()
    val canDeleteCloud by viewModel.canDeleteCloud.collectAsStateWithLifecycle()
    val canDeleteDevice by viewModel.canDeleteDevice.collectAsStateWithLifecycle()
    val cloudFilesCountState by viewModel.cloudFilesCount.collectAsStateWithLifecycle()
    val allAttachments by viewModel.allAttachments.collectAsStateWithLifecycle()
    val brokenAttachments by viewModel.brokenAttachments.collectAsStateWithLifecycle()
    val attachmentActions = AttachmentActions(
        attachmentsFor = { ownerType, ownerId -> viewModel.attachmentsFor(ownerType, ownerId) },
        add = { ownerType, ownerId, uri, label, onResult -> viewModel.addAttachment(ownerType, ownerId, uri, label, onResult) },
        remove = { id, onDone -> viewModel.removeAttachment(id, onDone) },
        openIntent = { attachment -> viewModel.openAttachmentIntent(attachment) },
        refreshBroken = { viewModel.refreshBrokenAttachments() },
        allAttachments = allAttachments,
        brokenIds = brokenAttachments.map { it.id }.toSet()
    )
    var showBackupVersions by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val isSyncPaused = remember(syncDecisionState, driveSyncState, dataDeletion, driveAccountEmail) {
        !driveAccountEmail.isNullOrBlank() &&
        syncDecisionState is DriveSyncDecisionState.None &&
        !viewModel.driveSyncManager.isSyncApprovedForAccount(driveAccountEmail)
    }

    // Com o diálogo de decisão aberto, o erro aparece dentro dele (o Snackbar ficaria atrás do diálogo).
    LaunchedEffect(uiErrorMessage, syncDecisionState) {
        val message = uiErrorMessage
        if (message != null && syncDecisionState !is DriveSyncDecisionState.DecisionPending) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearUiErrorMessage()
        }
    }

    LaunchedEffect(driveSyncState) {
        when (val state = driveSyncState) {
            is DriveSyncState.RestoreSuccess -> {
                snackbarHostState.showSnackbar(
                    "${state.message} (${state.recordsCount} registros, ${state.filesCount} arquivos restaurados)"
                )
            }
            is DriveSyncState.RestorePartial -> {
                snackbarHostState.showSnackbar(
                    "${state.message}: ${state.recordsCount} registros restaurados, ${state.failedFiles.size} anexo(s) não recuperados."
                )
            }
            else -> Unit
        }
    }

    val context = LocalContext.current
    var refreshRateMode by remember { mutableStateOf(RefreshRateManager.getSavedMode(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "Permissão POST_NOTIFICATIONS concedida com sucesso.")
        } else {
            Log.w("MainActivity", "Permissão POST_NOTIFICATIONS negada pelo usuário.")
        }
    }

    val requestNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }

    LaunchedEffect(Unit) {
        requestNotificationPermission()
    }

    var isRecoveringDriveAuth by remember { mutableStateOf(false) }
    var skipNextResumeAfterAuthRecovery by remember { mutableStateOf(false) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isRecoveringDriveAuth) {
            isRecoveringDriveAuth = false
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    viewModel.verifyDriveConnection()
                }
                Activity.RESULT_CANCELED -> {
                    Log.w("MainActivity", "Recuperação de autorização do Google Drive cancelada pelo usuário.")
                }
                else -> {
                    Log.w("MainActivity", "Falha na recuperação de autorização do Google Drive: resultCode=${result.resultCode}")
                }
            }
            return@rememberLauncherForActivityResult
        }

        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                viewModel.handleDriveSignIn(account)
            }
        } catch (e: ApiException) {
            Log.w("MainActivity", "Google Sign-In ApiException: code=${e.statusCode}, message=${e.message}")
            if (e.statusCode == 12501) {
                // User cancelled account picker
                viewModel.driveSyncManager.refreshStateFromDevice()
            } else {
                viewModel.setDriveSignInError("Falha na autenticação Google (código ${e.statusCode})")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Falha na autenticação do Google Drive: ${e.message}")
            viewModel.setDriveSignInError(e.localizedMessage ?: "Erro na autenticação com conta Google")
        }
    }

    LaunchedEffect(recoverableAuthIntent) {
        recoverableAuthIntent?.let { intent ->
            isRecoveringDriveAuth = true
            googleSignInLauncher.launch(intent)
            viewModel.driveSyncManager.clearRecoverableAuthIntent()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (isRecoveringDriveAuth) {
            skipNextResumeAfterAuthRecovery = true
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (skipNextResumeAfterAuthRecovery) {
            skipNextResumeAfterAuthRecovery = false
        } else {
            viewModel.onDriveAppResumed()
        }
    }

    var currentTab by remember { mutableIntStateOf(0) } // 0: Home, 1: Glucose, 2: Medications, 3: Health Hub, 4: Stella/Profile
    var showVaccinationHistoryScreen by remember { mutableStateOf(false) }
    var editingVaccineRecord by remember { mutableStateOf<VaccinePreventionRecord?>(null) }
    var editingExamDocument by remember { mutableStateOf<ExamDocument?>(null) }

    // Dialog flags
    var showGlucoseDialog by remember { mutableStateOf(false) }
    var showMedicationDialog by remember { mutableStateOf(false) }
    var showAppointmentDialog by remember { mutableStateOf(false) }
    var showExamDialog by remember { mutableStateOf(false) }
    var showWeightDialog by remember { mutableStateOf(false) }
    var showFeedingDialog by remember { mutableStateOf(false) }
    var showVaccineDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var selectedMedForAdmin by remember { mutableStateOf<Medication?>(null) }

    // Full screen Vaccination History screen if requested
    if (showVaccinationHistoryScreen) {
        BackHandler {
            showVaccinationHistoryScreen = false
        }
        VaccinationHistoryScreen(
            vaccines = vaccines,
            onBackClick = { showVaccinationHistoryScreen = false },
            onAddVaccine = { name, type, date, dose, next, vet, clinic, notes, reminder ->
                viewModel.addVaccine(name, type, date, dose, next, vet, clinic, notes, reminder)
            },
            onUpdateVaccine = { record, reminder ->
                viewModel.updateVaccine(record, reminder)
            },
            onDeleteVaccine = { id ->
                viewModel.deleteVaccine(id)
            }
        )
        return
    }

    // Intercept back press when on secondary tab
    BackHandler(enabled = currentTab != 0) {
        currentTab = 0
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Pets,
                                contentDescription = null,
                                tint = PureWhite,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "STELLA",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.sp,
                                color = PureWhite
                            )
                        }
                        Text(
                            text = "Tudo sobre ela, em um só lugar.",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = CreamOffWhite.copy(alpha = 0.85f)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (isSyncPaused) {
                                viewModel.checkSyncApprovalOrPromptDecision()
                            } else if (driveConnectionStatus == DriveConnectionStatus.CONFIGURED) {
                                viewModel.syncToGoogleDrive()
                            } else if (driveConnectionStatus == DriveConnectionStatus.VERIFYING ||
                                       driveConnectionStatus == DriveConnectionStatus.UNVERIFIED) {
                                viewModel.verifyDriveConnection()
                            } else if (driveConnectionStatus == DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE) {
                                viewModel.configureDriveFolder()
                            } else {
                                isRecoveringDriveAuth = false
                                googleSignInLauncher.launch(viewModel.driveSyncManager.getGoogleSignInClient().signInIntent)
                            }
                        },
                        modifier = Modifier.testTag("app_drive_sync_button")
                    ) {
                        Icon(
                            imageVector = if (isSyncPaused) Icons.Default.PauseCircle else Icons.Default.CloudSync,
                            contentDescription = if (isSyncPaused) "Sincronização Pausada (Decidir Agora)" else "Sincronizar Google Drive",
                            tint = PureWhite
                        )
                    }
                    IconButton(
                        onClick = { showSearchDialog = true },
                        modifier = Modifier.testTag("app_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Busca Global",
                            tint = PureWhite
                        )
                    }
                    IconButton(
                        onClick = { showReportDialog = true },
                        modifier = Modifier.testTag("app_report_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Assignment,
                            contentDescription = "Relatório de saúde do pet",
                            tint = PureWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = BordeauxPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = PureWhite,
                contentColor = BordeauxPrimary,
                tonalElevation = 6.dp,
                modifier = Modifier.testTag("stella_bottom_navigation")
            ) {
                val navItems = listOf(
                    NavigationItem("Início", Icons.Default.Home, 0),
                    NavigationItem("Glicemia", Icons.Default.Speed, 1),
                    NavigationItem("Remédios", Icons.Default.Medication, 2),
                    NavigationItem("Saúde", Icons.Default.LocalHospital, 3),
                    NavigationItem("Stella", Icons.Default.Pets, 4)
                )

                navItems.forEach { item ->
                    val isSelected = currentTab == item.tabIndex
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = item.tabIndex },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BordeauxPrimary,
                            selectedTextColor = BordeauxPrimary,
                            indicatorColor = BordeauxSoftContainer,
                            unselectedIconColor = GraySecondary,
                            unselectedTextColor = GraySecondary
                        ),
                        modifier = Modifier.testTag("nav_item_${item.label.lowercase()}")
                    )
                }
            }
        },
        containerColor = CreamOffWhite
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(modifier = Modifier.fillMaxSize().widthIn(max = 720.dp)) {
                when (currentTab) {
                    0 -> HomeScreen(
                        profile = profile,
                        glucoseRecords = glucoseRecords,
                        medications = medications,
                        appointments = appointments,
                        documents = documents,
                        feedingRecords = feedingRecords,
                        weightRecords = weightRecords,
                        vaccines = vaccines,
                        reminders = reminders,
                        onQuickAddGlucose = { showGlucoseDialog = true },
                        onQuickAddMedication = { showMedicationDialog = true },
                        onQuickAddVaccine = { showVaccineDialog = true },
                        onQuickAddAppointment = { showAppointmentDialog = true },
                        onQuickAddExam = { showExamDialog = true },
                        onQuickAddWeight = { showWeightDialog = true },
                        onQuickAddReminder = {
                            requestNotificationPermission()
                            showReminderDialog = true
                        },
                        onRecordAdministration = { med -> selectedMedForAdmin = med },
                        onToggleReminder = { rem -> viewModel.toggleReminderStatus(rem) },
                        onNavigateToSection = { section ->
                            when (section) {
                                "Glicemia" -> currentTab = 1
                                "Medicamentos" -> currentTab = 2
                                "Saúde", "Consultas", "Exames" -> currentTab = 3
                                "Vacinas" -> showVaccinationHistoryScreen = true
                                "Perfil", "Alimentação", "Stella" -> currentTab = 4
                                else -> currentTab = 0
                            }
                        }
                    )
                    1 -> GlucoseScreen(
                        records = glucoseRecords,
                        onAddGlucoseClick = { showGlucoseDialog = true },
                        onDeleteGlucoseClick = { id -> viewModel.deleteGlucose(id) }
                    )
                    2 -> MedicationsScreen(
                        medications = medications,
                        administrations = administrations,
                        onAddMedicationClick = { showMedicationDialog = true },
                        onRecordAdministrationClick = { med -> selectedMedForAdmin = med },
                        onSuspendMedication = { med, reason -> viewModel.suspendMedication(med, reason) },
                        onReactivateMedication = { med -> viewModel.reactivateMedication(med) },
                        onDeleteMedication = { id -> viewModel.deleteMedication(id) },
                        onDeleteAdministration = { id -> viewModel.deleteAdministration(id) }
                    )
                    3 -> HealthHubScreen(
                        appointments = appointments,
                        documents = documents,
                        weights = weightRecords,
                        vaccines = vaccines,
                        onAddAppointmentClick = { showAppointmentDialog = true },
                        onAddDocumentClick = { showExamDialog = true },
                        onEditDocument = { doc -> editingExamDocument = doc },
                        onAddWeightClick = { showWeightDialog = true },
                        onAddVaccineClick = { showVaccineDialog = true },
                        onEditVaccine = { vac -> editingVaccineRecord = vac },
                        onDeleteAppointment = { id -> viewModel.deleteAppointment(id) },
                        onDeleteDocument = { id -> viewModel.deleteExamDocument(id) },
                        onDeleteWeight = { id -> viewModel.deleteWeightRecord(id) },
                        onDeleteVaccine = { id -> viewModel.deleteVaccine(id) },
                        attachmentActions = attachmentActions
                    )
                    4 -> ProfileAndMoreScreen(
                        profile = profile,
                        glucoseRecords = glucoseRecords,
                        medications = medications,
                        appointments = appointments,
                        documents = documents,
                        feedingRecords = feedingRecords,
                        weightRecords = weightRecords,
                        vaccines = vaccines,
                        reminders = reminders,
                        driveSyncState = driveSyncState,
                        driveConnectionStatus = driveConnectionStatus,
                        driveAccountEmail = driveAccountEmail,
                        lastSyncTimestamp = lastSyncTimestamp,
                        hasPendingChanges = hasPendingChanges,
                        isSyncPaused = isSyncPaused,
                        onSyncToDrive = { viewModel.syncToGoogleDrive() },
                        onConnectDriveAccount = {
                            isRecoveringDriveAuth = false
                            googleSignInLauncher.launch(viewModel.driveSyncManager.getGoogleSignInClient().signInIntent)
                        },
                        onVerifyDriveConnection = { viewModel.verifyDriveConnection() },
                        onDisconnectDriveAccount = { viewModel.disconnectGoogleDrive() },
                        onSwitchDriveAccount = {
                            viewModel.prepareForDriveAccountSwitch {
                                isRecoveringDriveAuth = false
                                googleSignInLauncher.launch(viewModel.driveSyncManager.getGoogleSignInClient().signInIntent)
                            }
                        },
                        onConfigureDriveFolder = { viewModel.configureDriveFolder() },
                        onDecideNowClick = { viewModel.checkSyncApprovalOrPromptDecision() },
                        onBackupVersionsClick = { showBackupVersions = true },
                        canDeleteCloud = canDeleteCloud,
                        canDeleteDevice = canDeleteDevice,
                        cloudFilesCountState = cloudFilesCountState,
                        attachmentCount = allAttachments.size,
                        brokenAttachmentCount = brokenAttachments.size,
                        onRefreshBrokenAttachments = { viewModel.refreshBrokenAttachments() },
                        onLoadCloudFilesCount = { viewModel.loadCloudFilesCount() },
                        onDeleteCloudData = { viewModel.deleteCloudData() },
                        onDeleteDeviceData = { viewModel.deleteDeviceData() },
                        onDeleteBothData = { viewModel.deleteDeviceAndCloudData() },
                        onUpdateProfile = { updated ->
                            viewModel.updateProfile(updated)
                        },
                        onSetProfilePhoto = { uri, onResult -> viewModel.setProfilePhoto(uri, onResult) },
                        onAddFeedingClick = { showFeedingDialog = true },
                        onDeleteFeeding = { id ->
                            viewModel.deleteFeedingRecord(id)
                        },
                        onAddReminderClick = {
                            requestNotificationPermission()
                            showReminderDialog = true
                        },
                        onToggleReminder = { rem ->
                            viewModel.toggleReminderStatus(rem)
                        },
                        onDeleteReminder = { id ->
                            viewModel.deleteReminder(id)
                        },
                        onOpenMedicalReport = { showReportDialog = true },
                        refreshRateMode = refreshRateMode,
                        onRefreshRateModeChanged = { newMode ->
                            RefreshRateManager.saveMode(context, newMode)
                            (context as? Activity)?.let { act ->
                                RefreshRateManager.applyToWindow(act, newMode)
                            }
                            refreshRateMode = newMode
                        }
                    )
                }
            }
        }
    }

    // --- Interactive Entry Dialogs ---
    if (showGlucoseDialog) {
        AddGlucoseDialog(
            onDismiss = { showGlucoseDialog = false },
            onSave = { date, time, value, unit, meal, med, notes, symptoms ->
                viewModel.addGlucose(date, time, value, unit, meal, med, notes, symptoms)
            }
        )
    }

    if (showMedicationDialog) {
        AddMedicationDialog(
            onDismiss = { showMedicationDialog = false },
            onSave = { name, active, dosage, unit, route, qty, freq, times, start, end, vet, reason, notes, isVitamin ->
                viewModel.addMedication(name, active, dosage, unit, route, qty, freq, times, start, end, vet, reason, notes, isVitamin)
            }
        )
    }

    if (showAppointmentDialog) {
        AddAppointmentDialog(
            onDismiss = { showAppointmentDialog = false },
            onSave = { date, time, vet, clinic, reason, weight, temp, notes, symptoms, conduct, presc, nextDate, attachments ->
                viewModel.addAppointment(
                    date, time, vet, clinic, reason, weight, temp, notes, symptoms, conduct, presc, nextDate, attachments
                )
            },
            onStageAttachment = { uri, label, onResult -> viewModel.stageAttachment(uri, label, onResult) },
            onDiscardAttachment = { viewModel.discardStagedAttachment(it) }
        )
    }

    if (showExamDialog) {
        AddExamDialog(
            initialDocument = null,
            onDismiss = { showExamDialog = false },
            onSave = { name, type, date, clinic, vet, cat, notes, uri, attachments ->
                viewModel.addExamDocument(name, type, date, clinic, vet, cat, notes, uri, attachments)
            },
            onStageAttachment = { uri, label, onResult -> viewModel.stageAttachment(uri, label, onResult) },
            onDiscardAttachment = { viewModel.discardStagedAttachment(it) }
        )
    }

    editingExamDocument?.let { docToEdit ->
        AddExamDialog(
            initialDocument = docToEdit,
            onDismiss = { editingExamDocument = null },
            attachmentActions = attachmentActions,
            onSave = { name, type, date, clinic, vet, cat, notes, uri, _ ->
                viewModel.updateExamDocument(
                    docToEdit.copy(
                        name = name,
                        fileType = type,
                        dateString = date,
                        clinicName = clinic,
                        vetName = vet,
                        category = cat,
                        notes = notes,
                        fileUriOrPath = uri
                    )
                )
                editingExamDocument = null
            }
        )
    }

    if (showWeightDialog) {
        AddWeightDialog(
            currentWeight = profile.currentWeight,
            onDismiss = { showWeightDialog = false },
            onSave = { date, weight, notes ->
                viewModel.addWeightRecord(date, weight, notes)
            }
        )
    }

    if (showFeedingDialog) {
        AddFeedingDialog(
            onDismiss = { showFeedingDialog = false },
            onSave = { brand, food, type, line, flavor, daily, portion, meals, times, start, notes ->
                viewModel.addFeedingRecord(brand, food, type, line, flavor, daily, portion, meals, times, start, notes)
            }
        )
    }

    if (showVaccineDialog) {
        AddOrEditVaccineDialog(
            initialRecord = null,
            onDismiss = { showVaccineDialog = false },
            onSave = { record, scheduleReminder ->
                val success = viewModel.addVaccine(
                    record.name,
                    record.type,
                    record.dateAdministered,
                    record.dose,
                    record.nextDueDate,
                    record.vetName,
                    record.clinicName,
                    record.notes,
                    scheduleReminder
                )
                if (success) {
                    showVaccineDialog = false
                }
            }
        )
    }

    editingVaccineRecord?.let { recordToEdit ->
        AddOrEditVaccineDialog(
            initialRecord = recordToEdit,
            onDismiss = { editingVaccineRecord = null },
            onSave = { updated, scheduleReminder ->
                val success = viewModel.updateVaccine(updated, scheduleReminder)
                if (success) {
                    editingVaccineRecord = null
                }
            }
        )
    }

    if (showReminderDialog) {
        AddReminderDialog(
            onDismiss = { showReminderDialog = false },
            onSave = { title, category, date, time, notes ->
                viewModel.addReminder(title, category, date, time, notes)
            }
        )
    }

    if (showSearchDialog) {
        GlobalSearchDialog(
            glucoseRecords = glucoseRecords,
            medications = medications,
            appointments = appointments,
            documents = documents,
            feeding = feedingRecords,
            vaccines = vaccines,
            onDismiss = { showSearchDialog = false },
            onNavigateToSection = { section ->
                when (section) {
                    "Glicemia" -> currentTab = 1
                    "Medicamentos" -> currentTab = 2
                    "Consultas", "Exames" -> currentTab = 3
                    "Vacinas" -> showVaccinationHistoryScreen = true
                    "Alimentação" -> currentTab = 4
                    else -> currentTab = 0
                }
            }
        )
    }

    if (showReportDialog) {
        MedicalReportDialog(
            profile = profile,
            glucoseRecords = glucoseRecords,
            medications = medications,
            appointments = appointments,
            documents = documents,
            feeding = feedingRecords,
            weights = weightRecords,
            onDismiss = { showReportDialog = false }
        )
    }

    selectedMedForAdmin?.let { med ->
        RecordAdministrationDialog(
            medication = med,
            onDismiss = { selectedMedForAdmin = null },
            onConfirm = { date, time, dosage, regBy, notes ->
                viewModel.recordMedicationAdministration(
                    medicationId = med.id,
                    medicationName = med.name,
                    dosage = dosage,
                    dateString = date,
                    timeString = time,
                    registeredBy = regBy,
                    notes = notes
                )
                selectedMedForAdmin = null
            }
        )
    }

    if (showBackupVersions) {
        BackupVersionsDialog(
            state = backupVersions,
            syncState = driveSyncState,
            actionInProgress = decisionActionInProgress,
            onReload = { viewModel.loadBackupVersions() },
            onPreview = { fileId, onResult -> viewModel.previewBackupVersion(fileId, onResult) },
            onRestore = { fileId, replace, onComplete ->
                viewModel.restoreBackupVersion(fileId, replace, onComplete)
            },
            onDismiss = { showBackupVersions = false }
        )
    }

    // Andamento e resultado de "apagar" (nuvem, aparelho ou os dois). Tem prioridade sobre a decisão abaixo.
    DataDeletionStatusDialog(
        state = dataDeletion,
        onDismiss = { viewModel.dismissDataDeletionResult() }
    )

    // Diálogo "O que fazer com a nuvem?" (decisão de sincronização). Um diálogo por vez:
    // enquanto apaga ou mostra o resultado, ele espera (volta sozinho se a decisão ainda estiver pendente).
    if (dataDeletion is DataDeletionState.Idle) DriveSyncDecisionDialog(
        decisionState = syncDecisionState,
        syncState = driveSyncState,
        actionInProgress = decisionActionInProgress,
        errorMessage = uiErrorMessage,
        onConfirmRestore = {
            viewModel.clearUiErrorMessage()
            viewModel.confirmRestoreFromDrive(replaceExisting = true)
        },
        onConfirmOverwrite = {
            viewModel.clearUiErrorMessage()
            viewModel.confirmOverwriteDriveBackup()
        },
        onDismissDecision = {
            viewModel.clearUiErrorMessage()
            viewModel.dismissSyncDecision()
        },
        onRetryCheck = {
            viewModel.checkSyncApprovalOrPromptDecision()
        }
    )
}

private data class NavigationItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tabIndex: Int
)
