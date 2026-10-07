/**
 * Navien Smart 숙면매트 (Connector) — App
 *
 * REST 로그인 세션(계정당 1개)과 AWS IoT 임시자격증명을 이 앱이 유일하게
 * 소유한다. 자식 드라이버("Navien Smart 숙면매트")는:
 *   - getAwsCredentials() 로 자격증명을 받아 AWS IoT에 wss:// MQTT로 직접
 *     접속해 실시간 상태(shadow reported)를 구독한다.
 *   - sendControl() 로 전원/단계 제어를 이 앱에 위임한다 (REST 는 MQTT 연결
 *     여부와 무관하게 항상 동작한다).
 *
 * 브리지(navien-mate/bridge) 없이 허브 안에서 전부 끝난다 — 다만 Hubitat
 * 내장 MQTT 클라이언트의 wss:// 지원은 공식 문서에 없는 동작이라 허브
 * 펌웨어 빌드에 따라 안 될 수 있다. 그 경우에도 이 앱을 통한 REST 제어는
 * 그대로 동작한다 (상태 실시간 반영만 안 될 뿐).
 *
 * 참고: https://github.com/ripe-avocado/navien_smart_ha (REST/AWS IoT 프로토콜)
 *       https://github.com/jlslate/hubitat-navien (Hubitat에서 wss:// 직접
 *       접속이 된다는 걸 다른 나비엔 제품(NaviLink)으로 먼저 검증한 참고 구현)
 */
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String LOGIN_URL = "https://member.naviensmartcontrol.com"
@Field static final String API_URL = "https://nskr.naviensmartcontrol.com/api/v2.0"
@Field static final String IOT_ENDPOINT = "nskr-iot.naviensmartcontrol.com"
@Field static final String IOT_REGION = "ap-northeast-2"
@Field static final String USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2_1 like Mac OS X) AppleWebKit/605.1.15 " +
    "(KHTML, like Gecko) Mobile/15E148 APP_NAVIENSMART_IOS"

@Field static final int CODE_SUCCESS = 200
@Field static final int CODE_NOT_AUTHORIZED = 404
@Field static final int CODE_TOKEN_EXPIRED = 407
@Field static final int SERVICE_MATE = 200

definition(
    name: "Navien Smart 숙면매트 (Connector)",
    namespace: "kwon2288",
    author: "kwon2288",
    description: "나비엔 스마트 숙면매트 REST 로그인/AWS 자격증명 관리 — 드라이버가 AWS IoT에 직접 접속",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "나비엔 스마트 숙면매트", install: true, uninstall: true) {
        section("계정") {
            input "username", "text", title: "나비엔 스마트 아이디", required: true, submitOnChange: true
            input "password", "password", title: "비밀번호", required: true, submitOnChange: true
            input "doLogin", "button", title: "로그인 및 기기 검색"
        }
        if (state.loginError) {
            section { paragraph "❌ ${state.loginError}" }
        }
        if (state.homeSeq) {
            section("상태") {
                paragraph "✅ 로그인됨 (userSeq=${state.userSeq}, homeSeq=${state.homeSeq})"
                paragraph "검색된 매트: ${state.devices?.size() ?: 0}대"
            }
        }
        section("옵션") {
            input "logEnable", "bool", title: "디버그 로그 남기기", defaultValue: true
        }
    }
}

// ── 라이프사이클 ───────────────────────────────────────────────────────────

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    unschedule()
    if (username && password) {
        login()
        if (state.accessToken) {
            discoverDevices()
            createChildDevices()
        }
    }
    // AWS 임시자격증명은 보통 1시간 정도 유효 — 만료 전에 미리 갱신하고
    // 자식 드라이버들에게 재접속하라고 알린다.
    runEvery30Minutes("refreshAwsCredentials")
}

def appButtonHandler(String btn) {
    if (btn == "doLogin") {
        state.loginError = null
        login()
        if (state.accessToken) {
            discoverDevices()
            createChildDevices()
        }
    }
}

