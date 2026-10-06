# 월간 근무 기록 및 상세 수정 (Issue #5)

홈의 `근무 기록`에서 월간 목록으로 이동한다. 이전/다음 달, 완료 근무 목록, 월 총
유급시간·휴게시간·예상 급여와 수동 추가를 제공한다. 개별 기록의 상세에서 시간·시급·
휴게를 수정하거나 확인 후 삭제할 수 있다. 진행 중 근무 화면에는 기록 진입 버튼이 없다.

## 월 귀속과 계산

Shift는 **출근 시각의 월**에 하나의 기록으로 귀속한다. 예를 들어
10월 31일 22:00 → 11월 1일 06:00은 전체가 10월 기록이다. 진행 중 Shift는 제외한다.
ISO 시간 문자열의 가변 소수초 길이 때문에 월 필터와 정렬은 복원된 LocalDateTime으로
수행한다. 현재 데이터 규모에서는 완료 관계 목록을 읽고 Repository에서 월을 필터한다.

`HistoryCalculation`은 완료 기록의 `StoredShift.toWageInput()`으로 출퇴근·휴게·
**해당 Shift의 conditionSnapshot**을 받아 기존 `WageCalculator`를 호출한다.
현재 WorkProfile의 조건은 과거 급여 계산에 사용하지 않는다. 월 합계는 각 결과의
BigDecimal 금액과 Duration을 합산한다. 항목마다 표시에서 버린 원 미만을 합산하지 않으며,
월 합계 표시에서만 원 미만을 버린다. 연장가산은 기존 엔진 정책대로 현재 0원이다.
근무지 표시는 연결된 프로필의 별명을 사용한다. 별명 변경은 표시명에만 영향을 준다.

## 저장 경계와 수정

`WorkHistoryStore` 뒤의 `WorkRepository`만 DAO에 접근한다. 완료 기록 조회·상세,
`updateCompletedShift`, `addCompletedShift`, `deleteCompletedShift`를 제공한다.
기존 DB version 1, Entity와 schema는 유지하며 새 영구 필드를 추가하지 않는다.

수정은 출근/퇴근, hourlyWageSnapshot, 휴게 전체 목록을 한 Room transaction으로
교체한다. Shift id, 프로필 연결, COMPLETED 상태, 5인 이상 여부, 야간/연장 비율은
보존한다. 현재 WorkProfile을 수정하지 않는다. 입력 시급의 수치가 같으면 원래 scale도
유지한다. 휴게는 새 id로 재생성하며 실패하면 기존 휴게 id와 내용까지 모두 롤백한다.
출근 월을 바꾸면 저장된 날짜의 월로 상세/목록이 이동하고 이전 월에서는 빠진다.

## 수동 근무와 삭제

수동 추가는 생성 시점의 현재 WorkProfile을 transaction 안에서 읽어 사업장 조건과
가산비율을 복사하고, 입력한 시급을 Snapshot에 적용한다. 처음부터 COMPLETED로
저장하며 실시간 진행 Shift를 만들지 않는다. 현재 프로필이 없으면 Repository가 거부하고
화면은 홈에서 근무조건을 설정하도록 안내한다. 선택 월이 현재 월이면 오늘, 다른 월이면
그 달 1일의 09:00~18:00을 초기값으로 제안하며 저장 전 변경할 수 있다.

삭제는 상세 화면의 확인 Dialog에서만 실행한다. 완료 여부 확인과 삭제는 transaction이며
FK CASCADE로 휴게도 삭제한다. 진행 중 기록은 History 조회·수정·삭제 대상이 아니다.
추가/삭제 후 월 목록을 다시 읽고, 수정 후 상세를 재계산하며 목록으로 돌아갈 때 합계를
다시 읽는다. 별도 합계 캐시나 매초 금액 저장은 없다.

## validation과 입력

Repository는 두 저장 경로 모두에서 아래 조건을 검증한다.

- 퇴근 > 출근, 시급 > 0 (BigDecimal).
- 휴게 종료 필수, 종료 > 시작.
- 휴게는 근무 범위 안에 포함되고 서로 겹치지 않는다.
- 맞닿은 휴게와 근무 전체를 덮는 휴게는 허용한다.
- 진행 중 Shift 수정/삭제는 거부한다.

화면은 Material 3 DatePicker/TimePicker로 날짜와 시간을 선택한다. 자유 문자열 날짜/시간
입력은 제공하지 않는다. Picker 상태와 `HistoryEditor`의 LocalDateTime 상태를 분리한다.
기존 초/나노초는 편집을 열거나 날짜만 바꿀 때 보존하며, 시간을 선택하면 선택한 분의
0초로 설정한다. 시급 입력은 기존 설정과 같은 64자 이내 십진수, 0원 초과·1,000,000원
이하 정책이다. Editor의 빠른 검증/급여 미리보기와 별개로 Repository가 다시 검증한다.

## 상태와 복구

`WorkHistoryViewModel`은 Closed / Loading / MonthLoaded / Detail / Editing / Error로
구성한다. 저장·삭제 중 중복 요청, 입력 변경, 화면 이탈을 막는다. 저장 오류는 입력을
유지하고 삭제 오류는 상세를 유지한다. 조회 오류는 재시도할 수 있다. 화면 회전 시
ViewModel 상태를 유지하며 앱 재실행 후 저장한 기록은 Room에서 다시 읽는다.
저장하지 않은 편집 초안과 선택 월의 프로세스 종료 복원은 이번 범위에 포함하지 않는다.

## 검증

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.orchid.wagelivetracker.test/androidx.test.runner.AndroidJUnitRunner
```

JVM 120개(기존 99 + History 21)가 통과했다. Pixel 8 / API 35에서 직접 Runner 실행으로
Android **OK (73 tests)**를 확인했다. 기존 54개에 실제 Room 14개와 앱 통합
5개를 추가했다. 통합 A~D는 월 합계, 시간/시급/휴게 수정, 수동 추가 후 재진입, 삭제를
확인하고 추가 사례는 진행 중 화면에 기록 편집 진입이 없음을 확인한다.
Room 테스트는 월 경계·나노초 정렬·Snapshot 보존·validation·파일 DB 재오픈과
UPDATE/INSERT 실패 주입을 통한 rollback을 포함한다. 기존 #1~#4 테스트는 유지한다.
Issue #12 때문에 Gradle connected task의 exit code만으로 Android 통과를 판단하지 않는다.

별도 수동 검증에서는 시급 12,000원/HistoryCafe/5인 이상으로 설정했다. 기존 완료 기록과
첫 수동 기록의 월 합계 114,566원을 확인했다. 실제 DatePicker/TimePicker로 첫 수동 기록을
10월 5일 09:00~15:00, 적용 시급 15,000원, 휴게 12:00~12:30으로 수정하여 상세
82,500원, 월 합계 89,066원으로 즉시 갱신됐다. 홈의 현재 시급은 12,000원 그대로였다.
두 번째 수동 기록 추가 후 force-stop/COLD 실행에서도 세 기록과 월 합계 197,066원이
유지됐다. 수정 기록을 확인 Dialog에서 삭제하여 월 합계 114,566원으로 갱신되고 해당
항목이 사라짐을 확인했다. 화면 캡처로 월 합계·항목·Picker·편집 입력의 가독성을 확인했다.
긴 편집 폼 저장 후 다음 화면의 상단이 보이도록 화면별 스크롤 상태를 분리했고,
통합 테스트에서도 저장 후 상세/월 합계가 실제 표시되는지 검증한다.
