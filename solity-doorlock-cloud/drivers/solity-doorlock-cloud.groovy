/**
 *  Solity Doorlock (Cloud) — Hubitat Driver
 *
 *  스마트솔리티(SOLITY) 스마트 도어락을 솔리티 클라우드 API로 직접 제어합니다.
 *  SmartThings 불필요. 원격 열기/닫기 + 상태 + 실시간 출입 감지.
 *
 *  ✅ 원격 개폐 지원: PUT /api_v2/controlDevice/{id}
 *     (tpgi2013-hue/Smart-Solity-Doorlock HA 통합에서 확인된 엔드포인트를 Hubitat 로 이식)
 *
 *  동작 구조 (HA 통합과 동일 전략):
 *   - 상태 폴링(statusMinutes, 기본 30분): get_status → deadBolt/battery.
 *     ⚠️ 이 호출은 도어락을 깨우므로(배터리 소모) 느리게.
 *   - 로그 폴링(logSeconds, 기본 10초): retrieveLog → 외부 열림/닫힘을 수초 내 감지.
 *     ⚠️ 이 호출은 클라우드 로그만 읽어 도어락을 안 깨움 → 자주 돌려도 됨.
 *   - 도어락은 "열림"만 로그로 남김(닫힘 로그 없음). 자동잠김 기기라,
 *     열림 감지 후 autoCloseSeconds(기본 5초) 뒤 lock 을 locked 로 자동 복귀.
 *
 *  Author : kwon2288
 *  Version: 1.1.0 (2026-10-07)
 *    - FIX: retrieveLog 쿼리스트링을 path 에 붙이면 '?' 가 인코딩되어 로그가 항상 비어 옴
 *           → query 맵으로 전달 (lastAccess* 가 한 번도 안 채워지던 원인)
 *    - NEW: 누가/어떤 방식으로 열고 닫았는지 표시
 *           lastAccess, lastAccessUser/Method/Type/Time, lastUnlockedBy, lastUnlockMethod,
 *           lastLockedBy, lastLockMethod, lastCodeName, accessHistory(대시보드용 HTML)
 *    - NEW: lock 이벤트 descriptionText / data 에 사용자·방식 포함 (Rule Machine, 알림용)
 *    - NEW: 폴링 사이에 쌓인 로그를 빠짐없이 순서대로 처리 (기존: 최신 1건만)
 *    - NEW: diagnose 가 최근 로그 원본(mediaType 등)을 출력
 *  Licensed under the Apache License, Version 2.0
 *
 *  Credit: 클라우드 controlDevice 엔드포인트 및 동작 전략은
 *          github.com/tpgi2013-hue/Smart-Solity-Doorlock (MIT) 의 HA 통합을 참고함.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String BASE_URL   = "https://www.smartsolity.com"
@Field static final String PHONE_TOKEN = "ha-solity-hubitat"  // 폰 앱과 독립 세션용 기기 식별자

// controlDevice(open/close/get_status)는 서버→게이트웨이→도어락 BLE 왕복이라 느립니다.
// 도어락이 수면 중이면 깨우는 데 시간이 걸려 수십 초가 걸릴 수 있어 넉넉히 잡습니다.
@Field static final int CONTROL_TIMEOUT = 40   // controlDevice 용
@Field static final int QUICK_TIMEOUT   = 20   // login / retrieveLog 용 (도어락 안 깨움)

// retrieveLog logCode → 이벤트 분류 (const.py 확인)
@Field static final String LOG_CODE_CLOSE     = "0"
@Field static final String LOG_CODE_OPEN      = "1"
@Field static final String LOG_CODE_OPEN_LONG = "7"

metadata {
    definition(
        name:      "Solity Doorlock (Cloud)",
        namespace: "kwon2288",
        author:    "kwon2288"
    ) {
        capability "Lock"
        capability "Battery"
        capability "Refresh"
        capability "Sensor"

        // ── 누가 / 어떻게 ──
        attribute "lastAccess",       "string"   // "열림 · 권민 · 지문" 요약
        attribute "lastAccessMessage","string"   // 솔리티 서버 logMessage 원문
        attribute "lastAccessTime",   "string"
        attribute "lastAccessUser",   "string"
        attribute "lastAccessMethod", "string"   // 지문/카드/비밀번호/앱(원격)/수동(실내)/Hubitat/자동잠김 ...
        attribute "lastAccessType",   "enum", ["open", "close", "other"]
        attribute "lastUnlockedBy",   "string"
        attribute "lastUnlockMethod", "string"
        attribute "lastUnlockTime",   "string"
        attribute "lastLockedBy",     "string"
        attribute "lastLockMethod",   "string"
        attribute "lastCodeName",     "string"   // 내장 알림/Lock 관련 앱 호환용 (= lastUnlockedBy)
        attribute "accessHistory",    "string"   // 최근 출입 내역 HTML (대시보드 Attribute 타일)
        attribute "subLatch",       "string"
        attribute "systemMode",     "string"
        attribute "cardCount",      "number"
        attribute "fingerprintCount","number"
        attribute "passwordCount",  "number"
        attribute "apiStatus",      "enum", ["ok", "auth_failed", "error"]

        command "login"
        command "diagnose"
    }

    preferences {
        input name: "emailId", type: "text",
              title: "<b>솔리티 이메일(ID)</b>", required: true
        input name: "password", type: "password",
              title: "<b>솔리티 비밀번호</b>",
              description: "평문. SHA-256 해시로만 서버에 전송되며 state 에는 해시만 저장됩니다.",
              required: true
        input name: "myDeviceId", type: "text",
              title: "<b>myDeviceId</b>",
              description: "예: BTBZ9BDI8S — 비워두면 계정의 첫 번째 기기를 사용", required: false

        input name: "logSeconds", type: "enum",
              title: "출입 감지 주기(초) — 도어락 안 깨움",
              options: ["5":"5초","10":"10초","15":"15초","30":"30초","60":"60초"],
              defaultValue: "10", required: true
        input name: "statusMinutes", type: "enum",
              title: "상태/배터리 폴링 주기(분) — ⚠️ 도어락 깨움",
              options: ["10":"10분","30":"30분","60":"60분","240":"4시간","720":"12시간"],
              defaultValue: "30", required: true
        input name: "autoCloseSeconds", type: "number",
              title: "열림 후 자동 잠김 복귀(초)",
              description: "도어락이 닫힘 로그를 안 남기므로, 열림 감지 후 이 시간 뒤 locked 로 되돌림",
              defaultValue: 5, required: true

        input name: "historyCount", type: "enum",
              title: "accessHistory 에 표시할 최근 출입 건수",
              options: ["3":"3건","5":"5건","8":"8건","10":"10건"],
              defaultValue: "5", required: true

        input name: "logEnable", type: "bool", title: "디버그 로그(30분 후 자동 해제)", defaultValue: true
        input name: "txtEnable", type: "bool", title: "설명 로그", defaultValue: true
    }
}

// ─────────────────────────────────────────────────────────────
//  Lifecycle
// ─────────────────────────────────────────────────────────────

def installed() {
    unschedule()   // 이전 드라이버의 좀비 스케줄(poll 등) 제거
    initialize()
}

def updated() {
    log.info "Solity Doorlock 설정 갱신"
    unschedule()

    // 비밀번호가 바뀌었으면 해시 재계산 + 토큰 초기화
    String newHash = password ? sha256b64(password) : null
    if (newHash && newHash != state.hashedPwd) {
        state.hashedPwd = newHash
        state.token = null
        state.tokenPwd = null
        if (txtEnable) log.info "비밀번호 해시 갱신 → 토큰 초기화"
    }
    initialize()
}

def initialize() {
    if (logEnable) runIn(1800, "logsOff")

    if (!emailId || !state.hashedPwd) {
        log.warn "이메일/비밀번호를 입력하세요"
        sendEvent(name: "apiStatus", value: "auth_failed")
        return
    }

    try {
        switch (logSeconds ?: "10") {
            case "5":  runEvery5Seconds_("pollLog"); break
            default:   scheduleEvery((logSeconds ?: "10") as Integer, "pollLog"); break
        }
    } catch (e) { log.error "로그 폴링 등록 실패: ${e.message}" }

    switch (statusMinutes ?: "30") {
        case "10": runEvery10Minutes("pollStatus"); break
        case "30": runEvery30Minutes("pollStatus"); break
        case "60": runEvery1Hour("pollStatus"); break
        case "240": runEvery3Hours("pollStatus"); break
        default:   schedule("0 0 */12 * * ?", "pollStatus"); break
    }

    runIn(3, "pollStatus")
    runIn(6, "pollLog")
}

