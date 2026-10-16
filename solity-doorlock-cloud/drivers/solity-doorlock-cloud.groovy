/**
 *  Solity Doorlock (Cloud) — Hubitat Driver
 *
 *  스마트솔리티(SOLITY) 스마트 도어락을 솔리티 클라우드 API로 직접 제어합니다.
 *  SmartThings 불필요. 원격 열기/잠그기 + 실제 데드볼트 상태 + 문 센서 + 출입 감지.
 *
 *  동작 구조:
 *   - 열림 로그 폴링(logSeconds, 기본 10초): retrieveLog → 열림을 수초 내 감지.
 *     클라우드 기록만 읽으므로 도어락을 깨우지 않음.
 *   - 상태 폴링(statusMinutes, 기본 30분): get_status → deadBolt(잠김)/subLatch(문)/배터리.
 *     ⚠️ 도어락을 깨우므로(배터리 소모) 느리게.
 *   - 도어락은 "열림"만 기록하고 잠김 기록은 없음. 그래서 열림을 감지하면 상태를 짧게
 *     다시 읽어 실제 데드볼트가 나온 시점에 locked 로 바꿈(타이머로 잠김 처리하지 않음).
 *     다음 확인 시점은 문 상태로 정함:
 *       잠김                 → 끝
 *       문 닫힘 + 빗장 안 나옴 → 1.5초 후 (3번까지, 이후 수동잠금 모드로 보고 간격 늘림)
 *       문 열림              → 5초 → 10초 → 20초 … 최대 2분 간격
 *     15분 지나도 안 잠기면 평소 상태 조회 주기로 복귀.
 *   - 도어락이 응답하지 않으면 1분 → 2분 → 5분(이후 5분마다) 간격으로 다시 조회.
 *
 *  Author : kwon2288
 *  Version: 1.2.3 (2026-10-15)
 *    - FIX: 속성 비교 시 허브 캐시를 건너뛰고 실제 현재값을 읽음 (currentValue(name, true))
 *           — 캐시가 늦어 confirmed/contact 이벤트가 빠지던 문제
 *    - FIX: 정기 조회 첫 실패(바로 재조회할 때)는 confirmed=false 로 바꾸지 않음
 *  Version: 1.2.2 (2026-10-15)
 *    - NEW: 같은 계정을 폰 앱과 같이 쓸 때 서로 로그아웃시키는 문제 완화
 *           토큰 만료 전에 401/403 이 오면 "앱이 로그인함"으로 보고 바로 재로그인하지 않음
 *           → apiStatus=session_taken, 설정한 시간(기본 30분) 뒤 재로그인. 그동안 조회 중단.
 *           열기/잠그기 명령이나 login 명령은 바로 재로그인.
 *           재로그인 후 밀린 열림 기록은 이벤트 없이 내역에만 반영
 *  Version: 1.2.1 (2026-10-15)
 *    - FIX: 잠긴 것을 읽었는데 subLatch 가 빠진 응답이면 문을 닫힘으로 반영
 *           (데드볼트가 나왔으면 문은 닫혀 있음 — 이전엔 다음 정기 조회까지 contact=open 으로 남음)
 *    - FIX: 정기 조회 첫 호출이 도어락을 깨우다 타임아웃 나면 바로 한 번 더 조회한 뒤에만 무응답 처리
 *           (매 정기 조회마다 notAnswering 이 true→false 로 깜빡이던 문제)
 *    - FIX: 상태 조회가 겹치지 않게 (동시에 두 번 보내면 하나는 타임아웃)
 *  Version: 1.2.0 (2026-10-14)
 *    - NEW: lock 은 실제 데드볼트 값 기준. 열림 후 문 상태에 맞춰 잠김을 확인
 *           (기존 "열림 후 N초 뒤 자동 locked" 타이머 제거)
 *    - NEW: ContactSensor(contact) — 도어락 문 감지 래치(subLatch: 1=닫힘, 0=열림)
 *           + door(open/closed/unknown), confirmed
 *    - NEW: 열림 방식을 mediaType 코드로 판정
 *           (1 비밀번호, 5 카드, 7 원격, 8 지문, 11 스마트오픈, 32 실내)
 *    - NEW: 도어락 무응답 시 1·2·5분 간격 재조회(notAnswering)
 *    - NEW: 잠김 확인 과정 기록(verifying, lastVerifyResult/Seconds/Trace)
 *    - CHG: 상태 조회를 비동기 HTTP 로 (허브 스레드 점유 없음), 열림 직후 조회는 3초에 끊고 재시도
 *    - CHG: 잠김 로그 코드(0) 처리 제거 — 도어락은 열림만 기록
 *  Version: 1.1.0 (2026-10-07)
 *    - FIX: retrieveLog 쿼리를 query 맵으로 전달 (출입 로그가 비어 오던 문제)
 *    - NEW: 누가/어떤 방식으로 열었는지 표시, accessHistory, diagnose 로그 원본 출력
 *  Licensed under the Apache License, Version 2.0
 *
 *  Credit: 클라우드 API 엔드포인트, mediaType 코드, 잠김 확인 전략은
 *          github.com/tpgi2013-hue/Smart-Solity-Doorlock (MIT, v1.7.3) 의 HA 통합을 참고함.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String BASE_URL    = "https://www.smartsolity.com"
@Field static final String PHONE_TOKEN = "ha-solity-hubitat"

@Field static final int STATUS_TIMEOUT  = 15   // 정기 상태 조회 / 열기·잠그기
@Field static final int QUICK_TIMEOUT   = 20   // login / retrieveLog (도어락 안 깨움)
@Field static final int VERIFY_TIMEOUT  = 3    // 열림 직후 조회: 자주 멈추므로 짧게 끊고 재시도

// 잠김 확인 일정 (원본 v1.7.x const.py)
@Field static final long VERIFY_CLOSED_RECHECK_MS = 1500
@Field static final int  VERIFY_CLOSED_FAST_TRIES = 3
@Field static final int  VERIFY_OPEN_FIRST        = 5
@Field static final int  VERIFY_UNKNOWN_RECHECK   = 3
@Field static final int  VERIFY_BACKOFF_MAX       = 120
@Field static final int  VERIFY_WINDOW            = 900
@Field static final int  VERIFY_MAX_POLLS         = 40
@Field static final int  VERIFY_POLL_RETRIES      = 3
@Field static final int  VERIFY_TRACE_LEN         = 15
@Field static final List RECOVER_DELAYS           = [60, 120, 300]

// retrieveLog: 열림 기록 코드
@Field static final List OPEN_LOG_CODES = ["1", "7"]
// mediaType → 방식 (원본에서 실측된 값)
@Field static final Map MEDIA_TYPES = [
    "1": "비밀번호", "5": "카드", "7": "원격(앱)", "8": "지문", "11": "스마트오픈", "32": "실내"
]
@Field static final String MEDIA_REMOTE = "7"

metadata {
    definition(
        name:      "Solity Doorlock (Cloud)",
        namespace: "kwon2288",
        author:    "kwon2288"
    ) {
        capability "Lock"
        capability "ContactSensor"
        capability "Battery"
        capability "Refresh"
        capability "Sensor"

        // ── 누가 / 어떻게 ──
        attribute "lastAccess",       "string"   // "열림 · 홍길동 · 지문"
        attribute "lastAccessMessage","string"
        attribute "lastAccessTime",   "string"
        attribute "lastAccessUser",   "string"
        attribute "lastAccessMethod", "string"
        attribute "lastAccessType",   "enum", ["open", "close", "other"]
        attribute "lastUnlockedBy",   "string"
        attribute "lastUnlockMethod", "string"
        attribute "lastUnlockTime",   "string"
        attribute "lastLockedBy",     "string"
        attribute "lastLockMethod",   "string"
        attribute "lastLockTime",     "string"
        attribute "lastCodeName",     "string"
        attribute "accessHistory",    "string"

        // ── 잠김 확인 / 문 ──
        attribute "door",             "enum", ["open", "closed", "unknown"]
        attribute "confirmed",        "enum", ["true", "false"]   // 값을 도어락에서 다시 읽었는지
        attribute "verifying",        "enum", ["true", "false"]   // 열림 후 잠김 확인 중
        attribute "notAnswering",     "enum", ["true", "false"]   // 도어락 무응답 → 느린 재조회 중
        attribute "lastVerifyResult", "string"   // locked / still_unlocked / no_answer
        attribute "lastVerifySeconds","number"
        attribute "lastVerifyTrace",  "string"   // "0.9s 문열림 → 6.2s 닫힘·안잠김 → 7.9s 잠김"

        // ── 상태 원본 ──
        attribute "subLatch",         "string"
        attribute "systemMode",       "string"
        attribute "cardCount",        "number"
        attribute "fingerprintCount", "number"
        attribute "passwordCount",    "number"
        attribute "apiStatus",        "enum", ["ok", "auth_failed", "error", "session_taken"]
        attribute "sessionResumeAt",  "string"   // 앱에 세션을 내준 뒤 다시 로그인할 시각

        command "login"
        command "diagnose"
    }

    preferences {
        input name: "emailId", type: "text",
              title: "<b>솔리티 이메일(ID)</b>", required: true
        input name: "password", type: "password",
              title: "<b>솔리티 비밀번호</b>",
              description: "SHA-256 해시로만 서버에 전송되며 state 에는 해시만 저장됩니다.",
              required: true
        input name: "myDeviceId", type: "text",
              title: "<b>myDeviceId</b>",
              description: "비워두면 계정의 첫 번째 기기를 사용", required: false

        input name: "logSeconds", type: "enum",
              title: "열림 감지 주기(초) — 도어락 안 깨움",
              options: ["5":"5초","10":"10초","15":"15초","30":"30초","60":"60초"],
              defaultValue: "10", required: true
        input name: "statusMinutes", type: "enum",
              title: "상태/배터리 폴링 주기(분) — ⚠️ 도어락 깨움",
              options: ["10":"10분","30":"30분","60":"60분","240":"4시간","720":"12시간"],
              defaultValue: "30", required: true
        input name: "sessionWait", type: "enum",
              title: "앱이 같은 계정으로 로그인했을 때 허브 재로그인 대기",
              description: "솔리티는 계정당 한 곳만 로그인됩니다. 허브가 바로 재로그인하면 앱이 로그아웃되므로 이 시간만큼 양보합니다.",
              options: ["10":"10분","30":"30분","60":"1시간","180":"3시간","manual":"수동 (login 명령 누를 때까지)"],
              defaultValue: "30", required: true
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
    unschedule()
    initialize()
}

def updated() {
    log.info "Solity Doorlock 설정 갱신"
    unschedule()

    String newHash = password ? sha256b64(password) : null
    if (newHash && newHash != state.hashedPwd) {
        state.hashedPwd = newHash
        state.token = null
        state.tokenPwd = null
        if (txtEnable) log.info "비밀번호 해시 갱신 → 토큰 초기화"
    }
    // v1.1 이하 잔재 정리
    ["override", "statusMiss", "authRetry"].each { state.remove(it) }
    try { device.removeSetting("autoCloseSeconds") } catch (ignored) { }
    initialize()
}

def initialize() {
    if (logEnable) runIn(1800, "logsOff")

    state.remove("verify")
    state.remove("recover")
    setAttr("verifying", "false")
    setAttr("notAnswering", "false")

    if (!emailId || !state.hashedPwd) {
        log.warn "이메일/비밀번호를 입력하세요"
        sendEvent(name: "apiStatus", value: "auth_failed")
        return
    }

    int secs = ((logSeconds ?: "10") as Integer)
    schedule("*/${Math.max(5, Math.min(secs, 59))} * * * * ?", "pollLog")
    state.logPollSeconds = secs

    switch (statusMinutes ?: "30") {
        case "10":  runEvery10Minutes("pollStatus"); break
        case "30":  runEvery30Minutes("pollStatus"); break
        case "60":  runEvery1Hour("pollStatus"); break
        case "240": runEvery3Hours("pollStatus"); break
        default:    schedule("0 0 */12 * * ?", "pollStatus"); break
    }

    runIn(3, "pollStatus")
    runIn(6, "pollLog")
}

