"""
Navien Smart 숙면매트 → Hubitat 브리지

역할:
  1) 나비엔 클라우드 로그인/세션을 유일하게 소유한다 (계정당 세션 1개 제약 —
     Hubitat 드라이버가 별도로 로그인하면 서로 세션을 뺏는다).
  2) AWS IoT Core 에 SigV4 서명 WebSocket 으로 붙어 매트 상태(shadow reported)를 구독한다.
  3) 받은 상태를 Hubitat MQTT 브로커(내장/외부 무관)로 재발행한다.
  4) Hubitat 이 보낸 제어 요청(HTTP)을 나비엔 REST control 로 중계한다.

참고 원본: https://github.com/ripe-avocado/navien_smart_ha
"""
from __future__ import annotations

import datetime
import hashlib
import hmac
import json
import logging
import os
import re
import threading
import time
import urllib.parse
import uuid
from typing import Any

import paho.mqtt.client as mqtt
import requests
from flask import Flask, jsonify, request

# ── 설정 (환경변수) ──────────────────────────────────────────────────────

NAVIEN_USERNAME = os.environ["NAVIEN_USERNAME"]
NAVIEN_PASSWORD = os.environ["NAVIEN_PASSWORD"]

MQTT_HOST = os.environ.get("MQTT_HOST", "127.0.0.1")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))
MQTT_USERNAME = os.environ.get("MQTT_USERNAME") or None
MQTT_PASSWORD = os.environ.get("MQTT_PASSWORD") or None
MQTT_PREFIX = os.environ.get("MQTT_PREFIX", "navien")

HTTP_PORT = int(os.environ.get("HTTP_PORT", "8099"))

LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO")
logging.basicConfig(level=LOG_LEVEL, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")
log = logging.getLogger("navien-bridge")

# ── 나비엔 상수 (원본 const.py 값) ───────────────────────────────────────

LOGIN_URL = "https://member.naviensmartcontrol.com"
API_URL = "https://nskr.naviensmartcontrol.com/api/v2.0"
IOT_ENDPOINT = "nskr-iot.naviensmartcontrol.com"
IOT_REGION = "ap-northeast-2"
IOT_SERVICE = "iotdevicegateway"
USER_AGENT = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2_1 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Mobile/15E148 APP_NAVIENSMART_IOS"
)

CODE_SUCCESS = 200
CODE_NOT_AUTHORIZED = 404
CODE_TOKEN_EXPIRED = 407
SERVICE_MATE = 200

RECONNECT_DELAYS = (5, 15, 30, 60, 120, 300)


class NavienAuthError(Exception):
    pass


class NavienApiError(Exception):
    def __init__(self, code: int | None, message: str) -> None:
        super().__init__(message)
        self.code = code


# ── 세션/인증 (계정당 세션 1개 — 이 프로세스가 유일한 소유자) ─────────────

