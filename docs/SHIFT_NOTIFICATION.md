# 근무 지속 알림과 백그라운드 세션 (Issue #6)

## 목적과 FGS 정책

사용자가 직접 출근한 근무 동안 앱을 닫거나 화면을 꺼도 현재 근무/휴게 상태,
경과시간과 예상 급여를 보고 휴게·재개·퇴근을 실행하는 사용자 가시 기능이다.
앱 화면의 계산·저장·복구에는 서비스가 필요하지 않다. 지속 알림과 즉시 실행할
버튼을 제공하기 위해 선택적으로 Foreground Service를 사용한다.

위치, 건강, 미디어 재생, 데이터 동기화 등의 표준 type에는 해당하지 않아
`specialUse`를 선언한다. 이 선택은 출시 시 Google Play 검토 대상이며,
코드·에뮬레이터 테스트가 Play 승인을 보장하지 않는다.

Manifest 권한은 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
Android 13+ `POST_NOTIFICATIONS`다. 서비스는 외부에 export하지 않는다.
`foregroundServiceType="specialUse"`와 아래 property를 선언한다.

```xml
<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    android:value="User-initiated active work shift: show ongoing elapsed time and estimated wages with pause, resume and finish controls until the user ends the shift. Room remains the source of truth; no location tracking or data synchronization." />
```

## 시작·갱신·종료

1. 출근을 Repository에 먼저 저장한다. 저장 성공 후 Activity가 RESUMED일 때만
   `startForegroundService()`를 시도한다. 서비스 시작 실패는 Shift 저장에 영향을 주지 않는다.
2. 서비스는 시작 즉시 짧은 “근무 기록 확인 중” 알림으로 foreground 승격한다.
   API 35에서는 `startForegroundService()` 후 승격 없이 `stopSelf()`만 해도
   `ForegroundServiceDidNotStartInTimeException`이 발생하므로, 비동기 Room 조회 중
   기한을 보장하는 초기 알림이 필요하다. 이후 Room의 진행 Shift를 조회한다.
   없거나 조회 제한시간을 초과하면 알림을 제거하고 즉시 `stopSelf()`한다.
3. 진행 Shift가 있으면 LOW importance의 무음·무진동 `active_shift` 채널과
   `startForeground()`를 보장한다. 알림은 근무지, 근무/휴게 상태, 예상 급여,
   경과시간 chronometer와 휴게/휴게 종료·퇴근 액션을 제공한다.
4. 급여는 **20초 간격**으로 `LiveWageProjection`을 호출해 갱신한다.
   Shift Snapshot·Break를 기존 `WageCalculator`로 계산하며 BigDecimal을 유지한다.
   화면의 1초 ticker와 독립적이다. 알림 갱신은 Room 조회만 수행하며 급여를 DB에 쓰지 않는다.
   근무/휴게가 변경되면 Room Flow 관찰로 즉시 갱신한다.
5. 퇴근은 기존 atomic `finishShift()`로 열린 휴게를 닫고 COMPLETED로 저장한다.
   진행 Shift가 사라지면 알림 제거, `stopForeground()`, `stopSelf()`를 수행한다.
   앱 내 퇴근도 Room 관찰을 통해 동일하게 서비스를 종료한다.

화면 OFF 동안 CPU를 계속 깨우는 WakeLock이나 exact alarm을 사용하지 않는다.
Doze/절전/OEM 정책으로 20초 게시가 늦어질 수 있고, 급여는 다음 갱신에서 현재
시각 기준으로 재계산된다. 전체 경과시간 chronometer는 휴게 중에도 진행한다.
예상 급여는 휴게 중 증가하지 않는다.

## 액션과 동기화

서비스 Mutex로 액션을 직렬화한다. 각 immutable PendingIntent에는 Shift ID와
Shift·Break의 SHA-256 상태 검증값을 넣는다. Intent identity에는 근무 ID·발급 토큰·액션을 넣는다.
Repository transaction 안에서 최신 진행 Shift와 검증값·근무/휴게 상태를 확인한 뒤
기존 `startBreak()` / `endBreak()` / `finishShift()`를 호출한다.
추가로 서비스가 발급한 임시 액션 토큰을 확인하며, 저장 성공마다 토큰을 교체한다.
0초 휴게 종료로 Break가 삭제되어 Room 데이터가 이전과 같아져도 이전 버튼을 거부한다.
이 토큰은 PendingIntent 중복 방어용이며 근무 상태의 Source of Truth가 아니다.
서비스 재생성 전의 오래된 버튼은 무시되고 새 알림의 버튼을 사용한다.
반복 클릭, 이전 휴게 주기의 버튼, 이미 끝난 근무의 버튼은 무시한다.
이 검증과 저장 사이에 앱 UI 저장이 끼어들 수 없으며 실패 시 모두 rollback한다.
표시 내용이 동일한 연속 액션/Room 이벤트는 재게시하지 않는다. 20초 주기 갱신은
동일 내용도 다시 게시하여 시스템이 이전 게시를 제한하거나 알림을 제거한 경우에도 복구한다.

