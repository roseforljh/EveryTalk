package com.android.everytalk.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object DynamicIconSwitcher {

    internal const val WHITE_ALIAS = "com.android.everytalk.LauncherWhite"
    internal const val LIGHT_ALIAS = "com.android.everytalk.LauncherLight"
    internal const val DARK_ALIAS = "com.android.everytalk.LauncherDark"

    internal fun aliasToEnable(isDarkTheme: Boolean): String = WHITE_ALIAS

    internal fun aliasesToDisable(isDarkTheme: Boolean): List<String> = listOf(LIGHT_ALIAS, DARK_ALIAS)

    fun syncIcon(context: Context) {
        val pm = context.packageManager

        val enableAlias = aliasToEnable(isDarkTheme = false)
        val disableAliases = aliasesToDisable(isDarkTheme = false)

        val enableComponent = ComponentName(context, enableAlias)

        val currentEnable = pm.getComponentEnabledSetting(enableComponent)

        if (currentEnable != PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
            currentEnable != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        ) {
            pm.setComponentEnabledSetting(
                enableComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        disableAliases.forEach { disableAlias ->
            val disableComponent = ComponentName(context, disableAlias)
            val currentDisable = pm.getComponentEnabledSetting(disableComponent)
            if (currentDisable != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    disableComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
