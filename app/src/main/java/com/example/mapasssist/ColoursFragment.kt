package com.example.mapasssist

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.mapasssist.data.TextFileViewModel
import com.example.mapasssist.databinding.FragmentColoursBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels

class ColoursFragment : Fragment() {
    private var _binding: FragmentColoursBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TextFileViewModel by viewModels()
    private var dncMapNumbers = emptyList<String>()
    private var mapNumbers = emptyList<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentColoursBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.primaryColourButton.setOnClickListener { choosePrimary() }
        viewModel.allTextFiles.observe(viewLifecycleOwner) { files ->
            dncMapNumbers = files.map { it.mapNo }
            updateMapNumbers()
            render()
        }
        render()
    }

    private fun render() {
        if (_binding == null) return
        val primary = AccentPreferences.primary(requireContext())
        binding.primaryColourButton.text = "App accent: ${AccentPreferences.primaryName(requireContext())}"
        binding.primaryColourButton.backgroundTintList = ColorStateList.valueOf(primary)
        binding.primaryColourButton.setTextColor(android.graphics.Color.WHITE)
        binding.groupColourContainer.removeAllViews()
        val groups = MapGroupAccent.groupIndices(mapNumbers.filterNot(MapGroupAccent::isBusinessMap))
        addGroupButton("No letter group", "", 0)
        groups.entries.sortedBy { it.key }.forEach { (prefix, index) ->
            addGroupButton("$prefix group", prefix, index)
        }
        if (mapNumbers.any(MapGroupAccent::isBusinessMap)) {
            addGroupButton("Business Suffix Group", MapGroupAccent.BUSINESS_GROUP, 0)
        }
    }

    private fun updateMapNumbers() {
        mapNumbers = dncMapNumbers + LedgerStore.read(requireContext())
            .map { it.mapNo }
            .filter(MapGroupAccent::isBusinessMap)
    }

    private fun addGroupButton(label: String, prefix: String, index: Int) {
        val button = MaterialButton(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
                text = "$label: ${AccentPreferences.groupName(requireContext(), prefix, index)}"
                val hue = AccentPreferences.groupHue(requireContext(), prefix, index)
                backgroundTintList = ColorStateList.valueOf(mutedFill(hue))
                setTextColor(darkText(hue))
                setOnClickListener { chooseGroup(prefix, index) }
            }
        binding.groupColourContainer.addView(button)
    }

    private fun choosePrimary() {
        val names = AccentPreferences.choices.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Choose app accent")
            .setSingleChoiceItems(names, AccentPreferences.choices.indexOfFirst { it.label == AccentPreferences.primaryName(requireContext()) }) { dialog, which ->
                AccentPreferences.setPrimary(requireContext(), which)
                dialog.dismiss()
                requireActivity().recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseGroup(prefix: String, index: Int) {
        val labels = arrayOf("Automatic") + AccentPreferences.choices.map { it.label }.toTypedArray()
        val saved = requireContext().getSharedPreferences("map_assist", 0).getInt("accent_group_$prefix", -1)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(when (prefix) {
                "" -> "No letter group colour"
                MapGroupAccent.BUSINESS_GROUP -> "Business Suffix Group colour"
                else -> "$prefix group colour"
            })
            .setSingleChoiceItems(labels, if (saved in AccentPreferences.choices.indices) saved + 1 else 0) { dialog, which ->
                AccentPreferences.setGroup(requireContext(), prefix, if (which == 0) null else which - 1)
                dialog.dismiss()
                requireActivity().recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun mutedFill(hue: Float) = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.22f, 0.94f))
    private fun darkText(hue: Float) = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.40f))
    override fun onResume() { super.onResume(); updateMapNumbers(); render() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
