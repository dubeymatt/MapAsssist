package com.example.mapasssist

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import com.example.mapasssist.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MapAsssist)
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(if (getSharedPreferences("map_assist", 0).getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController
        binding.addFab.setOnClickListener { navController.navigate(R.id.action_FirstFragment_to_SecondFragment) }
        binding.toolbar.setNavigationOnClickListener {
            val editor = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)?.childFragmentManager?.fragments?.filterIsInstance<SecondFragment>()?.firstOrNull()
            if (editor?.requestNavigateUp() != true) navController.navigateUp()
        }
        binding.bottomNavigation.selectedItemId = R.id.navigation_dncs
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_maps -> { if (navController.currentDestination?.id != R.id.MapsFragment) navController.navigate(R.id.MapsFragment); true }
                R.id.navigation_dncs -> { if (navController.currentDestination?.id != R.id.FirstFragment) navController.popBackStack(R.id.FirstFragment, false); true }
                R.id.navigation_settings -> { navController.navigate(R.id.SettingsFragment); true }
                else -> false
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isDncPage = destination.id == R.id.FirstFragment
            if (!isDncPage) binding.toolbar.menu.clear()
            val selectedTab = when (destination.id) {
                R.id.FirstFragment -> R.id.navigation_dncs
                R.id.MapsFragment -> R.id.navigation_maps
                R.id.SettingsFragment -> R.id.navigation_settings
                else -> null
            }
            selectedTab?.let { binding.bottomNavigation.menu.findItem(it).isChecked = true }
            val isTabPage = isDncPage || destination.id == R.id.SettingsFragment || destination.id == R.id.MapsFragment
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
                R.id.MapsFragment -> getString(R.string.app_name)
                else -> getString(R.string.second_fragment_label)
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
