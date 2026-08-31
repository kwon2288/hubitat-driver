/**
 * Homey Dimmer (Child Driver)
 *
 * Dedicated driver for dimmable and color/color-temperature Homey
 * devices. Pairs Switch with SwitchLevel/ColorControl/ColorTemperature
 * since a dimmable/color light still needs its own on/off control.
 * Plain on/off-only devices use the separate, minimal "Homey Switch"
 * driver instead.
 *
 * Author: kwon2288
 */
metadata {
    definition(name: "Homey Dimmer", namespace: "kwon2288", author: "kwon2288") {
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "Refresh"
    }
    preferences {
        input name: "logDebug", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    // placeholder for future per-device setup
}

// ---------- Commands (Hubitat -> Homey) ----------

def on() {
    parent?.sendCommand(device.deviceNetworkId, "onoff", true)
}

def off() {
    parent?.sendCommand(device.deviceNetworkId, "onoff", false)
}

def setLevel(level, duration = null) {
    def v = (level as Integer) / 100.0
    parent?.sendCommand(device.deviceNetworkId, "dim", v)
}

def setColor(colorMap) {
    if (colorMap?.hue != null) {
        parent?.sendCommand(device.deviceNetworkId, "light_hue", (colorMap.hue as Integer) / 100.0)
    }
    if (colorMap?.saturation != null) {
        parent?.sendCommand(device.deviceNetworkId, "light_saturation", (colorMap.saturation as Integer) / 100.0)
    }
}

def setColorTemperature(temp, level = null, duration = null) {
    // Homey's light_temperature is 0 (cold) - 1 (warm); Hubitat uses Kelvin.
    // Rough linear mapping across a 2200K-6500K range - tune if your bulbs differ.
    def clamped = Math.max(2200, Math.min(6500, temp as Integer))
    def v = 1 - ((clamped - 2200) / (6500 - 2200))
    parent?.sendCommand(device.deviceNetworkId, "light_temperature", v)
}

def refresh() {
    if (logDebug) log.debug "Homey Dimmer: refresh() called, parent=${parent}"
    parent?.refreshDevice(device.deviceNetworkId)
}

// ---------- State sync (Homey -> Hubitat) ----------
// capsObj: Map of Homey capabilityId -> [value: ..., ...] (Homey device.capabilitiesObj)

def updateFromHomey(Map capsObj) {
    if (logDebug) log.debug "updateFromHomey received: ${capsObj}"
    capsObj.each { capId, capData ->
        def v = (capData instanceof Map) ? capData.value : capData
        if (v == null) return
        switch (capId) {
            case "onoff":
                sendEvent(name: "switch", value: v ? "on" : "off")
                break
            case "dim":
                sendEvent(name: "level", value: Math.round((v as Double) * 100))
                break
            default:
                if (logDebug) log.debug "Unmapped Homey capability ${capId} = ${v}"
                break
        }
    }
}
