#!/usr/bin/env python3
"""
촬영용 Android 기기 조작.

데모 영상의 컷은 "촬영 메뉴를 이렇게 맞추고, 이렇게 움직이고, 몇 초 녹화한다" 로
정의된다. 그걸 손으로 하면 컷마다 결과가 달라지고, 마음에 안 드는 컷 하나를 다시
찍으려면 처음부터 다시 맞춰야 한다. 여기서는 컷이 **함수 한 번**이다.

**좌표를 박지 않는다.** 이 앱의 문장은 길이가 제각각이라 푸터 위치가 컷마다 움직인다.
대신 접근성 트리(uiautomator)에서 글자로 찾는다 — Compose 가 단어마다 노드를 내므로
`The shelf` 같은 링크는 정확히 잡힌다.

사용:
    python3 scripts/demo_drive.py dump              # 화면의 글자와 좌표
    python3 scripts/demo_drive.py tap "The shelf"
    python3 scripts/demo_drive.py shot out.png

기기는 `ALMANAC_DEVICE` 환경변수로 고른다. 안 주면 붙어 있는 기기가 하나일 때만 쓴다.
의존성 없음 (Python 3 표준 라이브러리 + adb).
"""

from __future__ import annotations

import html
import os
import re
import subprocess
import sys
import time

PKG = "com.dogdduddy.almanac"
ACTIVITY = f"{PKG}/.MainActivity"

# Samsung 등 다중 사용자 기기에서 셸 기본 계정이 막히는 경우가 있어 항상 명시한다.
USER = "0"

#: 화면 맨 아래 이만큼은 시스템 내비게이션 바의 몫으로 본다 (픽셀).
#: 여기 걸친 대상을 그냥 누르면 탭이 앱이 아니라 시스템으로 간다.
SYSTEM_BAR_MARGIN = 220


