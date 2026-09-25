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
    python3 scripts/cut_demo.py cut       <컷 이름> <녹화 뿌리> <컷 디렉터리>
    python3 scripts/cut_demo.py montage   <녹화 디렉터리> <출력.mp4>   # #5 조립
    python3 scripts/cut_demo.py subtitles <출력 디렉터리> [연도차]      # 자막 8장
    python3 scripts/cut_demo.py status    <컷 디렉터리>                # 진행 상황
    python3 scripts/cut_demo.py master    <컷 디렉터리> <출력.mp4>      # 전체 이어붙이기

의존성: ffmpeg, swift(자막 렌더).
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

#: 기기별 시스템 영역 (위, 아래 픽셀). **녹화 해상도로 기기를 알아낸다** —
#: 파일이 스스로 어디서 왔는지 말하므로 컷마다 기기를 적어 둘 필요가 없다.
#:
#: 잘라내는 이유는 두 가지다. 상태바의 시각이 #5 의 고정 시간대와 어긋나고,
#: **Android 와 iOS 를 섞어 쓰기 때문이다** — 시스템 영역을 걷어내면 남는 것은
#: 앱 화면뿐이라 두 기기의 컷이 한 영상 안에서 따로 놀지 않는다.
DEVICE_CROP = {
    (1080, 2340): (98, 130),    # Galaxy S23 — cutout / 내비게이션 바
    (1206, 2622): (186, 102),   # iPhone 17 Pro — 상태바 62pt / 홈 인디케이터 34pt
}

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

#: 상한. 대회 규정이므로 사람이 기억할 일이 아니라 빌드가 막을 일이다.
MAX_SECONDS = 120.0

#: 콘티 전체 (docs/product/demo-video.md). 합 105초 = 1분 45초.
#: 한 줄을 고치면 뒤가 저절로 밀린다 — 손으로 맞추지 않는다.
TIMELINE = [
    ("01-cold-open",   7.0, 1),
    ("02-pitch",       7.0, 2),
    ("03-matching",   12.0, 3),
    ("04-page-turn",  12.0, 4),
    ("05-montage",    15.0, None),   # 자막은 montage 단계에서 이미 태운다
    ("06-widgets",    15.0, None),   # 화면이 스스로 설명한다
    ("07-kmp",        14.0, 6),
    ("08-purchase",   16.0, 7),
    ("09-outro",       7.0, 8),
]

#: 컷마다 어느 녹화의 **어디서부터** 쓰는가. (원본 경로, 시작 시각)
#:
#: 시작 시각이 표에 있는 이유는 녹화가 컷보다 길기 때문이다 — 앱이 뜨기 전의
#: 홈 화면이나, 촬영 메뉴를 닫는 손이 앞에 붙어 있다. 그 값을 눈으로 한 번 찾고
#: 여기 적어 두면 그 컷은 다시 돌려도 같은 자리에서 시작한다.
#:
#: **`05-montage` 는 여기 없다.** 그건 일곱 조각을 이어 만드는 것이라 `montage` 가 맡는다.
SOURCES = {
    "01-cold-open": ("raw-ios/01-cold-open.mp4", 1.78),
    "02-pitch":     ("raw-ios/02-pitch.png",     0.00),
    "03-matching":  ("raw-ios/03-matching.mp4",  1.78),
    "04-page-turn": ("raw-ios/04-page-turn.mp4", 3.60),
    "09-outro":     ("raw-ios/09-outro.png",     0.00),
}

#: 자막 8줄. `{N}` 은 그날 찍힌 실제 연도 차이로 바꾼다.
SUBTITLES = {
    1: "This weather, written {N} years ago.",
    2: "Almanac matches today's sky with literature.",
    3: "Weather chooses today's page.",
    4: "Turn back through time.",
    5: "Each sky reveals a different page.",
    6: "One Kotlin core. Five surfaces.",
    7: "One purchase opens the whole shelf.",
    8: "Look up. Read back.",
}


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


