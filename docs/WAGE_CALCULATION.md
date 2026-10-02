# 급여 계산 엔진

Issue #1의 순수 Kotlin API는 `com.orchid.wagelivetracker.domain.wage`에 있다.
Android 의존성과 추가 라이브러리 없이 `java.time`과 `BigDecimal`을 사용한다.

```kotlin
val result = WageCalculator().calculate(
    workPeriod = WorkPeriod(
        LocalDateTime.parse("2026-10-02T21:00"),
        LocalDateTime.parse("2026-10-03T07:00"),
    ),
    condition = WorkCondition(
        hourlyWage = BigDecimal("10000"),
        hasAtLeastFiveEmployees = true,
    ),
    breaks = listOf(BreakPeriod(
        LocalDateTime.parse("2026-10-02T23:00"),
        LocalDateTime.parse("2026-10-03T00:30"),
    )),
)
// 유급 8시간 30분, 야간 6시간 30분
// 기본급 85,000원 + 야간가산 32,500원 = 예상 총액 117,500원
```

## 계산 정책

- 입력은 같은 로컬 시간 기준의 날짜 포함 `LocalDateTime`이다. 타임존 변환과
  일광절약시간에 따른 실제 경과시간 보정은 호출자가 책임진다.
  종료 시각에 날짜를 암묵적으로 더하지 않는다.
- 모든 구간은 시작 포함, 종료 제외다. 근무와 휴게의 종료는 시작보다 늦어야 한다.
  잘못된 입력은 `IllegalArgumentException`으로 거부한다.
- 휴게는 근무 안에 완전히 포함되어야 한다. 입력 순서는 무관하며 서로 겹치거나
  중복된 휴게는 예외로 거부한다. 맞닿은 휴게와 근무 전체를 덮는 휴게는 허용한다.
- 휴게를 뺀 구간에서 전체 유급시간과 야간시간을 각각 계산한다.
  야간구간은 매일 22:00부터 다음 날 06:00까지다.
- 여러 날짜에 걸친 근무와 장시간 입력을 허용한다. 명세에 최대 근무기간이 없으므로
  임의의 상한을 두지 않는다. 야간시간은 날짜별 반복 없이 완전한 날짜 수와 양 끝
  날짜의 부분 구간으로 계산한다. 긴 기간 허용은 법적 적정성 판단을 의미하지 않는다.
- 기본급은 전체 유급시간 × 시급이다. 야간가산은 야간 유급시간 × 시급 × 가산비율로
  별도 계산한다. 현재 제품 기본 비율은 `0.5`이며 `WorkCondition`에서 변경 가능하다.
  5인 이상 조건이 꺼져도 야간시간은 반환하며 야간가산액만 0이다.
- 금액은 원화 `BigDecimal`이며 음수 시급과 음수 비율은 거부한다. 시간은 나노초까지
  보존하고 중간 곱셈은 정확하게 수행한다. 3,600초로 나눌 때 유한소수는 정확하게
  보존하며, 무한소수만 `DECIMAL128`(유효숫자 34자리, HALF_EVEN)로 반올림한다.
  총액은 반환한 기본급과 각 가산액의 정확한 합이다. 정수 원 표시 정책은 UI가 정한다.
- 연장근로는 현재 기본값으로 시간과 금액 모두 0이다. 추후 `OvertimeRule`로 휴게가
  제외된 구간의 적용시간을 정하고 `overtimePremiumRate`를 지정할 수 있다.
  기본 연장가산 비율도 0이며 일/주 기준, 대상 조건 등은 아직 구현하지 않는다.
  규칙의 결과는 0 이상, 전체 유급시간 이하여야 한다. 야간과 연장은 각각의 가산액을
  더하므로 기본급을 다시 더하지 않는다.

실시간 화면은 계산할 기준 시각을 `WorkPeriod.end`로 전달해 재계산할 수 있다.
시작과 기준 시각이 같은 순간은 화면에서 초기값 0을 표시한다. 열린 휴게는 호출자가
현재 기준 시각까지의 닫힌 구간으로 만들어 전달한다. 엔진은 시계나 저장소를 읽지 않는다.

## 로컬 검증

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

경계값과 잘못된 입력, 소수 금액, 여러 날짜 및 연장규칙 연결은
`WageCalculatorTest`의 JVM 단위 테스트에서 검증한다.
