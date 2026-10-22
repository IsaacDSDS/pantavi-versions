package com.isaacbenavides.aircast.features.pro.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.isaacbenavides.aircast.R
import com.isaacbenavides.aircast.core.entitlement.Entitlement
import com.isaacbenavides.aircast.core.entitlement.PRO_KEY_PACKAGE
import com.isaacbenavides.aircast.databinding.ActivityProBinding
import kotlinx.coroutines.launch

/** What Pro adds, and the way to the Pantavi Pro key app on Google Play. */
class ProActivity : ComponentActivity() {

    private val viewModel: ProViewModel by viewModels { ProViewModel.Factory }
    private lateinit var binding: ActivityProBinding
    private var limitReached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProBinding.inflate(layoutInflater)
        setContentView(binding.root)
        limitReached = intent.getBooleanExtra(EXTRA_LIMIT_REACHED, false)
        binding.proGetButton.setOnClickListener { openStore() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.entitlement.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh() // back from Google Play
    }

    private fun render(plan: Entitlement) {
        val pro = plan == Entitlement.PRO
        val limitShown = limitReached && !pro
        binding.proTitle.setText(
            when {
                pro -> R.string.pro_active_title
                limitShown -> R.string.pro_limit_title
                else -> R.string.pro_title
            },
        )
        binding.proSubtitle.setText(if (limitShown) R.string.pro_limit_subtitle else R.string.pro_subtitle)
        binding.proGetButton.isVisible = !pro
        binding.proNote.setText(if (pro) R.string.pro_active_text else R.string.pro_how)
        if (!pro) binding.proGetButton.requestFocus()
    }

    private fun openStore() {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PRO_KEY_PACKAGE"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PRO_KEY_PACKAGE"))
        runCatching { startActivity(app) }
            .recoverCatching { startActivity(web) }
            .onFailure { binding.proNote.setText(R.string.pro_store_error) }
    }

    companion object {
        const val EXTRA_LIMIT_REACHED = "limit_reached"
    }
}
