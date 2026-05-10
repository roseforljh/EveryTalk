package com.android.everytalk.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DynamicIconSwitcher {

    private const val LIGHT_ALIAS = "com.android.everytalk.LauncherLight"
    private const val DARK_ALIAS = "com.android.everytalk.LauncherDark"

    fun syncIcon(context: Context) {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val pm = context.packageManager

        val enableAlias = if (isDark) DARK_ALIAS else LIGHT_ALIAS
        val disableAlias = if (isDark) LIGHT_ALIAS else DARK_ALIAS

        val enableComponent = ComponentName(context, enableAlias)
        val disableComponent = ComponentName(context, disableAlias)

        val currentEnable = pm.getComponentEnabledSetting(enableComponent)
        if (currentEnable == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return

        pm.setComponentEnabledSetting(
            enableComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            disableComponent,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
