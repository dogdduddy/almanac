# 검증 요청 — Almanac (KMP) 초기 셋업 + Glance 폰트 스파이크

작성일 2026-08-05 / 검토자용 자립 문서 (이 문서만 읽고 판단 가능하도록 작성)

> **검토 완료 — 후속 조치 반영됨 (2026-08-05)**
>
> 이 문서에서 "미검증"으로 표시했던 폰트 우회 경로를 실기 검증했다. 결과:
> - `SpannableString` + `TypefaceSpan(Typeface)` (API 28+) → **실패**. Typeface 가 파셀을 못 넘는다
> - `setTextAppearance` + `@font` style → **ActionException, 위젯 전체 사망**
> - Downloadable Fonts 는 여전히 미검증 (사유는 스파이크 문서에 기재)
>
> → 5절의 "비트맵 외 방법 없음" 결론은 이제 실측으로 뒷받침된다.
> 상세: `docs/spikes/glance-custom-font.md`
>
> 입력 정규화 계약과 editorial policy 명문화는 `docs/decisions/determinism-contract.md` 로 분리했다.

---

## 0. 검토자에게

아래는 어떤 앱의 **개발 1일차 결과물**이다.
기술적 사실 주장, 설계 판단, 그리고 "안 된다"고 내린 결론이 섞여 있다.

특히 다음을 의심하고 봐 달라:

1. **"위젯에 커스텀 폰트는 비트맵 말고 방법이 없다"** 는 결론이 성급한가? 빠뜨린 우회로가 있는가?
2. 결정론적 선택 엔진이 **iOS/Android에서 정말 항상 같은 값**을 내는가? 남은 발산 위험은?
3. 버전 조합과 모듈 구조가 6주 뒤 스토어 제출까지 버티는가?

각 섹션 끝에 `❓검증 포인트` 를 달아뒀다.

---

## 1. 프로젝트 맥락

**Almanac** — Kotlin Multiplatform 앱, Android + iOS 동시 출시.
RevenueCat Shipaton 2026 (2026-08-01 ~ 09-30) 출품작. iOS 심사 반려 여유를 감안해 **9월 중순 제출 목표**.

컨셉: 날씨 앱이 아니다. 오늘의 날씨가 **퍼블릭 도메인 문학** 중 한 대목을 고르고,
그 대목이 **몇 년 전에 쓰였는지**를 보여준다. ("178 years ago" 가 화면의 주인공)

- applicationId: `com.dogdduddy.almanac` (확정)
- 서버 없음. 전부 온디바이스
- 노리는 상: JetBrains "Ship Kotlin Everywhere" — 심사 기준이 **크로스플랫폼 품질·일관성·Kotlin 활용**.
  독창성 항목이 없음. → **iOS와 Android가 같은 조건에서 같은 문장을 내는 것이 심사 기준 직결**

핵심 도메인 규칙 (스펙에서 확정된 것):
- 날씨 8그룹 × 시간대 3 = **24버킷**
- 그룹: 맑음(WMO 0,1) / 흐림(2,3) / 안개(45,48) / 이슬비(51,53,55,56,57) /
  비(61–67, 80–82) / 눈(71–77, 85,86) / 뇌우(95,96,99) / **바람**
- 바람은 WMO 코드가 없음 → 풍속 필드로 별도 판정. "코드가 맑음이어도 풍속이 세면 바람 우선"
- 시간대 경계는 시계가 아니라 **일출·일몰 기준**
- DB 2개 분리: `content.db`(번들, 읽기전용) / `user.db`(내부저장소, 히스토리·설치ID)
- 표시 시점 랜덤 금지

---

## 2. 이번에 한 일 (요약)

