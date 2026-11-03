package com.example.ui.screens

import com.example.util.DateInput
import com.example.ui.components.DateTextField
import com.example.ui.components.TimeTextField
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VaccinePreventionRecord
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxLight
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.BordeauxSoftContainer
import com.example.ui.theme.BordeauxVerySoft
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.DiscreetGold
import com.example.ui.theme.DiscreetGoldContainer
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GrayLight
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftGreen
import com.example.ui.theme.SoftGreenContainer
import com.example.ui.theme.SoftRed
import com.example.ui.theme.SoftRedContainer
import com.example.util.DateTimeUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Status calculation for a vaccine's next due date.
 */
data class VaccineDueStatus(
    val badgeLabel: String,
    val detailText: String,
    val badgeTextColor: Color,
    val badgeBgColor: Color,
    val iconColor: Color,
    val isOverdue: Boolean,
    val isDueSoon: Boolean,
    val isUpToDate: Boolean,
    val daysRemaining: Long?
)

fun calculateVaccineDueStatus(nextDueDateStr: String?): VaccineDueStatus {
    if (nextDueDateStr.isNullOrBlank()) {
        return VaccineDueStatus(
            badgeLabel = "Dose Única",
            detailText = "Sem reforço programado",
            badgeTextColor = GraySecondary,
            badgeBgColor = GrayLight,
            iconColor = GraySecondary,
            isOverdue = false,
            isDueSoon = false,
            isUpToDate = false,
            daysRemaining = null
        )
    }

    return try {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply { isLenient = false }
        val dueDate = sdf.parse(nextDueDateStr.trim()) ?: return VaccineDueStatus(
            badgeLabel = "Data inválida",
            detailText = nextDueDateStr,
            badgeTextColor = SoftRed,
            badgeBgColor = SoftRedContainer,
            iconColor = SoftRed,
            isOverdue = false,
            isDueSoon = false,
            isUpToDate = false,
            daysRemaining = null
        )

        // Compare using midnight calendar boundaries
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dueCal = Calendar.getInstance().apply {
            time = dueDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val diffMillis = dueCal.timeInMillis - todayCal.timeInMillis
        val diffDays = diffMillis / (24 * 60 * 60 * 1000)

        when {
            diffDays < 0 -> {
                val pastDays = abs(diffDays)
                VaccineDueStatus(
                    badgeLabel = "Atrasada",
                    detailText = if (pastDays == 1L) "Atrasada há 1 dia" else "Atrasada há $pastDays dias",
                    badgeTextColor = SoftRed,
                    badgeBgColor = SoftRedContainer,
                    iconColor = SoftRed,
                    isOverdue = true,
                    isDueSoon = false,
                    isUpToDate = false,
                    daysRemaining = diffDays
                )
            }
            diffDays == 0L -> {
                VaccineDueStatus(
                    badgeLabel = "Vence Hoje",
                    detailText = "Reforço previsto para hoje!",
                    badgeTextColor = DiscreetGold,
                    badgeBgColor = DiscreetGoldContainer,
                    iconColor = DiscreetGold,
                    isOverdue = false,
                    isDueSoon = true,
                    isUpToDate = false,
                    daysRemaining = 0L
                )
            }
            diffDays in 1..30 -> {
                VaccineDueStatus(
                    badgeLabel = "Vence em breve",
                    detailText = if (diffDays == 1L) "Vence amanhã" else "Vence em $diffDays dias",
                    badgeTextColor = DiscreetGold,
                    badgeBgColor = DiscreetGoldContainer,
                    iconColor = DiscreetGold,
                    isOverdue = false,
                    isDueSoon = true,
                    isUpToDate = false,
                    daysRemaining = diffDays
                )
            }
            else -> {
                VaccineDueStatus(
                    badgeLabel = "Em dia",
                    detailText = "Vence em $diffDays dias ($nextDueDateStr)",
                    badgeTextColor = SoftGreen,
                    badgeBgColor = SoftGreenContainer,
                    iconColor = SoftGreen,
                    isOverdue = false,
                    isDueSoon = false,
                    isUpToDate = true,
                    daysRemaining = diffDays
                )
            }
        }
    } catch (_: Exception) {
        VaccineDueStatus(
            badgeLabel = "Data a verificar",
            detailText = nextDueDateStr,
            badgeTextColor = GraySecondary,
            badgeBgColor = GrayLight,
            iconColor = GraySecondary,
            isOverdue = false,
            isDueSoon = false,
            isUpToDate = false,
            daysRemaining = null
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaccinationHistoryScreen(
    vaccines: List<VaccinePreventionRecord>,
    onBackClick: (() -> Unit)? = null,
    onAddVaccine: (name: String, type: String, date: String, dose: String, next: String, vet: String, clinic: String, notes: String, scheduleReminder: Boolean) -> Boolean,
    onUpdateVaccine: (record: VaccinePreventionRecord, scheduleReminder: Boolean) -> Boolean,
    onDeleteVaccine: (Long) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRecord by remember { mutableStateOf<VaccinePreventionRecord?>(null) }
    var deletingRecord by remember { mutableStateOf<VaccinePreventionRecord?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Histórico de Vacinação",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                        Text(
                            text = "Stella • Doses aplicadas e próximos reforços",
                            style = MaterialTheme.typography.labelSmall,
                            color = CreamOffWhite.copy(alpha = 0.85f)
                        )
                    }
                },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Voltar",
                                tint = PureWhite
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BordeauxPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = BordeauxPrimary,
                contentColor = PureWhite,
                modifier = Modifier.testTag("fab_add_vaccine")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Vacina")
            }
        },
        containerColor = CreamOffWhite
    ) { padding ->
        VaccinationHistoryContent(
            vaccines = vaccines,
            onAddVaccineClick = { showAddDialog = true },
            onEditVaccineClick = { editingRecord = it },
            onDeleteVaccineClick = { deletingRecord = it },
            modifier = Modifier.padding(padding)
        )
    }

    // Add Vaccine Dialog
    if (showAddDialog) {
        AddOrEditVaccineDialog(
            initialRecord = null,
            onDismiss = { showAddDialog = false },
            onSave = { record, scheduleReminder ->
                val success = onAddVaccine(
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
                    showAddDialog = false
                }
            }
        )
    }

    // Edit Vaccine Dialog
    editingRecord?.let { recordToEdit ->
        AddOrEditVaccineDialog(
            initialRecord = recordToEdit,
            onDismiss = { editingRecord = null },
            onSave = { updated, scheduleReminder ->
                val success = onUpdateVaccine(updated, scheduleReminder)
                if (success) {
                    editingRecord = null
                }
            }
        )
    }

    // Delete Confirmation Dialog
    deletingRecord?.let { recordToDelete ->
        ConfirmDeleteDialog(
            title = "Excluir Registro de Vacina",
            message = "Deseja excluir o registro da vacina '${recordToDelete.name}' aplicada em ${recordToDelete.dateAdministered}?",
            onConfirm = {
                onDeleteVaccine(recordToDelete.id)
                deletingRecord = null
            },
            onDismiss = { deletingRecord = null }
        )
    }
}

