package com.discord.evs.generator

import com.discord.evs.generator.update.UpdateManager

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.discord.evs.generator.locale.LocaleHelper
import com.discord.evs.generator.ui.UiMotion
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    private lateinit var binding: com.discord.evs.generator.databinding.ActivityMainBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = com.discord.evs.generator.databinding.ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController

        binding.bottomNav.setupWithNavController(navController)

        UpdateManager.attach(this)
        binding.bottomNav.setOnItemReselectedListener { _ ->
            UiMotion.press(binding.bottomNav)
        }
        // Keep bottom-nav highlight in sync (e.g. Settings → About → other tab)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            UiMotion.enter(binding.navHostFragment)
            val menu = binding.bottomNav.menu
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item.itemId == destination.id) {
                    item.isChecked = true
                    return@addOnDestinationChangedListener
                }
            }
            // About is under Settings — keep Settings selected
            if (destination.id == R.id.infoFragment || destination.id == R.id.changelogFragment) {
                menu.findItem(R.id.settingsFragment)?.isChecked = true
            }
        }
    }

    override fun onDestroy() {
        UpdateManager.detach()
        super.onDestroy()
    }
}
