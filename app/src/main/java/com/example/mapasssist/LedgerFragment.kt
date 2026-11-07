package com.example.mapasssist

import android.os.Bundle
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.navigation.fragment.findNavController
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mapasssist.data.TextFile
import com.example.mapasssist.data.TextFileViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LedgerFragment : Fragment() {
    private val viewModel: TextFileViewModel by viewModels()
    private var root: View? = null
    private var dncCards = emptyList<TextFile>()
    private var cards = emptyList<TextFile>()
    private var expanded = emptySet<String>()
    private var sortMode = 0
    private var searchQuery = ""
    private var hiddenGroups = emptySet<String>()
    private var groupFilterSignature: String? = null
    private var ledgerAdapter: LedgerAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ledger, container, false).also { root = it }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sortMode = requireContext().getSharedPreferences("map_assist", 0).getInt("ledger_sort_mode", 0)
        hiddenGroups = requireContext().getSharedPreferences("map_assist", 0)
            .getStringSet("ledger_hidden_groups", emptySet())
            ?.toSet()
            .orEmpty()
        val adapter = LedgerAdapter(
            { card -> toggle(card.mapNo) },
            { card -> showAssignmentDialog(card) },
            { record -> showEditDialog(record) },
            { card, record -> showCompleteDialog(card, record) }
        )
        ledgerAdapter = adapter
        view.findViewById<RecyclerView>(R.id.ledger_recycler).layoutManager = LinearLayoutManager(requireContext())
        view.findViewById<RecyclerView>(R.id.ledger_recycler).adapter = adapter
        view.findViewById<MaterialButton>(R.id.ledger_sort_button).apply {
            iconTint = ColorStateList.valueOf(AccentPreferences.control(requireContext()))
            setOnClickListener { showSortMenu(this) }
        }
        viewModel.allTextFiles.observe(viewLifecycleOwner) { files ->
            dncCards = files
            render(adapter)
        }
        configureToolbarSearch()
    }

    private fun render(adapter: LedgerAdapter) {
        val assignments = LedgerStore.read(requireContext())
        cards = cardsForLedger(assignments)
        // Keep the colour index based on every card, never on the filtered list.
        // This matches the Maps and DNC tabs and prevents hidden groups reshuffling hues.
        adapter.setGroupMapNumbers(cards.map { it.mapNo }.filterNot(MapGroupAccent::isBusinessMap))
        createGroupFilters()
        val activityByMap = assignments.groupBy { it.mapNo }
            .mapValues { (_, records) -> lastLedgerActivityTime(records) }
        val activityTimes = cards.associate { it.mapNo to (activityByMap[it.mapNo] ?: Long.MIN_VALUE) }
        val sorted = when (sortMode) {
            1 -> cards.sortedByDescending { activityTimes[it.mapNo] ?: Long.MIN_VALUE }
            2 -> cards.sortedWith(compareBy<TextFile> { if ((activityTimes[it.mapNo] ?: Long.MIN_VALUE) == Long.MIN_VALUE) 1 else 0 }
                .thenBy { activityTimes[it.mapNo] ?: Long.MIN_VALUE })
            else -> cards.sortedWith { first, second -> MapNumber.compare(first.mapNo, second.mapNo) }
        }
        adapter.submit(sorted.filter { card ->
            (searchQuery.isBlank() || "${card.mapNo} ${card.title}".contains(searchQuery, ignoreCase = true)) &&
                MapGroupAccent.groupKey(card.mapNo) !in hiddenGroups
        }, assignments, expanded)
        root?.findViewById<View>(R.id.ledger_empty)?.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun cardsForLedger(assignments: List<LedgerAssignment>): List<TextFile> {
        val knownNumbers = dncCards.map { MapNumber.identifier(it.mapNo) }.toSet()
        val ledgerOnly = assignments
            .filter { MapGroupAccent.isBusinessMap(it.mapNo) }
            .groupBy { MapNumber.identifier(it.mapNo) }
            .filterKeys { it !in knownNumbers }
            .values
            .map { records ->
                val sample = records.first()
                TextFile(title = sample.cardTitle, mapNo = sample.mapNo, lastModified = 0L)
            }
        return dncCards + ledgerOnly
    }

    private fun createGroupFilters() {
        val container = root?.findViewById<LinearLayout>(R.id.ledger_group_container) ?: return
        val standardNumbers = cards.map { it.mapNo }.filterNot(MapGroupAccent::isBusinessMap)
        val groupIndices = MapGroupAccent.groupIndices(standardNumbers)
        val groups = buildList {
            if (cards.any { MapGroupAccent.prefixOf(it.mapNo) == null }) add("" to 0)
            addAll(groupIndices.entries.sortedBy { it.key }.map { it.key to it.value })
            if (cards.any { MapGroupAccent.isBusinessMap(it.mapNo) }) add(MapGroupAccent.BUSINESS_GROUP to 0)
        }
        val signature = groups.joinToString("|") { it.first } + "#" + hiddenGroups.sorted().joinToString("|")
        if (groupFilterSignature == signature) return
        groupFilterSignature = signature
        container.removeAllViews()
        groups.forEach { (prefix, _) ->
            val mapNumber = when (prefix) {
                "" -> "42"
                MapGroupAccent.BUSINESS_GROUP -> "1B"
                else -> "${prefix}1"
            }
            val (background, foreground) = MapGroupAccent.colorsFor(requireContext(), mapNumber, groupIndices)
            val isHidden = prefix in hiddenGroups
            val button = MaterialButton(requireContext()).apply {
                text = when (prefix) { "" -> "42"; MapGroupAccent.BUSINESS_GROUP -> "B"; else -> prefix }
                contentDescription = "Show or hide ${when (prefix) { "" -> "numbered"; MapGroupAccent.BUSINESS_GROUP -> "B-suffix"; else -> prefix }} maps"
                isAllCaps = false
                minWidth = dp(42)
                minimumWidth = dp(42)
                minHeight = dp(36)
                minimumHeight = dp(36)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(12)
                setPadding(dp(10), 0, dp(10), 0)
                // Keep the muted version of this group's own colour when it
                // is hidden; rebuilding after each tap prevents a stale tint.
                backgroundTintList = ColorStateList.valueOf(background)
                setTextColor(foreground)
                alpha = if (isHidden) 0.42f else 1f
                setOnClickListener {
                    hiddenGroups = if (prefix in hiddenGroups) hiddenGroups - prefix else hiddenGroups + prefix
                    requireContext().getSharedPreferences("map_assist", 0).edit()
                        .putStringSet("ledger_hidden_groups", hiddenGroups)
                        .apply()
                    groupFilterSignature = null
                    ledgerAdapter?.let(::render)
                }
            }
            container.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginEnd = dp(6)
            })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showSortMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 0, 0, "Numerically").isChecked = sortMode == 0
            menu.add(0, 1, 1, "Last assigned: newest").isChecked = sortMode == 1
            menu.add(0, 2, 2, "Last assigned: oldest").isChecked = sortMode == 2
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener {
                sortMode = it.itemId
                requireContext().getSharedPreferences("map_assist", 0).edit().putInt("ledger_sort_mode", sortMode).apply()
                (root?.findViewById<RecyclerView>(R.id.ledger_recycler)?.adapter as? LedgerAdapter)?.let(::render)
                root?.findViewById<RecyclerView>(R.id.ledger_recycler)?.scrollToPosition(0)
                true
            }
            show()
        }
    }

    private fun configureToolbarSearch() {
        val toolbar = requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        if (root == null || findNavController().currentDestination?.id != R.id.LedgerFragment) return
        toolbar.menu.clear()
        val search = SearchView(requireContext()).apply {
            queryHint = "Search ledger"
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = true
                override fun onQueryTextChange(query: String): Boolean { searchQuery = query; ledgerAdapter?.let(::render); return true }
            })
        }
        toolbar.menu.add("Search").apply {
            setIcon(R.drawable.ic_search)
            actionView = search
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS or android.view.MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: android.view.MenuItem): Boolean { toolbar.title = ""; return true }
                override fun onMenuItemActionCollapse(item: android.view.MenuItem): Boolean { searchQuery = ""; ledgerAdapter?.let(::render); toolbar.title = getString(R.string.app_name); return true }
            })
        }
        toolbar.menu.add("Manage business territories").apply {
            setIcon(R.drawable.ic_more)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.title == "Manage business territories") {
                showBusinessTerritoriesDialog()
                true
            } else false
        }
    }

    private fun showBusinessTerritoriesDialog() {
        val list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(list) }
        val businessCards = cards.filter { MapGroupAccent.isBusinessMap(it.mapNo) }
            .sortedWith { first, second -> MapNumber.compare(first.mapNo, second.mapNo) }
        if (businessCards.isEmpty()) {
            list.addView(TextView(requireContext()).apply {
                text = "No business territories yet."
                setPadding(dp(24), dp(12), dp(24), dp(12))
            })
        } else {
            businessCards.forEach { territory ->
                list.addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                    val selectable = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                    setBackgroundResource(selectable.resourceId)
                    addView(TextView(context).apply {
                        text = territory.mapNo
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    })
                    addView(TextView(context).apply {
                        text = territory.title
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    })
                    setOnClickListener { showEditBusinessTerritoryDialog(territory) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Business territories")
            .setMessage("Tap a territory to edit its name or remove it.")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setPositiveButton("Add territory") { _, _ -> showAddBusinessTerritoryDialog() }
            .show()
    }

    private fun showAddBusinessTerritoryDialog() {
        val number = EditText(requireContext()).apply {
            hint = "Map number ending in B"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val title = EditText(requireContext()).apply { hint = "Territory name" }
        val form = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), 0, dp(48), 0)
            addView(number)
            addView(title)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add business territory")
            .setMessage("Business territories should end in B. For example, L1B. Business territories are kept in the ledger without creating a DNC card.")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val mapNo = MapNumber.identifier(number.text.toString())
                val name = title.text.toString().trim()
                if (!MapGroupAccent.isBusinessMap(mapNo) || name.isBlank()) {
                    android.widget.Toast.makeText(requireContext(), "Enter a territory name and a map number ending in B", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val exists = cards.any { MapNumber.identifier(it.mapNo) == mapNo }
                if (exists) {
                    android.widget.Toast.makeText(requireContext(), "This business territory already exists", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                LedgerStore.replace(requireContext(), LedgerStore.read(requireContext()) + LedgerAssignment(
                    mapNo = mapNo,
                    cardTitle = name,
                    assignedTo = "",
                    assignedOn = ""
                ))
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showEditBusinessTerritoryDialog(territory: TextFile) {
        val number = EditText(requireContext()).apply {
            hint = "Map number ending in B"
            setText(territory.mapNo)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val title = EditText(requireContext()).apply { hint = "Territory name"; setText(territory.title) }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit ${territory.mapNo}")
            .setView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(48), 0, dp(48), 0)
                addView(number)
                addView(title)
            })
            .setNegativeButton("Remove") { _, _ -> confirmRemoveBusinessTerritory(territory) }
            .setNeutralButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val mapNo = MapNumber.identifier(number.text.toString())
                val name = title.text.toString().trim()
                if (!MapGroupAccent.isBusinessMap(mapNo) || name.isBlank()) {
                    android.widget.Toast.makeText(requireContext(), "Enter a territory name and a map number ending in B", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val duplicate = cards.any {
                    MapNumber.identifier(it.mapNo) == mapNo && MapNumber.identifier(it.mapNo) != MapNumber.identifier(territory.mapNo)
                }
                if (duplicate) {
                    android.widget.Toast.makeText(requireContext(), "This business territory already exists", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                LedgerStore.replace(requireContext(), LedgerStore.read(requireContext()).map { record ->
                    if (MapNumber.identifier(record.mapNo) == MapNumber.identifier(territory.mapNo)) record.copy(mapNo = mapNo, cardTitle = name) else record
                })
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmRemoveBusinessTerritory(territory: TextFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove ${territory.mapNo}?")
            .setMessage("This will permanently remove the business territory and all of its assignment history.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                LedgerStore.replace(requireContext(), LedgerStore.read(requireContext()).filterNot {
                    MapNumber.identifier(it.mapNo) == MapNumber.identifier(territory.mapNo)
                })
                refresh()
            }
            .show()
    }

    private fun toggle(mapNo: String) {
        expanded = if (mapNo in expanded) expanded - mapNo else expanded + mapNo
        (root?.findViewById<RecyclerView>(R.id.ledger_recycler)?.adapter as? LedgerAdapter)?.let(::render)
    }

    private fun showAssignmentDialog(card: TextFile) {
        val person = EditText(requireContext()).apply { hint = "Assigned to" }
        val date = EditText(requireContext()).apply {
            hint = "Assigned on (DD/MM/YYYY)"
            setText(today())
            inputType = android.text.InputType.TYPE_CLASS_DATETIME
            setOnClickListener {
                val calendar = java.util.Calendar.getInstance()
                android.app.DatePickerDialog(requireContext(), { _, year, month, day ->
                    setText("%02d/%02d/%04d".format(day, month + 1, year))
                }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }
        }
        val notes = EditText(requireContext()).apply { hint = "Notes (optional)" }
        val form = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 0)
            addView(person); addView(date); addView(notes)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Assign ${card.mapNo} – ${card.title}")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save assignment") { _, _ ->
                if (person.text.isNullOrBlank()) {
                    android.widget.Toast.makeText(requireContext(), "Enter who the map is assigned to", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val history = LedgerStore.read(requireContext()).toMutableList()
                LedgerStore.replace(requireContext(), history + LedgerAssignment(
                    card.mapNo, card.title, person.text.toString().trim(), date.text.toString().trim(), notes = notes.text.toString().trim()
                ))
                render(root?.findViewById<RecyclerView>(R.id.ledger_recycler)?.adapter as LedgerAdapter)
            }
            .show()
    }

    private fun showCompleteDialog(card: TextFile, active: LedgerAssignment) {
        val holder = TextView(requireContext()).apply { text = "Assigned to: ${active.assignedTo}"; setPadding(0, 0, 0, 14) }
        val date = dateField("Completed on (DD/MM/YYYY)", today())
        val notes = EditText(requireContext()).apply { hint = "Completion notes (optional)"; setText(active.notes) }
        val form = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(holder); addView(date); addView(notes) }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Complete ${card.mapNo} – ${card.title}").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Complete") { _, _ ->
                replaceRecord(active, active.copy(returnedOn = date.text.toString().trim(), notes = notes.text.toString().trim()))
            }.show()
    }

    private fun showEditDialog(record: LedgerAssignment) {
        val person = EditText(requireContext()).apply { hint = "Assigned to"; setText(record.assignedTo) }
        val assigned = dateField("Assigned on (DD/MM/YYYY)", record.assignedOn)
        val returned = dateField("Completed on (DD/MM/YYYY)", record.returnedOn)
        val notes = EditText(requireContext()).apply { hint = "Notes (optional)"; setText(record.notes) }
        val form = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(person); addView(assigned); addView(returned); addView(notes) }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Edit assignment").setView(form)
            .setNegativeButton("Delete") { _, _ -> confirmDelete(record) }
            .setNeutralButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                if (person.text.isNullOrBlank()) return@setPositiveButton
                replaceRecord(record, record.copy(assignedTo = person.text.toString().trim(), assignedOn = assigned.text.toString().trim(), returnedOn = returned.text.toString().trim(), notes = notes.text.toString().trim()))
            }.show()
    }

    private fun confirmDelete(record: LedgerAssignment) {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Delete assignment history?")
            .setMessage("This assignment record will be permanently deleted.").setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val values = LedgerStore.read(requireContext()).toMutableList(); values.remove(record); LedgerStore.replace(requireContext(), values); refresh()
            }.show()
    }

    private fun replaceRecord(old: LedgerAssignment, updated: LedgerAssignment) {
        LedgerStore.replace(requireContext(), LedgerStore.read(requireContext()).map { if (it == old) updated else it }); refresh()
    }
    private fun refresh() = (root?.findViewById<RecyclerView>(R.id.ledger_recycler)?.adapter as? LedgerAdapter)?.let(::render)
    private fun dateField(hint: String, value: String) = EditText(requireContext()).apply {
        this.hint = hint; setText(value); inputType = android.text.InputType.TYPE_CLASS_DATETIME
        setOnClickListener { val c = java.util.Calendar.getInstance(); android.app.DatePickerDialog(requireContext(), { _, y, m, d -> setText("%02d/%02d/%04d".format(d, m + 1, y)) }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show() }
    }
    private fun lastLedgerActivityTime(mapAssignments: List<LedgerAssignment>): Long {
        // A territory still out has no completion date. Its issue date is its latest
        // relevant Ledger event, so it naturally appears first in the newest view.
        val issuedWhileOut = mapAssignments.filter { it.active }
            .map { parseDate(it.assignedOn) }
            .maxOrNull()
        if (issuedWhileOut != null) return issuedWhileOut
        return mapAssignments.asSequence()
            .filter { it.returnedOn.isNotBlank() }
            .map { parseDate(it.returnedOn) }
            .maxOrNull() ?: Long.MIN_VALUE
    }
    private fun parseDate(value: String) = runCatching { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(value)?.time ?: Long.MIN_VALUE }.getOrDefault(Long.MIN_VALUE)

    private fun today() = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
    override fun onResume() { super.onResume(); root?.post { configureToolbarSearch() } }
    override fun onDestroyView() { root = null; ledgerAdapter = null; super.onDestroyView() }
}

private class LedgerAdapter(
    private val toggled: (TextFile) -> Unit,
    private val assigned: (TextFile) -> Unit,
    private val edited: (LedgerAssignment) -> Unit,
    private val completed: (TextFile, LedgerAssignment) -> Unit
) : RecyclerView.Adapter<LedgerAdapter.Holder>() {
    private var cards = emptyList<TextFile>()
    private var assignmentsByMap = emptyMap<String, List<LedgerAssignment>>()
    private var expanded = emptySet<String>()
    private var groupIndices = emptyMap<String, Int>()
    private val pendingExpansionAnimations = mutableSetOf<String>()

    fun setGroupMapNumbers(mapNumbers: Iterable<String>) {
        groupIndices = MapGroupAccent.groupIndices(mapNumbers)
    }

    fun submit(cards: List<TextFile>, assignments: List<LedgerAssignment>, expanded: Set<String>) {
        val groupedAssignments = assignments.groupBy { it.mapNo }
        val expansionOnlyChange = this.cards == cards && this.assignmentsByMap == groupedAssignments
        val changedMaps = (this.expanded - expanded) + (expanded - this.expanded)
        pendingExpansionAnimations += changedMaps
        this.cards = cards
        this.assignmentsByMap = groupedAssignments
        this.expanded = expanded
        if (expansionOnlyChange) {
            changedMaps.forEach { mapNo ->
                cards.indexOfFirst { it.mapNo == mapNo }.takeIf { it >= 0 }?.let(::notifyItemChanged)
            }
        } else {
            notifyDataSetChanged()
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.ledger_item, parent, false))
    override fun getItemCount() = cards.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = cards[position]
        holder.bind(
            card,
            assignmentsByMap[card.mapNo].orEmpty(),
            card.mapNo in expanded,
            pendingExpansionAnimations.remove(card.mapNo)
        )
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val number = view.findViewById<TextView>(R.id.ledger_map_number)
        private val title = view.findViewById<TextView>(R.id.ledger_map_title)
        private val current = view.findViewById<TextView>(R.id.ledger_current)
        private val assign = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.ledger_assign)
        private val history = view.findViewById<LinearLayout>(R.id.ledger_history)
        fun bind(card: TextFile, records: List<LedgerAssignment>, isExpanded: Boolean, animateExpansion: Boolean) {
            number.text = card.mapNo; MapGroupAccent.applyTo(number, card.mapNo, groupIndices)
            title.text = card.title
            val active = records.lastOrNull { it.active }
            current.text = active?.let { "${it.assignedTo} · ${it.assignedOn}" } ?: unassignedText(records)
            current.setTextColor(ContextCompat.getColor(itemView.context, if (active == null) R.color.primary else R.color.destructive))
            assign.text = ""
            assign.setIconResource(if (active == null) R.drawable.ic_ledger_add_light else R.drawable.ic_ledger_check_light)
            assign.backgroundTintList = ColorStateList.valueOf(
                if (active == null) ContextCompat.getColor(itemView.context, R.color.primary)
                else ContextCompat.getColor(itemView.context, R.color.destructive)
            )
            assign.iconTint = ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.on_primary))
            assign.contentDescription = if (active == null) "Assign map" else "Complete map assignment"
            assign.setTextColor(ContextCompat.getColor(itemView.context, R.color.on_primary))
            assign.setOnClickListener { if (active == null) assigned(card) else completed(card, active) }
            itemView.setOnClickListener { toggled(card) }
            history.removeAllViews()
            val historyRecords = records.filter { it.assignedTo.isNotBlank() || it.assignedOn.isNotBlank() || it.returnedOn.isNotBlank() || it.notes.isNotBlank() }
            historyRecords.asReversed().forEachIndexed { index, record ->
                history.addView(LinearLayout(itemView.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(56, 5, 0, 5)
                    if (index % 2 == 0) setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.ledger_history_row))
                    addView(TextView(itemView.context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        text = buildString {
                        append(record.assignedTo).append(" · ").append(record.assignedOn)
                        if (record.returnedOn.isNotBlank()) append(" – ").append(record.returnedOn)
                        if (record.notes.isNotBlank()) append("\n").append(record.notes)
                        }
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        setOnLongClickListener { edited(record); true }
                    })
                    contentDescription = "Assignment history. Hold to edit."
                    setOnLongClickListener { edited(record); true }
                })
            }
            if (historyRecords.isEmpty()) history.addView(TextView(itemView.context).apply { text = "No assignment history"; setPadding(56, 6, 0, 6) })
            val shouldAnimate = animateExpansion && itemView.width > 0
            if (shouldAnimate) animateHistory(isExpanded) else {
                history.visibility = if (isExpanded) View.VISIBLE else View.GONE
                history.alpha = 1f
                history.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        private fun animateHistory(expand: Boolean) {
            history.animate().cancel()
            val params = history.layoutParams
            if (expand) {
                history.visibility = View.VISIBLE
                history.alpha = 0f
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                history.measure(
                    View.MeasureSpec.makeMeasureSpec(itemView.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val targetHeight = history.measuredHeight
                params.height = 0
                history.layoutParams = params
                ValueAnimator.ofInt(0, targetHeight).apply {
                    duration = 180
                    interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                    addUpdateListener {
                        params.height = it.animatedValue as Int
                        history.layoutParams = params
                        history.alpha = it.animatedFraction
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            history.layoutParams = params
                            history.alpha = 1f
                        }
                    })
                    start()
                }
            } else {
                val startHeight = history.height
                ValueAnimator.ofInt(startHeight, 0).apply {
                    duration = 160
                    interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                    addUpdateListener {
                        params.height = it.animatedValue as Int
                        history.layoutParams = params
                        history.alpha = 1f - it.animatedFraction
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            history.layoutParams = params
                            history.visibility = View.GONE
                            history.alpha = 1f
                        }
                    })
                    start()
                }
            }
        }

        private fun unassignedText(records: List<LedgerAssignment>): String {
            val last = records.mapNotNull { assignment ->
                assignment.returnedOn.takeIf { it.isNotBlank() }?.let(::parseDate)
            }.maxOrNull() ?: return "Not assigned"
            val elapsed = (System.currentTimeMillis() - last).coerceAtLeast(0L)
            val days = elapsed / 86_400_000L
            val time = when {
                days >= 365 -> "${days / 365}y ago"
                days >= 30 -> "${days / 30}mo ago"
                days >= 7 -> "${days / 7}w ago"
                days > 0 -> "${days}d ago"
                else -> "today"
            }
            return "Not assigned – $time"
        }

        private fun parseDate(value: String) = runCatching {
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(value)?.time ?: Long.MIN_VALUE
        }.getOrDefault(Long.MIN_VALUE)
    }
}
