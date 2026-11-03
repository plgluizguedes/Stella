package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ExamDocument
import com.example.data.model.FeedingRecord
import com.example.data.model.GlucoseRecord
import com.example.data.model.Medication
import com.example.data.model.VaccinePreventionRecord
import com.example.data.model.VeterinaryAppointment
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxLight
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.BordeauxSoftContainer
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import kotlin.math.roundToInt

data class GlobalSearchResultItem(
    val title: String,
    val subtitle: String,
    val section: String,
    val date: String,
    val iconType: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchDialog(
    glucoseRecords: List<GlucoseRecord>,
    medications: List<Medication>,
    appointments: List<VeterinaryAppointment>,
    documents: List<ExamDocument>,
    feeding: List<FeedingRecord>,
    vaccines: List<VaccinePreventionRecord>,
    onDismiss: () -> Unit,
    onNavigateToSection: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedSectionFilter by remember { mutableStateOf<String?>(null) }

    val allItems = remember(glucoseRecords, medications, appointments, documents, feeding, vaccines) {
        val list = mutableListOf<GlobalSearchResultItem>()

        glucoseRecords.forEach {
            list.add(
                GlobalSearchResultItem(
                    title = "Glicemia: ${it.value.roundToInt()} ${it.unit}",
                    subtitle = "${it.mealRelation} • ${it.notes} ${it.symptoms}".trim(),
                    section = "Glicemia",
                    date = "${it.dateString} ${it.timeString}",
                    iconType = "glucose"
                )
            )
        }

        medications.forEach {
            val statusTag = if (it.status == "ATIVO") "Ativo" else "Encerrado"
            list.add(
                GlobalSearchResultItem(
                    title = "${it.name} (${it.dosage})",
                    subtitle = "${it.frequency} • $statusTag • ${it.activeIngredient} • ${it.reason}".trim(),
                    section = "Medicamentos",
                    date = it.startDate,
                    iconType = "medication"
                )
            )
        }

        appointments.forEach {
            list.add(
                GlobalSearchResultItem(
                    title = "Consulta: ${it.reason}",
                    subtitle = "Dr(a). ${it.vetName} • ${it.clinicName} • ${it.conductRecommended}".trim(),
                    section = "Consultas",
                    date = it.dateString,
                    iconType = "appointment"
                )
            )
        }

        documents.forEach {
            list.add(
                GlobalSearchResultItem(
                    title = it.name,
                    subtitle = "${it.category} • ${it.fileType} • ${it.notes}".trim(),
                    section = "Exames",
                    date = it.dateString,
                    iconType = "exam"
                )
            )
        }

        feeding.forEach {
            val cur = if (it.isCurrent) "Ração Atual" else "Ração Anterior"
            list.add(
                GlobalSearchResultItem(
                    title = "Alimentação: ${it.brand} ${it.foodName}",
                    subtitle = "$cur • ${it.dailyAmount}/dia • ${it.type} • ${it.notes}".trim(),
                    section = "Alimentação",
                    date = it.startDate,
                    iconType = "feeding"
                )
            )
        }

        vaccines.forEach {
            list.add(
                GlobalSearchResultItem(
                    title = "${it.type}: ${it.name}",
                    subtitle = "Dose: ${it.dose} • Próxima: ${it.nextDueDate} • ${it.notes}".trim(),
                    section = "Vacinas",
                    date = it.dateAdministered,
                    iconType = "vaccine"
                )
            )
        }

        list
    }

    val filteredResults = remember(query, selectedSectionFilter, allItems) {
        if (query.isBlank() && selectedSectionFilter == null) {
            emptyList()
        } else {
            allItems.filter { item ->
                val matchesQuery = query.isBlank() ||
                    item.title.contains(query, ignoreCase = true) ||
                    item.subtitle.contains(query, ignoreCase = true) ||
                    item.section.contains(query, ignoreCase = true) ||
                    item.date.contains(query, ignoreCase = true)

                val matchesSection = selectedSectionFilter == null || item.section == selectedSectionFilter

                matchesQuery && matchesSection
            }
        }
    }

    val filterChips = listOf("Glicemia", "Medicamentos", "Consultas", "Exames", "Alimentação", "Vacinas")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .height(650.dp)
                .testTag("global_search_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = CreamOffWhite,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, BordeauxPrimary)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = BordeauxPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Busca Global no Prontuário",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search field
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Pesquisar exames, glicemia, remédios...") },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = BordeauxPrimary) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("global_search_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Section filter chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filterChips.take(3).forEach { sec ->
                        FilterChip(
                            selected = selectedSectionFilter == sec,
                            onClick = {
                                selectedSectionFilter = if (selectedSectionFilter == sec) null else sec
                            },
                            label = { Text(sec, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BordeauxPrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filterChips.drop(3).forEach { sec ->
                        FilterChip(
                            selected = selectedSectionFilter == sec,
                            onClick = {
                                selectedSectionFilter = if (selectedSectionFilter == sec) null else sec
                            },
                            label = { Text(sec, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BordeauxPrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = GrayBorder)

                // Results list
                if (query.isBlank() && selectedSectionFilter == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Digite qualquer termo ou toque em uma categoria para buscar em todo o prontuário da Stella.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GraySecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else if (filteredResults.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Nenhum resultado encontrado para '$query'.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GraySecondary
                        )
                    }
                } else {
                    Text(
                        text = "${filteredResults.size} resultado(s) encontrado(s):",
                        style = MaterialTheme.typography.labelSmall,
                        color = GraySecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredResults) { item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onNavigateToSection(item.section)
                                        onDismiss()
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                border = androidx.compose.foundation.BorderStroke(1.dp, GrayBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(BordeauxSoftContainer, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val icon = when (item.iconType) {
                                            "glucose" -> Icons.Default.Speed
                                            "medication" -> Icons.Default.Medication
                                            "appointment" -> Icons.Default.Event
                                            "feeding" -> Icons.Default.Restaurant
                                            else -> Icons.Default.Description
                                        }
                                        Icon(icon, null, tint = BordeauxPrimary, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = BordeauxDark
                                            )
                                            Text(
                                                text = item.section,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = BordeauxPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        if (item.subtitle.isNotBlank()) {
                                            Text(
                                                text = item.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = GraySecondary,
                                                maxLines = 2
                                            )
                                        }
                                        Text(
                                            text = item.date,
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
        }
    }
}
