package com.example.ui.screens

import com.example.util.DateInput
import com.example.ui.components.DateTextField
import com.example.ui.components.TimeTextField
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.ExamDocument
import com.example.data.model.FeedingRecord
import com.example.data.model.GlucoseRecord
import com.example.data.model.Medication
import com.example.data.model.Reminder
import com.example.data.model.StellaProfile
import com.example.data.model.VaccinePreventionRecord
import com.example.data.model.VeterinaryAppointment
import com.example.data.model.WeightRecord
import com.example.data.drive.DriveConnectionStatus
import com.example.data.drive.DriveSyncState
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.components.DeleteDataSection
import com.example.ui.components.MissingAttachmentsNotice
import com.example.ui.components.profilePhotoModel
import com.example.data.attachments.ImportResult
import com.example.ui.components.DisplayRefreshRateCard
import com.example.viewmodel.CloudFilesCountState
import com.example.ui.components.GoogleDriveSyncCard
import com.example.ui.components.SyncPausedBanner
import com.example.util.RefreshRateMode
import com.example.viewmodel.TimelineItem
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
import kotlin.math.roundToInt

@Composable
fun ProfileAndMoreScreen(
    profile: StellaProfile,
    glucoseRecords: List<GlucoseRecord>,
    medications: List<Medication>,
    appointments: List<VeterinaryAppointment>,
    documents: List<ExamDocument>,
    feedingRecords: List<FeedingRecord>,
    weightRecords: List<WeightRecord>,
    vaccines: List<VaccinePreventionRecord>,
    reminders: List<Reminder>,
    driveSyncState: DriveSyncState,
    driveConnectionStatus: DriveConnectionStatus = DriveConnectionStatus.NOT_CONNECTED,
    driveAccountEmail: String?,
    lastSyncTimestamp: String?,
    hasPendingChanges: Boolean = false,
    isSyncPaused: Boolean = false,
    onSyncToDrive: () -> Unit,
    onConnectDriveAccount: () -> Unit,
    onVerifyDriveConnection: () -> Unit = {},
    onDisconnectDriveAccount: () -> Unit = {},
    onSwitchDriveAccount: () -> Unit = {},
    onConfigureDriveFolder: () -> Unit = {},
    onDecideNowClick: () -> Unit = {},
    onBackupVersionsClick: () -> Unit = {},
    canDeleteCloud: Boolean = false,
    canDeleteDevice: Boolean = true,
    cloudFilesCountState: CloudFilesCountState = CloudFilesCountState.Idle,
    attachmentCount: Int = 0,
    brokenAttachmentCount: Int = 0,
    onRefreshBrokenAttachments: () -> Unit = {},
    onLoadCloudFilesCount: () -> Unit = {},
    onDeleteCloudData: () -> Unit = {},
    onDeleteDeviceData: () -> Unit = {},
    onDeleteBothData: () -> Unit = {},
    onUpdateProfile: (StellaProfile) -> Unit,
    onSetProfilePhoto: (uri: String, onResult: (ImportResult) -> Unit) -> Unit = { _, _ -> },
    onAddFeedingClick: () -> Unit,
    onDeleteFeeding: (Long) -> Unit,
    onAddReminderClick: () -> Unit,
    onToggleReminder: (Reminder) -> Unit,
    onDeleteReminder: (Long) -> Unit,
    onOpenMedicalReport: () -> Unit,
    refreshRateMode: RefreshRateMode = RefreshRateMode.AUTO,
    onRefreshRateModeChanged: (RefreshRateMode) -> Unit = {}
) {
    val subTabs = listOf(
        "Sobre a Stella",
        "Linha do Tempo",
        "Alimentação (${feedingRecords.size})",
        "Lembretes & Config"
    )
    val pagerState = rememberPagerState(initialPage = 0) { subTabs.size }
    val coroutineScope = rememberCoroutineScope()
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var deleteFeedingTarget by remember { mutableStateOf<FeedingRecord?>(null) }
    var deleteReminderTarget by remember { mutableStateOf<Reminder?>(null) }

    // Ao abrir a aba "Lembretes & Ajustes", confere quais anexos perderam o arquivo.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 3) onRefreshBrokenAttachments()
    }

    val photoContext = androidx.compose.ui.platform.LocalContext.current
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            // Copia a imagem para dentro do app: o acesso da galeria é temporário e a foto sumiria depois.
            onSetProfilePhoto(uri.toString()) { result ->
                if (result is ImportResult.Rejected) {
                    android.widget.Toast.makeText(photoContext, result.message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val calculatedAge = remember(profile.birthDate) {
        calculateAgeString(profile.birthDate)
    }

    Column(modifier = Modifier.fillMaxSize().background(CreamOffWhite)) {
        if (isSyncPaused) {
            SyncPausedBanner(
                onDecideNowClick = onDecideNowClick,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Tab Header
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = PureWhite,
            contentColor = BordeauxPrimary,
            edgePadding = 16.dp,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                    color = BordeauxPrimary
                )
            }
        ) {
            subTabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (pagerState.currentPage == index) BordeauxPrimary else GraySecondary
                        )
                    }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
            0 -> {
                // "Sobre a Stella" - Section 4 & 22
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = PureWhite,
                            border = BorderStroke(1.dp, GrayBorder),
                            shadowElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Large Profile Photo with photo picker badge
                                Box(
                                    modifier = Modifier
                                        .size(130.dp)
                                        .clip(CircleShape)
                                        .border(4.dp, BordeauxPrimary, CircleShape)
                                        .clickable {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                        .testTag("stella_profile_photo"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (profile.photoUri != null && profile.photoUri.isNotBlank()) {
                                        AsyncImage(
                                            model = profilePhotoModel(profile.photoUri),
                                            contentDescription = "Foto do pet",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_pet_placeholder),
                                            contentDescription = "Foto do pet",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }

                                    // Camera icon badge
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(36.dp)
                                            .background(BordeauxPrimary, CircleShape)
                                            .border(2.dp, PureWhite, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.CameraAlt, null, tint = PureWhite, modifier = Modifier.size(18.dp))
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BordeauxDark
                                )

                                Text(
                                    text = "${profile.species} • ${profile.breed} • ${profile.gender}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = GraySecondary
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Idade: $calculatedAge (Nasc: ${profile.birthDate})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BordeauxPrimary,
                                    fontWeight = FontWeight.Medium
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedButton(
                                    onClick = { showEditProfileDialog = true },
                                    border = BorderStroke(1.dp, BordeauxPrimary),
                                    modifier = Modifier.testTag("edit_profile_button")
                                ) {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = BordeauxPrimary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Editar Informações", color = BordeauxPrimary)
                                }
                            }
                        }
                    }

                    // Clinical & Cadastral Specs
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = PureWhite),
                            border = BorderStroke(1.dp, GrayBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Informações Cadastrais",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = BordeauxDark
                                )

                                InfoRow(label = "Pelagem / Cor", value = profile.color)
                                InfoRow(label = "Microchip", value = profile.microchip.ifBlank { "Não informado" })
                                InfoRow(label = "Nº Cadastro Vet", value = profile.vetRegistrationNumber.ifBlank { "Não informado" })
                                InfoRow(label = "Veterinário(a)", value = profile.vetName)
                                InfoRow(label = "Clínica Veterinária", value = profile.clinicName)
                                InfoRow(label = "Telefone da Clínica", value = profile.clinicPhone)
                            }
                        }
                    }

                    // Personal & Affective Specs (Section 22: Sobre a Stella)
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = PureWhite),
                            border = BorderStroke(1.dp, GrayBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Favorite, null, tint = BordeauxPrimary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Personalidade & Curiosidades",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = BordeauxDark
                                    )
                                }

                                if (profile.personality.isNotBlank()) {
                                    Text(text = "Comportamento:", style = MaterialTheme.typography.labelSmall, color = BordeauxPrimary, fontWeight = FontWeight.Bold)
                                    Text(text = profile.personality, style = MaterialTheme.typography.bodySmall, color = GraySecondary)
                                }

                                if (profile.curiosities.isNotBlank()) {
                                    Text(text = "Curiosidades e Gostos:", style = MaterialTheme.typography.labelSmall, color = BordeauxPrimary, fontWeight = FontWeight.Bold)
                                    Text(text = profile.curiosities, style = MaterialTheme.typography.bodySmall, color = GraySecondary)
                                }

                                if (profile.notes.isNotBlank()) {
                                    Text(text = "Observações Importantes:", style = MaterialTheme.typography.labelSmall, color = BordeauxPrimary, fontWeight = FontWeight.Bold)
                                    Text(text = profile.notes, style = MaterialTheme.typography.bodySmall, color = GraySecondary)
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // "Linha do Tempo Médica" - Section 10
                val timelineItems = remember(glucoseRecords, medications, appointments, documents, feedingRecords, weightRecords, vaccines) {
                    val list = mutableListOf<TimelineItem>()

                    glucoseRecords.forEach {
                        list.add(
                            TimelineItem(
                                id = "glu_${it.id}",
                                title = "Medição de glicemia — ${it.value.roundToInt()} ${it.unit}",
                                subtitle = "${it.mealRelation} ${if (it.notes.isNotBlank()) "• ${it.notes}" else ""}",
                                dateString = "${it.dateString} às ${it.timeString}",
                                timestamp = it.dateTimeMillis,
                                category = "GLICEMIA"
                            )
                        )
                    }

                    appointments.forEach {
                        list.add(
                            TimelineItem(
                                id = "apt_${it.id}",
                                title = "Consulta veterinária: ${it.reason}",
                                subtitle = "Dr(a). ${it.vetName} • ${it.clinicName}",
                                dateString = "${it.dateString} às ${it.timeString}",
                                timestamp = it.timestamp,
                                category = "CONSULTA"
                            )
                        )
                    }

                    medications.forEach {
                        list.add(
                            TimelineItem(
                                id = "med_${it.id}",
                                title = "Medicamento iniciado: ${it.name} (${it.dosage})",
                                subtitle = "Frequência: ${it.frequency} • Motivo: ${it.reason}",
                                dateString = it.startDate,
                                timestamp = it.createdAt,
                                category = "MEDICAMENTO"
                            )
                        )
                        if (it.status == "ENCERRADO" && it.suspensionDate.isNotBlank()) {
                            list.add(
                                TimelineItem(
                                    id = "med_end_${it.id}",
                                    title = "Medicamento encerrado: ${it.name}",
                                    subtitle = "Motivo: ${it.suspensionReason.ifBlank { "Término prescrito" }}",
                                    dateString = it.suspensionDate,
                                    timestamp = it.createdAt + 1000,
                                    category = "MEDICAMENTO_ENCERRADO"
                                )
                            )
                        }
                    }

                    documents.forEach {
                        list.add(
                            TimelineItem(
                                id = "doc_${it.id}",
                                title = "Exame / Documento: ${it.name}",
                                subtitle = "Categoria: ${it.category} • ${it.notes}",
                                dateString = it.dateString,
                                timestamp = it.createdAt,
                                category = "EXAME"
                            )
                        )
                    }

                    weightRecords.forEach {
                        list.add(
                            TimelineItem(
                                id = "wei_${it.id}",
                                title = "Pesagem registrada: ${it.weightKg} kg",
                                subtitle = it.notes.ifBlank { "Aferição de rotina" },
                                dateString = it.dateString,
                                timestamp = it.timestamp,
                                category = "PESO"
                            )
                        )
                    }

                    vaccines.forEach {
                        list.add(
                            TimelineItem(
                                id = "vac_${it.id}",
                                title = "Aplicação de ${it.type}: ${it.name}",
                                subtitle = "Dose: ${it.dose} • Próxima: ${it.nextDueDate}",
                                dateString = it.dateAdministered,
                                timestamp = it.createdAt,
                                category = "VACINA"
                            )
                        )
                    }

                    list.sortedByDescending { it.timestamp }
                }

                if (timelineItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "A linha do tempo reunirá automaticamente todas as consultas, exames, medições de glicemia, vacinas e medicações à medida que forem adicionadas.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GraySecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(timelineItems) { item ->
                            TimelineCard(item = item)
                        }
                    }
                }
            }

            2 -> {
                // "Alimentação" - Section 11
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Nutrição & Alimentação",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BordeauxDark
                                )
                                Text(
                                    text = "Dieta atual e histórico permanente de rações",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GraySecondary
                                )
                            }
                            Button(
                                onClick = onAddFeedingClick,
                                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cadastrar Ração", color = PureWhite)
                            }
                        }
                    }

                    if (feedingRecords.isEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, GrayBorder)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Restaurant, null, tint = BordeauxLight, modifier = Modifier.size(40.dp))
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("Nenhuma ração cadastrada ainda.", style = MaterialTheme.typography.bodyMedium, color = GraySecondary)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(onClick = onAddFeedingClick, colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)) {
                                        Text("Cadastrar Ração Atual", color = PureWhite)
                                    }
                                }
                            }
                        }
                    } else {
                        items(feedingRecords.sortedByDescending { it.isCurrent }) { food ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, if (food.isCurrent) BordeauxPrimary else GrayBorder),
                                shadowElevation = if (food.isCurrent) 2.dp else 1.dp
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "${food.brand} - ${food.foodName}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = BordeauxDark
                                            )
                                            Text(
                                                text = "${food.type} • Linha: ${food.line} • Sabor: ${food.flavor}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = GraySecondary
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (food.isCurrent) SoftGreenContainer else GrayBorder
                                        ) {
                                            Text(
                                                text = if (food.isCurrent) "EM USO ATUAL" else "ANTERIOR",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (food.isCurrent) SoftGreen else GraySecondary,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(shape = RoundedCornerShape(8.dp), color = CreamOffWhite, modifier = Modifier.weight(1f)) {
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                Text("Qtd. Diária", style = MaterialTheme.typography.labelSmall, color = GraySecondary)
                                                Text(food.dailyAmount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = BordeauxDark)
                                            }
                                        }
                                        Surface(shape = RoundedCornerShape(8.dp), color = CreamOffWhite, modifier = Modifier.weight(1f)) {
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                Text("Refeições", style = MaterialTheme.typography.labelSmall, color = GraySecondary)
                                                Text("${food.mealsPerDay}x ao dia (${food.portionAmount})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = BordeauxDark)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Horários: ${food.scheduleTimes}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BordeauxPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Início: ${food.startDate} ${if (food.endDate.isNotBlank()) "• Término: ${food.endDate}" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GraySecondary
                                    )

                                    if (food.notes.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(text = "Obs: ${food.notes}", style = MaterialTheme.typography.bodySmall, color = GraySecondary)
                                    }

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        IconButton(onClick = { deleteFeedingTarget = food }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = GraySecondary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3 -> {
                // "Lembretes & Configurações" - Section 7 & 23
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Google Drive Sync Card
                    item {
                        GoogleDriveSyncCard(
                            connectionStatus = driveConnectionStatus,
                            syncState = driveSyncState,
                            accountEmail = driveAccountEmail,
                            lastSyncTimestamp = lastSyncTimestamp,
                            hasPendingChanges = hasPendingChanges,
                            isSyncPaused = isSyncPaused,
                            onSyncClick = onSyncToDrive,
                            onConnectClick = onConnectDriveAccount,
                            onVerifyClick = onVerifyDriveConnection,
                            onConfigureFolderClick = onConfigureDriveFolder,
                            onSwitchAccountClick = onSwitchDriveAccount,
                            onDisconnectClick = onDisconnectDriveAccount,
                            onDecideNowClick = onDecideNowClick,
                            onBackupVersionsClick = onBackupVersionsClick
                        )
                    }

                    // Medical Report Card
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BordeauxDark,
                            border = BorderStroke(1.dp, BordeauxLight)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Relatório de Saúde do Pet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PureWhite
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Gere e exporte o prontuário completo para apresentar em consultas veterinárias.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamOffWhite.copy(alpha = 0.85f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = onOpenMedicalReport,
                                    colors = ButtonDefaults.buttonColors(containerColor = BordeauxLight)
                                ) {
                                    Icon(Icons.Default.Description, null, tint = PureWhite)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Visualizar / Exportar Relatório", color = PureWhite)
                                }
                            }
                        }
                    }

                    // Display Refresh Rate Card (60, 90, 120 Hz support)
                    item {
                        DisplayRefreshRateCard(
                            currentMode = refreshRateMode,
                            onModeSelected = onRefreshRateModeChanged
                        )
                    }

                    // Reminders Section
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Lembretes & Tarefas",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = BordeauxDark
                            )
                            Button(
                                onClick = onAddReminderClick,
                                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Novo Lembrete", fontSize = 11.sp)
                            }
                        }
                    }

                    if (reminders.isEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, GrayBorder)
                            ) {
                                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Nenhum lembrete cadastrado.", style = MaterialTheme.typography.bodyMedium, color = GraySecondary)
                                }
                            }
                        }
                    } else {
                        items(reminders) { rem ->
                            val isDone = rem.status == "REALIZADO"
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, GrayBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { onToggleReminder(rem) }) {
                                        Icon(
                                            imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isDone) SoftGreen else GraySecondary
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = rem.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDone) GraySecondary else BordeauxDark,
                                            textDecoration = if (isDone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                        )
                                        Text(
                                            text = "${rem.category} • ${rem.dateString} às ${rem.timeString}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GraySecondary
                                        )
                                    }
                                    IconButton(onClick = { deleteReminderTarget = rem }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = GraySecondary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Security & Storage info
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = PureWhite,
                            border = BorderStroke(1.dp, GrayBorder)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Security, null, tint = SoftGreen, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Segurança & Armazenamento", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = BordeauxDark)
                                }
                                Text(
                                    text = "Os dados ficam armazenados localmente neste aparelho, em um banco Room (SQLite) sem criptografia própria. Nada vai para a nuvem a menos que você vincule o Google Drive.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GraySecondary
                                )
                            }
                        }
                    }

                    if (brokenAttachmentCount > 0) {
                        item { MissingAttachmentsNotice(count = brokenAttachmentCount) }
                    }

                    // Privacidade e dados: apagar a nuvem, este aparelho ou os dois (3 confirmações cada)
                    item {
                        DeleteDataSection(
                            canDeleteCloud = canDeleteCloud,
                            canDeleteDevice = canDeleteDevice,
                            cloudFilesCount = cloudFilesCountState,
                            localRecordCount = glucoseRecords.size + medications.size + appointments.size +
                                documents.size + feedingRecords.size + weightRecords.size +
                                vaccines.size + reminders.size,
                            localAttachmentCount = attachmentCount,
                            onLoadCloudFilesCount = onLoadCloudFilesCount,
                            onDeleteCloud = onDeleteCloudData,
                            onDeleteDevice = onDeleteDeviceData,
                            onDeleteBoth = onDeleteBothData
                        )
                    }
                }
            }
        }
    }
}

    // Dialogs
    deleteFeedingTarget?.let { feed ->
        ConfirmDeleteDialog(
            title = "Excluir Registro de Ração",
            message = "Deseja remover '${feed.brand} ${feed.foodName}'?",
            onConfirm = { onDeleteFeeding(feed.id) },
            onDismiss = { deleteFeedingTarget = null }
        )
    }

    deleteReminderTarget?.let { rem ->
        ConfirmDeleteDialog(
            title = "Excluir Lembrete",
            message = "Deseja remover '${rem.title}'?",
            onConfirm = { onDeleteReminder(rem.id) },
            onDismiss = { deleteReminderTarget = null }
        )
    }

    if (showEditProfileDialog) {
        EditProfileDialog(
            profile = profile,
            onDismiss = { showEditProfileDialog = false },
            onSave = { updated ->
                onUpdateProfile(updated)
                showEditProfileDialog = false
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = GraySecondary)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = BordeauxDark)
    }
}