def logsOff() {
    log.warn "디버그 로그 자동 해제"
    device.updateSetting("logEnable", [value:"false", type:"bool"])
}

// 초 단위 스케줄 헬퍼 (Hubitat 은 runEvery5Seconds 만 기본 제공 → cron 으로 구성)
private scheduleEvery(int seconds, String handler) {
    if (seconds <= 5) { runEvery5Seconds_(handler); return }
    // cron 초 필드로 N초마다 (최대 60)
    schedule("*/${Math.min(seconds,59)} * * * * ?", handler)
    state.logPollSeconds = seconds
}
private runEvery5Seconds_(String handler) {
    schedule("*/5 * * * * ?", handler)
    state.logPollSeconds = 5
}

// ─────────────────────────────────────────────────────────────
//  Commands
// ─────────────────────────────────────────────────────────────

def refresh() { pollStatus(); pollLog() }

// 안전망: 이전 드라이버/스케줄이 poll() 을 호출할 수 있음 (capability Polling 등).
// 내 스케줄은 pollLog/pollStatus 를 쓰지만, 좀비 스케줄 대비해 매핑해 둠.
def poll() { pollLog() }

def lock() {
    if (txtEnable) log.info "잠금 요청(close)"
    control("close") { ok ->
        if (ok) {
            Map e = [t: nowStamp(), kind: "close", user: "Hubitat", method: "Hubitat", msg: "Hubitat 에서 잠금"]
            publishAccess(e)
            sendLockEvent(e, "digital")
            setOverride(true)
        }
    }
}

