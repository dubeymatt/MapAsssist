package com.example.mapasssist

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.graphics.BitmapFactory
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.data.DncEntryCodec
import com.example.mapasssist.data.TextFile
import com.example.mapasssist.data.TextFileViewModel
import com.example.mapasssist.databinding.FragmentSettingsBinding
import java.io.OutputStreamWriter
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TextFileViewModel by viewModels()
    private var files = emptyList<TextFile>()
    private var restoreScrollY = 0
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@registerForActivityResult
        requireContext().contentResolver.openOutputStream(uri)?.use { stream -> OutputStreamWriter(stream).use { it.write(BackupCsv.create(files, LedgerStore.read(requireContext()))) } }
        Toast.makeText(requireContext(), "Spreadsheet exported", Toast.LENGTH_SHORT).show()
    }
    private val googleDriveSignIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        runCatching { task.getResult(ApiException::class.java) }
            .onSuccess { updateGoogleAccount(); reconcileGoogleDriveBackup() }
            .onFailure { Toast.makeText(requireContext(), "Google Drive was not connected", Toast.LENGTH_SHORT).show(); updateGoogleAccount() }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val imported = runCatching { readCsv(uri) }.getOrElse { Toast.makeText(requireContext(), "Could not read this spreadsheet. Export a fresh Map Assist spreadsheet and use that as the template.", Toast.LENGTH_LONG).show(); return@registerForActivityResult }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Import will permanently replace all data").setMessage("This will delete all ${files.size} saved DNC cards and replace them with ${imported.files.size} cards from the spreadsheet. This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Replace all data") { _, _ -> viewModel.replaceAll(imported.files); LedgerStore.replace(requireContext(), imported.assignments); Toast.makeText(requireContext(), "DNCs and ledger replaced", Toast.LENGTH_SHORT).show() }.show()
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View { _binding = FragmentSettingsBinding.inflate(inflater, container, false); return binding.root }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val preferences = requireContext().getSharedPreferences("map_assist", 0)
        // A normal app launch opens Settings at the top. The saved-state value
        // is only used for the immediate dark-mode recreation.
        restoreScrollY = savedInstanceState?.getInt("settings_scroll_y") ?: 0
        preferences.edit().remove("settings_scroll_y").apply()
        binding.root.post { binding.root.scrollTo(0, restoreScrollY) }
        binding.darkModeSwitch.isChecked = preferences.getBoolean("dark_mode", false)
        applyAccentControls()
        binding.darkModeSwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.edit().putBoolean("dark_mode", enabled).apply()
            AppCompatDelegate.setDefaultNightMode(if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        binding.coloursButton.setOnClickListener { findNavController().navigate(R.id.action_SettingsFragment_to_ColoursFragment) }
        binding.openDownloadsButton.setOnClickListener {
            val intent = android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { startActivity(intent) }.getOrElse {
                Toast.makeText(requireContext(), "Could not open the Files app", Toast.LENGTH_SHORT).show()
            }
        }
        binding.googleAccountButton.setOnClickListener {
            if (!GoogleDriveBackup.hasConnectedAccount(requireContext())) googleDriveSignIn.launch(GoogleDriveBackup.signInIntent(requireContext()))
        }
        binding.googleAccountHelpButton.setOnClickListener { showGoogleAccountHelp() }
        binding.googleDriveSyncButton.setOnClickListener {
            if (!GoogleDriveBackup.hasConnectedAccount(requireContext())) {
                Toast.makeText(requireContext(), "Sign in to a Google account first", Toast.LENGTH_SHORT).show()
            } else if (GoogleDriveBackup.isSyncConfigured(requireContext())) confirmDriveSync()
            else reconcileGoogleDriveBackup()
        }
        binding.googleDriveDisconnectButton.setOnClickListener { confirmDisconnect() }
        binding.createSharedLibraryButton.setOnClickListener { createSharedLibrary() }
        binding.joinSharedLibraryButton.setOnClickListener { showJoinSharedLibraryDialog() }
        binding.sharedAutoSyncSwitch.setOnCheckedChangeListener { _, enabled ->
            if (GoogleDriveBackup.isSharedLibraryConfigured(requireContext())) {
                GoogleDriveBackup.setSharedAutoSyncEnabled(requireContext(), enabled)
                updateSharedLibraryStatus()
            }
        }
        binding.sharedSyncButton.setOnClickListener { syncSharedLibrary() }
        binding.disconnectSharedLibraryButton.setOnClickListener { confirmLeaveSharedLibrary() }
        viewModel.allTextFiles.observe(viewLifecycleOwner) { files = it; binding.exportButton.isEnabled = it.isNotEmpty() }
        binding.exportButton.setOnClickListener {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            exportFile.launch("map_assist_dncs_$date.csv")
        }
        binding.importButton.setOnClickListener { importFile.launch(arrayOf("text/csv", "text/comma-separated-values", "application/vnd.ms-excel")) }
        updateGoogleAccount()
        updateDriveStatus()
        updateSharedLibraryStatus()
        settleSwitchAnimations()
    }
    private fun confirmDriveSync() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sync local data to Google Drive?")
            .setMessage("This will overwrite the current Google Drive backup with the DNC cards stored on this device. If you are unsure, export a local spreadsheet backup first.\n\nFor safety, if this device has no DNC cards, its existing Google Drive backup will be restored instead.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sync and overwrite") { _, _ -> syncNow() }
            .show()
    }
    private fun syncNow() {
        binding.googleDriveSyncButton.isEnabled = false
        binding.googleDriveSyncButton.text = "Syncing…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GoogleDriveBackup.sync(requireContext()) }
                .onSuccess { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(requireContext(), "Google Drive backup failed: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            if (_binding != null) {
                binding.googleDriveSyncButton.isEnabled = true
                updateDriveStatus()
            }
        }
    }

    private fun reconcileGoogleDriveBackup() {
        binding.googleDriveSyncButton.isEnabled = false
        binding.googleDriveSyncButton.text = "Checking Google Drive…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GoogleDriveBackup.findExistingBackup(requireContext()) }
                .onSuccess { backup ->
                    if (backup == null) {
                        useLocalVersion()
                    } else {
                        showBackupChoice(backup)
                    }
                }
                .onFailure { error ->
                    Toast.makeText(requireContext(), "Could not check Google Drive: ${error.message ?: "try again"}", Toast.LENGTH_LONG).show()
                    if (_binding != null) updateDriveStatus()
                }
        }
    }

    private fun showBackupChoice(backup: GoogleDriveBackup.RemoteBackup) {
        if (_binding == null) return
        val timestamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(backup.modifiedTime))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Google Drive backup found")
            .setMessage("A Map Assist backup from $timestamp already exists in Google Drive.\n\nRestore from Google Drive will permanently replace the cards on this device.\n\nKeep local version will permanently overwrite the Google Drive backup with this device's cards.")
            .setNegativeButton("Cancel") { _, _ -> updateDriveStatus() }
            .setNeutralButton("Keep local version") { _, _ -> confirmBackupChoice(
                title = "Overwrite the Drive backup?",
                message = "This will permanently replace the Google Drive backup with the cards on this device. If you are unsure, export a local spreadsheet backup first.",
                action = "Overwrite Drive backup",
                confirmed = ::useLocalVersion
            ) }
            .setPositiveButton("Restore from Google Drive") { _, _ -> confirmBackupChoice(
                title = "Replace this device's cards?",
                message = "This will permanently replace all cards on this device with the Google Drive backup. If you are unsure, export a local spreadsheet backup first.",
                action = "Restore and replace local cards",
                confirmed = { restoreDriveBackup(backup) }
            ) }
            .show()
    }

    private fun confirmBackupChoice(title: String, message: String, action: String, confirmed: () -> Unit) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel") { _, _ -> updateDriveStatus() }
            .setPositiveButton(action) { _, _ -> confirmed() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.destructive)
                )
            }
        }
        dialog.show()
    }

    private fun restoreDriveBackup(backup: GoogleDriveBackup.RemoteBackup) {
        binding.googleDriveSyncButton.isEnabled = false
        binding.googleDriveSyncButton.text = "Restoring…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GoogleDriveBackup.restore(requireContext(), backup) }
                .onSuccess { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(requireContext(), "Could not restore backup: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            if (_binding != null) { binding.googleDriveSyncButton.isEnabled = true; updateDriveStatus() }
        }
    }

    private fun useLocalVersion() {
        binding.googleDriveSyncButton.isEnabled = false
        binding.googleDriveSyncButton.text = "Saving…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GoogleDriveBackup.upload(requireContext()) }
                .onSuccess { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(requireContext(), "Google Drive backup failed: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            if (_binding != null) { binding.googleDriveSyncButton.isEnabled = true; updateDriveStatus() }
        }
    }
    private fun updateDriveStatus() {
        val preferences = requireContext().getSharedPreferences("map_assist", 0)
        val connected = GoogleDriveBackup.hasConnectedAccount(requireContext())
        binding.googleDriveSyncButton.isEnabled = connected
        binding.googleDriveSyncButton.text = "Sync now to Google Drive"
        binding.googleDriveStatus.text = when {
            !connected -> "Sign in above to create or restore a Google Drive backup."
            preferences.getBoolean("drive_backup_newer", false) -> "A newer Google Drive backup is available. Syncing now will replace it with this device's data."
            preferences.getLong("drive_last_backup", 0) > 0 -> "Last saved ${formatLastSync(preferences.getLong("drive_last_backup", 0))}."
            else -> "Google Drive is connected. Choose which version to keep before backups start."
        }
    }

    private fun updateGoogleAccount() {
        if (_binding == null) return
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        val connected = GoogleDriveBackup.hasConnectedAccount(requireContext())
        binding.googleAccountStatus.text = if (connected) {
            "Signed in as ${account?.email ?: "Google account"}"
        } else {
            "Not signed in. Sign in once to use Google Drive backups or a shared library."
        }
        binding.googleAccountButton.visibility = if (connected) View.GONE else View.VISIBLE
        binding.googleDriveDisconnectButton.visibility = if (connected) View.VISIBLE else View.GONE
        // Reserve the space only for a signed-in account while its optional
        // remote photo loads. A signed-out message should align to the card.
        binding.googleAccountAvatar.visibility = if (connected) View.INVISIBLE else View.GONE
        val photoUrl = account?.photoUrl?.toString()
        if (connected && !photoUrl.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val avatar = withContext(Dispatchers.IO) {
                    runCatching { java.net.URL(photoUrl).openStream().use(BitmapFactory::decodeStream) }.getOrNull()
                }
                if (_binding != null && avatar != null && GoogleSignIn.getLastSignedInAccount(requireContext())?.photoUrl?.toString() == photoUrl) {
                    binding.googleAccountAvatar.setImageBitmap(avatar)
                    binding.googleAccountAvatar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showGoogleAccountHelp() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("How Google Drive is used")
            .setMessage("When you choose a Google account, Map Assist creates a folder called Map Assist in that account's Google Drive.\n\nManual Google Drive backups are saved there. Shared libraries are also stored there as a CSV file, which you can share with other people as Editors.\n\nMap Assist only accesses files that it creates or that you explicitly connect as a shared library.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun createSharedLibrary() {
        if (!GoogleDriveBackup.hasConnectedAccount(requireContext())) {
            Toast.makeText(requireContext(), "Sign in to a Google account at the top of Settings first", Toast.LENGTH_LONG).show()
            return
        }
        binding.createSharedLibraryButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GoogleDriveBackup.createSharedLibrary(requireContext()) }
                .onSuccess { link ->
                    updateSharedLibraryStatus()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Shared library created")
                        .setMessage("Share this file with the other people as Editors in Google Drive, then send them this link:\n\n$link\n\nThis is the synchronised CSV. Do not use Google Drive's ‘Save as Google Sheets’ option as that creates a separate copy which Map Assist cannot sync.")
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Share link") { _, _ ->
                            startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, link)
                            }, "Share shared library link"))
                        }
                        .show()
                }
                .onFailure { Toast.makeText(requireContext(), "Could not create shared library: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            if (_binding != null) binding.createSharedLibraryButton.isEnabled = true
        }
    }

    private fun showJoinSharedLibraryDialog() {
        if (!GoogleDriveBackup.hasConnectedAccount(requireContext())) {
            Toast.makeText(requireContext(), "Sign in to a Google account at the top of Settings first", Toast.LENGTH_LONG).show()
            return
        }
        val link = android.widget.EditText(requireContext()).apply { hint = "Google Drive sharing link" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Join shared library")
            .setMessage("Ask the library owner to share the file with your Google account as an Editor, then paste its sharing link here.")
            .setView(link)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { GoogleDriveBackup.connectSharedLibrary(requireContext(), link.text.toString()) }
                        .onSuccess(::showSharedLibraryChoice)
                        .onFailure { Toast.makeText(requireContext(), "Could not open shared library: ${it.message ?: "check the link and sharing permission"}", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    private fun showSharedLibraryChoice(remote: GoogleDriveBackup.RemoteBackup) {
        val timestamp = formatLastSync(remote.modifiedTime)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Shared library connected")
            .setMessage("The shared library was last updated $timestamp. Download shared library replaces this device's data. Upload local data replaces the shared library for everyone.")
            .setNegativeButton("Cancel") { _, _ -> updateSharedLibraryStatus() }
            .setNeutralButton("Upload local data") { _, _ -> confirmSharedOverwrite(enableAutoSync = true) }
            .setPositiveButton("Download shared library") { _, _ -> downloadSharedLibrary(remote, enableAutoSync = true) }
            .show()
    }

    private fun confirmSharedOverwrite(enableAutoSync: Boolean = false) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Replace shared library?")
            .setMessage("This will replace the shared library for every person using it. Export a local backup first if you are unsure.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Replace shared library") { _, _ -> uploadSharedLibrary(enableAutoSync) }
            .show()
    }

    private fun syncSharedLibrary() {
        binding.sharedSyncButton.isEnabled = false
        binding.sharedSyncButton.text = "Syncing…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GoogleDriveBackup.syncSharedLibrary(requireContext()) }
                .onSuccess { result ->
                    if (result == "Shared library needs attention") showSharedConflict()
                    else Toast.makeText(requireContext(), result, Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(requireContext(), "Shared library sync failed: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            if (_binding != null) { binding.sharedSyncButton.isEnabled = true; updateSharedLibraryStatus() }
        }
    }

    private fun uploadSharedLibrary(enableAutoSync: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GoogleDriveBackup.uploadSharedLibrary(requireContext()) }
                .onSuccess {
                    if (enableAutoSync) GoogleDriveBackup.setSharedAutoSyncEnabled(requireContext(), true)
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                    updateSharedLibraryStatus()
                }
                .onFailure { Toast.makeText(requireContext(), "Could not update shared library: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun downloadSharedLibrary(remote: GoogleDriveBackup.RemoteBackup? = null, enableAutoSync: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GoogleDriveBackup.restoreSharedLibrary(requireContext(), remote) }
                .onSuccess {
                    if (enableAutoSync) GoogleDriveBackup.setSharedAutoSyncEnabled(requireContext(), true)
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                    updateSharedLibraryStatus()
                }
                .onFailure { Toast.makeText(requireContext(), "Could not download shared library: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun showSharedConflict() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Shared library needs attention")
            .setMessage("Both this device and the shared library changed since the last sync. Choose one version to keep. Export a local backup first if you are unsure.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Replace shared library") { _, _ -> confirmSharedOverwrite() }
            .setPositiveButton("Download shared library") { _, _ -> downloadSharedLibrary() }
            .show()
    }

    private fun confirmLeaveSharedLibrary() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Leave shared library?")
            .setMessage("This only disconnects this device. The shared data in Google Drive will not be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Leave") { _, _ -> GoogleDriveBackup.disconnectSharedLibrary(requireContext()); updateSharedLibraryStatus() }
            .show()
    }

    private fun updateSharedLibraryStatus() {
        if (_binding == null) return
        val configured = GoogleDriveBackup.isSharedLibraryConfigured(requireContext())
        val preferences = requireContext().getSharedPreferences("map_assist", 0)
        binding.sharedAutoSyncSwitch.setOnCheckedChangeListener(null)
        binding.sharedAutoSyncSwitch.isChecked = GoogleDriveBackup.isSharedAutoSyncEnabled(requireContext())
        binding.sharedAutoSyncSwitch.isEnabled = configured
        binding.sharedAutoSyncSwitch.setOnCheckedChangeListener { _, enabled ->
            if (configured) GoogleDriveBackup.setSharedAutoSyncEnabled(requireContext(), enabled)
        }
        binding.sharedSyncButton.visibility = if (configured) View.VISIBLE else View.GONE
        binding.sharedSyncButton.text = "Sync shared library"
        binding.disconnectSharedLibraryButton.visibility = if (configured) View.VISIBLE else View.GONE
        val missing = preferences.getString("shared_library_state", "") == "missing"
        binding.createSharedLibraryButton.visibility = if (!configured || missing) View.VISIBLE else View.GONE
        binding.createSharedLibraryButton.text = if (missing) "Replace shared library" else "Create shared library"
        binding.joinSharedLibraryButton.visibility = if (!configured || missing) View.VISIBLE else View.GONE
        binding.sharedLibraryStatus.text = when {
            !GoogleDriveBackup.hasConnectedAccount(requireContext()) -> "Sign in to a Google account above to create or join a shared library."
            !configured -> "Create a shared library, or join one that its owner has shared with your Google account."
            missing -> "The shared CSV is missing from Google Drive. Create a replacement library, then share its new link with the other people."
            preferences.getString("shared_library_state", "") == "conflict" -> "Changes were made on more than one device. Sync needs your attention."
            GoogleDriveBackup.isSharedAutoSyncEnabled(requireContext()) -> "Automatic sync is on. Last synced ${formatLastSync(preferences.getLong("shared_library_last_sync", 0L))}."
            else -> "Connected. Turn on automatic sync when you are ready."
        }
        updateSharedSyncAttention()
        settleSwitchAnimations()
    }

    private fun updateSharedSyncAttention() {
        val state = GoogleDriveBackup.sharedSyncState(requireContext())
        val message = when (state) {
            "pending" -> "Shared library sync is pending. Your changes will be sent when a connection is available."
            "failed" -> "Shared library sync failed. Check your connection and use Sync shared library below to try again."
            "conflict" -> "Shared library needs attention. Changes were made on more than one device; choose which version to keep below."
            "missing" -> "The shared library file was deleted from Google Drive. Create a replacement library or join the correct shared file below."
            else -> null
        }
        binding.sharedSyncAttentionCard.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) {
            val error = state != "pending"
            binding.sharedSyncAttentionCard.setCardBackgroundColor(
                ContextCompat.getColor(requireContext(), if (error) R.color.destructive else R.color.primary_container)
            )
            binding.sharedSyncAttentionText.text = message
            binding.sharedSyncAttentionText.setTextColor(
                if (error) android.graphics.Color.WHITE else ContextCompat.getColor(requireContext(), R.color.on_surface)
            )
        }
    }

    private fun settleSwitchAnimations() {
        binding.root.post {
            if (_binding != null) {
                binding.darkModeSwitch.jumpDrawablesToCurrentState()
                binding.sharedAutoSyncSwitch.jumpDrawablesToCurrentState()
            }
        }
    }
    private fun applyAccentControls() {
        val accent = AccentPreferences.control(requireContext())
        val onAccent = android.graphics.Color.WHITE
        fun solid(button: com.google.android.material.button.MaterialButton) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            button.setTextColor(onAccent)
            button.iconTint = android.content.res.ColorStateList.valueOf(onAccent)
        }
        fun outlined(button: com.google.android.material.button.MaterialButton) {
            button.strokeColor = android.content.res.ColorStateList.valueOf(accent)
            button.setTextColor(accent)
            button.iconTint = android.content.res.ColorStateList.valueOf(accent)
        }
        solid(binding.exportButton)
        solid(binding.googleDriveSyncButton)
        solid(binding.createSharedLibraryButton)
        solid(binding.sharedSyncButton)
        solid(binding.googleAccountButton)
        outlined(binding.importButton)
        outlined(binding.coloursButton)
        outlined(binding.joinSharedLibraryButton)
        binding.openDownloadsButton.setTextColor(accent)
        binding.openDownloadsButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        binding.googleDriveDisconnectButton.setTextColor(accent)
        binding.googleDriveDisconnectButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        binding.googleAccountHelpButton.setTextColor(accent)
        binding.googleAccountHelpButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        binding.disconnectSharedLibraryButton.setTextColor(accent)
        binding.disconnectSharedLibraryButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        binding.darkModeSwitch.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(accent, ContextCompat.getColor(requireContext(), R.color.on_surface_variant)))
        binding.darkModeSwitch.trackTintList = android.content.res.ColorStateList(states, intArrayOf(AccentPreferences.primaryContainer(requireContext()), ContextCompat.getColor(requireContext(), R.color.outline_variant)))
        binding.sharedAutoSyncSwitch.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(accent, ContextCompat.getColor(requireContext(), R.color.on_surface_variant)))
        binding.sharedAutoSyncSwitch.trackTintList = android.content.res.ColorStateList(states, intArrayOf(AccentPreferences.primaryContainer(requireContext()), ContextCompat.getColor(requireContext(), R.color.outline_variant)))
    }
    private fun formatLastSync(time: Long) = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Disconnect Google Drive?")
            .setMessage("This disconnects this device from Google Drive. Shared-library syncing and Google Drive backup features will be unavailable until you sign in again. Shared files and backups in Google Drive will not be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disconnect") { _, _ ->
                GoogleDriveBackup.disconnect(requireContext()).addOnCompleteListener {
                    GoogleDriveBackup.clearConnection(requireContext())
                    if (_binding != null) { updateGoogleAccount(); updateDriveStatus(); updateSharedLibraryStatus() }
                    Toast.makeText(requireContext(), "Google Drive disconnected", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
    private fun readCsv(uri: android.net.Uri): BackupCsv.BackupData = requireContext().contentResolver.openInputStream(uri)
        ?.bufferedReader()
        ?.use { BackupCsv.parseBundle(it.readText()) }
        ?: error("No data")
    private fun normalizeDate(value: String): String { val match = Regex("(0?[1-9]|1[0-2])[/.-](\\d{2,4})").find(value) ?: return value; return "%02d/%02d".format(match.groupValues[1].toInt(), match.groupValues[2].takeLast(2).toInt()) }
    private fun importedDate(value: String): String { val text = value.trim(); val date = if (text.startsWith("=\"") && text.endsWith("\"") && text.length >= 4) text.substring(2, text.length - 1) else text; return date.replace('.', '/').take(5) }
    private fun parseCsvRecords(text: String): List<List<String>> { val records = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder(); var quote = false; var i = 0; while (i < text.length) { val c = text[i]; when { c == '"' && quote && i + 1 < text.length && text[i + 1] == '"' -> { cell.append(c); i++ }; c == '"' -> quote = !quote; c == ',' && !quote -> { row += cell.toString(); cell.clear() }; (c == '\n' || c == '\r') && !quote -> { if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++; row += cell.toString(); cell.clear(); records += row; row = mutableListOf() }; else -> cell.append(c) }; i++ }; if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); records += row }; return records }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("settings_scroll_y", _binding?.root?.scrollY ?: restoreScrollY); super.onSaveInstanceState(outState) }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
