package com.isaacbenavides.aircast.core.di

import android.content.Context
import android.content.Intent
import com.isaacbenavides.aircast.core.navigation.AppNavigator
import com.isaacbenavides.aircast.features.about.presentation.LicensesActivity
import com.isaacbenavides.aircast.features.pro.presentation.ProActivity

internal class ActivityNavigator : AppNavigator {

    override fun openPro(context: Context, limitReached: Boolean) = context.startActivity(
        Intent(context, ProActivity::class.java).putExtra(ProActivity.EXTRA_LIMIT_REACHED, limitReached),
    )

    override fun openLicenses(context: Context) =
        context.startActivity(Intent(context, LicensesActivity::class.java))
}
