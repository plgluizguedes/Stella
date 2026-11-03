package com.example.util

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

enum class RefreshRateMode(
    val id: String,
    val targetHz: Int,
    val title: String,
    val description: String
) {
    AUTO(
        id = "auto",
        targetHz = 120,
        title = "Dinâmico / Até 120 Hz",
        description = "Fluidez adaptativa máxima compatível com o seu display (até 120 Hz)."
    ),
    HZ_120(
        id = "120",
        targetHz = 120,
        title = "120 Hz (Ultra Fluido)",
        description = "Prioriza 120 quadros por segundo para animações e rolagens ultra fluidas."
    ),
    HZ_90(
        id = "90",
        targetHz = 90,
        title = "90 Hz (Suave)",
        description = "Equilíbrio refinado entre alta taxa de quadros e consumo de energia."
    ),
    HZ_60(
        id = "60",
        targetHz = 60,
        title = "60 Hz (Padrão / Econômico)",
        description = "Taxa clássica de 60 quadros por segundo, ideal para economizar bateria."
    );

    companion object {
        fun fromId(id: String?): RefreshRateMode {
            return entries.firstOrNull { it.id == id } ?: AUTO
        }
    }
}

object RefreshRateManager {
    private const val TAG = "RefreshRateManager"
    private const val PREFS_NAME = "stella_display_preferences"
    private const val KEY_REFRESH_RATE_MODE = "refresh_rate_mode"

    fun getSavedMode(context: Context): RefreshRateMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_REFRESH_RATE_MODE, RefreshRateMode.AUTO.id)
        return RefreshRateMode.fromId(id)
    }

    fun saveMode(context: Context, mode: RefreshRateMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_REFRESH_RATE_MODE, mode.id).apply()
    }

    private fun getDisplay(context: Context): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display
            } catch (e: Exception) {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                dm?.getDisplay(Display.DEFAULT_DISPLAY)
            }
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay
        }
    }

    /**
     * Returns the list of distinct hardware refresh rates supported by the current display (e.g. [60, 90, 120]).
     */
    fun getSupportedRefreshRates(context: Context): List<Int> {
        val display = getDisplay(context) ?: return listOf(60)
        return display.supportedModes
            .map { it.refreshRate.roundToInt() }
            .distinct()
            .sorted()
    }

    /**
     * Checks if a specific target rate is supported by the physical display (within 2 Hz tolerance).
     */
    fun isRateSupported(context: Context, targetHz: Int): Boolean {
        val display = getDisplay(context) ?: return targetHz == 60
        return display.supportedModes.any { abs(it.refreshRate - targetHz.toFloat()) <= 2.0f }
    }

    /**
     * Returns the active refresh rate of the primary display.
     */
    fun getCurrentRefreshRate(context: Context): Float {
        val display = getDisplay(context) ?: return 60.0f
        return display.refreshRate
    }

    /**
     * Applies the desired refresh rate mode to the target Activity Window.
     */
    fun applyToWindow(activity: Activity, mode: RefreshRateMode) {
        try {
            val window = activity.window ?: return
            val layoutParams = window.attributes
            val display = getDisplay(activity)
            val supportedModes = display?.supportedModes ?: emptyArray()

            when (mode) {
                RefreshRateMode.HZ_60 -> {
                    val mode60 = supportedModes.minByOrNull { abs(it.refreshRate - 60.0f) }
                    if (mode60 != null && abs(mode60.refreshRate - 60.0f) <= 2.0f) {
                        layoutParams.preferredDisplayModeId = mode60.modeId
                    }
                    @Suppress("DEPRECATION")
                    layoutParams.preferredRefreshRate = 60.0f
                }
                RefreshRateMode.HZ_90 -> {
                    val mode90 = supportedModes.minByOrNull { abs(it.refreshRate - 90.0f) }
                    if (mode90 != null && abs(mode90.refreshRate - 90.0f) <= 2.0f) {
                        layoutParams.preferredDisplayModeId = mode90.modeId
                    }
                    @Suppress("DEPRECATION")
                    layoutParams.preferredRefreshRate = 90.0f
                }
                RefreshRateMode.HZ_120 -> {
                    val mode120 = supportedModes.minByOrNull { abs(it.refreshRate - 120.0f) }
                    if (mode120 != null && abs(mode120.refreshRate - 120.0f) <= 2.0f) {
                        layoutParams.preferredDisplayModeId = mode120.modeId
                    }
                    @Suppress("DEPRECATION")
                    layoutParams.preferredRefreshRate = 120.0f
                }
                RefreshRateMode.AUTO -> {
                    // Choose the highest supported mode up to 120Hz (or device maximum if higher)
                    val maxMode = supportedModes.filter { it.refreshRate <= 122.0f }.maxByOrNull { it.refreshRate }
                        ?: supportedModes.maxByOrNull { it.refreshRate }

                    if (maxMode != null && maxMode.refreshRate > 60.0f) {
                        layoutParams.preferredDisplayModeId = maxMode.modeId
                    } else {
                        layoutParams.preferredDisplayModeId = 0
                    }
                    @Suppress("DEPRECATION")
                    layoutParams.preferredRefreshRate = maxMode?.refreshRate ?: 120.0f
                }
            }

            window.attributes = layoutParams
            Log.d(TAG, "Applied refresh rate mode: ${mode.name} (modeId=${layoutParams.preferredDisplayModeId})")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying refresh rate mode: ${e.message}", e)
        }
    }
}
