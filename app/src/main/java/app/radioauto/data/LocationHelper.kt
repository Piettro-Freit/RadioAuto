package app.radioauto.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Localização aproximada (rede/Wi-Fi), sem depender dos serviços do Google. */
object LocationHelper {

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val last: Location? = runCatching {
            lm.getProviders(true)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
        val lastPair = last?.let { it.latitude to it.longitude }

        // Uma posição de até 6 horas atrás serve: o interesse é a região, não o ponto exato.
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3600_000L) return lastPair

        val provider = LocationManager.NETWORK_PROVIDER
        if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) return lastPair

        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= 30) {
                        lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { loc ->
                            if (cont.isActive) cont.resume(loc?.let { it.latitude to it.longitude })
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(provider, object : LocationListener {
                            override fun onLocationChanged(l: Location) {
                                if (cont.isActive) cont.resume(l.latitude to l.longitude)
                            }
                        }, Looper.getMainLooper())
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        return fresh ?: lastPair
    }
}
