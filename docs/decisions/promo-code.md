# 심사위원 전체 해제 — 스토어 코드와 비상구

2026-09-23. 제출 요건(심사위원이 모든 프리미엄 기능을 시험할 수 있을 것)에 대한 답.
외부 피드백 반영해 **스토어 코드를 정식 경로로** 돌리고 앱 안의 코드는 비상구로 내렸다.

---

## 결론

| | 경로 | RevenueCat 을 타는가 | 전제 |
|---|---|---|---|
| **정식** | iOS **Offer Code** / Play **프로모션 코드** | **탄다** | 프로덕션 코드는 앱·상품 승인이 필요하다 |
| 비상구 | 앱 안 `Have a code` | **안 탄다** | 없음 |

일반 부문은 **실제 스토어 출시와 작동하는 RevenueCat 연동이 필수**다.
앱 안에서 로컬로 팩을 지급하면 심사위원이 프리미엄 기능은 보지만
**결제 연동은 한 번도 건드리지 않는다.** 그래서 정식 경로는 스토어 코드다.

비상구를 남기는 이유는 하나뿐이다 — 프로덕션 스토어 코드는 **앱이 Ready for Distribution 이고
상품이 승인된 뒤에야** 나온다. iOS 샌드박스 Offer Code 는 그 전에도 만들 수 있으므로 복귀
동기화 검증에는 쓸 수 있다.
iOS 는 이미 2.1(a) 로 한 번 반려됐다. 마감까지 한쪽이 안 열리면 그쪽 심사위원에게는
줄 것이 없어지고, 그러면 그가 본 것은 무료 버전뿐이다.

---

## 1. iOS — Offer Code (Promo Code 아님)

**원래 계획이 낡았다.** revenuecat-integration.md 는 "비소모품이므로 App Store 프로모 코드가
맞는다" 고 적었는데, **2026-03-26 부터 App Store Connect 에서 인앱 구매용 프로모 코드를
새로 만들 수 없다.** 이미 지난 날짜다. (기존에 만들어 둔 코드는 만료까지 사용 가능)

대신 **Offer Code 가 모든 인앱 구매 유형을 지원하도록 확장됐다** —
소모품, **비소모품**, 비갱신 구독, 자동갱신 구독. 우리 상품(`com.dogdduddy.almanac.core2026`)은
비소모품이므로 여기에 해당한다.

- 앱 밖 **App Store 사용 링크**로 쓸 수 있다. 심사위원에게는 이쪽이 안정적이다 —
  앱 안 코드 입력 시트는 동작이 불안정하다는 보고가 있다
- 사용 후 앱으로 돌아오면 `CustomerInfo` 를 다시 받아 화면을 갱신한다.
  Android `onResume` 과 iOS `UIApplicationDidBecomeActiveNotification` 이 공유
  `AppLoader.storeMayHaveChanged()` 를 부른다.

## 2. Android — 비구독 프로모션

Play Console → 프로모션 → **비구독(일회성 상품) 프로모션**에 `core-2026` 상품을 연결한다.

- **분기당 500개** (유료 앱 + 일회성 상품 합산)
- **1회용 코드**: Play 스토어 앱에서도, 앱 안에서도 사용 가능 → **심사위원에게는 이쪽**
- **커스텀 코드**(같은 문자열 반복 사용)는 **앱 안에서만** 쓸 수 있고
  In-app Promotions 통합이 따로 필요하다. 지금 붙일 것이 아니다

즉 Android 는 심사위원마다 **다른 코드 한 장씩** 나간다. iOS Offer Code 와 형태가 다르므로
제출물에 **플랫폼을 구분해서** 적어야 한다.

## 3. 비상구 — 앱 안 `Have a code`

앱 → **The shelf** → **Have a code**. 대소문자·하이픈·공백을 무시한다.

| 코드 | 용도 |
|---|---|
| `SHIPATON-2026` | Shipaton |
| `KMP-AWARD-2026` | KMP Award |
| `DESIGN-AWARD-2026` | Design Award |

**스토어 코드가 양쪽 다 준비되면 제출물에서 이 코드를 빼는 것이 낫다.**
결제 연동을 증명하지 않는 경로를 심사위원에게 굳이 먼저 보여줄 이유가 없다.
기능 자체를 들어내고 싶으면 `AppActions.onRedeemCode` 를 안 넘기면 된다 — 입력란이 사라진다.

### 열렸다는 소식은 구매와 같은 경로로 간다

