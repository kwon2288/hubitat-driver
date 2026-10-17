/**
 *  HOBEIAN ZG-210Z Pressure Sensing Strap / Bed & Seat Occupancy Sensor (with T&H)
 *
 *  Model        : ZG-210Z
 *  Manufacturer : _TZE200_ihizzpzn
 *
 *  Tuya DP map (ported from zigbee-herdsman-converters "ZG-210Z", HOBEIAN):
 *    DP   type   name                     notes
 *    1    enum   pressure_state           0 none / 1 sit / 2 sedentary
 *    2    value  current_pressure         raw sensing value
 *    15   value  battery                  %
 *    101  value  temperature              /10 °C
 *    102  value  humidity                 %
 *    103  value  temperature_calibration  /10 °C, -2.0..2.0          (read/write)
 *    104  value  humidity_calibration     %, -30..30                  (read/write)
 *    105  value  pressure_intensity       0..2000, trigger threshold  (read/write)
 *    106  value  presence_delay           0..3600 s, delay before "none" (read/write)
 *    107  value  sedentary_reminder       0..1440 min                 (read/write)
 *
 *  The device also reports standard ZCL Temperature (0402), Humidity (0405)
 *  and Power Configuration (0001) attributes; those are parsed too.
 *
 *  Tuya EF00 frame: seq(2) | dp(1) | type(1) | len(2) | data(len) [| next dp ...]
 *
 *  Author: kwon2288
 */

import groovy.transform.Field

@Field static final String VERSION = "1.0.0"

@Field static final int CLUSTER_TUYA = 0xEF00
@Field static final int TUYA_SET_DATA   = 0x00
@Field static final int TUYA_QUERY_DATA = 0x03
@Field static final int TUYA_TIME_SYNC  = 0x24

@Field static final int DP_TYPE_BOOL  = 0x01
@Field static final int DP_TYPE_VALUE = 0x02
@Field static final int DP_TYPE_ENUM  = 0x04

@Field static final Map<Integer, String> PRESSURE_STATE = [0: "none", 1: "sit", 2: "sedentary"]

metadata {
    definition(name: "HOBEIAN ZG-210Z Pressure Sensor", namespace: "kwon2288", author: "kwon2288") {
        capability "Sensor"
        capability "PresenceSensor"
        capability "MotionSensor"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Battery"
        capability "Refresh"
        capability "Configuration"

        attribute "pressureState", "enum", ["none", "sit", "sedentary"]
        attribute "currentPressure", "number"
        attribute "pressureIntensity", "number"
        attribute "presenceDelay", "number"
        attribute "sedentaryReminder", "number"
        attribute "temperatureCalibration", "number"
        attribute "humidityCalibration", "number"
        attribute "lastPresenceChange", "string"

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0500,0406,EF00,0402,0405,0001",
                    outClusters: "0003", model: "ZG-210Z", manufacturer: "_TZE200_ihizzpzn", controllerType: "ZGB",
                    deviceJoinName: "HOBEIAN ZG-210Z Pressure Sensor"
    }

    preferences {
        input name: "pressureIntensityPref", type: "number", title: "Pressure intensity threshold (0-2000)",
              description: "Sensing value at which the strap counts as occupied", range: "0..2000", required: false
        input name: "presenceDelayPref", type: "number", title: "Delay before reporting 'not present' (s, 0-3600)",
              range: "0..3600", required: false
        input name: "sedentaryReminderPref", type: "number", title: "Sedentary reminder time (min, 0-1440)",
              range: "0..1440", required: false
        input name: "tempCalPref", type: "decimal", title: "Temperature calibration (°C, -2.0 to 2.0)",
              range: "-2..2", required: false
        input name: "humCalPref", type: "number", title: "Humidity calibration (%, -30 to 30)",
              range: "-30..30", required: false
        input name: "sedentaryIsPresent", type: "bool", title: "Treat 'sedentary' as present",
              defaultValue: true
        input name: "motionMirror", type: "bool", title: "Mirror presence to motion attribute (active/inactive)",
              defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 min)", defaultValue: true
    }
}

/* ------------------------------------------------------------------ */
/* Lifecycle                                                          */
/* ------------------------------------------------------------------ */

def installed() {
    log.info "${device.displayName} installed (v${VERSION})"
    if (logEnable) runIn(1800, "logsOff")
}

def updated() {
    log.info "${device.displayName} preferences updated (v${VERSION})"
    unschedule("logsOff")
    if (logEnable) runIn(1800, "logsOff")

    if (settings.motionMirror == false) {
        device.deleteCurrentState("motion")
    }

    List<String> cmds = []
    cmds += valuePrefCommand(105, "pressureIntensityPref", "pressureIntensity", 0, 2000, 1)
    cmds += valuePrefCommand(106, "presenceDelayPref", "presenceDelay", 0, 3600, 1)
    cmds += valuePrefCommand(107, "sedentaryReminderPref", "sedentaryReminder", 0, 1440, 1)
    cmds += valuePrefCommand(103, "tempCalPref", "temperatureCalibration", -2, 2, 10)
    cmds += valuePrefCommand(104, "humCalPref", "humidityCalibration", -30, 30, 1)

    if (cmds) {
        logInfo "sending setting change(s) — battery device, press the strap to wake it if nothing is applied"
        sendZigbeeCommands(cmds)
    } else {
        logDebug "no setting changes to send"
    }
}

