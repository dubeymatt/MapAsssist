package com.example.mapasssist

import android.os.Bundle
import android.view.View
import android.content.res.ColorStateList
import android.content.SharedPreferences
import android.graphics.Color
import android.widget.Toast
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.findNavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.core.graphics.ColorUtils
import com.example.mapasssist.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var activeTabDestination = R.id.FirstFragment
    private var pendingTabDirection = 0
    private var syncPillAction: Runnable? = null
    private val sharedSyncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shared_library_state" || key == "shared_library_file_id") updateSharedSyncPill()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MapAsssist)
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(if (getSharedPreferences("map_assist", 0).getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyAccent()
        getSharedPreferences("map_assist", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(sharedSyncListener)
        // Do not replay a past success merely because this activity was
        // recreated for a local appearance change.
        updateSharedSyncPill(showStoredSuccess = false)
        GoogleDriveBackup.disableLegacyWeeklyBackups(this)
        // Shared syncing is queued only by data edits. In particular, activity
        // recreation after changing colours or dark mode must stay local.
        setSupportActionBar(binding.toolbar)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController
        binding.sharedSyncPill.setOnClickListener {
            if (GoogleDriveBackup.sharedSyncState(this) in setOf("pending", "failed", "conflict", "missing")) {
                selectTab(navController, R.id.SettingsFragment)
            }
        }
        binding.addFab.setOnClickListener { navController.navigate(R.id.action_FirstFragment_to_SecondFragment) }
        binding.toolbar.setNavigationOnClickListener {
            val editor = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)?.childFragmentManager?.fragments?.filterIsInstance<SecondFragment>()?.firstOrNull()
            if (editor?.requestNavigateUp() != true) navController.navigateUp()
        }
        binding.bottomNavigation.selectedItemId = R.id.navigation_dncs
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_maps -> { selectTab(navController, R.id.MapsFragment); true }
                R.id.navigation_dncs -> { selectTab(navController, R.id.FirstFragment); true }
                R.id.navigation_ledger -> { selectTab(navController, R.id.LedgerFragment); true }
                R.id.navigation_settings -> { selectTab(navController, R.id.SettingsFragment); true }
                else -> false
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isDncPage = destination.id == R.id.FirstFragment
            // Maps owns a search action too. Do not clear it after that fragment has
            // populated the toolbar, otherwise the icon briefly appears then vanishes.
            if (!isDncPage && destination.id != R.id.MapsFragment && destination.id != R.id.LedgerFragment) binding.toolbar.menu.clear()
            val selectedTab = when (destination.id) {
                R.id.FirstFragment -> R.id.navigation_dncs
                R.id.MapsFragment -> R.id.navigation_maps
                R.id.SettingsFragment -> R.id.navigation_settings
                R.id.LedgerFragment -> R.id.navigation_ledger
                else -> null
            }
            selectedTab?.let {
                binding.bottomNavigation.menu.findItem(it).isChecked = true
                activeTabDestination = destination.id
                if (pendingTabDirection != 0) {
                    animateTabContent(pendingTabDirection)
                    pendingTabDirection = 0
                }
            }
            val isTabPage = isDncPage || destination.id == R.id.SettingsFragment || destination.id == R.id.MapsFragment || destination.id == R.id.LedgerFragment
            val content = binding.root.findViewById<View>(R.id.content_main_root)
            content.layoutParams = (content.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                bottomMargin = if (isTabPage) (80 * resources.displayMetrics.density).toInt() else 0
            }
            binding.addFab.visibility = if (isDncPage) View.VISIBLE else View.GONE
            binding.bottomNavigation.visibility = if (isTabPage) View.VISIBLE else View.GONE
            binding.toolbar.navigationIcon = if (isTabPage) null else getDrawable(R.drawable.ic_back)
            binding.toolbar.title = when (destination.id) {
                R.id.FirstFragment -> getString(R.string.app_name)
                R.id.SettingsFragment -> "Settings"
                R.id.LedgerFragment -> getString(R.string.app_name)
                R.id.ColoursFragment -> "Settings"
                R.id.MapsFragment -> getString(R.string.app_name)
                else -> getString(R.string.second_fragment_label)
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        syncPillAction?.let { binding.sharedSyncPill.removeCallbacks(it) }
        getSharedPreferences("map_assist", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(sharedSyncListener)
        super.onDestroy()
    }

    /** Keeps background sync feedback visible without turning the toolbar into a progress screen. */
    private fun updateSharedSyncPill(showStoredSuccess: Boolean = true) {
        if (!::binding.isInitialized || !GoogleDriveBackup.isSharedLibraryConfigured(this)) {
            hideSharedSyncPill(immediate = true)
            updateSettingsSyncBadge()
            return
        }
        when (GoogleDriveBackup.sharedSyncState(this)) {
            "pending" -> showSharedSyncPill("Sync pending", R.drawable.ic_cloud_backup, Color.rgb(168, 122, 20), false, true, true)
            "syncing" -> showSharedSyncPill("Syncing", R.drawable.ic_cloud_backup, AccentPreferences.primary(this), false)
            "synced" -> if (showStoredSuccess) showSharedSyncPill("Synced", R.drawable.ic_check, AccentPreferences.primary(this), true) else hideSharedSyncPill(immediate = true)
            "failed" -> showSharedSyncPill("Sync failed", R.drawable.ic_cloud_backup, getColor(R.color.destructive), false, true, true)
            "conflict" -> showSharedSyncPill("Sync needs attention", R.drawable.ic_cloud_backup, getColor(R.color.destructive), false, true, true)
            "missing" -> showSharedSyncPill("Shared file missing", R.drawable.ic_cloud_backup, getColor(R.color.destructive), false, true, true)
            else -> hideSharedSyncPill(immediate = true)
        }
        updateSettingsSyncBadge()
    }

    private fun showSharedSyncPill(text: String, icon: Int, tint: Int, dismissWhenIdle: Boolean, collapseText: Boolean = false, collapseToCircle: Boolean = false) {
        syncPillAction?.let { binding.sharedSyncPill.removeCallbacks(it) }
        syncPillAction = null
        binding.sharedSyncPill.apply {
            minimumWidth = dp(72)
            binding.sharedSyncContent.setPadding(dp(11), 0, dp(11), 0)
            setCardBackgroundColor(ColorStateList.valueOf(syncPillBackground(tint)))
            binding.sharedSyncIcon.setImageResource(icon)
            binding.sharedSyncIcon.imageTintList = ColorStateList.valueOf(tint)
            binding.sharedSyncLabel.apply {
                this.text = text
                alpha = 1f
                visibility = View.VISIBLE
                setTextColor(if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) Color.WHITE else getColor(R.color.on_surface))
            }
            if (visibility != View.VISIBLE) {
                visibility = View.VISIBLE
                alpha = 0f
                scaleX = 0.82f
                scaleY = 0.82f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L)
                    .setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
        if (dismissWhenIdle) {
            val dismiss = Runnable { hideSharedSyncPill(immediate = false) }
            syncPillAction = dismiss
            binding.sharedSyncPill.postDelayed(dismiss, 2_200L)
        } else if (collapseText) {
            val collapse = Runnable { collapseSharedSyncPill(collapseToCircle) }
            syncPillAction = collapse
            binding.sharedSyncPill.postDelayed(collapse, 2_500L)
        }
    }

    private fun collapseSharedSyncPill(toCircle: Boolean) {
        syncPillAction = null
        if (binding.sharedSyncPill.visibility != View.VISIBLE || binding.sharedSyncLabel.visibility != View.VISIBLE) return
        binding.sharedSyncLabel.animate().alpha(0f).setDuration(150L).withEndAction {
            binding.sharedSyncLabel.visibility = View.GONE
            if (toCircle) {
                binding.sharedSyncPill.minimumWidth = dp(36)
                binding.sharedSyncContent.setPadding(dp(8), 0, dp(8), 0)
            }
            binding.sharedSyncPill.animate().scaleX(0.9f).scaleY(0.9f).setDuration(130L)
                .withEndAction {
                    binding.sharedSyncPill.scaleX = 1f
                    binding.sharedSyncPill.scaleY = 1f
                }.start()
        }.start()
    }

    private fun hideSharedSyncPill(immediate: Boolean) {
        syncPillAction?.let { binding.sharedSyncPill.removeCallbacks(it) }
        syncPillAction = null
        if (binding.sharedSyncPill.visibility != View.VISIBLE) return
        if (immediate) {
            binding.sharedSyncPill.visibility = View.GONE
            return
        }
        binding.sharedSyncLabel.animate().alpha(0f).setDuration(120L).start()
        binding.sharedSyncPill.animate()
            .scaleX(0.72f)
            .scaleY(0.72f)
            .alpha(0f)
            .setStartDelay(100L)
            .setDuration(220L)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                binding.sharedSyncPill.visibility = View.GONE
                binding.sharedSyncPill.alpha = 1f
                binding.sharedSyncPill.scaleX = 1f
                binding.sharedSyncPill.scaleY = 1f
            }
            .start()
    }

    private fun syncPillBackground(tint: Int): Int {
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return ColorUtils.blendARGB(tint, if (dark) Color.rgb(39, 45, 42) else Color.WHITE, if (dark) 0.70f else 0.84f)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateSettingsSyncBadge() {
        val state = GoogleDriveBackup.sharedSyncState(this)
        val needsAttention = state in setOf("pending", "failed", "conflict", "missing")
        val badge = binding.bottomNavigation.getOrCreateBadge(R.id.navigation_settings)
        badge.backgroundColor = getColor(R.color.destructive)
        badge.clearNumber()
        // A blank text badge uses the larger badge geometry while remaining a
        // clean red notification dot.
        badge.setText(" ")
        badge.setBadgeTextColor(Color.TRANSPARENT)
        badge.isVisible = needsAttention
    }
    private fun applyAccent() {
        val accent = AccentPreferences.primary(this)
        binding.addFab.backgroundTintList = ColorStateList.valueOf(accent)
        binding.addFab.iconTint = ColorStateList.valueOf(Color.WHITE)
        binding.addFab.setTextColor(Color.WHITE)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val selectedContent = if (dark) Color.WHITE else accent
        binding.bottomNavigation.itemIconTintList = ColorStateList(states, intArrayOf(selectedContent, getColor(R.color.on_surface_variant)))
        binding.bottomNavigation.itemTextColor = ColorStateList(states, intArrayOf(selectedContent, getColor(R.color.on_surface_variant)))
        binding.bottomNavigation.itemActiveIndicatorColor = ColorStateList.valueOf(
            if (dark) accent else AccentPreferences.primaryContainer(this)
        )
    }

    private fun selectTab(navController: androidx.navigation.NavController, destination: Int) {
        if (activeTabDestination == destination) return
        val movingRight = tabIndex(destination) > tabIndex(activeTabDestination)
        pendingTabDirection = if (movingRight) 1 else -1
        navController.navigate(destination, null, NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.startDestinationId, false, true)
            // State restoration has inconsistent enter/pop callbacks. The consistent
            // directional animation is applied below after the destination changes.
            .setEnterAnim(0)
            .setExitAnim(0)
            .setPopEnterAnim(0)
            .setPopExitAnim(0)
            .build())
    }

    private fun animateTabContent(direction: Int) {
        val content = binding.root.findViewById<View>(R.id.nav_host_fragment_content_main)
        content.post {
            val distance = content.width * 0.055f * direction
            content.animate().cancel()
            content.translationX = distance
            content.alpha = 0f
            content.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(190L)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
    }

    private fun tabIndex(destination: Int): Int = when (destination) {
        R.id.MapsFragment -> 0
        R.id.FirstFragment -> 1
        R.id.LedgerFragment -> 2
        R.id.SettingsFragment -> 3
        else -> 1
    }
}