def unlock() {
    if (txtEnable) log.info "열기 요청(open)"
    control("open") { ok ->
        if (ok) {
            Map e = [t: nowStamp(), kind: "open", user: "Hubitat", method: "Hubitat", msg: "Hubitat 에서 열기"]
            publishAccess(e)
            addHistory(e)
            state.hubOpenAt = now()   // 곧 클라우드 로그에 같은 열림이 올라옴 → 중복 방지용
            sendLockEvent(e, "digital")
            setOverride(false)
        }
    }
}

// capability Lock 의 open 커맨드가 있는 기기도 있어 매핑(= unlock)
def open() { unlock() }

// ─────────────────────────────────────────────────────────────
//  API — 로그인 / 토큰
// ─────────────────────────────────────────────────────────────

def login() { doLogin() }

private doLogin() {
    Map body = [
        emailId:    settings.emailId?.trim(),
        hashedPwd:  state.hashedPwd,
        phoneToken: PHONE_TOKEN,
        appSource:  "0",
        lang:       "0"   // ⚠️ 반드시 숫자 "0". "ko" 면 반쪽 토큰 발급됨.
    ]
    if (logEnable) log.debug "POST /api_v2/login"
    boolean ok = false
    try {
        httpPostJson([uri: BASE_URL, path: "/api_v2/login",
                      body: body, timeout: 20]) { resp ->
            def c = resp.data?.contents
            if (c?.loginResult == 0 && c?.token) {
                state.token = c.token
                state.tokenPwd = c.tokenPwd
                state.memberId = c.memberInfo?.memberId
                ok = true
                sendEvent(name: "apiStatus", value: "ok")
                if (txtEnable) log.info "로그인 성공"
            } else {
                log.error "로그인 거부: ${c?.loginMessage ?: c?.loginResult}"
                sendEvent(name: "apiStatus", value: "auth_failed")
            }
        }
    } catch (e) {
        log.error "로그인 실패: ${e.message}"
        sendEvent(name: "apiStatus", value: "error")
    }
    return ok
}