def configure() {
    log.info "${device.displayName} configure"
    List<String> cmds = []
    // Tuya "magic spell": reading these Basic attributes unlocks DP reporting on many Tuya devices
    cmds += zigbee.readAttribute(0x0000, [0x0004, 0x0000, 0x0001, 0x0005, 0x0007, 0xFFFE], [:], 200)
    cmds += zigbee.command(CLUSTER_TUYA, TUYA_QUERY_DATA, [:], 200)
    sendZigbeeCommands(cmds)
}

def refresh() {
    logDebug "refresh: sending Tuya data query"
    sendZigbeeCommands(zigbee.command(CLUSTER_TUYA, TUYA_QUERY_DATA, [:], 200))
}

def logsOff() {
    log.warn "${device.displayName} debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/* ------------------------------------------------------------------ */
/* Parsing                                                            */
/* ------------------------------------------------------------------ */

def parse(String description) {
    logDebug "parse: ${description}"

    if (description?.startsWith("zone status")) {
        // IAS Zone (0500) is listed in the cluster list but the Tuya DP is the source of truth
        logDebug "IAS zone status ignored: ${description}"
        return null
    }

    Map descMap
    try {
        descMap = zigbee.parseDescriptionAsMap(description)
    } catch (e) {
        log.warn "${device.displayName} failed to parse: ${description} (${e})"
        return null
    }
    if (descMap == null) return null

    switch (descMap.clusterInt) {
        case CLUSTER_TUYA:
            parseTuyaCommand(descMap)
            break
        case 0x0001:
            parsePowerConfig(descMap)
            break
        case 0x0402:
            if (descMap.attrInt == 0 && descMap.value) {
                int raw = hexToSignedInt16(descMap.value)
                emitTemperature(raw / 100.0)
            }
            break
        case 0x0405:
            if (descMap.attrInt == 0 && descMap.value) {
                emitHumidity(Integer.parseInt(descMap.value, 16) / 100.0)
            }
            break
        case 0x0406:
            logDebug "Occupancy cluster (ignored, using Tuya DP): ${descMap.value}"
            break
        case 0x0000:
            logDebug "Basic cluster: attr ${descMap.attrId} = ${descMap.value}"
            break
        default:
            logDebug "unhandled message: ${descMap}"
    }
    return null
}

private void parseTuyaCommand(Map descMap) {
    switch (descMap.command) {
        case "01":  // data response
        case "02":  // data report
        case "06":  // proactive report
            parseTuyaData(descMap.data)
            break
        case "24":  // device requests time sync
            syncTime()
            break
        case "0B":
        case "10":
        case "11":
            logDebug "ignored Tuya command ${descMap.command}: ${descMap.data}"
            break
        default:
            logDebug "unhandled Tuya command ${descMap.command}: ${descMap.data}"
    }
}

private void parsePowerConfig(Map descMap) {
    List<Map> attrs = [[attrInt: descMap.attrInt, value: descMap.value]]
    descMap.additionalAttrs?.each { attrs << [attrInt: it.attrInt, value: it.value] }
    attrs.each { a ->
        if (a.value == null) return
        int raw = Integer.parseInt(a.value as String, 16)
        if (a.attrInt == 0x0021 && raw != 0xFF) {
            emit("battery", Math.min(100, (int) Math.round(raw / 2.0d)), "%")
        } else if (a.attrInt == 0x0020) {
            logDebug "battery voltage ${raw / 10.0} V"
        }
    }
}

private void parseTuyaData(List<String> data) {
    if (!data || data.size() < 6) {
        logDebug "Tuya frame too short: ${data}"
        return
    }
    int i = 2  // skip 2-byte sequence number
    while (i + 4 <= data.size()) {
        int dp   = hexToInt(data[i])
        int type = hexToInt(data[i + 1])
        int len  = (hexToInt(data[i + 2]) << 8) | hexToInt(data[i + 3])
        if (i + 4 + len > data.size()) {
            log.warn "${device.displayName} truncated Tuya frame: ${data}"
            break
        }
        long value = 0L
        data.subList(i + 4, i + 4 + len).each { value = (value << 8) | hexToInt(it) }
        if (type == DP_TYPE_VALUE && len == 4 && value > 0x7FFFFFFFL) {
            value -= 0x100000000L  // signed 32-bit
        }
        processDp(dp, type, value)
        i += 4 + len
    }
}

private void processDp(int dp, int type, long v) {
    logDebug "DP ${dp} (type ${type}) = ${v}"
    switch (dp) {
        case 1:
            handlePressureState((int) v)
            break
        case 2:
            emit("currentPressure", (int) v)
            break
        case 15:
            emit("battery", (int) v, "%")
            break
        case 101:
            emitTemperature(v / 10.0)
            break
        case 102:
            emitHumidity((double) v)
            break
        case 103:
            emit("temperatureCalibration", v / 10.0, "°C")
            break
        case 104:
            emit("humidityCalibration", (int) v, "%")
            break
        case 105:
            emit("pressureIntensity", (int) v)
            break
        case 106:
            emit("presenceDelay", (int) v, "s")
            break
        case 107:
            emit("sedentaryReminder", (int) v, "min")
            break
        default:
            logInfo "unknown DP ${dp} (type ${type}) = ${v}"
    }
}

private void handlePressureState(int v) {
    String state = PRESSURE_STATE[v] ?: "unknown(${v})"
    emit("pressureState", state)

    boolean occupied = (v == 1) || (v == 2 && settings.sedentaryIsPresent != false)
    String presence = occupied ? "present" : "not present"
    if (device.currentValue("presence") != presence) {
        sendEvent(name: "lastPresenceChange", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
    }
    emit("presence", presence)
    if (settings.motionMirror != false) {
        emit("motion", occupied ? "active" : "inactive")
    }
}

private void emitTemperature(double celsius) {
    String scale = location.temperatureScale ?: "C"
    double t = (scale == "F") ? (celsius * 9 / 5 + 32) : celsius
    emit("temperature", Math.round(t * 10) / 10.0, "°${scale}")
}

private void emitHumidity(double pct) {
    emit("humidity", (int) Math.round(pct), "%")
}

private void emit(String name, def value, String unit = null) {
    String desc = "${device.displayName} ${name} is ${value}${unit ? ' ' + unit : ''}"
    if (device.currentValue(name)?.toString() != value?.toString()) {
        logInfo desc
    } else {
        logDebug "${desc} (unchanged)"
    }
    Map evt = [name: name, value: value, descriptionText: desc]
    if (unit) evt.unit = unit
    sendEvent(evt)
}

/* ------------------------------------------------------------------ */
/* Outgoing commands                                                  */
/* ------------------------------------------------------------------ */

/**
 * Build a Tuya value-DP set command from a preference.
 * scale: multiplier applied before sending (e.g. 10 for a /10 DP); min/max are in user units.
 */
private List<String> valuePrefCommand(int dp, String prefName, String attrName, Number minN, Number maxN, int scale) {
    BigDecimal min = minN as BigDecimal
    BigDecimal max = maxN as BigDecimal
    def raw = settings[prefName]
    if (raw == null) return []
    BigDecimal v = raw as BigDecimal
    if (v < min || v > max) {
        log.warn "${device.displayName} ${prefName}=${v} is out of range ${min}..${max}, ignored"
        return []
    }
    int deviceValue = (v * scale).setScale(0, java.math.RoundingMode.HALF_UP).intValue()
    def current = device.currentValue(attrName)
    if (current != null) {
        int currentDeviceValue = ((current as BigDecimal) * scale).setScale(0, java.math.RoundingMode.HALF_UP).intValue()
        if (currentDeviceValue == deviceValue) return []
    }
    return tuyaCommand(dp, DP_TYPE_VALUE, deviceValue)
}

private List<String> tuyaCommand(int dp, int type, int value) {
    String data = (type == DP_TYPE_VALUE) ? toHex32(value) : zigbee.convertToHexString(value & 0xFF, 2)
    int len = (int) (data.length() / 2)
    String payload = nextSeq() + zigbee.convertToHexString(dp, 2) + zigbee.convertToHexString(type, 2) +
                     zigbee.convertToHexString(len, 4) + data
    logDebug "Tuya set DP ${dp} = ${value} (payload ${payload})"
    return zigbee.command(CLUSTER_TUYA, TUYA_SET_DATA, [:], 200, payload)
}

private void syncTime() {
    long nowMs = now()
    int offsetMs = location.timeZone.getOffset(nowMs)
    int utcSec = (int) nowMs.intdiv(1000L)
    int localSec = (int) (nowMs + offsetMs).intdiv(1000L)
    String payload = "0008" + toHex32(utcSec) + toHex32(localSec)
    logDebug "time sync requested, replying ${payload}"
    sendZigbeeCommands(zigbee.command(CLUSTER_TUYA, TUYA_TIME_SYNC, [:], 0, payload))
}

private String nextSeq() {
    int seq = (((state.seq ?: 0) as int) + 1) & 0xFFFF
    state.seq = seq
    return zigbee.convertToHexString(seq, 4)
}

private void sendZigbeeCommands(List<String> cmds) {
    if (!cmds) return
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

/* ------------------------------------------------------------------ */
/* Helpers                                                            */
/* ------------------------------------------------------------------ */

private static int hexToInt(String h) {
    return Integer.parseInt(h, 16)
}

private static int hexToSignedInt16(String h) {
    int v = Integer.parseInt(h, 16)
    return (v > 0x7FFF) ? v - 0x10000 : v
}

// Two's-complement 32-bit hex, so negative calibration values encode correctly
private static String toHex32(int v) {
    return String.format("%08X", ((long) v) & 0xFFFFFFFFL)
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName} ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable) log.info "${msg.startsWith(device.displayName) ? msg : device.displayName + ' ' + msg}"
}
