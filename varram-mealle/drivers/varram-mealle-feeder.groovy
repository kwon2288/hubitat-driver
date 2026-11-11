/**
 *  VARRAM Mealle (밀리) 스마트 펫 급식기  -  v7.0 (카카오/이메일 로그인, 서버 스케줄, 다중 기기)
 *  Hubitat 드라이버 — 비공식, 앱 통신을 관찰해 작성
 *
 *  인증    : POST https://auth.varram-robot.com/token   {user_id, refresh_token}
 *  실시간  : wss://smb.varram-robot.com/?auth=<jwt>&target=<device_id>
 *  REST    : https://web.varram-robot.com/...           헤더  auth: <jwt>
 *
 *  v3: WebSocket 세션 경합 대응.
 *      벤더 서버는 device 당 소켓 1개만 허용하는 것으로 보이며, 나중에 붙은 쪽이
 *      앞선 세션을 끊습니다. 기본값을 "필요할 때만 연결"로 두어 앱과 공존시킵니다.
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String AUTH_URL = "https://auth.varram-robot.com/token"
@Field static final String WEB_URL  = "https://web.varram-robot.com"
@Field static final String WS_BASE  = "wss://smb.varram-robot.com/"

metadata {
    definition(name: "VARRAM Mealle Feeder", namespace: "kwon2288", author: "kwon2288") {
        capability "Actuator"
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"

        attribute "급여상태",   "string"
        attribute "잔여사료",        "number"
        attribute "사료부족",        "string"
        attribute "마지막급여횟수", "number"   // 마지막 수동 급여 횟수 (1회 = 앱의 '1회 급여하기')
        attribute "마지막급여시각",    "string"
        attribute "오늘수동급여횟수", "number"  // 오늘 수동 급여 누적 횟수
        attribute "오늘배급량실측",  "number"
        attribute "오늘섭취량실측", "number"
        attribute "오늘배급량앱",    "number"
        attribute "오늘섭취량앱",   "number"
        attribute "급여실패횟수",   "number"
        attribute "급여성공횟수",     "number"
        attribute "마지막실패시각",    "string"
        attribute "마지막식기비움시각", "string"   // 사용자가 식기를 비운 마지막 시각
        attribute "마지막섭취시각",     "string"
        attribute "전원",     "string"
        attribute "연결상태",      "string"   // online | connecting | idle | offline
        attribute "펌웨어",        "string"

        attribute "스케줄요약",    "string"
        attribute "스케줄활성합계", "number"
        attribute "스케줄슬롯수",      "number"

        command "급여",       [[name: "portions*", type: "NUMBER", description: "급여 횟수 (회). 1회 = 앱의 \"1회 급여하기\" (스케줄 1끼분)"]]
        command "이력조회"
        command "이력출력"
        command "스케줄조회"
        command "스케줄가져오기", [[name: "s_list JSON*", type: "STRING", description: "스케줄 JSON 배열"]]
        command "스케줄내보내기"
        command "서버스케줄확인"
        command "기기목록"
        command "스케줄전송"
        command "슬롯켜기",  [[name: "slot*", type: "NUMBER", description: "슬롯 번호 (1부터)"]]
        command "슬롯끄기", [[name: "slot*", type: "NUMBER", description: "슬롯 번호 (1부터)"]]
        command "전체켜기"
        command "전체끄기"
        command "기기정보출력"
        command "세션종료"
        command "재연결"
    }

    preferences {
        input name: "userId",    type: "text", title: "User ID (비우면 로그인 후 자동)", required: false
        input name: "initToken", type: "text", title: "Refresh Token (비우면 Kakao ID 또는 이메일로 로그인)", required: false
        input name: "kakaoId",   type: "text", required: false,
              title: "Kakao ID (선택, 자동 재로그인용)",
              description: "refresh token 갱신이 실패하면 카카오 ID로 자동 재로그인 시도. 비밀번호처럼 취급하세요"
        input name: "loginId",   type: "text", required: false,
              title: "이메일 (선택)",
              description: "카카오가 아닌 계정용. Kakao ID가 비어 있을 때 사용"
        input name: "loginPw",   type: "password", required: false,
              title: "비밀번호 (선택)",
              description: "바램펫 전용 비밀번호를 권장. Hubitat 설정/백업에 저장됩니다"
        input name: "deviceId",  type: "text", title: "Device ID (비우면 자동 검색)", required: false
        input name: "petId",     type: "text", title: "Pet ID (비우면 자동 검색)", required: false

        input name: "connMode", type: "enum", title: "연결 방식",
              options: ["ondemand": "필요할 때만 연결 (앱과 공존, 권장)",
                        "persistent": "상시 연결 (실시간 push, 앱이 끊김)"],
              defaultValue: "ondemand", required: true
        input name: "scheduleSyncHours", type: "enum", title: "스케줄 서버 동기화 주기",
              options: ["0": "끄기 (getSchedule 수동 실행)", "1": "1시간", "6": "6시간 (기본)", "12": "12시간", "24": "24시간"],
              defaultValue: "6", required: false
        input name: "holdSeconds", type: "number", title: "세션 유지 시간 (초)",
              description: "온디맨드 모드에서 마지막 메시지 후 소켓을 닫기까지", defaultValue: 15

        input name: "maxFeedPortions", type: "number", title: "1회 최대 급여 횟수 (회)", defaultValue: 2,
              description: "feed 명령 1번에 허용하는 최대 횟수. 1회 = 앱의 '1회 급여하기' (스케줄 1끼분)"
        input name: "dailyCapPortions", type: "number", title: "일일 수동 급여 상한 (회)", defaultValue: 4
        input name: "minInterval", type: "number", title: "급여 최소 간격 (초)", defaultValue: 300

        input name: "nominalGram", type: "number", title: "앱 환산 기준량 (g)",
              description: "스케줄에 없는 수동 급여의 설정량. 앱 표시값 환산에만 사용", defaultValue: 6
        input name: "logEnable", type: "bool", title: "디버그 로그", defaultValue: true
    }
}

private String uid()    { settings.userId   ?: state.userId }
private String devId()  { settings.deviceId ?: state.deviceId }
private String petIdv() { settings.petId    ?: state.petId }

private boolean isOnDemand() { (settings.connMode ?: "ondemand") == "ondemand" }
private Integer holdSecs()   { (settings.holdSeconds ?: 15) as Integer }

// ---------------------------------------------------------------- lifecycle

def installed() { initialize() }

def updated() {
    log.info "updated()"
    if (settings.initToken && !state.tokenSeeded) {
        state.refreshToken = settings.initToken
        state.tokenSeeded = true
    }
    initialize()
}

def initialize() {
    unschedule()
    closeSocket()
    state.q = []
    state.backoff = 5
    if (!state.refreshToken) state.refreshToken = settings.initToken
    rollDayIfNeeded()
    // 첫 실제 값이 들어오기 전까지 자리표시자를 넣어 기기 페이지에 속성이 보이게 함
    ["마지막급여시각", "마지막섭취시각", "마지막실패시각", "마지막식기비움시각"].each {
        if (device.currentValue(it) == null) sendEvent(name: it, value: "-")
    }

    if (settings.logEnable) runIn(1800, "logsOff")
    schedule("0 0 4 * * ?", "tokenCheck")

    if (isOnDemand()) {
        // 소켓을 물고 있지 않는다. 상태는 HTTP 이력으로 갱신.
        runEvery15Minutes("pullHistory")
        sendEvent(name: "연결상태", value: "idle")
        log.info "온디맨드 모드 — 명령이 있을 때만 접속합니다"
    } else {
        runEvery5Minutes("refresh")
        runEvery30Minutes("pullHistory")
        log.warn "상시 연결 모드 — VARRAM 앱의 기기 연결이 끊길 수 있습니다"
    }

    scheduleSyncJob()
    renewToken(true)
}

// 주기적 스케줄 동기화 (공식 앱에서 바꾼 내용 반영). 기본 6시간, 0 = getSchedule 수동 실행만
private scheduleSyncJob() {
    int h = ((settings.scheduleSyncHours ?: "6") as Integer)
    if (h <= 0) return
    if (h >= 24) schedule("0 7 3 * * ?", "getSchedule")
    else schedule("0 7 */${h} * * ?", "getSchedule")
}

def logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.warn "디버그 로그 비활성화"
}

// ---------------------------------------------------------------- auth

def tokenCheck() {
    def ageDays = (now() - (state.tokenAt ?: 0)) / 86400000
    if (ageDays > 25) renewToken()
    else if (settings.logEnable) log.debug "토큰 나이 ${ageDays as Integer}일 — 갱신 불필요"
}

def renewToken(boolean initialRun = false) {
    if ((!state.refreshToken || !uid()) && hasLogin()) {
        log.info "refresh token 또는 User ID가 없어 계정 정보로 로그인합니다"
        loginAuto(initialRun)
        return
    }
    def params = [
        uri: AUTH_URL,
        contentType: "application/json",
        requestContentType: "application/json",
        headers: ["User-Agent": "(Hubitat) pcss/app/vapp/2.3.6/KR"],
        body: JsonOutput.toJson([user_id: uid(), refresh_token: state.refreshToken]),
        timeout: 20
    ]
    try {
        httpPost(params) { resp ->
            def d = resp.data
            if (d?.type == "success" && d?.data?.access_token) {
                state.accessToken = d.data.access_token
                if (d.data.refresh_token) state.refreshToken = d.data.refresh_token
                state.tokenAt = now()
                state.loginTried = false
                if (settings.logEnable) log.debug "토큰 갱신 성공"
                discoverIds()

                if (initialRun) {
                    runIn(3,  "getSchedule")     // HTTP
                    runIn(8,  "pullHistory")     // HTTP
                    if (!isOnDemand()) runIn(12, "connectSocket")
                } else if (!isOnDemand()) {
                    reconnect()
                }
            } else {
                log.error "토큰 응답 이상: ${d}"
            }
        }
    } catch (e) {
        log.error "토큰 갱신 실패: ${e.message}"
        if (hasLogin() && !state.loginTried) {
            state.loginTried = true
            log.warn "계정 정보로 재로그인을 시도합니다"
            loginAuto(initialRun)
            return
        }
        log.error "앱 재로그인 후 refresh token 재등록이 필요할 수 있습니다"
        sendEvent(name: "연결상태", value: "offline")
        runIn(300, "renewToken")
    }
}

