package com.golftracer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash)
        
        // Show splash for 2 seconds then go to main
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(android.content.Intent(this, MainActivity::class.java))
            finish()
        }, 2000)
    }
}
