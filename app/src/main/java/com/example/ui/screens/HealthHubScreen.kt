package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ExamDocument
import com.example.data.model.VaccinePreventionRecord
import com.example.data.model.VeterinaryAppointment
import com.example.data.model.WeightRecord
import com.example.data.model.AttachmentOwner
import com.example.ui.components.AttachmentActions
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.components.SavedAttachmentsSection
import com.example.ui.components.WeightChart
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

@Composable
fun HealthHubScreen(
    appointments: List<VeterinaryAppointment>,
    documents: List<ExamDocument>,
    weights: List<WeightRecord>,
    vaccines: List<VaccinePreventionRecord>,
    initialTab: Int = 0,
    onAddAppointmentClick: () -> Unit,
    onAddDocumentClick: () -> Unit,
    onEditDocument: ((ExamDocument) -> Unit)? = null,
    onAddWeightClick: () -> Unit,
    onAddVaccineClick: () -> Unit,
    onEditVaccine: ((VaccinePreventionRecord) -> Unit)? = null,
    onDeleteAppointment: (Long) -> Unit,
    onDeleteDocument: (Long) -> Unit,
    onDeleteWeight: (Long) -> Unit,
    onDeleteVaccine: (Long) -> Unit,
    attachmentActions: AttachmentActions? = null
) {
    val tabs = listOf(
        "Consultas (${appointments.size})",
        "Exames & Documentos (${documents.size})",
        "Controle de Peso",
        "Vacinas & Prevenção (${vaccines.size})"
    )
    val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, tabs.size - 1)) { tabs.size }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(initialTab) {
        if (initialTab in 0 until tabs.size && pagerState.currentPage != initialTab) {
            pagerState.scrollToPage(initialTab)
        }
    }

    // Ao abrir a tela, confere quais anexos perderam o arquivo.
    LaunchedEffect(Unit) { attachmentActions?.refreshBroken?.invoke() }
    var deleteAppointmentTarget by remember { mutableStateOf<VeterinaryAppointment?>(null) }
    var deleteDocumentTarget by remember { mutableStateOf<ExamDocument?>(null) }
    var deleteWeightTarget by remember { mutableStateOf<WeightRecord?>(null) }
    var deleteVaccineTarget by remember { mutableStateOf<VaccinePreventionRecord?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    when (pagerState.currentPage) {
                        0 -> onAddAppointmentClick()
                        1 -> onAddDocumentClick()
                        2 -> onAddWeightClick()
                        3 -> onAddVaccineClick()
                    }
                },
                containerColor = BordeauxPrimary,
                contentColor = PureWhite,
                modifier = Modifier.testTag("fab_health_hub")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar")
            }
        },
        containerColor = CreamOffWhite
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "Prontuário de Saúde",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
                Text(
                    text = "Consultas, laudos clínicos, evolução de peso e imunização",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary
                )
            }

            // Tab bar
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
                tabs.forEachIndexed { index, title ->
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

            // Tab Pages
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                0 -> {
                    // Appointments Tab
                    if (appointments.isEmpty()) {
                        EmptyHealthHubSection(
                            icon = Icons.Default.Event,
                            title = "Nenhuma consulta registrada",
                            subtitle = "Guarde o histórico de consultas, diagnósticos veterinários e prescrições da Stella.",
                            buttonText = "Registrar Consulta",
                            onAction = onAddAppointmentClick
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(appointments.sortedByDescending { it.timestamp }) { item ->
                                AppointmentCard(
                                    appointment = item,
                                    onDelete = { deleteAppointmentTarget = item },
                                    attachmentActions = attachmentActions
                                )
                            }
                        }
                    }
                }
                1 -> {
                    // Documents & Exams Tab
                    var selectedCatFilter by remember { mutableStateOf<String?>(null) }
                    val categories = listOf("Todas", "Exame de sangue", "Glicemia", "Ultrassom", "Receita", "Vacina", "Outros")

                    val filteredDocs = remember(documents, selectedCatFilter) {
                        if (selectedCatFilter == null || selectedCatFilter == "Todas") {
                            documents
                        } else {
                            documents.filter { it.category == selectedCatFilter }
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Category filter chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            categories.take(4).forEach { cat ->
                                val isSelected = (cat == "Todas" && selectedCatFilter == null) || selectedCatFilter == cat
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedCatFilter = if (cat == "Todas") null else cat },
                                    label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = BordeauxPrimary,
                                        selectedLabelColor = PureWhite
                                    )
                                )
                            }
                        }

                        if (filteredDocs.isEmpty()) {
                            EmptyHealthHubSection(
                                icon = Icons.Default.Description,
                                title = "Nenhum documento ou exame encontrado",
                                subtitle = "Armazene receitas, fotos de exames de sangue, laudos de ultrassom e tomografias da Stella.",
                                buttonText = "Cadastrar Exame / Documento",
                                onAction = onAddDocumentClick
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filteredDocs, key = { it.id }) { doc ->
                                    DocumentCard(
                                        document = doc,
                                        onEdit = { onEditDocument?.invoke(doc) },
                                        onDelete = { deleteDocumentTarget = doc },
                                        attachmentActions = attachmentActions
                                    )
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // Weight Tab
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, GrayBorder),
                                shadowElevation = 1.dp
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Evolução do Peso",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = BordeauxDark
                                        )
                                        Button(
                                            onClick = onAddWeightClick,
                                            colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                                            modifier = Modifier.height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                        ) {
                                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Nova Pesagem", fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    WeightChart(records = weights)
                                }
                            }
                        }

                        item {
                            Text(
                                text = "Histórico de Pesagens",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = BordeauxDark
                            )
                        }

                        if (weights.isEmpty()) {
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = PureWhite,
                                    border = BorderStroke(1.dp, GrayBorder)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Nenhuma pesagem cadastrada ainda.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = GraySecondary
                                        )
                                    }
                                }
                            }
                        } else {
                            items(weights.sortedByDescending { it.timestamp }) { w ->
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
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(SoftGreenContainer, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.MonitorWeight, null, tint = SoftGreen, modifier = Modifier.size(20.dp))
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = String.format("%.1f kg", w.weightKg),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BordeauxDark
                                                )
                                                Text(
                                                    text = "${w.dateString} ${if (w.notes.isNotBlank()) "• ${w.notes}" else ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = GraySecondary
                                                )
                                            }
                                        }
                                        IconButton(onClick = { deleteWeightTarget = w }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = GraySecondary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    // Vaccines & Prevention Tab with full history, countdown and filters
                    VaccinationHistoryContent(
                        vaccines = vaccines,
                        onAddVaccineClick = onAddVaccineClick,
                        onEditVaccineClick = { vac ->
                            if (onEditVaccine != null) {
                                onEditVaccine(vac)
                            } else {
                                onAddVaccineClick()
                            }
                        },
                        onDeleteVaccineClick = { vac -> deleteVaccineTarget = vac }
                    )
                }
            }
            }
        }
    }

    // Confirmation dialogs
    deleteAppointmentTarget?.let { appt ->
        ConfirmDeleteDialog(
            title = "Excluir Consulta",
            message = "Deseja excluir a consulta de ${appt.dateString} (${appt.reason})?" +
                attachmentsDeleteNote(attachmentActions?.countFor(AttachmentOwner.APPOINTMENT, appt.id) ?: 0),
            onConfirm = { onDeleteAppointment(appt.id) },
            onDismiss = { deleteAppointmentTarget = null }
        )
    }

    deleteDocumentTarget?.let { doc ->
        ConfirmDeleteDialog(
            title = "Excluir Documento",
            message = "Deseja remover o registro de '${doc.name}'? Conforme boas práticas médicas, documentos antigos devem ser preservados a menos que cadastrados com erro." +
                attachmentsDeleteNote(attachmentActions?.countFor(AttachmentOwner.EXAM_DOCUMENT, doc.id) ?: 0),
            onConfirm = { onDeleteDocument(doc.id) },
            onDismiss = { deleteDocumentTarget = null }
        )
    }

    deleteWeightTarget?.let { w ->
        ConfirmDeleteDialog(
            title = "Excluir Pesagem",
            message = "Deseja excluir o registro de peso de ${w.weightKg} kg de ${w.dateString}?",
            onConfirm = { onDeleteWeight(w.id) },
            onDismiss = { deleteWeightTarget = null }
        )
    }

    deleteVaccineTarget?.let { vac ->
        ConfirmDeleteDialog(
            title = "Excluir Vacina/Prevenção",
            message = "Deseja excluir o registro de '${vac.name}' de ${vac.dateAdministered}?",
            onConfirm = { onDeleteVaccine(vac.id) },
            onDismiss = { deleteVaccineTarget = null }
        )
    }
}