// 로그인 방식
//   카카오 : POST auth.varram-robot.com/v2/auth/kakao  {"kakao_id": "..."}  (JSON)
//   이메일 : POST auth.varram-robot.com/v2/login       email + password  (form)
// 응답은 /token 과 같은 구조이거나 data.auth_data 아래에 토큰이 들어 있다. 키 이름만 로그에 남기고
// 비밀번호/토큰 값은 로그에 쓰지 않는다.
private boolean hasLogin() { settings.kakaoId || (settings.loginId && settings.loginPw) }

def loginAuto(boolean initialRun = false) {
    if (settings.kakaoId) loginKakao(initialRun) else loginEmail(initialRun)
}

def loginKakao(boolean initialRun = false) {
    doLogin([uri: "https://auth.varram-robot.com/v2/auth/kakao",
             requestContentType: "application/json",
             body: JsonOutput.toJson([kakao_id: settings.kakaoId])], "Kakao", initialRun)
}

def loginEmail(boolean initialRun = false) {
        doLogin([uri: "https://auth.varram-robot.com/v2/login",
             requestContentType: "application/x-www-form-urlencoded",
             body: [email: settings.loginId, password: settings.loginPw]], "이메일", initialRun)
}

private doLogin(Map req, String label, boolean initialRun) {
    def params = [
        contentType: "application/json",
        headers: ["User-Agent": "(Hubitat) pcss/app/vapp/2.3.6/KR"],
        timeout: 20
    ] + req
    try {
        httpPost(params) { resp ->
            def d = resp.data
            def root = (d instanceof Map && d.data instanceof Map) ? d.data : d
            if (root instanceof Map && root.auth_data instanceof Map) root = root.auth_data
            log.info "${label} 로그인 응답 키: ${(d instanceof Map) ? d.keySet() : 'non-map'} / data 키: ${(root instanceof Map) ? root.keySet() : '-'}"
            def at = root?.access_token ?: root?.auth ?: root?.token
            def rt = root?.refresh_token
            def respUid = root?.user_id
            if (at) {
                state.accessToken = at
                if (rt) state.refreshToken = rt
                if (respUid && uid() && respUid != uid()) {
                    log.warn "응답 user_id가 설정값과 다릅니다 — 설정을 확인하세요"
                }
                state.tokenAt = now()
                state.loginTried = false
                log.info "${label} 로그인 성공"
                discoverIds()
                if (initialRun) {
                    runIn(3, "getSchedule"); runIn(8, "pullHistory")
                    if (!isOnDemand()) runIn(12, "connectSocket")
                } else if (!isOnDemand()) {
                    reconnect()
                }
            } else {
                log.error "${label} 로그인 응답에 토큰이 없습니다 (키 이름을 위 로그에서 확인): ${(d instanceof Map) ? d.type : ''}"
                sendEvent(name: "연결상태", value: "offline")
                runIn(1800, "renewToken")
            }
        }
    } catch (e) {
        log.error "${label} 로그인 실패: ${e.message}"
        sendEvent(name: "연결상태", value: "offline")
        runIn(1800, "renewToken")
    }
}

// ---------------------------------------------------------------- 자동 검색

// 로그인 뒤 User ID / Device ID / Pet ID 를 서버에서 읽어 state 에 저장한다.
// (설정에 직접 입력한 값이 있으면 그 값이 우선)
def discoverIds() {
    // 1) user_id : JWT payload 의 userid
    if (!state.userId) {
        try {
            def part = state.accessToken?.split("\\.")?.getAt(1)
            if (part) {
                def b64 = part.replace("-", "+").replace("_", "/")
                while (b64.length() % 4 != 0) b64 += "="
                def pj = new JsonSlurper().parseText(new String(b64.decodeBase64(), "UTF-8"))
                if (pj?.userid) state.userId = pj.userid
            }
        } catch (e) { log.warn "JWT 해석 실패: ${e.message}" }
    }
    if (!state.userId) {
        def u = apiGet("/res/user/info")
        if (u?.data?.user_id) state.userId = u.data.user_id
    }
    // 2) device_id / pet_id
    if (!devId() || !petIdv()) {
        def c = apiGet("/get/connected_pet")
        def list = c?.data?.connect_list ?: []
        def f = list.find { it.model_name == "feeder" } ?: list[0]
        if (f) {
            if (!state.deviceId && f.device_id) state.deviceId = f.device_id
            if (!state.petId && f.pet_id) state.petId = f.pet_id
        }
    }
    if (!devId()) {
        def dv = apiGet("/res/dev/info")
        def f2 = (dv?.data instanceof List) ? (dv.data.find { it.model_name == "feeder" } ?: dv.data[0]) : null
        if (f2?.device_id) state.deviceId = f2.device_id
    }
    log.info "자동 검색: user=${uid() ? 'OK' : '없음'} device=${devId() ?: '없음'} pet=${petIdv() ?: '없음'}"
}

// ---------------------------------------------------------------- REST

