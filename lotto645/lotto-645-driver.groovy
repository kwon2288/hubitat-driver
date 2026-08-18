/**
 *  Lotto 6/45 Winning Numbers (동행복권)
 *  Hubitat Elevation custom driver  -  v2
 *
 *  구 엔드포인트(common.do?method=getLottoNumber)는 302 리다이렉트로 차단됨.
 *  개편된 사이트의 당첨결과 페이지가 사용하는 엔드포인트를 이용한다.
 *    GET https://www.dhlottery.co.kr/lt645/selectPstLt645InfoNew.do?srchLtEpsd=1237
 *    -> {"data":{"list":[{ltEpsd, tm1WnNo..tm6WnNo, bnsWnNo, ltRflYmd, rnk1WnNope, rnk1WnAmt, ...}, ...]}}
 *    미발표/없는 회차는 list 가 빈 배열로 반환된다.
 *
 *  Author  : kwon2288
 *  Version : 1.0.0
 *  License : MIT
 */

metadata {
    definition(name: "Lotto 645 Winning Numbers",
               namespace: "kwon2288",
               author: "kwon2288",
               importUrl: "https://raw.githubusercontent.com/kwon2288/hubitat-driver/main/lotto645/lotto-645-driver.groovy") {

        capability "Refresh"
        capability "Sensor"

        attribute "drawNo",         "number"   // 회차
        attribute "drawDate",       "string"   // 추첨일
        attribute "numbers",        "string"   // 당첨번호 6개
        attribute "bonusNo",        "number"   // 보너스 번호
        attribute "summary",        "string"   // 대시보드 표시용 한 줄 요약
        attribute "firstWinAmount", "string"   // 1등 1게임당 당첨금
        attribute "firstWinners",   "number"   // 1등 당첨자 수
        attribute "myMatchCount",   "number"   // 내 번호 일치 개수
        attribute "myRank",         "string"   // 내 번호 등수
        attribute "lastChecked",    "string"

        command "fetchDraw", [[name: "회차", type: "NUMBER", description: "비우면 최신 회차 자동 계산"]]
    }

    preferences {
        input name: "autoSchedule", type: "bool",   title: "매주 토요일 추첨 후 자동 조회", defaultValue: true
        input name: "myNumbers",    type: "string", title: "내 번호 (쉼표 구분, 예: 3,11,17,25,33,41)", required: false
        input name: "logEnable",    type: "bool",   title: "디버그 로그(1시간 후 자동 해제)", defaultValue: true
    }
}

// ─────────────────────────────── lifecycle ───────────────────────────────

def installed() {
    log.info "Lotto 645 driver installed"
    updated()
}

def updated() {
    unschedule()
    if (autoSchedule != false) {
        // 추첨 20:35~20:45, 결과 반영 시차를 감안해 2회 시도
        schedule("0 10,45 21 ? * SAT", "refresh")
        log.info "자동 조회 스케줄 등록: 매주 토요일 21:10 / 21:45"
    }
    if (logEnable) runIn(3600, "logsOff")
    refresh()
}

def logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "디버그 로그 해제"
}

// ─────────────────────────────── commands ────────────────────────────────

def refresh() {
    requestDraw(estimateLatestDrawNo(), 0)
}

def fetchDraw(drawNo = null) {
    Integer n = (drawNo != null) ? (drawNo as Integer) : estimateLatestDrawNo()
    requestDraw(n, 0)
}

// ─────────────────────────────── internals ───────────────────────────────

/** 1회차 추첨일(2002-12-07)로부터 경과 주 수로 최신 회차를 추정 */
private Integer estimateLatestDrawNo() {
    Long base = Date.parse("yyyy-MM-dd HH:mm", "2002-12-07 20:45").getTime()
    Long week = 7L * 24 * 60 * 60 * 1000
    Integer n = (int) (((now() - base) / week) + 1)
    if (logEnable) log.debug "추정 최신 회차: ${n}"
    return n
}

