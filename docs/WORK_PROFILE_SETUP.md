# 기본 근무조건 설정 (Issue #3)

- `WageApplication`: 앱 범위의 Database/Repository 한 개를 재사용한다.
- `MainActivity`: Factory로 ViewModel을 얻고 lifecycle에 맞춰 StateFlow를 구독한다.
- `WorkProfileSetupViewModel`: 로드, 폼 입력, 검증, 저장, 수정, 오류/재시도를 담당한다.
- `WorkProfileSetupScreen`: 상태와 callback만 받아 Material 3 한국어 화면을 그린다.
- `WorkProfileStore`: 기존 Repository의 프로필 조회/저장 두 메서드만 분리한 테스트 경계다.

상태는 `Loading → Form(최초 설정) / Ready(저장된 프로필)`이다. Ready에서 설정 수정 시
기존 값이 채워진 Form으로 이동한다. Form의 `isSaving` 동안 입력·중복 제출·취소를 막는다.
입력 오류는 해당 필드 가까이 표시하고 저장 실패는 입력을 유지한 Form에서 재시도한다.
불러오기 실패는 `LoadError`에서 재시도한다. 수정 취소/뒤로 가기는 저장 전 Ready로 돌아간다.
폼은 화면 회전 시 ViewModel에서 유지되고, 프로세스 재시작 시 Room의 저장값을 다시 읽는다.
저장하지 않은 입력의 프로세스 종료 후 복원은 이번 범위에 포함하지 않는다.

시급은 공백을 제거한 일반 십진수 문자열을 `BigDecimal`로 직접 변환한다. 소수도 보존한다.
0원 초과, 1,000,000원 이하이며 최저임금 미만도 허용한다. 상한은 비정상 입력 방어용 UI
정책으로 법정 기준이 아니다. 숫자·소수점 외 문자는 오류이며 입력은 파싱 전 64자로 제한한다.
5인 이상 여부는 최초에 미선택(null)으로 시작하며 두 선택지 중 하나를 명시적으로 골라야 한다.
별명은 선택이며 양끝 공백 제거 후 빈 문자열로 저장한다. 입력 방어 상한은 80자다.

최초 저장은 `WorkCondition` 기본 가산비율로 새 WorkProfile을 만들고 현재 프로필로 선택한다.
수정은 기존 id/createdAt/가산비율을 유지하고 시급·사업장 조건·별명만 수정한다. 시간은 주입한
Clock에서 한 번 읽는다. updatedAt은 현재시각으로 갱신하되 기기시계가 뒤로 움직이면 기존
updatedAt 이상으로 유지해 저장 계층 정책을 지킨다. 편집 숫자의 불필요한 0은 표시에서만
제거하고 시급 수치가 같으면 원래 BigDecimal의 scale까지 유지한다.

WorkProfile은 Room에만 저장하며 DataStore를 추가하지 않는다. 과거 Shift Snapshot,
Room schema version 1 및 기존 급여 계산 코드는 변경하지 않는다. 출퇴근과 카운터는 Issue #4 범위다.

## 로컬 검증

`gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest` 성공.
JVM 75개(기존 53개 + ViewModel/표시 22개)가 모두 통과했다.
기존 Issue #12의 connected task 실행 0개 문제를 피하기 위해 APK를 직접 설치하고
`adb shell am instrument -w com.orchid.wagelivetracker.test/androidx.test.runner.AndroidJUnitRunner`를
호출했다. 실제 Android 33개(기존 23개 + Compose 9개 + 앱/Room 통합 1개) 통과를 확인했다.
통합 테스트는 폼 검증, 저장, Activity 재시작, 기존 ID와 createdAt 유지, 수정 후 재조회까지 확인한다.

Pixel 8 / API 35 에뮬레이터에서 별도로 앱 데이터를 비우고 실제 화면에 `12000`, `Cafe`,
5인 이상을 입력해 저장했다. `am force-stop` 후 COLD 실행에서 완료 화면과 `₩12,000`을
확인했다. 설정 수정에서 `15000`, 5인 미만으로 바꾼 뒤 다시 force-stop/COLD 실행하여
`₩15,000`, Cafe, 5인 미만 표시가 유지됨을 UI hierarchy와 화면 캡처로 확인했다.
