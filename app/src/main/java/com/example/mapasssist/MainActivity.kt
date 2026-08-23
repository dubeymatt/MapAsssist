package com.example.mapasssist

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import com.example.mapasssist.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController
        binding.addFab.setOnClickListener { navController.navigate(R.id.action_FirstFragment_to_SecondFragment) }
        binding.toolbar.setNavigationOnClickListener { navController.navigateUp() }
        binding.bottomNavigation.selectedItemId = R.id.navigation_dncs
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_dncs -> { if (navController.currentDestination?.id != R.id.FirstFragment) navController.popBackStack(R.id.FirstFragment, false); true }
                else -> { Toast.makeText(this, "This section is coming soon", Toast.LENGTH_SHORT).show(); false }
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isDncPage = destination.id == R.id.FirstFragment
            binding.addFab.visibility = if (isDncPage) View.VISIBLE else View.GONE
            binding.bottomNavigation.visibility = if (isDncPage) View.VISIBLE else View.GONE
            binding.toolbar.navigationIcon = if (isDncPage) null else getDrawable(R.drawable.ic_back)
            binding.toolbar.title = if (isDncPage) getString(R.string.app_name) else getString(R.string.second_fragment_label)
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
