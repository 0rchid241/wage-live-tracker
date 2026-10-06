# 실시간 출퇴근 흐름 (Issue #4)

최신 Issue #4 명세를 기준으로 설정 → 홈 → 출근 → 휴게/재개 → 퇴근 정산 → 홈을 구현했다.
Foreground Service, 알림, 월간 화면은 이번 범위에 포함하지 않는다.

## 상태와 계층

`LiveShiftViewModel`의 StateFlow는 `Loading`, `SetupRequired`, `Idle`, `Active`,
`Summary`, `Error`로 구성한다. Active의 열린 휴게 유무로 근무/휴게를 구분하며
`ShiftOperation`으로 저장 중 중복 요청을 막는다. 실패하면 기존 상태를 유지하고 재시도한다.
`MainActivity`는 설정/근무 화면 연결과 lifecycle 조립만 담당한다.
`LiveShiftScreen`은 상태와 콜백을 받아 급여, 상태, 경과시간, Breakdown, 버튼을 표시한다.

## 계산과 시간

Application에서 제공하는 `Clock`을 주입하고 화면이 STARTED일 때 약 1초마다 갱신한다.
백그라운드에서는 ticker를 중지한다. 각 tick은 DB를 읽거나 쓰지 않는다.
`LiveWageProjection`이 Shift Snapshot, 저장된 휴게, 현재 시각을 기존 `WageCalculator`에
전달한다. 열린 휴게는 계산에만 현재 시각까지의 구간으로 변환한다. 따라서 휴게 중에는
총 경과시간만 증가하고 유급시간/급여는 고정된다. 출근 0초는 0원이며 0초 휴게는 제외한다.

현재 초 시작부터 1초 구간을 같은 엔진으로 계산하여 급여 속도와 야간가산 표시를 구한다.
초 미만 시각에도 22시 이전에 야간 안내를 미리 켜지 않는다. 금액은 BigDecimal이고
표시에서만 원 미만을 버린다. 연장가산은 기존 엔진 정책대로 0원이다.
실행 중 표시 시각은 뒤로 줄이지 않으며, 저장 시각이 유효하지 않으면 저장 오류로 알린다.
기존 데이터 모델의 로컬 시각 정책을 유지한다.

## 저장과 복구

`LiveShiftStore`를 통해 `WorkRepository`의 atomic API를 호출한다. 출근 시 프로필을
Snapshot으로 복사하고, 휴게 시작/종료/근무 완료는 각각 Room transaction으로 처리한다.
휴게 중 퇴근은 휴게 종료와 Shift 완료를 함께 커밋하거나 함께 롤백한다.
같은 시각에 닫힌 휴게는 0초이므로 삭제한다. 기존 `completeShift`의 엄격한 정책은 유지한다.
Room version 1과 schema, 기존 계산 엔진은 변경하지 않았다.

Activity/프로세스 재진입 시 진행 중 Shift와 휴게를 우선 조회한다. 현재 프로필이 달라져도
Snapshot으로 계산하며 열린 휴게는 휴게 상태로 복원한다. 정산은 종료 시각을 사용해 고정되고
확인 후 홈으로 돌아간다. 완료 기록은 Room에 남으며 정산 화면 자체의 프로세스 종료 복원은
포함하지 않는다.

## 검증

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.orchid.wagelivetracker.test/androidx.test.runner.AndroidJUnitRunner
```

JVM 99개: 기존 75개 + 실시간 상태/계산/시간/오류 24개.
Pixel 8 / API 35의 AndroidJUnitRunner에서 **OK (54 tests)**를 확인했다.
Android 54개: 기존 33개 + Room atomic/recovery 12개 + 화면 5개 + 앱 통합 4개.
기존 Issue #12 때문에 connected task의 성공 종료만으로 통과를 판단하지 않는다.
Room 테스트는 완료 UPDATE 실패를 주입해 휴게 종료가 롤백되는지도 검증한다.
시간 주입 테스트는 실제 대기 없이 휴게, 여러 휴게, 22시/06시, 5인 미만을 검증한다.

별도의 실제 화면 검증에서는 시급 12,000원, Cafe, 5인 이상으로 설정하고 출근했다.
급여 증가 후 휴게 중 금액이 3,971원으로 고정되고, force-stop/COLD 실행 후에도
열린 휴게와 같은 금액이 복구됐다. 휴게 종료 후 3,976원으로 증가했으며, 근무 중
force-stop/COLD 실행 후 4,002원으로 재계산됐다. 두 번째 휴게 중 퇴근하여 최종
6,566원과 경과/휴게/유급시간, Breakdown을 확인했다. 확인 → 홈과 완료 후 COLD
실행 → 홈도 검증했다. 근무 및 정산 화면 캡처로 숫자·버튼·안내의 가독성을 확인했다.
