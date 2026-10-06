# 로컬 근무 데이터 저장

Issue #2는 `androidx.room` 2.8.5, KSP 2.3.6을 사용한다. AGP 9.4.1 및
Kotlin 설정 2.2.10은 변경하지 않았다. Kotlin/Coroutines용 Room 구현은
`room-runtime`에 포함되므로 별도의 `room-ktx` 의존성은 추가하지 않았다.

## 계층과 관계

- `data/local`: Room Database, DAO, Entity, TypeConverter.
- `data/repository`: 화면에 제공할 일반 Kotlin 저장 모델, mapper, `WorkRepository`.
- `domain/wage`: 기존 순수 계산 엔진 그대로 유지. Room과 Android를 참조하지 않는다.

`work_profiles` 1:N `shifts` 1:N `breaks` 구조이며 각 FK 열에 Index가 있다.
근무 상태에도 Index를 두어 진행 중 근무를 조회한다. DAO는 저장 구현용이며
앱의 저장·수정은 Repository를 통한다. 공개 Repository API에는 Room Entity가 없다.

## 시간, 금액, 상태

시간은 `LocalDateTime.toString()`의 ISO 로컬 문자열(TEXT)로 저장하고
`LocalDateTime.parse()`로 복원한다. 날짜·초·나노초를 보존하며 기기 timezone에
의존하지 않는다. 생성/수정 시각도 호출자가 같은 로컬 기준으로 제공한다.
ISO 표현의 소수초 길이는 가변이므로 시간 비교와 휴게 정렬은 복원한
`LocalDateTime`으로 수행한다. 추후 시간 범위 SQL 검색을 추가할 때 문자열
순서만으로 시간 순서를 판단해서는 안 된다.

시급과 가산비율은 `BigDecimal.toString()` ↔ `BigDecimal(String)`으로 저장한다.
수치, 소수 정밀도, scale(음수 scale 포함)을 보존한다. Double/Float를 거치지 않는다.
상태는 명시적인 `IN_PROGRESS`, `COMPLETED` 문자열이며 ordinal을 사용하지 않는다.
알 수 없는 상태는 복원 오류로 드러낸다. 상태 저장값 변경에는 migration이 필요하다.

## 현재 프로필과 Snapshot

프로필은 여러 개 저장할 수 있고 `isCurrent`로 현재 사용할 프로필을 선택한다.
`saveProfile(..., makeCurrent = true)`는 트랜잭션 안에서 기존 선택을 해제한다.
`makeCurrent = false`는 기존 선택 상태를 유지하며 새 프로필은 선택하지 않는다.
아직 프로필이 없으면 현재 프로필은 null이다. 생성 시각은 수정할 수 없으며
수정 시각은 이전 값보다 작아질 수 없다. 갱신은 `@Update`로 수행하고 FK 삭제가
발생할 수 있는 `INSERT OR REPLACE`는 사용하지 않는다.

`startShift`는 저장된 프로필을 읽어 다음 값 전체를 Shift에 복사한다.

- 시급
- 5인 이상 여부
- 야간가산 비율
- 연장가산 비율

이 복사와 근무 생성은 하나의 트랜잭션이다. 이후 프로필 변경은 과거 Shift에
영향을 주지 않는다. 근무 날짜 수정 API도 Snapshot과 프로필 연결을 유지한다.
완료 기록의 `StoredShift.toWageInput()`은 Shift의 Snapshot과 소속 휴게만 읽어
`WageCalculator.calculate(input.workPeriod, input.condition, input.breaks)`에 전달한다.
급여를 DB에 매초 저장하거나 계산 로직을 새로 구현하지 않는다.

## 진행 중 근무와 휴게

진행 중 근무는 `endedAt = null`, 완료 근무는 종료시각이 시작보다 늦어야 한다.
`startShift`의 기존 진행 중 근무 확인과 INSERT를 Room 트랜잭션으로 직렬화한다.
같은 DB를 쓰는 여러 Repository의 동시 호출도 하나만 성공한다.
다중 활성 근무나 다중 현재 프로필을 조회하면 오류를 내고 비정상 상태를 숨기지 않는다.

