#!/usr/bin/env python3
"""
데모 영상 컷 녹화 (Android).

콘티(docs/product/demo-video.md)의 컷 하나가 여기서는 함수 한 번이다.
마음에 안 드는 컷은 그 컷만 다시 돌리면 되고, 결과는 매번 같다.

**녹화 시작을 결정적으로 만든다.** 촬영 메뉴를 스크롤한 상태에서 녹화를 걸면
`Close` 를 찾느라 흘러간 시간만큼 페이지가 늦게 나타나 컷마다 시작점이 달라진다.
그래서 녹화 전에 항상 맨 위로 되돌려 `Close` 를 제자리에 둔다 — 그러면 페이지는
언제나 `lead + TRANSITION` 즈음에 나타나고, 편집이 추측할 일이 없다.

사용:
    python3 scripts/record_demo.py montage <출력 디렉터리>

기기는 `ALMANAC_DEVICE` 로 고른다.
"""

from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from demo_drive import Device  # noqa: E402

#: Close 를 누르고 페이지가 자리 잡기까지. 실측값이고 편집의 기준점이다.
#: 녹화 중에는 화면 조회를 하지 않으므로 이 값이 컷마다 흔들리지 않는다.
TRANSITION = 0.35

#: 녹화 앞에 두는 여유. 이 구간에는 촬영 메뉴가 찍히므로 편집에서 잘라낸다.
LEAD = 0.8

#: 등장 애니메이션 길이 (WeatherEntrance.kt 와 같아야 한다).
ENTRANCE_MS = {
    "Clear": 850, "Cloudy": 1500, "Fog": 1700, "Drizzle": 950,
    "Rain": 1150, "Snow": 2300, "Thunder": 950, "Wind": 1050,
}

#: 시간대 컷에서 고정할 날씨. **빠른 것이어야 한다** — 1.8초 칸에 눈(2300ms)을 두면 잘린다.
TIME_CUT_WEATHER = "Clear"

#: 날씨 컷 넷. (파일 이름, 날씨, 고정할 시간대)
#:
#: **시간대를 실제 시각에 맡기면 찍는 시각마다 책이 달라진다.** 그래서 컷마다 박는다.
#: 시간대는 화면에 드러나지 않으므로(구석 라벨은 날씨뿐) 컷마다 달라도 보는 사람은 모른다.
#: 고르는 기준은 **책이 겹치지 않는 것**이다 — 일곱 컷끼리도, 다른 컷과도.
#:
#: 2026-09-28 Pixel 8 에뮬레이터(잠긴 서가)에서 세 가지로 찍어 봤다.
#: - 전부 저녁 — 눈이 Ethan Frome. #6 의 iPhone 위젯도 Ethan Frome 이라 10초 사이에 두 번
#: - 전부 낮   — 비와 눈이 둘 다 Lorna Doone
#: - 전부 아침 — 바람이 Ethan Frome
#: 눈만 아침으로 두면 Dubliners("The Dead" 의 끝, 눈이 온 세상에 내리는 문장)가 나오고
#: 일곱 권이 전부 다르다. 날짜가 바뀌면 다시 골라야 한다.
WEATHER_CUTS = [
    ("rain", "Rain", "Evening & night"),
    ("snow", "Snow", "Morning"),
    ("fog", "Fog", "Evening & night"),
    ("wind", "Wind", "Evening & night"),
]


def open_demo(d: Device) -> None:
    """어느 화면에서든 촬영 메뉴까지 간다."""
    labels = {t for t, _, _ in d.dump()}
    if "Demo" in labels:
        return
    if "About" not in labels:
        d.tap_text("Close", settle=1.2)
    d.tap_text("About", settle=1.5)
    d.tap_text("Demo controls", settle=1.5)


def capture(d: Device, out_path: str, *, choose: list[str], hold: float) -> str:
    """촬영 메뉴에서 [choose] 를 고르고, 닫으면서 [hold] 초만큼 페이지를 녹화한다."""
    open_demo(d)
    for label in choose:
        d.tap_text(label, settle=1.2)

    # 결정적 시작.
    #
    # **녹화가 도는 동안 화면을 조회하면 안 된다.** 접근성 덤프 한 번이 1~2초씩 걸려서,
    # 그 시간만큼 촬영 메뉴가 찍히고 컷마다 시작점이 달라진다. 실제로 그렇게 찍혀
    # 몽타주 7컷 중 5컷이 메뉴 화면이었다.
    #
    # 그래서 좌표를 **미리** 구해두고, 녹화 중에는 그 자리를 누르기만 한다.
    d.scroll_to_top()
    close_x, close_y = d.find("Close")

    def action(dev: Device) -> None:
        dev.tap(close_x, close_y)
        time.sleep(hold)

    return d.record(out_path, action, lead=LEAD, tail=0.4)


def montage(d: Device, out_dir: str) -> None:
    """#5 하늘의 변주 — 날씨 4종 + 시간대 3종."""
    os.makedirs(out_dir, exist_ok=True)

    for name, label, time_of_day in WEATHER_CUTS:
        # 애니메이션이 끝나고도 2초 남게 받는다. 편집에서 자를 여유다.
        hold = ENTRANCE_MS[label] / 1000 + 2.0
        capture(d, f"{out_dir}/05-{name}.mp4", choose=[time_of_day, label], hold=hold)
        print(f"  ✓ 05-{name}  (등장 {ENTRANCE_MS[label]}ms)")

    hold = ENTRANCE_MS[TIME_CUT_WEATHER] / 1000 + 2.0
    for i, (name, label) in enumerate(
        [("dawn", "Morning"), ("day", "Day"), ("dusk", "Evening & night")]
    ):
        # 첫 컷에서만 날씨를 맞춘다. 이후에는 시간대만 바꾼다 —
        # 날씨까지 같이 바뀌면 무엇이 문장을 갈랐는지 화면에서 안 보인다.
        choose = [TIME_CUT_WEATHER, label] if i == 0 else [label]
        capture(d, f"{out_dir}/05-{name}.mp4", choose=choose, hold=hold)
        print(f"  ✓ 05-{name}")


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    d = Device()
    if argv[1] == "montage":
        montage(d, argv[2])
    else:
        print(f"모르는 컷: {argv[1]}", file=sys.stderr)
        return 2
    print(f"\n페이지가 나타나는 시각 ≈ {LEAD + TRANSITION:.2f}초. 편집은 여기서 자른다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