/** 인증 필요한 호출. 401/403 이면 재로그인 1회 후 재시도. closure(status, data) */
private authed(String method, String path, Map body, Closure cb, int timeoutSec = QUICK_TIMEOUT, Map query = null) {
    if (!state.token) { if (!doLogin()) { cb(0, null); return } }

    Closure doCall = { boolean retried ->
        Map params = [
            uri: BASE_URL, path: path,
            headers: ["Content-Type":"application/json",
                      "Authorization": state.token,
                      "AuthorizationPwd": state.tokenPwd],
            timeout: timeoutSec
        ]
        if (query) params.query = query
        if (body != null) {
            params.body = JsonOutput.toJson(body)
            params.requestContentType = "application/json"
        }
        try {
            Closure handler = { resp ->
                def d = resp.data
                // content-type 이 JSON 이 아니게 오면 문자열/스트림일 수 있음 → 직접 파싱
                if (d != null && !(d instanceof Map) && !(d instanceof List)) {
                    try { d = new JsonSlurper().parseText(d instanceof String ? d : d.text) } catch (ignored) { }
                }
                cb(resp.status, d)
                state.authRetry = 0
            }
            if (method == "GET") httpGet(params, handler)
            else if (method == "PUT") httpPut(params + [requestContentType:"application/json"], handler)
            else httpPostJson(params, handler)
        } catch (groovyx.net.http.HttpResponseException he) {
            int sc = he.statusCode
            if (sc in [401,403] && !retried) {
                if (logEnable) log.debug "${path} → ${sc}, 재로그인"
                if (doLogin()) { doCall(true) } else { cb(sc, null) }
            } else {
                log.error "${path} HTTP ${sc}: ${he.message}"
                sendEvent(name: "apiStatus", value: sc in [401,403] ? "auth_failed" : "error")
                cb(sc, null)
            }
        } catch (java.net.SocketTimeoutException te) {
            // 타임아웃은 "실패"가 아니라 도어락 수면/게이트웨이 왕복 지연일 수 있음.
            // apiStatus 를 error 로 떨구지 않고, 호출자가 status=-1 로 구분하게 함.
            log.warn "${path} 타임아웃 (도어락 수면/게이트웨이 지연 가능) — 재시도 대기"
            cb(-1, null)
        } catch (e) {
            String m = e.message ?: ""
            if (m.toLowerCase().contains("timed out") || m.toLowerCase().contains("timeout")) {
                log.warn "${path} 타임아웃 — 도어락 수면/지연 가능"
                cb(-1, null)
            } else {
                log.error "${path} 오류: ${m}"
                sendEvent(name: "apiStatus", value: "error")
                cb(0, null)
            }
        }
    }
    doCall(false)
}

// ─────────────────────────────────────────────────────────────
//  controlDevice (open / close / get_status)
// ─────────────────────────────────────────────────────────────