근무와 휴게 묶음 조회는 `@Transaction`/`@Relation`을 사용한다.
열린 휴게는 종료시각 null로 보존되므로 앱 재시작 후 그대로 복원된다.
Repository는 휴게가 근무 시작보다 이르거나, 서로 겹치거나, 여러 개가 열려 있는
경우를 거부한다. 맞닿은 휴게는 허용한다. 완료 근무의 휴게는 모두 닫혀 있어야 하며
근무 종료 안에 포함되어야 한다. 기존 `completeShift`는 휴게를 닫지 않은 채 종료하면
변경 없이 실패한다. Issue #4의 `finishShift`는 열린 휴게 종료와 Shift 완료를 하나의
트랜잭션으로 처리한다. `startBreak`, `endBreak`, `getOpenBreak`도 트랜잭션을 사용한다.
시작과 같은 시각에 종료한 휴게는 0초이므로 삭제하며, 이 삭제도 완료 저장 실패 시
함께 롤백한다. 자세한 화면·시간 처리 정책은 [실시간 흐름](LIVE_SHIFT_FLOW.md)을 참고한다.

`completeShift`, `updateShiftTimes`, `saveBreak`는 관련 휴게 검증과 저장을 동일
트랜잭션으로 수행한다. 위반은 `IllegalArgumentException`, 존재하지 않는 기록이나
중복 상태는 `IllegalStateException`으로 알린다. 완료 근무의 시간/휴게 수정도 지원한다.
완료 상태를 진행 중 상태로 되돌리는 API는 제공하지 않는다.

직접 DAO 쓰기는 FK 이외의 Repository 정책을 우회할 수 있다. DAO는 일반 UI에서
호출하지 않으며, 여러 활성 근무 등의 원시 데이터 위반은 읽을 때 오류로 드러낸다.
DB 트리거 또는 특수 SQLite 기능은 사용하지 않는다.

## 삭제와 버전

- Shift 삭제: 소속 Break는 FK `CASCADE`로 함께 삭제한다.
- WorkProfile 삭제: Shift가 참조하면 FK `RESTRICT`로 거부해 과거 기록을 보존한다.
  미참조 프로필만 삭제할 수 있다. 프로필 비활성화/보관 UI는 이후 범위다.
- Break 삭제: 해당 휴게만 삭제한다.

`WageDatabase`는 version 1, `exportSchema = true`다. Room Gradle plugin이
`app/schemas/com.orchid.wagelivetracker.data.local.database.WageDatabase/1.json`을
생성한다. schema JSON은 소스와 함께 버전 관리하고 이전 파일을 보존한다.
향후 스키마 변경은 버전을 올리고 명시적인 migration과 migration 테스트를 추가한다.
`fallbackToDestructiveMigration()`은 사용하지 않는다.

앱 조립 시 `WageDatabase.open(applicationContext)`와 `WorkRepository(database)`를
애플리케이션 범위에서 한 번 생성하고 재사용한다. Issue #4에서 `LiveShiftStore`를
통해 출퇴근 ViewModel과 연결했으며 DAO는 UI에 노출하지 않는다.

## 검증

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb devices
.\gradlew.bat connectedDebugAndroidTest
```

JVM 테스트는 변환기와 계산 입력 mapper 및 기존 계산 엔진을 검증한다.
Android 테스트는 in-memory Room의 CRUD, 관계, FK, Snapshot, 동시 호출을 검증하고,
파일 DB를 닫았다가 다시 열어 진행 중 근무·열린 휴게 복구도 검증한다.
향후 migration 자체의 테스트는 첫 스키마 변경 시 추가한다.

이번 로컬 검증에서 JVM 테스트는 총 53개(기존 44개 + 저장 변환/mapper 9개)가 통과했다.
`assembleDebugAndroidTest`도 성공했다. Pixel 8 / API 35 에뮬레이터가 연결되어 있었지만,
`connectedDebugAndroidTest`는 성공 종료와 함께 XML 보고서에 실행 0개를 기록했고
테스트 APK도 설치하지 않았다. 이 결과를 실제 테스트 통과로 세지 않는다.
생성된 APK를 직접 설치하고 같은 AndroidJUnitRunner를 호출하여 **23개 통과**
(저장 계층 22개 + 기존 앱 컨텍스트 1개)를 확인했다.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.orchid.wagelivetracker.test/androidx.test.runner.AndroidJUnitRunner
# OK (23 tests)
```

Gradle connected task의 테스트 검색/실행 문제는 원인 미확정이며, 위 직접 실행으로
저장 계층의 실제 Android 검증을 완료했다. 향후 CI에서는 성공 종료만 확인하지 말고
보고서의 실행 개수도 확인해야 한다.
