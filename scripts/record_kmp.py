#!/usr/bin/env python3
"""
#7 KMP 증명 컷의 원본 셋을 찍는다 — Android · iPhone · 데스크톱.

**화면을 한 번도 누르지 않는다.** 세 기기가 같은 실행 옵션(DemoLaunchOptions)으로 떠서
고정 → 이력 비우기 → 아카이브 채우기 → 한 장 넘기기를 스스로 한다. 그래서
- iOS 처럼 탭 수단이 없는 기기도 찍을 수 있고,
- Demo 화면이 한 프레임도 안 들어가며,
- 세 기기의 넘김이 **같은 곡선**이다 (손으로 밀면 기기마다 빠르기가 다르다).

넘김 순간은 기기마다 파일 안의 시각이 다르다. 맞추는 일은 편집이 한다
(`cut_demo.py split` 의 SPLIT_SOURCES).

데스크톱은 **정지 화면**으로 찍는다. 분할의 끝 2초에 "셋이 같은 문장" 으로만 들어가므로
움직일 필요가 없다. 다만 정지 캡처도 **화면 기록 권한**이 있어야 창이 찍힌다 — 없으면
오류 없이 바탕화면만 나온다. 그래서 찍은 뒤 종이색인지 확인하고 아니면 멈춘다.

**세 기기는 같은 날 찍어야 한다.** 슬롯에 날짜가 들어가므로 하루가 넘어가면 문장이 갈린다.
보유 팩도 같아야 한다 — 후보 풀이 보유 팩으로 걸러진다 (docs/product/demo-video.md #7).

사용:
    python3 scripts/record_kmp.py <녹화 뿌리> [android] [ios] [desktop]    # 안 주면 셋 다

Android 는 `ALMANAC_DEVICE`, iOS 는 부팅된 시뮬레이터 하나를 쓴다.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ios_record  # noqa: E402
from demo_drive import ACTIVITY, USER, Device  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: 세 기기에 똑같이 넣는 옵션. 날씨와 시간대는 #5 의 책과 겹치지 않는 조합으로 골랐다.
OPTIONS = {
    "almanac.demo.weather": "clear",
    "almanac.demo.time": "day",
    "almanac.demo.seed": "shared",
    "almanac.demo.archive": "refill",
    "almanac.demo.turn": "6000",
}

#: 앱이 뜨고 비우기·채우기가 끝나기까지 (DemoLaunchOptions.stage 의 1.5 + 2.0 에 시작 여유).
STAGED = 4.5

#: 채우기가 끝나고 넘기기까지. OPTIONS 의 turn 과 같은 값이다.
TURN = int(OPTIONS["almanac.demo.turn"]) / 1000

#: 넘긴 뒤 새 페이지의 등장까지 담는 여유.
AFTER = 3.5

#: 데스크톱 창 자리 (Main.kt 의 DEMO_WINDOW_X/Y 와 창 크기).
DESKTOP_REGION = "60,40,440,860"
DESKTOP_MAIN = "com.dogdduddy.almanac.MainKt"


def android(root: str) -> str:
    out = f"{root}/raw-android/07-kmp-android.mp4"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    d = Device()
    d.stop()
    extras = [arg for key, value in OPTIONS.items() for arg in ("--es", key, value)]
    d.sh("am", "start", "--user", USER, "-n", ACTIVITY, *extras)
    time.sleep(STAGED)
    # 넘김이 녹화 한가운데 오도록: 녹화 시작 0.5초 뒤부터 넘김 뒤 AFTER 초까지.
    d.record(out, lambda _: time.sleep(TURN - 1.0 + AFTER), lead=0.5, tail=0.4)
    return out


def ios(root: str) -> str:
    out = f"{root}/raw-ios/07-kmp-ios.mp4"
    device = ios_record.booted()
    ios_record.sim("terminate", device, ios_record.BUNDLE)
    time.sleep(1.0)
    args = [arg for key, value in OPTIONS.items() for arg in (f"-{key}", value)]
    ios_record.sim("launch", device, ios_record.BUNDLE, *args)
    time.sleep(STAGED)
    proc = ios_record.start_recording(out)        # 여기서 WARMUP 만큼 흐른다
    time.sleep(TURN - ios_record.WARMUP + AFTER)
    ios_record.stop_recording(proc, out)
    return out


def desktop(root: str) -> str:
    out = f"{root}/raw-desktop/07-kmp-desktop.png"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    props = [f"-P{key}={value}" for key, value in {"almanac.demo": "true", **OPTIONS}.items()]
    gradle = subprocess.Popen(
        [f"{ROOT}/gradlew", ":desktopApp:run", "--console=plain", *props],
        cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        # Gradle 이 앱 JVM 을 띄우기까지는 매번 다르다. 프로세스가 보이면 그때부터 잰다.
        for _ in range(240):
            if subprocess.run(["pgrep", "-f", DESKTOP_MAIN], capture_output=True).returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise SystemExit("데스크톱 앱이 뜨지 않았다.")
        # 창이 뜨고 첫 컴포지션이 도는 데 JVM 이 몇 초 더 쓴다.
        time.sleep(3.0 + STAGED + TURN + AFTER)
        subprocess.run(["screencapture", "-x", "-R", DESKTOP_REGION, out], check=True)
        if not looks_like_paper(out):
            os.remove(out)
            raise SystemExit(
                "창 대신 바탕화면이 찍혔다 — 화면 기록 권한이 없다.\n"
                "시스템 설정 → 개인정보 보호 및 보안 → 화면 기록 에서 이 터미널을 켜고 다시 돌릴 것.\n"
                "(폰 둘과 같은 날이어야 한다. 날이 바뀌었으면 셋 다 다시 찍는다.)")
    finally:
        subprocess.run(["pkill", "-f", DESKTOP_MAIN], capture_output=True)
        gradle.wait(timeout=60)
    return out


def looks_like_paper(png: str) -> bool:
    """캡처 한가운데가 앱의 종이색인가.

    **화면 기록 권한이 없으면 정지 캡처도 다른 앱의 창을 담지 못한다.** 오류도 없이
    바탕화면만 찍힌 그림이 나오므로, 그걸 원본으로 믿고 편집까지 가지 않게 여기서 본다.
    """
    rgb = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", png, "-vf", "crop=iw/2:ih/4:iw/4:ih*3/5,scale=1:1",
         "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
        capture_output=True).stdout
    if len(rgb) < 3:
        return False
    paper = (0xFB, 0xF9, 0xF4)
    return all(abs(c - p) <= 12 for c, p in zip(rgb[:3], paper))


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    root = argv[1]
    targets = argv[2:] or ["android", "ios", "desktop"]
    takes = {"android": android, "ios": ios, "desktop": desktop}
    for name in targets:
        if name not in takes:
            print(f"모르는 기기: {name}", file=sys.stderr)
            return 2
        print(f"  ✓ {takes[name](root)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