/**
 * Reusable Vaccination History component suitable for embedding in tabs or full screens.
 */
@Composable
fun VaccinationHistoryContent(
    vaccines: List<VaccinePreventionRecord>,
    onAddVaccineClick: () -> Unit,
    onEditVaccineClick: (VaccinePreventionRecord) -> Unit,
    onDeleteVaccineClick: (VaccinePreventionRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    var showGuide by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("Todas") }
    var searchQuery by remember { mutableStateOf("") }

    val filterOptions = listOf("Todas", "Vacinas", "Vermífugos", "Antiparasitários", "Atrasadas / Próximas")

    // Filter & search logic
    val filteredVaccines = remember(vaccines, selectedFilter, searchQuery) {
        vaccines.filter { record ->
            val matchesFilter = when (selectedFilter) {
                "Todas" -> true
                "Vacinas" -> record.type.contains("Vacina", ignoreCase = true)
                "Vermífugos" -> record.type.contains("Verm", ignoreCase = true)
                "Antiparasitários" -> record.type.contains("Antiparasit", ignoreCase = true) || record.type.contains("Pulga", ignoreCase = true)
                "Atrasadas / Próximas" -> {
                    val status = calculateVaccineDueStatus(record.nextDueDate)
                    status.isOverdue || status.isDueSoon
                }
                else -> true
            }

            val matchesSearch = if (searchQuery.isBlank()) true else {
                val q = searchQuery.trim().lowercase(Locale.ROOT)
                record.name.lowercase(Locale.ROOT).contains(q) ||
                    record.type.lowercase(Locale.ROOT).contains(q) ||
                    record.vetName.lowercase(Locale.ROOT).contains(q) ||
                    record.clinicName.lowercase(Locale.ROOT).contains(q) ||
                    record.notes.lowercase(Locale.ROOT).contains(q) ||
                    record.dateAdministered.contains(q) ||
                    record.nextDueDate.contains(q)
            }

            matchesFilter && matchesSearch
        }.sortedWith(
            compareBy<VaccinePreventionRecord> { record ->
                val status = calculateVaccineDueStatus(record.nextDueDate)
                when {
                    status.isOverdue -> 0
                    status.isDueSoon -> 1
                    status.isUpToDate -> 2
                    else -> 3
                }
            }.thenByDescending { it.createdAt }
        )
    }

    // Top metrics
    val overdueCount = remember(vaccines) {
        vaccines.count { calculateVaccineDueStatus(it.nextDueDate).isOverdue }
    }
    val dueSoonCount = remember(vaccines) {
        vaccines.count { calculateVaccineDueStatus(it.nextDueDate).isDueSoon }
    }
    val upToDateCount = remember(vaccines) {
        vaccines.count { calculateVaccineDueStatus(it.nextDueDate).isUpToDate }
    }
    val nearestUpcoming = remember(vaccines) {
        vaccines
            .filter { calculateVaccineDueStatus(it.nextDueDate).daysRemaining?.let { days -> days >= 0 } == true }
            .minByOrNull { calculateVaccineDueStatus(it.nextDueDate).daysRemaining ?: Long.MAX_VALUE }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("vaccination_history_lazy_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- 1. Summary Cards Banner ---
        item {
            VaccinationSummaryCards(
                totalCount = vaccines.size,
                overdueCount = overdueCount,
                dueSoonCount = dueSoonCount,
                upToDateCount = upToDateCount,
                nearestUpcoming = nearestUpcoming
            )
        }

        // --- 2. Expandable Canine Vaccination Reference Protocol ---
        item {
            CanineVaccineProtocolCard(
                isExpanded = showGuide,
                onToggle = { showGuide = !showGuide }
            )
        }

        // --- 3. Search and Filters ---
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vaccine_search_input"),
                    placeholder = { Text("Buscar por vacina, vermífugo, veterinário...") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = GraySecondary)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar busca", tint = GraySecondary)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BordeauxPrimary,
                        unfocusedBorderColor = GrayBorder,
                        focusedContainerColor = PureWhite,
                        unfocusedContainerColor = PureWhite
                    )
                )

                // Filter Chips Row
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(filterOptions) { filter ->
                        val isSelected = selectedFilter == filter
                        val badgeCount = when (filter) {
                            "Todas" -> vaccines.size
                            "Vacinas" -> vaccines.count { it.type.contains("Vacina", ignoreCase = true) }
                            "Vermífugos" -> vaccines.count { it.type.contains("Verm", ignoreCase = true) }
                            "Antiparasitários" -> vaccines.count { it.type.contains("Antiparasit", ignoreCase = true) || it.type.contains("Pulga", ignoreCase = true) }
                            "Atrasadas / Próximas" -> overdueCount + dueSoonCount
                            else -> 0
                        }

                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFilter = filter },
                            label = {
                                Text(
                                    text = "$filter ($badgeCount)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BordeauxPrimary,
                                selectedLabelColor = PureWhite,
                                containerColor = PureWhite,
                                labelColor = BordeauxDark
                            ),
                            border = BorderStroke(1.dp, if (isSelected) BordeauxPrimary else GrayBorder),
                            modifier = Modifier.testTag("filter_chip_${filter.lowercase().replace(" ", "_")}")
                        )
                    }
                }
            }
        }

        // --- 4. Section Title & Add Action ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Registros (${filteredVaccines.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
                TextButton(
                    onClick = onAddVaccineClick,
                    modifier = Modifier.testTag("btn_quick_add_vaccine")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = BordeauxPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Nova Aplicação", color = BordeauxPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- 5. Vaccine List or Empty State ---
        if (filteredVaccines.isEmpty()) {
            item {
                EmptyVaccinationState(
                    isFiltered = vaccines.isNotEmpty(),
                    onAddClick = onAddVaccineClick,
                    onResetFilters = {
                        selectedFilter = "Todas"
                        searchQuery = ""
                    }
                )
            }
        } else {
            items(filteredVaccines, key = { it.id }) { item ->
                VaccineHistoryCard(
                    record = item,
                    onEdit = { onEditVaccineClick(item) },
                    onDelete = { onDeleteVaccineClick(item) }
                )
            }
        }
    }
}


