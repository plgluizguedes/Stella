package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.BordeauxSoftContainer
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.PureWhite
import com.example.util.DateInput
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Os seletores do Android usam as cores claras do app, mesmo com o celular no modo escuro. */
private val PickerColors = lightColorScheme(
    primary = BordeauxPrimary,
    onPrimary = PureWhite,
    primaryContainer = BordeauxSoftContainer,
    onPrimaryContainer = BordeauxDark,
    surface = CreamOffWhite,
    onSurface = BordeauxDark,
    surfaceVariant = BordeauxSoftContainer,
    onSurfaceVariant = GraySecondary,
    surfaceContainerHigh = CreamOffWhite,
    surfaceContainerHighest = BordeauxSoftContainer,
    outline = GrayBorder,
    outlineVariant = GrayBorder
)

// O calendário do Material trabalha em milissegundos de meia-noite UTC do dia escolhido.
private fun utcDateFormat() = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
    isLenient = false
}

private fun dateToPickerMillis(text: String): Long? =
    try {
        utcDateFormat().parse(text.trim())?.time
    } catch (e: Exception) {
        null
    }

/**
 * Campo de data. Dá para digitar de forma curta ("11/10" usa o ano atual; "11/10/26" vira 2026; "1110" também
 * funciona) e o texto é ajustado ao sair do campo; o ícone de calendário abre o seletor de datas do Android.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    // Aceito só para as chamadas antigas continuarem iguais: o ícone agora é o do calendário clicável.
    @Suppress("UNUSED_PARAMETER") trailingIcon: @Composable (() -> Unit)? = null
) {
    var showPicker by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        trailingIcon = {
            IconButton(
                onClick = { showPicker = true },
                modifier = Modifier.testTag("date_picker_button")
            ) {
                Icon(Icons.Default.CalendarToday, contentDescription = "Escolher data no calendário")
            }
        },
        modifier = modifier.onFocusChanged { state ->
            if (!state.isFocused) {
                val fixed = DateInput.normalizeDate(latestValue)
                if (fixed != latestValue) latestOnChange(fixed)
            }
        }
    )

    if (showPicker) {
        val initial = dateToPickerMillis(DateInput.normalizeDate(value)) ?: System.currentTimeMillis()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        MaterialTheme(colorScheme = PickerColors) {
            DatePickerDialog(
                onDismissRequest = { showPicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pickerState.selectedDateMillis?.let { onValueChange(utcDateFormat().format(it)) }
                            showPicker = false
                        },
                        modifier = Modifier.testTag("date_picker_ok")
                    ) { Text("Ok", color = BordeauxPrimary) }
                },
                dismissButton = {
                    TextButton(onClick = { showPicker = false }) { Text("Cancelar", color = BordeauxDark) }
                }
            ) {
                DatePicker(
                    state = pickerState,
                    // O título padrão ("19 de out. de 2026") fica cortado em telas estreitas.
                    headline = {
                        Text(
                            text = pickerState.selectedDateMillis?.let { utcDateFormat().format(it) }
                                ?: "Selecione a data",
                            style = MaterialTheme.typography.headlineSmall,
                            color = BordeauxDark,
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp)
                        )
                    }
                )
            }
        }
    }
}

/**
 * Campo de horário. Dá para digitar de forma curta ("930" vira 09:30, "9" vira 09:00); o ícone de relógio abre
 * o relógio do Android, com a opção de digitar hora e minuto em caixas separadas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") trailingIcon: @Composable (() -> Unit)? = null
) {
    var showPicker by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        trailingIcon = {
            IconButton(
                onClick = { showPicker = true },
                modifier = Modifier.testTag("time_picker_button")
            ) {
                Icon(Icons.Default.Schedule, contentDescription = "Escolher horário no relógio")
            }
        },
        modifier = modifier.onFocusChanged { state ->
            if (!state.isFocused) {
                val fixed = DateInput.normalizeTime(latestValue)
                if (fixed != latestValue) latestOnChange(fixed)
            }
        }
    )

    if (showPicker) {
        val now = Calendar.getInstance()
        val typed = DateInput.normalizeTime(value).split(":")
        val startHour = typed.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 && typed.size == 2 }
            ?: now.get(Calendar.HOUR_OF_DAY)
        val startMinute = typed.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 && typed.size == 2 }
            ?: now.get(Calendar.MINUTE)
        val pickerState = rememberTimePickerState(
            initialHour = startHour,
            initialMinute = startMinute,
            is24Hour = true
        )
        var typing by remember { mutableStateOf(false) }

        MaterialTheme(colorScheme = PickerColors) {
            AlertDialog(
                onDismissRequest = { showPicker = false },
                title = { Text("Escolher horário", color = BordeauxDark) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (typing) TimeInput(state = pickerState) else TimePicker(state = pickerState)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onValueChange("%02d:%02d".format(pickerState.hour, pickerState.minute))
                            showPicker = false
                        },
                        modifier = Modifier.testTag("time_picker_ok")
                    ) { Text("Ok", color = BordeauxPrimary) }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { typing = !typing }) {
                            Icon(
                                imageVector = if (typing) Icons.Default.Schedule else Icons.Default.Keyboard,
                                contentDescription = if (typing) "Usar o relógio" else "Digitar hora e minuto",
                                tint = BordeauxPrimary
                            )
                        }
                        TextButton(onClick = { showPicker = false }) { Text("Cancelar", color = BordeauxDark) }
                    }
                },
                containerColor = CreamOffWhite
            )
        }
    }
}