private apiPost(String path, Map body) {
    def result = null
    try {
        httpPost([uri: WEB_URL, path: path,
                  headers: ["auth": state.accessToken],
                  contentType: "application/json",
                  requestContentType: "application/json",
                  body: JsonOutput.toJson(body), timeout: 25]) { resp -> result = resp.data }
    } catch (e) {
        log.error "POST ${path} 실패: ${e.message}"
        try { log.error "  응답 본문: ${e.response?.data}" } catch (ignored) {}
    }
    return result
}

private apiGet(String path) {
    def result = null
    try {
        httpGet([uri: WEB_URL, path: path,
                 headers: ["auth": state.accessToken],
                 contentType: "application/json", timeout: 25]) { resp -> result = resp.data }
    } catch (e) { log.warn "GET ${path} 실패: ${e.message}" }
    return result
}

// ---------------------------------------------------------------- 섭취량 (HTTP, 항상 안전)

private fetchToday() {
    def tz = location.timeZone
    def d = new Date()
    def r = apiPost("/get/device/info/feed/v2/${petIdv()}",
                    [start_time: d.format("yyyy-MM-dd", tz) + " 00:00:00",
                     end_time  : d.format("yyyy-MM-dd HH:mm:ss", tz)])
    if (r?.type != "success") { log.warn "이력 조회 실패: ${r}"; return null }
    return r?.data?.data_list ?: []
}

// 스케줄 슬롯의 설정량을 찾는다. 앱은 실측 토출량이 아니라 이 값을 기준으로 표시한다.
private BigDecimal targetGramFor(String giveTime) {
    def hm = (giveTime && giveTime.length() >= 16) ? giveTime.substring(11, 16) : null
    def slot = state.schedule?.s_list?.find { it.time == hm }
    if (slot?.gram != null) return slot.gram as BigDecimal
    return (settings.nominalGram ?: 6) as BigDecimal
}

def pullHistory() {
    if (!petIdv()) { log.warn "Pet ID가 없어 섭취량 조회를 건너뜁니다"; return }
    def list = fetchToday()
    if (list == null) return

    // give_time 이 같은 레코드들이 한 회차를 이룬다.
    //   feed_finish            : 토출 직후 (left == gram)
    //   waiting                : 먹은 뒤 상태. 마지막 waiting 의 left 가 실제 잔여량
    //   empty_feed             : 급여 실패 (사료부족 / 소량토출)
    //   none_bowl              : 식기 비움 (사용자 조작, 무시)
    // 실패 회차는 배급량에 넣지 않는다.
    def order = []
    def byTime = [:]
    list.each { e ->
        def k = e.give_time
        if (!byTime.containsKey(k)) { byTime[k] = []; order << k }
        byTime[k] << e
    }

    BigDecimal measGiven = 0, measIntake = 0, appGiven = 0, appIntake = 0, lastLeft = 0
    int okCount = 0, failCount = 0
    String lastEat = null, lastFail = null, lastBowl = null, lastGive = null

    order.each { k ->
        def recs = byTime[k]
        def bowl = recs.find { it.status == "none_bowl" }
        if (bowl) lastBowl = (bowl.eat_time ?: k)
        def failed = recs.any { it.status == "empty_feed" }   // none_bowl = 사용자가 식기를 비움(실패 아님, gram=0)
        def eaten  = recs.findAll { it.status == "waiting" }

        if (failed && !eaten) {
            failCount++
            lastFail = k
            return
        }

        def base = recs.find { it.gram != null }
        BigDecimal gram = (base?.gram ?: 0) as BigDecimal
        if (gram <= 0) return

        okCount++
        lastGive = k
        measGiven += gram

        BigDecimal target = targetGramFor(k)
        appGiven += target

        if (eaten) {
            BigDecimal left = (eaten[-1].left ?: 0) as BigDecimal
            BigDecimal ate = gram - left
            if (ate < 0) ate = 0
            measIntake += ate
            lastLeft = left
            // 앱 표시값: 실측을 설정량 기준으로 환산한 뒤 버림
            appIntake += (ate * target / gram).setScale(0, BigDecimal.ROUND_DOWN)
            if (eaten[-1].eat_time) lastEat = eaten[-1].eat_time
        } else {
            // feed_finish 만 있는 진행 중 회차
            lastLeft = gram
        }
    }

    sendEvent(name: "오늘배급량실측",  value: measGiven,  unit: "g")
    sendEvent(name: "오늘섭취량실측", value: measIntake, unit: "g")
    sendEvent(name: "오늘배급량앱",    value: appGiven,   unit: "g")
    sendEvent(name: "오늘섭취량앱",   value: appIntake,  unit: "g")
    sendEvent(name: "잔여사료",        value: lastLeft,   unit: "g")
    sendEvent(name: "급여성공횟수",     value: okCount)
    sendEvent(name: "급여실패횟수",   value: failCount)
    if (lastGive) sendEvent(name: "마지막급여시각", value: lastGive)
    if (lastEat)  sendEvent(name: "마지막섭취시각",  value: lastEat)
    if (lastFail) sendEvent(name: "마지막실패시각", value: lastFail)
    if (lastBowl) sendEvent(name: "마지막식기비움시각", value: lastBowl)

    if (failCount > 0) {
        log.warn "급여 실패 ${failCount}회 (마지막 ${lastFail}) — 사료통을 확인하세요"
    }
    if (settings.logEnable) {
        log.debug "회차 ${order.size()}건 (성공 ${okCount} / 실패 ${failCount}) / 실측 배급 ${measGiven}g 섭취 ${measIntake}g / 앱기준 배급 ${appGiven}g 섭취 ${appIntake}g / 잔여 ${lastLeft}g"
    }
}