/**
 * Top dashboard banner summarizing the vaccination status.
 */
@Composable
private fun VaccinationSummaryCards(
    totalCount: Int,
    overdueCount: Int,
    dueSoonCount: Int,
    upToDateCount: Int,
    nearestUpcoming: VaccinePreventionRecord?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // General health status pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                color = if (overdueCount > 0) SoftRedContainer else SoftGreenContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (overdueCount > 0) Icons.Default.Warning else Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (overdueCount > 0) SoftRed else SoftGreen,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Status de Imunização",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                        Text(
                            text = when {
                                overdueCount > 0 -> "$overdueCount dose(s) em atraso"
                                dueSoonCount > 0 -> "$dueSoonCount reforço(s) vencendo em breve"
                                totalCount > 0 -> "Todas as vacinas em dia"
                                else -> "Nenhuma vacina registrada"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdueCount > 0) SoftRed else if (dueSoonCount > 0) DiscreetGold else SoftGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BordeauxSoftContainer
                ) {
                    Text(
                        text = "$totalCount aplicadas",
                        style = MaterialTheme.typography.labelSmall,
                        color = BordeauxPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = GrayBorder.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(12.dp))

            // Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(
                    value = upToDateCount.toString(),
                    label = "Em dia",
                    valueColor = SoftGreen
                )
                MetricColumn(
                    value = dueSoonCount.toString(),
                    label = "A vencer (30d)",
                    valueColor = DiscreetGold
                )
                MetricColumn(
                    value = overdueCount.toString(),
                    label = "Atrasadas",
                    valueColor = SoftRed
                )
            }

            // Nearest upcoming reminder spotlight
            if (nearestUpcoming != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val dueStatus = calculateVaccineDueStatus(nearestUpcoming.nextDueDate)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BordeauxVerySoft,
                    border = BorderStroke(1.dp, BordeauxSoftContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = BordeauxPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Próximo reforço: ${nearestUpcoming.name}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = BordeauxDark
                            )
                            Text(
                                text = "${nearestUpcoming.nextDueDate} • ${dueStatus.detailText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = GraySecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(
    value: String,
    label: String,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = GraySecondary
        )
    }
}

