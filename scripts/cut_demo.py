#!/usr/bin/env python3
"""
녹화한 컷을 데모 영상으로 조립한다.

**컷 길이가 소수점까지 정해져 있는 것이 이 스크립트의 존재 이유다**
(docs/product/demo-video.md #5). 손으로 맞추면 매번 달라지고, 한 컷을 고치면
뒤가 전부 밀린다. 여기서는 표 한 줄을 고치면 끝이고 결과는 항상 같다.

하는 일.
- 기기 녹화의 시스템 영역(상태바·내비게이션 바)을 잘라낸다
- 지정한 길이로 **하드 컷**한다. 디졸브를 쓰지 않는 것이 #5 의 요구다
- 앱과 **같은 서체(Crimson Text)** 로 자막을 태운다
- 가변 프레임 녹화를 60fps 고정으로 맞춘다

사용:
    python3 scripts/cut_demo.py montage <녹화 디렉터리> <출력.mp4>

의존성: ffmpeg.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = f"{ROOT}/composeApp/src/commonMain/composeResources/font/crimson_text.ttf"

#: 앱의 종이색. 레터박스를 이 색으로 채우면 프레임 전체가 앱의 일부로 보인다.
PAPER = "0xFBF9F4"
INK = "0x1A1A1A"
MUTED = "0x8A8378"

#: Galaxy S23 의 시스템 영역 (픽셀). dumpsys 의 cutout/내비게이션 높이에서 왔다.
CROP_TOP = 98
CROP_BOTTOM = 130

#: 페이지가 나타나는 시각.
#:
#: **파일 안의 시각은 벽시계와 다르다.** `screenrecord` 는 화면이 바뀔 때만 프레임을
#: 쓰므로, 녹화 앞의 정지 구간(촬영 메뉴를 띄워둔 0.8초)은 파일에서 거의 사라진다.
#: 그래서 실측으로 잡는다 — 이 값 이후로는 촬영 메뉴가 한 프레임도 없다.
PAGE_AT = 0.25

OUT_W, OUT_H = 1920, 1080

#: #5 하늘의 변주. (파일 이름, 길이, 구석 라벨) — 합 15.0초.
MONTAGE = [
    ("05-rain", 2.0, "Rain"),
    ("05-snow", 3.0, "Snow"),
    ("05-fog", 2.5, "Fog"),
    ("05-wind", 2.0, "Wind"),
    ("05-dawn", 1.8, "Dawn"),
    ("05-day", 1.8, "Day"),
    ("05-dusk", 1.9, "Dusk"),
]

MONTAGE_SUBTITLE = "Each sky reveals a different page."


def run(args: list[str]) -> None:
    proc = subprocess.run(args, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr[-2500:])
        raise SystemExit(f"ffmpeg 실패: {' '.join(args[:6])} …")


def render_text(text: str, size: int, color: str, cache_dir: str) -> str:
    """
    글자를 투명 PNG 로 굽는다.

    **이 머신의 ffmpeg 에는 drawtext 도 subtitles 도 없다.** 그리고 있었더라도
    CoreText 쪽이 낫다 — 앱 화면과 같은 엔진으로 같은 폰트를 조판하므로
    커닝과 합자가 영상과 화면에서 어긋나지 않는다.
    """
    key = f"{abs(hash((text, size, color))):x}"
    out = f"{cache_dir}/text-{key}.png"
    if not os.path.isfile(out):
        run(["swift", f"{ROOT}/scripts/render_text.swift", FONT,
             str(size), color, out, text])
    return out


def prepare_cut(src: str, dst: str, *, start: float, length: float,
                label: str, cache_dir: str) -> None:
    """한 컷: 잘라내기 → 시스템 영역 제거 → 16:9 종이 위에 올리기 → 구석 라벨."""
    label_png = render_text(label, 34, "#8A8378", cache_dir)
    # `tpad` 로 **마지막 프레임을 늘린다.** 같은 이유다 — 문장이 다 놓인 뒤의 정적은
    # 파일에 들어 있지 않은데, 몽타주는 그 정적을 보여줘야 한다.
    # 뒤에서 `-t` 로 정확히 잘라내므로 여유는 넉넉히 준다.
    # **자르기를 필터 안에서 한다.** `-ss` 로 입력을 탐색하면 출력 타임스탬프에
    # 그 오프셋이 남아 `-t` 가 그만큼 일찍 끊는다 — 컷마다 0.4초씩 짧아졌다.
    # trim + setpts 는 그런 여지가 없다.
    chain = (
        f"[0:v]trim=start={start},setpts=PTS-STARTPTS,"
        f"crop=in_w:in_h-{CROP_TOP + CROP_BOTTOM}:0:{CROP_TOP},"
        f"scale=-2:{OUT_H},"
        f"pad={OUT_W}:{OUT_H}:(ow-iw)/2:0:color={PAPER},"
        f"fps=60,tpad=stop_mode=clone:stop_duration=8[bg];"
        f"[bg][1:v]overlay=x=W*0.06:y=H*0.08,format=yuv420p"
    )
    run([
        "ffmpeg", "-v", "error",
        "-i", src, "-i", label_png, "-filter_complex", chain, "-an",
        "-t", f"{length}",
        "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-y", dst,
    ])


def montage(raw_dir: str, out_path: str) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        parts = []
        for name, length, label in MONTAGE:
            src = f"{raw_dir}/{name}.mp4"
            if not os.path.isfile(src):
                raise SystemExit(f"녹화가 없다: {src}")
            dst = f"{tmp}/{name}.mp4"
            prepare_cut(src, dst, start=PAGE_AT, length=length,
                        label=label, cache_dir=tmp)
            parts.append(dst)
            print(f"  {name:10s} {length:>4.1f}초  ({label})")

        listing = f"{tmp}/parts.txt"
        with open(listing, "w") as f:
            for p in parts:
                f.write(f"file '{p}'\n")

        joined = f"{tmp}/joined.mp4"
        run(["ffmpeg", "-v", "error", "-f", "concat", "-safe", "0",
             "-i", listing, "-c", "copy", "-y", joined])

        # 자막은 이어붙인 뒤에 얹는다. 컷마다 태우면 경계에서 글자가 튄다.
        sub_png = render_text(MONTAGE_SUBTITLE, 46, "#1A1A1A", tmp)
        run(["ffmpeg", "-v", "error", "-i", joined, "-i", sub_png,
             "-filter_complex",
             "[0:v][1:v]overlay=x=(W-w)/2:y=H-H*0.14,format=yuv420p",
             "-c:v", "libx264", "-preset", "medium", "-crf", "16",
             "-y", out_path])


def main(argv: list[str]) -> int:
    if len(argv) < 4 or argv[1] != "montage":
        print(__doc__.strip(), file=sys.stderr)
        return 2
    montage(argv[2], argv[3])
    dur = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", argv[3]], capture_output=True, text=True).stdout.strip()
    print(f"\n{argv[3]}  —  {float(dur):.2f}초 (목표 15.00)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
