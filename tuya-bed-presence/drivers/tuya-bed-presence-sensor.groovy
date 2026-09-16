/**
 *  Tuya Zigbee Bed Presence (Pressure Strap) Sensor
 *
 *  Model        : TS0601
 *  Manufacturer : _TZE200_seq9cm6u
 *
 *  Tuya DP map (ported from zigbee-herdsman-converters "TS0601_bed_presence_sensor"):
 *    DP   type   name             notes
 *    1    enum   occupancy        0 = occupied (pressed), 1 = unoccupied
 *    4    value  battery          %
 *    9    enum   sensitivity      0 low / 1 middle / 2 high               (read/write)
 *    12   value  illuminance      lux (as exposed by Z2M)
 *    101  value  interval_time    5..720 min, sampling interval           (read/write)
 *    102  value  presence_delay   0..3600 s, delay before reporting "none" (read/write)
 *    103  value  presence_time    0..3600 s, delay before reporting presence (read/write)
 *    104  enum   work_state       0..5, see WORK_STATE
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

@Field static final Map<Integer, String> SENSITIVITY = [0: "low", 1: "middle", 2: "high"]
@Field static final Map<Integer, String> WORK_STATE = [
    0: "presence",
    1: "none",
    2: "presence_5min",
    3: "presence_30min",
    4: "none_5min",
    5: "none_30min"
]

metadata {
    definition(name: "Tuya Zigbee Bed Presence Sensor", namespace: "kwon2288", author: "kwon2288") {
        capability "Sensor"
        capability "PresenceSensor"
        capability "MotionSensor"
        capability "IlluminanceMeasurement"
        capability "Battery"
        capability "Refresh"
        capability "Configuration"

        attribute "sensitivity", "enum", ["low", "middle", "high"]
        attribute "intervalTime", "number"
        attribute "presenceDelay", "number"
        attribute "presenceTime", "number"
        attribute "workState", "enum", ["presence", "none", "presence_5min", "presence_30min", "none_5min", "none_30min"]
        attribute "lastPresenceChange", "string"

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,EF00", outClusters: "000A,0019",
                    model: "TS0601", manufacturer: "_TZE200_seq9cm6u", controllerType: "ZGB",
                    deviceJoinName: "Tuya Bed Presence Sensor"
    }

    preferences {
        input name: "sensitivityPref", type: "enum", title: "Sensitivity",
              options: ["low": "Low", "middle": "Middle", "high": "High"], required: false
        input name: "intervalTimePref", type: "number", title: "Sampling interval (min, 5-720, step 5)",
              range: "5..720", required: false
        input name: "presenceDelayPref", type: "number", title: "Delay before reporting 'not present' (s, 0-3600)",
              range: "0..3600", required: false
        input name: "presenceTimePref", type: "number", title: "Delay before reporting 'present' (s, 0-3600)",
              range: "0..3600", required: false
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

    // Only push values that differ from what the device last reported
    if (settings.sensitivityPref != null && settings.sensitivityPref != device.currentValue("sensitivity")) {
        Integer v = SENSITIVITY.find { it.value == settings.sensitivityPref }?.key
        if (v != null) cmds += tuyaCommand(9, DP_TYPE_ENUM, v)
    }
    cmds += numberPrefCommand(101, "intervalTimePref", "intervalTime", 5, 720, 5)
    cmds += numberPrefCommand(102, "presenceDelayPref", "presenceDelay", 0, 3600, 1)
    cmds += numberPrefCommand(103, "presenceTimePref", "presenceTime", 0, 3600, 1)

    if (cmds) {
        logInfo "sending ${cmds.size()} setting change(s) — battery device, press the strap or the button to wake it if nothing is applied"
        sendZigbeeCommands(cmds)
    } else {
        logDebug "no setting changes to send"
    }
}

def configure() {
    log.info "${device.displayName} configure"
    List<String> cmds = []
    // Tuya "magic spell": reading these Basic attributes unlocks DP reporting on many TS0601 devices
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
    Map descMap
    try {
        descMap = zigbee.parseDescriptionAsMap(description)
    } catch (e) {
        log.warn "${device.displayName} failed to parse: ${description} (${e})"
        return null
    }
    if (descMap == null) return null

    if (descMap.clusterInt == CLUSTER_TUYA) {
        switch (descMap.command) {
            case "01":  // data response
            case "02":  // data report
            case "06":  // proactive report
                parseTuyaData(descMap.data)
                break
            case "24":  // device requests time sync
                syncTime()
                break
            case "0B":  // default response
            case "10":
            case "11":  // MCU version
                logDebug "ignored Tuya command ${descMap.command}: ${descMap.data}"
                break
            default:
                logDebug "unhandled Tuya command ${descMap.command}: ${descMap.data}"
        }
    } else if (descMap.clusterInt == 0x0000) {
        logDebug "Basic cluster: attr ${descMap.attrId} = ${descMap.value}"
    } else {
        logDebug "unhandled message: ${descMap}"
    }
    return null
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
            boolean occupied = (v == 0L)
            String presence = occupied ? "present" : "not present"
            if (device.currentValue("presence") != presence) {
                sendEvent(name: "lastPresenceChange", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
            }
            emit("presence", presence)
            if (settings.motionMirror != false) {
                emit("motion", occupied ? "active" : "inactive")
            }
            break
        case 4:
            emit("battery", (int) v, "%")
            break
        case 9:
            emit("sensitivity", SENSITIVITY[(int) v] ?: "unknown(${v})")
            break
        case 12:
            emit("illuminance", (int) v, "lx")
            break
        case 101:
            emit("intervalTime", (int) v, "min")
            break
        case 102:
            emit("presenceDelay", (int) v, "s")
            break
        case 103:
            emit("presenceTime", (int) v, "s")
            break
        case 104:
            emit("workState", WORK_STATE[(int) v] ?: "unknown(${v})")
            break
        default:
            logInfo "unknown DP ${dp} (type ${type}) = ${v}"
    }
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

private List<String> numberPrefCommand(int dp, String prefName, String attrName, int min, int max, int step) {
    def raw = settings[prefName]
    if (raw == null) return []
    int v = raw as int
    if (v < min || v > max) {
        log.warn "${device.displayName} ${prefName}=${v} is out of range ${min}..${max}, ignored"
        return []
    }
    if (step > 1 && v % step != 0) {
        int rounded = (int) (Math.round((double) v / step) * step)
        rounded = Math.max(min, Math.min(max, rounded))
        logInfo "${prefName} rounded ${v} -> ${rounded} (step ${step})"
        v = rounded
    }
    def current = device.currentValue(attrName)
    if (current != null && (current as int) == v) return []
    return tuyaCommand(dp, DP_TYPE_VALUE, v)
}

private List<String> tuyaCommand(int dp, int type, int value) {
    String data = (type == DP_TYPE_VALUE) ? zigbee.convertToHexString(value, 8) : zigbee.convertToHexString(value, 2)
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
    String payload = "0008" + zigbee.convertToHexString(utcSec, 8) + zigbee.convertToHexString(localSec, 8)
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

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName} ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable) log.info "${msg.startsWith(device.displayName) ? msg : device.displayName + ' ' + msg}"
}
