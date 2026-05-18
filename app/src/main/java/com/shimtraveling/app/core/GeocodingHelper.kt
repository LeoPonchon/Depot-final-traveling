package com.shimtraveling.core

import android.content.Context
import android.os.Build
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.coroutines.resume

object GeocodingHelper {

    data class AddressResult(
        val fullAddress: String?,
        val city: String?
    )


    suspend fun getAddressInfo(context: Context, latitude: Double, longitude: Double): AddressResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(result: MutableList<Address>) {
                            continuation.resume(result)
                        }

                        override fun onError(errorMessage: String?) {
                            continuation.resume(emptyList())
                        }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1)
            }

            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val fullAddress = (0..address.maxAddressLineIndex).joinToString(", ") { address.getAddressLine(it) }
                val city = address.locality ?: address.subAdminArea ?: address.adminArea
                AddressResult(fullAddress, city)
            } else {
                AddressResult(null, null)
            }
        } catch (e: Exception) {
            AddressResult(null, null)
        }
    }


    suspend fun getCityFromLocation(context: Context, latitude: Double, longitude: Double): String? {
        return getAddressInfo(context, latitude, longitude).city
    }
}