// 오늘 급여 이력 원문을 로그로 출력 (계산식 검증용)
def dumpHistory() {
    if (!petIdv()) { log.warn "Pet ID 없음"; return }
    def tz = location.timeZone
    def d = new Date()
    def r = apiPost("/get/device/info/feed/v2/${petIdv()}",
                    [start_time: d.format("yyyy-MM-dd", tz) + " 00:00:00",
                     end_time  : d.format("yyyy-MM-dd HH:mm:ss", tz)])
    def list = r?.data?.data_list ?: []
    log.info "=== 오늘 급여 이력 ${list.size()}건 ==="
    list.eachWithIndex { e, i ->
        log.info "${i+1}. give=${e.give_time} status=${e.status} gram=${e.gram} left=${e.left} eat=${e.eat_time} spend=${e.spend} imm=${e.immediate}"
    }
    log.info "=== 끝 ==="
}

// ---------------------------------------------------------------- 스케줄

// 스케줄 입력 파서. 두 가지 형식을 받는다.
//   1) JSON 배열   : [{"name":"식사1","time":"06:00","gram":6,"on":true}, ...]
//   2) 압축 문자열 : 06:00,6,1;12:00,6,1;21:00,6,0     (시간,그램,온오프)
//      Hubitat 텍스트 preference 는 약 255자 제한이 있어 슬롯이 많으면 2번을 쓴다.
private List parseSeed(String raw) {
    if (!raw) return null
    def s = raw.trim()
    if (s.startsWith("[") || s.startsWith("{")) {
        def parsed = new JsonSlurper().parseText(s)
        def list = (parsed instanceof List) ? parsed : parsed?.s_list
        list.each { if (it.on == null) it.on = true }
        return list
    }
    def out = []
    int idx = 0
    s.split(";").each { part ->
        def f = part.trim().split(",")
        if (f.size() < 2) return
        idx++
        def on = (f.size() >= 3) ? (f[2].trim() in ["1", "true", "on", "Y", "y"]) : true
        def nm = (f.size() >= 4) ? f[3].trim() : "식사${idx}"
        out << [name: nm, time: f[0].trim(), gram: (f[1].trim() as BigDecimal), on: on]
    }
    return out ?: null
}

def getSchedule() {
    // 서버가 슬롯별 on/off를 포함한 전체 스케줄을 보관하며(request_schedule_data) 기준 데이터입니다.
    // 읽지 못하면 state에 있는 마지막 스케줄을 유지합니다.
    if (state.pendingSchedule) return   // 스케줄 변경이 아직 처리 중
    def srv = fetchServerSchedule()
    if (srv?.s_list) {
        def defWeek = [MON:true,TUE:true,WED:true,THU:true,FRI:true,SAT:true,SUN:true]
        state.schedule = [s_list: srv.s_list, weekday: srv.weekday ?: defWeek]
        if (settings.logEnable) log.debug "서버에서 스케줄을 불러왔습니다 (${srv.s_list.size()}개 슬롯)"
    } else if (state.schedule?.s_list) {
        log.warn "서버에서 스케줄을 읽지 못해 마지막으로 알려진 스케줄을 유지합니다"
    } else {
        log.error "서버에서 스케줄을 읽지 못했고 저장된 스케줄도 없습니다"
        sendEvent(name: "스케줄요약", value: "미확인")
        return
    }
    renderSchedule()
}

// 초기값을 다시 심거나 앱에서 바꾼 내용을 반영할 때 사용 (기기로는 전송하지 않음)
def importSchedule(String raw) {
    try {
        def sList = parseSeed(raw)
        if (!sList) { log.error "해석하지 못했습니다"; return }
        state.schedule = [s_list: sList,
                          weekday: state.schedule?.weekday ?:
                                   [MON:true,TUE:true,WED:true,THU:true,FRI:true,SAT:true,SUN:true]]
        log.info "스케줄 가져오기 완료 (${sList.size()}개 슬롯) — 기기 반영은 하지 않았습니다"
        renderSchedule()
    } catch (e) {
        log.error "가져오기 실패: ${e.message}"
    }
}

// 현재 스케줄을 JSON 으로 로그에 출력 (백업용)
def exportSchedule() {
    if (!state.schedule) { log.warn "스케줄 없음"; return }
    log.info "현재 스케줄: ${JsonOutput.toJson(state.schedule)}"
}