/**
 * Individual vaccination record card with comprehensive detail.
 */
@Composable
fun VaccineHistoryCard(
    record: VaccinePreventionRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dueStatus = calculateVaccineDueStatus(record.nextDueDate)

    val typeIcon = when {
        record.type.contains("Verm", ignoreCase = true) -> Icons.Default.Medication
        record.type.contains("Antiparasit", ignoreCase = true) || record.type.contains("Pulga", ignoreCase = true) -> Icons.Default.BugReport
        else -> Icons.Default.Shield
    }

    val typeColor = when {
        record.type.contains("Verm", ignoreCase = true) -> DiscreetGold
        record.type.contains("Antiparasit", ignoreCase = true) -> BordeauxLight
        else -> SoftGreen
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("vaccine_card_${record.id}"),
        shape = RoundedCornerShape(16.dp),
        color = PureWhite,
        border = BorderStroke(
            1.dp,
            if (dueStatus.isOverdue) SoftRed.copy(alpha = 0.5f) else GrayBorder
        ),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Type icon + Vaccine Name + Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                color = dueStatus.badgeBgColor,
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = record.type,
                            tint = dueStatus.iconColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = record.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = typeColor.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = record.type,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = typeColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (record.dose.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = GrayLight
                                ) {
                                    Text(
                                        text = record.dose,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GraySecondary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Edit and Delete buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("edit_vaccine_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Editar",
                            tint = GraySecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("delete_vaccine_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Excluir",
                            tint = SoftRed.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Two-column details: Date Administered & Next Due Date
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CreamOffWhite, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Application Date
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Data da Aplicação",
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary
                    )
                    Text(
                        text = record.dateAdministered.ifBlank { "Não informada" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = BordeauxDark
                    )
                }

                // Divider line
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .width(1.dp)
                        .background(GrayBorder)
                )

                // Next Due Date
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = "Próximo Reforço",
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary
                    )
                    if (record.nextDueDate.isNotBlank()) {
                        Text(
                            text = record.nextDueDate,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (dueStatus.isOverdue) SoftRed else if (dueStatus.isDueSoon) DiscreetGold else BordeauxDark
                        )
                    } else {
                        Text(
                            text = "Não programado",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraySecondary
                        )
                    }
                }
            }

            // Status Badge Row
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = dueStatus.badgeBgColor
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(dueStatus.badgeTextColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = dueStatus.badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = dueStatus.badgeTextColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = dueStatus.detailText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dueStatus.isOverdue) SoftRed else GraySecondary,
                    fontWeight = if (dueStatus.isOverdue) FontWeight.Bold else FontWeight.Normal
                )
            }

            // Optional Vet & Clinic info
            if (record.clinicName.isNotBlank() || record.vetName.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = listOfNotNull(
                        record.clinicName.takeIf { it.isNotBlank() },
                        record.vetName.takeIf { it.isNotBlank() }?.let { "Dr(a). $it" }
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary
                )
            }

            // Optional Notes / Batch
            if (record.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Obs: ${record.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary.copy(alpha = 0.9f)
                )
            }
        }
    }
}

