package com.example.util

/**
 * Limites para valores digitados. Servem para barrar erros de digitação (por exemplo, 1000 em vez de
 * 100), não para diagnóstico: valores dentro da faixa não significam "normal".
 *
 * ATENÇÃO: as faixas de glicemia e de peso são provisórias e abrangentes. Devem ser revisadas por um
 * médico-veterinário antes de qualquer uso além do acompanhamento pessoal.
 */
object InputLimits {
    const val GLUCOSE_MIN_MG_DL = 10.0
    const val GLUCOSE_MAX_MG_DL = 800.0
    const val WEIGHT_MIN_KG = 0.05
    const val WEIGHT_MAX_KG = 150.0

    fun isValidGlucose(valueMgDl: Double): Boolean =
        valueMgDl.isFinite() && valueMgDl in GLUCOSE_MIN_MG_DL..GLUCOSE_MAX_MG_DL

    fun isValidWeight(valueKg: Double): Boolean =
        valueKg.isFinite() && valueKg in WEIGHT_MIN_KG..WEIGHT_MAX_KG
}