// 계정에 연결된 모든 기기/펫을 로그로 출력해 알맞은 Device ID / Pet ID를 설정에 복사할 수 있게 함
// (급식기가 여러 대일 때 필요). 민감하지 않은 항목만 출력합니다.
def listDevices() {
    if (!state.accessToken) { log.error "아직 로그인되지 않았습니다 — initialize를 먼저 실행하세요"; return }
    def keys = ["device_id", "pet_id", "model_name", "device_name", "pet_name", "name", "nickname"]
    def fmt = { Map m -> keys.findAll { k -> m[k] != null }.collect { k -> "${k}=${m[k]}" }.join("  ") }
    def c = apiGet("/get/connected_pet")
    def list = c?.data?.connect_list ?: []
    log.info "=== connected_pet: ${list.size()}건 ==="
    list.eachWithIndex { e, i -> log.info "${i + 1}. ${fmt(e)}" }
    def dv = apiGet("/res/dev/info")
    def devs = (dv?.data instanceof List) ? dv.data : []
    log.info "=== devices: ${devs.size()}건 ==="
    devs.eachWithIndex { e, i -> log.info "${i + 1}. ${fmt(e)}" }
    log.info "이 기기가 사용 중: device=${devId() ?: '없음'} pet=${petIdv() ?: '없음'}"
    log.info "=== 끝 — device_id / pet_id를 이 기기의 Device ID / Pet ID 설정에 복사하세요 ==="
}

// 서버의 schedule_data 맵([s_list, weekday])을 반환하거나 null
private fetchServerSchedule() {
    if (!devId()) return null
    def r = apiPost("/get/mobile/info/feeder/${devId()}",
                    [request_update_info: true, request_schedule_data: true])
    def sd = r?.data?.schedule_data
    if (sd instanceof Map && sd.s_list instanceof List && sd.s_list) {
        sd.s_list.each { if (it.on == null) it.on = true }
        return sd
    }
    return null
}

// 조회 전용: VARRAM 앱이 이 엔드포인트에서 request_schedule_data로 스케줄을 불러옵니다.
// 서버 응답을 로그로 남기며 드라이버 스케줄이나 급식기는 변경하지 않습니다.
def readServerSchedule() {
    if (!devId()) { log.error "Device ID를 찾을 수 없습니다"; return }
    def r = apiPost("/get/mobile/info/feeder/${devId()}",
                    [request_update_info: true, request_schedule_data: true, request_adjustment: true])
    if (r == null) { log.error "서버 스케줄 요청 실패"; return }
    def d = r?.data
    log.info "서버 응답 키: ${(d instanceof Map) ? d.keySet() : r}"
    def sd = d?.schedule_data
    if (sd == null) { log.warn "응답에 schedule_data가 없습니다"; return }
    log.info "서버 schedule_data: ${JsonOutput.toJson(sd).take(1500)}"
    def sl = (sd instanceof Map) ? sd.s_list : null
    if (sl) {
        def hasOn = sl.every { it.containsKey("on") }
        log.info "서버 s_list: ${sl.size()}개 슬롯, 슬롯별 on/off 포함 여부: ${hasOn}"
    }
}

// 어떤 엔드포인트가 살아 있는지 로그로 확인하기 위한 진단용 (읽기 전용만)
def dumpDeviceInfo() {
    log.info "=== dumpDeviceInfo 시작 ==="
    ["/res/dev/info", "/res/pet/info"].each { p ->
        def r = apiGet(p)
        log.info "GET ${p} -> ${r == null ? '실패' : JsonOutput.toJson(r).take(700)}"
    }
    def r3 = apiPost("/get/mobile/info/feeder/${devId()}",
                     [request_update_info: true, request_check_optimization: true])
    log.info "POST /get/mobile/info/feeder -> ${r3 == null ? '실패' : JsonOutput.toJson(r3).take(700)}"

    if (petIdv()) {
        def mNow = new Date().format("yyyy-MM", location.timeZone)
        def r4 = apiPost("/get/device/info/feed/ver3/${petIdv()}", [read_month_list: [mNow]])
        log.info "POST feed/ver3 -> ${r4 == null ? '실패' : JsonOutput.toJson(r4).take(900)}"
    }
    log.info "=== dumpDeviceInfo 끝 ==="
}

private renderSchedule() {
    def sl = state.schedule?.s_list
    if (!sl) { sendEvent(name: "스케줄요약", value: "미확인"); return }
    def sorted = sl.sort { it.time }
    BigDecimal total = 0
    def parts = []
    sorted.eachWithIndex { s, i ->
        if (s.on) total += (s.gram as BigDecimal)
        parts << "${i+1}) ${s.time} ${s.gram}g ${s.on ? 'ON' : 'off'}"
    }
    sendEvent(name: "스케줄요약",    value: parts.join(" | "))
    sendEvent(name: "스케줄활성합계", value: total, unit: "g")
    sendEvent(name: "스케줄슬롯수",      value: sorted.size())
    log.info "스케줄: ${parts.join(' | ')}  (활성 합계 ${total}g)"
}

// 드라이버가 가진 스케줄(시간/그램/온오프)을 그대로 기기에 전송한다. 응답이 없으면 25초 뒤 로그에 오류를 남긴다.
def pushSchedule() {
    def sl = state.schedule?.s_list
    if (!sl) { log.error "스케줄이 없습니다. 스케줄조회 또는 스케줄가져오기를 먼저 실행하세요"; return }
    log.info "스케줄 ${sl.size()}개 슬롯을 기기로 전송합니다"
    applySchedule()
}

