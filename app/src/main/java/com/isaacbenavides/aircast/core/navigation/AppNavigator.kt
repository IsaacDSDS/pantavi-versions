package com.isaacbenavides.aircast.core.navigation

import android.content.Context

/** Opens screens that belong to other features, so features never import each other. */
interface AppNavigator {
    /** With [limitReached], the Pro screen says the free session just ran out. */
    fun openPro(context: Context, limitReached: Boolean = false)

    fun openLicenses(context: Context)
}