def logsOff() {
    log.warn "디버그 로그 자동 해제"
    device.updateSetting("logEnable", [value:"false", type:"bool"])
}

// v1.1 이하에서 예약된 작업이 남아 있을 수 있어 빈 핸들러로 둠
def clearOverride() { }
def poll() { pollLog() }

// ─────────────────────────────────────────────────────────────
//  Commands
// ─────────────────────────────────────────────────────────────

def refresh() { pollStatus(); pollLog() }

def unlock() {
    if (txtEnable) log.info "열기 요청(open)"
    int st = control("open")
    if (st == 200 || st == -1) {
        // -1(타임아웃): 명령이 전달됐을 수 있음 → 잠김 확인으로 실제 상태를 읽음
        Map e = [t: nowStamp(), kind: "open", user: "Hubitat", method: "Hubitat", msg: "Hubitat 에서 열기"]
        publishAccess(e)
        addHistory(e)
        state.hubOpenAt = now()
        state.lockAddHistory = true
        markUnlocked(e, false, "digital")
        startVerify()
    }
}

def open() { unlock() }

def lock() {
    if (txtEnable) log.info "잠금 요청(close)"
    int st = control("close")
    if (st == 200 || st == -1) {
        state.pendingLockBy = "Hubitat"
        state.lockAddHistory = true
        startVerify()   // 데드볼트가 나온 것을 읽은 뒤 locked
    }
}

