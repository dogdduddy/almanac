# 데스크톱 타깃 (JVM) — 세 번째 플랫폼과 결제의 자리

2026-09-16 작성. RevenueCat Shipaton 2026 의 "Ship Kotlin Everywhere" 심사(크로스플랫폼 품질·일관성·Kotlin 활용)를
겨냥해 Windows/macOS/Linux 실행 파일을 더했다.

---

## 1. 무엇이 어디에 있나

```
shared      +jvm()      액추얼 4개: Uuid, DeviceClock, LocationSource(no-op), DatabaseFactory(JDBC)
composeApp  +jvm()      화면은 그대로. 결제 SDK 는 mobileMain(android+ios)으로 내렸다
desktopApp  신규        Main.kt(창 하나) + DesktopAlmanacGraph(조립) + 렌더 스모크 테스트
```

화면(`App`)과 시작 절차(`AppLoader`)는 세 플랫폼이 **같은 코드**다. 플랫폼마다 다른 것은 조립 지점뿐이다.

실행: `./gradlew :desktopApp:run`. 튜닝: `./gradlew :desktopApp:hotRunAsync` 로 띄운 뒤 저장할 때마다
`./gradlew reload` (Compose Hot Reload). 패키징: `:desktopApp:createDistributable` — **jpackage 가 든
전체 JDK 21 이 필요하다.** Android Studio 의 JBR 에는 없다.

## 2. 결제 SDK 를 commonMain 에서 뺀 이유

RevenueCat KMP SDK 는 Android·iOS 아티팩트만 발행한다. `composeApp` 의 commonMain 에 둔 채 `jvm()` 을
붙이면 의존성 해석이 통째로 실패한다 — iosX64 를 뺐던 것과 같은 종류의 문제.

그래서 `mobileMain` 중간 소스셋(androidMain + iosMain 이 상속)을 만들어 SDK, `RevenueCatBilling`,
빌드 때 생성되는 `BillingKeys` 를 전부 그리로 내렸다. commonMain 은 shared 의 `Billing` 인터페이스만 본다.

**주의:** `applyDefaultHierarchyTemplate()` 을 명시적으로 부른 뒤에 `dependsOn` 을 써야 한다.
명시 없이 `dependsOn` 만 쓰면 Kotlin 이 기본 계층 적용을 건너뛰어 `iosMain` 자체가 사라진다.

## 3. 데스크톱에는 결제가 없다 — 의도된 결정

`DesktopAlmanacGraph.billing = NoBilling`. 결과: 자동 지급 팩(starter-2026, 86편)만 보유하고,
상품이 비어 화면이 서가 진입점을 감춘다. About 화면에 한 줄(`AppActions.shelfNote`)로만 알린다.

### 선택지
- **A. 인정한다 (채택).** 데스크톱은 무료 서가를 읽는 창.
- B. 서가 코드 동기화. 모바일이 안정 ID 로 RevenueCat `logIn` 하고 그 ID 를 About 에 보여준다.
  데스크톱은 코드를 입력받아 RevenueCat REST `subscribers` 를 공개 키로 조회해 엔티틀먼트를 읽는다.
- C. Web Billing 으로 데스크톱에서 직접 구매. B 의 공통 ID 가 전제라 그 다음 단계.

### A 를 고른 근거
- **지금도 기기 간 동기화는 없다.** RevenueCat 을 익명 ID 로 쓰므로 Android 구매는 iPhone 에도 오지 않는다.
  데스크톱은 이미 받아들인 "기기마다 별개"에 기기 하나가 더 늘어나는 것뿐이다.
- **문장도 원래 기기마다 다르다.** installId·히스토리·읽음 횟수가 시드 입력이라(결정론 계약),
  엔티틀먼트를 맞춰도 데스크톱과 폰은 다른 문장을 낸다. 동기화로 얻는 것은 후보 풀 크기뿐이다.
- B 는 출시된 모바일 결제 경로(익명 → 커스텀 ID, 기존 설치 별칭 처리)를 재제출 직전에 건드린다.

### 뒤집을 조건
콘테스트 뒤 데스크톱을 실제로 쓰는 유저가 생기면 B. `Billing` 이 인터페이스라 조립 지점만 바꾸면 된다.

## 4. 데이터 디렉터리와 content.db 갱신

- macOS `~/Library/Application Support/Almanac`, Windows `%APPDATA%\Almanac`, Linux `$XDG_DATA_HOME/almanac`
- content.db 는 클래스패스 리소스(`desktopApp/src/main/resources`, `syncContentToDesktop` 이 복사, gitignore)에서
  데이터 디렉터리로 복사한다. 모바일은 앱 버전으로 갱신 여부를 판단하지만 실행 파일에는 그런 버전이 없어
  **번들 바이트의 SHA-256 을 스탬프**로 쓴다.
- user.db 는 JDBC 드라이버가 스키마를 스스로 만들지 않으므로 `PRAGMA user_version` 을 읽어 직접 create/migrate 한다.

## 5. 데스크톱이 결정론 계약에 미치는 영향

없다. `DeviceClock` JVM 구현은 Android 구현과 같은 원칙(오프셋만 플랫폼, 날짜 계산은 공유 코드)이고,
commonTest 68개가 jvm 타깃에서도 통과한다 — 골든 벡터가 이제 JVM(Android host)·Native(iOS)·JVM(desktop) 세 런타임에서 돈다.
측위가 없으므로 위치는 저장된 도시(없으면 기본 도시)다. 모바일에서 권한을 거부한 것과 같은 경로다.

## 6. 알려진 제약

- 페이저를 마우스 드래그로 넘기는 것은 Compose Desktop 기본 동작이 아니다. 트랙패드 가로 스크롤은 된다.
- "Use my location" 링크가 도시 화면에 그대로 보인다. 누르면 저장된 도시로 되돌아갈 뿐이다.
- 위젯 없음. 데스크톱 위젯 개념이 플랫폼마다 달라 범위 밖.
