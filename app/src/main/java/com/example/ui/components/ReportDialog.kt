package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ExamDocument
import com.example.data.model.FeedingRecord
import com.example.data.model.GlucoseRecord
import com.example.data.model.Medication
import com.example.data.model.StellaProfile
import com.example.data.model.VeterinaryAppointment
import com.example.data.model.WeightRecord
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.DiscreetGold
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.SoftGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MedicalReportDialog(
    profile: StellaProfile,
    glucoseRecords: List<GlucoseRecord>,
    medications: List<Medication>,
    appointments: List<VeterinaryAppointment>,
    documents: List<ExamDocument>,
    feeding: List<FeedingRecord>,
    weights: List<WeightRecord>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val todayStr = remember {
        SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault()).format(Date())
    }

    // Build plain text report for clipboard
    val reportText = remember(profile, glucoseRecords, medications, appointments, documents, feeding, weights) {
        buildString {
            appendLine("==============================================")
            appendLine("🐾 RELATÓRIO DE SAÚDE DO PET - STELLA 🐾")
            appendLine("Registro feito pelo tutor. Não é laudo nem documento veterinário.")
            appendLine("Gerado em: $todayStr")
            appendLine("==============================================")
            appendLine()
            appendLine("1. IDENTIFICAÇÃO")
            appendLine("• Nome: ${profile.name}")
            appendLine("• Espécie: ${profile.species}")
            appendLine("• Raça: ${profile.breed}")
            appendLine("• Sexo: ${profile.gender}")
            appendLine("• Data de Nascimento: ${profile.birthDate}")
            appendLine("• Peso Atual: ${profile.currentWeight} kg")
            appendLine("• Microchip: ${profile.microchip}")
            appendLine("• Veterinário Principal: ${profile.vetName} (${profile.clinicName})")
            appendLine("• Contato da Clínica: ${profile.clinicPhone}")
            appendLine()
            appendLine("2. ALIMENTAÇÃO ATUAL")
            val currentDiet = feeding.firstOrNull { it.isCurrent }
            if (currentDiet != null) {
                appendLine("• Ração: ${currentDiet.brand} - ${currentDiet.foodName} (${currentDiet.line})")
                appendLine("• Quantidade Diária: ${currentDiet.dailyAmount} (${currentDiet.mealsPerDay}x ao dia - ${currentDiet.scheduleTimes})")
            } else {
                appendLine("• Nenhuma ração marcada como em uso atual.")
            }
            appendLine()
            appendLine("3. RESUMO GLICÊMICO")
            if (glucoseRecords.isNotEmpty()) {
                val lastG = glucoseRecords.first()
                val avg = glucoseRecords.map { it.value }.average()
                appendLine("• Última medição: ${lastG.value.roundToInt()} mg/dL (${lastG.dateString} ${lastG.timeString} - ${lastG.mealRelation})")
                appendLine("• Média geral (${glucoseRecords.size} registros): ${String.format("%.1f", avg)} mg/dL")
                appendLine("• Últimas 5 medições:")
                glucoseRecords.take(5).forEach { g ->
                    appendLine("  - ${g.dateString} ${g.timeString}: ${g.value.roundToInt()} mg/dL [${g.mealRelation}] ${g.notes}")
                }
            } else {
                appendLine("• Nenhuma medição de glicemia registrada.")
            }
            appendLine()
            appendLine("4. MEDICAMENTOS E SUPLEMENTOS ATIVOS")
            val activeMeds = medications.filter { it.status == "ATIVO" }
            if (activeMeds.isNotEmpty()) {
                activeMeds.forEach { m ->
                    val type = if (m.isVitaminOrSupplement) "Suplemento" else "Medicamento"
                    appendLine("• [$type] ${m.name} (${m.dosage}) - ${m.frequency} [Horários: ${m.scheduledTimes}]")
                    if (m.reason.isNotBlank()) appendLine("  Motivo: ${m.reason}")
                }
            } else {
                appendLine("• Nenhum medicamento ativo no momento.")
            }
            appendLine()
            appendLine("5. HISTÓRICO DE MEDICAMENTOS ENCERRADOS")
            val pastMeds = medications.filter { it.status == "ENCERRADO" }
            if (pastMeds.isNotEmpty()) {
                pastMeds.forEach { m ->
                    appendLine("• ${m.name} (${m.dosage}) - Início: ${m.startDate} | Parou: ${m.suspensionDate} (Motivo: ${m.suspensionReason})")
                }
            } else {
                appendLine("• Nenhum medicamento anterior registrado.")
            }
            appendLine()
            appendLine("6. CONSULTAS RECENTES")
            if (appointments.isNotEmpty()) {
                appointments.take(3).forEach { a ->
                    appendLine("• ${a.dateString}: ${a.reason} - Dr(a). ${a.vetName}")
                    if (a.weightKg != null) appendLine("  Peso: ${a.weightKg} kg")
                    if (a.conductRecommended.isNotBlank()) appendLine("  Conduta: ${a.conductRecommended}")
                }
            } else {
                appendLine("• Nenhuma consulta registrada.")
            }
            appendLine()
            appendLine("7. EXAMES E LAUDOS CADASTRADOS")
            if (documents.isNotEmpty()) {
                documents.take(5).forEach { d ->
                    appendLine("• [${d.category}] ${d.name} (${d.dateString}) - ${d.notes}")
                }
            } else {
                appendLine("• Nenhum documento cadastrado.")
            }
            appendLine()
            appendLine("----------------------------------------------")
            appendLine("Prontuário Veterinário Pessoal Digital STELLA")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .height(650.dp)
                .testTag("medical_report_modal"),
            shape = RoundedCornerShape(20.dp),
            color = CreamOffWhite,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, BordeauxPrimary)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Relatório de Saúde do Pet",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                        Text(
                            text = "Prontuário veterinário consolidado para o médico",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraySecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = GrayBorder)

                // Scrollable preview content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, GrayBorder, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Stella Card in report
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(BordeauxPrimary, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Pets, null, tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "${profile.name} • ${profile.breed}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = BordeauxDark
                            )
                            Text(
                                text = "${profile.gender} • Nasc: ${profile.birthDate} • Peso: ${profile.currentWeight} kg",
                                style = MaterialTheme.typography.bodySmall,
                                color = GraySecondary
                            )
                        }
                    }

                    HorizontalDivider(color = GrayBorder.copy(alpha = 0.5f))

                    // Sections
                    ReportSectionItem(title = "Veterinário Responsável", content = "${profile.vetName} • ${profile.clinicName} • Tel: ${profile.clinicPhone}")

                    val currentDiet = feeding.firstOrNull { it.isCurrent }
                    ReportSectionItem(
                        title = "Alimentação Atual",
                        content = if (currentDiet != null) "${currentDiet.brand} ${currentDiet.foodName} (${currentDiet.dailyAmount}/dia em ${currentDiet.mealsPerDay} refeições)" else "Não informada"
                    )

                    val activeMeds = medications.filter { it.status == "ATIVO" }
                    ReportSectionItem(
                        title = "Medicamentos & Suplementos Ativos (${activeMeds.size})",
                        content = if (activeMeds.isNotEmpty()) {
                            activeMeds.joinToString("\n") { "• ${it.name} (${it.dosage}) - ${it.frequency} às ${it.scheduledTimes}" }
                        } else "Nenhum medicamento ativo"
                    )

                    val lastGlucose = glucoseRecords.firstOrNull()
                    val glucoseAvg = if (glucoseRecords.isNotEmpty()) glucoseRecords.map { it.value }.average() else null
                    ReportSectionItem(
                        title = "Acompanhamento Glicêmico",
                        content = if (lastGlucose != null) {
                            "Última: ${lastGlucose.value.roundToInt()} mg/dL (${lastGlucose.dateString} às ${lastGlucose.timeString})\nMédia geral: ${glucoseAvg?.let { String.format("%.1f", it) } ?: "-"} mg/dL (${glucoseRecords.size} medições)"
                        } else "Nenhuma medição registrada"
                    )

                    ReportSectionItem(
                        title = "Consultas Recentes",
                        content = if (appointments.isNotEmpty()) {
                            appointments.take(3).joinToString("\n") { "• ${it.dateString}: ${it.reason} (${it.vetName})" }
                        } else "Nenhuma consulta registrada"
                    )

                    ReportSectionItem(
                        title = "Exames e Documentos",
                        content = if (documents.isNotEmpty()) {
                            documents.take(4).joinToString("\n") { "• [${it.category}] ${it.name} (${it.dateString})" }
                        } else "Nenhum exame cadastrado"
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Actions: Copy report to clipboard
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Relatório Stella", reportText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Relatório copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("copy_report_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copiar Relatório Completo", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportSectionItem(title: String, content: String) {
    Column {
        Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = BordeauxPrimary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = content, style = MaterialTheme.typography.bodySmall, color = GraySecondary)
    }
}
