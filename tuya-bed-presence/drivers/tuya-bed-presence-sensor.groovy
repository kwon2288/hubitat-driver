/**
 *  Tuya Zigbee Bed Presence (Pressure Strap) Sensor
 *
 *  One driver for two Tuya TS0601 pressure-strap occupancy sensors. The model is
 *  detected from the device's manufacturer/model data; the DP numbers differ per model.
 *
 *  (A) Tuya TS0601            Manufacturer : _TZE200_seq9cm6u
 *      Tuya DP map (ported from zigbee-herdsman-converters "TS0601_bed_presence_sensor"):
 *        DP   type   name             notes
 *        1    enum   occupancy        0 = occupied (pressed), 1 = unoccupied
 *        4    value  battery          %
 *        9    enum   sensitivity      0 low / 1 middle / 2 high               (read/write)
 *        12   value  illuminance      lux (as exposed by Z2M)
 *        101  value  interval_time    5..720 min, sampling interval           (read/write)
 *        102  value  presence_delay   0..3600 s, delay before reporting "none" (read/write)
 *        103  value  presence_time    0..3600 s, delay before reporting presence (read/write)
 *        104  enum   work_state       0..5, see WORK_STATE
 *
 *  (B) HOBEIAN ZG-210Z (with T&H)   Manufacturer : _TZE200_ihizzpzn
 *      Tuya DP map (ported from zigbee-herdsman-converters "ZG-210Z", HOBEIAN):
 *        DP   type   name                     notes
 *        1    enum   pressure_state           0 none / 1 sit / 2 sedentary
 *        2    value  current_pressure         raw sensing value
 *        15   value  battery                  %
 *        101  value  temperature              /10 °C
 *        102  value  humidity                 %
 *        103  value  temperature_calibration  /10 °C, -2.0..2.0          (read/write)
 *        104  value  humidity_calibration     %, -30..30                  (read/write)
 *        105  value  pressure_intensity       0..2000, trigger threshold  (read/write)
 *        106  value  presence_delay           0..3600 s, delay before "none" (read/write)
 *        107  value  sedentary_reminder       0..1440 min                 (read/write)
 *      The ZG-210Z also reports standard ZCL Temperature (0402), Humidity (0405)
 *      and Power Configuration (0001) attributes; those are parsed too.
 *
 *  Tuya EF00 frame: seq(2) | dp(1) | type(1) | len(2) | data(len) [| next dp ...]
 *
 *  ZG-210Z presence:
 *    - If "Detection threshold" is set, presence is decided in the driver:
 *      currentPressure >= threshold -> present (fixed hysteresis). Going back to "not present"
 *      waits for the "not present" delay so short dips do not flicker. Use this when the
 *      firmware threshold (DP105, unreliable) does not track the real load.
 *    - If it is left blank, the device's own pressure_state (DP1) is used.
 *
 *  Version: 1.3.2
 *    - CHG: preference titles and log tags use the model name (TS0601 / ZG-210Z) instead of the manufacturer code
 *  Version: 1.3.1
 *    - FIX: rename evaluate() (clashed with Script.evaluate on Hubitat, compile error)
 *  Version: 1.3.0
 *    - CHG (ZG-210Z): simplified preferences — one detection threshold, one "not present" delay,
 *      temperature/humidity calibration. Removed detection mode, hysteresis, invert, firmware
 *      threshold, sedentary options and the setPressureIntensity command
 *    - CHG (ZG-210Z): a new detection threshold is applied immediately; stale attributes and
 *      settings from 1.2.0 are cleaned up
 *  Version: 1.2.0
 *    - NEW (ZG-210Z): driver-side detection, settings kept in a pending queue and re-sent each
 *      time the sleepy device reports, until it echoes them back
 *  Version: 1.1.0
 *    - NEW: HOBEIAN ZG-210Z (_TZE200_ihizzpzn) support in the same driver
 *    - CHG: Tuya value DPs are encoded as two's-complement 32-bit (negative calibration values)
 *  Version: 1.0.0
 *    - Initial release (_TZE200_seq9cm6u)
 *
 *  Author: kwon2288
 */

import groovy.transform.Field

@Field static final String VERSION = "1.3.2"

// ZG-210Z driver-side detection: fixed hysteresis so values hovering at the threshold do not flicker
@Field static final int HYSTERESIS = 20

