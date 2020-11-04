package com.csi.arsample.ext

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Build.VERSION_CODES


private const val MIN_OPENGL_VERSION = 3.0

fun Activity.checkIsSupportedDevice(): Boolean {
    if (Build.VERSION.SDK_INT < VERSION_CODES.N) {
        return false
    }
    val openGlVersionString =
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .deviceConfigurationInfo
            .glEsVersion

    if (openGlVersionString.toDouble() < MIN_OPENGL_VERSION) {
        return false
    }
    return true
}