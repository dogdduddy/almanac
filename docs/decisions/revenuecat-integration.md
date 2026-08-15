# RevenueCat 연동 — 남은 작업

2026-08-15. 코드 이음매는 만들었고, **키와 스토어 상품이 없어 SDK 자체는 아직 안 붙였다.**

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
