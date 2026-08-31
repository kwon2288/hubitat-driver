/**
 * Homey Switch (Child Driver)
 *
 * Minimal driver for plain on/off Homey devices (plugs, wall switches).
 * Dimmable or color/color-temperature devices use the separate
 * "Homey Dimmer" driver instead.
 *
 * Author: kwon2288
 */
metadata {
    definition(name: "Homey Switch", namespace: "kwon2288", author: "kwon2288") {
        capability "Switch"
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

def refresh() {
    if (logDebug) log.debug "Homey Switch: refresh() called, parent=${parent}"
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
            default:
                if (logDebug) log.debug "Unmapped Homey capability ${capId} = ${v}"
                break
        }
    }
}