// ── 자식 드라이버가 호출하는 공개 메서드 (parent.xxx()) ──────────────────

Map getAwsCredentials() {
    if (!state.aws) refreshAwsCredentials()
    return [
        accessKeyId : state.aws?.accessKeyId,
        secretKey   : state.aws?.secretKey,
        sessionToken: state.aws?.sessionToken,
        endpoint    : IOT_ENDPOINT,
        region      : IOT_REGION,
        homeSeq     : state.homeSeq,
        userSeq     : state.userSeq
    ]
}

Map getDeviceRegistry(String deviceId) {
    return state.devices?.get(deviceId)
}

Map sendControl(String deviceId, Map desired) {
    def dev = state.devices?.get(deviceId)
    if (!dev) return [ok: false, error: "알 수 없는 deviceId: ${deviceId}"]

    String topic = "\$aws/things/${deviceId}/shadow/name/status/update"
    Map body = [event: [modelCode: (dev.modelCode as Integer)]] + desired
    Map bodyMap = [serviceCode: dev.serviceCode, topic: topic, payload: [state: [desired: body]]]

    // 앱은 topic 안의 '/' 를 '\/' 로 이스케이프해서 보낸다. 서버가 까다로울 수
    // 있어 맞춘다 (원본: ripe-avocado api.py `async_control` 주석).
    String rawJson = JsonOutput.toJson(bodyMap)
    String topicLiteral = JsonOutput.toJson(topic)
    rawJson = rawJson.replace(topicLiteral, topicLiteral.replace("/", "\\/"))

    String path = "/devices/${dev.deviceSeq}/control?homeSeq=${state.homeSeq}&userSeq=${state.userSeq}"
    def result = authedPost(path, rawJson)
    if (result?.code != CODE_SUCCESS) {
        log.warn "제어 실패 (code=${result?.code}, msg=${result?.msg}): ${desired}"
        return [ok: false, error: result?.msg, code: result?.code]
    }
    if (logEnable) log.debug "제어 전송 성공: ${desired}"
    return [ok: true]
}

def refreshAwsCredentials() {
    if (!state.accessToken) {
        login()
        if (!state.accessToken) return
    }
    try {
        Map data = securedSignIn(state.accessToken, state.loginId, state.accountSeq)
        setAws(data.authInfo ?: [:])
        if (logEnable) log.debug "AWS 자격증명 갱신 완료"
        notifyChildrenCredentialsRefreshed()
    } catch (Exception e) {
        // 토큰이 있어도 서버가 더 이상 안 받아줄 수 있다(만료·세션 뺏김 등).
        // 그런 경우를 대비해 갱신 실패 시 전체 재로그인으로 폴백한다.
        log.warn "AWS 자격증명 갱신 실패(${e.message}) — 재로그인으로 폴백"
        login()
        if (state.accessToken) {
            notifyChildrenCredentialsRefreshed()
        }
    }
}

private void notifyChildrenCredentialsRefreshed() {
    getChildDevices()?.each { child ->
        try {
            child.credentialsRefreshed()
        } catch (Exception e) {
            if (logEnable) log.debug "${child.displayName} credentialsRefreshed() 호출 실패: ${e.message}"
        }
    }
}

private void setAws(Map info) {
    if (info?.accessKeyId && info?.secretKey && info?.sessionToken) {
        state.aws = [accessKeyId: info.accessKeyId, secretKey: info.secretKey, sessionToken: info.sessionToken]
    } else {
        state.aws = null
        log.warn "secured-sign-in 응답에 AWS 자격증명이 없습니다."
    }
}

// ── 기기 검색 / 자식 디바이스 생성 ─────────────────────────────────────────