// ─────────────────────────────────────────────────────────────
//  API — 로그인 / 동기 호출 (로그·명령)
// ─────────────────────────────────────────────────────────────

def login() {
    clearSessionTaken()
    if (doLogin()) { state.catchUp = true; runIn(2, "pollStatus") }
}

private boolean doLogin() {
    Map body = [
        emailId:    settings.emailId?.trim(),
        hashedPwd:  state.hashedPwd,
        phoneToken: PHONE_TOKEN,
        appSource:  "0",
        lang:       "0"   // ⚠️ 반드시 숫자 "0". "ko" 면 반쪽 토큰 발급됨.
    ]
    boolean ok = false
    try {
        httpPostJson([uri: BASE_URL, path: "/api_v2/login", body: body, timeout: QUICK_TIMEOUT]) { resp ->
            def c = resp.data?.contents
            if (c?.loginResult == 0 && c?.token) {
                state.token = c.token
                state.tokenPwd = c.tokenPwd
                state.memberId = c.memberInfo?.memberId
                state.tokenAt = now()
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

private Map authHeaders() {
    ["Content-Type": "application/json", "Authorization": state.token, "AuthorizationPwd": state.tokenPwd]
}

/** 동기 인증 호출. 401/403 이면 재로그인 1회. cb(status, data) — status -1 = 타임아웃 */
private authed(String method, String path, Map body, Closure cb, int timeoutSec = QUICK_TIMEOUT, Map query = null) {
    if (!canAuth()) { cb(0, null); return }
    if (!state.token && !doLogin()) { cb(0, null); return }

    Closure doCall
    doCall = { boolean retried ->
        Map params = [uri: BASE_URL, path: path, headers: authHeaders(), timeout: timeoutSec]
        if (query) params.query = query
        if (body != null) {
            params.body = JsonOutput.toJson(body)
            params.requestContentType = "application/json"
        }
        try {
            Closure handler = { resp -> cb(resp.status, toJson(resp.data)) }
            if (method == "GET") httpGet(params, handler)
            else httpPut(params, handler)
        } catch (groovyx.net.http.HttpResponseException he) {
            int sc = he.statusCode
            if (sc in [401, 403] && !retried) {
                if (isTakeover()) { markSessionTaken(); cb(sc, null); return }
                if (logEnable) log.debug "${path} → ${sc}, 재로그인"
                if (doLogin()) doCall(true) else cb(sc, null)
            } else {
                log.error "${path} HTTP ${sc}: ${he.message}"
                sendEvent(name: "apiStatus", value: sc in [401, 403] ? "auth_failed" : "error")
                cb(sc, null)
            }
        } catch (e) {
            String m = (e.message ?: "").toLowerCase()
            if (e instanceof java.net.SocketTimeoutException || m.contains("timed out") || m.contains("timeout")) {
                log.warn "${path} 타임아웃 — 도어락 무응답 가능"
                cb(-1, null)
            } else {
                log.error "${path} 오류: ${e.message}"
                sendEvent(name: "apiStatus", value: "error")
                cb(0, null)
            }
        }
    }
    doCall(false)
}

/** 열기/잠그기. 반환: HTTP 상태, -1 = 타임아웃 */
private int control(String controlType) {
    if (state.sessionTakenAt) {
        log.warn "명령 실행을 위해 재로그인 (폰 앱은 로그아웃될 수 있음)"
        clearSessionTaken()
        state.catchUp = true
    }
    String devId = resolvedDeviceId()
    if (!devId) return 0
    int result = 0
    authed("PUT", "/api_v2/controlDevice/${devId}", [controlType: controlType, optionValue: "1"], { status, data ->
        result = status as int
        if (status == 200) sendEvent(name: "apiStatus", value: "ok")
        else if (status == -1) log.warn "${controlType} 응답 타임아웃 — 명령은 전달됐을 수 있어 상태를 다시 읽습니다"
        else log.error "${controlType} 실패 (HTTP ${status})"
    }, STATUS_TIMEOUT)
    return result
}

// ─────────────────────────────────────────────────────────────
//  상태 조회 (비동기) — get_status
// ─────────────────────────────────────────────────────────────

/** get_status 비동기 호출. 결과는 statusResp(resp, data) 에서 data.kind 로 분기 */
private requestStatus(String kind, int timeoutSec, Map extra = [:]) {
    String devId = resolvedDeviceId()
    if (!devId) return
    if (!canAuth()) return
    if (!state.token && !doLogin()) { onStatus(kind, null, extra); return }
    Map params = [
        uri: BASE_URL, path: "/api_v2/controlDevice/${devId}",
        headers: authHeaders(),
        requestContentType: "application/json",
        contentType: "application/json",
        body: JsonOutput.toJson([controlType: "get_status", optionValue: ""]),
        timeout: timeoutSec
    ]
    state.statusBusyAt = now()
    asynchttpPut("statusResp", params, extra + [kind: kind, timeoutSec: timeoutSec])
}

def statusResp(resp, Map data) {
    state.remove("statusBusyAt")
    int sc = 0
    try { sc = resp.status as int } catch (ignored) { }
    if (sc in [401, 403] && !data.retried) {
        if (isTakeover()) { markSessionTaken(); return }
        if (logEnable) log.debug "get_status → ${sc}, 재로그인"
        if (doLogin()) { requestStatus(data.kind, data.timeoutSec as int, data + [retried: true]); return }
    }
    Map st = null
    if (sc == 200) {
        try {
            def body = toJson(resp.getData())
            def msg = body?.contents?.controlDeviceMessage
            if (msg) st = new JsonSlurper().parseText(msg.toString()) as Map
            if (logEnable) log.debug "get_status(${data.kind}) 원본: ${msg.toString().take(300)}"
        } catch (e) {
            if (logEnable) log.debug "상태 파싱 실패: ${e.message}"
        }
    } else if (logEnable) {
        String err = ""
        try { err = resp.hasError() ? resp.getErrorMessage() : "" } catch (ignored) { }
        log.debug "get_status(${data.kind}) 실패: HTTP ${sc} ${err}"
    }
    onStatus(data.kind, st, data)
}

private onStatus(String kind, Map st, Map data) {
    boolean ok = (st != null && !st.isEmpty())
    if (ok) {
        sendEvent(name: "apiStatus", value: "ok")
        applyStatus(st)
    } else if (!(kind == "regular" && !data.wakeRetry)) {
        setAttr("confirmed", "false")   // 깨우기 재조회 전 첫 실패는 제외
    }
    switch (kind) {
        case "verify":  verifyResult(ok, st, data); break
        case "recover": recoverResult(ok); break
        default:        regularResult(ok, data); break
    }
}

/** 정기 상태 조회 */
def pollStatus() {
    if (!canAuth()) return
    // 허브 재부팅 등으로 응답 콜백을 잃어 멈춘 확인/재조회는 정리
    if (isVerifying() && now() - (state.verify.start as long) > (VERIFY_WINDOW + 120) * 1000L) {
        log.warn "멈춘 잠김 확인 정리"
        state.remove("verify"); setAttr("verifying", "false")
    }
    if (state.recover && now() - ((state.recover.at ?: 0) as long) > 15 * 60 * 1000L) {
        log.warn "멈춘 무응답 재조회 정리"
        state.remove("recover"); setAttr("notAnswering", "false")
    }
    if (isVerifying() || state.recover) {
        if (logEnable) log.debug "잠김 확인/무응답 재조회 중 — 정기 조회 건너뜀"
        return
    }
    if (state.statusBusyAt && now() - (state.statusBusyAt as long) < (STATUS_TIMEOUT + 5) * 1000L) {
        if (logEnable) log.debug "상태 조회 진행 중 — 중복 요청 건너뜀"
        return
    }
    requestStatus("regular", STATUS_TIMEOUT)
}

private regularResult(boolean ok, Map data) {
    if (ok) { state.statusFailures = 0; return }
    if (!data.wakeRetry) {
        // 잠든 도어락은 첫 호출에서 깨어나느라 타임아웃이 잦음 → 바로 한 번 더
        if (logEnable) log.debug "정기 조회 실패 — 깨운 뒤 바로 재조회"
        requestStatus("regular", STATUS_TIMEOUT, [wakeRetry: true])
        return
    }
    state.statusFailures = (state.statusFailures ?: 0) + 1
    if (logEnable) log.debug "상태 조회 실패 ${state.statusFailures}회 — 이전값 유지"
    scheduleRecover()
}

private applyStatus(Map st) {
    state.lastStatusAt = nowStamp()
    setAttr("confirmed", "true")
    state.doorAssumed = false

    if (st.deadBolt != null) {
        boolean locked = st.deadBolt.toString() == "1"
        String lv = locked ? "locked" : "unlocked"
        if (cur("lock") != lv) {
            if (locked) {
                Map e = lockEntry()
                publishAccess(e)
                if (state.lockAddHistory) addHistory(e)
                sendLockEvent(e, state.pendingLockBy ? "digital" : "physical")
            } else {
                sendEvent(name: "lock", value: "unlocked", descriptionText: "${device.displayName} 열림(상태 조회)")
            }
        }
        if (locked) { state.remove("pendingLockBy"); state.remove("lockAddHistory") }
    }
    String sl = st.subLatch?.toString() ?: ""
    if (sl) sendIfChanged("subLatch", sl)
    if (sl == "1") setDoor("closed")
    else if (sl == "0") setDoor("open")
    else if (st.deadBolt?.toString() == "1") setDoor("closed")   // 빗장이 나왔으면 문은 닫혀 있음
    if (st.battery != null) {
        Integer b = st.battery as Integer
        if (cur("battery")?.toString() != b.toString()) sendEvent(name: "battery", value: b, unit: "%")
    }
    sendIfChanged("systemMode", st.systemMode)
    sendIfChanged("cardCount", st.cardCount)
    sendIfChanged("fingerprintCount", st.fingerPrintCount)
    sendIfChanged("passwordCount", st.passwordCount)
    if (logEnable) log.debug "상태: deadBolt=${st.deadBolt} subLatch=${st.subLatch} battery=${st.battery}"
}

/** 잠김이 확인됐을 때의 기록. Hubitat 에서 잠근 경우 외에는 도어락 자체(자동/수동)로 봄 */
private Map lockEntry() {
    String by = state.pendingLockBy ?: ""
    return [t: nowStamp(), kind: "close", user: by, method: by ? "Hubitat" : "도어락",
            msg: by ? "Hubitat 에서 잠금" : "도어락 잠김 확인"]
}

private setDoor(String v) {
    setAttr("door", v)
    if (v in ["open", "closed"]) {
        if (cur("contact") != v)
            sendEvent(name: "contact", value: v, descriptionText: "${device.displayName} 문 ${v == 'open' ? '열림' : '닫힘'}")
    }
}

// ─────────────────────────────────────────────────────────────
//  열림 후 잠김 확인 (verify)
// ─────────────────────────────────────────────────────────────

private boolean isVerifying() { state.verify != null }

/** 잠김 확인 시작(이미 진행 중이면 처음부터 다시) */
private startVerify() {
    unschedule("verifyTick")
    if (state.recover) {
        unschedule("recoverTick")
        state.remove("recover")
        setAttr("notAnswering", "false")
    }
    int gen = ((state.verifyGen ?: 0) as int) + 1
    state.verifyGen = gen
    state.verify = [gen: gen, start: now(), polls: 0, tries: 0, openWait: VERIFY_OPEN_FIRST,
                    closedStreak: 0, trace: []]
    setAttr("verifying", "true")
    verifyPoll()
}

def verifyTick() { verifyPoll() }

private verifyPoll() {
    Map v = state.verify
    if (!v) return
    requestStatus("verify", VERIFY_TIMEOUT, [gen: v.gen])
}

private verifyResult(boolean ok, Map st, Map data) {
    Map v = state.verify
    if (!v || (data.gen as int) != (v.gen as int)) return   // 새 열림으로 교체된 이전 확인
    v.polls = (v.polls as int) + 1

    if (!ok && (v.tries as int) < VERIFY_POLL_RETRIES && (v.polls as int) < VERIFY_MAX_POLLS) {
        v.tries = (v.tries as int) + 1     // 열림 직후 멈춘 조회 → 바로 다시
        state.verify = v
        verifyPoll()
        return
    }
    v.tries = 0
    double elapsed = Math.round((now() - (v.start as long)) / 100.0) / 10.0
    long waitMs
    String result

    if (!ok) {
        result = "no_answer"
        dropAssumedDoor()
        waitMs = VERIFY_UNKNOWN_RECHECK * 1000L
    } else if (st.deadBolt?.toString() == "1") {
        result = "locked"
    } else if (st.subLatch?.toString() == "1") {
        result = "closed_unlocked"
        v.closedStreak = (v.closedStreak as int) + 1
        if ((v.closedStreak as int) <= VERIFY_CLOSED_FAST_TRIES) {
            waitMs = VERIFY_CLOSED_RECHECK_MS
        } else {
            waitMs = (v.openWait as int) * 1000L
            v.openWait = Math.min((v.openWait as int) * 2, VERIFY_BACKOFF_MAX)
        }
    } else if (st.subLatch?.toString() == "0") {
        result = "open"
        v.closedStreak = 0
        waitMs = (v.openWait as int) * 1000L
        v.openWait = Math.min((v.openWait as int) * 2, VERIFY_BACKOFF_MAX)
    } else {
        result = "unlocked"
        waitMs = VERIFY_UNKNOWN_RECHECK * 1000L
    }

    List trace = (v.trace ?: []) as List
    trace << [t: elapsed, r: result]
    v.trace = trace.takeRight(VERIFY_TRACE_LEN)
    state.verify = v
    if (logEnable) log.debug "잠김 확인 ${elapsed}s: ${result}"

    v.lastOk = ok
    state.verify = v
    if (result == "locked") { finishVerify("locked", elapsed); return }

    long elapsedMs = now() - (v.start as long)
    if (elapsedMs + waitMs > VERIFY_WINDOW * 1000L || (v.polls as int) >= VERIFY_MAX_POLLS) {
        boolean answered = trace.any { it.r != "no_answer" }
        finishVerify(answered ? "still_unlocked" : "no_answer", elapsed)
        return
    }
    runInMillis(waitMs, "verifyTick", [overwrite: true])
}

private finishVerify(String result, double elapsed) {
    Map v = state.verify ?: [:]
    List trace = (v.trace ?: []) as List
    state.remove("verify")
    unschedule("verifyTick")
    setAttr("verifying", "false")
    setAttr("lastVerifyResult", result)
    setAttr("lastVerifySeconds", elapsed)
    setAttr("lastVerifyTrace", trace.collect { "${it.t}s ${traceLabel(it.r)}" }.join(" → "))
    if (txtEnable) log.info "잠김 확인 결과: ${result} (${elapsed}초, 조회 ${v.polls}회)"
    if (result == "no_answer" || !v.lastOk) scheduleRecover()
}

private String traceLabel(String r) {
    [locked: "잠김", closed_unlocked: "닫힘·안잠김", open: "문열림", unlocked: "안잠김", no_answer: "무응답"][r] ?: r
}

/** 열림이 일어남: 캐시된 locked 를 버리고 unlocked 로. assumeOpen 이면 문도 바로 열림 표시 */
private markUnlocked(Map e, boolean assumeOpen, String type) {
    sendLockEvent(e, type)
    setAttr("confirmed", "false")
    if (assumeOpen) {
        state.doorAssumed = true
        setDoor("open")
    } else {
        state.doorAssumed = false
        setAttr("door", "unknown")
    }
}

/** 도어락이 응답하지 않음: 추정한 '문 열림'을 계속 보여주지 않음 */
private dropAssumedDoor() {
    if (state.doorAssumed) {
        state.doorAssumed = false
        setAttr("door", "unknown")
    }
}

// ─────────────────────────────────────────────────────────────
//  도어락 무응답 → 느린 재조회 (recover)
// ─────────────────────────────────────────────────────────────

private scheduleRecover() {
    if (state.recover || isVerifying()) return
    state.recover = [attempt: 0, at: now()]
    setAttr("notAnswering", "true")
    log.warn "도어락이 상태 조회에 응답하지 않음 — ${RECOVER_DELAYS[0]}~${RECOVER_DELAYS[-1]}초 간격으로 재조회 " +
             "(오래 가면 도어락 배터리를 뺐다 끼워 재부팅)"
    runIn(RECOVER_DELAYS[0] as int, "recoverTick")
}

def recoverTick() {
    if (!state.recover) return
    requestStatus("recover", STATUS_TIMEOUT)
}

private recoverResult(boolean ok) {
    Map r = state.recover
    if (!r) return
    if (ok) {
        state.remove("recover")
        state.statusFailures = 0
        setAttr("notAnswering", "false")
        log.info "도어락 응답 재개 (재조회 ${(r.attempt as int) + 1}회)"
        if (cur("lock") != "locked") startVerify()
        return
    }
    r.attempt = (r.attempt as int) + 1
    r.at = now()
    state.recover = r
    int delay = RECOVER_DELAYS[Math.min(r.attempt as int, RECOVER_DELAYS.size() - 1)] as int
    runIn(delay, "recoverTick")
}

// ─────────────────────────────────────────────────────────────
//  retrieveLog — 열림 감지 (도어락 안 깨움)
// ─────────────────────────────────────────────────────────────

/** ⚠️ 쿼리는 반드시 query 맵으로 (path 에 붙이면 '?' 가 %3F 로 인코딩됨) */
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
    if (!canAuth()) return
    fetchLogs(20) { List logs -> handleLogs(logs) }
}

/** logs 는 최신순. 마지막으로 본 시각 이후 항목을 오래된 것부터 처리. */
private handleLogs(List logs) {
    String newest = logs[0]?.logDateTime?.toString()
    if (!newest) return

    if (state.lastLogDt == null) {   // 최초: 기준점만 잡고 이벤트는 내지 않음
        state.lastLogDt = newest
        state.history = logs.take(histMax()).collect { historyRow(toEntry(it)) }
        publishAccess(toEntry(logs[0]))
        renderHistory()
        if (txtEnable) log.info "출입 로그 기준점 설정: ${newest}"
        return
    }

    List fresh = logs.findAll { (it.logDateTime?.toString() ?: "") > state.lastLogDt }
    if (state.catchUp) {
        // 세션을 앱에 내준 동안 밀린 기록: 늦은 알림이 가지 않게 이벤트 없이 내역에만 반영
        state.remove("catchUp")
        if (fresh) {
            state.lastLogDt = newest
            fresh.reverse().each { Map e = toEntry(it); publishAccess(e); addHistory(e) }
            if (txtEnable) log.info "밀린 출입 기록 ${fresh.size()}건을 내역에 반영 (이벤트 없음)"
        }
        return
    }
    if (!fresh) return
    state.lastLogDt = newest
    List entries = fresh.reverse().collect { toEntry(it) }
    entries.each { processEntry(it) }

    List opens = entries.findAll { it.kind == "open" && !it.echo }
    if (!opens) return
    if (opens.every { it.code7 }) {
        // 원격 열기: 문 앞에 사람이 없을 수 있음. Hubitat 이 연 경우 이미 확인 중이므로 덮어쓰지 않음.
        if (!isVerifying()) startVerify()
    } else {
        startVerify()
    }
}

/** 서버 로그 1건 → 내부 표현 */
private Map toEntry(Map raw) {
    String code = raw.logCode?.toString()
    String media = (raw.mediaType ?: "").toString()
    String msg = (raw.logMessage ?: "").toString().trim()
    return [
        t:      raw.logDateTime?.toString(),
        kind:   (code in OPEN_LOG_CODES) ? "open" : "other",
        user:   (raw.nickname ?: "").toString().trim(),
        method: normMethod(media, msg),
        raw:    media,
        code7:  media == MEDIA_REMOTE,
        msg:    msg
    ]
}

private processEntry(Map e) {
    publishAccess(e)   // 속성 먼저 → lock 이벤트로 트리거된 룰이 새 값을 읽음

    if (e.kind == "open") {
        boolean echo = state.hubOpenAt && (now() - (state.hubOpenAt as Long)) < 60000 && e.code7
        if (echo) {
            e.echo = true
            state.remove("hubOpenAt")
            List h = (state.history ?: []) as List
            if (h) { h[0] = historyRow(e + [user: e.user ?: "Hubitat"]); state.history = h; renderHistory() }
            if (txtEnable) log.info "Hubitat 열기 기록 확인: ${describe(e)}"
            return
        }
        addHistory(e)
        state.lockAddHistory = true
        if (txtEnable) log.info "열림 감지: ${describe(e)}"
        boolean remote = e.code7
        if (!(remote && isVerifying())) markUnlocked(e, !remote, remote ? "digital" : "physical")
    } else {
        addHistory(e)
        if (txtEnable) log.info "기타 로그: ${e.msg ?: describe(e)}"
    }
}

/** lock 이벤트 발행 — descriptionText 와 data 에 누가/어떻게 포함. 열림은 매번 발행 */
private sendLockEvent(Map e, String type) {
    String v = (e.kind == "open") ? "unlocked" : "locked"
    sendEvent(name: "lock", value: v, type: type, isStateChange: (e.kind == "open"),
              descriptionText: "${device.displayName} ${describe(e)}",
              data: [user: e.user ?: "", method: e.method ?: "", time: e.t ?: "",
                     message: e.msg ?: "", mediaType: e.raw ?: "", codeName: e.user ?: ""])
}

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
        setAttr("lastLockTime", e.t ?: "-")
    }
}

/** "열림 · 홍길동 · 지문" */
private String describe(Map e) {
    String act = (e.kind == "open") ? "열림" : (e.kind == "close") ? "잠김" : "기타"
    List parts = [act]
    if (e.user) parts << e.user
    if (e.method && e.method != "알 수 없음") parts << e.method
    if (e.kind == "other" && e.msg) parts << e.msg
    return parts.join(" · ")
}

/** mediaType 코드 우선, 모르는 코드는 메시지 문구로, 그래도 모르면 코드 그대로 */
private String normMethod(String media, String msg) {
    return MEDIA_TYPES[media] ?: classify(msg) ?: (media ? "코드 ${media}" : "알 수 없음")
}

private String classify(String text) {
    if (!text) return null
    String h = text.toLowerCase()
    if (h =~ /지문|finger/)                                    return "지문"
    if (h =~ /카드|card|rfid|nfc|태그/)                         return "카드"
    if (h =~ /비밀번호|비번|password|passcode|pincode|keypad/)   return "비밀번호"
    if (h =~ /스마트\s*오픈|smart\s*open/)                       return "스마트오픈"
    if (h =~ /원격|remote|앱|app/)                              return "원격(앱)"
    if (h =~ /실내|내부|inside|manual|수동/)                     return "실내"
    if (h =~ /비상|열쇠|emergency/)                             return "비상키"
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

/** 대시보드 Attribute 타일용 HTML. 속성 1024자 제한에 맞춰 오래된 행부터 줄임. */
private renderHistory() {
    List h = ((state.history ?: []) as List).take(histMax())
    String html = ""
    while (true) {
        html = "<table style='width:100%;font-size:0.8em'>" + h.collect { r ->
            String icon = (r.kind == "open") ? "🔓" : (r.kind == "close") ? "🔒" : "ℹ️"
            String ts = r.t ?: ""
            if (ts.length() >= 16) ts = ts.substring(5, 16)
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
//  헬퍼
// ─────────────────────────────────────────────────────────────

def diagnose() {
    log.info "=== Solity 진단 ==="
    log.info "emailId      : ${settings.emailId ?: '(없음)'}"
    log.info "token        : ${state.token ? '있음' : '(없음)'}"
    log.info "myDeviceId   : ${resolvedDeviceId() ?: '(미확인)'}"
    log.info "apiStatus    : ${device.currentValue('apiStatus')}"
    log.info "lock/door    : ${device.currentValue('lock')} / ${device.currentValue('door')} (confirmed=${device.currentValue('confirmed')})"
    log.info "잠김 확인    : ${isVerifying() ? '진행 중' : '대기'} · 마지막 ${device.currentValue('lastVerifyResult') ?: '-'} (${device.currentValue('lastVerifyTrace') ?: '-'})"
    log.info "무응답 재조회: ${state.recover ? '진행 중' : '아님'}"
    log.info "마지막 상태조회: ${state.lastStatusAt ?: '(없음)'}"
    log.info "마지막 로그DT : ${state.lastLogDt ?: '(없음)'}"
    log.info "=================="
    fetchLogs(5) { List logs ->
        logs.each {
            log.info "  로그: dt=${it.logDateTime} code=${it.logCode} mediaType=${it.mediaType} " +
                     "(${MEDIA_TYPES[it.mediaType?.toString()] ?: '?'}) nick=${it.nickname} msg=${it.logMessage}"
        }
    }
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

/** 응답 본문을 Map 으로 (content-type 이 JSON 이 아니면 문자열/스트림으로 옴) */
private def toJson(def d) {
    if (d == null || d instanceof Map || d instanceof List) return d
    try { return new JsonSlurper().parseText(d instanceof String ? d : d.text) } catch (ignored) { return null }
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
    if (cur(name)?.toString() != value.toString())
        sendEvent(name: name, value: value)
}

/** 허브 캐시를 건너뛴 현재 속성값 */
private def cur(String name) { device.currentValue(name, true) }

private setAttr(String name, def value) {
    if (value == null) return
    if (cur(name)?.toString() != value.toString())
        sendEvent(name: name, value: value)
}

// ─────────────────────────────────────────────────────────────
//  같은 계정 세션 충돌 (폰 앱 ↔ 허브)
// ─────────────────────────────────────────────────────────────

/** 토큰은 약 30일 유효. 그보다 한참 전에 401 이면 다른 곳(폰 앱)에서 같은 계정으로 로그인한 것 */
private boolean isTakeover() {
    Long at = state.tokenAt as Long
    return at != null && (now() - at) < 25L * 24 * 3600 * 1000
}

private markSessionTaken() {
    state.sessionTakenAt = now()
    state.token = null
    state.tokenPwd = null
    String wait = settings.sessionWait ?: "30"
    String resume = (wait == "manual") ? "수동 (login 명령)" :
        new Date(now() + (wait as long) * 60000L).format("yyyy-MM-dd HH:mm", location.timeZone)
    sendEvent(name: "apiStatus", value: "session_taken")
    setAttr("sessionResumeAt", resume)
    setAttr("confirmed", "false")
    log.warn "같은 계정으로 다른 곳(폰 앱)에서 로그인함 — 앱이 로그아웃되지 않게 재로그인을 미룸 (재개: ${resume})"
    // 진행 중인 확인/재조회는 접고, 재개 후 다시 읽음
    if (isVerifying()) { state.remove("verify"); unschedule("verifyTick"); setAttr("verifying", "false") }
    if (state.recover) { state.remove("recover"); unschedule("recoverTick"); setAttr("notAnswering", "false") }
}

private clearSessionTaken() {
    if (!state.sessionTakenAt) return
    state.remove("sessionTakenAt")
    state.token = null
    setAttr("sessionResumeAt", "-")
}

/** 세션을 양보하는 중이면 false. 대기 시간이 지났으면 해제하고 다음 호출에서 재로그인 */
private boolean canAuth() {
    if (!state.sessionTakenAt) return true
    String wait = settings.sessionWait ?: "30"
    if (wait == "manual") return false
    if (now() - (state.sessionTakenAt as long) < (wait as long) * 60000L) return false
    log.info "세션 양보 시간이 지나 다시 로그인합니다 (폰 앱은 로그아웃될 수 있음)"
    clearSessionTaken()
    state.catchUp = true
    runIn(5, "pollStatus")
    return true
}
