package com.example.ui.components

import com.example.util.DateInput
import com.example.data.attachments.StageResult
import com.example.data.attachments.StagedAttachment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.ExamDocument
import com.example.data.model.Medication
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.SoftRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title, fontWeight = FontWeight.Bold, color = BordeauxDark)
        },
        text = {
            Text(text = message, color = GraySecondary)
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
                modifier = Modifier.testTag("confirm_delete_button")
            ) {
                Text("Excluir", color = androidx.compose.ui.graphics.Color.White)
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
fun AddGlucoseDialog(
    onDismiss: () -> Unit,
    onSave: (date: String, time: String, value: Double, unit: String, meal: String, med: String, notes: String, symptoms: String) -> Unit
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val sdfTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val now = remember { Date() }

    var date by remember { mutableStateOf(sdfDate.format(now)) }
    var time by remember { mutableStateOf(sdfTime.format(now)) }
    var valueStr by remember { mutableStateOf("") }
    var mealRelation by remember { mutableStateOf("Antes da alimentação") }
    var medRelation by remember { mutableStateOf("Sem relação") }
    var notes by remember { mutableStateOf("") }
    var symptoms by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val mealOptions = listOf("Antes da alimentação", "Depois da alimentação", "Em jejum", "Não relacionado")
    val medOptions = listOf("Sem relação", "Antes da insulina/medicação", "Após insulina/medicação")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Registrar Glicemia", fontWeight = FontWeight.Bold, color = BordeauxPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Acompanhamento da curva glicêmica da Stella",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraySecondary
                )

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Data") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("glucose_input_date"),
                        trailingIcon = { Icon(Icons.Default.CalendarToday, null) }
                    )
                    TimeTextField(
                        value = time,
                        onValueChange = { time = it },
                        label = { Text("Horário") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("glucose_input_time"),
                        trailingIcon = { Icon(Icons.Default.Schedule, null) }
                    )
                }

                OutlinedTextField(
                    value = valueStr,
                    onValueChange = {
                        valueStr = it
                        errorMsg = null
                    },
                    label = { Text("Valor da Glicemia (mg/dL) *") },
                    placeholder = { Text("Ex: 92") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = errorMsg != null,
                    supportingText = { errorMsg?.let { Text(it, color = SoftRed) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("glucose_input_value")
                )

                Text("Momento em relação à alimentação:", style = MaterialTheme.typography.labelMedium, color = BordeauxDark)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    mealOptions.take(2).forEach { opt ->
                        FilterChip(
                            selected = mealRelation == opt,
                            onClick = { mealRelation = opt },
                            label = { Text(opt, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BordeauxPrimary,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    mealOptions.drop(2).forEach { opt ->
                        FilterChip(
                            selected = mealRelation == opt,
                            onClick = { mealRelation = opt },
                            label = { Text(opt, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BordeauxPrimary,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }

                Text("Relação com medicação:", style = MaterialTheme.typography.labelMedium, color = BordeauxDark)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    medOptions.forEach { opt ->
                        FilterChip(
                            selected = medRelation == opt,
                            onClick = { medRelation = opt },
                            label = { Text(opt, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BordeauxPrimary,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observação") },
                    placeholder = { Text("Ex: Dormiu normalmente, passeou de manhã") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = symptoms,
                    onValueChange = { symptoms = it },
                    label = { Text("Sintomas ou acontecimentos") },
                    placeholder = { Text("Ex: Vômito, fraqueza, sede excessiva") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = valueStr.replace(',', '.').toDoubleOrNull()
                    if (parsed == null || !com.example.util.InputLimits.isValidGlucose(parsed)) {
                        errorMsg = "Informe um valor entre 10 e 800 mg/dL"
                        return@Button
                    }
                    if (!com.example.util.DateTimeUtils.isValidDateTime(DateInput.normalizeDate(date), DateInput.normalizeTime(time))) {
                        errorMsg = "Data ou horário inválido (use DD/MM/AAAA e HH:MM)"
                        return@Button
                    }
                    onSave(DateInput.normalizeDate(date), DateInput.normalizeTime(time), parsed, "mg/dL", mealRelation, medRelation, notes, symptoms)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                modifier = Modifier.testTag("save_glucose_button")
            ) {
                Text("Salvar Medição", color = androidx.compose.ui.graphics.Color.White)
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
fun AddMedicationDialog(
    isVitaminDefault: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (
        name: String, activeIngredient: String, dosage: String, unit: String, route: String,
        qty: String, frequency: String, times: String, start: String, end: String,
        vet: String, reason: String, notes: String, isVitamin: Boolean
    ) -> Unit
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val today = remember { sdfDate.format(Date()) }

    var isVitamin by remember { mutableStateOf(isVitaminDefault) }
    var name by remember { mutableStateOf("") }
    var activeIngredient by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("comprimido") }
    var route by remember { mutableStateOf("Oral") }
    var quantity by remember { mutableStateOf("1") }
    var frequency by remember { mutableStateOf("A cada 12 horas") }
    var scheduledTimes by remember { mutableStateOf("08:00, 20:00") }
    var startDate by remember { mutableStateOf(today) }
    var endDate by remember { mutableStateOf("") }
    var vetName by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isVitamin) "Nova Vitamina / Suplemento" else "Novo Medicamento",
                fontWeight = FontWeight.Bold,
                color = BordeauxPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isVitamin,
                        onClick = { isVitamin = false },
                        label = { Text("Medicamento") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BordeauxPrimary,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White
                        )
                    )
                    FilterChip(
                        selected = isVitamin,
                        onClick = { isVitamin = true },
                        label = { Text("Vitamina / Suplemento") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BordeauxPrimary,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White
                        )
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = false
                    },
                    label = { Text("Nome do item *") },
                    placeholder = { Text("Ex: Levetiracetam, Ômega 3") },
                    isError = nameError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("medication_input_name")
                )

                OutlinedTextField(
                    value = activeIngredient,
                    onValueChange = { activeIngredient = it },
                    label = { Text("Princípio Ativo") },
                    placeholder = { Text("Ex: Cloridrato de...") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dosage,
                        onValueChange = { dosage = it },
                        label = { Text("Dosagem *") },
                        placeholder = { Text("Ex: 250mg ou 1 comp") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("medication_input_dosage")
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Forma") },
                        placeholder = { Text("comp, ml, gotas") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = frequency,
                        onValueChange = { frequency = it },
                        label = { Text("Frequência") },
                        placeholder = { Text("12 em 12h, 1x dia") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = scheduledTimes,
                        onValueChange = { scheduledTimes = it },
                        label = { Text("Horários previstos") },
                        placeholder = { Text("08:00, 20:00") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTextField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = { Text("Data de início") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DateTextField(
                        value = endDate,
                        onValueChange = { endDate = it },
                        label = { Text("Data término (opc)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = vetName,
                    onValueChange = { vetName = it },
                    label = { Text("Veterinário Responsável") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Motivo da Utilização") },
                    placeholder = { Text("Ex: Controle de convulsões, proteção articular") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observações") },
                    placeholder = { Text("Ex: Administrar com alimento") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        nameError = true
                        return@Button
                    }
                    onSave(
                        name.trim(), activeIngredient.trim(), dosage.ifBlank { "Conforme receita" }.trim(),
                        unit.trim(), route.trim(), quantity.trim(), frequency.trim(), scheduledTimes.trim(),
                        DateInput.normalizeDate(startDate), DateInput.normalizeDate(endDate), vetName.trim(), reason.trim(), notes.trim(), isVitamin
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                modifier = Modifier.testTag("save_medication_button")
            ) {
                Text("Salvar", color = androidx.compose.ui.graphics.Color.White)
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
fun RecordAdministrationDialog(
    medication: Medication,
    onDismiss: () -> Unit,
    onConfirm: (date: String, time: String, dosage: String, registeredBy: String, notes: String) -> Unit
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val sdfTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val now = remember { Date() }

    var date by remember { mutableStateOf(sdfDate.format(now)) }
    var time by remember { mutableStateOf(sdfTime.format(now)) }
    var dosage by remember { mutableStateOf(medication.dosage) }
    var registeredBy by remember { mutableStateOf("Tutor") }
    var notes by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Registrar Administração",
                fontWeight = FontWeight.Bold,
                color = BordeauxPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = CreamOffWhite,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GrayBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = medication.name,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Prescrito: ${medication.dosage} • ${medication.frequency}",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraySecondary
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTextField(
                        value = date,
                        onValueChange = {
                            date = it
                            errorMsg = null
                        },
                        label = { Text("Data") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TimeTextField(
                        value = time,
                        onValueChange = {
                            time = it
                            errorMsg = null
                        },
                        label = { Text("Horário") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                errorMsg?.let {
                    Text(text = it, color = SoftRed, style = MaterialTheme.typography.bodySmall)
                }

                OutlinedTextField(
                    value = dosage,
                    onValueChange = { dosage = it },
                    label = { Text("Dose Administrada") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = registeredBy,
                    onValueChange = { registeredBy = it },
                    label = { Text("Quem administrou") },
                    placeholder = { Text("Ex: Tutor, Cuidador") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observações (opcional)") },
                    placeholder = { Text("Ex: Aceitou bem com petisco") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!com.example.util.DateTimeUtils.isValidDateTime(DateInput.normalizeDate(date), DateInput.normalizeTime(time))) {
                        errorMsg = "Data ou horário inválido (use DD/MM/AAAA e HH:MM)"
                        return@Button
                    }
                    onConfirm(DateInput.normalizeDate(date), DateInput.normalizeTime(time), dosage.trim(), registeredBy.trim(), notes.trim())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                modifier = Modifier.testTag("confirm_admin_button")
            ) {
                Text("Confirmar Registro", color = androidx.compose.ui.graphics.Color.White)
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
fun SuspendMedicationDialog(
    medication: Medication,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Encerrar Medicamento", fontWeight = FontWeight.Bold, color = BordeauxDark)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Deseja passar '${medication.name}' para o histórico de medicamentos encerrados? O registro não será apagado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GraySecondary
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Motivo da suspensão / alta médica") },
                    placeholder = { Text("Ex: Conclusão do ciclo de 10 dias, alta pelo vet") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(reason.trim())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
            ) {
                Text("Encerrar", color = androidx.compose.ui.graphics.Color.White)
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
fun AddAppointmentDialog(
    onDismiss: () -> Unit,
    onSave: (
        date: String, time: String, vet: String, clinic: String, reason: String,
        weight: Double?, temp: Double?, notes: String, symptoms: String, conduct: String,
        prescriptions: String, nextDate: String,
        attachments: List<StagedAttachment>
    ) -> Unit,
    onStageAttachment: (uri: String, label: String, onResult: (StageResult) -> Unit) -> Unit = { _, _, _ -> },
    onDiscardAttachment: (StagedAttachment) -> Unit = {}
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val sdfTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val now = remember { Date() }

    var date by remember { mutableStateOf(sdfDate.format(now)) }
    var time by remember { mutableStateOf(sdfTime.format(now)) }
    var vetName by remember { mutableStateOf("") }
    var clinicName by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var weightStr by remember { mutableStateOf("") }
    var tempStr by remember { mutableStateOf("") }
    var symptoms by remember { mutableStateOf("") }
    var conduct by remember { mutableStateOf("") }
    // Anexos preparados: se o diálogo fechar sem salvar, os arquivos copiados são descartados.
    val stagedAttachments = remember { StagedAttachmentsState() }
    var saved by remember { mutableStateOf(false) }
    val closeWithoutSaving = {
        if (!saved) stagedAttachments.discardAll(onDiscardAttachment)
        onDismiss()
    }
    var prescriptions by remember { mutableStateOf("") }
    var nextDate by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = closeWithoutSaving,
        title = {
            Text(text = "Registrar Consulta Veterinária", fontWeight = FontWeight.Bold, color = BordeauxPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTextField(
                        value = date,
                        onValueChange = {
                            date = it
                            errorMsg = null
                        },
                        label = { Text("Data *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TimeTextField(
                        value = time,
                        onValueChange = {
                            time = it
                            errorMsg = null
                        },
                        label = { Text("Horário") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                errorMsg?.let {
                    Text(text = it, color = SoftRed, style = MaterialTheme.typography.bodySmall)
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Motivo da Consulta *") },
                    placeholder = { Text("Ex: Retorno glicemia, rotina, vacinação") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vetName,
                        onValueChange = { vetName = it },
                        label = { Text("Veterinário(a)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = clinicName,
                        onValueChange = { clinicName = it },
                        label = { Text("Clínica / Hospital") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = weightStr,
                        onValueChange = { weightStr = it },
                        label = { Text("Peso (kg)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tempStr,
                        onValueChange = { tempStr = it },
                        label = { Text("Temperatura (°C)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = symptoms,
                    onValueChange = { symptoms = it },
                    label = { Text("Sintomas relatados") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = conduct,
                    onValueChange = { conduct = it },
                    label = { Text("Condutas recomendadas") },
                    placeholder = { Text("Ex: Dieta específica, monitorar glicemia 3x ao dia") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = prescriptions,
                    onValueChange = { prescriptions = it },
                    label = { Text("Medicamentos prescritos") },
                    modifier = Modifier.fillMaxWidth()
                )

                DateTextField(
                    value = nextDate,
                    onValueChange = { nextDate = it },
                    label = { Text("Próximo retorno / consulta") },
                    placeholder = { Text("Ex: 15/10/2026") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observações gerais") },
                    modifier = Modifier.fillMaxWidth()
                )

                StagedAttachmentsSection(
                    state = stagedAttachments,
                    onStage = onStageAttachment,
                    onDiscard = onDiscardAttachment
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!com.example.util.DateTimeUtils.isValidDateTime(DateInput.normalizeDate(date), DateInput.normalizeTime(time))) {
                        errorMsg = "Data ou horário da consulta inválido (use DD/MM/AAAA e HH:MM)"
                        return@Button
                    }
                    val w = weightStr.replace(',', '.').toDoubleOrNull()
                    val t = tempStr.replace(',', '.').toDoubleOrNull()
                    onSave(
                        DateInput.normalizeDate(date), DateInput.normalizeTime(time), vetName.trim(), clinicName.trim(), reason.ifBlank { "Consulta de rotina" }.trim(),
                        w, t, notes.trim(), symptoms.trim(), conduct.trim(), prescriptions.trim(), DateInput.normalizeDate(nextDate),
                        stagedAttachments.toSave()
                    )
                    // Marca antes de fechar: o fechamento que vem logo depois não pode descartar o que foi salvo.
                    saved = true
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
            ) {
                Text("Salvar Consulta", color = androidx.compose.ui.graphics.Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = closeWithoutSaving) {
                Text("Cancelar", color = GraySecondary)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = CreamOffWhite
    )
}

@Composable
fun AddExamDialog(
    initialDocument: ExamDocument? = null,
    onDismiss: () -> Unit,
    onSave: (
        name: String, fileType: String, date: String, clinic: String, vet: String, category: String,
        notes: String, uri: String?, attachments: List<StagedAttachment>
    ) -> Unit,
    onStageAttachment: (uri: String, label: String, onResult: (StageResult) -> Unit) -> Unit = { _, _, _ -> },
    onDiscardAttachment: (StagedAttachment) -> Unit = {},
    attachmentActions: AttachmentActions? = null
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val today = remember { sdfDate.format(Date()) }

    var name by remember { mutableStateOf(initialDocument?.name ?: "") }
    var date by remember { mutableStateOf(initialDocument?.dateString ?: today) }
    var clinic by remember { mutableStateOf(initialDocument?.clinicName ?: "") }
    var vet by remember { mutableStateOf(initialDocument?.vetName ?: "") }
    var category by remember { mutableStateOf(initialDocument?.category ?: "Exame de sangue") }
    var fileType by remember { mutableStateOf(initialDocument?.fileType ?: "Laudo / PDF") }
    var notes by remember { mutableStateOf(initialDocument?.notes ?: "") }
    var fileUriOrPath by remember { mutableStateOf(initialDocument?.fileUriOrPath) }
    // Anexos preparados (só ao criar): se o diálogo fechar sem salvar, os arquivos copiados são descartados.
    val stagedAttachments = remember { StagedAttachmentsState() }
    var saved by remember { mutableStateOf(false) }
    val closeWithoutSaving = {
        if (!saved) stagedAttachments.discardAll(onDiscardAttachment)
        onDismiss()
    }

    val categories = listOf(
        "Exame de sangue", "Glicemia", "Ultrassom", "Tomografia",
        "PCR", "Receita", "Consulta", "Vacina", "Documento", "Outros"
    )

    AlertDialog(
        onDismissRequest = closeWithoutSaving,
        title = {
            Text(
                text = if (initialDocument == null) "Cadastrar Exame / Documento" else "Editar Exame / Documento",
                fontWeight = FontWeight.Bold,
                color = BordeauxPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do Exame / Arquivo *") },
                    placeholder = { Text("Ex: Hemograma Completo, Ultrassom Abdominal") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("exam_name_input")
                )

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Data do Documento") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = fileType,
                        onValueChange = { fileType = it },
                        label = { Text("Tipo (PDF, JPG, etc.)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("Categoria:", style = MaterialTheme.typography.labelMedium, color = BordeauxDark)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categories.chunked(3).forEach { rowList ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rowList.forEach { cat ->
                                FilterChip(
                                    selected = category == cat,
                                    onClick = { category = cat },
                                    label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = BordeauxPrimary,
                                        selectedLabelColor = androidx.compose.ui.graphics.Color.White
                                    )
                                )
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = clinic,
                        onValueChange = { clinic = it },
                        label = { Text("Laboratório / Clínica") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = vet,
                        onValueChange = { vet = it },
                        label = { Text("Veterinário(a)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Resumo / Laudo / Observações") },
                    placeholder = { Text("Ex: Valores normais, fígado sem alterações") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Vários arquivos por exame/documento. Ao editar um documento que já existe, os anexos
                // são tratados à parte (não aqui), porque lá o efeito é imediato.
                if (initialDocument == null) {
                    StagedAttachmentsSection(
                        state = stagedAttachments,
                        onStage = onStageAttachment,
                        onDiscard = onDiscardAttachment
                    )
                }

                // Preservation of attached file (Requirement 4)
                if (!fileUriOrPath.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CreamOffWhite,
                        border = androidx.compose.foundation.BorderStroke(1.dp, GrayBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = BordeauxPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Arquivo Anexo Vinculado",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BordeauxDark
                                )
                                Text(
                                    text = fileUriOrPath.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GraySecondary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Documento que já existe: os anexos são acrescentados e removidos na hora (sem esperar salvar).
                if (initialDocument != null && attachmentActions != null) {
                    SavedAttachmentsSection(
                        ownerType = com.example.data.model.AttachmentOwner.EXAM_DOCUMENT,
                        ownerId = initialDocument.id,
                        actions = attachmentActions,
                        startExpanded = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    onSave(
                        name.trim(),
                        fileType.trim(),
                        DateInput.normalizeDate(date),
                        clinic.trim(),
                        vet.trim(),
                        category,
                        notes.trim(),
                        fileUriOrPath, // Preserves original attachment
                        stagedAttachments.toSave()
                    )
                    // Marca antes de fechar: o fechamento que vem logo depois não pode descartar o que foi salvo.
                    saved = true
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary),
                modifier = Modifier.testTag("save_exam_button")
            ) {
                Text(
                    text = if (initialDocument == null) "Salvar Arquivo" else "Salvar Alterações",
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        },
        dismissButton = {
            TextButton(onClick = closeWithoutSaving) {
                Text("Cancelar", color = GraySecondary)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = CreamOffWhite
    )
}

@Composable
fun AddWeightDialog(
    currentWeight: Double,
    onDismiss: () -> Unit,
    onSave: (date: String, weight: Double, notes: String) -> Unit
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val today = remember { sdfDate.format(Date()) }

    var date by remember { mutableStateOf(today) }
    var weightStr by remember { mutableStateOf(currentWeight.toString()) }
    var notes by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Registrar Peso da Stella", fontWeight = FontWeight.Bold, color = BordeauxPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DateTextField(
                    value = date,
                    onValueChange = {
                        date = it
                        errorMsg = null
                    },
                    label = { Text("Data da pesagem *") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = weightStr,
                    onValueChange = {
                        weightStr = it
                        errorMsg = null
                    },
                    label = { Text("Peso em kg *") },
                    placeholder = { Text("Ex: 14.5") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = errorMsg != null,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMsg?.let {
                    Text(text = it, color = SoftRed, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observação") },
                    placeholder = { Text("Ex: Pesada na balança da clínica após o jejum") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = weightStr.replace(',', '.').toDoubleOrNull()
                    if (w == null || !com.example.util.InputLimits.isValidWeight(w)) {
                        errorMsg = "Informe um peso entre 0,05 e 150 kg"
                        return@Button
                    }
                    if (!com.example.util.DateTimeUtils.isValidDate(DateInput.normalizeDate(date))) {
                        errorMsg = "Data da pesagem inválida (use DD/MM/AAAA)"
                        return@Button
                    }
                    onSave(DateInput.normalizeDate(date), w, notes.trim())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
            ) {
                Text("Salvar Peso", color = androidx.compose.ui.graphics.Color.White)
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
fun AddFeedingDialog(
    onDismiss: () -> Unit,
    onSave: (brand: String, food: String, type: String, line: String, flavor: String, daily: String, portion: String, meals: Int, times: String, start: String, notes: String) -> Unit
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val today = remember { sdfDate.format(Date()) }

    var brand by remember { mutableStateOf("") }
    var foodName by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Seca") }
    var line by remember { mutableStateOf("Super Premium") }
    var flavor by remember { mutableStateOf("Frango e Arroz") }
    var dailyAmount by remember { mutableStateOf("180g") }
    var portionAmount by remember { mutableStateOf("90g") }
    var mealsPerDay by remember { mutableStateOf("2") }
    var scheduleTimes by remember { mutableStateOf("08:00, 19:30") }
    var startDate by remember { mutableStateOf(today) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Cadastrar Ração / Dieta", fontWeight = FontWeight.Bold, color = BordeauxPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = brand,
                        onValueChange = { brand = it },
                        label = { Text("Marca *") },
                        placeholder = { Text("Ex: Royal Canin, Premier") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = foodName,
                        onValueChange = { foodName = it },
                        label = { Text("Nome da Ração *") },
                        placeholder = { Text("Ex: Diabetic / Cuidado Especial") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it },
                        label = { Text("Tipo (Seca, Úmida, Natural)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = line,
                        onValueChange = { line = it },
                        label = { Text("Linha") },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = flavor,
                    onValueChange = { flavor = it },
                    label = { Text("Sabor") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dailyAmount,
                        onValueChange = { dailyAmount = it },
                        label = { Text("Qtd. Diária") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = portionAmount,
                        onValueChange = { portionAmount = it },
                        label = { Text("Qtd. / Refeição") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mealsPerDay,
                        onValueChange = { mealsPerDay = it },
                        label = { Text("Refeições/dia") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = scheduleTimes,
                        onValueChange = { scheduleTimes = it },
                        label = { Text("Horários") },
                        modifier = Modifier.weight(1f)
                    )
                }

                DateTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("Data de início") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observações") },
                    placeholder = { Text("Ex: Transição gradual realizada com sucesso") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (foodName.isBlank()) return@Button
                    val meals = mealsPerDay.toIntOrNull() ?: 2
                    onSave(
                        brand.trim(), foodName.trim(), type.trim(), line.trim(),
                        flavor.trim(), dailyAmount.trim(), portionAmount.trim(),
                        meals, scheduleTimes.trim(), DateInput.normalizeDate(startDate), notes.trim()
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
            ) {
                Text("Salvar Alimentação", color = androidx.compose.ui.graphics.Color.White)
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
fun AddVaccineDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, type: String, date: String, dose: String, next: String, vet: String, clinic: String, notes: String) -> Unit
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val today = remember { sdfDate.format(Date()) }

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Vacina") }
    var date by remember { mutableStateOf(today) }
    var dose by remember { mutableStateOf("Dose Única / Anual") }
    var nextDueDate by remember { mutableStateOf("") }
    var vetName by remember { mutableStateOf("") }
    var clinicName by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    val typeOptions = listOf("Vacina", "Vermífugo", "Antiparasitário", "Outro")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Vacina ou Prevenção", fontWeight = FontWeight.Bold, color = BordeauxPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    typeOptions.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BordeauxPrimary,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome *") },
                    placeholder = { Text("Ex: V10, Antirrábica, Simparic, Bravecto") },
                    modifier = Modifier.fillMaxWidth()
                )

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Data de Aplicação") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dose,
                        onValueChange = { dose = it },
                        label = { Text("Dose") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                DateTextField(
                    value = nextDueDate,
                    onValueChange = { nextDueDate = it },
                    label = { Text("Próxima Dose / Reforço (opcional)") },
                    placeholder = { Text("Ex: 27/09/2027") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observações") },
                    placeholder = { Text("Ex: Lote, fabricante ou reações") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    onSave(name.trim(), type, DateInput.normalizeDate(date), dose.trim(), DateInput.normalizeDate(nextDueDate), vetName.trim(), clinicName.trim(), notes.trim())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
            ) {
                Text("Salvar", color = androidx.compose.ui.graphics.Color.White)
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
fun AddReminderDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, category: String, date: String, time: String, notes: String) -> Unit
) {
    val sdfDate = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val sdfTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val now = remember { Date() }

    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Medicamento") }
    var date by remember { mutableStateOf(sdfDate.format(now)) }
    var time by remember { mutableStateOf(sdfTime.format(now)) }
    var notes by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val categories = listOf("Medicamento", "Vitamina", "Consulta", "Exame", "Vacina", "Alimentação", "Outro")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Novo Lembrete / Tarefa", fontWeight = FontWeight.Bold, color = BordeauxPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        errorMsg = null
                    },
                    label = { Text("Título da Tarefa *") },
                    placeholder = { Text("Ex: Dar comprimido matinal, aferir glicemia") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Categoria:", style = MaterialTheme.typography.labelMedium, color = BordeauxDark)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categories.chunked(3).forEach { chunk ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            chunk.forEach { cat ->
                                FilterChip(
                                    selected = category == cat,
                                    onClick = { category = cat },
                                    label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = BordeauxPrimary,
                                        selectedLabelColor = androidx.compose.ui.graphics.Color.White
                                    )
                                )
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTextField(
                        value = date,
                        onValueChange = {
                            date = it
                            errorMsg = null
                        },
                        label = { Text("Data *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TimeTextField(
                        value = time,
                        onValueChange = {
                            time = it
                            errorMsg = null
                        },
                        label = { Text("Horário *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                errorMsg?.let {
                    Text(text = it, color = SoftRed, style = MaterialTheme.typography.bodySmall)
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observação") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        errorMsg = "Informe o título do lembrete"
                        return@Button
                    }
                    if (!com.example.util.DateTimeUtils.isValidDateTime(DateInput.normalizeDate(date), DateInput.normalizeTime(time))) {
                        errorMsg = "Data ou horário inválido (use DD/MM/AAAA e HH:MM)"
                        return@Button
                    }
                    onSave(title.trim(), category, DateInput.normalizeDate(date), DateInput.normalizeTime(time), notes.trim())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BordeauxPrimary)
            ) {
                Text("Criar Lembrete", color = androidx.compose.ui.graphics.Color.White)
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
