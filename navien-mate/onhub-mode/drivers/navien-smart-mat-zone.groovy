/**
 * Navien Smart 숙면매트 — 구역 온도조절기 (컴포넌트 드라이버)
 *
 * "Navien Smart 숙면매트 (On-Hub)" 드라이버가 구역(왼쪽/오른쪽/전체)마다 자동으로
 * 만들어 주는 자식 기기다. 목적은 HomeKit(HomeKit Integration 앱 등)에서 구역별로
 * 온도조절기처럼 보이게 하는 것 — 매트는 실제 온도를 모르고 1~8단계만 있어서,
 * 단계를 가짜 온도로 변환해 노출한다.
 *
 *   설정온도(°C) = 20 + 단계        (1단계 = 21°C … 8단계 = 28°C)
 *   모드 off      = 운전 대기(0단계)
 *   모드 heat     = 마지막으로 쓰던 단계(없으면 3단계)로 가열
 *   현재온도      = 설정온도와 같은 값 (실제 온도 센서가 없어서 항상 "도달" 상태)
 *
 * HomeKit이 보낸 온도는 가장 가까운 단계로 맞추고 1~최대 단계로 제한한다
 * (예: 25.4°C → 5단계, 35°C → 8단계). HomeKit의 조절 범위가 10~38°C라서 범위 밖으로
 * 끌면 가장 가까운 단계로 되돌아온다.
 *
 * 실제 제어는 전부 부모 드라이버로 위임한다 (부모가 Connector 앱 → REST 로 전송).
 */
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final int SETPOINT_BASE = 20     // 설정온도(°C) = SETPOINT_BASE + 단계
@Field static final int DEFAULT_LEVEL = 3      // heat 모드로 켤 때 이전 단계가 없으면 쓸 값

metadata {
    definition(name: "Navien Smart 숙면매트 (구역 온도조절기)", namespace: "kwon2288", author: "kwon2288") {
        capability "Thermostat"
        capability "Refresh"

        attribute "level", "number"
        attribute "levelLabel", "string"

        command "setHeatLevel", [
            [name: "level*", type: "NUMBER", description: "0(운전 대기) ~ 8단계"]
        ]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "디버그 로그 남기기", defaultValue: false
    }
}

// ── 라이프사이클 ───────────────────────────────────────────────────────────

def installed() { publishStatic() }

def updated() { publishStatic() }

def refresh() {
    // 부모가 현재 구역 단계를 이미 알고 있으면 그 값으로 다시 반영한다.
    try {
        def lvl = parent?.currentValue("${zone()}_level")
        if (lvl != null) applyLevel(lvl)
    } catch (Exception e) {
        if (logEnable) log.debug "refresh 실패: ${e.message}"
    }
}

private String zone() { return getDataValue("zone") }

private void publishStatic() {
    sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(["off", "heat"]))
    sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(["auto"]))
    sendEvent(name: "thermostatFanMode", value: "auto")
    if (device.currentValue("thermostatMode") == null) applyLevel(0)
}

// ── 단계 <-> 온도 변환 (허브 온도 단위가 °F 여도 안전하게) ────────────────

private boolean hubIsFahrenheit() { return location?.temperatureScale == "F" }

private BigDecimal toHubTemp(BigDecimal celsius) {
    return hubIsFahrenheit() ? (celsius * 9 / 5 + 32).setScale(1, BigDecimal.ROUND_HALF_UP) : celsius
}

private BigDecimal fromHubTemp(BigDecimal value) {
    return hubIsFahrenheit() ? (value - 32) * 5 / 9 : value
}

private List<Integer> levelRange() {
    try {
        Map r = parent?.zoneLevelRange()
        if (r?.min != null && r?.max != null) return [r.min as int, r.max as int]
    } catch (Exception ignored) { }
    return [1, 8]
}

private int lastLevel() {
    return ((state.lastLevel ?: DEFAULT_LEVEL) as int)
}

// ── 부모가 호출: 실제 단계가 바뀌었을 때 반영 (MQTT 상태·제어 결과) ──────

def applyLevel(lvl) {
    int level = (lvl ?: 0) as int
    if (level > 0) state.lastLevel = level
    int shown = level > 0 ? level : lastLevel()

    String unit = hubIsFahrenheit() ? "°F" : "°C"
    BigDecimal sp = toHubTemp((SETPOINT_BASE + shown) as BigDecimal)

    sendEvent(name: "level", value: level)
    sendEvent(name: "levelLabel", value: level == 0 ? "운전 대기" : "${level}단계")
    sendEvent(name: "thermostatMode", value: level > 0 ? "heat" : "off")
    sendEvent(name: "thermostatOperatingState", value: level > 0 ? "heating" : "idle")
    sendEvent(name: "heatingSetpoint", value: sp, unit: unit)
    sendEvent(name: "coolingSetpoint", value: sp, unit: unit)
    sendEvent(name: "thermostatSetpoint", value: sp, unit: unit)
    sendEvent(name: "temperature", value: sp, unit: unit)
}

// ── 명령 (HomeKit/대시보드/룰이 호출) ──────────────────────────────────────

def setHeatingSetpoint(temperature) {
    if (temperature == null) return
    BigDecimal celsius = fromHubTemp(temperature as BigDecimal)
    List<Integer> range = levelRange()
    int level = Math.round((celsius - SETPOINT_BASE) as double) as int
    level = Math.max(range[0], Math.min(range[1], level))
    if (logEnable) log.debug "설정온도 ${temperature} → ${level}단계"
    setHeatLevel(level)
}

def setCoolingSetpoint(temperature) {
    // 냉방은 없다 — HomeKit이 목표온도를 이쪽으로 보내는 경우만 가열 설정으로 취급한다.
    setHeatingSetpoint(temperature)
}

def setThermostatMode(String mode) {
    switch (mode) {
        case "off":
            setHeatLevel(0)
            break
        case "heat":
        case "auto":
        case "emergencyHeat":
            setHeatLevel(lastLevel())
            break
        default:
            log.warn "지원하지 않는 모드입니다: ${mode} (off / heat 만 지원)"
    }
}

def off() { setThermostatMode("off") }
def heat() { setThermostatMode("heat") }
def auto() { setThermostatMode("heat") }
def emergencyHeat() { setThermostatMode("heat") }
def cool() { log.warn "냉방은 지원하지 않습니다." }
def fanOn() { }
def fanAuto() { }
def fanCirculate() { }
def setThermostatFanMode(String mode) { }
def setSchedule(schedule) { }

def setHeatLevel(level) {
    List<Integer> range = levelRange()
    int lvl = (level ?: 0) as int
    if (lvl != 0) lvl = Math.max(range[0], Math.min(range[1], lvl))
    if (logEnable) log.debug "${zone()} 구역 ${lvl}단계로 설정"
    try {
        parent?.componentSetLevel(zone(), lvl)
    } catch (Exception e) {
        log.warn "부모 드라이버 제어 요청 실패: ${e.message}"
        return
    }
    // 부모가 REST 전송 후 applyLevel 로 다시 알려주지만, 반응을 빠르게 하려고 먼저 반영한다.
    applyLevel(lvl)
}
