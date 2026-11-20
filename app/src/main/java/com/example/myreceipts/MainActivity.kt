package com.example.myreceipts

import android.os.Bundle
import android.view.View
import android.widget.ViewFlipper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        val screens = findViewById<ViewFlipper>(R.id.screens)
        ViewCompat.setOnApplyWindowInsetsListener(screens) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, 0)
            insets
        }

        val welcome = screens.getChildAt(0)
        val receiptOnboarding = screens.getChildAt(1)
        val analyticsOnboarding = screens.getChildAt(2)

        welcome.findViewById<View>(R.id.btnCreateAccount)?.setOnClickListener {
            screens.displayedChild = 1
        }
        welcome.findViewById<View>(R.id.btnHaveAccount)?.setOnClickListener {
            screens.displayedChild = 1
        }
        receiptOnboarding.findViewById<View>(R.id.btnNext)?.setOnClickListener {
            screens.displayedChild = 2
        }
        receiptOnboarding.findViewById<View>(R.id.btnSkip)?.setOnClickListener {
            screens.displayedChild = 2
        }
        analyticsOnboarding.findViewById<View>(R.id.btnNext)?.setOnClickListener {
            screens.displayedChild = 0
        }
        analyticsOnboarding.findViewById<View>(R.id.btnSkip)?.setOnClickListener {
            screens.displayedChild = 0
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val screens = findViewById<ViewFlipper>(R.id.screens)
        if (screens.displayedChild > 0) {
            screens.displayedChild--
        } else {
            super.onBackPressed()
        }
    }
}