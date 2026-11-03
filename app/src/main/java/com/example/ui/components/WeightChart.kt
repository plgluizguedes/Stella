package com.example.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.WeightRecord
import com.example.ui.theme.BordeauxLight
import com.example.ui.theme.BordeauxPrimary
import com.example.ui.theme.CreamOffWhite
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.GraySecondary
import com.example.ui.theme.SoftGreen
import kotlin.math.max
import kotlin.math.min

@Composable
fun WeightChart(
    records: List<WeightRecord>,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp),
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
                    imageVector = Icons.Default.MonitorWeight,
                    contentDescription = null,
                    tint = BordeauxPrimary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Nenhum histórico de peso cadastrado ainda.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GraySecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Registre os pesos para visualizar a curva de evolução.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BordeauxLight,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        return
    }

    val sortedRecords = remember(records) {
        records.sortedBy { it.timestamp }
    }

    val weights = sortedRecords.map { it.weightKg }
    val currentWeight = sortedRecords.last().weightKg
    val minWeight = weights.minOrNull() ?: currentWeight
    val maxWeight = weights.maxOrNull() ?: currentWeight

    val displayMin = max(0.0, minWeight - 1.5)
    val displayMax = maxWeight + 1.5

    val lineColor = SoftGreen
    val primaryColor = BordeauxPrimary
    val gridColor = GrayBorder.copy(alpha = 0.6f)

    Column(modifier = modifier.fillMaxWidth()) {
        // Summary row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WeightMetricBadge(
                label = "Atual",
                value = String.format("%.1f kg", currentWeight),
                color = BordeauxPrimary,
                modifier = Modifier.weight(1f)
            )
            WeightMetricBadge(
                label = "Mínimo",
                value = String.format("%.1f kg", minWeight),
                color = SoftGreen,
                modifier = Modifier.weight(1f)
            )
            WeightMetricBadge(
                label = "Máximo",
                value = String.format("%.1f kg", maxWeight),
                color = BordeauxLight,
                modifier = Modifier.weight(1f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(1.dp, GrayBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("weight_chart_canvas")
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val chartWidth = size.width
                val chartHeight = size.height
                val topPadding = 15f
                val bottomPadding = 20f
                val effectiveHeight = chartHeight - topPadding - bottomPadding

                // Grid line
                drawLine(
                    color = gridColor,
                    start = Offset(0f, topPadding + effectiveHeight / 2f),
                    end = Offset(chartWidth, topPadding + effectiveHeight / 2f),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )

                if (sortedRecords.size == 1) {
                    val single = sortedRecords[0]
                    val normY = ((single.weightKg - displayMin) / (displayMax - displayMin)).toFloat()
                    val y = topPadding + effectiveHeight * (1f - normY)
                    val x = chartWidth / 2f

                    drawCircle(color = lineColor, radius = 7f, center = Offset(x, y))
                    drawCircle(color = Color.White, radius = 3.5f, center = Offset(x, y))
                    return@Canvas
                }

                val stepX = chartWidth / (sortedRecords.size - 1)
                val linePath = Path()

                val points = sortedRecords.mapIndexed { index, item ->
                    val normY = ((item.weightKg - displayMin) / (displayMax - displayMin)).toFloat().coerceIn(0f, 1f)
                    val y = topPadding + effectiveHeight * (1f - normY)
                    val x = index * stepX
                    Offset(x, y)
                }

                points.forEachIndexed { index, point ->
                    if (index == 0) linePath.moveTo(point.x, point.y)
                    else linePath.lineTo(point.x, point.y)
                }

                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )

                points.forEach { point ->
                    drawCircle(color = lineColor, radius = 5.dp.toPx(), center = point)
                    drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = point)
                }
            }
        }
    }
}

@Composable
private fun WeightMetricBadge(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = CreamOffWhite,
        border = androidx.compose.foundation.BorderStroke(1.dp, GrayBorder)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = GraySecondary)
            Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
        }
    }
}