class NavienSession:
    """폼 로그인 + secured-sign-in. requests.Session 이 쿠키/리다이렉트를 알아서 처리한다."""

    def __init__(self, username: str, password: str) -> None:
        self.username = username
        self.password = password
        self.http = requests.Session()
        self.http.headers.update({"User-Agent": USER_AGENT})
        self.lock = threading.Lock()

        self.access_token: str | None = None
        self.refresh_token: str | None = None
        self.login_id: str | None = None
        self.account_seq: int | None = None
        self.user_seq: int | None = None
        self.home_seq: int | None = None
        self.aws: dict[str, str] | None = None  # accessKeyId/secretKey/sessionToken

    def login(self) -> None:
        with self.lock:
            login = self._form_login()
            data = self._secured_sign_in(login["accessToken"], login["loginId"], login["userSeq"])
            homes = data.get("home") or []
            if not homes:
                raise NavienAuthError("계정에 등록된 home이 없습니다.")

            self.access_token = login["accessToken"]
            self.refresh_token = login.get("refreshToken")
            self.login_id = login["loginId"]
            self.account_seq = login["userSeq"]
            self.user_seq = data["userInfo"]["userSeq"]
            self.home_seq = homes[0]["homeSeq"]
            self._set_aws(data.get("authInfo") or {})
            log.info("로그인 성공 userSeq=%s homeSeq=%s", self.user_seq, self.home_seq)

    def refresh_aws_credentials(self) -> None:
        """`/auth/token/refresh` 는 AWS 자격증명을 안 준다 — secured-sign-in을 다시 부르는 게 유일한 경로."""
        with self.lock:
            if not self.access_token:
                raise NavienAuthError("로그인이 먼저 필요합니다.")
            data = self._secured_sign_in(self.access_token, self.login_id, self.account_seq)
            self._set_aws(data.get("authInfo") or {})

    def _set_aws(self, info: dict[str, Any]) -> None:
        try:
            self.aws = {
                "accessKeyId": info["accessKeyId"],
                "secretKey": info["secretKey"],
                "sessionToken": info["sessionToken"],
            }
        except KeyError:
            self.aws = None
            log.warning("secured-sign-in 응답에 AWS 자격증명이 없습니다.")

    def _form_login(self) -> dict[str, Any]:
        resp = self.http.post(
            f"{LOGIN_URL}/member/login",
            data={"username": self.username, "password": self.password},
            headers={"Origin": LOGIN_URL, "Referer": f"{LOGIN_URL}/member/login"},
            timeout=20,
        )
        html = resp.text
        if 'id="loginFailPopup" style="display:none;"' in html:
            raise NavienAuthError(self._auth_error_message(html))
        if "passwordChg" in html:
            raise NavienAuthError("서버가 비밀번호 변경을 요구합니다. 나비엔 앱에서 먼저 처리하세요.")

        m = re.search(r"var message\s*=\s*(\{.*\})\s*;?\s*$", html, re.MULTILINE)
        if not m:
            raise NavienAuthError(f"로그인 토큰을 찾지 못했습니다 (status={resp.status_code}).")
        data = json.loads(m.group(1))
        if "accessToken" not in data:
            raise NavienAuthError("응답에 accessToken이 없습니다.")
        return data

    @staticmethod
    def _auth_error_message(html: str) -> str:
        if "입력한 정보가 일치하지 않습니다." not in html:
            return "아이디가 올바르지 않습니다."
        m = re.search(r"현재 (\d)회", html)
        if m:
            return f"비밀번호가 올바르지 않습니다 (현재 {m.group(1)}회 실패 — 5회 초과 시 재설정 필요)."
        return "비밀번호가 올바르지 않습니다."

    def _secured_sign_in(self, token: str, login_id: str, account_seq: int) -> dict[str, Any]:
        resp = self.http.post(
            f"{API_URL}/users/secured-sign-in",
            json={"userId": login_id, "accountSeq": account_seq},
            headers={"Authorization": token},
            timeout=20,
        )
        payload = resp.json()
        if payload.get("code") != CODE_SUCCESS:
            raise NavienAuthError(f"secured-sign-in 실패 (code={payload.get('code')})")
        if not payload.get("data"):
            raise NavienAuthError("secured-sign-in 응답에 data가 없습니다.")
        return payload["data"]

    # -- 인증된 REST 요청 (세션 만료 시 1회 재로그인) ----------------------

    def authed_request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        if not self.access_token:
            self.login()
        payload = self._request(method, path, **kwargs)
        if payload.get("code") in (CODE_NOT_AUTHORIZED, CODE_TOKEN_EXPIRED):
            log.info("세션 무효(code=%s) — 재로그인 후 재시도", payload.get("code"))
            self.login()
            payload = self._request(method, path, **kwargs)
        if payload.get("code") != CODE_SUCCESS:
            raise NavienApiError(payload.get("code"), payload.get("msg") or f"{path} 실패")
        return payload

    def _request(self, method: str, path: str, *, params=None, raw_body=None) -> dict[str, Any]:
        headers = {"Authorization": self.access_token}
        data = None
        if raw_body is not None:
            headers["Content-Type"] = "application/json"
            data = raw_body.encode()
        resp = self.http.request(
            method, f"{API_URL}{path}", params=params, headers=headers, data=data, timeout=20
        )
        return resp.json()


# ── AWS SigV4 (AWS IoT WebSocket 사전서명 경로) ──────────────────────────

