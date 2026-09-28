package com.example.mapasssist

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.appcompat.widget.SearchView
import com.example.mapasssist.data.TextFile
import com.example.mapasssist.data.TextFileViewModel
import com.example.mapasssist.databinding.FragmentMapsBinding
import com.example.mapasssist.databinding.MapGridItemBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapsFragment : Fragment() {
    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!
    private val dncViewModel: TextFileViewModel by viewModels()
    private lateinit var adapter: MapSlotAdapter
    private var dncCards = emptyList<TextFile>()
    private var savedMaps = emptyList<SavedMap>()
    private var sortNumerically = false
    private var searchQuery = ""
    private val thumbnailCache = mutableMapOf<Uri, Bitmap?>()

    private val imagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { loadMaps() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sortNumerically = requireContext()
            .getSharedPreferences("map_assist", 0)
            .getBoolean("maps_sort_numerically", false)
        adapter = MapSlotAdapter(
            { slot -> openMap(slot) },
            { slot -> shareMap(slot) },
            { slot -> shareMapAndDnc(slot) },
            { uri, imageView -> loadThumbnail(uri, imageView) }
        )
        binding.mapsRecyclerview.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.mapsRecyclerview.adapter = adapter
        configureToolbarSearch()
        // Allow the same far-right native scrollbar as the other tabs to draw
        // through the pull-to-refresh container.
        binding.mapsRecyclerview.isVerticalScrollBarEnabled = true
        binding.mapsRecyclerview.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        binding.mapsRecyclerview.isScrollbarFadingEnabled = true
        binding.mapsFilterButton.setOnClickListener { showSortMenu(it) }
        binding.mapsHelpButton.setOnClickListener { showMapHelp() }
        binding.mapsRefresh.setOnRefreshListener { loadMaps() }
        dncViewModel.allTextFiles.observe(viewLifecycleOwner) {
            dncCards = it
            submitSlots()
        }
        // Maps are added directly through the device Files app. The optional image
        // permission lets the gallery discover those files in Downloads.
        loadMaps()
        if (!hasImagePermission()) imagePermission.launch(requiredImagePermission())
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) loadMaps()
    }

    private fun hasImagePermission() = ContextCompat.checkSelfPermission(requireContext(), requiredImagePermission()) == PackageManager.PERMISSION_GRANTED
    private fun requiredImagePermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun loadMaps() {
        viewLifecycleOwner.lifecycleScope.launch {
            savedMaps = withContext(Dispatchers.IO) { runCatching { readSavedMaps() }.getOrDefault(emptyList()) }
            if (_binding != null) {
                submitSlots()
                binding.mapsRefresh.isRefreshing = false
            }
        }
    }

    private fun loadThumbnail(uri: Uri, imageView: ImageView) {
        imageView.tag = uri
        if (thumbnailCache.containsKey(uri)) {
            imageView.setImageBitmap(thumbnailCache[uri])
            return
        }
        imageView.setImageDrawable(null)
        viewLifecycleOwner.lifecycleScope.launch {
            val thumbnail = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requireContext().contentResolver.loadThumbnail(uri, Size(480, 360), null)
                    } else {
                        requireContext().contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                    }
                }.getOrNull()
            }
            thumbnailCache[uri] = thumbnail
            if (_binding != null && imageView.tag == uri) imageView.setImageBitmap(thumbnail)
        }
    }

    private fun submitSlots(afterSubmit: (() -> Unit)? = null) {
        if (_binding == null || !::adapter.isInitialized) return
        val mapsByNumber = savedMaps.mapNotNull { map ->
            firstNumber(map.baseName)?.let { number -> number to map }
        }.toMap()
        val slots = dncCards.map { card ->
            MapSlot(card, card.title, firstNumber(card.mapNo)?.let(mapsByNumber::get)?.uri)
        }
        val ordered = if (sortNumerically) {
            slots.sortedWith(
                compareBy<MapSlot> { firstNumber(it.card.mapNo) ?: Int.MAX_VALUE }
                    .thenBy { it.label.lowercase() }
            )
        } else {
            slots.sortedBy { it.label.lowercase() }
        }
        adapter.submit(ordered.filter { slot ->
            searchQuery.isBlank() || "${slot.card.mapNo} ${slot.label}".contains(searchQuery, ignoreCase = true)
        }, afterSubmit)
        binding.mapsEmptyView.visibility = if (dncCards.isNotEmpty()) View.GONE else View.VISIBLE
    }

    private fun showSortMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, "Alphabetically")
            menu.add(0, 2, 1, "Numerically")
            setOnMenuItemClickListener {
                sortNumerically = it.itemId == 2
                requireContext().getSharedPreferences("map_assist", 0)
                    .edit()
                    .putBoolean("maps_sort_numerically", sortNumerically)
                    .apply()
                submitSlots { binding.mapsRecyclerview.scrollToPosition(0) }
                true
            }
            show()
        }
    }

    private fun configureToolbarSearch() {
        val toolbar = requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.post {
            if (_binding == null) return@post
            toolbar.menu.clear()
            val searchView = SearchView(requireContext()).apply {
                queryHint = "Search maps"
                setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String) = true
                    override fun onQueryTextChange(query: String): Boolean { searchQuery = query; submitSlots(); return true }
                })
            }
            toolbar.menu.add("Search").apply {
                setIcon(R.drawable.ic_search)
                actionView = searchView
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS or android.view.MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
                setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
                    override fun onMenuItemActionExpand(item: android.view.MenuItem): Boolean { toolbar.title = ""; return true }
                    override fun onMenuItemActionCollapse(item: android.view.MenuItem): Boolean { searchQuery = ""; submitSlots(); toolbar.title = getString(R.string.app_name); return true }
                })
            }
        }
    }

    private fun showMapHelp() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Adding territory maps")
            .setMessage(
                "Add image files using the device Files app. Place them in:\n\n" +
                    "Downloads/Map Assist/Maps\n\n" +
                    "Name each image with its map number only, for example:\n" +
                    "30.png\n\n" +
                    "The app matches that number to the corresponding DNC card. Return to Map Assist and pull down to refresh the gallery."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun openMap(slot: MapSlot) {
        val uri = slot.uri ?: return
        findNavController().navigate(R.id.action_MapsFragment_to_MapViewerFragment, Bundle().apply {
            putString("imageUri", uri.toString())
            putString("imageName", "${slot.card.mapNo} ${slot.label}".trim())
        })
    }

    private fun shareMap(slot: MapSlot) {
        val uri = slot.uri ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(requireContext().contentResolver, slot.label, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share map"))
    }

    private fun shareMapAndDnc(slot: MapSlot) {
        val uri = slot.uri ?: return
        DncImageExporter.shareWithMap(requireContext(), slot.card, uri)
    }

    private fun readSavedMaps(): List<SavedMap> {
        val resolver = requireContext().contentResolver
        // Files apps do not all index an image in Downloads the same way. Check both
        // Downloads and the image library, while still restricting results to Maps.
        val collections = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        } else {
            listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        return collections.flatMap { collection ->
            runCatching { readMapsFromCollection(resolver, collection) }.getOrDefault(emptyList())
        }
            .distinctBy { it.uri.toString() }
    }

    private fun readMapsFromCollection(
        resolver: android.content.ContentResolver,
        collection: Uri
    ): List<SavedMap> {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }
        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf("Download/Map Assist/Maps/") else arrayOf("%/Download/Map Assist/Maps/%")
        return resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            buildList {
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(name).orEmpty()
                    add(SavedMap(Uri.withAppendedPath(collection, cursor.getLong(id).toString()), displayName.substringBeforeLast('.', displayName)))
                }
            }
        }.orEmpty()
    }

    private fun firstNumber(value: String) = Regex("\\d+").find(value)?.value?.toIntOrNull()

    override fun onDestroyView() {
        requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu.clear()
        super.onDestroyView()
        _binding = null
    }
}

