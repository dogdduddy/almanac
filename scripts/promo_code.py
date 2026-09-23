#!/usr/bin/env python3
"""
프로모션 코드 → PromoCodes.kt 에 넣을 해시.

평문 코드를 앱 바이너리에 싣지 않기 위해 해시만 소스에 둔다.
코드를 새로 발급하거나 바꿀 때 이 스크립트로 상수를 뽑는다.

사용:
    python3 scripts/promo_code.py SHIPATON-2026
    python3 scripts/promo_code.py SHIPATON-2026 KMP-AWARD-2026

출력한 값을 shared 의 billing/PromoCodes.kt 의 ACCEPTED 에 넣고,
평문은 docs/decisions/promo-code.md 에 적는다.

정규화와 해시는 Kotlin 쪽과 **반드시 같아야 한다.**
- 정규화: 대문자로 올린 뒤 A-Z0-9 만 남긴다 (PromoCodes.normalize)
- 해시: FNV-1a 64 (core/Hashing.kt). 시드에 쓰는 것과 같은 함수다

의존성 없음 (Python 3 표준 라이브러리만).
"""

from __future__ import annotations

import sys

FNV64_OFFSET_BASIS = 14695981039346656037
FNV64_PRIME = 1099511628211
MASK64 = (1 << 64) - 1

ALLOWED = set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")


def normalize(raw: str) -> str:
    """PromoCodes.normalize 와 같은 규칙."""
    return "".join(c for c in raw.upper() if c in ALLOWED)


def fnv1a64(text: str) -> int:
    """core/Hashing.kt 의 fnv1a64 와 같은 값을 낸다."""
    h = FNV64_OFFSET_BASIS
    for byte in text.encode("utf-8"):
        h ^= byte
        h = (h * FNV64_PRIME) & MASK64
    return h


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2

    for raw in argv[1:]:
        key = normalize(raw)
        if not key:
            print(f"# {raw!r} 은 정규화하면 비어 있다 — 코드로 쓸 수 없다", file=sys.stderr)
            return 1
        print(f"{fnv1a64(key)}uL,  // {raw}  (정규화: {key})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
