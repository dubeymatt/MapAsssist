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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
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
        requireContext().contentResolver.openOutputStream(uri)?.use { stream -> OutputStreamWriter(stream).use { it.write(BackupCsv.create(files, LedgerStore.read(requireContext()), SharedActivityStore.read(requireContext()))) } }
        Toast.makeText(requireContext(), "Spreadsheet exported", Toast.LENGTH_SHORT).show()
    }
    private val googleDriveSignIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        runCatching { task.getResult(ApiException::class.java) }
            .onSuccess { account ->
                val token = account.idToken
                if (token == null) {
                    Toast.makeText(requireContext(), "Google sign-in needs an updated Firebase configuration", Toast.LENGTH_LONG).show()
                    updateGoogleAccount()
                } else {
                    FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                        .addOnSuccessListener { FirebaseSharedLibrary.start(requireContext()); updateGoogleAccount(); reconcileGoogleDriveBackup(); updateSharedLibraryStatus() }
                        .addOnFailureListener { Toast.makeText(requireContext(), "Google account could not be verified: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
                }
            }
            .onFailure { Toast.makeText(requireContext(), "Google Drive was not connected", Toast.LENGTH_SHORT).show(); updateGoogleAccount() }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val imported = runCatching { readCsv(uri) }.getOrElse { Toast.makeText(requireContext(), "Could not read this spreadsheet. Export a fresh Map Assist spreadsheet and use that as the template.", Toast.LENGTH_LONG).show(); return@registerForActivityResult }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Import will permanently replace all data").setMessage("This will delete all ${files.size} saved DNC cards and replace them with ${imported.files.size} cards from the spreadsheet. This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Replace all data") { _, _ -> viewModel.replaceAll(imported.files); LedgerStore.replace(requireContext(), imported.assignments); Toast.makeText(requireContext(), "DNCs and ledger replaced", Toast.LENGTH_SHORT).show() }.show()
    }
    private val sharedLibraryFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GoogleDriveBackup.connectSharedLibraryDocument(requireContext(), uri) }
                .onSuccess(::showSharedLibraryChoice)
                .onFailure { error -> Toast.makeText(requireContext(), "Could not join shared library: ${error.message ?: "choose the shared CSV from Google Drive"}", Toast.LENGTH_LONG).show() }
        }
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
        binding.openDriveButton.setOnClickListener { openDriveFolder() }
        binding.checkUpdatesButton.setOnClickListener { checkForUpdates() }
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
        binding.sharedAutoSyncSwitch.setOnCheckedChangeListener { _, _ -> }
        binding.sharedSyncButton.setOnClickListener { syncSharedLibrary() }
        binding.sharedInviteButton.setOnClickListener { showInviteDialog() }
        binding.sharedMembersButton.setOnClickListener { showSharedMembers() }
        binding.sharedTransferHelpButton.setOnClickListener { showHostTransferHelp() }
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
        binding.settingsVersion.text = "Version ${BuildConfig.VERSION_NAME}"
        settleSwitchAnimations()
    }

    private fun checkForUpdates() {
        binding.checkUpdatesButton.isEnabled = false
        binding.checkUpdatesButton.text = "Checking…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GitHubUpdateManager.checkForUpdate() }
                .onSuccess { release ->
                    if (release == null) {
                        Toast.makeText(requireContext(), "Map Assist is up to date", Toast.LENGTH_SHORT).show()
                    } else {
                        showUpdateAvailable(release)
                    }
                }
                .onFailure { error ->
                    Toast.makeText(requireContext(), "Could not check for updates: ${error.message ?: "try again"}", Toast.LENGTH_LONG).show()
                }
            if (_binding != null) {
                binding.checkUpdatesButton.isEnabled = true
                binding.checkUpdatesButton.text = "Check for updates"
            }
        }
    }

    private fun showUpdateAvailable(release: GitHubUpdateManager.Release) {
        val notes = release.notes.take(1_800).ifBlank { "No release notes were supplied." }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Map Assist ${release.version} is available")
            .setMessage("$notes\n\nAndroid will ask for confirmation before installing the update.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Download update") { _, _ -> downloadUpdate(release) }
            .show()
    }

    private fun downloadUpdate(release: GitHubUpdateManager.Release) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !requireContext().packageManager.canRequestPackageInstalls()
        ) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Allow updates from Map Assist")
                .setMessage("Android needs permission for Map Assist to open its update installer. After allowing it, tap Check for updates again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${requireContext().packageName}")))
                }
                .show()
            return
        }
        binding.checkUpdatesButton.isEnabled = false
        binding.checkUpdatesButton.text = "Downloading…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { GitHubUpdateManager.download(requireContext(), release) }
                .onSuccess { apk -> GitHubUpdateManager.install(requireContext(), apk) }
                .onFailure { error -> Toast.makeText(requireContext(), "Could not download update: ${error.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            if (_binding != null) {
                binding.checkUpdatesButton.isEnabled = true
                binding.checkUpdatesButton.text = "Check for updates"
            }
        }
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
            .setTitle("Your Google account")
            .setMessage("Your Google account identifies you in Map Assist and lets you use personal Google Drive backups.\n\nShared libraries are live and private: they use Map Assist’s secure online database, not files in your Google Drive. Join one with an invite code from its host.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openDriveFolder() {
        val folderId = requireContext().getSharedPreferences("map_assist", 0).getString("drive_folder_id", null)
        if (folderId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "The Map Assist Drive folder will be created when you first save a backup or shared library.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://drive.google.com/drive/folders/$folderId"))
        runCatching { startActivity(intent) }.getOrElse {
            Toast.makeText(requireContext(), "Could not open Google Drive", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createSharedLibrary() {
        if (!FirebaseSharedLibrary.signedIn()) {
            Toast.makeText(requireContext(), "Sign in to a Google account at the top of Settings first", Toast.LENGTH_LONG).show()
            return
        }
        binding.createSharedLibraryButton.isEnabled = false
        binding.createSharedLibraryButton.text = "Creating…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { FirebaseSharedLibrary.create(requireContext()) }
                .onSuccess {
                    updateSharedLibraryStatus()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Shared library created")
                        .setMessage("Your live shared library is ready. Use Share to create a private invite code. Changes made by members will appear automatically.")
                        .setPositiveButton("Done", null)
                        .show()
                }
                .onFailure { Toast.makeText(requireContext(), "Could not create shared library: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            if (_binding != null) { binding.createSharedLibraryButton.isEnabled = true; binding.createSharedLibraryButton.text = "Create" }
        }
    }

    private fun showJoinSharedLibraryDialog() {
        val code = android.widget.EditText(requireContext()).apply {
            hint = "8-character invite code"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Join shared library")
            .setMessage("Enter the private invite code sent by the library host. Joining safely downloads the live library and keeps it in sync automatically.")
            .setView(code)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Join") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { FirebaseSharedLibrary.join(requireContext(), code.text.toString()) }
                        .onSuccess { updateSharedLibraryStatus(); Toast.makeText(requireContext(), "Joined shared library", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(requireContext(), "Could not join: ${it.message ?: "check the invite code"}", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    private fun showInviteDialog() {
        binding.sharedInviteButton.isEnabled = false
        binding.sharedInviteButton.text = "Preparing…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { FirebaseSharedLibrary.createInvite(requireContext()) }
                .onSuccess { code ->
                    val message = "Join my Map Assist shared library.\n\nOpen Settings → Shared library → Join, then enter this private invite code:\n\n$code"
                    startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, message)
                    }, "Share Map Assist invite"))
                }
                .onFailure { Toast.makeText(requireContext(), "Could not create invite: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            if (_binding != null) { binding.sharedInviteButton.isEnabled = true; binding.sharedInviteButton.text = "Share" }
        }
    }

    private fun showSharedMembers() {
        binding.sharedMembersButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { FirebaseSharedLibrary.members(requireContext()) }
                .onSuccess { members ->
                    val host = FirebaseSharedLibrary.isHost(requireContext())
                    val labels = members.map { member -> "${member.name}\n${member.email.ifBlank { "Google account" }} · ${member.role}" }.toTypedArray()
                    MaterialAlertDialogBuilder(requireContext()).setTitle("Library members")
                        .setItems(labels) { _, index ->
                            val member = members[index]
                            if (host && member.role != "Host") confirmRemoveMember(member)
                        }
                        .setNegativeButton("Close", null).show()
                }
                .onFailure { error -> Toast.makeText(requireContext(), "Could not load members: ${error.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            if (_binding != null) binding.sharedMembersButton.isEnabled = true
        }
    }

    private fun confirmRemoveMember(member: FirebaseSharedLibrary.Member) {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Remove ${member.name}?")
            .setMessage("They will immediately lose access to this shared library on all of their devices.")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { FirebaseSharedLibrary.removeMember(requireContext(), member) }
                        .onSuccess { Toast.makeText(requireContext(), "Member removed", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(requireContext(), "Could not remove member: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() }
                }
            }.show()
    }

    private fun showHostTransferHelp() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { FirebaseSharedLibrary.members(requireContext()).filter { it.role != "Host" } }
                .onSuccess { members ->
                    MaterialAlertDialogBuilder(requireContext()).setTitle("Manage shared library")
                        .setMessage("Before leaving, transfer the host role to a current member or permanently delete this shared library for everyone.")
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Delete library") { _, _ -> confirmDeleteLibrary() }
                        .setPositiveButton("Transfer host") { _, _ -> chooseNewHost(members) }.show()
                }
                .onFailure { Toast.makeText(requireContext(), "Could not load members", Toast.LENGTH_LONG).show() }
        }
    }

    private fun chooseNewHost(members: List<FirebaseSharedLibrary.Member>) {
        if (members.isEmpty()) { Toast.makeText(requireContext(), "Invite another member before transferring the host role", Toast.LENGTH_LONG).show(); return }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Choose new host")
            .setItems(members.map { it.name }.toTypedArray()) { _, index ->
                val member = members[index]
                MaterialAlertDialogBuilder(requireContext()).setTitle("Transfer host role?")
                    .setMessage("${member.name} will manage members and can delete the library. You will remain a member.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Transfer") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch { runCatching { FirebaseSharedLibrary.transferHost(requireContext(), member) }
                            .onSuccess { updateSharedLibraryStatus(); Toast.makeText(requireContext(), "Host role transferred", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(requireContext(), "Could not transfer host: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() } }
                    }.show()
            }.show()
    }

    private fun confirmDeleteLibrary() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Delete shared library?")
            .setMessage("This permanently removes the live shared library for every member. Their local data stays on their own devices. Export a backup first if you are unsure.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete for everyone") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { runCatching { FirebaseSharedLibrary.deleteLibrary(requireContext()) }
                    .onSuccess { updateSharedLibraryStatus(); Toast.makeText(requireContext(), "Shared library deleted", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(requireContext(), "Could not delete library: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() } }
            }.show()
    }

    private fun showSharedLibraryChoice(remote: GoogleDriveBackup.RemoteBackup) {
        val timestamp = formatLastSync(remote.modifiedTime)
        var joining = true
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Download shared library to join")
            .setMessage("The owner’s shared library was last updated $timestamp.\n\nTo join, Map Assist will download that library and permanently replace the DNC cards and Ledger data currently on this device. The shared library will not be changed.\n\nIf you want to keep this device’s local data, cancel and export a local backup first.")
            .setNegativeButton("Cancel joining") { _, _ -> cancelPendingSharedJoin() }
            .setPositiveButton("Download and join") { _, _ ->
                joining = false
                downloadSharedLibrary(remote, enableAutoSync = true)
            }
            .create()
        dialog.setOnCancelListener {
            if (joining) cancelPendingSharedJoin()
        }
        dialog.show()
    }

    private fun cancelPendingSharedJoin() {
        GoogleDriveBackup.disconnectSharedLibrary(requireContext())
        updateSharedLibraryStatus()
    }

    private fun confirmSharedOverwrite(enableAutoSync: Boolean = false) {
        if (!GoogleDriveBackup.isSharedLibraryHost(requireContext())) {
            Toast.makeText(requireContext(), "Only the host can replace the shared library", Toast.LENGTH_LONG).show()
            return
        }
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
            runCatching { FirebaseSharedLibrary.syncAll(requireContext()) }
                .onSuccess { Toast.makeText(requireContext(), "Shared library updated", Toast.LENGTH_SHORT).show() }
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
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Shared library needs attention")
            .setMessage("Both this device and the shared library changed since the last sync. Choose one version to keep. Export a local backup first if you are unsure.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download shared library") { _, _ -> downloadSharedLibrary() }
        if (GoogleDriveBackup.isSharedLibraryHost(requireContext())) {
            dialog.setNeutralButton("Replace shared library") { _, _ -> confirmSharedOverwrite() }
        }
        dialog.show()
    }

    private fun confirmLeaveSharedLibrary() {
        if (FirebaseSharedLibrary.isHost(requireContext())) { showHostTransferHelp(); return }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Leave shared library?")
            .setMessage("This only disconnects this device. The shared library and other members will not be affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Leave") { _, _ -> FirebaseSharedLibrary.leave(requireContext()); updateSharedLibraryStatus() }
            .show()
    }

    private fun updateSharedLibraryStatus() {
        if (_binding == null) return
        val configured = FirebaseSharedLibrary.isConfigured(requireContext())
        val preferences = requireContext().getSharedPreferences("map_assist", 0)
        val isHost = configured && FirebaseSharedLibrary.isHost(requireContext())
        binding.sharedLibraryRoleBadge.visibility = if (configured) View.VISIBLE else View.GONE
        binding.sharedLibraryDetails.visibility = if (configured) View.VISIBLE else View.GONE
        if (configured) {
        binding.sharedLibraryDetails.text = if (isHost) "You manage invitations and recovery for this library." else "The host manages invitations and recovery for this library."
            binding.sharedLibraryRoleText.text = if (isHost) "HOST · created on this device" else "GUEST · joined shared library"
            binding.sharedLibraryRoleBadge.setCardBackgroundColor(
                if (isHost) AccentPreferences.primaryContainer(requireContext()) else ContextCompat.getColor(requireContext(), R.color.outline_variant)
            )
            binding.sharedLibraryRoleText.setTextColor(
                if (isHost) AccentPreferences.control(requireContext()) else ContextCompat.getColor(requireContext(), R.color.on_surface_variant)
            )
        }
        binding.sharedAutoSyncSwitch.visibility = View.GONE
        binding.sharedSyncButton.visibility = if (configured) View.VISIBLE else View.GONE
        binding.sharedSyncButton.text = "Sync shared library"
        binding.disconnectSharedLibraryButton.visibility = if (configured) View.VISIBLE else View.GONE
        binding.sharedHostActions.visibility = if (configured) View.VISIBLE else View.GONE
        binding.sharedInviteButton.visibility = if (isHost) View.VISIBLE else View.GONE
        binding.sharedMembersButton.visibility = if (configured) View.VISIBLE else View.GONE
        binding.sharedTransferHelpButton.visibility = if (configured && isHost) View.VISIBLE else View.GONE
        binding.sharedTransferHelpButton.text = "Manage library"
        binding.createSharedLibraryButton.visibility = if (!configured) View.VISIBLE else View.GONE
        binding.createSharedLibraryButton.text = "Create shared library"
        binding.joinSharedLibraryButton.visibility = if (!configured) View.VISIBLE else View.GONE
        binding.sharedLibraryStatus.text = when {
            !configured && !FirebaseSharedLibrary.signedIn() -> "Sign in with Google to create or join a live shared library."
            !configured -> "Create a library or join one with a private invite code."
            FirebaseSharedLibrary.state(requireContext()) == "removed" -> "You were removed from this shared library. Your local data has not been deleted."
            FirebaseSharedLibrary.state(requireContext()) == "failed" -> "Live sync needs attention. Check your connection, then use Sync shared library."
            else -> "Live sync is on. Changes appear automatically for library members."
        }
        updateSharedSyncAttention()
        settleSwitchAnimations()
    }

    private fun updateSharedSyncAttention() {
        val state = FirebaseSharedLibrary.state(requireContext())
        val message = when (state) {
            "pending" -> "Shared library sync is pending. Your changes will be sent when a connection is available."
            "failed" -> "Shared library sync failed. Check your connection and use Sync shared library below to try again."
            "removed" -> "You no longer have access to this shared library. Your saved local data remains on this device."
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
        outlined(binding.sharedInviteButton)
        outlined(binding.sharedMembersButton)
        binding.openDownloadsButton.setTextColor(accent)
        binding.openDownloadsButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        binding.openDriveButton.setTextColor(accent)
        binding.openDriveButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        binding.googleDriveDisconnectButton.setTextColor(accent)
        binding.googleDriveDisconnectButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        binding.googleAccountHelpButton.setTextColor(accent)
        binding.googleAccountHelpButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        binding.disconnectSharedLibraryButton.setTextColor(accent)
        binding.disconnectSharedLibraryButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        binding.sharedTransferHelpButton.setTextColor(accent)
        binding.sharedTransferHelpButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        binding.darkModeSwitch.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(accent, ContextCompat.getColor(requireContext(), R.color.on_surface_variant)))
        binding.darkModeSwitch.trackTintList = android.content.res.ColorStateList(states, intArrayOf(AccentPreferences.primaryContainer(requireContext()), ContextCompat.getColor(requireContext(), R.color.outline_variant)))
        binding.sharedAutoSyncSwitch.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(accent, ContextCompat.getColor(requireContext(), R.color.on_surface_variant)))
        binding.sharedAutoSyncSwitch.trackTintList = android.content.res.ColorStateList(states, intArrayOf(AccentPreferences.primaryContainer(requireContext()), ContextCompat.getColor(requireContext(), R.color.outline_variant)))
    }
    private fun formatLastSync(time: Long) = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Disconnect Google account?")
            .setMessage("This pauses live shared-library access and Google Drive backups on this device. The library itself and this device’s local data will not be deleted. Signing in again restores access if you are still a member.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disconnect") { _, _ ->
                GoogleDriveBackup.disconnect(requireContext()).addOnCompleteListener {
                    FirebaseAuth.getInstance().signOut()
                    FirebaseSharedLibrary.signedOut(requireContext())
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