@Field static final String MFR_SEQ9 = "_TZE200_seq9cm6u"
@Field static final String MFR_ZG210Z = "_TZE200_ihizzpzn"

@Field static final int CLUSTER_TUYA = 0xEF00
@Field static final int TUYA_SET_DATA   = 0x00
@Field static final int TUYA_QUERY_DATA = 0x03
@Field static final int TUYA_TIME_SYNC  = 0x24

@Field static final int DP_TYPE_BOOL  = 0x01
@Field static final int DP_TYPE_VALUE = 0x02
@Field static final int DP_TYPE_ENUM  = 0x04

// _TZE200_seq9cm6u
@Field static final Map<Integer, String> SENSITIVITY = [0: "low", 1: "middle", 2: "high"]
@Field static final Map<Integer, String> WORK_STATE = [
    0: "presence",
    1: "none",
    2: "presence_5min",
    3: "presence_30min",
    4: "none_5min",
    5: "none_30min"
]

// _TZE200_ihizzpzn (ZG-210Z)
@Field static final Map<Integer, String> PRESSURE_STATE = [0: "none", 1: "sit", 2: "sedentary"]

metadata {
    definition(name: "Tuya Zigbee Bed Presence Sensor", namespace: "kwon2288", author: "kwon2288") {
        capability "Sensor"
        capability "PresenceSensor"
        capability "MotionSensor"
        capability "IlluminanceMeasurement"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Battery"
        capability "Refresh"
        capability "Configuration"

        attribute "lastPresenceChange", "string"

        // _TZE200_seq9cm6u
        attribute "sensitivity", "enum", ["low", "middle", "high"]
        attribute "intervalTime", "number"
        attribute "presenceTime", "number"
        attribute "workState", "enum", ["presence", "none", "presence_5min", "presence_30min", "none_5min", "none_30min"]

        // _TZE200_ihizzpzn (ZG-210Z)
        attribute "pressureState", "enum", ["none", "sit", "sedentary"]
        attribute "currentPressure", "number"
        attribute "temperatureCalibration", "number"
        attribute "humidityCalibration", "number"

        // both
        attribute "presenceDelay", "number"

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,EF00", outClusters: "000A,0019",
                    model: "TS0601", manufacturer: "_TZE200_seq9cm6u", controllerType: "ZGB",
                    deviceJoinName: "Tuya Bed Presence Sensor"

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0500,0406,EF00,0402,0405,0001",
                    outClusters: "0003", model: "ZG-210Z", manufacturer: "_TZE200_ihizzpzn", controllerType: "ZGB",
                    deviceJoinName: "HOBEIAN ZG-210Z Pressure Sensor"
    }

    preferences {
        input name: "sensitivityPref", type: "enum", title: "Sensitivity (TS0601 only)",
              options: ["low": "Low", "middle": "Middle", "high": "High"], required: false
        input name: "intervalTimePref", type: "number", title: "Sampling interval (min, 5-720, step 5; TS0601 only)",
              range: "5..720", required: false
        input name: "presenceTimePref", type: "number", title: "Delay before reporting 'present' (s, 0-3600; TS0601 only)",
              range: "0..3600", required: false
        input name: "presenceDelayPref", type: "number", title: "Delay before reporting 'not present' (s, 0-3600)",
              range: "0..3600", required: false
        input name: "threshold", type: "number", title: "Detection threshold / 감지 기준값 (ZG-210Z only)",
              description: "Present when currentPressure is at or above this value. Leave blank to use the device's own judgement / 비워 두면 기기 자체 판정 사용",
              required: false
        input name: "tempCalPref", type: "decimal", title: "Temperature calibration (°C, -2.0 to 2.0; ZG-210Z only)",
              range: "-2..2", required: false
        input name: "humCalPref", type: "number", title: "Humidity calibration (%, -30 to 30; ZG-210Z only)",
              range: "-30..30", required: false
        input name: "motionMirror", type: "bool", title: "Mirror presence to motion attribute (active/inactive)",
              defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 min)", defaultValue: true
    }
}

/* ------------------------------------------------------------------ */
/* Model detection                                                    */
/* ------------------------------------------------------------------ */

// Anything that is not a ZG-210Z is treated as the original _TZE200_seq9cm6u layout.
private boolean isZg210z() {
    String mfr = device.getDataValue("manufacturer")
    String model = device.getDataValue("model")
    return mfr == MFR_ZG210Z || model == "ZG-210Z"
}

