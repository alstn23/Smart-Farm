#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <EEPROM.h>

// I2C LCD 주소와 크기 설정
LiquidCrystal_I2C lcd(0x27, 16, 2);

// 핀 설정
const int soilPin = A0; // 토양 습도 센서
const int cdsPin = A1; // 조도 센서 (CDS)
const int pumpIn1 = 9; // 펌프 드라이버 핀 1
const int pumpIn2 = 8; // 펌프 드라이버 핀 2
const int ledPin = 10; // LED (보조광)
const int btnUp = 2; // 설정값 증가 버튼
const int btnDown = 3; // 설정값 감소 버튼
const int btnMode = 4; // 모드 변경 버튼 (습도/조도)

// EEPROM 주소 정의
const int THRESHOLD_ADDR = 0; // 습도 임계값(int) 저장 주소
const int CDS_THRESHOLD_ADDR = 4; // 조도 임계값(int) 저장 주소

// 변수 초기화
int cdsThreshold = 100;
int threshold = 30;
const int minThreshold = 0;
const int maxThreshold = 90;

unsigned long lastButtonTime = 0;
const unsigned long debounceDelay = 500;

bool pumpRunning = false; // RP: 펌프 현재 작동 상태
bool ledRunning = false; // RL: LED 현재 작동 상태
bool controlHumidity = true; // true: 습도 제어 모드, false: 조도 제어 모드

// 블루투스 수신에 의한 수동 제어 상태 플래그
bool manualPumpOverride = false; // MP (펌프 수동 제어 명령 여부)
bool manualLedOverride = false; // ML (LED 수동 제어 명령 여부)
bool systemManualMode = false; // SM (시스템 수동 모드 여부)

void setup() {
    // 핀 모드 설정
    pinMode(pumpIn1, OUTPUT);
    pinMode(pumpIn2, OUTPUT);
    pinMode(ledPin, OUTPUT);
    pinMode(btnUp, INPUT_PULLUP);
    pinMode(btnDown, INPUT_PULLUP);
    pinMode(btnMode, INPUT_PULLUP);

    // 시리얼 통신 시작 (HM-10 블루투스)
    Serial.begin(9600);

    // LCD 초기화
    lcd.init();
    lcd.backlight();

    // --- EEPROM에서 저장된 값 불러오기 ---
    EEPROM.get(THRESHOLD_ADDR, threshold);
    EEPROM.get(CDS_THRESHOLD_ADDR, cdsThreshold);
    if (threshold < minThreshold || threshold > maxThreshold) {
        threshold = 30;
    }
    // CDS 임계값은 원본 ADC 값(0~1023, 0=밝음)을 기준으로 합니다.
    if (cdsThreshold < 0 || cdsThreshold > 1023) {
        cdsThreshold = 100;
    }
    // ------------------------------------

    lcd.setCursor(0, 0);
    lcd.print("      ");
    delay(1000);
    lcd.clear();
}

/**
 * 버튼 입력을 확인하고 임계값을 변경하며, 변경 시 EEPROM에 저장합니다.
 */
void checkButtons() {
    unsigned long now = millis();

    if (now - lastButtonTime > debounceDelay) {
        bool upPressed = digitalRead(btnUp) == LOW;
        bool downPressed = digitalRead(btnDown) == LOW;
        bool modePressed = digitalRead(btnMode) == LOW;

        bool valueChanged = false;

        // 모드 변경 버튼 (습도/조도 설정 전환)
        if (modePressed) {
            controlHumidity = !controlHumidity;
            lastButtonTime = now;
            return;
        }


        if (upPressed && downPressed) {
            if (controlHumidity) {
                // 습도 제어 모드 (임계값: threshold)
                if (threshold > 0) {
                    // 현재 값이 1 이상이면 0으로 초기화
                    threshold = 0;
                } else {
                    // 현재 값이 0이면 80으로 설정
                    threshold = 80;
                }
                EEPROM.put(THRESHOLD_ADDR, threshold); // EEPROM 저장
            } else {
                // 조도 제어 모드 (임계값: cdsThreshold)
                if (cdsThreshold > 0) {
                    // 현재 값이 1 이상이면 0으로 초기화
                    cdsThreshold = 0;
                } else {
                    // 현재 값이 0이면 500으로 설정
                    cdsThreshold = 500;
                }
                EEPROM.put(CDS_THRESHOLD_ADDR, cdsThreshold); // EEPROM 저장
            }
            
            // 디바운스 처리 후 함수 종료
            lastButtonTime = now;
            return;
        }



        // UP 버튼
        if (upPressed) {
            if (controlHumidity && threshold < maxThreshold) {
                threshold += 1;
                valueChanged = true;
            } else if (!controlHumidity && cdsThreshold < 1023) { // CDS 임계값은 원본 1023까지
                cdsThreshold += 50;
                valueChanged = true;
            }
            lastButtonTime = now;
        }

        // DOWN 버튼
        if (downPressed) {
            if (controlHumidity && threshold > minThreshold) {
                threshold -= 1;
                valueChanged = true;
            } else if (!controlHumidity && cdsThreshold > 0) {
                cdsThreshold -= 50;
                valueChanged = true;
            }
            lastButtonTime = now;
        }

        // 값이 변경되었으면 EEPROM에 저장합니다.
        if (valueChanged) {
            if (controlHumidity) {
                EEPROM.put(THRESHOLD_ADDR, threshold);
            } else {
                EEPROM.put(CDS_THRESHOLD_ADDR, cdsThreshold);
            }
        }
    }
}

