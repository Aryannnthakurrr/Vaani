package com.saathi.app.features.tasks

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class LatLng(val lat: Double, val lng: Double)

/** Finds a named place near the user, or null (no permission, no position, no match, too slow). */
fun interface PlaceLookup {
    suspend fun find(name: String): LatLng?
}

/** The search area around the user, so "Apollo Hospital" means the nearby one, not one in another city. */
object NearbyBox {
    /** ±0.3° is roughly 33 km: a city and its suburbs. */
    const val RADIUS_DEG = 0.3

    /** lowerLeftLat, lowerLeftLng, upperRightLat, upperRightLng, as [Geocoder] takes them. */
    fun around(p: LatLng, radius: Double = RADIUS_DEG) =
        doubleArrayOf(p.lat - radius, p.lng - radius, p.lat + radius, p.lng + radius)
}

/**
 * Android's Geocoder, limited to [NearbyBox] around the last known coarse position. Only the place name and the box
 * leave the phone (to the platform geocoder); nothing is logged.
 */
class AndroidPlaceFinder(private val context: Context) : PlaceLookup {
    private companion object {
        const val TIMEOUT_MS = 3_000L
    }

    override suspend fun find(name: String): LatLng? {
        if (!Geocoder.isPresent()) return null
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            val here = lastKnown() ?: return@withTimeoutOrNull null
            geocode(name, NearbyBox.around(here))
        }
    }

    @SuppressLint("MissingPermission") // checked in find()
    private fun lastKnown(): LatLng? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        // Coarse permission can't read the GPS provider; runCatching skips it.
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { LatLng(it.latitude, it.longitude) }
    }

    private suspend fun geocode(name: String, box: DoubleArray): LatLng? {
        val geocoder = Geocoder(context, Locale("en", "IN"))
        val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(name, 1, box[0], box[1], box[2], box[3], object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.firstOrNull())
                    override fun onError(errorMessage: String?) = cont.resume(null)
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocationName(name, 1, box[0], box[1], box[2], box[3]) }.getOrNull()?.firstOrNull()
            }
        }
        return address?.takeIf { it.hasLatitude() && it.hasLongitude() }?.let { LatLng(it.latitude, it.longitude) }
    }
}