/* ------------------------------------------------------------------ */
/* Lifecycle                                                          */
/* ------------------------------------------------------------------ */

def installed() {
    log.info "${device.displayName} installed (v${VERSION}, ${isZg210z() ? 'ZG-210Z' : 'TS0601'})"
    if (logEnable) runIn(1800, "logsOff")
}

def updated() {
    log.info "${device.displayName} preferences updated (v${VERSION}, ${isZg210z() ? 'ZG-210Z' : 'TS0601'})"
    unschedule("logsOff")
    if (logEnable) runIn(1800, "logsOff")

    if (settings.motionMirror == false) {
        device.deleteCurrentState("motion")
    }

    // Clean up attributes/settings left over from earlier versions
    ["pressureIntensity", "sedentaryReminder", "pendingSettings"].each { device.deleteCurrentState(it) }
    ["detectionMode", "localThreshold", "localHysteresis", "localInvert", "pressureIntensityPref",
     "sedentaryReminderPref", "sedentaryIsPresent"].each { device.removeSetting(it) }

    if (isZg210z()) {
        // Sleepy device: changes go through the pending queue (re-sent until the device echoes them).
        // "Not present" delay defaults to 5 s when never set.
        def delay = (settings.presenceDelayPref != null) ? settings.presenceDelayPref : 5
        queueSetting(106, delay, "presenceDelay", 0, 3600, 1)
        queueSetting(103, settings.tempCalPref, "temperatureCalibration", -2, 2, 10)
        queueSetting(104, settings.humCalPref, "humidityCalibration", -30, 30, 1)
        flushPending(true)

        // Apply a new threshold right away instead of waiting for the next report
        def cp = device.currentValue("currentPressure")
        if (settings.threshold != null && cp != null) evaluatePressure(cp as int)
        return
    }

    List<String> cmds = []

    // Only push values that differ from what the device last reported
    if (settings.sensitivityPref != null && settings.sensitivityPref != device.currentValue("sensitivity")) {
        Integer v = SENSITIVITY.find { it.value == settings.sensitivityPref }?.key
        if (v != null) cmds += tuyaCommand(9, DP_TYPE_ENUM, v)
    }
    cmds += valuePrefCommand(101, "intervalTimePref", "intervalTime", 5, 720, 1, 5)
    cmds += valuePrefCommand(102, "presenceDelayPref", "presenceDelay", 0, 3600, 1)
    cmds += valuePrefCommand(103, "presenceTimePref", "presenceTime", 0, 3600, 1)

    if (cmds) {
        logInfo "sending ${cmds.size()} setting change(s) — battery device, press the strap to wake it if nothing is applied"
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

    if (description?.startsWith("zone status")) {
        // IAS Zone (0500) is listed in the ZG-210Z cluster list but the Tuya DP is the source of truth
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
            flushPending(false)
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
    confirmPending(dp, v)
    if (isZg210z()) processDpZg210z(dp, type, v)
    else processDpSeq9(dp, type, v)
}

// _TZE200_seq9cm6u
private void processDpSeq9(int dp, int type, long v) {
    switch (dp) {
        case 1:
            setPresence(v == 0L)
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

// _TZE200_ihizzpzn (ZG-210Z)
private void processDpZg210z(int dp, int type, long v) {
    switch (dp) {
        case 1:
            String state = PRESSURE_STATE[(int) v] ?: "unknown(${v})"
            emit("pressureState", state)
            // With a detection threshold set, presence comes from evaluatePressure()
            if (settings.threshold == null) setPresence(v == 1L || v == 2L)
            break
        case 2:
            emit("currentPressure", (int) v)
            if (settings.threshold != null) evaluatePressure((int) v)
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
        case 106:
            emit("presenceDelay", (int) v, "s")
            break
        case 105:
        case 107:
            logDebug "DP ${dp} (firmware threshold / sedentary reminder) = ${v}, not used"
            break
        default:
            logInfo "unknown DP ${dp} (type ${type}) = ${v}"
    }
}

/* ZG-210Z driver-side detection: fixed hysteresis, absence honours the "not present" delay */
private void evaluatePressure(int cp) {
    int th = settings.threshold as int
    boolean present = device.currentValue("presence") == "present"
    // Hysteresis: need th+H to become present, drop below th-H to start leaving
    boolean loaded = present ? (cp > th - HYSTERESIS) : (cp >= th + HYSTERESIS)
    logDebug "currentPressure ${cp} vs threshold ${th} (±${HYSTERESIS}) -> ${loaded ? 'loaded' : 'empty'}"

    if (loaded) {
        unschedule("goVacant")
        state.vacantPending = false
        setPresence(true)
    } else if (present && !state.vacantPending) {
        // Schedule once; later empty reports must not keep pushing the timer back
        int delay = (settings.presenceDelayPref != null ? settings.presenceDelayPref : 5) as int
        if (delay > 0) {
            state.vacantPending = true
            runIn(delay, "goVacant")
        } else {
            setPresence(false)
        }
    }
}

def goVacant() {
    state.vacantPending = false
    // Re-check: pressure may have come back during the delay
    def cp = device.currentValue("currentPressure")
    if (settings.threshold != null && cp != null && (cp as int) > (settings.threshold as int) - HYSTERESIS) return
    setPresence(false)
}

private void setPresence(boolean occupied) {
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

/* ---- Pending write queue (ZG-210Z) ---------------------------------
 * Sleepy Tuya devices drop writes sent while asleep, so each change is kept in
 * state.pending until the device echoes the value, and re-sent on each report.
 */
private void queueSetting(int dp, def raw, String attrName, Number min, Number max, int scale) {
    if (raw == null) return
    BigDecimal v = raw as BigDecimal
    if (v < (min as BigDecimal) || v > (max as BigDecimal)) {
        log.warn "${device.displayName} DP${dp} value ${v} is out of range ${min}..${max}, ignored"
        return
    }
    int deviceValue = (v * scale).setScale(0, java.math.RoundingMode.HALF_UP).intValue()
    def current = device.currentValue(attrName)
    if (current != null &&
        ((current as BigDecimal) * scale).setScale(0, java.math.RoundingMode.HALF_UP).intValue() == deviceValue) return
    Map p = (state.pending ?: [:]) as Map
    p[dp.toString()] = deviceValue
    state.pending = p
    state.pendingTries = 0
}

private void flushPending(boolean force) {
    Map p = (state.pending ?: [:]) as Map
    if (!p) return
    long last = (state.lastFlush ?: 0L) as long
    if (!force && now() - last < 20000L) return  // at most every 20 s
    state.lastFlush = now()

    int tries = ((state.pendingTries ?: 0) as int) + 1
    state.pendingTries = tries
    if (tries > 15) {
        log.warn "${device.displayName} device did not accept ${p}, giving up"
        state.pending = [:]
        return
    }
    List<String> cmds = []
    p.each { k, v -> cmds += tuyaCommand(k as int, DP_TYPE_VALUE, v as int) }
    logDebug "sending ${p} (attempt ${tries})"
    sendZigbeeCommands(cmds)
}

private void confirmPending(int dp, long v) {
    Map p = (state.pending ?: [:]) as Map
    String key = dp.toString()
    if (p.containsKey(key) && (p[key] as long) == v) {
        p.remove(key)
        state.pending = p
        logInfo "setting applied (DP${dp}=${v})"
    }
}

/**
 * Build a Tuya value-DP set command from a preference (seq9cm6u: sent immediately).
 * scale: multiplier applied before sending (e.g. 10 for a /10 DP); min/max are in user units.
 * step: if > 1, the value is rounded to the nearest multiple of step (user units).
 */
private List<String> valuePrefCommand(int dp, String prefName, String attrName, Number minN, Number maxN, int scale, int step = 1) {
    BigDecimal min = minN as BigDecimal
    BigDecimal max = maxN as BigDecimal
    def raw = settings[prefName]
    if (raw == null) return []
    BigDecimal v = raw as BigDecimal
    if (v < min || v > max) {
        log.warn "${device.displayName} ${prefName}=${v} is out of range ${min}..${max}, ignored"
        return []
    }
    if (step > 1 && v.remainder(step as BigDecimal) != 0) {
        BigDecimal rounded = (v / step).setScale(0, java.math.RoundingMode.HALF_UP) * step
        rounded = rounded.max(min).min(max)
        logInfo "${prefName} rounded ${v} -> ${rounded} (step ${step})"
        v = rounded
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