def discoverDevices() {
    def result = authedGet("/devices?homeSeq=${state.homeSeq}&userSeq=${state.userSeq}")
    if (result?.code != CODE_SUCCESS) {
        log.warn "기기 목록 조회 실패 (code=${result?.code})"
        return
    }
    def devices = result?.data?.devices ?: []
    def mats = devices.findAll { it.serviceCode == SERVICE_MATE }
    if (!mats) {
        log.warn "계정에 등록된 숙면매트 기기를 찾지 못했습니다."
        state.devices = [:]
        return
    }

    Map registry = [:]
    mats.each { dev ->
        def attrs = dev?.Properties?.registry?.attributes ?: [:]
        def functions = attrs?.functions ?: [:]
        def heatControl = functions?.heatControl ?: [:]
        def mcu = attrs?.mcu ?: [:]
        def nick = dev?.Properties?.nickName
        def side = (nick instanceof Map) ? (nick.side ?: [:]) : [:]
        boolean isDouble = (((mcu?.capacity ?: 1) as int) == 2) || (side && !side.isEmpty())

        registry[dev.deviceId as String] = [
            deviceSeq  : dev.deviceSeq,
            deviceId   : dev.deviceId,
            serviceCode: dev.serviceCode,
            modelCode  : dev.modelCode,
            modelName  : dev.modelName,
            zones      : isDouble ? ["left", "right"] : ["single"],
            unit       : heatControl?.unit,
            rangeMin   : heatControl?.rangeMin,
            rangeMax   : heatControl?.rangeMax
        ]
    }
    state.devices = registry
    if (logEnable) log.debug "기기 검색됨: ${registry.keySet()}"
}

def createChildDevices() {
    (state.devices ?: [:]).each { deviceId, dev ->
        String dni = "navien-mate-${deviceId}"
        def child = getChildDevice(dni)
        if (!child) {
            try {
                child = addChildDevice(
                    "kwon2288", "Navien Smart 숙면매트 (On-Hub)", dni,
                    [
                        name : "Navien Smart 숙면매트",
                        label: dev.modelName ?: "Navien Smart 숙면매트",
                        data : [deviceId: deviceId],
                        isComponent: false
                    ]
                )
                log.info "자식 디바이스 생성됨: ${dni}"
            } catch (Exception e) {
                log.error "자식 디바이스 생성 실패 (${dni}): ${e.message}"
                return
            }
        }
        try {
            child.initialize()
        } catch (Exception e) {
            if (logEnable) log.debug "${dni} initialize() 호출 실패(무시): ${e.message}"
        }
    }
}

// ── 인증 (실기기로 검증된 GET→POST→GET 쿠키 흐름) ─────────────────────────

def login() {
    try {
        Map loginResp = formLogin()
        Map signIn = securedSignIn(loginResp.accessToken, loginResp.loginId, loginResp.userSeq)
        def homes = signIn?.home ?: []
        if (!homes) throw new Exception("계정에 등록된 home 이 없습니다.")

        state.accessToken = loginResp.accessToken
        state.refreshToken = loginResp.refreshToken
        state.loginId = loginResp.loginId
        state.accountSeq = loginResp.userSeq
        state.userSeq = signIn?.userInfo?.userSeq
        state.homeSeq = homes[0]?.homeSeq
        setAws(signIn?.authInfo ?: [:])

        state.loginError = null
        if (logEnable) log.debug "로그인 성공 userSeq=${state.userSeq} homeSeq=${state.homeSeq}"
    } catch (Exception e) {
        state.accessToken = null
        state.loginError = e.message
        log.warn "로그인 실패: ${e.message}"
    }
}

private String extractText(data) {
    if (data == null) return null
    if (data instanceof String) return data
    try { return data.text } catch (Exception ignored) { }
    return data.toString()
}

