package io.github.genneth.pupil

import android.app.Application
import com.google.android.material.color.DynamicColors

/** Applies the device's Material You dynamic colour palette to every activity (Android 12+). */
class PupilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