def scheduleSlotOn(BigDecimal slot)  { setSlot(slot as Integer, true) }
def scheduleSlotOff(BigDecimal slot) { setSlot(slot as Integer, false) }

private setSlot(Integer slot, boolean on) {
    def sl = state.schedule?.s_list
    if (!sl) { log.error "스케줄을 먼저 조회하세요 (스케줄조회)"; return }
    def sorted = sl.sort { it.time }
    if (slot < 1 || slot > sorted.size()) { log.error "슬롯 번호 범위 초과 (1~${sorted.size()})"; return }
    sorted[slot - 1].on = on
    state.schedule.s_list = sorted
    log.info "슬롯 ${slot} (${sorted[slot-1].time}) → ${on ? 'ON' : 'OFF'}"
    applySchedule()
}

def scheduleAllOn()  { setAll(true) }
def scheduleAllOff() { setAll(false) }

private setAll(boolean on) {
    def sl = state.schedule?.s_list
    if (!sl) { log.error "스케줄을 먼저 조회하세요 (스케줄조회)"; return }
    sl.each { it.on = on }
    state.schedule.s_list = sl
    log.info "전체 슬롯 → ${on ? 'ON' : 'OFF'}"
    applySchedule()
}

private applySchedule() {
    state.pendingSchedule = true
    queueMsg([type: "req_apply_schedule",
              data: [device_id: devId(), msg_id: uid(),
                     s_list: state.schedule.s_list, weekday: state.schedule.weekday]])
    runIn(25, "scheduleTimeout")
}

def scheduleTimeout() {
    if (state.pendingSchedule) {
        state.pendingSchedule = false
        log.error "스케줄 적용 응답 없음 — 서버 저장을 건너뜁니다"
        getSchedule()
    }
}

private persistSchedule() {
    def r = apiPost("/res/dev/schedule/edit/feeder/${devId()}", state.schedule)
    if (r?.type == "success") {
        log.info "스케줄 서버 저장 완료 (flag ${r?.data?.schedule_flag})"
        renderSchedule()
    } else {
        log.error "스케줄 서버 저장 실패: ${r} — 앱과 표시가 어긋날 수 있습니다"
    }
}

// ---------------------------------------------------------------- socket (큐 기반)

private queueMsg(Map msg) {
    def q = (state.q ?: [])
    q << msg
    state.q = q
    if (device.currentValue("연결상태") == "online") flushQueue()
    else connectSocket()
}

private flushQueue() {
    def q = state.q ?: []
    q.each {
        def json = JsonOutput.toJson(it)
        if (settings.logEnable) log.debug "-> ${json.take(300)}"
        interfaces.webSocket.sendMessage(json)
    }
    state.q = []
    touchIdle()
}

private touchIdle() {
    if (isOnDemand()) runIn(holdSecs(), "idleClose", [overwrite: true])
}

def idleClose() {
    if (!isOnDemand()) return
    if (state.pendingSchedule) { runIn(5, "idleClose"); return }
    if ((state.q ?: []).size() > 0) { runIn(5, "idleClose"); return }
    if (settings.logEnable) log.debug "세션 종료 (idle)"
    closeSocket()
    sendEvent(name: "연결상태", value: "idle")
}

def closeSession() { state.q = []; state.pendingSchedule = false; idleCloseForce() }
private idleCloseForce() { closeSocket(); sendEvent(name: "연결상태", value: "idle") }

def connectSocket() {
    if (!state.accessToken) { renewToken(true); return }
    def url = "${WS_BASE}?auth=${state.accessToken}&target=${devId()}"
    sendEvent(name: "연결상태", value: "connecting")
    try {
        interfaces.webSocket.connect(url, pingInterval: 30)
    } catch (e) {
        log.error "소켓 연결 실패: ${e.message}"
        if (isOnDemand()) { state.q = [] } else scheduleReconnect()
    }
}

def closeSocket() { try { interfaces.webSocket.close() } catch (ignored) {} }

def reconnect() { closeSocket(); runIn(2, "connectSocket") }

def webSocketStatus(String status) {
    if (settings.logEnable) log.debug "webSocketStatus: ${status}"
    if (status.startsWith("status: open")) {
        sendEvent(name: "연결상태", value: "online")
        state.backoff = 5
        if ((state.q ?: []).size() > 0) flushQueue() else touchIdle()
    } else if (status.startsWith("status: closing")) {
        sendEvent(name: "연결상태", value: isOnDemand() ? "idle" : "offline")
    } else if (status.startsWith("failure")) {
        sendEvent(name: "연결상태", value: isOnDemand() ? "idle" : "offline")
        if (status.toLowerCase().contains("401") || status.toLowerCase().contains("403")) {
            runIn(3, "renewToken")
        } else if (!isOnDemand()) {
            scheduleReconnect()
        } else {
            log.warn "세션 실패 — 큐를 비웁니다. 앱이 접속 중이면 잠시 후 다시 시도하세요."
            state.q = []
        }
    }
}

private scheduleReconnect() {
    state.backoff = Math.min((state.backoff ?: 5) * 2, 600)
    log.warn "재연결 대기 ${state.backoff}s"
    runIn(state.backoff as Integer, "connectSocket")
}

