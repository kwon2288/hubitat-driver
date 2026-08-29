/**
 * Homey Generic Device (Child Driver)
 *
 * Generic bridge driver for devices imported from Homey Pro via the
 * "Homey Bridge" parent app. Declares a broad capability set and maps
 * the common Homey capabilities it recognizes to matching Hubitat
 * attributes. Unmapped Homey capabilities are logged but ignored -
 * extend the switch statement in updateFromHomey() as needed.
 *
 * Author: kwon2288
 */
metadata {
    definition(name: "Homey Generic Device", namespace: "kwon2288", author: "kwon2288") {
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "IlluminanceMeasurement"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "MotionSensor"
        capability "ContactSensor"
        capability "PresenceSensor"
        capability "Battery"
        capability "WindowShade"
        capability "PushableButton"
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
    sendEvent(name: "numberOfButtons", value: 1)
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

def open() {
    parent?.sendCommand(device.deviceNetworkId, "windowcoverings_state", "up")
}

def close() {
    parent?.sendCommand(device.deviceNetworkId, "windowcoverings_state", "down")
}

def setPosition(position) {
    parent?.sendCommand(device.deviceNetworkId, "windowcoverings_set", (position as Integer) / 100.0)
}

def push(buttonNumber = 1) {
    // Homey's "button" system capability is a stateless boolean trigger.
    parent?.sendCommand(device.deviceNetworkId, "button", true)
    sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
}

def refresh() {
    if (logDebug) log.debug "Homey Generic Device: refresh() called, parent=${parent}"
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
            case "measure_temperature":
                sendEvent(name: "temperature", value: v, unit: "\u00b0C")
                break
            case "measure_humidity":
                sendEvent(name: "humidity", value: v, unit: "%")
                break
            case "measure_luminance":
                sendEvent(name: "illuminance", value: v, unit: "lux")
                break
            case "measure_power":
                sendEvent(name: "power", value: v, unit: "W")
                break
            case "meter_power":
                sendEvent(name: "energy", value: v, unit: "kWh")
                break
            case "alarm_motion":
                sendEvent(name: "motion", value: v ? "active" : "inactive")
                break
            case "alarm_contact":
                sendEvent(name: "contact", value: v ? "open" : "closed")
                break
            case "alarm_generic":
                sendEvent(name: "presence", value: v ? "present" : "not present")
                break
            case "measure_battery":
                sendEvent(name: "battery", value: v, unit: "%")
                break
            case "button":
                if (v) sendEvent(name: "pushed", value: 1, isStateChange: true)
                break
            case "windowcoverings_set":
                def pct = Math.round((v as Double) * 100)
                sendEvent(name: "position", value: pct)
                sendEvent(name: "windowShade", value: pct >= 95 ? "open" : (pct <= 5 ? "closed" : "partially open"))
                break
            default:
                if (logDebug) log.debug "Unmapped Homey capability ${capId} = ${v}"
                break
        }
    }
}