/** controlType 전송. cb(boolean ok) */
private control(String controlType, Closure cb = null) {
    String devId = resolvedDeviceId()
    if (!devId) { if (cb) cb(false); return }
    String option = (controlType == "get_status") ? "" : "1"
    authed("PUT", "/api_v2/controlDevice/${devId}",
           [controlType: controlType, optionValue: option], { status, data ->
        boolean ok = (status == 200)
        if (ok) sendEvent(name: "apiStatus", value: "ok")
        else if (status == -1) {
            // 타임아웃: 명령이 도어락에 전달됐을 수도, 아닐 수도. 개폐는 낙관적 반영 후 재조회.
            log.warn "${controlType} 응답 타임아웃 — 명령은 전달됐을 수 있음. 잠시 후 상태 재조회"
            runIn(8, "pollStatus")
        }
        if (cb) cb(ok)
    }, CONTROL_TIMEOUT)
}

def pollStatus() {
    String devId = resolvedDeviceId()
    if (!devId) return
    authed("PUT", "/api_v2/controlDevice/${devId}",
           [controlType: "get_status", optionValue: ""], { status, data ->
        if (status == -1) {
            // 타임아웃: 도어락 수면. 이전 상태 유지(HA 통합과 동일 정책), 몇 번까지 관대.
            state.statusMiss = (state.statusMiss ?: 0) + 1
            if (logEnable) log.debug "상태 조회 타임아웃 ${state.statusMiss}회 — 이전값 유지"
            return
        }
        if (status != 200) return
        state.statusMiss = 0
        // contents.controlDeviceMessage 가 JSON 문자열 → 한 번 더 파싱
        def msg = data?.contents?.controlDeviceMessage
        if (!msg) { if (logEnable) log.debug "상태 응답 비어있음(도어락 수면?)"; return }
        def st
        try { st = new JsonSlurper().parseText(msg.toString()) }
        catch (e) { log.warn "상태 파싱 실패: ${e.message} / ${msg}"; return }

        applyStatus(st)
    }, CONTROL_TIMEOUT)
}

private applyStatus(Map st) {
    state.lastStatusAt = nowStamp()
    // deadBolt 1=잠김
    def db = st.deadBolt
    if (db != null && state.override == null) {
        String lv = (db.toString() == "1") ? "locked" : "unlocked"
        if (device.currentValue("lock") != lv)
            sendEvent(name: "lock", value: lv, descriptionText: "${device.displayName} ${lv}")
    }
    if (st.battery != null) {
        Integer b = st.battery as Integer
        if (device.currentValue("battery") != b)
            sendEvent(name: "battery", value: b, unit: "%")
    }
    sendIfChanged("subLatch", st.subLatch)
    sendIfChanged("systemMode", st.systemMode)
    sendIfChanged("cardCount", st.cardCount)
    sendIfChanged("fingerprintCount", st.fingerPrintCount)
    sendIfChanged("passwordCount", st.passwordCount)
    if (logEnable) log.debug "상태: deadBolt=${db} battery=${st.battery}"
}

// ─────────────────────────────────────────────────────────────
//  retrieveLog — 실시간 출입 감지 (도어락 안 깨움)
// ─────────────────────────────────────────────────────────────

/** retrieveLog 공통 호출. ⚠️ 쿼리는 반드시 query 맵으로 (path 에 붙이면 '?' 가 %3F 로 인코딩됨) */
private fetchLogs(int length, Closure cb) {
    String devId = resolvedDeviceId()
    if (!devId) return
    Map q = [pMemberId: "", pLogType: "", pLogStart: "0", pLogLength: length.toString(), pTimezone: "+9:00"]
    authed("GET", "/api_v2/retrieveLog/page/${devId}", null, { status, data ->
        if (status != 200) return
        def logs = data?.contents?.retrieveLogList
        if (!(logs instanceof List) || !logs) {
            if (logEnable) log.debug "retrieveLog 결과 없음: ${data?.toString()?.take(200)}"
            return
        }
        cb(logs)
    }, QUICK_TIMEOUT, q)
}

def pollLog() {
    fetchLogs(20) { List logs -> handleLogs(logs) }
}