def _uri_encode(value: str) -> str:
    return urllib.parse.quote(value, safe="-_.~")


def build_signed_ws_path(aws: dict[str, str], region: str = IOT_REGION) -> str:
    """보안 토큰은 서명 계산 뒤에 붙인다 — AWS IoT 규칙."""
    now = datetime.datetime.now(datetime.timezone.utc)
    amzdate = now.strftime("%Y%m%dT%H%M%SZ")
    datestamp = now.strftime("%Y%m%d")
    scope = f"{datestamp}/{region}/{IOT_SERVICE}/aws4_request"

    query = {
        "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
        "X-Amz-Credential": f"{aws['accessKeyId']}/{scope}",
        "X-Amz-Date": amzdate,
        "X-Amz-SignedHeaders": "host",
    }
    canonical_query = "&".join(f"{_uri_encode(k)}={_uri_encode(v)}" for k, v in sorted(query.items()))
    empty_hash = hashlib.sha256(b"").hexdigest()
    canonical_request = "\n".join(
        ["GET", "/mqtt", canonical_query, f"host:{IOT_ENDPOINT}\n", "host", empty_hash]
    )
    string_to_sign = "\n".join(
        [
            "AWS4-HMAC-SHA256",
            amzdate,
            scope,
            hashlib.sha256(canonical_request.encode()).hexdigest(),
        ]
    )

    key = f"AWS4{aws['secretKey']}".encode()
    for part in (datestamp, region, IOT_SERVICE, "aws4_request"):
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(key, string_to_sign.encode(), hashlib.sha256).hexdigest()

    token = urllib.parse.quote(aws["sessionToken"], safe="")
    return f"/mqtt?{canonical_query}&X-Amz-Signature={signature}&X-Amz-Security-Token={token}"


# ── 기기 레지스트리 (등록정보 캐시) ───────────────────────────────────────

class DeviceStore:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.devices: dict[str, dict[str, Any]] = {}  # deviceId -> registry

    def set_devices(self, mats: list[dict[str, Any]]) -> None:
        with self.lock:
            self.devices = {}
            for dev in mats:
                attrs = ((dev.get("Properties") or {}).get("registry") or {}).get("attributes") or {}
                heat_control = (attrs.get("functions") or {}).get("heatControl") or {}
                mcu = attrs.get("mcu") or {}
                nick = (dev.get("Properties") or {}).get("nickName")
                side = nick.get("side") if isinstance(nick, dict) else None
                is_double = (mcu.get("capacity") == 2) or bool(side)
                self.devices[dev["deviceId"]] = {
                    "deviceSeq": dev["deviceSeq"],
                    "deviceId": dev["deviceId"],
                    "serviceCode": dev["serviceCode"],
                    "modelCode": dev["modelCode"],
                    "modelName": dev.get("modelName"),
                    "zones": ["left", "right"] if is_double else ["single"],
                    "unit": heat_control.get("unit"),
                    "rangeMin": heat_control.get("rangeMin"),
                    "rangeMax": heat_control.get("rangeMax"),
                }

    def get(self, device_id: str) -> dict[str, Any] | None:
        with self.lock:
            return self.devices.get(device_id)

    def list(self) -> list[dict[str, Any]]:
        with self.lock:
            return list(self.devices.values())


# ── 나비엔 REST 얇은 래퍼 ─────────────────────────────────────────────────

