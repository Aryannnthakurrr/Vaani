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

/** One geocoder match: where it is and the city or district it is in. */
data class PlaceCandidate(val at: LatLng, val area: String?)

/** Finds a named place near the user, or null (no permission, no position, no match, too slow). */
fun interface PlaceLookup {
    suspend fun find(name: String): LatLng?

    /** Every nearby match, so "Apollo Hospital" in Delhi and in Faridabad can be told apart. */
    suspend fun candidates(name: String): List<PlaceCandidate> = find(name)?.let { listOf(PlaceCandidate(it, null)) }.orEmpty()
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
        /** The platform geocoder is a network call; 3 s often wasn't enough, and then the cab opened on Uber's home. */
        const val TIMEOUT_MS = 7_000L
        const val MAX_RESULTS = 5
        /** Roughly India, for when the phone has no position at all: still finds "India Gate", not one abroad. */
        val INDIA = doubleArrayOf(6.0, 68.0, 36.0, 98.0)
    }

    override suspend fun find(name: String): LatLng? = candidates(name).firstOrNull()?.at

    /**
     * Nearby matches first. Without a last-known position (common when no app asked for one recently) a fresh fix is
     * requested; with none at all, the search covers India. Nearby finding nothing (an odd spelling) also widens to
     * India, so the deep link still lands on Uber's ride list instead of its home screen.
     */
    override suspend fun candidates(name: String): List<PlaceCandidate> {
        if (!Geocoder.isPresent()) return emptyList()
        return withTimeoutOrNull(TIMEOUT_MS) {
            val here = (DeviceLocation.lastKnown(context) ?: DeviceLocation.current(context, timeoutMs = 2_500L))?.at
            val near = here?.let { geocode(name, NearbyBox.around(it)) }.orEmpty()
            near.ifEmpty { geocode(name, INDIA) }
        }.orEmpty()
    }

    private suspend fun geocode(name: String, box: DoubleArray): List<PlaceCandidate> {
        val geocoder = Geocoder(context, Locale("en", "IN"))
        val found: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(name, MAX_RESULTS, box[0], box[1], box[2], box[3], object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.toList())
                    override fun onError(errorMessage: String?) = cont.resume(emptyList())
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocationName(name, MAX_RESULTS, box[0], box[1], box[2], box[3]) }.getOrNull().orEmpty()
            }
        }
        return found.filter { it.hasLatitude() && it.hasLongitude() }
            .map { PlaceCandidate(LatLng(it.latitude, it.longitude), it.locality ?: it.subAdminArea ?: it.adminArea) }
    }
}

/** A position fix and how far off it may be. */
data class Fix(val at: LatLng, val accuracyM: Float?)

/** The phone's own position, from whichever location permission Saathi holds. */
object DeviceLocation {
    private fun allowed(context: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Newest last-known fix from any enabled provider, or null without permission or any fix. */
    @SuppressLint("MissingPermission") // checked by allowed()
    fun lastKnown(context: Context): Fix? {
        if (!allowed(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        // Coarse permission can't read the GPS provider; runCatching skips it.
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { Fix(LatLng(it.latitude, it.longitude), if (it.hasAccuracy()) it.accuracy else null) }
    }

    /**
     * A fresh fix for an emergency: asks the providers for the current position (up to [timeoutMs]); a last-known
     * fix is used only if it is under 2 minutes old or nothing fresh arrives in time.
     */
    @SuppressLint("MissingPermission") // checked by allowed()
    suspend fun current(context: Context, timeoutMs: Long = 6_000L): Fix? {
        if (!allowed(context)) return null
        val last = lastKnown(context)
        val lm = context.getSystemService(LocationManager::class.java) ?: return last
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last // getCurrentLocation needs Android 11
        lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 120_000 }?.let { return last }
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        // Coarse permission can't use GPS, so it starts with the network provider.
        val provider = (if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER))
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return last
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                val cancel = android.os.CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching { lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) } }
                    .onFailure { cont.resume(null) }
            }
        }
        return fresh?.let { Fix(LatLng(it.latitude, it.longitude), if (it.hasAccuracy()) it.accuracy else null) } ?: last
    }

    /** A readable street address for [at], or null (no geocoder, no match, slower than 3 s). */
    suspend fun address(context: Context, at: LatLng): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale("en", "IN"))
        val found: Address? = withTimeoutOrNull(3_000L) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(at.lat, at.lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.firstOrNull())
                        override fun onError(errorMessage: String?) = cont.resume(null)
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(at.lat, at.lng, 1) }.getOrNull()?.firstOrNull()
                }
            }
        }
        return found?.getAddressLine(0)
    }
}