/** logs 는 최신순. 마지막으로 본 시각 이후 항목을 오래된 것부터 전부 처리. */
private handleLogs(List logs) {
    String newest = logs[0]?.logDateTime?.toString()
    if (!newest) return

    // 최초 1회: 기준점만 잡고 lock 이벤트는 내지 않음. 표시용 속성/내역만 채움.
    if (state.lastLogDt == null) {
        state.lastLogDt = newest
        state.history = logs.take(histMax()).collect { historyRow(toEntry(it)) }
        publishAccess(toEntry(logs[0]))
        renderHistory()
        if (txtEnable) log.info "출입 로그 기준점 설정: ${newest}"
        return
    }

    List fresh = logs.findAll { (it.logDateTime?.toString() ?: "") > state.lastLogDt }
    if (!fresh) return
    state.lastLogDt = newest
    fresh.reverse().each { processEntry(toEntry(it)) }
}

/** 서버 로그 1건 → 내부 표현 */
private Map toEntry(Map raw) {
    String code = raw.logCode?.toString()
    String kind = (code in [LOG_CODE_OPEN, LOG_CODE_OPEN_LONG]) ? "open" :
                  (code == LOG_CODE_CLOSE) ? "close" : "other"
    String msg = (raw.logMessage ?: "").toString().trim()
    return [
        t:      raw.logDateTime?.toString(),
        kind:   kind,
        user:   (raw.nickname ?: "").toString().trim(),
        method: normMethod(raw.mediaType, msg),
        raw:    (raw.mediaType ?: "").toString(),
        code:   code,
        msg:    msg
    ]
}

private processEntry(Map e) {
    // 속성을 먼저 갱신 → lock 이벤트로 트리거된 룰이 lastUnlockedBy 등을 바로 읽을 수 있게
    publishAccess(e)

    if (e.kind == "open") {
        // Hubitat 에서 방금 연 것이 클라우드 로그로 되돌아온 경우: 내역 중복 추가 안 함
        boolean echo = state.hubOpenAt && (now() - (state.hubOpenAt as Long)) < 60000
        state.remove("hubOpenAt")
        if (echo) {
            List h = (state.history ?: []) as List
            if (h) { h[0] = historyRow(e + [user: e.user ?: "Hubitat"]); state.history = h; renderHistory() }
        } else {
            addHistory(e)
        }
        if (txtEnable) log.info "열림 감지: ${describe(e)}"
        sendLockEvent(e, isRemote(e) ? "digital" : "physical")
        setOverride(false)   // autoCloseSeconds 뒤 자동 locked 복귀
    } else if (e.kind == "close") {
        addHistory(e)
        if (txtEnable) log.info "잠김 감지: ${describe(e)}"
        sendLockEvent(e, isRemote(e) ? "digital" : "physical")
        setOverride(true)
    } else {
        addHistory(e)
        if (txtEnable) log.info "기타 로그: ${e.msg ?: describe(e)}"
    }
}

/** lock 이벤트 발행 — descriptionText 와 data 에 누가/어떻게 포함 */
private sendLockEvent(Map e, String type) {
    String v = (e.kind == "open") ? "unlocked" : "locked"
    sendEvent(name: "lock", value: v, type: type,
              descriptionText: "${device.displayName} ${describe(e)}",
              data: [user: e.user ?: "", method: e.method ?: "", time: e.t ?: "",
                     message: e.msg ?: "", mediaType: e.raw ?: "", codeName: e.user ?: ""])
}

/** 누가/어떻게 속성 갱신 */
private publishAccess(Map e) {
    setAttr("lastAccess", describe(e))
    setAttr("lastAccessMessage", e.msg ?: "-")
    setAttr("lastAccessTime", e.t ?: "-")
    setAttr("lastAccessUser", e.user ?: "-")
    setAttr("lastAccessMethod", e.method ?: "-")
    setAttr("lastAccessType", e.kind)
    if (e.kind == "open") {
        setAttr("lastUnlockedBy", e.user ?: "-")
        setAttr("lastUnlockMethod", e.method ?: "-")
        setAttr("lastUnlockTime", e.t ?: "-")
        setAttr("lastCodeName", e.user ?: "-")
    } else if (e.kind == "close") {
        setAttr("lastLockedBy", e.user ?: "-")
        setAttr("lastLockMethod", e.method ?: "-")
    }
}

