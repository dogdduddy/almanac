# Almanac 출시 체크리스트

2026-09-25 기준. 코드에서 확인 가능한 것과 콘솔에서 직접 확인해야 하는 것을 분리한다.

## 현재 확인된 상태

- [x] Android `targetSdk 36` — 2026-08-31 이후 Play 제출 기준 충족
- [x] Android Release AAB 생성, 업로드 키 서명, 릴리스 RevenueCat 키 검사 통과
- [x] Android AAB 에 `content.db` 428편과 Crimson Text 폰트 포함
- [x] Android 릴리스 린트와 전체 공유 테스트 통과
- [x] iOS Release arm64 앱 + 위젯 빌드, 콘텐츠 및 RevenueCat 키 검사 통과
- [x] iOS/Android 포그라운드 복귀 시 스토어 엔티틀먼트 재동기화
- [x] 개인정보처리방침 소스와 앱 안 진입점 존재
- [x] Play 아이콘, 기능 그래픽, 휴대전화 스크린샷 2장 존재
- [x] App Store 6.5형 RGB 스크린샷 2장 존재

## 제출 전에 결정할 것

- [x] 출시 버전 통일: 2026-09-28 에 둘 다 `0.4.0` 으로 맞췄다 — Android `(5)`, iOS `(6)`.
  다음 업로드는 빌드 번호를 반드시 더 크게 잡는다.
- [ ] 출시 국가와 기준 가격 확정. 현재 제안은 USD 3.99 비소모품이다.
- [ ] 자동 출시가 아니라 수동 출시로 두어 양 스토어의 공개 시점을 맞춘다.

## 스토어 상품과 RevenueCat — 가장 먼저

- [ ] App Store Connect 와 Play Console 에 비소모품/일회성 상품
  `com.dogdduddy.almanac.core2026` 생성
- [ ] 표시 이름 `The 2026 Collection`, 30자 이하
- [ ] 설명 `Unlock all 342 additional passages.`, 45자 이하
- [ ] 가격과 판매 지역 설정, Paid Apps 계약·세금·은행 정보 활성 확인
- [ ] RevenueCat 에 양 플랫폼 상품을 import하고 entitlement `core-2026` 에 연결
- [ ] RevenueCat 고객 화면에서 샌드박스 구매 뒤 `core-2026` 활성 확인
- [ ] iOS 첫 비소모품은 **앱 버전과 같은 심사 제출물**에 Add for Review
- [ ] iOS IAP 심사용 스크린샷과 Review Notes 추가

## 내부 테스트

- [ ] Play 내부 테스트 트랙에 Release AAB 업로드 — `0.4.0 (5)` AAB 는 2026-09-28 에 만들었다
  (`androidApp/build/outputs/bundle/release/almanac-0.4.0-5.aab`). 콘솔 업로드는 손으로 한다
  (Play 게시 API 자격 증명이 없다)
- [ ] 라이선스 테스터 계정으로 새 설치 → 구매 → 재실행 → 복원 확인
- [x] TestFlight 내부 테스트에 Release 아카이브 업로드 — `0.4.0 (6)`, 2026-09-28.
  `xcodebuild -exportArchive` 로 Xcode 계정을 써서 올렸다 (`iosApp/build/AppStoreExportOptions.plist`)
- [ ] TestFlight 의 **수출 규정 준수(암호화)** 질문에 답한다. Info.plist 에
  `ITSAppUsesNonExemptEncryption` 이 없어서 빌드마다 묻는다 — 답이 정해지면 키로 넣을지 결정
- [ ] 샌드박스 계정으로 새 설치 → 구매 → 재실행 → 복원 확인
- [ ] iOS Sandbox Offer Code와 Play 1회용 프로모션 코드 사용 후 앱 복귀 즉시 서가가 열리는지 확인
- [ ] 구매/복원 뒤 위젯이 유료 문장을 표시하는지 확인
- [ ] 위치 허용·거부, 첫 실행 오프라인, 앱 삭제 후 재설치 흐름 확인

## 개인정보와 심사 설문

- [ ] `https://dogdduddy.github.io/almanac-privacy/` 가 로그인 없이 열리는지 실기 확인
- [ ] App Store App Privacy에 위치, 구매, 식별자 등 RevenueCat을 포함한 실제 수집 항목 입력
- [ ] Play Data safety에 앱과 RevenueCat/MET Norway의 전송·수집 항목 입력
- [ ] Play 위치 권한 선언에서 대략적 위치가 핵심 날씨 기능에 쓰이며 거부 시 도시 선택이 가능하다고 설명
- [ ] 연령 등급, 광고 없음, 타깃 연령, 콘텐츠 권리 문항 완료

## 스토어 페이지

- [ ] 앱 이름, 부제/짧은 설명, 긴 설명, 키워드, 지원 URL 입력
- [ ] iOS는 현재 6.5형 RGB 스크린샷 2장을 사용한다. `iphone-6.9/*source.png`는 알파 채널이 있어 그대로 업로드하지 않는다
- [ ] 앱 미리보기 영상은 선택 사항. 올릴 경우 샌드박스 결제 표기가 없는 최종본 사용
- [ ] IAP 가격은 스크린샷 이미지에 박지 않고 스토어 현지화 가격을 사용

## 심사 메모

- [ ] 계정/로그인이 없고 위치 권한은 선택 사항임을 첫 문단에 명시
- [ ] 구매 화면 경로: 앱 하단 `The shelf` → `Open the full shelf`
- [ ] 상품 ID와 비소모품임을 명시
- [ ] 위치 거부 시 도시 선택으로 전체 무료 흐름을 확인할 수 있다고 설명
- [ ] 첫 IAP를 앱 버전과 함께 제출했는지 마지막으로 재확인

## 승인 뒤

- [ ] iOS 프로덕션 Offer Code 생성(앱 Ready for Distribution + IAP Approved 필요)
- [ ] Play 일회용 프로모션 코드 생성
- [ ] 새 계정에서 코드 사용 → 앱 복귀 → 서가/위젯/재실행 유지 최종 확인
- [ ] 공개 전 비상구 코드 노출 여부 결정. 스토어 코드가 준비되면 심사 자료에서는 비상구를 우선 안내하지 않는다
- [ ] `main`의 미푸시 커밋과 이번 출시 수정 커밋을 원격에 push하고 태그 생성
