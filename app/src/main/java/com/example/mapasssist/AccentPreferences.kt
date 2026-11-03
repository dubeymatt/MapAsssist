package com.example.mapasssist

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat

data class AccentChoice(val label: String, val hue: Float, val primary: Int)

/** Persisted colour choices used by the app controls and optional map-series overrides. */
object AccentPreferences {
    val choices = listOf(
        AccentChoice("Green", 164f, Color.rgb(13, 94, 78)),
        AccentChoice("Blue", 210f, Color.rgb(33, 88, 150)),
        AccentChoice("Purple", 275f, Color.rgb(104, 63, 145)),
        AccentChoice("Golden yellow", 47f, Color.rgb(125, 91, 18)),
        AccentChoice("Orange", 20f, Color.rgb(164, 75, 32)),
        AccentChoice("Rose", 335f, Color.rgb(152, 55, 100)),
        AccentChoice("Teal", 175f, Color.rgb(18, 104, 103)),
        AccentChoice("Indigo", 250f, Color.rgb(71, 71, 156))
    )
    private const val PRIMARY = "accent_primary"
    private const val GROUP = "accent_group_"

    fun primary(context: Context): Int = choice(context, PRIMARY, 0).primary
    fun primaryHue(context: Context): Float = choice(context, PRIMARY, 0).hue
    /** Accent for icons and outlined controls, lifted in dark mode for reliable contrast. */
    fun control(context: Context): Int {
        val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (dark) Color.HSVToColor(floatArrayOf(primaryHue(context), 0.48f, 0.92f)) else primary(context)
    }
    fun primaryContainer(context: Context): Int {
        val selection = context.getSharedPreferences("map_assist", 0).getInt(PRIMARY, 0)
        if (selection == 0) return ContextCompat.getColor(context, R.color.primary_container)
        val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return Color.HSVToColor(floatArrayOf(primaryHue(context), if (dark) 0.30f else 0.22f, if (dark) 0.31f else 0.94f))
    }
    fun primaryName(context: Context): String = choice(context, PRIMARY, 0).label
    fun setPrimary(context: Context, index: Int) = context.getSharedPreferences("map_assist", 0).edit().putInt(PRIMARY, index).apply()

    fun groupHue(context: Context, prefix: String, automaticIndex: Int): Float {
        val selected = context.getSharedPreferences("map_assist", 0).getInt(GROUP + prefix, -1)
        return if (selected in choices.indices) choices[selected].hue
        else if (prefix.isEmpty()) 164f
        else if (prefix == MapGroupAccent.BUSINESS_GROUP) 20f
        else MapGroupAccent.automaticHue(automaticIndex)
    }
    fun groupName(context: Context, prefix: String, automaticIndex: Int): String {
        val selected = context.getSharedPreferences("map_assist", 0).getInt(GROUP + prefix, -1)
        return if (selected in choices.indices) choices[selected].label else if (prefix.isEmpty()) "Green" else "Automatic"
    }
    fun setGroup(context: Context, prefix: String, index: Int?) = context.getSharedPreferences("map_assist", 0).edit()
        .putInt(GROUP + prefix, index ?: -1).apply()

    private fun choice(context: Context, key: String, fallback: Int): AccentChoice {
        val index = context.getSharedPreferences("map_assist", 0).getInt(key, fallback)
        return choices.getOrElse(index) { choices[fallback] }
    }
}