// ---------------------------------------------------------------- commands

def refresh() {
    if (isOnDemand()) {
        // 소켓을 열지 않고 HTTP만으로 갱신
        pullHistory()
        return
    }
    queueMsg([type: "req_device_status", data: [device_id: devId(), msg_id: uid()]])
}

// 참고: req_feeding의 "gram" 필드는 그램이 아니라 급여 횟수("1회 급여하기" × N)입니다.
// 1회는 대략 스케줄 1끼분이 나옵니다 (예: 8g 설정 → 실측 약 7.2g). 그래서 8을 보내면
// 약 58g이 나왔습니다. 실제 기기의 급여 이력으로 확인했습니다.
def feed(BigDecimal portions) {
    if (portions == null || portions <= 0 || portions != portions.setScale(0, BigDecimal.ROUND_DOWN)) {
        log.error "급여 횟수는 1 이상의 정수여야 합니다 (1회 = 앱의 '1회 급여하기')"; return
    }
    rollDayIfNeeded()

    def maxP = (settings.maxFeedPortions ?: 2) as BigDecimal
    if (portions > maxP) { log.error "거부: 1회 최대 ${maxP}회 초과 (${portions}회)"; return }

    def elapsed = (now() - (state.lastFeedAt ?: 0)) / 1000
    def minI = (settings.minInterval ?: 300) as Integer
    if (elapsed < minI) { log.error "거부: 마지막 급여 후 ${Math.round(elapsed)}s (최소 ${minI}s)"; return }

    def today = (state.dailyPortions ?: 0) as BigDecimal
    def cap = (settings.dailyCapPortions ?: 4) as BigDecimal
    if (today + portions > cap) { log.error "거부: 일일 수동 상한 초과 (${today}회 + ${portions}회 > ${cap}회)"; return }

    state.lastFeedAt = now()
    state.dailyPortions = today + portions
    sendEvent(name: "마지막급여횟수", value: portions)
    sendEvent(name: "마지막급여시각", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
    sendEvent(name: "오늘수동급여횟수", value: state.dailyPortions)

    queueMsg([type: "req_feeding",
              data: [device_id: devId(), msg_id: uid(), gram: portions as Integer]])
    log.info "급여 요청 ${portions}회 (오늘 수동 누적 ${state.dailyPortions}회)"
    runIn(120, "pullHistory")
}

private rollDayIfNeeded() {
    def d = new Date().format("yyyy-MM-dd", location.timeZone)
    if (state.dailyDate != d) {
        state.dailyDate = d
        state.dailyPortions = 0
        sendEvent(name: "오늘수동급여횟수", value: 0)
    }
}

// ---------------------------------------------------------------- parse

def parse(String description) {
    if (settings.logEnable) log.debug "<- ${description.take(400)}"
    touchIdle()

    def msg
    try { msg = new JsonSlurper().parseText(description) }
    catch (e) { log.warn "파싱 실패"; return }

    def d = msg?.data
    switch (msg?.type) {
        case "nt_device_status":
            if (d?.feeding?.type != null)      sendEvent(name: "급여상태", value: d.feeding.type)
            if (d?.feeding?.left_feed != null) sendEvent(name: "잔여사료", value: d.feeding.left_feed, unit: "g")
            if (d?.feed_lack != null) {
                sendEvent(name: "사료부족", value: d.feed_lack.toString())
                if (d.feed_lack) log.warn "사료 부족 감지"
            }
            if (d?.power?.type != null) sendEvent(name: "전원", value: d.power.type)
            if (d?.stm_ver != null) sendEvent(name: "펌웨어", value: "stm ${d.stm_ver} / esp ${d.esp_ver} / pba ${d.pba_ver}")
            break

        case "rsp_feeding":
            log.info "급여 명령 수락됨"
            break

        case "rsp_apply_schedule":
            unschedule("scheduleTimeout")
            state.pendingSchedule = false
            log.info "기기 스케줄 적용됨 — 서버 저장 진행"
            persistSchedule()
            touchIdle()
            break

        default:
            if (settings.logEnable) log.debug "미처리 타입: ${msg?.type}"
    }
}

// ── 한글 명령 이름 (기기 페이지 Commands 메뉴용) — 내부 로직은 위의 영문 메서드를 그대로 사용 ──
def 급여(BigDecimal portions)          { feed(portions) }
def 이력조회()                         { pullHistory() }
def 이력출력()                         { dumpHistory() }
def 스케줄조회()                       { getSchedule() }
def 스케줄가져오기(String raw)         { importSchedule(raw) }
def 스케줄내보내기()                   { exportSchedule() }
def 서버스케줄확인()                   { readServerSchedule() }
def 기기목록()                         { listDevices() }
def 스케줄전송()                       { pushSchedule() }
def 슬롯켜기(BigDecimal slot)          { scheduleSlotOn(slot) }
def 슬롯끄기(BigDecimal slot)          { scheduleSlotOff(slot) }
def 전체켜기()                         { scheduleAllOn() }
def 전체끄기()                         { scheduleAllOff() }
def 기기정보출력()                     { dumpDeviceInfo() }
def 세션종료()                         { closeSession() }
def 재연결()                           { reconnect() }
