/**
 * Navien Smart 숙면매트 — 구역 슬라이더 (컴포넌트 드라이버)
 *
 * "Navien Smart 숙면매트 (On-Hub)" 드라이버가 구역(왼쪽/오른쪽/전체)마다 자동으로
 * 만들어 주는 자식 기기다. HomeKit(HomeKit Integration 앱 등)에서 구역별로
 * 켜기/끄기 + 슬라이더로 단계를 조절할 수 있게 한다 (Switch + SwitchLevel).
 *
 *   슬라이더 %  = 단계 × 12.5  (반올림)   1단계=13%  2=25%  3=38%  4=50%
 *                                          5=63%  6=75%  7=88%  8=100%
 *   끄기 / 0%   = 운전 대기(0단계)
 *   켜기        = 마지막으로 쓰던 단계(없으면 3단계)
 *
 * 슬라이더로 보낸 값은 가장 가까운 단계로 맞춘다 (1% 이상이면 최소 1단계,
 * 예: 60% → 5단계 = 63%). 실제 제어는 전부 부모 드라이버로 위임한다
 * (부모가 Connector 앱 → REST 로 전송).
 */
import groovy.transform.Field

@Field static final int DEFAULT_LEVEL = 3      // 켜기 시 이전 단계가 없으면 쓸 값

metadata {
    definition(name: "Navien Smart 숙면매트 (구역 슬라이더)", namespace: "kwon2288", author: "kwon2288") {
        capability "Switch"
        capability "SwitchLevel"
        capability "Refresh"

        attribute "heatLevel", "number"
        attribute "heatLevelLabel", "string"

        command "setHeatLevel", [
            [name: "level*", type: "NUMBER", description: "0(운전 대기) ~ 8단계"]
        ]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "디버그 로그 남기기", defaultValue: false
    }
}

// ── 라이프사이클 ───────────────────────────────────────────────────────────

def installed() { cleanupOldAttributes() }

def updated() { cleanupOldAttributes() }

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

// 이전 버전(온도조절기)이 남긴 속성은 지운다 — 남아 있으면 헷갈린다.
private void cleanupOldAttributes() {
    if (state.cleanedOldAttributes) return
    ["thermostatMode", "thermostatOperatingState", "heatingSetpoint", "coolingSetpoint",
     "thermostatSetpoint", "temperature", "supportedThermostatModes",
     "supportedThermostatFanModes", "thermostatFanMode", "levelLabel"].each { attr ->
        try { device.deleteCurrentState(attr) } catch (Exception ignored) { }
    }
    state.cleanedOldAttributes = true
}

// ── 단계 <-> 슬라이더 % 변환 ───────────────────────────────────────────────

private List<Integer> levelRange() {
    try {
        Map r = parent?.zoneLevelRange()
        if (r?.min != null && r?.max != null) return [r.min as int, r.max as int]
    } catch (Exception ignored) { }
    return [1, 8]
}

private int levelToPercent(int level) {
    int max = levelRange()[1]
    return Math.round((level * 100 / max) as double) as int
}

private int percentToLevel(int percent) {
    if (percent <= 0) return 0
    List<Integer> range = levelRange()
    int level = Math.round((percent * range[1] / 100) as double) as int
    return Math.max(range[0], Math.min(range[1], level))
}

private int lastLevel() {
    return ((state.lastLevel ?: DEFAULT_LEVEL) as int)
}

// ── 부모가 호출: 실제 단계가 바뀌었을 때 반영 (MQTT 상태·제어 결과) ──────

def applyLevel(lvl) {
    cleanupOldAttributes()
    int level = (lvl ?: 0) as int
    if (level > 0) state.lastLevel = level

    sendEvent(name: "heatLevel", value: level)
    sendEvent(name: "heatLevelLabel", value: level == 0 ? "운전 대기" : "${level}단계")
    sendEvent(name: "switch", value: level > 0 ? "on" : "off")
    sendEvent(name: "level", value: levelToPercent(level), unit: "%")
}

// ── 명령 (HomeKit/대시보드/룰이 호출) ──────────────────────────────────────

def on() { setHeatLevel(lastLevel()) }

def off() { setHeatLevel(0) }

def setLevel(level, duration = null) {
    if (level == null) return
    int lvl = percentToLevel(level as int)
    if (logEnable) log.debug "슬라이더 ${level}% → ${lvl}단계"
    setHeatLevel(lvl)
}

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