private requestDraw(Integer n, Integer retry) {
    Map params = [
        uri            : "https://www.dhlottery.co.kr",
        path           : "/lt645/selectPstLt645InfoNew.do",
        query          : [srchLtEpsd: n.toString()],
        headers        : [
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Accept"    : "application/json",
            "Referer"   : "https://www.dhlottery.co.kr/lt645/result"
        ],
        timeout        : 20,
        ignoreSSLIssues: true
    ]
    if (logEnable) log.debug "조회 요청: ${n}회차 (retry=${retry})"
    asynchttpGet("parseDraw", params, [drawNo: n, retry: retry])
}

def parseDraw(resp, Map data) {
    if (resp.hasError()) {
        log.warn "HTTP 오류: ${resp.getErrorMessage()}"
        return
    }

    def json
    try {
        json = parseJson(resp.data)
    } catch (e) {
        log.warn "JSON 파싱 실패: ${e.message} / raw=${resp.data?.toString()?.take(200)}"
        return
    }

    List list = json?.data?.list ?: []
    def row = list.find { (it.ltEpsd as Integer) == (data.drawNo as Integer) }

    // 아직 발표 전이거나 회차 추정이 1 앞선 경우 → 이전 회차로 한 번만 재시도
    if (!row) {
        if ((data.retry ?: 0) < 1) {
            if (logEnable) log.debug "${data.drawNo}회차 미발표 → ${data.drawNo - 1}회차 재시도"
            requestDraw((data.drawNo as Integer) - 1, (data.retry ?: 0) + 1)
        } else {
            log.warn "당첨번호 조회 실패: ${data.drawNo}회차 데이터 없음"
        }
        return
    }

    List<Integer> nums = [row.tm1WnNo, row.tm2WnNo, row.tm3WnNo,
                          row.tm4WnNo, row.tm5WnNo, row.tm6WnNo].collect { it as Integer }.sort()
    Integer bonus   = row.bnsWnNo as Integer
    String  numStr  = nums.join(", ")
    String  drawDay = formatYmd(row.ltRflYmd?.toString())
    String  summary = "${row.ltEpsd}회 (${drawDay})  ${numStr}  + ${bonus}"

    sendEvent(name: "drawNo",         value: row.ltEpsd as Integer)
    sendEvent(name: "drawDate",       value: drawDay)
    sendEvent(name: "numbers",        value: numStr)
    sendEvent(name: "bonusNo",        value: bonus)
    sendEvent(name: "summary",        value: summary, descriptionText: summary)
    sendEvent(name: "firstWinAmount", value: String.format("%,d원", (row.rnk1WnAmt ?: 0) as Long))
    sendEvent(name: "firstWinners",   value: (row.rnk1WnNope ?: 0) as Integer)
    sendEvent(name: "lastChecked",    value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))

    state.lastDrawNo = row.ltEpsd
    log.info summary

    checkMyNumbers(nums, bonus)
}

/** "20260815" → "2026-08-15" */
private String formatYmd(String ymd) {
    if (!ymd || ymd.length() != 8) return (ymd ?: "")
    return "${ymd[0..3]}-${ymd[4..5]}-${ymd[6..7]}"
}

/** 내 번호와 대조해 등수 판정 */
private checkMyNumbers(List<Integer> win, Integer bonus) {
    if (!myNumbers) return

    List<Integer> mine
    try {
        mine = myNumbers.split(/[,\s]+/).findAll { it }.collect { it as Integer }
    } catch (e) {
        log.warn "내 번호 형식 오류: ${myNumbers}"
        return
    }
    if (mine.size() != 6) {
        log.warn "내 번호는 6개여야 합니다 (현재 ${mine.size()}개)"
        return
    }

    int match    = mine.count { win.contains(it) }
    boolean bMat = mine.contains(bonus)

    String rank
    if      (match == 6)         rank = "1등"
    else if (match == 5 && bMat) rank = "2등"
    else if (match == 5)         rank = "3등"
    else if (match == 4)         rank = "4등"
    else if (match == 3)         rank = "5등"
    else                         rank = "낙첨"

    sendEvent(name: "myMatchCount", value: match)
    sendEvent(name: "myRank",       value: rank, descriptionText: "내 번호 결과: ${rank} (${match}개 일치)")
    log.info "내 번호 결과: ${rank} (${match}개 일치${bMat ? ', 보너스 일치' : ''})"
}
