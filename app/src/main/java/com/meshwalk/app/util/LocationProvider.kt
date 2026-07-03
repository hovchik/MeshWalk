package com.meshwalk.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A captured position for sharing into a conversation or SOS broadcast. */
data class SharedPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?
)

/**
 * Thin wrapper over the platform LocationManager (no Play Services dependency).
 * Returns the freshest last-known fix across providers, or null when location
 * permission is missing or no provider has a fix yet.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getLastKnownPosition(): SharedPosition? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Location permission not granted, cannot share position")
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            lm.allProviders
                .mapNotNull { provider ->
                    try {
                        lm.getLastKnownLocation(provider)
                    } catch (_: SecurityException) {
                        null
                    }
                }
                .maxByOrNull { it.time }
                ?.let { loc ->
                    SharedPosition(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null
                    )
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read last known location")
            null
        }
    }
}