서비스와 `LiveShiftViewModel`은 Repository의 Room Flow를 각각 관찰한다.
singleton의 mutable Shift로 화면과 서비스를 연결하지 않는다. 알림 본문 탭은
MainActivity로 돌아가며 새 ViewModel도 기존 복구 경로로 최신 Room 상태를 표시한다.

## 복구 및 권한 거부

Room이 유일한 Source of Truth다. 별도 service-running flag, 영구 타이머,
급여 누적 금액을 저장하지 않는다. DB schema/version 변경도 없다.

`START_NOT_STICKY`를 선택해 자동 재시작에 의존하지 않는다. 서비스 종료·프로세스 종료·
재부팅 뒤에도 Shift와 열린 Break는 남는다. 사용자가 Activity를 다시 열면 Room을
복구하고 visible 상태에서 서비스를 다시 시작한다. BOOT_COMPLETED 수신이나
임의 background receiver의 자동 시작은 없다. 직접 다시 시작된 서비스도 Room을 먼저 읽는다.
Room 조회 실패/제한시간 초과는 서비스를 종료하고 기록을 보존한다.
알림 액션 저장 실패는 transaction으로 기록을 보존하고 최신 상태 알림을 유지한다.

권한 요청은 근무 화면의 **근무 알림 켜기** 설명을 읽고 사용자가 선택할 때 한다.
미결정/거부/채널 차단은 출근·휴게·퇴근·월간 기록을 막지 않는다.
앱에는 알림이 표시되지 않을 수 있다는 안내를 표시한다. Android 13+에서
POST_NOTIFICATIONS 거부는 FGS 시작 자체의 금지 조건이 아니다. 시스템 Task Manager에는
FGS 표시가 남을 수 있으나 알림 drawer에는 나타나지 않을 수 있다.

## Play Console 제출 설명 초안

아래는 실제 기능에 맞춘 초안이다. 제출 시 Console의 최신 양식과 심사 기준을 확인하고
실제 데모 영상 URL을 넣는다. 아직 제출하거나 승인받은 내용은 아니다.

**Feature / purpose**

Wage Live Tracker lets hourly workers manually start a work shift and see estimated earnings.
The specialUse foreground service provides an ongoing notification only for an active shift
started by the user. It shows working or unpaid-break status, elapsed time, estimated earnings,
and controls to pause, resume and finish the shift while the app is not visible. It does not
track location, record audio, synchronize data, or automatically detect work.

**Why foreground execution is needed / impact of deferral or interruption**

The user expects the current shift and its controls to remain readily available after leaving
the app or turning off the screen. Deferring this feature would make the ongoing notification
and pause/resume/finish controls unavailable when needed; interrupting it removes this visible
session interface and delays earnings updates. Monetary updates run approximately every
20 seconds, while Android's notification chronometer displays elapsed time. Persisted shifts
remain recoverable even if the service is interrupted, and the core app works without it.

**Start / stop / user control**

The service starts only after a manually initiated shift has been saved, while the Activity is
visible, or when the user visibly reopens an existing active shift. The user can end the shift
in the app or notification. Ending the shift stops the service and removes the notification.
Notification permission is requested with a contextual explanation; denying permission does
not prevent work-session storage or recovery. The service does not start at boot or from an
unrelated background receiver and does not rely on automatic restart.

**Demonstration video**

URL: `<제출 시 실제 데모 영상 URL 입력>`

녹화 흐름: 기본 설정 → 출근 → 권한 설명/허용 → 알림 내용과 경과시간 → Home →
화면 OFF/ON → 알림 휴게 → 급여 정지 → 재개 → 급여 증가 → 알림 퇴근 →
알림/서비스 종료 → 앱의 완료 기록. 권한 거부 상태에서도 앱의 근무 저장이 가능한 장면도 담는다.

## specialUse 거절 시 제거 경로