class Device:
    SYSTEM_BAR_MARGIN = SYSTEM_BAR_MARGIN

    def __init__(self, serial: str | None = None):
        self.serial = serial or os.environ.get("ALMANAC_DEVICE") or self._only_device()

    @staticmethod
    def _only_device() -> str:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
        found = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]
        if len(found) != 1:
            raise SystemExit(
                f"기기를 특정할 수 없다 ({len(found)}대). ALMANAC_DEVICE 로 지정할 것.\n" + out
            )
        return found[0]

    # ---- 기본 ---------------------------------------------------------------

    def sh(self, *args: str, **kw) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["adb", "-s", self.serial, "shell", *args],
            capture_output=True, text=True, **kw,
        )

    def launch(self) -> None:
        self.sh("am", "start", "--user", USER, "-n", ACTIVITY)

    def stop(self) -> None:
        """앱을 죽인다. **촬영용 고정이 메모리에만 살기 때문에** 여기서 전부 풀린다."""
        self.sh("am", "force-stop", "--user", USER, PKG)

    def screenshot(self, path: str) -> None:
        raw = subprocess.run(
            ["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True
        ).stdout
        with open(path, "wb") as f:
            f.write(raw)

    # ---- 화면에서 글자 찾기 ---------------------------------------------------

    def dump(self) -> list[tuple[str, int, int]]:
        """(글자, 중심 x, 중심 y). 화면에 보이는 것만."""
        self.sh("uiautomator", "dump", "/sdcard/ui.xml")
        xml = self.sh("cat", "/sdcard/ui.xml").stdout
        out = []
        for text, x1, y1, x2, y2 in re.findall(
            r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
        ):
            # XML 이스케이프를 되돌린다. `Evening & night` 이 덤프에는
            # `Evening &amp; night` 로 들어와 글자로 찾으면 못 맞춘다.
            label = html.unescape(text)
            if label.strip():
                out.append((label, (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2))
        return out

    def find(self, text: str, timeout: float = 6.0, scrolls: int = 4) -> tuple[int, int]:
        """
        글자가 나타날 때까지 기다렸다가 중심 좌표를 준다.

        화면에 없으면 **끌어올려 가며 찾는다.** 촬영 메뉴는 날씨 8종 + 시간대 3종이라
        한 화면에 다 안 들어오고, 기기마다 어디서 잘리는지가 다르다.
        선택 표시가 붙은 줄(`Snow ·`)도 같은 항목으로 본다.
        """
        wanted = (text, f"{text} ·")
        for attempt in range(scrolls + 1):
            deadline = time.time() + (timeout if attempt == 0 else 1.0)
            while True:
                for label, cx, cy in self.dump():
                    if label in wanted:
                        return cx, cy
                if time.time() > deadline:
                    break
                time.sleep(0.3)
            if attempt == 0:
                # 위쪽에 있을 수도 있다. 한 번 맨 위로 되돌리고 다시 훑는다 —
                # 촬영 메뉴는 오르내리며 쓰는 화면이라 어디서 시작할지 알 수 없다.
                self.scroll_to_top()
            elif attempt < scrolls:
                self.scroll_up()
        raise SystemExit(f"화면에서 못 찾았다: {text!r}")

    # ---- 동작 ---------------------------------------------------------------

    def tap(self, x: int, y: int) -> None:
        self.sh("input", "tap", str(x), str(y))

    def tap_text(self, text: str, settle: float = 0.8) -> None:
        """
        글자를 찾아 누른다. **화면 아래 시스템 영역에 걸린 대상은 먼저 끌어올린다.**

        Samsung 3버튼 내비게이션 바가 화면 맨 아래를 덮는데, 그 위의 앱 요소를 누르면
        탭이 시스템 UI 로 간다 — 실제로 최근 앱 화면이 열려 촬영이 끊겼다.
        About 화면의 `Demo controls` 처럼 맨 밑에 있는 줄이 정확히 그 자리다.
        """
        x, y = self.find(text)
        _, h = self.size()
        if y > h - self.SYSTEM_BAR_MARGIN:
            self.scroll_up()
            x, y = self.find(text)
        self.tap(x, y)
        time.sleep(settle)

    def scroll_to_top(self, times: int = 4) -> None:
        """맨 위로 되돌린다. 목록 화면에서 위쪽 항목을 찾기 전에 부른다."""
        w, h = self.size()
        for _ in range(times):
            self.swipe(w // 2, int(h * 0.35), w // 2, int(h * 0.8), 300)
            time.sleep(0.35)
        time.sleep(0.6)

    def scroll_up(self, amount: float = 0.35) -> None:
        """내용을 위로 끌어올린다. 스크롤이 없는 화면에서는 아무 일도 없다."""
        w, h = self.size()
        self.swipe(w // 2, int(h * 0.75), w // 2, int(h * (0.75 - amount)), 400)
        time.sleep(1.0)

    def type_text(self, text: str) -> None:
        self.sh("input", "text", text)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 450) -> None:
        self.sh("input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))

    def turn_page_back(self, ms: int = 450) -> None:
        """과거 쪽으로 한 장. 오른쪽으로 미는 것이 역방향이다 (reverseLayout)."""
        w, h = self.size()
        self.swipe(int(w * 0.12), int(h * 0.45), int(w * 0.88), int(h * 0.45), ms)

    # ---- 녹화 ---------------------------------------------------------------

    def record(self, out_path: str, action, *, bitrate: str = "20000000",
               lead: float = 1.0, tail: float = 1.0) -> str:
        """
        화면을 녹화하면서 [action] 을 실행한다.

        앞뒤로 여유(lead/tail)를 둔다 — 편집에서 잘라내려면 남는 프레임이 있어야 하고,
        녹화가 시작되기 전에 동작이 끝나버리는 사고를 막는다.

        **`--time-limit` 을 쓰지 않는다.** 동작이 언제 끝날지는 동작이 안다.
        끝나면 기기 쪽 screenrecord 에 SIGINT 를 보내 파일을 닫게 한다 —
        adb 클라이언트만 죽이면 기기에 프로세스가 남아 다음 컷을 망친다.
        """
        remote = "/sdcard/almanac-cut.mp4"
        self.sh("rm", "-f", remote)
        proc = subprocess.Popen(
            ["adb", "-s", self.serial, "shell", "screenrecord",
             "--bit-rate", bitrate, remote],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        try:
            time.sleep(lead)
            action(self)
            time.sleep(tail)
        finally:
            self.sh("pkill", "-INT", "screenrecord")
            proc.wait(timeout=20)
            # 기기가 무빙 헤더를 쓰므로 파일이 닫힐 때까지 잠깐 기다린다.
            time.sleep(1.5)

        subprocess.run(["adb", "-s", self.serial, "pull", remote, out_path],
                       capture_output=True, text=True, check=True)
        self.sh("rm", "-f", remote)
        return out_path

    def size(self) -> tuple[int, int]:
        m = re.search(r"(\d+)x(\d+)", self.sh("wm", "size").stdout)
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 2340)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    d = Device()
    cmd = argv[1]
    if cmd == "dump":
        for text, cx, cy in d.dump():
            print(f"{text[:40]:42s} ({cx},{cy})")
    elif cmd == "tap":
        d.tap_text(argv[2])
    elif cmd == "shot":
        d.screenshot(argv[2])
    elif cmd == "launch":
        d.launch()
    elif cmd == "stop":
        d.stop()
    else:
        print(f"모르는 명령: {cmd}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
