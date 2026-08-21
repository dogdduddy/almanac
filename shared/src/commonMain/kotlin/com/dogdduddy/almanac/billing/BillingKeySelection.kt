package com.dogdduddy.almanac.billing

/**
 * 어떤 RevenueCat 키로 SDK 를 초기화할지 고른다.
 *
 * **"비어 있지 않으면 실키" 로 두면 안 된다.** 스토어에서 키를 아직 못 받은 동안
 * `appl_XXXXX` 같은 자리표시자를 넣어두는 일이 실제로 있었고, 그 값이 비어 있지
 * 않다는 이유만으로 Test Store 폴백을 가렸다. 결과는 조용한 죽음이다 — 실키도
 * 아니고 Test Store 도 아닌 키로 초기화되어 상품이 빈 목록으로 오고, 페이월
 * 진입점이 통째로 사라진다. 화면에는 에러가 없으니 "키를 넣었는데 왜 안 되지"
 * 로만 보인다.
 *
 * 그래서 형식을 본다. 접두사만 보면 자리표시자를 못 거르므로(`appl_` 다섯 자도
 * 접두사는 맞다) 길이도 같이 본다.
 */

/** RevenueCat 공개 SDK 키의 스토어 접두사. */
private val KEY_PREFIXES = listOf("goog_", "appl_", "test_", "amzn_")

/**
 * 접두사 뒤 본문의 최소 길이.
 *
 * 실제 키의 본문은 30자 안팎이고 자리표시자는 훨씬 짧다. 경계를 20 으로 둬서
 * 키 형식이 다소 바뀌어도 실키를 잘못 버리지 않게 한다.
 */
private const val MIN_KEY_BODY_LENGTH = 20

/** RevenueCat 공개 SDK 키로 쓸 수 있는 형태인가. */
fun isUsableBillingKey(key: String): Boolean =
    KEY_PREFIXES.any { prefix ->
        key.startsWith(prefix) && key.length - prefix.length >= MIN_KEY_BODY_LENGTH
    }

/**
 * 실키를 우선하고, 쓸 수 없으면 Test Store 키로 떨어진다.
 *
 * @return 초기화에 쓸 키. 둘 다 쓸 수 없으면 null — 호출자는 결제 없는 구현으로 간다
 */
fun selectBillingKey(platformKey: String, testKey: String): String? =
    platformKey.takeIf { isUsableBillingKey(it) }
        ?: testKey.takeIf { isUsableBillingKey(it) }