class NavienApi:
    def __init__(self, session: NavienSession) -> None:
        self.session = session

    def get_devices(self) -> list[dict[str, Any]]:
        payload = self.session.authed_request(
            "GET", "/devices", params={"homeSeq": self.session.home_seq, "userSeq": self.session.user_seq}
        )
        devices = (payload.get("data") or {}).get("devices") or []
        return [d for d in devices if d.get("serviceCode") == SERVICE_MATE]

    def control(self, device: dict[str, Any], desired: dict[str, Any]) -> None:
        """`desired`를 shadow로 중계한다. 빈 dict를 보내면 현재 상태를 다시 올려달라는 요청이 된다."""
        topic = f"$aws/things/{device['deviceId']}/shadow/name/status/update"
        body_obj = {
            "serviceCode": device["serviceCode"],
            "topic": "\x00TOPIC\x00",
            "payload": {"state": {"desired": {"event": {"modelCode": int(device["modelCode"])}, **desired}}},
        }
        # 앱은 topic 의 '/' 를 '\/' 로 이스케이프해 보낸다. 서버가 까다로울 수 있어 맞춘다.
        raw = json.dumps(body_obj, ensure_ascii=False).replace(
            '"\\u0000TOPIC\\u0000"', json.dumps(topic).replace("/", "\\/")
        )
        self.session.authed_request(
            "POST",
            f"/devices/{device['deviceSeq']}/control",
            params={"homeSeq": self.session.home_seq, "userSeq": self.session.user_seq},
            raw_body=raw,
        )


# ── 로컬 MQTT 발행 (Hubitat 브로커로) ─────────────────────────────────────

class LocalPublisher:
    def __init__(self) -> None:
        self.client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2, client_id=f"navien-bridge-{uuid.uuid4()}"
        )
        if MQTT_USERNAME:
            self.client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
        self.client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
        self.client.loop_start()

    def publish_state(self, device_id: str, reported: dict[str, Any]) -> None:
        topic = f"{MQTT_PREFIX}/mate/{device_id}/state"
        self.client.publish(topic, json.dumps(reported, ensure_ascii=False), qos=0, retain=True)

    def publish_registry(self, device: dict[str, Any]) -> None:
        topic = f"{MQTT_PREFIX}/mate/{device['deviceId']}/registry"
        self.client.publish(topic, json.dumps(device, ensure_ascii=False), qos=0, retain=True)

    def publish_status(self, status: str) -> None:
        self.client.publish(f"{MQTT_PREFIX}/bridge/status", status, qos=0, retain=True)


# ── AWS IoT 구독기 ────────────────────────────────────────────────────────

class CloudSubscriber:
    """구독 전용. 발행하지 않는다 — 제어는 REST(NavienApi.control)로 간다."""

    def __init__(
        self, session: NavienSession, api: NavienApi, store: DeviceStore, publisher: LocalPublisher
    ) -> None:
        self.session = session
        self.api = api
        self.store = store
        self.publisher = publisher
        self._client: mqtt.Client | None = None
        self._connected = threading.Event()
        self._stopping = False

    @property
    def topics(self) -> list[str]:
        return [f"{self.session.home_seq}/mate/#"]

    def run_forever(self) -> None:
        attempt = 0
        while not self._stopping:
            try:
                self.session.refresh_aws_credentials()
                self._connect_once()
                if self._connected.wait(timeout=15):
                    attempt = 0
                    self._request_initial_state()
                    while not self._stopping and self._client and self._client.is_connected():
                        time.sleep(5)
                else:
                    raise TimeoutError("MQTT CONNACK 대기 시간 초과")
            except Exception as err:  # noqa: BLE001 - 어떤 실패든 재시도로 흡수
                log.warning("MQTT 접속 실패: %s", err)

            self._disconnect()
            self.publisher.publish_status("offline")
            if self._stopping:
                break
            delay = RECONNECT_DELAYS[min(attempt, len(RECONNECT_DELAYS) - 1)]
            attempt += 1
            log.info("%s초 후 재접속", delay)
            time.sleep(delay)

    def stop(self) -> None:
        self._stopping = True
        self._disconnect()

    def _connect_once(self) -> None:
        self._connected.clear()
        client_id = f"{uuid.uuid4()}-U{self.session.user_seq}"
        client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2, client_id=client_id, transport="websockets"
        )
        client.on_connect = self._on_connect
        client.on_disconnect = self._on_disconnect
        client.on_message = self._on_message
        client.tls_set()
        client.ws_set_options(path=build_signed_ws_path(self.session.aws))
        self._client = client
        client.connect(IOT_ENDPOINT, 443, keepalive=60)
        client.loop_start()

    def _disconnect(self) -> None:
        client, self._client = self._client, None
        if client:
            try:
                client.disconnect()
            except Exception:  # noqa: BLE001
                pass
            try:
                client.loop_stop()
            except Exception:  # noqa: BLE001
                pass

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):  # noqa: ANN001
        # paho-mqtt 2.x는 reason_code가 ReasonCode 객체다 — .value 없으면 정수로 취급.
        code = getattr(reason_code, "value", reason_code)
        if code != 0:
            log.warning("MQTT 접속 거부 (code=%s)", code)
            return
        self._connected.set()
        self.publisher.publish_status("online")
        for topic in self.topics:
            client.subscribe(topic, qos=0)
        log.info("MQTT 구독 시작: %s", ", ".join(self.topics))

    def _on_disconnect(self, client, userdata, *args):  # noqa: ANN001
        # v2 시그니처는 (client, userdata, disconnect_flags, reason_code, properties) 지만
        # 버전별 차이를 흡수하려고 나머지는 *args로 받는다.
        self._connected.clear()

    def _on_message(self, client, userdata, message):  # noqa: ANN001
        try:
            event = json.loads(message.payload)
        except (json.JSONDecodeError, UnicodeDecodeError):
            return
        shadow_topic = event.get("topic") or ""
        if not shadow_topic.endswith("/update/accepted"):
            return
        state = ((event.get("payload") or {}).get("state")) or {}
        reported = state.get("reported")
        if not isinstance(reported, dict):
            return
        device_id = (reported.get("info") or {}).get("deviceId") or message.topic.rsplit("/", 1)[-1]
        if not device_id:
            return
        log.debug("상태 수신: %s heater=%s", device_id, reported.get("heater"))
        self.publisher.publish_state(device_id, reported)

    def _request_initial_state(self) -> None:
        """구독 직후 빈 desired로 찔러서 현재 shadow 상태를 받아온다."""
        for device in self.store.list():
            try:
                self.api.control(device, {})
            except Exception as err:  # noqa: BLE001
                log.warning("초기 상태 요청 실패 (%s): %s", device["deviceId"], err)


