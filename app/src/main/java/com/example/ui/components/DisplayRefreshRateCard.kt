package com.example.ui.components

import android.app.Activity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.util.RefreshRateManager
import com.example.util.RefreshRateMode
import kotlin.math.roundToInt

@Composable
fun DisplayRefreshRateCard(
    currentMode: RefreshRateMode,
    onModeSelected: (RefreshRateMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val supportedRates = remember { RefreshRateManager.getSupportedRefreshRates(context) }
    var currentActiveHz by remember { mutableFloatStateOf(RefreshRateManager.getCurrentRefreshRate(context)) }

    // Re-check display rate periodically or when mode changes
    LaunchedEffect(currentMode) {
        currentActiveHz = RefreshRateManager.getCurrentRefreshRate(context)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("display_refresh_rate_card"),
        shape = RoundedCornerShape(16.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, GrayBorder),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row: Title, Icon and Active Hz Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(BordeauxSoftContainer, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = BordeauxPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Taxa de Atualização da Tela",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BordeauxDark
                        )
                        Text(
                            text = "Suporte a 60, 90 e 120 Hz",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraySecondary
                        )
                    }
                }

                // Dynamic Active Hz Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SoftGreenContainer,
                    border = BorderStroke(1.dp, SoftGreen.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(SoftGreen, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${currentActiveHz.roundToInt()} Hz Ativo",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = SoftGreen
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Personalize a fluidez das rolagens e transições. O app sincroniza a renderização diretamente com a taxa de atualização do seu visor.",
                style = MaterialTheme.typography.bodySmall,
                color = GraySecondary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Hardware compatibility notice
            HardwareCapabilitiesBar(supportedRates = supportedRates)

            Spacer(modifier = Modifier.height(14.dp))

            // Mode Selector Cards
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RefreshRateOptionRow(
                    mode = RefreshRateMode.AUTO,
                    isSelected = currentMode == RefreshRateMode.AUTO,
                    badgeText = "Recomendado",
                    badgeColor = BordeauxSoftContainer,
                    badgeTextColor = BordeauxPrimary,
                    icon = Icons.Default.Bolt,
                    onClick = { onModeSelected(RefreshRateMode.AUTO) }
                )

                RefreshRateOptionRow(
                    mode = RefreshRateMode.HZ_120,
                    isSelected = currentMode == RefreshRateMode.HZ_120,
                    badgeText = "120 FPS",
                    badgeColor = SoftGreenContainer,
                    badgeTextColor = SoftGreen,
                    icon = Icons.Default.RocketLaunch,
                    isHardwareSupported = supportedRates.any { it in 118..122 },
                    onClick = { onModeSelected(RefreshRateMode.HZ_120) }
                )

                RefreshRateOptionRow(
                    mode = RefreshRateMode.HZ_90,
                    isSelected = currentMode == RefreshRateMode.HZ_90,
                    badgeText = "90 FPS",
                    badgeColor = DiscreetGoldContainer,
                    badgeTextColor = DiscreetGold,
                    icon = Icons.Default.Speed,
                    isHardwareSupported = supportedRates.any { it in 88..92 },
                    onClick = { onModeSelected(RefreshRateMode.HZ_90) }
                )

                RefreshRateOptionRow(
                    mode = RefreshRateMode.HZ_60,
                    isSelected = currentMode == RefreshRateMode.HZ_60,
                    badgeText = "Econômico",
                    badgeColor = CreamOffWhite,
                    badgeTextColor = GraySecondary,
                    icon = Icons.Default.CheckCircle,
                    isHardwareSupported = true,
                    onClick = { onModeSelected(RefreshRateMode.HZ_60) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Interactive Smoothness Test Banner
            SmoothnessTesterSection(currentHz = currentActiveHz.roundToInt())
        }
    }
}

@Composable
private fun HardwareCapabilitiesBar(supportedRates: List<Int>) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = CreamOffWhite,
        border = BorderStroke(1.dp, GrayBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = BordeauxPrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Modos detectados no aparelho: ${supportedRates.joinToString(", ") { "$it Hz" }}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = BordeauxDark
            )
        }
    }
}

@Composable
private fun RefreshRateOptionRow(
    mode: RefreshRateMode,
    isSelected: Boolean,
    badgeText: String,
    badgeColor: androidx.compose.ui.graphics.Color,
    badgeTextColor: androidx.compose.ui.graphics.Color,
    icon: ImageVector,
    isHardwareSupported: Boolean = true,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) BordeauxPrimary else GrayBorder
    val containerBg = if (isSelected) BordeauxSoftContainer.copy(alpha = 0.4f) else PureWhite

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("refresh_rate_option_${mode.id}"),
        shape = RoundedCornerShape(12.dp),
        color = containerBg,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Radio Indicator
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(
                        width = if (isSelected) 6.dp else 2.dp,
                        color = if (isSelected) BordeauxPrimary else GraySecondary.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = BordeauxDark
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeTextColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = GraySecondary
                )
                if (!isHardwareSupported) {
                    Text(
                        text = "Aviso: painel físico atual não reportou este modo nativo; o Android fará o melhor ajuste disponível.",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = DiscreetGold
                    )
                }
            }
        }
    }
}

@Composable
private fun SmoothnessTesterSection(currentHz: Int) {
    val transition = rememberInfiniteTransition(label = "smoothness_tester")
    val animProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ball_anim"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CreamOffWhite,
        border = BorderStroke(1.dp, GrayBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Teste de Fluidez em Tempo Real",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxDark
                )
                Text(
                    text = "$currentHz FPS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = BordeauxPrimary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Smooth animated track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(PureWhite, RoundedCornerShape(13.dp))
                    .border(1.dp, GrayBorder, RoundedCornerShape(13.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            val maxOffsetPx = 240.dp.toPx() // bounded dynamic motion
                            IntOffset((animProgress * maxOffsetPx).toInt(), 0)
                        }
                        .size(18.dp)
                        .background(BordeauxPrimary, CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Animação executada sincronizada com o VSYNC da tela. Teste a rolagem das telas do prontuário para sentir o ganho de resposta.",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = GraySecondary
            )
        }
    }
}