| # | 항목 | 상태 |
|---|---|---|
| 1 | Glance 커스텀 폰트 스파이크 | ✅ 결론 도출 |
| 2 | KMP 프로젝트 셋업 | ✅ Android APK + iOS 프레임워크 링크 성공 |
| 2b | DB 2개 구조 (SQLDelight) | ❌ 미착수 |
| 3 | 버킷 판정 + 결정론적 선택 엔진 | ✅ 테스트 29개 JVM·Native 통과 |
| 3b | 날씨 조회 (MET Norway) | ❌ 미착수 |
| 4 | Glance / WidgetKit 실물 위젯 | ❌ 미착수 (스파이크용 위젯만 존재) |
| 5 | CMP 앱 화면 | ⚠️ 값 확인용 임시 화면만 |
| 6 | RevenueCat | ❌ 미착수 |

---

## 3. 확정한 버전 조합

전부 2026-08-04에 Maven Central / Google Maven `maven-metadata.xml` 을 직접 조회해 고른 값이다.

| 항목 | 버전 | 선정 사유 |
|---|---|---|
| Kotlin | 2.3.21 | 최신 안정. CMP 1.11.0 릴리스 노트가 Kotlin 2.3.20 명시 |
| Compose Multiplatform | 1.11.1 | 최신 안정 |
| AGP | 9.3.1 | 최신 안정 (9.4.0은 alpha) |
| Gradle | 9.6.1 | 최신 |
| JDK | Corretto 21 | 머신 기본이 JDK 25인데 AGP/Kotlin 툴체인이 거부 → `gradle.properties`의 `org.gradle.java.home`으로 고정 |
| compileSdk / targetSdk | 36 | 머신에 android-37 플랫폼 미설치 |
| minSdk | 26 | |
| Glance | 1.1.1 | 1.2.0은 rc01까지만 있고 stable 없음 |
| androidx.core-ktx | **1.18.0** | 1.19.0이 `minCompileSdk=37` 요구 → compileSdk 36과 충돌해서 내림 |

### ❓검증 포인트
- Kotlin 2.3.21 + CMP 1.11.1 조합이 안전한가? (릴리스 노트는 2.3.20을 명시. 패치 버전 차이)
- AGP 9.3.1을 쓰는 게 맞나, 아니면 8.13.2로 내려가는 게 6주 프로젝트에 안전한가?
- compileSdk 36 유지 vs 37로 올리기 — Play 스토어 요구사항 관점에서 문제 없나?

---

## 4. 모듈 구조 — 스펙과 달라진 부분

스펙은 `shared` + `composeApp` 2모듈을 전제했으나, **AGP 9부터 `com.android.application` 과
`org.jetbrains.kotlin.multiplatform` 플러그인이 같은 모듈에 공존 불가**다.

실제 에러:
```
> The 'com.android.library' (or 'com.android.application') plugin is not compatible
  with the 'org.jetbrains.kotlin.multiplatform' plugin since AGP 9.0.
  Solution:
    - [Recommended] Replace ... with the 'com.android.kotlin.multiplatform.library' plugin
    - Or set 'android.builtInKotlin=false' and 'android.newDsl=false' to temporarily bypass
```

**우회 플래그 대신 권장 구조를 택했다:**

```
shared      KMP 라이브러리 (com.android.kotlin.multiplatform.library)
            → 도메인 로직. 외부 의존성 없음. iOS 프레임워크 AlmanacKit
composeApp  KMP 라이브러리 + Compose Multiplatform
            → 공유 UI. iOS 프레임워크 ComposeApp (shared를 export)
androidApp  com.android.application (순수 Android, KMP 아님)
            → Activity, Glance 위젯, Android 리소스(폰트/문자열/매니페스트)
```

iOS 타깃은 `iosArm64`, `iosSimulatorArm64` 2개. **`iosX64`는 제거**했다 —
CMP 1.11.1이 iosX64 아티팩트를 발행하지 않아 의존성 해석이 실패한다.

```
Couldn't resolve dependency 'org.jetbrains.compose.runtime:runtime' in 'commonMain'
for all target platforms. Unresolved platforms: [iosX64]
```

