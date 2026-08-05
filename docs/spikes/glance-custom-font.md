# 스파이크: Glance 위젯에 번들 커스텀 폰트를 적용할 수 있는가

- 일자: 2026-08-05
- 검증 환경: Android 에뮬레이터 API 36 (`sdk_gphone64_arm64`), Pixel Launcher, Glance 1.1.1
- 테스트 폰트: Crimson Text Regular (SIL OFL) — `res/font/crimson_text.ttf`

## 질문

번들 `.ttf` 를 Glance 위젯의 **텍스트**에 적용할 수 있는가.
불가능하면 Canvas 비트맵 우회가 실무적으로 감당 가능한가.

## 결론 (요약)

| 경로 | 실제 런처에서 결과 |
|---|---|
| A. `TextStyle(fontFamily = FontFamily("crimson_text"))` | ❌ 기본 sans 로 폴백 |
| B. `FontFamily.Serif` | ✅ 시스템 serif (Noto Serif) |
| M. `FontFamily.Monospace` (배관 대조군) | ✅ 정상 — 배관은 멀쩡하다 |
| C. `AndroidRemoteViews` + 레이아웃 XML 의 `android:fontFamily="@font/..."` | ❌ 기본 sans 로 폴백 |
| P. Glance 없이 **클래식** `RemoteViews` + `android:fontFamily="@font/..."` | ❌ 기본 sans 로 폴백 |
| S. `SpannableString` + **`TypefaceSpan(Typeface)`** (API 28+) → `setTextViewText` | ❌ 기본 sans 로 폴백 |
| T. `setInt(id, "setTextAppearance", styleRes)` + `@font` 품은 style | 💥 **ActionException — 위젯 전체 사망** |
| R. **Canvas 비트맵 + `Typeface`** → `ImageView` | ✅ **유일하게 성공** |

**위젯 텍스트에 커스텀 폰트를 넣는 방법은 비트맵 렌더링뿐이다.**
이건 Glance 의 한계가 아니라 RemoteViews 자체의 한계다 — 클래식 위젯에서도 똑같이 실패한다.

