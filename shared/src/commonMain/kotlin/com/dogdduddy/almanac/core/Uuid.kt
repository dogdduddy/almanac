package com.dogdduddy.almanac.core

/**
 * 플랫폼의 랜덤 UUID. 형식은 표준 UUID v4 문자열.
 *
 * 대소문자는 플랫폼마다 다르다 (iOS 는 대문자를 준다). **저장 전에 반드시
 * 소문자로 정규화한다** — 시드 입력이라 형식이 흔들리면 문장이 갈라진다.
 * 플랫폼 간 같아야 하는 것은 생성 알고리즘이 아니라 저장 형식이다.
 */
internal expect fun randomUuid(): String
