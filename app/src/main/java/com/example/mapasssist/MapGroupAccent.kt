package com.example.mapasssist

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale

/** Gives the letter-prefixed map series currently in use distinct, muted badge colours. */
object MapGroupAccent {
    /** Ledger-only territories such as L1B use their own independently configurable colour. */
    const val BUSINESS_GROUP = "__ledger_business__"
    // The first current letter group is blue, the next purple, then yellow, and so on.
    private val groupHues = floatArrayOf(210f, 275f, 47f, 20f, 335f, 175f, 100f, 250f)

    fun automaticHue(index: Int): Float = groupHues[index % groupHues.size]

    fun groupIndices(mapNumbers: Iterable<String>): Map<String, Int> = mapNumbers
        .mapNotNull(::prefixOf)
        .distinct()
        .sorted()
        .mapIndexed { index, prefix -> prefix to index }
        .toMap()

    fun applyTo(badge: TextView, mapNumber: String, groups: Map<String, Int>) {
        val (background, foreground) = colorsFor(badge.context, mapNumber, groups)
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * badge.resources.displayMetrics.density
            setColor(background)
        }
        badge.setTextColor(foreground)
    }

    /** Applies the same group fill to the map thumbnail's share-action overlay. */
    fun applyToShareOverlay(overlay: View, mapNumber: String, groups: Map<String, Int>): Int {
        val (background, foreground) = colorsFor(overlay.context, mapNumber, groups)
        val radius = 18f * overlay.resources.displayMetrics.density
        overlay.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, 0f, 0f)
            setColor(background)
        }
        return foreground
    }

    /** Background and foreground used by a card's map-number badge. */
    fun colorsFor(context: Context, mapNumber: String, groups: Map<String, Int>): Pair<Int, Int> {
        if (isBusinessMap(mapNumber)) {
            return mutedColors(context, AccentPreferences.groupHue(context, BUSINESS_GROUP, 0))
        }
        val prefix = prefixOf(mapNumber)
        val groupIndex = prefix?.let(groups::get)
        if (prefix == null) {
            val selected = context.getSharedPreferences("map_assist", 0).getInt("accent_group_", -1)
            if (selected !in AccentPreferences.choices.indices) {
                return ContextCompat.getColor(context, R.color.primary_container) to
                    ContextCompat.getColor(context, R.color.primary)
            }
            val hue = AccentPreferences.groupHue(context, "", 0)
            return mutedColors(context, hue)
        }
        val hue = AccentPreferences.groupHue(context, prefix, groupIndex ?: 0)
        return mutedColors(context, hue)
    }

    private fun mutedColors(context: Context, hue: Float): Pair<Int, Int> {
        val dark = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val backgroundLightness = if (dark) 0.31f else 0.94f
        val foregroundLightness = if (dark) 0.88f else 0.40f
        // Deliberately low-saturation fill mirrors the existing pale green badge.
        return Color.HSVToColor(floatArrayOf(hue, if (dark) 0.30f else 0.22f, backgroundLightness)) to
            Color.HSVToColor(floatArrayOf(hue, if (dark) 0.55f else 0.62f, foregroundLightness))
    }

    fun prefixOf(mapNumber: String): String? = Regex("^\\s*([A-Za-z]+)\\d")
        .find(mapNumber)
        ?.groupValues
        ?.get(1)
        ?.uppercase(Locale.ROOT)

    fun groupKey(mapNumber: String): String = if (isBusinessMap(mapNumber)) BUSINESS_GROUP else prefixOf(mapNumber).orEmpty()

    fun isBusinessMap(mapNumber: String): Boolean = Regex("^\\s*[A-Za-z]*\\d+B\\s*$", RegexOption.IGNORE_CASE)
        .matches(mapNumber)
}
