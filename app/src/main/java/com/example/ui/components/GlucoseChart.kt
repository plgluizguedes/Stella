package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GlucoseRecord
import com.example.ui.theme.BordeauxDark
import com.example.ui.theme.BordeauxLight
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.DiscreetGold
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.SoftGreen
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun GlucoseChart(
    records: List<GlucoseRecord>,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp),
            shape = RoundedCornerShape(16.dp),
            color = CreamOffWhite,
            border = androidx.compose.foundation.BorderStroke(1.dp, GrayBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = BordeauxPrimary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Nenhuma medição registrada para o período selecionado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GraySecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Toque em '+ Glicemia' para registrar a primeira aferição.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BordeauxLight,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        return
    }

    // Sort chronologically ascending for line chart
    val sortedRecords = remember(records) {
        records.sortedBy { it.dateTimeMillis }
    }

    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val selectedRecord = selectedIndex?.let { if (it in sortedRecords.indices) sortedRecords[it] else null }

    val primaryColor = BordeauxPrimary
    val lineColor = BordeauxLight
    val gridColor = GrayBorder.copy(alpha = 0.6f)

    val values = sortedRecords.map { it.value }
    val rawMin = values.minOrNull() ?: 70.0
    val rawMax = values.maxOrNull() ?: 180.0
    val minVal = max(40.0, ((rawMin - 15.0) / 10.0).toInt() * 10.0)
    val maxVal = max(minVal + 60.0, ((rawMax + 20.0) / 10.0).toInt() * 10.0)

    Column(modifier = modifier.fillMaxWidth()) {
        // Selected point detail tooltip box
        AnimatedVisibility(
            visible = selectedRecord != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            selectedRecord?.let { item ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .testTag("glucose_tooltip_card"),
                    shape = RoundedCornerShape(12.dp),
                    color = CreamOffWhite,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BordeauxLight.copy(alpha = 0.5f)),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(BordeauxPrimary, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${item.dateString} às ${item.timeString}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = GraySecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${item.value.roundToInt()} ${item.unit}",
                                style = MaterialTheme.typography.titleMedium,
                                color = BordeauxDark,
                                fontWeight = FontWeight.Bold
                            )
                            if (item.mealRelation.isNotBlank() || item.medicationRelation.isNotBlank()) {
                                Text(
                                    text = listOf(item.mealRelation, item.medicationRelation)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BordeauxLight
                                )
                            }
                            if (item.notes.isNotBlank()) {
                                Text(
                                    text = "Obs: ${item.notes}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GraySecondary
                                )
                            }
                            if (item.symptoms.isNotBlank()) {
                                Text(
                                    text = "Sintomas: ${item.symptoms}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DiscreetGold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Interactive Canvas Chart
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(1.dp, GrayBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("glucose_interactive_canvas")
        ) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(sortedRecords) {
                        detectTapGestures { offset ->
                            val width = size.width.toFloat()
                            val stepX = if (sortedRecords.size > 1) {
                                width / (sortedRecords.size - 1).toFloat()
                            } else {
                                width / 2f
                            }
                            // Find closest record index
                            var closestIndex = 0
                            var minDistance = Float.MAX_VALUE
                            sortedRecords.indices.forEach { index ->
                                val x = if (sortedRecords.size > 1) index.toFloat() * stepX else width / 2f
                                val dist = kotlin.math.abs(offset.x - x)
                                if (dist < minDistance) {
                                    minDistance = dist
                                    closestIndex = index
                                }
                            }
                            // If close enough (within 40dp roughly)
                            if (minDistance <= 80f) {
                                selectedIndex = closestIndex
                            } else {
                                selectedIndex = null
                            }
                        }
                    }
            ) {
                val chartWidth = size.width
                val chartHeight = size.height
                val topPadding = 20f
                val bottomPadding = 25f
                val effectiveHeight = chartHeight - topPadding - bottomPadding

                // Draw horizontal reference lines (e.g. 4 lines)
                val gridSteps = 3
                for (i in 0..gridSteps) {
                    val stepRatio = i.toFloat() / gridSteps
                    val y = topPadding + effectiveHeight * (1f - stepRatio)
                    val labelVal = (minVal + (maxVal - minVal) * stepRatio).roundToInt()

                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(chartWidth, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }

                // If only 1 record
                if (sortedRecords.size == 1) {
                    val single = sortedRecords[0]
                    val normY = ((single.value - minVal) / (maxVal - minVal)).toFloat()
                    val y = topPadding + effectiveHeight * (1f - normY)
                    val x = chartWidth / 2f

                    drawCircle(
                        color = primaryColor,
                        radius = 8f,
                        center = Offset(x, y)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4f,
                        center = Offset(x, y)
                    )
                    return@Canvas
                }

                val stepX = chartWidth / (sortedRecords.size - 1)

                // Build line path & fill gradient
                val linePath = Path()
                val fillPath = Path()

                fillPath.moveTo(0f, chartHeight - bottomPadding)

                val points = sortedRecords.mapIndexed { index, item ->
                    val normY = ((item.value - minVal) / (maxVal - minVal)).toFloat().coerceIn(0f, 1f)
                    val y = topPadding + effectiveHeight * (1f - normY)
                    val x = index * stepX
                    Offset(x, y)
                }

                points.forEachIndexed { index, point ->
                    if (index == 0) {
                        linePath.moveTo(point.x, point.y)
                        fillPath.lineTo(point.x, point.y)
                    } else {
                        // Smooth cubic bezier or straight lines
                        val prev = points[index - 1]
                        val cx = (prev.x + point.x) / 2f
                        linePath.cubicTo(cx, prev.y, cx, point.y, point.x, point.y)
                        fillPath.cubicTo(cx, prev.y, cx, point.y, point.x, point.y)
                    }
                }

                fillPath.lineTo(points.last().x, chartHeight - bottomPadding)
                fillPath.close()

                // Draw gradient under the curve
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.22f),
                            primaryColor.copy(alpha = 0.02f)
                        ),
                        startY = topPadding,
                        endY = chartHeight - bottomPadding
                    )
                )

                // Draw main curve stroke
                drawPath(
                    path = linePath,
                    color = primaryColor,
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                )

                // Draw visible points on graph
                points.forEachIndexed { index, point ->
                    val isSelected = selectedIndex == index

                    // Outer dot
                    drawCircle(
                        color = if (isSelected) DiscreetGold else primaryColor,
                        radius = if (isSelected) 8.dp.toPx() else 5.dp.toPx(),
                        center = point
                    )
                    // Inner dot
                    drawCircle(
                        color = Color.White,
                        radius = if (isSelected) 4.dp.toPx() else 2.5.dp.toPx(),
                        center = point
                    )

                    // Draw vertical guide line for selected point
                    if (isSelected) {
                        drawLine(
                            color = DiscreetGold,
                            start = Offset(point.x, topPadding),
                            end = Offset(point.x, chartHeight - bottomPadding),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${minVal.roundToInt()} mg/dL",
                style = MaterialTheme.typography.labelSmall,
                color = GraySecondary
            )
            Text(
                text = "Toque nos pontos para ver detalhes",
                style = MaterialTheme.typography.labelSmall,
                color = GraySecondary
            )
            Text(
                text = "${maxVal.roundToInt()} mg/dL",
                style = MaterialTheme.typography.labelSmall,
                color = GraySecondary
            )
        }
    }
}
