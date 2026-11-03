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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.sp
import com.example.data.model.GlucoseRecord
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.components.GlucoseChart
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxLight
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.BordeauxSoftContainer
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.DiscreetGold
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftGreen
import com.example.ui.theme.SoftRed
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun GlucoseScreen(
    records: List<GlucoseRecord>,
    onAddGlucoseClick: () -> Unit,
    onDeleteGlucoseClick: (Long) -> Unit
) {
    var periodFilter by remember { mutableStateOf("7D") } // "HOJE", "7D", "30D", "TODOS"
    var recordToDelete by remember { mutableStateOf<GlucoseRecord?>(null) }

    val todayStr = remember {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
    }

    // Filter records according to selected period
    val filteredRecords = remember(records, periodFilter) {
        val now = System.currentTimeMillis()
        when (periodFilter) {
            "HOJE" -> records.filter { it.dateString == todayStr }
            "7D" -> {
                val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
                records.filter { it.dateTimeMillis >= sevenDaysAgo }
            }
            "30D" -> {
                val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000
                records.filter { it.dateTimeMillis >= thirtyDaysAgo }
            }
            else -> records
        }
    }

    // Automatic statistics
    val todayRecords = remember(records) { records.filter { it.dateString == todayStr } }
    val avgToday = if (todayRecords.isNotEmpty()) todayRecords.map { it.value }.average() else null

    val now = System.currentTimeMillis()
    val sevenDaysRecords = remember(records) {
        val minTime = now - 7L * 24 * 60 * 60 * 1000
        records.filter { it.dateTimeMillis >= minTime }
    }
    val avg7Days = if (sevenDaysRecords.isNotEmpty()) sevenDaysRecords.map { it.value }.average() else null

    val thirtyDaysRecords = remember(records) {
        val minTime = now - 30L * 24 * 60 * 60 * 1000
        records.filter { it.dateTimeMillis >= minTime }
    }
    val avg30Days = if (thirtyDaysRecords.isNotEmpty()) thirtyDaysRecords.map { it.value }.average() else null

    val allValues = records.map { it.value }
    val minValue = allValues.minOrNull()
    val maxValue = allValues.maxOrNull()
    val totalCount = records.size

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddGlucoseClick,
                containerColor = BordeauxPrimary,
                contentColor = PureWhite,
                modifier = Modifier.testTag("fab_add_glucose")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nova Medição")
            }
        },
        containerColor = CreamOffWhite
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("glucose_screen_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Info & Disclaimer
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Controle de Glicemia",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                        Button(
                            onClick = onAddGlucoseClick,
                            colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Nova Aferição", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Curva glicêmica e acompanhamento contínuo da Stella",
                        style = MaterialTheme.typography.bodySmall,
                        color = GraySecondary
                    )
                }
            }

            // Veterinary Medical Disclaimer Banner
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BordeauxSoftContainer,
                    border = BorderStroke(1.dp, BordeauxLight.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = BordeauxPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Aviso importante: Esta ferramenta destina-se exclusivamente ao registro e visualização de dados para o tutor e o veterinário. Não fornece diagnósticos nem substitui a avaliação médica.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = BordeauxDark,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Period Selector Chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("HOJE" to "Hoje", "7D" to "Últimos 7 dias", "30D" to "Últimos 30 dias", "TODOS" to "Histórico Total").forEach { (key, label) ->
                        FilterChip(
                            selected = periodFilter == key,
                            onClick = { periodFilter = key },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BordeauxPrimary,
                                selectedLabelColor = PureWhite
                            )
                        )
                    }
                }
            }

            // Glucose Line Chart Card
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PureWhite,
                    border = BorderStroke(1.dp, GrayBorder),
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Curva Glicêmica",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = BordeauxDark
                            )
                            Text(
                                text = "${filteredRecords.size} medições",
                                style = MaterialTheme.typography.labelSmall,
                                color = GraySecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        GlucoseChart(records = filteredRecords)
                    }
                }
            }

            // Automatic Statistics Grid
            item {
                Text(
                    text = "Estatísticas Automáticas",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBadge(
                            label = "Média de Hoje",
                            value = avgToday?.let { "${it.roundToInt()} mg/dL" } ?: "--",
                            color = BordeauxPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        StatBadge(
                            label = "Média 7 Dias",
                            value = avg7Days?.let { "${it.roundToInt()} mg/dL" } ?: "--",
                            color = BordeauxLight,
                            modifier = Modifier.weight(1f)
                        )
                        StatBadge(
                            label = "Média 30 Dias",
                            value = avg30Days?.let { "${it.roundToInt()} mg/dL" } ?: "--",
                            color = BordeauxDark,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBadge(
                            label = "Menor Valor",
                            value = minValue?.let { "${it.roundToInt()} mg/dL" } ?: "--",
                            color = SoftGreen,
                            modifier = Modifier.weight(1f)
                        )
                        StatBadge(
                            label = "Maior Valor",
                            value = maxValue?.let { "${it.roundToInt()} mg/dL" } ?: "--",
                            color = DiscreetGold,
                            modifier = Modifier.weight(1f)
                        )
                        StatBadge(
                            label = "Total de Aferições",
                            value = "$totalCount",
                            color = GraySecondary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Chronological Log List
            item {
                Text(
                    text = "Histórico Cronológico",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
            }

            if (records.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = PureWhite,
                        border = BorderStroke(1.dp, GrayBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Nenhuma aferição registrada ainda.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = GraySecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onAddGlucoseClick,
                                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
                            ) {
                                Text("Registrar Primeira Glicemia", color = PureWhite)
                            }
                        }
                    }
                }
            } else {
                items(records.sortedByDescending { it.dateTimeMillis }) { record ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("glucose_item_${record.id}"),
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
                                    .size(44.dp)
                                    .background(BordeauxSoftContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${record.value.roundToInt()}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BordeauxPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${record.dateString} às ${record.timeString}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = BordeauxDark
                                    )
                                    Text(
                                        text = "${record.value.roundToInt()} ${record.unit}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BordeauxPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = listOf(record.mealRelation, record.medicationRelation)
                                        .filter { it.isNotBlank() && it != "Sem relação" }
                                        .joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BordeauxLight
                                )
                                if (record.notes.isNotBlank()) {
                                    Text(
                                        text = "Obs: ${record.notes}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GraySecondary
                                    )
                                }
                                if (record.symptoms.isNotBlank()) {
                                    Text(
                                        text = "Sintomas: ${record.symptoms}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DiscreetGold
                                    )
                                }
                            }
                            IconButton(
                                onClick = { recordToDelete = record },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Excluir",
                                    tint = GraySecondary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    recordToDelete?.let { rec ->
        ConfirmDeleteDialog(
            title = "Excluir Medição",
            message = "Tem certeza que deseja excluir a aferição de ${rec.value.roundToInt()} mg/dL realizada em ${rec.dateString} às ${rec.timeString}?",
            onConfirm = { onDeleteGlucoseClick(rec.id) },
            onDismiss = { recordToDelete = null }
        )
    }
}

@Composable
private fun StatBadge(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = GraySecondary, maxLines = 1)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
        }
    }
}