private fun attachmentsDeleteNote(count: Int): String =
    if (count > 0) " Os $count anexo(s) deste registro também serão apagados deste aparelho." else ""

@Composable
private fun AppointmentCard(
    appointment: VeterinaryAppointment,
    onDelete: () -> Unit,
    attachmentActions: AttachmentActions? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(BordeauxSoftContainer, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Event, null, tint = BordeauxPrimary)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = appointment.reason,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                        Text(
                            text = "${appointment.dateString} às ${appointment.timeString}",
                            style = MaterialTheme.typography.labelSmall,
                            color = GraySecondary
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = GraySecondary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (appointment.vetName.isNotBlank() || appointment.clinicName.isNotBlank()) {
                Text(
                    text = "Veterinário: ${appointment.vetName.ifBlank { "Não informado" }} • ${appointment.clinicName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary
                )
            }

            if (appointment.weightKg != null || appointment.temperatureC != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    appointment.weightKg?.let {
                        Text(text = "Peso: $it kg", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = BordeauxPrimary)
                    }
                    appointment.temperatureC?.let {
                        Text(text = "Temp: $it °C", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DiscreetGold)
                    }
                }
            }

            if (appointment.symptomsReported.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sintomas relatados: ${appointment.symptomsReported}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary
                )
            }

            if (appointment.conductRecommended.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Conduta: ${appointment.conductRecommended}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BordeauxDark,
                    fontWeight = FontWeight.Medium
                )
            }

            if (appointment.medicationsPrescribed.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Prescrições: ${appointment.medicationsPrescribed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BordeauxLight
                )
            }

            if (appointment.nextAppointmentDate.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DiscreetGoldContainer
                ) {
                    Text(
                        text = "Próximo Retorno: ${appointment.nextAppointmentDate}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF5A4408),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (attachmentActions != null) {
                Spacer(modifier = Modifier.height(6.dp))
                SavedAttachmentsSection(
                    ownerType = AttachmentOwner.APPOINTMENT,
                    ownerId = appointment.id,
                    actions = attachmentActions
                )
            }
        }
    }
}