/** "열림 · 권민 · 지문" */
private String describe(Map e) {
    String act = (e.kind == "open") ? "열림" : (e.kind == "close") ? "잠김" : "기타"
    List parts = [act]
    if (e.user) parts << e.user
    if (e.method && e.method != "알 수 없음") parts << e.method
    if (e.kind == "other" && e.msg) parts << e.msg
    return parts.join(" · ")
}

private boolean isRemote(Map e) { e.method in ["앱(원격)", "Hubitat"] }

/**
 * mediaType(서버 원본) → 표시용 방식.
 * 서버 값 체계가 문서화돼 있지 않아 키워드로 분류하고, 모르는 값은 원본 그대로 노출.
 * (diagnose 로 원본 값을 확인해 필요하면 아래 분류를 보강)
 */
private String normMethod(def media, String msg) {
    String m = (media ?: "").toString().trim()
    return classify(m) ?: classify(msg) ?: (m ?: "알 수 없음")
}

private String classify(String text) {
    if (!text) return null
    String h = text.toLowerCase()
    if (h =~ /지문|finger/)                                   return "지문"
    if (h =~ /카드|card|rfid|nfc|태그/)                        return "카드"
    if (h =~ /비밀번호|비번|password|passcode|pincode|keypad/)  return "비밀번호"
    if (h =~ /앱|app|원격|remote|phone|mobile|스마트폰|블루투스|bluetooth|ble/) return "앱(원격)"
    if (h =~ /수동|manual|내부|실내|inside|버튼|button|thumb/)   return "수동(실내)"
    if (h =~ /자동|auto/)                                      return "자동잠김"
    if (h =~ /비상|열쇠|emergency/)                            return "비상키"
    return null
}

// ── 출입 내역 (accessHistory) ──

private int histMax() { (settings.historyCount ?: "5") as Integer }

private Map historyRow(Map e) {
    [t: e.t ?: "", kind: e.kind, user: e.user ?: "", method: e.method ?: "",
     msg: (e.kind == "other") ? (e.msg ?: "").take(40) : ""]
}

private addHistory(Map e) {
    List h = (state.history ?: []) as List
    h.add(0, historyRow(e))
    state.history = h.take(histMax())
    renderHistory()
}

/** 대시보드 Attribute 타일용 HTML. Hubitat 속성 1024자 제한에 맞춰 오래된 행부터 줄임. */
private renderHistory() {
    List h = ((state.history ?: []) as List).take(histMax())
    String html = ""
    while (true) {
        html = "<table style='width:100%;font-size:0.8em'>" + h.collect { r ->
            String icon = (r.kind == "open") ? "🔓" : (r.kind == "close") ? "🔒" : "ℹ️"
            String ts = r.t ?: ""
            if (ts.length() >= 16) ts = ts.substring(5, 16)      // MM-dd HH:mm
            String who = r.user ?: (r.kind == "other" ? (r.msg ?: "") : "-")
            String how = (r.method && r.method != "알 수 없음") ? r.method : ""
            "<tr><td>${ts}</td><td>${icon}</td><td>${esc(who)}</td><td>${esc(how)}</td></tr>"
        }.join("") + "</table>"
        if (html.length() <= 1024 || h.size() <= 1) break
        h = h.take(h.size() - 1)
    }
    setAttr("accessHistory", html)
}

