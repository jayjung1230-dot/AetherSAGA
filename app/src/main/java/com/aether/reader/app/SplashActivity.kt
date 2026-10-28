package com.aether.reader.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ImageView
import com.aether.reader.R

/**
 * Opening Splash Activity displaying user's retro E-Reader Quest pixel art opening image.
 */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val iv = ImageView(this).apply {
            setImageResource(R.drawable.splash_screen)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFF7F4EB.toInt()) // Warm paper off-white splash background matching Aether SAGA art
        }
        setContentView(iv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window.decorView.hideSystemBars()

        // Transition to library after 1.5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, LibraryActivity::class.java))
            finish()
            overridePendingTransition(0, 0)
        }, 1500L)
    }

    override fun onBackPressed() {
        // Prevent back press during opening splash
    }
}
