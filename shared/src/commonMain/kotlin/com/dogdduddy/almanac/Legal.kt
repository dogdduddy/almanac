package com.dogdduddy.almanac

/**
 * 앱 밖에 있는 법적 문서의 주소.
 *
 * Apple 과 Google 모두 개인정보처리방침을 **스토어 등록 정보와 앱 안 양쪽에서**
 * 접근할 수 있게 요구한다. 한 곳에만 두면 심사에서 지적된다.
 *
 * 두 플랫폼과 스토어 등록 페이지가 같은 주소를 봐야 하므로 여기 한 곳에 둔다.
 */
object Legal {

    /**
     * 별도 공개 저장소의 GitHub Pages 로 서비스한다.
     *
     * 저장소: dogdduddy/almanac-privacy
     * Pages 설정: Settings → Pages → Deploy from a branch → main / `/ (root)`
     */
    const val PRIVACY_POLICY_URL = "https://dogdduddy.github.io/almanac-privacy/"
}