/**
 * Educational reference guide explaining the standard canine vaccination schedule.
 */
@Composable
private fun CanineVaccineProtocolCard(
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = BordeauxPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Protocolo Canino Recomendado",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = BordeauxDark
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Ocultar" else "Expandir",
                        tint = GraySecondary
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = GrayBorder.copy(alpha = 0.6f))

                    ProtocolItem(
                        name = "V8 ou V10 (Polivalente)",
                        frequency = "Anual (ou protocolo inicial filhote)",
                        description = "Protege contra Cinomose, Parvovirose, Hepatite Infecciosa, Coronavirose, Adenovirose, Parainfluenza e Leptospirose."
                    )
                    ProtocolItem(
                        name = "Antirrábica (Raiva)",
                        frequency = "Anual (Obrigatória por lei)",
                        description = "Imunização contra a raiva, zoonose grave transmissível aos seres humanos."
                    )
                    ProtocolItem(
                        name = "Gripe Canina / Tosse dos Canis",
                        frequency = "Anual",
                        description = "Protege contra Bordetella bronchiseptica e vírus da Parainfluenza."
                    )
                    ProtocolItem(
                        name = "Giardíase",
                        frequency = "Anual",
                        description = "Prevenção contra cistos do protozoário Giardia lamblia."
                    )
                    ProtocolItem(
                        name = "Vermifugação",
                        frequency = "A cada 3 a 6 meses",
                        description = "Controle de vermes intestinais conforme estilo de vida e orientação do veterinário."
                    )
                    ProtocolItem(
                        name = "Antipulgas e Carrapatos",
                        frequency = "Mensal ou Trimestral",
                        description = "Prevenção constante contra ectoparasitas e doenças graves como a Doença do Carrapato (Erliquiose/Babesiose)."
                    )

                    Text(
                        text = "* Consulte sempre o médico veterinário de confiança da Stella para adequar o calendário individual.",
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolItem(
    name: String,
    frequency: String,
    description: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = BordeauxPrimary
            )
            Text(
                text = frequency,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = DiscreetGold
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = GraySecondary
        )
    }
}