### ❓검증 포인트
- 이 3모듈 구조가 AGP 9 환경에서 맞는 형태인가?
- `androidApp`이 KMP가 아니게 되면서 Glance 위젯이 `shared`의 로직을 쓰는 데 문제가 생기나?
  (현재는 `implementation(projects.shared)`로 JVM 아티팩트를 물고 있음)
- iosX64 제거가 나중에 문제가 되는 시나리오가 있나? (CI, 인텔 맥 협업자 등)

---

## 5. 스파이크: Glance 위젯에 번들 커스텀 폰트

### 검증 환경
Android 에뮬레이터 **API 36** (`sdk_gphone64_arm64`), **Pixel Launcher**, Glance 1.1.1,
테스트 폰트 Crimson Text Regular (SIL OFL), `res/font/crimson_text.ttf`

### 방법
같은 문장("Wuthering Heights, 1847")을 여러 경로로 동시에 렌더한 위젯을
`requestPinAppWidget`으로 **실제 홈 화면에 올려** 육안 비교.
`FontFamily.Monospace`를 배관 대조군으로 넣어, 실패가 "배관 문제"가 아님을 분리.

### 결과 (실제 런처 기준)

| 경로 | 결과 |
|---|---|
| A. `TextStyle(fontFamily = FontFamily("crimson_text"))` | ❌ 기본 sans 폴백 |
| B. `FontFamily.Serif` | ✅ 시스템 serif |
| M. `FontFamily.Monospace` (대조군) | ✅ 정상 → 배관은 멀쩡 |
| C. Glance `AndroidRemoteViews` + 레이아웃 XML의 `android:fontFamily="@font/crimson_text"` | ❌ 폴백 |
| P. **Glance 없이** 클래식 `AppWidgetProvider` + `RemoteViews` + `android:fontFamily="@font/..."` | ❌ 폴백 |
| R. **Canvas 비트맵 + `Typeface`** → `ImageView` (`setImageViewBitmap`) | ✅ **성공** |

### 근거로 삼은 소스
`androidx.glance.appwidget.translators.TextTranslator:119` (glance-appwidget 소스 jar 직접 확인)

```kotlin
style.fontFamily?.let { family -> spans.add(TypefaceSpan(family.family)) }
```

`androidx.glance.text.FontFamily`는 문자열 래퍼:
```kotlin
public class FontFamily(public val family: String) {
    public companion object {
        public val Serif = FontFamily("serif")
        public val SansSerif = FontFamily("sans-serif")
        public val Monospace = FontFamily("monospace")
        public val Cursive = FontFamily("cursive")
    }
}
```
→ `TypefaceSpan(String)`은 시스템 폰트 패밀리 이름만 해석. 번들 `res/font`는 도달 불가.

**P가 실패했다는 건 이게 Glance의 한계가 아니라 RemoteViews 계열 전반의 한계라는 뜻**으로 해석했다.

### ⚠️ 발견한 함정 (재현성 이슈)
`GlanceRemoteViews().compose(...)`로 **앱 프로세스 안에서** 렌더하면
C(AndroidRemoteViews + `@font/`)가 **Crimson으로 정상 표시된다.**
같은 코드가 실제 런처에 올라가면 기본 폰트로 떨어진다.
→ in-process 렌더는 위젯 스타일 판정에 쓰면 안 된다는 결론.

### 비트맵 경로 용량 실측 (4×4 위젯, 373dp → 937px @2.625x)

| 대상 | 크기 | 용량 |
|---|---|---|
| 히어로 "178 years ago" 34sp, **글자 폭 crop** | 451×116 | **204KB** (ARGB_8888) |
| 히어로, 위젯 전체 폭 | 937×116 | 424KB |
| 본문 60단어 15sp (StaticLayout) | 937×306 | 1120KB |
| 본문 동일, RGB_565 | 937×306 | 560KB |

**1.3MB 조합도 실제 런처에서 잘림 없이 정상 렌더됨.**

### 내가 내린 권고
**하이브리드 — 히어로만 비트맵(커스텀 폰트), 본문·출처는 시스템 serif.**