> S·T 는 외부 검토에서 "아직 검증 안 된 경로"로 지적받아 2026-08-05 에 추가 검증한 것이다.
> 상세는 아래 [S·T 추가 검증](#st-추가-검증-2026-08-05) 참고.

## 왜 실패하는가

`androidx.glance.appwidget.translators.TextTranslator:119`

```kotlin
style.fontFamily?.let { family -> spans.add(TypefaceSpan(family.family)) }
```

`FontFamily` 는 문자열 래퍼일 뿐이고(`FontFamily(val family: String)`),
`TypefaceSpan(String)` 은 **시스템 폰트 패밀리 이름**만 해석한다.
앱에 번들된 `res/font` 리소스는 이 경로에 도달할 방법이 없다.

클래식 RemoteViews 의 `android:fontFamily="@font/..."` 가 안 먹는 것도 같은 계열의 문제다.
위젯 호스트(런처) 프로세스에서 인플레이트될 때 폰트 리소스가 해석되지 않는다.

## ⚠️ 함정: in-process 렌더링은 거짓말을 한다

`GlanceRemoteViews().compose(...)` 로 **앱 프로세스 안에서** 렌더하면
C(AndroidRemoteViews + `@font/`)가 **Crimson 으로 정상 표시된다.**
같은 코드가 실제 런처에 올라가면 기본 폰트로 떨어진다.

→ `evidence/00-inprocess-render-misleading.png` (C 가 Crimson 으로 보임)
→ `evidence/01-glance-widget-launcher.png` (같은 C 가 sans 로 떨어짐)

**위젯 검증은 반드시 실제 홈 화면에 올려서 할 것.** in-process 렌더는 스타일 판정에 쓰면 안 된다.

## S·T 추가 검증 (2026-08-05)

외부 검토에서 "`TypefaceSpan(Typeface)` 실기 스파이크가 빠져 있어
'비트맵 말고 방법 없음'을 확정 문장으로 쓰기엔 이르다"는 지적을 받아 추가 검증했다.
`evidence/05-typefacespan-textappearance-launcher.png`

### S. `SpannableString` + `TypefaceSpan(Typeface)` → ❌

```kotlin
val typeface = ResourcesCompat.getFont(context, R.font.crimson_text)   // 진단: loaded (non-null)
val spanned = SpannableString(sample).apply {
    setSpan(TypefaceSpan(typeface), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
views.setTextViewText(R.id.text_span, spanned)
```

앱 프로세스에서 `Typeface` 는 정상 로드됐고(위젯에 `typeface=loaded` 로 표시) span 도 적용됐다.
그런데 **런처에 도착한 렌더는 기본 폰트(Q)와 픽셀 단위로 동일**하다.

`TypefaceSpan` 은 `ParcelableSpan` 이고, 파셀에 실려 건너가는 것은 **family 문자열**뿐이다.
`TypefaceSpan(Typeface)` 로 만들면 family 가 null 이므로 건너편에는 아무 정보도 남지 않는다.
결국 A·C·P 와 같은 벽이다.

### T. `setTextAppearance` + `@font` 품은 style → 💥 위젯 전체 사망

```kotlin
views.setInt(R.id.text_appearance, "setTextAppearance", R.style.TextAppearance_Almanac_Crimson)
```

```
W AppWidgetHostView: android.widget.RemoteViews$ActionException:
    view: android.widget.TextView can't use method with RemoteViews: setTextAppearance(int)
      at android.widget.RemoteViews.getMethod(RemoteViews.java:2207)
```

`TextView.setTextAppearance(int)` 은 `@RemotableViewMethod` 가 아니다.
더 나쁜 건 **이 예외 하나가 위젯 전체를 죽인다**는 것 — 홈 화면에 `Can't load widget` 만 남는다.
부분 실패로 끝나지 않으므로 실서비스에서 절대 시도하면 안 된다.

### 아직 안 해본 것 하나 — Downloadable Fonts

`FontsContract` / Google Fonts provider 경로는 여전히 미검증이다.
쓰려는 폰트(Crimson Text, EB Garamond)가 마침 Google Fonts 라 이론상 적용 대상이긴 하다.

다만 우선순위를 낮게 뒀다:
- Downloadable font 도 결국 `res/font` XML 리소스로 선언해 **P 와 같은 해석 경로**를 탄다. P 가 실패했다
- 위젯 첫 렌더에 네트워크 의존이 생긴다 — 위젯 성격상 나쁜 트레이드오프
- 폰트 선택이 Google Fonts provider 카탈로그로 묶인다

**결론을 뒤집을 만한 근거가 나오면 그때 검증한다.** 현재 권고는 이것 없이도 충분히 선다.

## 비트맵 경로의 용량 예산 (실측)

4×4 위젯, 373dp 폭 → 937px @2.625x 기준:

| 대상 | 크기 | 용량 |
|---|---|---|
| 히어로 "178 years ago" 34sp, **글자 폭으로 crop** | 451×116 | **204KB** (ARGB_8888) |
| 히어로, 위젯 전체 폭 사용 | 937×116 | 424KB |
| 본문 60단어 15sp (StaticLayout 줄바꿈) | 937×306 | 1120KB |
| 본문 동일, RGB_565 | 937×306 | 560KB |

**전부 실제 런처에서 정상 렌더됐다.** 1.3MB 조합도 잘림 없이 표시됨.
`evidence/03-bitmap-render-launcher.png`, `evidence/04-bitmap-size-report.png`

crop 여부가 2배 차이를 만든다. 히어로처럼 짧은 문구는 반드시
`layout.getLineWidth()` 로 실제 글자 폭을 재서 잘라낼 것.

## 권고

**하이브리드: 히어로만 비트맵, 나머지는 시스템 serif.**

근거:

1. **스펙이 정한 위계와 정확히 맞는다.** 화면의 주인공은 "178 years ago" 다.
   폰트 개성이 값을 하는 지점이 거기고, 거기만 비트맵이면 **204KB** 로 끝난다.
2. **본문 40~80단어는 어차피 위젯의 몫이 아니다.** 위젯은 히어로 + 짧은 한 줄 + 출처면 충분하고,
   본문 인용문은 앱 화면에서 보여준다. 앱은 Compose 라 커스텀 폰트에 아무 제약이 없다.
3. **비트맵 텍스트는 대가가 있다.** 폰트 스케일(접근성 큰 글씨) 무시, 텍스트 선택 불가,
   위젯 크기 바뀔 때마다 재렌더. `contentDescription` 으로 TalkBack 은 살릴 수 있지만
   본문 전체를 이미지로 만들면 손해가 커진다.
4. **크로스플랫폼 일관성** — iOS WidgetKit 은 커스텀 폰트를 네이티브로 지원하므로
   iOS 만 전부 커스텀 폰트를 쓰면 두 플랫폼 위젯이 달라 보인다.
   JetBrains 심사 기준에 "일관성"이 명시돼 있으므로, **양쪽 다 같은 위계**로 가는 게 유리하다.

즉 4개 표면 전부 시스템 serif 로 통일할 필요는 없다. 히어로만 살리면 된다.

전면 커스텀 폰트도 기술적으로는 검증됐으니(1.3MB, 정상 렌더), 나중에 뒤집을 여지는 있다.

## 덤으로 발견한 제약 — 이게 더 중요할 수 있다

**Glance 의 컨테이너는 자식이 10개를 넘으면 조용히 잘라낸다.**

```
E GlanceAppWidget: Truncated Column container from 11 to 10 elements
E GlanceAppWidget: java.lang.IllegalArgumentException: Column container cannot have more than 10 elements
    at androidx.glance.appwidget.LayoutSelectionKt.insertContainerView(LayoutSelection.kt:384)
```

크래시도 아니고 화면에 아무 표시도 없다. **요소가 그냥 사라진다.**
스파이크에서 실제로 이것 때문에 항목 하나가 통째로 증발했고, 원인을 찾는 데 시간이 걸렸다.

실제 위젯 설계 시 Column/Row 당 자식 10개 상한을 전제로 잡을 것.
넘으면 중첩 컨테이너로 쪼개야 한다.

## 폰트 라이선스

- Crimson Text — SIL OFL 1.1 (`docs/licenses/CrimsonText-OFL.txt`)
- EB Garamond — SIL OFL 1.1 (`docs/licenses/EBGaramond-OFL.txt`)

둘 다 상업적 임베딩 가능. 앱 내 출처 표기 화면에 폰트 라이선스도 함께 넣을 것.
(EB Garamond 는 가변 폰트라 용량 851KB — 정적 서브셋을 만들어 넣는 편이 낫다.)

## 스파이크 코드 위치 (결론 반영 후 삭제 대상)

- `androidApp/src/main/kotlin/com/dogdduddy/almanac/widget/FontSpikeWidget.kt`
- `androidApp/src/main/kotlin/com/dogdduddy/almanac/widget/ClassicFontWidgetProvider.kt`
- `androidApp/src/main/kotlin/com/dogdduddy/almanac/widget/SpikeActivity.kt`
- `androidApp/src/main/res/layout/spike_remote_text.xml`, `classic_font_widget.xml`
- `androidApp/src/main/res/xml/font_spike_widget_info.xml`, `classic_font_widget_info.xml`
- `strings.xml` 의 `spike_sample_text`
- `AndroidManifest.xml` 의 스파이크 receiver/activity 3건
