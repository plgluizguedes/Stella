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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.data.model.Medication
import com.example.data.model.MedicationAdministration
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.components.RecordAdministrationDialog
import com.example.ui.components.SuspendMedicationDialog
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

@Composable
fun MedicationsScreen(
    medications: List<Medication>,
    administrations: List<MedicationAdministration>,
    onAddMedicationClick: () -> Unit,
    onRecordAdministrationClick: (Medication) -> Unit,
    onSuspendMedication: (Medication, String) -> Unit,
    onReactivateMedication: (Medication) -> Unit,
    onDeleteMedication: (Long) -> Unit,
    onDeleteAdministration: (Long) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    val coroutineScope = rememberCoroutineScope()
    var suspendTarget by remember { mutableStateOf<Medication?>(null) }
    var deleteTarget by remember { mutableStateOf<Medication?>(null) }
    var deleteAdminTarget by remember { mutableStateOf<MedicationAdministration?>(null) }
    var adminTarget by remember { mutableStateOf<Medication?>(null) }

    val activeMeds = remember(medications) {
        medications.filter { it.status == "ATIVO" }
    }
    val endedMeds = remember(medications) {
        medications.filter { it.status == "ENCERRADO" }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddMedicationClick,
                containerColor = BordeauxPrimary,
                contentColor = PureWhite,
                modifier = Modifier.testTag("fab_add_medication")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Novo Medicamento")
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
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Medicamentos & Vitaminas",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = BordeauxDark
                    )
                    Button(
                        onClick = onAddMedicationClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cadastrar", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Acompanhamento posológico, horários e histórico de administrações",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary
                )
            }

            // Tabs: Ativos, Encerrados, Histórico de Doses
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = PureWhite,
                contentColor = BordeauxPrimary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        color = BordeauxPrimary
                    )
                }
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    text = {
                        Text(
                            "Em Uso (${activeMeds.size})",
                            fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (pagerState.currentPage == 0) BordeauxPrimary else GraySecondary
                        )
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    text = {
                        Text(
                            "Encerrados (${endedMeds.size})",
                            fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (pagerState.currentPage == 1) BordeauxPrimary else GraySecondary
                        )
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                    text = {
                        Text(
                            "Histórico Doses (${administrations.size})",
                            fontWeight = if (pagerState.currentPage == 2) FontWeight.Bold else FontWeight.Normal,
                            color = if (pagerState.currentPage == 2) BordeauxPrimary else GraySecondary
                        )
                    }
                )
            }

            // Tab Content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                0 -> {
                    // Active list
                    if (activeMeds.isEmpty()) {
                        EmptyMedicationView(
                            message = "Nenhum medicamento ou suplemento ativo no momento.",
                            onAdd = onAddMedicationClick
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Split vitamins and regular medications visually as requested
                            val regularMeds = activeMeds.filter { !it.isVitaminOrSupplement }
                            val vitamins = activeMeds.filter { it.isVitaminOrSupplement }

                            if (regularMeds.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Medicamentos Prescritos",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = BordeauxDark
                                    )
                                }
                                items(regularMeds) { med ->
                                    ActiveMedicationCard(
                                        medication = med,
                                        onRecordAdministration = { adminTarget = med },
                                        onSuspend = { suspendTarget = med },
                                        onDelete = { deleteTarget = med }
                                    )
                                }
                            }

                            if (vitamins.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Vitaminas & Suplementos",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SoftGreen
                                    )
                                }
                                items(vitamins) { vit ->
                                    ActiveMedicationCard(
                                        medication = vit,
                                        onRecordAdministration = { adminTarget = vit },
                                        onSuspend = { suspendTarget = vit },
                                        onDelete = { deleteTarget = vit }
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Ended list
                    if (endedMeds.isEmpty()) {
                        EmptyMedicationView(
                            message = "Nenhum medicamento no histórico de encerrados.",
                            onAdd = onAddMedicationClick
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(endedMeds) { med ->
                                EndedMedicationCard(
                                    medication = med,
                                    onReactivate = { onReactivateMedication(med) },
                                    onDelete = { deleteTarget = med }
                                )
                            }
                        }
                    }
                }
                2 -> {
                    // Administrations log
                    if (administrations.isEmpty()) {
                        EmptyMedicationView(
                            message = "Nenhum registro de administração efetuado ainda.",
                            onAdd = onAddMedicationClick
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(administrations) { admin ->
                                AdministrationLogCard(
                                    administration = admin,
                                    onDelete = { deleteAdminTarget = admin }
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    // Dialogs
    suspendTarget?.let { med ->
        SuspendMedicationDialog(
            medication = med,
            onDismiss = { suspendTarget = null },
            onConfirm = { reason ->
                onSuspendMedication(med, reason)
                suspendTarget = null
            }
        )
    }

    deleteTarget?.let { med ->
        ConfirmDeleteDialog(
            title = "Excluir Medicamento",
            message = "Deseja excluir permanentemente o cadastro de '${med.name}'? Para manter o histórico clínico, prefira a opção 'Encerrar'.",
            onConfirm = { onDeleteMedication(med.id) },
            onDismiss = { deleteTarget = null }
        )
    }

    deleteAdminTarget?.let { admin ->
        ConfirmDeleteDialog(
            title = "Excluir Registro de Dose",
            message = "Deseja excluir o registro da dose de '${admin.medicationName}' em ${admin.dateString} às ${admin.timeString}?",
            onConfirm = { onDeleteAdministration(admin.id) },
            onDismiss = { deleteAdminTarget = null }
        )
    }

    adminTarget?.let { med ->
        RecordAdministrationDialog(
            medication = med,
            onDismiss = { adminTarget = null },
            onConfirm = { date, time, dosage, regBy, notes ->
                onRecordAdministrationClick(med)
                adminTarget = null
            }
        )
    }
}

@Composable
private fun ActiveMedicationCard(
    medication: Medication,
    onRecordAdministration: () -> Unit,
    onSuspend: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("medication_card_${medication.id}"),
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
                            .size(42.dp)
                            .background(
                                if (medication.isVitaminOrSupplement) SoftGreenContainer else BordeauxSoftContainer,
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (medication.isVitaminOrSupplement) Icons.Default.Spa else Icons.Default.Medication,
                            contentDescription = null,
                            tint = if (medication.isVitaminOrSupplement) SoftGreen else BordeauxPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = medication.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                        if (medication.activeIngredient.isNotBlank()) {
                            Text(
                                text = "Princípio: ${medication.activeIngredient}",
                                style = MaterialTheme.typography.labelSmall,
                                color = GraySecondary
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SoftGreen.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "EM USO",
                        style = MaterialTheme.typography.labelSmall,
                        color = SoftGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Details grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MedDetailChip(label = "Dose", value = medication.dosage)
                MedDetailChip(label = "Frequência", value = medication.frequency)
                MedDetailChip(label = "Horários", value = medication.scheduledTimes.ifBlank { "Livre" })
            }

            if (medication.reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Motivo: ${medication.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary
                )
            }

            if (medication.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Obs: ${medication.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BordeauxLight
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = GrayBorder.copy(alpha = 0.5f))

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onSuspend,
                    colors = ButtonDefaults.textButtonColors(contentColor = GraySecondary)
                ) {
                    Icon(Icons.Default.PauseCircle, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Encerrar Uso", fontSize = 12.sp)
                }

                Button(
                    onClick = onRecordAdministration,
                    colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                    modifier = Modifier.testTag("record_admin_btn_${medication.id}")
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = PureWhite)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Registrar Dose", color = PureWhite, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun EndedMedicationCard(
    medication: Medication,
    onReactivate: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = medication.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GraySecondary
                    )
                    Text(
                        text = "Início: ${medication.startDate} • Encerrado em: ${medication.suspensionDate.ifBlank { "Histórico" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = GrayBorder
                ) {
                    Text(
                        text = "ENCERRADO",
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (medication.suspensionReason.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Motivo do encerramento: ${medication.suspensionReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BordeauxDark
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onReactivate,
                    colors = ButtonDefaults.textButtonColors(contentColor = BordeauxPrimary)
                ) {
                    Icon(Icons.Default.PlayCircle, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reativar Tratamento", fontSize = 12.sp)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = GraySecondary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun AdministrationLogCard(
    administration: MedicationAdministration,
    onDelete: () -> Unit
) {
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(SoftGreenContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, null, tint = SoftGreen, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = administration.medicationName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = BordeauxDark
                    )
                    Text(
                        text = "${administration.dateString} às ${administration.timeString}",
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary
                    )
                }
                Text(
                    text = "Dose: ${administration.dosage} • Registrado por: ${administration.registeredBy}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary
                )
                if (administration.notes.isNotBlank()) {
                    Text(
                        text = "Obs: ${administration.notes}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BordeauxLight
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = GraySecondary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MedDetailChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = CreamOffWhite,
        border = BorderStroke(1.dp, GrayBorder.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = GraySecondary)
            Text(text = value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = BordeauxDark)
        }
    }
}

@Composable
private fun EmptyMedicationView(message: String, onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Medication,
            contentDescription = null,
            tint = BordeauxLight,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = GraySecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
        ) {
            Text("Cadastrar Item", color = PureWhite)
        }
    }
}