private data class SavedMap(val uri: Uri, val baseName: String)
private data class MapSlot(val card: TextFile, val label: String, val uri: Uri?)

private class MapSlotAdapter(
    private val selected: (MapSlot) -> Unit,
    private val shared: (MapSlot) -> Unit,
    private val sharedWithDnc: (MapSlot) -> Unit,
    private val thumbnailLoader: (Uri, ImageView) -> Unit
) : ListAdapter<MapSlot, MapSlotAdapter.Holder>(Diff()) {
    fun submit(slots: List<MapSlot>, afterSubmit: (() -> Unit)? = null) = submitList(slots, afterSubmit)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(MapGridItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: MapGridItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(slot: MapSlot) {
            binding.mapName.text = slot.label
            binding.mapNumber.text = slot.card.mapNo
            if (slot.uri != null) thumbnailLoader(slot.uri, binding.mapThumbnail) else binding.mapThumbnail.setImageDrawable(null)
            binding.mapPlaceholder.visibility = if (slot.uri == null) View.VISIBLE else View.GONE
            binding.shareMapButton.visibility = if (slot.uri != null) View.VISIBLE else View.GONE
            binding.shareMapDncButton.visibility = if (slot.uri != null) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { selected(slot) }
            binding.shareMapButton.setOnClickListener { shared(slot) }
            binding.shareMapDncButton.setOnClickListener { sharedWithDnc(slot) }
        }
    }

    private class Diff : DiffUtil.ItemCallback<MapSlot>() {
        override fun areItemsTheSame(oldItem: MapSlot, newItem: MapSlot) = oldItem.card.id == newItem.card.id
        override fun areContentsTheSame(oldItem: MapSlot, newItem: MapSlot) = oldItem == newItem
    }
}
