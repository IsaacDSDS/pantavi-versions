package com.isaacbenavides.aircast.features.about.presentation

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RawRes
import androidx.core.content.ContextCompat
import com.isaacbenavides.aircast.BuildConfig
import com.isaacbenavides.aircast.R
import com.isaacbenavides.aircast.databinding.ActivityLicensesBinding

/** GPL-3 "Appropriate Legal Notices": copyright, no warranty, source code, trademark, licenses. */
class LicensesActivity : ComponentActivity() {

    private lateinit var binding: ActivityLicensesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.licensesVersion.text = getString(R.string.licenses_version, BuildConfig.VERSION_NAME)
        binding.licensesSource.text = getString(R.string.licenses_source, BuildConfig.SOURCE_CODE_URL)
        binding.licensesComponents.text = readRaw(R.raw.third_party_notices)
        LICENSE_TEXTS.forEach { (title, raw) -> addLicense(title, raw) }
        binding.licensesScroll.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> View.FOCUS_DOWN
            else -> return super.onKeyDown(keyCode, event)
        }
        binding.licensesScroll.pageScroll(direction)
        return true
    }

    private fun addLicense(title: String, @RawRes raw: Int) {
        val heading = TextView(this).apply {
            text = title
            setTextAppearance(R.style.TextAppearance_AirCast_Label)
            setPadding(0, resources.getDimensionPixelSize(R.dimen.space_l), 0, resources.getDimensionPixelSize(R.dimen.space_s))
        }
        val body = TextView(this).apply {
            text = readRaw(raw)
            setTextAppearance(R.style.TextAppearance_AirCast_Caption)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        binding.licensesContent.addView(heading, params)
        binding.licensesContent.addView(body, params)
    }

    private fun readRaw(@RawRes raw: Int): String =
        resources.openRawResource(raw).bufferedReader().use { it.readText() }

    private companion object {
        /** Official license texts, untranslated. */
        val LICENSE_TEXTS = listOf(
            "GNU General Public License v3.0" to R.raw.license_gpl_3_0,
            "GNU Lesser General Public License v2.1" to R.raw.license_lgpl_2_1,
            "Apache License 2.0" to R.raw.license_apache_2_0,
            "MIT License" to R.raw.license_mit,
        )
    }
}
