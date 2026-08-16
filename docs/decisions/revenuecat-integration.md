# RevenueCat 연동 — 남은 작업

2026-08-15. 코드 이음매는 만들었고, **키와 스토어 상품이 없어 SDK 자체는 아직 안 붙였다.**

## 진행 상황 (2026-08-16 갱신)

SDK 를 붙였다. **Test Store 키로 초기화까지 확인**했고, 상품 조회는 아직 빈 목록이다.

## 지금 있는 것

- `billing/Billing.kt` — 결제 백엔드 인터페이스. 구현은 `NoBilling` 하나(키 없을 때용)
- `billing/EntitlementSync.kt` — 결제 상태 → `owned_packs` 동기화
- 테스트 11개 (환불 회수, 오프라인 보존, 무료 팩 보호, 복원 등)

**RevenueCat 이 진실의 원천이고 `user.db.owned_packs` 는 캐시다.**
캐시를 두는 이유는 위젯이다 — 위젯은 네트워크를 기다릴 수 없으므로 로컬 값으로 즉시 그린다.

## 왜 SDK 를 아직 안 붙였나

키와 스토어 상품 없이는 **동작을 검증할 수 없다.** 붙여만 두고 "될 것이다" 라고
남기면, 정작 제출 직전에 터진다. 검증 가능한 부분(엔티틀먼트 로직)을 먼저 못박고
SDK 는 키가 생기는 즉시 붙인다.

## 붙일 때 할 일

의존성 (Maven Central 확인함, 2026-08-15 기준 최신):

```
com.revenuecat.purchases:purchases-kmp-core:3.5.0
```

1. `RevenueCatBilling : Billing` 을 shared 에 구현
   - `Purchases.configure(apiKey)` — 키는 플랫폼별로 다르다 (Android/iOS 각각)
   - `customerInfo.entitlements` → 활성 엔티틀먼트 식별자 → `packId` 매핑
   - **엔티틀먼트 식별자와 packs.id 를 같게 두면** 매핑 테이블이 필요 없다
2. 조립 지점에서 `NoBilling` → `RevenueCatBilling` 교체
   (`AlmanacGraph`, `IosAlmanacGraph`)
3. iOS 는 RevenueCat 네이티브 SDK 링크가 추가로 필요하다 (SPM 또는 CocoaPods).
   **여기가 미검증 구간이다** — Xcode 프로젝트에 SPM 의존성을 넣어야 하고,
   XcodeGen `packages:` 로 선언할 수 있다

## 스토어 쪽 (개발자가 해야 함)

- RevenueCat 프로젝트 생성 → Android/iOS 앱 등록 → 공개 SDK 키 발급
- App Store Connect / Play Console 에 **비소모품 상품** 등록
  - 제안 ID: `com.dogdduddy.almanac.core2026`
  - 가격: 4,000~6,000원대 (앞서 논의 — 2,000원은 "장인정신" 포지션에 안 맞는다)
- RevenueCat 에서 Entitlement 생성 → id 를 `core-2026` 으로 (packs.id 와 일치)
- **상품 등록은 스토어 심사를 탄다.** 9월 중순 제출 목표면 여유를 둬야 한다

## 심사위원 체험 (제출 요건)

무료 체험 또는 프로모 코드를 제출물에 넣어야 한다.
비소모품이므로 App Store 프로모 코드가 맞는다. **제출 전에 코드 입력 → 즉시 전체 해제가
한 번에 되는지 직접 확인할 것** — 여기서 막히면 심사위원이 본 것은 무료 버전뿐이다.


---

# 2026-08-16 — SDK 연결 결과

## 된 것

- `purchases-kmp-core:3.5.0` 의존성 추가. **iOS Native 컴파일까지 통과**
- `RevenueCatBilling` 구현. 엔티틀먼트 → 보유 팩 매핑
- 키 주입: `local.properties` → 빌드 시 `BillingKeys.kt` 생성.
  소스에 키가 안 들어가므로 저장소를 공개해도 된다