/**
 * Empty state when no vaccination records match filters.
 */
@Composable
private fun EmptyVaccinationState(
    isFiltered: Boolean,
    onAddClick: () -> Unit,
    onResetFilters: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(BordeauxSoftContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = BordeauxPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isFiltered) "Nenhum registro encontrado" else "Nenhuma vacina registrada",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BordeauxDark,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isFiltered) {
                    "Nenhuma aplicação corresponde aos filtros ou à pesquisa realizada."
                } else {
                    "Mantenha a Stella protegida! Cadastre as vacinas aplicadas (V10, Antirrábica), vermífugos e antiparasitários."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = GraySecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            if (isFiltered) {
                OutlinedButton(
                    onClick = onResetFilters,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BordeauxPrimary),
                    border = BorderStroke(1.dp, BordeauxPrimary)
                ) {
                    Text("Limpar Filtros")
                }
            } else {
                Button(
                    onClick = onAddClick,
                    colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cadastrar Primeira Vacina")
                }
            }
        }
    }
}

/**
 * Dialog for adding a new vaccine or editing an existing one,
 * with presets, date validation, and automated reminder creation.
 */
@Composable
fun AddOrEditVaccineDialog(
    initialRecord: VaccinePreventionRecord? = null,
    onDismiss: () -> Unit,
    onSave: (record: VaccinePreventionRecord, scheduleReminder: Boolean) -> Unit
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val today = remember { sdfDate.format(Date()) }

    var name by remember { mutableStateOf(initialRecord?.name ?: "") }
    var type by remember { mutableStateOf(initialRecord?.type ?: "Vacina") }
    var dateAdministered by remember { mutableStateOf(initialRecord?.dateAdministered ?: today) }
    var dose by remember { mutableStateOf(initialRecord?.dose ?: "Dose Única / Anual") }
    var nextDueDate by remember { mutableStateOf(initialRecord?.nextDueDate ?: "") }
    var vetName by remember { mutableStateOf(initialRecord?.vetName ?: "") }
    var clinicName by remember { mutableStateOf(initialRecord?.clinicName ?: "") }
    var notes by remember { mutableStateOf(initialRecord?.notes ?: "") }
    var scheduleReminder by remember { mutableStateOf(true) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    val typeOptions = listOf("Vacina", "Vermífugo", "Antiparasitário", "Outro")
    val commonVaccineSuggestions = listOf("V10", "V8", "Antirrábica", "Gripe Canina", "Giárdia", "Simparic", "Bravecto", "Drontal")

    // Helper to calculate offset from dateAdministered
    fun applyDateOffset(months: Int) {
        try {
            val date = sdfDate.parse(DateInput.normalizeDate(dateAdministered)) ?: Date()
            val cal = Calendar.getInstance().apply {
                time = date
                add(Calendar.MONTH, months)
            }
            nextDueDate = sdfDate.format(cal.time)
            errorMessage = null
        } catch (_: Exception) {
            // Ignore if date format is invalid
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = BordeauxPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (initialRecord == null) "Registrar Vacina / Prevenção" else "Editar Registro",
                    fontWeight = FontWeight.Bold,
                    color = BordeauxPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Type selection chips
                Text(
                    text = "Tipo *",
                    style = MaterialTheme.typography.labelSmall,
                    color = GraySecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    typeOptions.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BordeauxPrimary,
                                selectedLabelColor = PureWhite
                            )
                        )
                    }
                }

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        errorMessage = null
                    },
                    label = { Text("Nome da Vacina / Medicamento *") },
                    placeholder = { Text("Ex: V10, Antirrábica, Simparic") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_vaccine_name")
                )

                // Quick name suggestions chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    items(commonVaccineSuggestions) { suggestion ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CreamOffWhite,
                            border = BorderStroke(1.dp, GrayBorder),
                            modifier = Modifier.clickable { name = suggestion }
                        ) {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelSmall,
                                color = BordeauxDark,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Dates row: Administered date & Dose
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DateTextField(
                        value = dateAdministered,
                        onValueChange = {
                            dateAdministered = it
                            errorMessage = null
                        },
                        label = { Text("Data Aplicação *") },
                        placeholder = { Text("DD/MM/AAAA") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_vaccine_date_administered")
                    )
                    OutlinedTextField(
                        value = dose,
                        onValueChange = { dose = it },
                        label = { Text("Dose") },
                        placeholder = { Text("Ex: Anual, 1ª dose") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_vaccine_dose")
                    )
                }

                // Next Due Date Field
                DateTextField(
                    value = nextDueDate,
                    onValueChange = {
                        nextDueDate = it
                        errorMessage = null
                    },
                    label = { Text("Próximo Reforço (opcional)") },
                    placeholder = { Text("DD/MM/AAAA") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_vaccine_next_due_date")
                )

                // Quick next date helpers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Calcular:", style = MaterialTheme.typography.labelSmall, color = GraySecondary)
                    DateHelperChip(label = "+1 ano") { applyDateOffset(12) }
                    DateHelperChip(label = "+6 meses") { applyDateOffset(6) }
                    DateHelperChip(label = "+3 meses") { applyDateOffset(3) }
                    DateHelperChip(label = "+1 mês") { applyDateOffset(1) }
                }

                // Vet & Clinic row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = vetName,
                        onValueChange = { vetName = it },
                        label = { Text("Veterinário(a)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = clinicName,
                        onValueChange = { clinicName = it },
                        label = { Text("Clínica") },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Lote / Fabricante / Observações") },
                    placeholder = { Text("Ex: Lote 9821, sem reações pós-vacina") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Automated reminder toggle if nextDueDate is set
                if (nextDueDate.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BordeauxVerySoft, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = scheduleReminder,
                            onCheckedChange = { scheduleReminder = it },
                            colors = CheckboxDefaults.colors(checkedColor = BordeauxPrimary)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Criar lembrete automático",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = BordeauxDark
                            )
                            Text(
                                text = "Notificar no celular na data do reforço ($nextDueDate)",
                                style = MaterialTheme.typography.labelSmall,
                                color = GraySecondary
                            )
                        }
                    }
                }

                // Error message display
                errorMessage?.let { errorText ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SoftRedContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorText,
                            color = SoftRed,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        errorMessage = "O nome da vacina ou medicamento é obrigatório."
                        return@Button
                    }
                    if (!DateTimeUtils.isValidDate(DateInput.normalizeDate(dateAdministered))) {
                        errorMessage = "Data de aplicação inválida (use o formato DD/MM/AAAA)."
                        return@Button
                    }
                    if (nextDueDate.isNotBlank() && !DateTimeUtils.isValidDate(nextDueDate)) {
                        errorMessage = "Data do próximo reforço inválida (use o formato DD/MM/AAAA)."
                        return@Button
                    }

                    val recordToSave = (initialRecord ?: VaccinePreventionRecord(
                        name = "",
                        dateAdministered = ""
                    )).copy(
                        name = name.trim(),
                        type = type.trim(),
                        dateAdministered = DateInput.normalizeDate(dateAdministered),
                        dose = dose.trim(),
                        nextDueDate = DateInput.normalizeDate(nextDueDate),
                        vetName = vetName.trim(),
                        clinicName = clinicName.trim(),
                        notes = notes.trim()
                    )

                    onSave(recordToSave, scheduleReminder && nextDueDate.isNotBlank())
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                modifier = Modifier.testTag("btn_save_vaccine")
            ) {
                Text(if (initialRecord == null) "Salvar Vacina" else "Atualizar", color = PureWhite)
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

@Composable
private fun DateHelperChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = BordeauxSoftContainer,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = BordeauxPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
