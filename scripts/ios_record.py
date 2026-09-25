#!/usr/bin/env python3
"""
데모 영상 컷 녹화 (iOS 시뮬레이터).

Android 쪽(`record_demo.py`)과 나누는 이유는 조작 수단이 다르기 때문이다.
adb 에는 `input tap` 이 있지만 **`simctl` 에는 탭이 없다** — 시뮬레이터의 터치는
바깥(iOS Simulator 제어 도구)에서 들어온다. 그래서 여기서는 **녹화와 앱 수명만**
맡고, 화면을 누르는 일은 부르는 쪽이 한다.

그 경계 덕분에 컷은 여전히 결정적이다. 녹화는 `simctl` 이 시작·종료 시각을 쥐고,
탭은 녹화 밖(앞) 또는 `start`/`stop` 사이에서만 일어난다.

사용:
    python3 scripts/ios_record.py cold <출력.mp4> [초]   # #1 찬 시작: 껐다 켜며 녹화
    python3 scripts/ios_record.py hold <출력.mp4> [초]   # 지금 화면을 그대로 녹화
    python3 scripts/ios_record.py start <출력.mp4>       # 녹화 시작 (탭은 바깥에서)
    python3 scripts/ios_record.py stop                   # 녹화 종료
    python3 scripts/ios_record.py relaunch               # 껐다 켜기 (고정이 풀린다)

의존성: Xcode 명령줄 도구.
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import time

BUNDLE = "com.dogdduddy.almanac"

#: 녹화 파이프가 첫 프레임을 뱉기까지. 이보다 일찍 화면을 건드리면 앞이 잘린다.
WARMUP = 1.2

#: 끝에 남기는 여유. 편집에서 잘라낸다.
TAIL = 0.6

#: 실행 중인 녹화의 pid. `start` 가 남기고 `stop` 이 읽는다.
PIDFILE = "/tmp/almanac-ios-record.pid"


def sim(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["xcrun", "simctl", *args],
                          capture_output=True, text=True)


def booted() -> str:
    out = sim("list", "devices", "booted").stdout
    ids = [l.split("(")[1].split(")")[0]
           for l in out.splitlines() if "(Booted)" in l]
    if len(ids) != 1:
        raise SystemExit(f"부팅된 시뮬레이터가 하나여야 한다 ({len(ids)}대).\n{out}")
    return ids[0]


def start_recording(out_path: str) -> subprocess.Popen:
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    if os.path.exists(out_path):
        os.remove(out_path)
    # **h264 로 못 박는다.** 기본 코덱은 기기·OS 마다 달라서, 나중에 컷을 이어붙일 때
    # 하나만 hevc 면 `concat -c copy` 가 조용히 실패한다.
    # `--mask ignored` 는 둥근 모서리를 검게 칠하지 않는다 — 어차피 위아래를 자른다.
    proc = subprocess.Popen(
        ["xcrun", "simctl", "io", booted(), "recordVideo",
         "--codec", "h264", "--mask", "ignored", "--force", out_path],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    time.sleep(WARMUP)
    return proc


def stop_recording(proc: subprocess.Popen, out_path: str) -> str:
    time.sleep(TAIL)
    # SIGINT 여야 한다. 죽이면 파일이 안 닫혀 재생할 수 없는 조각이 남는다.
    proc.send_signal(signal.SIGINT)
    proc.wait(timeout=30)
    for _ in range(40):
        if os.path.isfile(out_path) and os.path.getsize(out_path) > 0:
            break
        time.sleep(0.25)
    return out_path


def relaunch() -> None:
    """껐다 켠다. **촬영 고정은 메모리에만 살아서 여기서 전부 풀린다.**"""
    sim("terminate", booted(), BUNDLE)
    time.sleep(0.6)
    sim("launch", booted(), BUNDLE)


def cold(out_path: str, seconds: float) -> None:
    """#1 찬 시작 — 앱이 처음 열리는 그 장면. 녹화가 먼저 돌고 앱이 나중에 뜬다."""
    sim("terminate", booted(), BUNDLE)
    time.sleep(1.0)
    proc = start_recording(out_path)
    sim("launch", booted(), BUNDLE)
    time.sleep(seconds)
    stop_recording(proc, out_path)


def hold(out_path: str, seconds: float) -> None:
    """지금 화면을 그대로 녹화한다. 화면을 맞추는 일은 부르는 쪽이 이미 했다."""
    proc = start_recording(out_path)
    time.sleep(seconds)
    stop_recording(proc, out_path)


def probe(path: str) -> str:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height:format=duration", "-of", "csv=p=0", path],
        capture_output=True, text=True).stdout.split()
    return " ".join(out)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    cmd = argv[1]

    if cmd == "relaunch":
        relaunch()
        print("다시 띄웠다. 촬영 고정은 전부 풀렸다.")
    elif cmd == "cold" and len(argv) >= 3:
        seconds = float(argv[3]) if len(argv) > 3 else 9.0
        cold(argv[2], seconds)
        print(f"{argv[2]}  —  {probe(argv[2])}")
    elif cmd == "hold" and len(argv) >= 3:
        seconds = float(argv[3]) if len(argv) > 3 else 6.0
        hold(argv[2], seconds)
        print(f"{argv[2]}  —  {probe(argv[2])}")
    elif cmd == "start" and len(argv) >= 3:
        proc = start_recording(argv[2])
        with open(PIDFILE, "w") as f:
            f.write(f"{proc.pid}\n{argv[2]}\n")
        print(f"녹화 중 (pid {proc.pid}). 이제 화면을 조작할 것.")
    elif cmd == "stop":
        if not os.path.isfile(PIDFILE):
            raise SystemExit("도는 녹화가 없다.")
        pid, out_path = open(PIDFILE).read().split("\n")[:2]
        time.sleep(TAIL)
        os.kill(int(pid), signal.SIGINT)
        for _ in range(60):
            try:
                os.kill(int(pid), 0)
            except OSError:
                break
            time.sleep(0.25)
        os.remove(PIDFILE)
        print(f"{out_path}  —  {probe(out_path)}")
    else:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