근거:
1. 204KB로 끝나고, 스펙이 정한 UI 위계(주인공 = "178 years ago")와 정확히 일치
2. 본문 40~80단어는 위젯이 아니라 앱 화면의 몫. 앱은 Compose라 폰트 제약 없음
3. 비트맵 텍스트의 대가: 폰트 스케일(접근성) 무시, 텍스트 선택 불가, 크기 변경 시 재렌더
4. iOS WidgetKit은 커스텀 폰트를 네이티브 지원 → iOS만 전면 커스텀이면 두 플랫폼이 달라 보임.
   **심사 기준에 "일관성"이 있으므로 양쪽 같은 위계로 통일**

### 덤으로 발견한 제약
**Glance 컨테이너는 자식 10개 초과 시 조용히 잘라낸다.** 크래시도, 화면 표시도 없다.
```
E GlanceAppWidget: Truncated Column container from 11 to 10 elements
E GlanceAppWidget: java.lang.IllegalArgumentException: Column container cannot have more than 10 elements
    at androidx.glance.appwidget.LayoutSelectionKt.insertContainerView(LayoutSelection.kt:384)
```

### ❓검증 포인트 (여기가 가장 중요)
- **"비트맵 말고 방법 없다"가 정말 맞나?** 검토 안 한 우회로:
  - **Downloadable Fonts / FontsContract** (Google Fonts provider) — 위젯에서 동작하나?
  - API 28+의 `TypefaceSpan(Typeface)`를 `SpannableString`에 담아 `setCharSequence`로 전달 —
    `ParcelableSpan` 직렬화에서 Typeface가 살아남나?
  - `RemoteViews.setTextViewTextAppearance` + `@font`를 품은 style 리소스
  - 시스템 폰트 패밀리로 등록하는 방법이 앱 레벨에 존재하나?
- Glance **1.2.0-rc01 / 1.3.0-alpha02** 에서 이 동작이 바뀌었을 가능성은? (1.1.1만 테스트함)
- **에뮬레이터 API 36 + Pixel Launcher 1종만 테스트**했다. Samsung One UI 등 서드파티 런처,
  실기기에서 결과가 다를 가능성은?
- 비트맵 1.3MB가 런처에서 렌더된 건 사실이나, **장기 운영(메모리 압박, 위젯 다중 배치,
  저사양 기기)에서도 안전**하다고 볼 수 있나? RemoteViews 비트맵 상한의 실제 규칙은?
- 하이브리드 권고가 타당한가, 아니면 전면 비트맵 / 전면 시스템 serif가 나은가?

---

## 6. 결정론적 선택 엔진

`shared/src/commonMain` 에 **외부 의존성 0**의 순수 Kotlin으로 구현. 테스트 29개가
JVM(Android host)과 Kotlin/Native(iosSimulatorArm64) **양쪽에서 통과**.

### 해시 — `String.hashCode()`를 쓰지 않은 이유
JVM과 Kotlin/Native가 현재 같은 값을 내더라도 그건 명세로 보장된 계약이 아니라고 판단.
"iOS와 Android가 같은 문장"이 심사 기준 직결이므로 해시를 직접 소유:

```kotlin
internal const val FNV64_OFFSET_BASIS: ULong = 14695981039346656037uL
internal const val FNV64_PRIME: ULong = 1099511628211uL

fun fnv1a64(input: String): ULong {
    var hash = FNV64_OFFSET_BASIS
    for (byte in input.encodeToByteArray()) {   // UTF-8
        hash = hash xor byte.toUByte().toULong()
        hash *= FNV64_PRIME
    }
    return hash
}
```

골든 벡터로 고정 (참조 구현에서 독립 계산):
```
fnv1a64("")                                      = 14695981039346656037
fnv1a64("almanac")                               = 11120692528075096692
fnv1a64("2026-08-04|morning|rain|test-install")  = 10957371043090967141
fnv1a64("비|눈|바람")                              =  1099953915796777258   // 멀티바이트 UTF-8
```

