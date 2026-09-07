package io.github.genneth.pupil

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** Runtime permissions that gate starting the broadcast service, checked by both surfaces. */
fun missingStartPermissions(context: Context): List<String> =
    arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS)
        .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