def frame_size(path: str) -> tuple[int, int]:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height", "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True).stdout.strip()
    w, h = out.split("x")[:2]
    return int(w), int(h)


def crop_for(path: str) -> tuple[int, int]:
    """이 녹화의 시스템 영역. **모르는 기기면 만들지 않는다** — 잘못 자르느니 멈춘다."""
    size = frame_size(path)
    if size[0] >= size[1]:
        return (0, 0)   # 가로 프레임이면 기기 화면이 아니다 (구조 카드 등)
    if size not in DEVICE_CROP:
        known = ", ".join(f"{w}x{h}" for w, h in DEVICE_CROP)
        raise SystemExit(
            f"{size[0]}x{size[1]} 의 시스템 영역을 모른다 ({path}).\n"
            f"아는 기기: {known}. DEVICE_CROP 에 한 줄 더할 것.")
    return DEVICE_CROP[size]


def prepare_cut(src: str, dst: str, *, start: float, length: float,
                label: str | None, cache_dir: str) -> None:
    """한 컷: 잘라내기 → 시스템 영역 제거 → 16:9 종이 위에 올리기 → 구석 라벨.

    원본은 녹화일 수도 **정지화면일 수도** 있다. 화면이 멈춰 있는 컷(#2 #9, #7 의
    구조 카드)은 녹화로 담기지 않는다 — `simctl` 도 `screenrecord` 도 화면이 바뀔 때만
    프레임을 쓰기 때문이다. 8초를 녹화해도 파일은 0.07초로 나온다. 그런 컷은
    스크린샷 한 장이 원본이고, 움직임은 편집이 만든다.
    """
    crop_top, crop_bottom = crop_for(src)
    still = src.lower().endswith((".png", ".jpg", ".jpeg"))
    # `tpad` 로 **마지막 프레임을 늘린다.** 같은 이유다 — 문장이 다 놓인 뒤의 정적은
    # 파일에 들어 있지 않은데, 몽타주는 그 정적을 보여줘야 한다.
    # 뒤에서 `-t` 로 정확히 잘라내므로 여유는 넉넉히 준다.
    # **자르기를 필터 안에서 한다.** `-ss` 로 입력을 탐색하면 출력 타임스탬프에
    # 그 오프셋이 남아 `-t` 가 그만큼 일찍 끊는다 — 컷마다 0.4초씩 짧아졌다.
    # trim + setpts 는 그런 여지가 없다.
    # 정지화면은 잘라낼 앞부분이 없다. `-loop` 이 길이를 만들고 `-t` 가 끊는다.
    head = "[0:v]" if still else f"[0:v]trim=start={start},setpts=PTS-STARTPTS,"
    base = (
        f"{head}"
        f"crop=in_w:in_h-{crop_top + crop_bottom}:0:{crop_top},"
        f"scale=-2:{OUT_H},"
        f"pad={OUT_W}:{OUT_H}:(ow-iw)/2:0:color={PAPER},"
        f"fps=60,tpad=stop_mode=clone:stop_duration=8"
    )
    inputs = (["-loop", "1", "-framerate", "60", "-t", f"{length + 1}", "-i", src]
              if still else ["-i", src])
    if label:
        inputs += ["-i", render_text(label, 34, "#8A8378", cache_dir)]
        chain = f"{base}[bg];[bg][1:v]overlay=x=W*0.06:y=H*0.08,format=yuv420p"
    else:
        chain = f"{base},format=yuv420p"
    run([
        "ffmpeg", "-v", "error", *inputs, "-filter_complex", chain, "-an",
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


def cut(name: str, raw_root: str, parts_dir: str) -> None:
    """컷 하나를 원본에서 잘라낸다. 길이는 콘티가 정하고, 시작은 `SOURCES` 가 정한다."""
    lengths = {n: length for n, length, _ in TIMELINE}
    if name not in lengths:
        raise SystemExit(f"콘티에 없는 컷: {name}\n있는 것: " + ", ".join(lengths))
    if name not in SOURCES:
        raise SystemExit(f"원본이 어디인지 모른다: {name} (SOURCES 에 적을 것)")

    rel, start = SOURCES[name]
    src = os.path.join(raw_root, rel)
    if not os.path.isfile(src):
        raise SystemExit(f"녹화가 없다: {src}")

    os.makedirs(parts_dir, exist_ok=True)
    dst = f"{parts_dir}/{name}.mp4"
    with tempfile.TemporaryDirectory() as tmp:
        prepare_cut(src, dst, start=start, length=lengths[name],
                    label=None, cache_dir=tmp)
    made = duration(dst)
    mark = "✓" if abs(made - lengths[name]) < 0.15 else "!"
    print(f"  {mark} {name}  {lengths[name]:.1f}초 (실제 {made:.2f})  ← {rel} @ {start}초")


def duration(path: str) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip()
    return float(out) if out else 0.0


def subtitles(out_dir: str, years: str = "179") -> None:
    """자막 8줄을 전부 PNG 로 굽는다. 편집기에서 바로 얹을 수 있다."""
    os.makedirs(out_dir, exist_ok=True)
    for number, text in SUBTITLES.items():
        line = text.replace("{N}", years)
        path = render_text(line, 46, "#1A1A1A", out_dir)
        named = f"{out_dir}/subtitle-{number}.png"
        os.replace(path, named)
        print(f"  {number}. {line}")


def status(parts_dir: str) -> None:
    """어느 컷이 있고 어느 컷이 비었는지. 촬영 중에 계속 보게 된다."""
    total = 0.0
    for name, length, _ in TIMELINE:
        path = f"{parts_dir}/{name}.mp4"
        if os.path.isfile(path):
            actual = duration(path)
            mark = "✓" if abs(actual - length) < 0.15 else "!"
            print(f"  {mark} {name:14s} {length:>5.1f}초  (실제 {actual:.2f})")
            total += actual
        else:
            print(f"  · {name:14s} {length:>5.1f}초  — 없음")
    planned = sum(length for _, length, _ in TIMELINE)
    print(f"\n  찍은 것 {total:.2f}초 / 계획 {planned:.1f}초 / 상한 {MAX_SECONDS:.0f}초")


def master(parts_dir: str, out_path: str) -> None:
    """완성된 컷들을 하나로 잇는다. **2분을 넘기면 만들지 않는다.**"""
    missing = [n for n, _, _ in TIMELINE if not os.path.isfile(f"{parts_dir}/{n}.mp4")]
    if missing:
        raise SystemExit("아직 없는 컷: " + ", ".join(missing))

    planned = sum(length for _, length, _ in TIMELINE)
    if planned > MAX_SECONDS:
        raise SystemExit(f"콘티가 이미 상한을 넘는다: {planned:.1f}초 > {MAX_SECONDS:.0f}초")

    with tempfile.TemporaryDirectory() as tmp:
        listing = f"{tmp}/parts.txt"
        with open(listing, "w") as f:
            for name, _, _ in TIMELINE:
                f.write(f"file '{parts_dir}/{name}.mp4'\n")
        run(["ffmpeg", "-v", "error", "-f", "concat", "-safe", "0",
             "-i", listing, "-c", "copy", "-y", out_path])

    made = duration(out_path)
    if made > MAX_SECONDS:
        os.remove(out_path)
        raise SystemExit(f"2분을 넘었다: {made:.2f}초. 만들지 않는다.")
    print(f"\n{out_path}  —  {made:.2f}초 (상한 {MAX_SECONDS:.0f})")


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    cmd = argv[1]
    if cmd == "cut" and len(argv) >= 5:
        cut(argv[2], argv[3], argv[4])
    elif cmd == "montage" and len(argv) >= 4:
        montage(argv[2], argv[3])
        print(f"\n{argv[3]}  —  {duration(argv[3]):.2f}초 (목표 15.00)")
    elif cmd == "subtitles":
        subtitles(argv[2], argv[3] if len(argv) > 3 else "179")
    elif cmd == "status":
        status(argv[2])
    elif cmd == "master" and len(argv) >= 4:
        master(argv[2], argv[3])
    else:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