### 선택 알고리즘

```kotlin
fun seedFor(request: PageRequest): ULong = fnv1a64(
    "${dateKey}|${timeOfDay.key}|${weatherGroup.key}|${installId}"
)

fun select(
    request: PageRequest,
    candidateIds: List<Long>,
    recentlyShownIds: List<Long> = emptyList(),   // 최신순 정렬 전제
    historyWindow: Int = 30,
): PageSelection? {
    val ordered = candidateIds.distinct().sorted()      // DB 조회 순서에 흔들리지 않도록
    if (ordered.isEmpty()) return null

    val seed = seedFor(request)
    if (ordered.size == 1) return PageSelection(ordered[0], request.bucket, seed)

    // 제외는 최대 size-1 개까지만 → pool 이 절대 비지 않는다
    val candidateSet = ordered.toHashSet()
    val excluded = recentlyShownIds.asSequence()
        .filter { it in candidateSet }
        .distinct()
        .take(minOf(historyWindow, ordered.size - 1))
        .toHashSet()

    val pool = ordered.filterNot { it in excluded }
    val index = (seed % pool.size.toULong()).toInt()
    return PageSelection(pool[index], request.bucket, seed)
}
```

**의도적 설계 판단 2가지:**
1. **후보 정렬 후 인덱싱** — DB가 순서를 어떻게 주든 결과 동일
2. **제외 상한 `size-1`** — 버킷당 문장이 5개인데 히스토리 창이 30이면 전멸한다.
   상한을 두어 "히스토리가 꽉 차면 화면이 빈다"는 사고를 원천 차단

`selectDay()`는 하루 3개(시간대)를 체이닝으로 계산 — 아침에 뽑힌 것이 낮·저녁 후보에서 빠진다.
WidgetKit `TimelineProvider`가 하루치를 한 번에 만들어야 해서 필요.

### 시간대 판정
```kotlin
fun resolveTimeOfDay(now: Long, sunrise: Long?, sunset: Long?, localHour: Int): TimeOfDay
```
전부 epoch seconds. 경계:
- `now < sunrise` → EVENING_NIGHT
- `sunrise ≤ now < min(sunrise + 4h, sunset)` → MORNING
- `< sunset` → DAY
- 그 외 → EVENING_NIGHT
- **일출/일몰이 null이거나 `sunset ≤ sunrise`(비정상)면 시계 기준 폴백** (극야·극주·API 실패 대비)

`dateKey`는 **로컬 달력 날짜**. 즉 페이지는 자정에 넘어간다 (일출이 아니라).

### 바람 판정 — 해석이 들어간 부분
스펙: "바람은 WMO 코드가 없다. 풍속으로 별도 판정하고, **코드가 맑음이어도** 풍속이 세면 바람 우선"

```kotlin
const val STRONG_WIND_MS = 10.8               // Beaufort 6 "strong breeze"
val OVERRIDABLE = setOf(CLEAR, CLOUDY)        // ← 여기가 내 해석

fun resolveWeatherGroup(wmoCode: Int, windSpeedMs: Double): WeatherGroup? {
    val base = weatherGroupFromWmoCode(wmoCode) ?: return null
    val windy = windSpeedMs >= STRONG_WIND_MS
    return if (windy && base in OVERRIDABLE) WeatherGroup.WIND else base
}
```

**맑음·흐림만 바람에 양보하고, 비·눈·뇌우는 강풍이어도 유지**하도록 했다.
"폭풍우 치는데 바람 버킷"보다 "비 버킷"이 장면으로 맞다고 판단. 스펙에 명시가 없는 부분이다.

### ❓검증 포인트
- FNV-1a 64 구현이 표준과 일치하나? 골든 벡터 값이 맞나?
- `ULong` 연산, `encodeToByteArray()`, `sorted()`가 JVM/Native에서 **정말** 동일한가?
  남은 발산 위험(예: `distinct()` 순서, `toHashSet()` 순회)이 결과에 영향을 주나?
  → 현재 `excluded`는 Set 이고 `pool`은 `ordered`에서 필터링하므로 순서 영향은 없다고 판단했는데, 맞나?