private String esc(def v) {
    (v ?: "").toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

// ─────────────────────────────────────────────────────────────
//  override (실시간 상태 임시 반영 + 자동 복귀)
// ─────────────────────────────────────────────────────────────

/** 로그/명령으로 알아낸 lock 상태를 임시 표시. 열림이면 autoCloseSeconds 뒤 자동 잠김. */
private setOverride(boolean locked) {
    state.override = locked
    unschedule("clearOverride")
    if (!locked) {
        Integer secs = (settings.autoCloseSeconds ?: 5) as Integer
        runIn(secs, "clearOverride", [overwrite: true])
    }
}

def clearOverride() {
    // 자동잠김 기기 → 열림 후 복귀 시 locked 로
    state.override = null
    if (device.currentValue("lock") != "locked") {
        // 도어락은 닫힘 로그를 안 남김 → 자동잠김으로 합성 (사용자 없음)
        Map e = [t: nowStamp(), kind: "close", user: "", method: "자동잠김", msg: "자동 잠김(합성)"]
        setAttr("lastLockedBy", "-")
        setAttr("lastLockMethod", "자동잠김")
        sendLockEvent(e, "physical")
    }
    if (logEnable) log.debug "override 해제 → locked 복귀"
}

// ─────────────────────────────────────────────────────────────
//  헬퍼
// ─────────────────────────────────────────────────────────────

def diagnose() {
    log.info "=== Solity 진단 ==="
    log.info "emailId      : ${settings.emailId ?: '(없음)'}"
    log.info "hashedPwd    : ${state.hashedPwd ? '설정됨(44자)' : '(없음)'}"
    log.info "token        : ${state.token ? '있음' : '(없음)'}"
    log.info "myDeviceId   : ${resolvedDeviceId() ?: '(미확인)'}"
    log.info "apiStatus    : ${device.currentValue('apiStatus')}"
    log.info "마지막 상태조회: ${state.lastStatusAt ?: '(없음)'}"
    log.info "마지막 로그DT : ${state.lastLogDt ?: '(없음)'}"
    log.info "로그폴링(초)  : ${state.logPollSeconds ?: '?'}"
    log.info "=================="
    // 최근 출입 로그 원본 — mediaType/logCode 실제 값 확인용
    fetchLogs(5) { List logs ->
        logs.each {
            log.info "  로그: dt=${it.logDateTime} code=${it.logCode} type=${it.logType} " +
                     "mediaType=${it.mediaType} nick=${it.nickname} msg=${it.logMessage}"
        }
    }
    // 기기 목록도 한번 찍어줌
    authed("GET", "/api_v2/myDevice", null) { status, data ->
        if (status == 200) {
            data?.contents?.myDeviceList?.each {
                log.info "  기기: id=${it.myDeviceId} nick=${it.myDeviceNickName} battery=${it.battery}"
            }
        }
    }
}

private String resolvedDeviceId() {
    if (settings.myDeviceId) return settings.myDeviceId.trim()
    if (state.autoDeviceId) return state.autoDeviceId
    // 자동 해석: myDevice 첫 기기
    authed("GET", "/api_v2/myDevice", null) { status, data ->
        if (status == 200) {
            def first = data?.contents?.myDeviceList?.getAt(0)
            if (first) {
                state.autoDeviceId = first.myDeviceId
                if (txtEnable) log.info "myDeviceId 자동 설정: ${state.autoDeviceId} (${first.myDeviceNickName})"
            }
        }
    }
    return state.autoDeviceId
}

private String sha256b64(String s) {
    def md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(s.getBytes("UTF-8")).encodeBase64().toString()
}

private String nowStamp() {
    new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

private sendIfChanged(String name, def value) {
    if (value == null || value == "") return
    if (device.currentValue(name)?.toString() != value.toString())
        sendEvent(name: name, value: value)
}

/** 값이 바뀌었을 때만 이벤트. sendIfChanged 와 달리 빈 값 대신 넘겨준 값을 그대로 기록. */
private setAttr(String name, def value) {
    if (value == null) return
    if (device.currentValue(name)?.toString() != value.toString())
        sendEvent(name: name, value: value)
}
