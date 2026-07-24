package com.android.everytalk.ui.components.syntax.languages
import com.android.everytalk.statecontroller.*

import java.util.regex.Matcher

internal fun Matcher.groupText(group: Int = 0): String = group(group) ?: ""