@Composable
private fun TimelineCard(item: com.example.viewmodel.TimelineItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon, badgeColor) = when (item.category) {
                "GLICEMIA" -> Icons.Default.Speed to BordeauxPrimary
                "CONSULTA" -> Icons.Default.Event to BordeauxDark
                "MEDICAMENTO" -> Icons.Default.Medication to BordeauxLight
                "MEDICAMENTO_ENCERRADO" -> Icons.Default.Medication to GraySecondary
                "EXAME" -> Icons.Default.Description to DiscreetGold
                "PESO" -> Icons.Default.MonitorWeight to SoftGreen
                "VACINA" -> Icons.Default.Security to SoftGreen
                else -> Icons.Default.Pets to BordeauxPrimary
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(badgeColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = badgeColor, modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = GraySecondary
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = GraySecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun EditProfileDialog(
    profile: StellaProfile,
    onDismiss: () -> Unit,
    onSave: (StellaProfile) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var breed by remember { mutableStateOf(profile.breed) }
    var gender by remember { mutableStateOf(profile.gender) }
    var birthDate by remember { mutableStateOf(profile.birthDate) }
    var color by remember { mutableStateOf(profile.color) }
    var microchip by remember { mutableStateOf(profile.microchip) }
    var vetReg by remember { mutableStateOf(profile.vetRegistrationNumber) }
    var vetName by remember { mutableStateOf(profile.vetName) }
    var clinicName by remember { mutableStateOf(profile.clinicName) }
    var clinicPhone by remember { mutableStateOf(profile.clinicPhone) }
    var personality by remember { mutableStateOf(profile.personality) }
    var curiosities by remember { mutableStateOf(profile.curiosities) }
    var notes by remember { mutableStateOf(profile.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Editar Perfil da Stella", fontWeight = FontWeight.Bold, color = BordeauxPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = breed, onValueChange = { breed = it }, label = { Text("Raça") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = gender, onValueChange = { gender = it }, label = { Text("Sexo") }, modifier = Modifier.fillMaxWidth())
                DateTextField(value = birthDate, onValueChange = { birthDate = it }, label = { Text("Data de Nascimento") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = color, onValueChange = { color = it }, label = { Text("Cor / Pelagem") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = microchip, onValueChange = { microchip = it }, label = { Text("Microchip") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = vetReg, onValueChange = { vetReg = it }, label = { Text("CRMV Veterinário") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = vetName, onValueChange = { vetName = it }, label = { Text("Veterinário Responsável") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = clinicName, onValueChange = { clinicName = it }, label = { Text("Clínica / Hospital") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = clinicPhone, onValueChange = { clinicPhone = it }, label = { Text("Telefone da Clínica") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = personality, onValueChange = { personality = it }, label = { Text("Comportamento") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = curiosities, onValueChange = { curiosities = it }, label = { Text("Curiosidades") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Observações Clínicas") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        profile.copy(
                            name = name.trim(),
                            breed = breed.trim(),
                            gender = gender.trim(),
                            birthDate = DateInput.normalizeDate(birthDate),
                            color = color.trim(),
                            microchip = microchip.trim(),
                            vetRegistrationNumber = vetReg.trim(),
                            vetName = vetName.trim(),
                            clinicName = clinicName.trim(),
                            clinicPhone = clinicPhone.trim(),
                            personality = personality.trim(),
                            curiosities = curiosities.trim(),
                            notes = notes.trim()
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
            ) {
                Text("Salvar Alterações", color = PureWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = GraySecondary)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = CreamOffWhite
    )
}
