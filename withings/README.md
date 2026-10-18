# Withings

[English README](./README.en.md)

Withings 기기(Sleep Analyzer, 체중계, 혈압계, 체온계, 활동량계)를 Hubitat에 연동하는
앱 + 드라이버입니다. [lnjustin/hubitat-withings](https://github.com/lnjustin/hubitat-withings)
(v1.7.4, 원저작 Dominick Meglio)를 그대로 가져와 **Hubitat 2.5.x 펌웨어에서 동작하도록
수정한 사본**입니다.

## 왜 수정했나

Hubitat 2.5.x 펌웨어부터 앱/드라이버 코드에서 `metaClass` 접근이 보안상 차단됩니다.
원본 `Withings User` 앱은 스크립트 최상단에서 `String.metaClass.toSHA1`로 String
클래스를 확장하는데, 이 코드가 앱이 실행될 때마다 가장 먼저 돌기 때문에 다음 에러로
앱 전체가 시작 직후 실패합니다.

```
java.lang.SecurityException: Subscript property 'metaClass' is not allowed from app or driver code on line 78 (method run)
```

결과적으로 Withings 웹훅(침대 진입/이탈, 수면 요약, 체중 등)은 계속 들어오지만
기기 상태가 전혀 갱신되지 않습니다. 원본 저장소는 2025-01 이후 업데이트가 없어
여기서 직접 고쳤습니다.

## 변경 내용 (v1.7.5)

- `apps/Withings_User.groovy`: `String.metaClass.toSHA1` 확장을 일반 메서드
  `toSHA1(String str, String salt)`로 교체하고 호출부를 수정
- 해시 알고리즘·결과는 동일하므로 가상 활동량계의 DNI 등 기존 기기에 영향 없음
- 나머지 파일(`Withings_Integration.groovy`, `drivers/*`)은 원본과 동일

## 구성

```
withings/
├── apps/
│   ├── Withings_Integration.groovy   # 부모 앱 (OAuth 클라이언트 설정)
│   └── Withings_User.groovy          # 사용자별 자식 앱 (v1.7.5, 수정본)
└── drivers/
    ├── Withings_Activity_Tracker.groovy
    ├── Withings_Blood_Pressure_Monitor.groovy
    ├── Withings_Scale.groovy
    ├── Withings_Sleep_Sensor.groovy
    └── Withings_Thermometer.groovy
```

## 적용 방법

이미 원본을 설치해 쓰고 있다면 **Apps Code → Withings User**의 코드만
`apps/Withings_User.groovy`로 교체하고 저장하면 됩니다. 앱 재설정이나 Withings
재인증은 필요 없습니다.

신규 설치는 원본 README의 절차(Withings 개발자 앱 등록 → 부모 앱 설치 → 사용자
추가)를 따르되, `Withings User`만 이 폴더의 파일을 사용하세요.

> HPM으로 원본 패키지를 설치했다면 HPM의 업데이트/Repair가 이 수정을 원본으로
> 덮어쓸 수 있습니다.

## 라이선스

원본과 동일하게 BSD 3-Clause License를 따릅니다(각 파일 헤더 참고). 저장소 루트의
Apache 2.0은 이 폴더에 적용되지 않습니다.