@Composable
private fun DocumentCard(
    document: ExamDocument,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    attachmentActions: AttachmentActions? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .testTag("document_card_${document.id}"),
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(BordeauxSoftContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, null, tint = BordeauxPrimary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = document.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = BordeauxDark
                    )
                    Text(
                        text = document.dateString,
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary
                    )
                }
                Text(
                    text = "Categoria: ${document.category} • Tipo: ${document.fileType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BordeauxLight
                )
                if (document.clinicName.isNotBlank() || document.vetName.isNotBlank()) {
                    Text(
                        text = "${document.clinicName} ${if (document.vetName.isNotBlank()) "• Dr(a). ${document.vetName}" else ""}".trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary
                    )
                }
                if (document.notes.isNotBlank()) {
                    Text(
                        text = document.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = GraySecondary
                    )
                }
                if (!document.fileUriOrPath.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Arquivo anexado vinculado",
                        style = MaterialTheme.typography.labelSmall,
                        color = BordeauxPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (attachmentActions != null) {
                    SavedAttachmentsSection(
                        ownerType = AttachmentOwner.EXAM_DOCUMENT,
                        ownerId = document.id,
                        actions = attachmentActions
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_document_button_${document.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Editar Exame",
                        tint = BordeauxPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_document_button_${document.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Excluir",
                        tint = GraySecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}



@Composable
private fun EmptyHealthHubSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = BordeauxLight, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = BordeauxDark)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = GraySecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
        ) {
            Text(buttonText, color = PureWhite)
        }
    }
}
