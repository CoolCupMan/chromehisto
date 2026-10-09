package com.chromehisto.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.time.Instant
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Live device, network and location facts. Used for the export context and,
 * through the JavaScript bridge, for every link opened from a report.
 *
 * Everything reported here is measured at the moment of the call; nothing is
 * back-dated to the time of a past history visit.
 */
class NativeContext(private val ctx: Context) : LocationListener {

    @Volatile private var freshest: Location? = null
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasLocationPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Keep a fresh fix while the app is in the foreground. */
    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (!hasLocationPermission()) return
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 5000L, 0f, this, Looper.getMainLooper())
            } catch (e: Exception) { /* provider missing or permission revoked */ }
        }
    }

    fun stopLocation() {
        try { lm.removeUpdates(this) } catch (e: Exception) { }
    }

    override fun onLocationChanged(location: Location) {
        val cur = freshest
        if (cur == null || location.time >= cur.time) freshest = location
    }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    fun snapshot(): JSONObject {
        val now = Instant.now()
        val o = JSONObject()
        o.put("captured_at_utc", HistorySources.iso(now))
        o.put("captured_at_local", ZonedDateTime.now().toString())
        o.put("timezone", TimeZone.getDefault().id)
        o.put("utc_offset_minutes", TimeZone.getDefault().getOffset(now.toEpochMilli()) / 60000)
        o.put("device", safe { device() })
        o.put("wifi", safe { wifi() })
        o.put("active_network", safe { network() })
        o.put("cellular", safe { cellular() })
        o.put("ip_addresses", safe { ips() })
        o.put("location", safe { location() })
        o.put("battery", safe { battery() })
        return o
    }

    private fun safe(f: () -> Any): Any = try { f() } catch (e: Throwable) { "unavailable: $e" }

    private fun device(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("brand", Build.BRAND)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("product", Build.PRODUCT)
        .put("hardware", Build.HARDWARE)
        .put("device_name", Settings.Global.getString(ctx.contentResolver, "device_name") ?: JSONObject.NULL)
        .put("android_version", Build.VERSION.RELEASE)
        .put("sdk_int", Build.VERSION.SDK_INT)
        .put("security_patch", Build.VERSION.SECURITY_PATCH)
        .put("build_fingerprint", Build.FINGERPRINT)
        .put("android_id", Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: JSONObject.NULL)
        .put("android_user_id", Process.myUid() / 100000)
        .put("uptime_ms", SystemClock.elapsedRealtime())

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun wifi(): JSONObject {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val o = JSONObject().put("wifi_enabled", wm.isWifiEnabled)
        val info = wm.connectionInfo ?: return o.put("connected", false)
        if (info.networkId == -1 && info.rssi <= -127) return o.put("connected", false)
        o.put("connected", true)
        o.put("rssi_dbm", info.rssi)
        o.put("signal_level_0_4", WifiManager.calculateSignalLevel(info.rssi, 5))
        o.put("ssid", info.ssid ?: JSONObject.NULL)
        o.put("bssid", info.bssid ?: JSONObject.NULL)
        o.put("frequency_mhz", info.frequency)
        o.put("link_speed_mbps", info.linkSpeed)
        if (Build.VERSION.SDK_INT >= 29) {
            o.put("tx_link_speed_mbps", info.txLinkSpeedMbps)
            o.put("rx_link_speed_mbps", info.rxLinkSpeedMbps)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            o.put("wifi_standard", when (info.wifiStandard) {
                1 -> "legacy (a/b/g)"; 4 -> "802.11n (Wi-Fi 4)"; 5 -> "802.11ac (Wi-Fi 5)"
                6 -> "802.11ax (Wi-Fi 6/6E)"; 7 -> "802.11ad"; 8 -> "802.11be (Wi-Fi 7)"
                else -> "unknown(${info.wifiStandard})"
            })
            o.put("max_supported_tx_mbps", info.maxSupportedTxLinkSpeedMbps)
        }
        if (Build.VERSION.SDK_INT >= 31) o.put("current_security_type", info.currentSecurityType)
        o.put("hidden_ssid", info.hiddenSSID)
        if (info.ssid == WifiManager.UNKNOWN_SSID) {
            o.put("note", "SSID/BSSID hidden by Android: grant location permission and turn on location")
        }
        return o
    }

    private fun network(): JSONObject {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return JSONObject().put("connected", false)
        val caps = cm.getNetworkCapabilities(net) ?: return JSONObject().put("connected", true)
        val transports = JSONArray()
        mapOf(NetworkCapabilities.TRANSPORT_WIFI to "WIFI", NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET", NetworkCapabilities.TRANSPORT_VPN to "VPN",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "BLUETOOTH").forEach { (t, n) ->
            if (caps.hasTransport(t)) transports.put(n)
        }
        val o = JSONObject().put("connected", true).put("transports", transports)
            .put("downstream_kbps_estimate", caps.linkDownstreamBandwidthKbps)
            .put("upstream_kbps_estimate", caps.linkUpstreamBandwidthKbps)
            .put("validated_internet", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        if (Build.VERSION.SDK_INT >= 29) o.put("signal_strength", caps.signalStrength)
        cm.getLinkProperties(net)?.let { lp ->
            o.put("interface", lp.interfaceName ?: JSONObject.NULL)
            o.put("dns_servers", JSONArray(lp.dnsServers.map { it.hostAddress }))
            o.put("link_addresses", JSONArray(lp.linkAddresses.map { it.toString() }))
            if (Build.VERSION.SDK_INT >= 28) o.put("private_dns", lp.privateDnsServerName ?: (if (lp.isPrivateDnsActive) "automatic" else "off"))
        }
        return o
    }

    @SuppressLint("MissingPermission")
    private fun cellular(): JSONObject {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val o = JSONObject()
            .put("network_operator", tm.networkOperatorName)
            .put("network_operator_mcc_mnc", tm.networkOperator)
            .put("network_country", tm.networkCountryIso)
            .put("sim_operator", tm.simOperatorName)
            .put("roaming", tm.isNetworkRoaming)
        if (Build.VERSION.SDK_INT >= 28) {
            tm.signalStrength?.let { ss ->
                o.put("signal_level_0_4", ss.level)
                if (Build.VERSION.SDK_INT >= 29) {
                    o.put("signals", JSONArray(ss.cellSignalStrengths.map {
                        JSONObject().put("type", it.javaClass.simpleName)
                            .put("dbm", it.dbm).put("asu", it.asuLevel).put("level", it.level)
                            .put("detail", it.toString())
                    }))
                }
            }
        }
        try {
            o.put("data_network_type", tm.dataNetworkType)
        } catch (e: SecurityException) {
            o.put("data_network_type", "needs phone permission")
        }
        return o
    }

    private fun ips(): JSONArray {
        val arr = JSONArray()
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses) arr.put("${ni.name}: ${a.hostAddress}")
        }
        return arr
    }

    @SuppressLint("MissingPermission")
    private fun location(): Any {
        if (!hasLocationPermission()) return "location permission not granted"
        val cands = mutableListOf<Location>()
        freshest?.let { cands.add(it) }
        for (p in lm.getProviders(true)) {
            try { lm.getLastKnownLocation(p)?.let { cands.add(it) } } catch (e: Exception) { }
        }
        val best = cands.maxByOrNull { it.time } ?: return "no location fix available (is location turned on?)"
        val ageS = (System.currentTimeMillis() - best.time) / 1000.0
        val o = JSONObject()
            .put("latitude", best.latitude)
            .put("longitude", best.longitude)
            .put("accuracy_m", if (best.hasAccuracy()) best.accuracy.toDouble() else JSONObject.NULL)
            .put("altitude_m", if (best.hasAltitude()) best.altitude else JSONObject.NULL)
            .put("speed_mps", if (best.hasSpeed()) best.speed.toDouble() else JSONObject.NULL)
            .put("bearing_deg", if (best.hasBearing()) best.bearing.toDouble() else JSONObject.NULL)
            .put("provider", best.provider ?: JSONObject.NULL)
            .put("fix_time_utc", HistorySources.iso(Instant.ofEpochMilli(best.time)))
            .put("fix_age_seconds", ageS)
            .put("maps_link", "https://maps.google.com/?q=${best.latitude},${best.longitude}")
        if (Build.VERSION.SDK_INT >= 26 && best.hasVerticalAccuracy()) o.put("vertical_accuracy_m", best.verticalAccuracyMeters.toDouble())
        best.extras?.let { if (it.containsKey("satellites")) o.put("satellites_used", it.getInt("satellites")) }
        @Suppress("DEPRECATION")
        o.put("mock_location", if (Build.VERSION.SDK_INT >= 31) best.isMock else best.isFromMockProvider)
        return o
    }

    private fun battery(): JSONObject {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return JSONObject().put("available", false)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return JSONObject()
            .put("level_percent", if (level >= 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"; else -> "no"
            })
            .put("temperature_c", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            .put("voltage_mv", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
    }
}