코드가 통하면 `PurchaseState.Unlocked` 로 올라가 페이월의 확인 화면이 받는다 —
`The shelf is open` / `342 more passages are yours now`. 구매로 열린 것과 코드로 열린 것이
유저에게 다른 사건일 이유가 없고, 확인 문구도 이미 거기 있다.
입력란 옆에 남는 것은 **코드 자체의 문제**(모르는 코드, 기간 만료)뿐이다.

**데스크톱에는 입력란이 없다.** 결제가 없어(`NoBilling`) 서가 진입점 자체가 감춰지고,
About 의 한 줄이 전체 컬렉션은 모바일에 있다고 안내한다 (desktop-target.md 의 선택 A).
데스크톱에서도 열리게 하려면 `Main.kt` 의 `AppActions` 에 `onRedeemCode` 를 넘기면 되지만,
그건 그 결정을 뒤집는 일이므로 따로 판단할 것.

### 여기 걸어둔 제약

- **기간 제한 (2027-01-31 UTC).** 서버가 없어 **사용 횟수를 셀 수 없다.**
  제출 페이지가 공개되면 이 코드는 그대로 영구 무료 해제가 된다
  (스토어 코드는 1회용이라 이 문제가 없다). 만료는 **새 입력만** 막고
  이미 연 기기는 건드리지 않는다 — 심사 중에 닫히는 쪽이 훨씬 나쁘다
- **평문을 바이너리에 싣지 않는다.** 정규화한 코드의 FNV-1a 64 해시만 넣는다.
  암호학적 방어가 아니라 `strings` 한 번에 드러나지 않게 하는 정도다
- **지급 출처를 `promo` 로 남겨 동기화의 회수 대상에서 뺀다.** 이게 없으면
  결제 없는 보유 팩으로 보여 **첫 동기화에서 조용히 닫힌다** — 에러도 안내도 없이
- 특정 팩 id 를 박지 않고 잠긴 팩을 전부 연다. 팩이 늘어도 약속이 저절로 지켜진다

### 코드를 바꾸거나 더할 때

```bash
python3 scripts/promo_code.py NEW-CODE-2026
```

출력한 `...uL` 줄을 `shared/.../billing/PromoCodes.kt` 의 `ACCEPTED` 에 넣고
평문을 위 표에 적는다. **이 저장소는 비공개다** — 공개로 돌린다면 코드를 새로 발급할 것.

---

## 남은 일 (순서대로)

1. **두 스토어에 상품을 올리고 실제 구매가 되는지 확인한다.** 이게 먼저다 —
   코드 경로는 전부 여기에 매달려 있다
2. RevenueCat 대시보드에서 그 구매가 잡히는지 본다
3. iOS Offer Code / Play 비구독 프로모션 발급
4. **완료 — 포그라운드 복귀 동기화.** Offer Code / Play 프로모션 코드를 스토어에서
   쓰고 앱으로 돌아오면 양 플랫폼 모두 엔티틀먼트를 다시 받고 서가를 즉시 갱신한다.
   `AppLoaderTest` 가 앱 재실행 없이 `core-2026` 이 열리는 흐름을 검증한다
5. 제출물에 플랫폼별 코드와 **2~3단계 사용법**을 적는다

## 제출 전에 직접 해볼 것

**새 계정**(미국 스토어)으로, 릴리스 빌드에서:

- [ ] iOS: Offer Code 사용 → 앱 복귀 → 서가가 열려 있는가
- [ ] Android: 1회용 코드 사용 → 앱 복귀 → 서가가 열려 있는가
- [ ] 앱을 껐다 켜도 열린 상태인가 (동기화 회수가 안 걸리는지)
- [ ] **구매 복원**이 되는가 (기기 변경 시나리오)
- [ ] 위젯도 갱신되는가
- [ ] 비상구 코드: 비행기 모드에서도 먹는가 / 두 번째 입력은 `already have all` 인가

## 출처

- [Apple — Enhancements to help you submit and market your apps and games](https://developer.apple.com/news/?id=gf6mgrs6)
  (인앱 구매 프로모 코드 생성 종료 2026-03-26, Offer Code 의 비소모품 지원)
- [RevenueCat — iOS Subscription Offers](https://www.revenuecat.com/docs/subscription-guidance/subscription-offers/ios-subscription-offers)
- [Google Play — 프로모션 만들기](https://support.google.com/googleplay/android-developer/answer/6321495?hl=en)
