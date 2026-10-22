package com.assistant.core.services

/** Connectivity observations are local measurements, never permission to change a radio or VPN. */
object ConnectionAssistance {
    const val MANAGER_PACKAGE = "com.jarvis.connectionmanager"
    enum class Kind { ALL, WIFI, BLUETOOTH, CELLULAR, VPN, WIFI_SETTINGS, BLUETOOTH_SETTINGS, CELLULAR_SETTINGS, VPN_SETTINGS, MANAGER, HELP, INVALID }
    enum class State { ON, OFF, TRANSITIONING, PERMISSION, UNSUPPORTED, UNAVAILABLE }
    interface Platform {
        fun wifi(): State
        fun bluetooth(): State
        fun cellular(): State
        fun vpn(): State
        fun network(): String
        fun settings(kind: Kind): Boolean
        fun manager(): String
    }
    fun parse(text: String): Kind? {
        val value = ScreenSemantics.clean(text).trimEnd('.', '?', '!').removePrefix("please ").removePrefix("can you ")
        return when (value) {
            "connection status", "connectivity status", "connection diagnostics", "connectivity diagnostics", "check my connections" -> Kind.ALL
            "wifi status", "wi-fi status", "check wifi", "check wi-fi" -> Kind.WIFI
            "bluetooth status", "check bluetooth" -> Kind.BLUETOOTH
            "cellular status", "mobile data status", "check mobile data" -> Kind.CELLULAR
            "vpn status", "check vpn" -> Kind.VPN
            "wifi settings", "wi-fi settings", "open wifi settings", "open wi-fi settings", "turn on wifi", "turn off wifi", "turn on wi-fi", "turn off wi-fi" -> Kind.WIFI_SETTINGS
            "bluetooth settings", "open bluetooth settings", "turn on bluetooth", "turn off bluetooth" -> Kind.BLUETOOTH_SETTINGS
            "cellular settings", "mobile data settings", "open mobile data settings", "turn on mobile data", "turn off mobile data" -> Kind.CELLULAR_SETTINGS
            "vpn settings", "open vpn settings", "disconnect vpn", "connect vpn" -> Kind.VPN_SETTINGS
            "connection manager", "open connection manager", "open jarvis connection manager", "find jarvis connection manager" -> Kind.MANAGER
            "connection help", "connectivity help" -> Kind.HELP
            else -> if (Regex("^(?:connection|connectivity) (?:status|diagnostics|manager|help)\\b|^(?:wifi|wi-fi|bluetooth|cellular|mobile data|vpn) (?:status|settings)\\b").containsMatchIn(value)) Kind.INVALID else null
        }
    }
    private fun read(sample: () -> State) = try { sample() } catch (_: SecurityException) { State.PERMISSION } catch (_: Exception) { State.UNAVAILABLE }
    private fun describe(value: State, name: String) = when (value) {
        State.ON -> if (name == "Cellular data") "Android reports data connected; this does not verify a particular app or internet endpoint."
            else if (name == "VPN") "Android reports a VPN transport visible to Jarvis; tunnel health and all traffic coverage are unverified."
            else "radio enabled; connection, pairing and internet access are separate checks."
        State.OFF -> if (name == "VPN") "no VPN transport is visible to Jarvis; another profile or work-profile tunnel may be outside its view."
            else if (name == "Cellular data") "Android reports data disconnected; this does not identify the cause or prove the radio is disabled."
            else "radio disabled."
        State.TRANSITIONING -> "Android reports a transition; check its system settings."
        State.PERMISSION -> "permission unavailable; review Android app permissions or use system settings. Jarvis has not granted access."
        State.UNSUPPORTED -> "this device or Android version does not expose this capability."
        State.UNAVAILABLE -> "Android could not supply a reliable measurement."
    }
    fun reply(kind: Kind, platform: Platform): String {
        if (kind in setOf(Kind.HELP, Kind.INVALID)) return "Say 'connection status', 'Wi-Fi status', 'Bluetooth status', 'mobile data status', 'VPN status', or 'open Jarvis Connection Manager'. To make a change, ask for the corresponding settings and approve it in Android. Use one request at a time."
        if (kind == Kind.MANAGER) return runCatching { platform.manager() }.getOrElse { "The separate Connection Manager could not be discovered. Check that it is installed and enabled, then find the installed app by its launcher name." }
        if (kind in setOf(Kind.WIFI_SETTINGS, Kind.BLUETOOTH_SETTINGS, Kind.CELLULAR_SETTINGS, Kind.VPN_SETTINGS)) {
            val name = when (kind) { Kind.WIFI_SETTINGS -> "Wi-Fi"; Kind.BLUETOOTH_SETTINGS -> "Bluetooth"; Kind.CELLULAR_SETTINGS -> "mobile data"; else -> "VPN" }
            return if (runCatching { platform.settings(kind) }.getOrDefault(false))
                "Opened Android $name settings. Review and approve any change there. Jarvis has not changed a radio, paired a device, selected a SIM or altered a VPN."
            else "Android $name settings could not be opened. Open Settings manually and select $name; no change was made."
        }
        return buildList {
            if (kind == Kind.ALL || kind == Kind.WIFI) add("Wi-Fi: " + describe(read(platform::wifi), "Wi-Fi"))
            if (kind == Kind.ALL || kind == Kind.BLUETOOTH) add("Bluetooth: " + describe(read(platform::bluetooth), "Bluetooth"))
            if (kind == Kind.ALL || kind == Kind.CELLULAR) add("Cellular data: " + describe(read(platform::cellular), "Cellular data"))
            if (kind == Kind.ALL || kind == Kind.VPN) add("VPN: " + describe(read(platform::vpn), "VPN"))
            if (kind == Kind.ALL) add(runCatching { platform.network() }.getOrDefault("Default network measurement unavailable."))
            add("These checks run locally without an internet probe, scan or device identifiers. Ask for the relevant settings to troubleshoot.")
        }.joinToString("\n")
    }
}
