# 🌱 Smart Farm

Arduino + Android BLE 기반 스마트 화분 자동 제어 시스템

---

## 📖 프로젝트 소개

**Smart Farm**은 Arduino와 Android 앱을 Bluetooth Low Energy(BLE)로 연결하여 화분의 토양 습도와 조도를 실시간으로 모니터링하고, 자동 또는 수동으로 워터펌프와 LED 보조광을 제어하는 임베디드 IoT 프로젝트입니다.

또한 Android 앱 내에 **Gemini AI 기반 식물 진단 기능**이 탑재되어 있어, 카메라로 식물을 촬영하면 식물 이름, 건강 상태, 최적 생장 조건을 자동으로 분석해줍니다.

---

## ✨ 주요 기능

### 🔌 Arduino (펌웨어)
- **토양 습도 자동 급수** — 설정한 임계값 이하로 습도가 떨어지면 워터펌프 자동 작동
- **조도 자동 보조광** — 설정한 임계값보다 어두워지면 LED 자동 점등
- **BLE 실시간 데이터 송신** — 습도, 조도, 임계값, 각 장치 상태를 주기적으로 전송
- **BLE 명령 수신** — 앱에서 수동 모드 전환 및 펌프/LED 개별 제어
- **물리 버튼** — 기기 자체 버튼으로 임계값 조정 및 수동 급수 가능
- **EEPROM 저장** — 전원이 꺼져도 임계값 설정 유지
- **I2C LCD 표시** — 현재 습도, 조도, 임계값 실시간 표시

### 📱 Android 앱
- **BLE 자동 재연결** — 마지막으로 연결된 기기 주소를 저장하여 앱 실행 시 자동 연결 시도
- **실시간 대시보드** — 토양 습도, 조도, 각 임계값을 카드 형태로 표시
- **수동 제어** — 수동 모드 전환 후 펌프/LED 개별 ON/OFF 제어
- **임계값 원격 설정** — 앱에서 습도·조도 임계값을 직접 전송하여 변경
- **AI 식물 진단** — 카메라로 식물을 촬영하여 Gemini API로 이름, 상태, 생장 조건 분석

---

## 🏗️ 프로젝트 구조

```
Smart-Farm-main/
├── 4.ino                          # Arduino 펌웨어
├── AndroidManifest.xml            # Android 앱 매니페스트
├── java/
│   ├── MainActivity3.java         # 메인 액티비티 (ViewPager2)
│   ├── PagerAdapter.java          # 페이지 어댑터 (2페이지)
│   ├── ThirdPageFragment.java     # BLE 제어 페이지 (1페이지)
│   └── SecondPageFragment.java    # AI 식물 진단 페이지 (2페이지)
├── assets/
│   ├── ble_control.html           # BLE 대시보드 UI (WebView)
│   └── searchplant.html           # AI 식물 진단 UI (WebView)
└── res/
    └── layout/
        ├── activity_main3.xml
        └── fragment_page_2.xml
```

---

## 🛠️ 하드웨어 구성

| 부품 | 역할 | 핀 |
|---|---|---|
| 토양 습도 센서 | 토양 수분 측정 | A0 |
| CDS 조도 센서 | 주변 밝기 측정 | A1 |
| 워터펌프 + 모터 드라이버 | 급수 제어 | D8, D9 |
| LED | 보조광 | D10 |
| HM-10 (BLE 모듈) | 앱과 BLE 통신 | TX/RX (Serial) |
| I2C LCD (16x2) | 현재 상태 표시 | SDA/SCL (0x27) |
| 버튼 x3 | UP / DOWN / MODE | D2, D3, D4 |

---

## 📡 BLE 통신 프로토콜

### Arduino → 앱 (데이터 전송)

Arduino는 다음 형식으로 데이터를 주기적으로 전송합니다:

```
H,{습도}%,L,{조도},HT,{습도임계값},LT,{조도임계값},MP,{0|1},ML,{0|1},SM,{0|1},RP,{0|1},RL,{0|1}
```

| 키 | 설명 |
|---|---|
| `H` | 토양 습도 (%) |
| `L` | 조도 (raw ADC 값, 0=밝음) |
| `HT` | 습도 임계값 |
| `LT` | 조도 임계값 |
| `MP` | 펌프 수동 제어 여부 |
| `ML` | LED 수동 제어 여부 |
| `SM` | 시스템 수동 모드 여부 |
| `RP` | 펌프 현재 작동 상태 |
| `RL` | LED 현재 작동 상태 |

### 앱 → Arduino (명령 수신)

| 명령 | 설명 |
|---|---|
| `MANUAL_ON` | 수동 모드 활성화 |
| `MANUAL_OFF` | 자동 모드 복귀 |
| `PUMP_ON` | 펌프 켜기 (수동 모드 전용) |
| `PUMP_OFF` | 펌프 끄기 (수동 모드 전용) |
| `LED_ON` | LED 켜기 (수동 모드 전용) |
| `LED_OFF` | LED 끄기 (수동 모드 전용) |
| `SET_H_{값}` | 습도 임계값 설정 (0~90) |
| `SET_L_{값}` | 조도 임계값 설정 (0~1023) |

---

## 🚀 시작하기

### 1. Arduino 펌웨어 업로드

**필요 라이브러리:**
- `LiquidCrystal_I2C`
- `Wire` (기본 내장)
- `EEPROM` (기본 내장)

1. Arduino IDE에서 `4.ino`를 엽니다.
2. 라이브러리 매니저에서 `LiquidCrystal_I2C`를 설치합니다.
3. 보드를 연결하고 업로드합니다.

### 2. AI 식물 진단 기능 활성화

`assets/searchplant.html` 파일 내 API 키 자리를 실제 키로 교체합니다:

```javascript
// searchplant.html
const GEMINI_API_KEY = "YOUR_GEMINI_API_KEY"; // ← 여기에 실제 키 입력
```

Gemini API 키는 [Google AI Studio](https://aistudio.google.com/)에서 발급받을 수 있습니다.

### 3. Android 앱 빌드

1. Android Studio에서 프로젝트를 엽니다.
2. `build.gradle`에서 패키지 의존성을 확인합니다 (`com.google.code.gson:gson` 필요).
3. Android 12 이상 기기에서 **Bluetooth 권한**을 허용합니다.
4. 앱을 빌드하고 기기에 설치합니다.

---

## 🔧 제어 우선순위

펌프 제어는 아래 우선순위에 따라 동작합니다:

```
1순위: BLE 수동 모드 (MANUAL_ON 명령)
   └─ PUMP_ON / PUMP_OFF 명령으로만 제어

2순위: 물리 버튼 수동 급수 (UP + MODE 동시 누름)

3순위: 자동 급수 (습도 < 임계값이면 펌프 작동)
```

---

## 📋 요구사항

| 항목 | 사양 |
|---|---|
| Arduino | Uno / Nano (ATmega328P) |
| Android | API 21 (Android 5.0) 이상 |
| BLE 모듈 | HM-10 (UUID: `0000ffe0-...`) |
| Gemini API | `gemini-2.5-flash` 모델 |

---

## 📄 라이선스

This project is open source. Feel free to use and modify.
