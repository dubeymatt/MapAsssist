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
                R.id.navigation_dncs -> { if (navController.currentDestination?.id != R.id.FirstFragment) navController.popBackStack(R.id.FirstFragment, false); true }
                R.id.navigation_settings -> { navController.navigate(R.id.SettingsFragment); true }
                else -> { Toast.makeText(this, "Maps is coming soon", Toast.LENGTH_SHORT).show(); false }
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isDncPage = destination.id == R.id.FirstFragment
            val isTabPage = isDncPage || destination.id == R.id.SettingsFragment
            binding.addFab.visibility = if (isDncPage) View.VISIBLE else View.GONE
            binding.bottomNavigation.visibility = if (isTabPage) View.VISIBLE else View.GONE
            binding.toolbar.navigationIcon = if (isTabPage) null else getDrawable(R.drawable.ic_back)
            binding.toolbar.title = when (destination.id) {
                R.id.FirstFragment -> getString(R.string.app_name)
                R.id.SettingsFragment -> "Settings"
                else -> getString(R.string.second_fragment_label)
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