void loop() {
    // 1. 센서 값 읽기 및 변환
    int soilVal = analogRead(soilPin);
    // 0(건조) ~ 1023(젖음) -> 0%(건조) ~ 100%(젖음)으로 변환
    int humidity = map(soilVal, 1023, 0, 0, 100);

    int cdsVal = analogRead(cdsPin);
    // CDS 원본 값(0=밝음, 1023=어두움)
    int lightValueMapped = cdsVal;

    // 2. LED(보조광) 제어
    // CDS 원본 값(0=밝음, 1023=어두움)이 임계값보다 크면 (더 어두우면) -> isDark = true
    bool isDark = cdsVal > cdsThreshold;
    // systemManualMode가 켜져 있으면 자동 제어는 무조건 무시!
    if (!systemManualMode) {
        // 자동 제어
        digitalWrite(ledPin, isDark ? LOW : HIGH); // LED는 LOW일 때 켜지는 것이 일반적이지만, 여기서는 HIGH/LOW를 코드에 맞게 수정
        ledRunning = isDark; // LED 상태 변수 업데이트
    } else {
        // 수동 모드에서는 펌프/LED 제어 로직 4.5에서 별도로 처리됨.
        // 여기서는 ledRunning 상태를 현재 핀 상태에 맞춰 유지
        ledRunning = (digitalRead(ledPin) == HIGH); // LED 상태 변수 업데이트 (HIGH: 켜짐으로 가정)
    }

    // 3. 시리얼 통신 및 블루투스(HM-10)로 데이터 출력
    // H, L, MP, ML, SM, RP, RL 7개 값 전송
    String dataToSend = "H," + String(humidity) + "%,L," + String(lightValueMapped) +
                        ",HT," + String(threshold) +         // <--- 습도 임계값(HT) 추가!
                        ",LT," + String(cdsThreshold) +      // <--- 조도 임계값(LT) 추가!
                        ",MP," + String(manualPumpOverride ? "1" : "0") +
                        ",ML," + String(manualLedOverride ? "1" : "0") +
                        ",SM," + String(systemManualMode ? "1" : "0") +
                        ",RP," + String(pumpRunning ? "1" : "0") + // 펌프 작동 상태 추가
                        ",RL," + String(ledRunning ? "1" : "0"); // LED 작동 상태 추가
    Serial.println(dataToSend);
    // 데이터 전송 후 짧은 딜레이 추가: 블루투스 통신 안정화에 도움
    delay(5);

    // 4. 버튼 입력 확인 (임계값 변경 및 EEPROM 저장 포함)
    checkButtons();
    
    // 4.5. 블루투스/시리얼 데이터 수신 및 처리 로직 (명령 안정화)
    if (Serial.available() > 0) {
        // 개행 문자까지 문자열을 읽고 공백 제거 (앱 명령 수신 안정화!)
        String receivedData = Serial.readStringUntil('\n');
        receivedData.trim();

        // --- 임계값 설정 명령 처리 (새로 추가된 부분!) ---
        if (receivedData.startsWith("SET_H_")) {
            int newThreshold = receivedData.substring(6).toInt();
            if (newThreshold >= minThreshold && newThreshold <= maxThreshold) {
                threshold = newThreshold;
                EEPROM.put(THRESHOLD_ADDR, threshold);
            }
        } else if (receivedData.startsWith("SET_L_")) {
            int newCdsThreshold = receivedData.substring(6).toInt();
            if (newCdsThreshold >= 0 && newCdsThreshold <= 1023) {
                cdsThreshold = newCdsThreshold;
                EEPROM.put(CDS_THRESHOLD_ADDR, cdsThreshold);
            }
        }

        // --- 시스템 모드 전환 명령 처리 ---
        else if (receivedData.equals("MANUAL_ON")) {
            systemManualMode = true;
            manualPumpOverride = false;
            manualLedOverride = false;
        } else if (receivedData.equals("MANUAL_OFF")) {
            systemManualMode = false;
            // 자동 모드 복귀 시 펌프/LED 즉시 정지/끔
            digitalWrite(pumpIn1, LOW);
            digitalWrite(pumpIn2, LOW);
            digitalWrite(ledPin, LOW);
            manualPumpOverride = false;
            manualLedOverride = false;
            pumpRunning = false;
            ledRunning = false; // LED 상태 변수 업데이트
        }

        // --- 수동 모드일 때만 ON/OFF 명령 처리 ---
        if (systemManualMode) {
            // 펌프 제어 명령 처리
            if (receivedData.equals("PUMP_ON")) {
                manualPumpOverride = true;
                digitalWrite(pumpIn1, HIGH);
                digitalWrite(pumpIn2, LOW);
                pumpRunning = true; // 펌프 상태 변수 업데이트
            } else if (receivedData.equals("PUMP_OFF")) {
                manualPumpOverride = false;
                digitalWrite(pumpIn1, LOW);
                digitalWrite(pumpIn2, LOW);
                pumpRunning = false; // 펌프 상태 변수 업데이트
            }

            // LED 제어 명령 처리
            else if (receivedData.equals("LED_ON")) {
                manualLedOverride = true;
                digitalWrite(ledPin, HIGH);
                ledRunning = true; // LED 상태 변수 업데이트
            } else if (receivedData.equals("LED_OFF")) {
                manualLedOverride = false;
                digitalWrite(ledPin, LOW);
                ledRunning = false; // LED 상태 변수 업데이트
            }
        }
    }

    // 5. LCD 출력
    lcd.setCursor(0, 0);
    lcd.print("H:");
    lcd.print(humidity);
    lcd.print("% ");

    lcd.setCursor(7, 0);
    lcd.print("HT:");
    lcd.print(threshold);
    lcd.print("% ");

    lcd.setCursor(0, 1);
    lcd.print("L:");
    lcd.print(cdsVal);
    lcd.print("   "); // 공백 깔끔하게 처리

    lcd.setCursor(7, 1);
    lcd.print("LT:");
    lcd.print(cdsThreshold);
    lcd.print("   "); // 공백 깔끔하게 처리

    // 현재 설정 모드 표시
    if (controlHumidity) {
        lcd.setCursor(14, 0);
        lcd.print("<-");
        lcd.setCursor(14, 1);
        lcd.print("   "); // 공백 깔끔하게 처리
    } else {
        lcd.setCursor(14, 1);
        lcd.print("<-");
        lcd.setCursor(14, 0);
        lcd.print("   "); // 공백 깔끔하게 처리
    }

    // 6. 펌프 제어
    bool upPressed = digitalRead(btnUp) == LOW;
    bool modePressed = digitalRead(btnMode) == LOW;
    bool isButtonOverride = upPressed && modePressed;

    // 1순위: systemManualMode가 켜져 있으면 자동 제어 및 버튼 수동 급수 무시!
    if (systemManualMode) {
        // 펌프 상태는 PUMP_ON/OFF 명령(4.5 로직)으로만 제어됨.
    }
    // 2순위: 물리 버튼 수동 급수
    else if (isButtonOverride) {
        digitalWrite(pumpIn1, HIGH);
        digitalWrite(pumpIn2, LOW);
        pumpRunning = true; // 펌프 상태 변수 업데이트
    }
    // 3순위: 자동 급수
    else {
        // 모든 수동 제어가 없을 때만 자동 급수 작동
        if (humidity < threshold) {
            digitalWrite(pumpIn1, HIGH);
            digitalWrite(pumpIn2, LOW);
            pumpRunning = true; // 펌프 상태 변수 업데이트
        } else {
            // 펌프 정지
            digitalWrite(pumpIn1, LOW);
            digitalWrite(pumpIn2, LOW);
            pumpRunning = false; // 펌프 상태 변수 업데이트
        }
    }

    delay(50); // 짧은 지연
}