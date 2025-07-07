package com.example.gottagorun

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import coil.load
import com.example.gottagorun.databinding.ActivityHomeBinding
import com.google.firebase.auth.FirebaseAuth

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This is the core of the edge-to-edge implementation.
        // It must be called BEFORE setContentView().
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // This listener dynamically adds padding to our toolbars to account for
        // the system status bar and navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.topToolbar.updatePadding(top = systemBarInsets.top)
            binding.bottomNavBar.updatePadding(bottom = systemBarInsets.bottom)

            insets
        }

        loadProfilePicture()
        setupTopToolbarListeners()
        setupBottomNavListeners()
    }

    private fun loadProfilePicture() {
        val user = auth.currentUser
        if (user?.photoUrl != null) {
            binding.profileImage.load(user.photoUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_person)
                error(R.drawable.ic_person)
            }
        } else {
            binding.profileImage.setImageResource(R.drawable.ic_person)
        }
    }

    private fun setupTopToolbarListeners() {
        binding.profileImage.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        binding.buttonShare.setOnClickListener {
            Toast.makeText(this, "Share feature coming soon!", Toast.LENGTH_SHORT).show()
        }
        binding.buttonNotifications.setOnClickListener {
            Toast.makeText(this, "Notifications coming soon!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNavListeners() {
        binding.buttonNavHome.setOnClickListener {
            // Already on the home screen
        }

        binding.buttonNavRecord.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        binding.buttonNavHistory.setOnClickListener {
            startActivity(Intent(this, RunHistoryActivity::class.java))
        }

        binding.buttonNavSocial.setOnClickListener {
            Toast.makeText(this, "Social features coming soon!", Toast.LENGTH_SHORT).show()
        }

        binding.buttonNavProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }
}