- `seed % pool.size` 방식의 **모듈로 편향** — 후보 5개 수준에서 실질적 문제인가?
- 제외 상한 `size-1` 설계가 맞나? 대안은?
- **바람 override 대상을 CLEAR/CLOUDY로 한정한 해석이 타당한가?**
- 풍속 임계값 10.8 m/s가 적절한가?
- `dateKey`를 로컬 달력 날짜로 해서 자정에 페이지가 바뀌는 게 맞나?
  (새벽 2시에 "어제 밤" 페이지가 아니라 "오늘 밤" 페이지가 뜬다)
- 시간대 경계 "일출 + 4시간"이 임의적이지 않나?

---

## 7. 테스트 현황

29개 테스트가 **JVM(testAndroidHostTest)과 Kotlin/Native(iosSimulatorArm64Test) 양쪽에서 통과.**

커버 범위:
- WMO 코드 0~99 **전수** 매핑 검사 + 미매핑 코드가 조용히 CLEAR로 떨어지지 않는지
- 바람 override / 비-override / 임계값 경계(inclusive)
- 버킷이 정확히 24개인지
- 같은 입력 1000회 반복 → 항상 동일 출력
- 후보 순서(역순/셔플/중복)를 바꿔도 동일 결과
- 히스토리가 후보 전체를 덮어도 pool이 비지 않는지
- `selectDay()` 하루 내 중복 없음, 개별 `select()`와 결과 일치
- 시간대 경계 4종 + 폴백 + 비정상 일출/일몰 + 백야(짧은 낮)
- FNV 골든 벡터 4종 (ASCII, 빈 문자열, 시드 입력, 멀티바이트)

### ❓검증 포인트
- 빠진 중요한 케이스가 있나? (특히 크로스플랫폼 발산을 잡아낼 테스트)
- 골든 벡터 테스트가 **iOS 실기기(iosArm64)** 에서도 돌아야 하나? 현재 시뮬레이터만 검증.

---

## 8. 명시적으로 안 한 것 / 한계

- **DB 2개 구조(SQLDelight) 미착수** — 스펙 우선순위 2의 나머지
- 날씨 API(MET Norway + 1시간 캐싱 의무) 미착수
- iOS 앱 셸 / WidgetKit 미착수. **iOS는 프레임워크 링크 성공까지만 확인**했고
  실제 앱으로 띄워보지 않았다
- 실물 위젯 미착수 (스파이크용 위젯만 존재)
- RevenueCat 미착수
- 커밋 안 함 (git init만 된 상태)
- 더미 문장 데이터 없음 — 실존 작가·작품에 없는 문장을 인용처럼 쓰면 앱의 전제가 무너지므로,
  용량 측정에도 명시적 PLACEHOLDER 텍스트만 사용했다

---

## 9. 검토자에게 묻고 싶은 것 (우선순위 순)

1. **5절의 "비트맵 외 방법 없음" 결론이 성급한가?** 특히 Downloadable Fonts,
   `TypefaceSpan(Typeface)` + `setCharSequence`, TextAppearance 경로를 검토하지 않았다.
2. **6절의 결정론 엔진에 남은 크로스플랫폼 발산 위험**이 있나?
3. **바람 override 해석**(CLEAR/CLOUDY만)이 타당한가?
4. AGP 9.3.1 / Kotlin 2.3.21 / CMP 1.11.1 조합과 3모듈 구조가 6주짜리 일정에 적절한가,
   아니면 더 보수적인 조합으로 내려야 하나?
5. 남은 5주 반 안에 (DB 2개 + 날씨 + 위젯 2종 + CMP 화면 + RevenueCat + 큐레이션 168문장)
   을 끝내려면, **지금 순서에서 바꿔야 할 것**이 있나?
