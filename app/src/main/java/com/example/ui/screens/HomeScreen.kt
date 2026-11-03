package com.example.ui.screens

import com.example.ui.components.profilePhotoModel
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.example.ui.screens.calculateVaccineDueStatus
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    profile: StellaProfile,
    glucoseRecords: List<GlucoseRecord>,
    medications: List<Medication>,
    appointments: List<VeterinaryAppointment>,
    documents: List<ExamDocument>,
    feedingRecords: List<FeedingRecord>,
    weightRecords: List<WeightRecord>,
    vaccines: List<VaccinePreventionRecord> = emptyList(),
    reminders: List<Reminder>,
    onQuickAddGlucose: () -> Unit,
    onQuickAddMedication: () -> Unit,
    onQuickAddVaccine: () -> Unit = {},
    onQuickAddAppointment: () -> Unit,
    onQuickAddExam: () -> Unit,
    onQuickAddWeight: () -> Unit,
    onQuickAddReminder: () -> Unit,
    onRecordAdministration: (Medication) -> Unit,
    onToggleReminder: (Reminder) -> Unit,
    onNavigateToSection: (String) -> Unit
) {
    val activeMedications = remember(medications) {
        medications.filter { it.status == "ATIVO" }
    }
    val latestGlucose = remember(glucoseRecords) {
        glucoseRecords.maxByOrNull { it.dateTimeMillis }
    }
    val latestWeight = remember(weightRecords, profile.currentWeight) {
        weightRecords.maxByOrNull { it.timestamp }?.weightKg ?: profile.currentWeight
    }
    val nextAppointment = remember(appointments) {
        appointments.firstOrNull { it.nextAppointmentDate.isNotBlank() }
    }
    val latestDocument = remember(documents) {
        documents.maxByOrNull { it.createdAt }
    }
    val currentFeeding = remember(feedingRecords) {
        feedingRecords.firstOrNull { it.isCurrent }
    }

    val calculatedAge = remember(profile.birthDate) {
        calculateAgeString(profile.birthDate)
    }

    val overdueVaccines = remember(vaccines) {
        vaccines.count { calculateVaccineDueStatus(it.nextDueDate).isOverdue }
    }
    val dueSoonVaccines = remember(vaccines) {
        vaccines.count { calculateVaccineDueStatus(it.nextDueDate).isDueSoon }
    }
    val nextVaccine = remember(vaccines) {
        vaccines
            .filter { calculateVaccineDueStatus(it.nextDueDate).daysRemaining?.let { days -> days >= 0 } == true }
            .minByOrNull { calculateVaccineDueStatus(it.nextDueDate).daysRemaining ?: Long.MAX_VALUE }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_screen_lazy_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Stella Hero Card ---
        item {
            StellaHeroCard(
                profile = profile,
                calculatedAge = calculatedAge,
                currentWeight = latestWeight,
                onProfileClick = { onNavigateToSection("Perfil") }
            )
        }

        // --- 2. Quick Action Buttons ---
        item {
            Text(
                text = "Ações Rápidas",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BordeauxDark
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    QuickActionButton(
                        icon = Icons.Default.Speed,
                        label = "+ Glicemia",
                        onClick = onQuickAddGlucose,
                        modifier = Modifier.width(110.dp),
                        testTag = "quick_add_glucose"
                    )
                }
                item {
                    QuickActionButton(
                        icon = Icons.Default.Medication,
                        label = "+ Remédio",
                        onClick = onQuickAddMedication,
                        modifier = Modifier.width(110.dp),
                        testTag = "quick_add_medication"
                    )
                }
                item {
                    QuickActionButton(
                        icon = Icons.Default.Shield,
                        label = "+ Vacina",
                        onClick = onQuickAddVaccine,
                        modifier = Modifier.width(110.dp),
                        testTag = "quick_add_vaccine"
                    )
                }
                item {
                    QuickActionButton(
                        icon = Icons.Default.Event,
                        label = "+ Consulta",
                        onClick = onQuickAddAppointment,
                        modifier = Modifier.width(110.dp),
                        testTag = "quick_add_appointment"
                    )
                }
                item {
                    QuickActionButton(
                        icon = Icons.Default.Description,
                        label = "+ Exame",
                        onClick = onQuickAddExam,
                        modifier = Modifier.width(110.dp),
                        testTag = "quick_add_exam"
                    )
                }
                item {
                    QuickActionButton(
                        icon = Icons.Default.MonitorWeight,
                        label = "+ Peso",
                        onClick = onQuickAddWeight,
                        modifier = Modifier.width(110.dp),
                        testTag = "quick_add_weight"
                    )
                }
            }
        }

        // --- 3. Overview Cards (Glicemia, Peso, Medicamentos, Próxima Consulta, Último Exame) ---
        item {
            Text(
                text = "Quadro Geral de Saúde",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BordeauxDark
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Glucose Card
                    SummaryCard(
                        title = "Glicemia",
                        value = if (latestGlucose != null) "${latestGlucose.value.roundToInt()} ${latestGlucose.unit}" else "Sem registros",
                        subtitle = if (latestGlucose != null) "${latestGlucose.dateString} ${latestGlucose.timeString}\n(${latestGlucose.mealRelation})" else "Toque para registrar",
                        icon = Icons.Default.Speed,
                        highlightColor = BordeauxPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToSection("Glicemia") }
                    )
                    // Weight Card
                    SummaryCard(
                        title = "Peso Atual",
                        value = String.format("%.1f kg", latestWeight),
                        subtitle = "Meta saudável\nAferição recente",
                        icon = Icons.Default.MonitorWeight,
                        highlightColor = SoftGreen,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToSection("Saúde") }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Active Meds
                    SummaryCard(
                        title = "Medicamentos",
                        value = "${activeMedications.size} ativo(s)",
                        subtitle = if (activeMedications.isNotEmpty()) "${activeMedications.first().name}..." else "Nenhum remédio ativo",
                        icon = Icons.Default.Medication,
                        highlightColor = BordeauxLight,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToSection("Medicamentos") }
                    )
                    // Next appointment
                    SummaryCard(
                        title = "Próxima Consulta",
                        value = nextAppointment?.nextAppointmentDate?.ifBlank { "A agendar" } ?: "A agendar",
                        subtitle = if (nextAppointment != null && nextAppointment.clinicName.isNotBlank()) {
                            nextAppointment.clinicName
                        } else {
                            profile.clinicName.ifBlank { "Sem clínica cadastrada" }
                        },
                        icon = Icons.Default.Event,
                        highlightColor = DiscreetGold,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToSection("Saúde") }
                    )
                }

                // Latest Exam
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSection("Saúde") },
                    shape = RoundedCornerShape(14.dp),
                    color = PureWhite,
                    border = BorderStroke(1.dp, GrayBorder),
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(BordeauxSoftContainer, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Description, null, tint = BordeauxPrimary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Último Exame Cadastrado",
                                style = MaterialTheme.typography.labelMedium,
                                color = GraySecondary
                            )
                            Text(
                                text = latestDocument?.name ?: "Nenhum exame cadastrado no prontuário",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = BordeauxDark
                            )
                            if (latestDocument != null) {
                                Text(
                                    text = "${latestDocument.category} • ${latestDocument.dateString}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GraySecondary
                                )
                            }
                        }
                    }
                }

                // Vaccines & Immunization Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSection("Vacinas") }
                        .testTag("home_vaccines_card"),
                    shape = RoundedCornerShape(14.dp),
                    color = PureWhite,
                    border = BorderStroke(
                        1.dp,
                        if (overdueVaccines > 0) SoftRed.copy(alpha = 0.5f) else GrayBorder
                    ),
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (overdueVaccines > 0) SoftRedContainer else SoftGreenContainer,
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = if (overdueVaccines > 0) SoftRed else SoftGreen
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Histórico de Vacinas",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = GraySecondary
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (overdueVaccines > 0) SoftRedContainer else if (dueSoonVaccines > 0) DiscreetGoldContainer else SoftGreenContainer
                                ) {
                                    Text(
                                        text = when {
                                            overdueVaccines > 0 -> "$overdueVaccines atrasada(s)"
                                            dueSoonVaccines > 0 -> "$dueSoonVaccines a vencer"
                                            vaccines.isNotEmpty() -> "Em dia"
                                            else -> "Sem vacinas"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (overdueVaccines > 0) SoftRed else if (dueSoonVaccines > 0) DiscreetGold else SoftGreen,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (vaccines.isNotEmpty()) {
                                    if (nextVaccine != null) {
                                        "Próximo reforço: ${nextVaccine.name} (${nextVaccine.nextDueDate})"
                                    } else {
                                        "${vaccines.size} vacina(s) registradas"
                                    }
                                } else {
                                    "Nenhuma vacina cadastrada no prontuário"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = BordeauxDark
                            )
                            Text(
                                text = "Toque para ver histórico completo de doses e reforços",
                                style = MaterialTheme.typography.bodySmall,
                                color = GraySecondary
                            )
                        }
                    }
                }
            }
        }

        // --- 4. "Resumo de Hoje" ---
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("today_summary_card"),
                shape = RoundedCornerShape(16.dp),
                color = PureWhite,
                border = BorderStroke(1.dp, GrayBorder),
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(BordeauxPrimary, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Resumo de Hoje",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = BordeauxDark
                            )
                        }
                        TextButton(
                            onClick = onQuickAddReminder,
                            colors = ButtonDefaults.textButtonColors(contentColor = BordeauxPrimary)
                        ) {
                            Text("+ Lembrete", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Diet row
                    if (currentFeeding != null) {
                        TodayRoutineItem(
                            icon = Icons.Default.Restaurant,
                            title = "Alimentação: ${currentFeeding.brand} ${currentFeeding.foodName}",
                            details = "${currentFeeding.dailyAmount}/dia • ${currentFeeding.mealsPerDay}x refeições (${currentFeeding.scheduleTimes})",
                            status = "Rotina ativa",
                            statusColor = SoftGreen
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = GrayBorder.copy(alpha = 0.5f))
                    }

                    // Active Medications for today
                    if (activeMedications.isNotEmpty()) {
                        Text(
                            text = "Medicamentos & Suplementos do dia:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = BordeauxPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        activeMedications.forEach { med ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${med.name} (${med.dosage})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = BordeauxDark
                                    )
                                    Text(
                                        text = "Horários: ${med.scheduledTimes} • ${med.frequency}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GraySecondary
                                    )
                                }
                                OutlinedButton(
                                    onClick = { onRecordAdministration(med) },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    border = BorderStroke(1.dp, BordeauxPrimary)
                                ) {
                                    Text("Administrar", fontSize = 11.sp, color = BordeauxPrimary)
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = GrayBorder.copy(alpha = 0.5f))
                    }

                    // Reminders
                    if (reminders.isNotEmpty()) {
                        Text(
                            text = "Tarefas e Lembretes:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = BordeauxPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        reminders.take(4).forEach { rem ->
                            val isDone = rem.status == "REALIZADO"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleReminder(rem) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isDone) SoftGreen else GraySecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rem.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isDone) GraySecondary else BordeauxDark,
                                        textDecoration = if (isDone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                    )
                                    Text(
                                        text = "${rem.category} • ${rem.dateString} ${rem.timeString}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GraySecondary
                                    )
                                }
                            }
                        }
                    } else if (activeMedications.isEmpty() && currentFeeding == null) {
                        Text(
                            text = "Nenhum compromisso ou medicamento pendente para hoje.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraySecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StellaHeroCard(
    profile: StellaProfile,
    calculatedAge: String,
    currentWeight: Double,
    onProfileClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onProfileClick() }
            .testTag("stella_hero_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BordeauxPrimary),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Large circular photo of Stella
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .border(3.dp, PureWhite, CircleShape)
                        .background(CreamOffWhite),
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
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BordeauxDark.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = profile.gender,
                                style = MaterialTheme.typography.labelSmall,
                                color = PureWhite,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "${profile.species} • ${profile.breed}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CreamOffWhite.copy(alpha = 0.9f)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Idade: $calculatedAge",
                        style = MaterialTheme.typography.bodySmall,
                        color = DiscreetGoldContainer
                    )

                    Text(
                        text = if (currentWeight > 0.0) "Peso atual: ${String.format(java.util.Locale.getDefault(), "%.1f", currentWeight)} kg" else "Peso: Não registrado",
                        style = MaterialTheme.typography.bodySmall,
                        color = PureWhite,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = BordeauxDark.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (profile.birthDate.isNotBlank()) "Nascimento: ${profile.birthDate}" else "Nascimento: Não informado",
                        style = MaterialTheme.typography.labelSmall,
                        color = CreamOffWhite
                    )
                    Text(
                        text = "Ver Perfil Completo ›",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String
) {
    Surface(
        modifier = modifier
            .testTag(testTag)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(BordeauxSoftContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = BordeauxPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = BordeauxDark,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    highlightColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = GraySecondary
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(highlightColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = highlightColor, modifier = Modifier.size(14.dp))
                }
            }

            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BordeauxDark
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = GraySecondary,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun TodayRoutineItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    details: String,
    status: String,
    statusColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(BordeauxSoftContainer, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = BordeauxPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = BordeauxDark
            )
            Text(
                text = details,
                style = MaterialTheme.typography.labelSmall,
                color = GraySecondary
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = statusColor.copy(alpha = 0.15f)
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

fun calculateAgeString(birthDateStr: String): String {
    if (birthDateStr.isBlank()) return "Não informada"
    return try {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val birth = sdf.parse(birthDateStr) ?: return "Não calculada"
        val birthCal = Calendar.getInstance().apply { time = birth }
        val nowCal = Calendar.getInstance()

        var years = nowCal.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
        var months = nowCal.get(Calendar.MONTH) - birthCal.get(Calendar.MONTH)

        if (months < 0) {
            years--
            months += 12
        }

        when {
            years > 1 && months > 0 -> "$years anos e $months meses"
            years == 1 && months > 0 -> "1 ano e $months meses"
            years > 1 -> "$years anos"
            years == 1 -> "1 ano"
            months > 0 -> "$months meses"
            else -> "Menos de 1 mês"
        }
    } catch (_: Exception) {
        "Não calculada"
    }
}