private List<String> extractSetCookies(resp) {
    List<String> cookies = []
    try {
        resp?.headers?.each { h ->
            String name = null
            String value = null
            try { name = h?.name?.toString() } catch (ignored) { }
            try { value = h?.value?.toString() } catch (ignored) { }
            if (name?.equalsIgnoreCase('Set-Cookie') && value) cookies << value
        }
    } catch (Exception ignored) { }

    if (!cookies) {
        try {
            def raw = resp?.headers?.'Set-Cookie'
            if (raw instanceof Collection) {
                raw.each { cookies << it.toString() }
            } else if (raw != null) {
                cookies << raw.toString()
            }
        } catch (Exception ignored) { }
    }
    return cookies
}

private void mergeCookies(Map jar, List<String> setCookies) {
    setCookies.each { sc ->
        String pair = sc.split(';')[0].trim()
        int eq = pair.indexOf('=')
        if (eq > 0) {
            jar[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
        }
    }
}

private String cookieHeaderFrom(Map jar) {
    return jar.collect { k, v -> "${k}=${v}" }.join('; ')
}

private Map formLogin() {
    Map jar = [:]

    try {
        httpGet([uri: "${LOGIN_URL}/member/login", headers: ["User-Agent": USER_AGENT],
                 textParser: true, timeout: 20]) { resp ->
            mergeCookies(jar, extractSetCookies(resp))
        }
        if (logEnable) log.debug "로그인 페이지 사전 GET: jar=${jar.keySet()}"
    } catch (Exception e) {
        if (logEnable) log.debug "로그인 페이지 사전 GET 실패(무시): ${e.message}"
    }

    Map postHeaders = [
        "User-Agent": USER_AGENT,
        "Origin"    : LOGIN_URL,
        "Referer"   : "${LOGIN_URL}/member/login"
    ]
    String jarCookie = cookieHeaderFrom(jar)
    if (jarCookie) postHeaders["Cookie"] = jarCookie

    Map postParams = [
        uri               : "${LOGIN_URL}/member/login",
        requestContentType: "application/x-www-form-urlencoded",
        headers           : postHeaders,
        body              : [username: username, password: password],
        textParser        : true,
        timeout           : 20
    ]

    String html = null
    Integer status = null
    String location = null

    httpPost(postParams) { resp ->
        status = resp?.status
        location = resp?.headers?.'Location' ?: resp?.headers?.'location'
        mergeCookies(jar, extractSetCookies(resp))
        html = extractText(resp?.data)
        if (logEnable) {
            log.debug "로그인 POST 응답: status=${status}, location=${location}, " +
                "jar=${jar.keySet()}, bodyLen=${html?.length() ?: 0}"
        }
    }

    if ((!html || html.trim().isEmpty()) && location) {
        String redirectUri = location.startsWith("http") ? location : "${LOGIN_URL}${location}"
        Map redirectHeaders = ["User-Agent": USER_AGENT, "Referer": "${LOGIN_URL}/member/login"]
        String redirectCookie = cookieHeaderFrom(jar)
        if (redirectCookie) redirectHeaders["Cookie"] = redirectCookie
        if (logEnable) log.debug "리다이렉트 수동 추적: ${redirectUri} (jar=${jar.keySet()})"

        try {
            httpGet([uri: redirectUri, headers: redirectHeaders, textParser: true, timeout: 20]) { resp ->
                status = resp?.status
                mergeCookies(jar, extractSetCookies(resp))
                html = extractText(resp?.data)
            }
        } catch (Exception e) {
            String errBody = null
            Integer errStatus = null
            try { errStatus = e.response?.status } catch (ignored) { }
            try { errBody = extractText(e.response?.data) } catch (ignored) { }
            log.warn "리다이렉트 GET 실패 (status=${errStatus}): ${e.message}" +
                (errBody ? " / body=${errBody.take(300)}" : " / body 없음")
            throw new Exception("로그인 리다이렉트 실패 (status=${errStatus ?: status})")
        }
    }

    if (!html) throw new Exception("로그인 응답을 받지 못했습니다 (status=${status}).")

    if (html.contains('id="loginFailPopup" style="display:none;"')) {
        throw new Exception(authErrorMessage(html))
    }
    if (html.contains("passwordChg")) {
        throw new Exception("서버가 비밀번호 변경을 요구합니다. 나비엔 앱/웹에서 먼저 처리해주세요.")
    }

    def line = html.readLines().find { it.contains("var message = ") }
    if (!line) throw new Exception("로그인 응답에서 토큰을 찾지 못했습니다.")
    int start = line.indexOf("{")
    int end = line.lastIndexOf("}")
    if (start < 0 || end <= start) throw new Exception("로그인 토큰 파싱에 실패했습니다.")

    def json = new JsonSlurper().parseText(line.substring(start, end + 1))
    if (!json?.accessToken) throw new Exception("응답에 accessToken 이 없습니다.")
    return json as Map
}

private String authErrorMessage(String html) {
    if (!html.contains("입력한 정보가 일치하지 않습니다.")) {
        return "아이디가 올바르지 않습니다."
    }
    def m = (html =~ /현재 (\d)회/)
    if (m.find()) {
        return "비밀번호가 올바르지 않습니다. 5회 실패 시 재설정이 필요합니다 (현재 ${m.group(1)}회)."
    }
    return "비밀번호가 올바르지 않습니다. 재설정이 필요할 수 있습니다."
}

private Map securedSignIn(String token, String loginId, def accountSeq) {
    Map bodyMap = [userId: loginId, accountSeq: accountSeq]
    Map params = [
        uri       : "${API_URL}/users/secured-sign-in",
        headers   : ["Authorization": token, "User-Agent": USER_AGENT, "Content-Type": "application/json"],
        body      : JsonOutput.toJson(bodyMap),
        textParser: true,
        timeout   : 20
    ]
    Map payload
    httpPost(params) { resp -> payload = new JsonSlurper().parseText(resp.data.text) as Map }
    if (payload?.code != CODE_SUCCESS) throw new Exception("secured-sign-in 실패 (code=${payload?.code})")
    if (!payload?.data) throw new Exception("secured-sign-in 응답에 data 가 없습니다.")
    return payload.data as Map
}

// ── 인증된 요청 (세션 만료 시 1회 재로그인) ──────────────────────────────

private Map authedGet(String path) {
    return authedRequest("GET", path, null)
}

private Map authedPost(String path, String rawBody) {
    return authedRequest("POST", path, rawBody)
}

private Map authedRequest(String method, String path, String rawBody) {
    def result = rawRequest(method, path, rawBody, state.accessToken)
    // code 404/407은 JSON 바디 레벨의 "세션 무효" 응답, code -1은 rawRequest가
    // HTTP 레벨 예외(403 등)를 잡아 만든 값이다 — 둘 다 재로그인 후 재시도 대상.
    if (result?.code in [CODE_NOT_AUTHORIZED, CODE_TOKEN_EXPIRED, -1]) {
        if (logEnable) log.debug "세션 무효 가능성(code=${result?.code}) — 재로그인 후 재시도"
        login()
        if (!state.accessToken) return result
        result = rawRequest(method, path, rawBody, state.accessToken)
    }
    return result
}

private Map rawRequest(String method, String path, String rawBody, String token) {
    String uri = "${API_URL}${path}"
    Map headers = ["Authorization": token, "User-Agent": USER_AGENT]
    Map payload = null
    try {
        if (method == "GET") {
            httpGet([uri: uri, headers: headers, textParser: true, timeout: 20]) { resp ->
                payload = new JsonSlurper().parseText(resp.data.text) as Map
            }
        } else {
            headers["Content-Type"] = "application/json"
            httpPost([uri: uri, headers: headers, body: rawBody, textParser: true, timeout: 20]) { resp ->
                payload = new JsonSlurper().parseText(resp.data.text) as Map
            }
        }
    } catch (Exception e) {
        log.warn "${path} 요청 실패: ${e.message}"
        payload = [code: -1, msg: e.message]
    }
    return payload
}