# ── 로컬 HTTP 제어 API ─────────────────────────────────────────────────────

app = Flask(__name__)
_ctx: dict[str, Any] = {}


@app.get("/health")
def health():
    session: NavienSession = _ctx["session"]
    return jsonify({"ok": True, "homeSeq": session.home_seq, "deviceCount": len(_ctx["store"].list())})


@app.get("/devices")
def list_devices():
    return jsonify(_ctx["store"].list())


@app.post("/control")
def control():
    body = request.get_json(force=True, silent=True) or {}
    device_id = body.get("deviceId")
    desired = body.get("desired")
    if not device_id or not isinstance(desired, dict):
        return jsonify({"ok": False, "error": "deviceId, desired가 필요합니다."}), 400

    device = _ctx["store"].get(device_id)
    if not device:
        return jsonify({"ok": False, "error": f"알 수 없는 deviceId: {device_id}"}), 404

    try:
        _ctx["api"].control(device, desired)
    except NavienApiError as err:
        return jsonify({"ok": False, "error": str(err), "code": err.code}), 502
    except Exception as err:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(err)}), 502
    return jsonify({"ok": True})


# ── 기동 ────────────────────────────────────────────────────────────────

def main() -> None:
    session = NavienSession(NAVIEN_USERNAME, NAVIEN_PASSWORD)
    session.login()

    api = NavienApi(session)
    store = DeviceStore()
    mats = api.get_devices()
    if not mats:
        log.warning("계정에 등록된 숙면매트가 없습니다.")
    store.set_devices(mats)

    publisher = LocalPublisher()
    for device in store.list():
        publisher.publish_registry(device)

    subscriber = CloudSubscriber(session, api, store, publisher)

    _ctx["session"] = session
    _ctx["api"] = api
    _ctx["store"] = store
    _ctx["publisher"] = publisher
    _ctx["subscriber"] = subscriber

    threading.Thread(target=subscriber.run_forever, daemon=True, name="cloud-subscriber").start()

    log.info("HTTP API 기동: 0.0.0.0:%s", HTTP_PORT)
    app.run(host="0.0.0.0", port=HTTP_PORT)


if __name__ == "__main__":
    main()