- 키 선택 순서: **실키 → Test Store 키 → PreviewBilling**.
  키가 하나도 없어도 앱은 정상 동작한다 (CI, 신규 클론)
- Android 에뮬레이터에서 SDK 초기화 확인:
  `WARN: Using a Test Store API key.`

## 안 된 것 — 상품이 없다

페이월 진입점("The shelf")이 안 뜬다. `products()` 가 빈 목록이기 때문이다.

**RevenueCat 대시보드에 Test Store 상품을 만들어야 한다:**

1. Test Store 앱 → Products → 상품 생성
   - identifier: `com.dogdduddy.almanac.core2026`
2. Entitlements → 생성
   - identifier: **`core-2026`** (content.db 의 packs.id 와 같아야 매핑 테이블이 필요 없다)
   - 위 상품을 이 엔티틀먼트에 연결
3. Offering 은 쓰지 않는다 — 우리는 `getProducts(productIds)` 로 직접 조회한다

## ⚠️ Test Store 키로 출시하면 안 된다

SDK 가 직접 경고한다:

> Our SDK will **crash if using it in production**.
> Apps submitted with a Test Store API key will be **rejected during App Review**.

개발 중에는 실키가 없어 test 키로 떨어지는 게 정상이고, **그 편의가 릴리스로 새는 것이 위험하다.**
사람이 기억하는 대신 빌드가 막도록 `checkReleaseBillingKey` 태스크를 걸었다 —
`assembleRelease` / `bundleRelease` 는 실키(`goog_`)가 없으면 실패한다.

iOS 에도 같은 가드가 필요하다. 아직 안 걸었다 — Xcode Release 스킴에서
`almanac.revenuecat.ios` 를 검사하는 스크립트를 붙일 것.

## 실키가 나오면

1. `local.properties` 에 `almanac.revenuecat.android` / `almanac.revenuecat.ios` 추가
2. 코드 변경 없음 — 키 선택이 자동으로 실키를 우선한다
3. **다시 검증해야 한다.** Test Store 는 스토어 실연동을 증명하지 않는다
   (StoreKit / Play Billing 경로, 유료 앱 계약, 프로모 코드 흐름)


## iOS: RevenueCat 을 shared 에 두면 안 된다 (2026-08-16 발견)

`purchases-kmp` 의 iOS cinterop 은 구버전 Xcode(16.4) 기준으로 빌드돼 있고,
`libswiftCompatibility56` / `swiftCompatibilityConcurrency` / `swiftCompatibilityPacks`
를 요구한다. **Xcode 26 툴체인에는 이 라이브러리들이 없다.**

증상이 갈린다.
- 앱 프레임워크 링크: **성공** (Xcode 가 링크하므로)
- Kotlin/Native **테스트 실행 파일** 링크: **실패** (독립 실행 파일이라 스스로 링크해야 한다)

즉 shared 에 의존성을 두면 앱은 멀쩡한데 **iOS 테스트가 통째로 안 돈다.**
크로스플랫폼 골든 벡터가 거기 있으므로 잃으면 안 된다.

그래서 RevenueCat 구현과 의존성을 `composeApp`(앱 쪽 모듈)으로 옮겼다.
`shared` 는 순수 도메인으로 남고, `IosAlmanacGraph.billing` 은 기본값이 PreviewBilling 인
`var` 이며 앱이 시작할 때 실제 구현을 주입한다.

**위젯은 주입하지 않는다** — 아무것도 팔지 않으므로 결제 SDK 를 링크할 이유가 없다.
익스텐션 크기와 메모리에도 유리하다.

링커 옵션으로는 못 고친다. 라이브러리가 머신에 아예 없다.
SDK 가 새 Xcode 기준으로 재빌드되면 다시 합칠 수 있다.