`MainActivity`의 알림 control 호출, `WageApplication`의 선택적 starter/import,
화면의 선택적 알림 안내, notification package,
Manifest 서비스와 알림/FGS 권한 3개를 제거하면 지속 알림을 제외할 수 있다.
출근 저장과 서비스 시작 사이에 transaction이나 필수 의존성은 없다.
출근, 앱 화면의 실시간 계산, 휴게, 퇴근, 기록, Room 복구는 그대로 동작한다.
Repository 추가 API는 atomic action / session observation으로 유지하거나 함께 제거할 수 있다.

## 공식 자료

- [Android foreground service types / specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Google Play foreground service declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821)

## 검증 재현

기본 빌드와 JVM 회귀:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

API 35 에뮬레이터에 앱/테스트 APK를 직접 설치한 다음, 권한 거부 상태로 전체 계측을 실행한다.
테스트 대상 프로세스 안에서 권한을 revoke하면 프로세스가 종료될 수 있으므로 실행 전에 설정한다.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm revoke com.orchid.wagelivetracker android.permission.POST_NOTIFICATIONS
adb shell am instrument -w com.orchid.wagelivetracker.test/androidx.test.runner.AndroidJUnitRunner
```

`DeniedNotificationPermissionTest`는 실제 미허용 상태에서만 실행한다. 이어지는
`ShiftNotificationIntegrationTest`가 권한을 허용하여 양쪽 환경을 검증한다.
Gradle/UTP의 APK 설치는 권한을 자동 허용할 수 있어서 해당 거부 테스트가 skip될 수 있다.
따라서 `connectedDebugAndroidTest`의 성공이나 전체 선언 개수만으로 거부 검증을 대체하지 않는다.
직접 실행 결과의 실제 테스트 개수·실패·skip과 XML 루트 `testsuites`를 함께 확인한다.
이번 환경의 Gradle/UTP는 `AssumptionViolatedException`을 XML failure로 표시하면서
exit code 0을 반환하기도 했다. 이 보고서만으로 모든 테스트 통과를 선언하지 않는다.

자동 검증 범위: 알림 모델·Snapshot·야간/휴게 계산, 서비스 기동/종료, 알림 버튼과
이미 열린 Activity 동기화, 중복/오래된 버튼/0초 휴게, Room rollback,
서비스 중단 후 열린 휴게/시급 Snapshot 복구, 알림 본문 복귀, 화면 OFF,
권한 거부 상태의 출근·휴게·재개·퇴근·재실행, 선택적 서비스 시작 실패.

### 최종 결과 (2026-10-07)

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest`: BUILD SUCCESSFUL.
  JVM XML 실제 **135개**, failures 0, skipped 0 (기존 120 + 알림 모델 15).
- API 35 Pixel_8에 APK 직접 설치, POST_NOTIFICATIONS revoke 후 전체 Runner:
  **`OK (92 tests)`**. 기존 Android 73개 + Repository 5개 + 실제 권한 거부 1개 +
  서비스/알림 통합 13개. 최종 실행은 조건 skip 없이 전부 실행했다.
- Gradle connected 실행은 자동 권한 허용 때문에 거부 테스트의 assumption을
  XML failure 1개로 기록하면서 exit code 0을 반환했다. 최종 판정은 이 결과가 아닌
  위 직접 Runner 결과를 사용했다. 중간 에뮬레이터 시스템 중단은 재부팅 후 재검증했고,
  Activity 복귀/초기 확인 알림의 비동기 전환도 테스트에서 실제 준비 완료까지 기다리도록 했다.
- 실제 사용자 조작: 기본 설정(시급 12,000원/5인 미만/Issue6Cafe) → 출근 →
  권한 설명 및 시스템 허용 → Home → 화면 OFF(Dozing 확인, 30초 이상 유지) →
  화면 ON → 지속 알림 유지와 예상 급여 증가 확인(468원 → 1,937원) →
  알림 휴게(2,162원) → 앱/서비스 `am force-stop` → 앱 재실행 → 열린 휴게 복구,
  유급시간과 2,162원 유지 → 알림 휴게 종료 → 알림 본문으로 앱 복귀,
  증가 재개(2,435원) → 알림 퇴근 → 알림 제거/서비스 `(nothing)` →
  다시 프로세스를 종료하고 재실행 → 월간 완료 기록 3,514원,
  유급시간 00:17:34 / 휴게시간 00:23:07 유지 확인.
- 로컬 화면 증거는 `app/build/issue6-*.png`와 XML에 남겼다 (빌드 산출물로 Git 제외).
- 연결된 실기기는 없어 실기기/OEM 절전 정책 검증은 하지 않았다. Play 승인,
  실제 데모 영상 URL 제출 및 여러 OEM의 장시간 절전 동작 확인은 출시 준비에 남는다.
