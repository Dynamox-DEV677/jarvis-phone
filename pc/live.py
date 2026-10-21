"""Live control from the phone: streams this laptop's screen and replays the phone's taps, typing
and keys. pc/worker.mjs runs it while a live session is open and carries everything, encrypted,
through your Supabase project.

To keep the free plan's data allowance, it never sends a whole video:
  - the screen is cut into 128-pixel tiles and only tiles that changed are sent (typing costs a few KB),
  - a full picture only at the start, every 60 s, or when most of the screen changed,
  - at most `fps` updates a second, and at most two waiting for the phone at once.

The worker talks to it in JSON lines.
  in:  {"t":"tap","x":0.5,"y":0.5}  (x, y are 0..1 of the screen)  {"t":"dbl"} {"t":"right"}
       {"t":"down"} {"t":"move"} {"t":"up"} {"t":"scroll","dy":-3} {"t":"text","s":"hi"} {"t":"key","k":"ctrl+c"}
       {"t":"ack","f":12} {"t":"full"} {"t":"cfg","maxW":1280,"q":50,"fps":4} {"t":"bye"}
  out: {"t":"frame","f":12,"w":1280,"h":800,"full":false,"tiles":[[x,y,w,h,"<jpeg base64>"],...]}
       {"t":"stopped","why":"..."}

Needs:  python -m pip install --user mss pillow numpy uiautomation
"""
import base64
import io
import json
import os
import queue
import sys
import threading
import time
from ctypes import wintypes
import ctypes

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hands  # noqa: E402  (keyboard, mouse and the stop switch; also makes this process DPI-aware)

import mss  # noqa: E402

TILE = 128
cfg = {"maxW": 1280, "q": 50, "fps": 4}
inbox = queue.Queue()


def out(msg):
    sys.stdout.write(json.dumps(msg, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def read_stdin():
    for line in sys.stdin:
        try:
            inbox.put(json.loads(line))
        except ValueError:
            pass
    inbox.put({"t": "bye"})


# ── input from the phone ───────────────────────────────────────────────────────

class Screen:
    def __init__(self, mon):
        self.x0, self.y0, self.w, self.h = mon["left"], mon["top"], mon["width"], mon["height"]

    def at(self, m):
        x = self.x0 + min(max(float(m.get("x", 0.5)), 0), 1) * (self.w - 1)
        y = self.y0 + min(max(float(m.get("y", 0.5)), 0), 1) * (self.h - 1)
        return int(x), int(y)


def mouse(flags):
    hands._send(hands.INPUT(type=0, u=hands.INPUT._U(mi=hands.MOUSEINPUT(dwFlags=flags))))


def handle(m, scr):
    t = m.get("t")
    if t in ("tap", "dbl", "right"):
        x, y = scr.at(m)
        hands.click(x, y, "right" if t == "right" else "left", t == "dbl")
    elif t == "down":
        x, y = scr.at(m)
        hands.user32.SetCursorPos(x, y)
        mouse(0x0002)  # left down
    elif t == "move":
        x, y = scr.at(m)
        hands.user32.SetCursorPos(x, y)
    elif t == "up":
        x, y = scr.at(m)
        hands.user32.SetCursorPos(x, y)
        mouse(0x0004)  # left up
    elif t == "scroll":
        dy = int(m.get("dy", 0))
        dx = int(m.get("dx", 0))
        x, y = scr.at(m) if "x" in m else (None, None)
        if dy:
            hands.scroll("up" if dy > 0 else "down", abs(dy), x, y)
        if dx:
            hands.scroll("right" if dx > 0 else "left", abs(dx), x, y)
    elif t == "text":
        hands.type_text(str(m.get("s", ""))[:2000], newline="enter")
    elif t == "key":
        for combo in str(m.get("k", "")).split():
            hands.press(combo)


# ── the screen ─────────────────────────────────────────────────────────────────

def cursor(draw, x, y, s):
    """A plain arrow where the mouse is (screen grabs don't include it)."""
    pts = [(0, 0), (0, 17), (4.5, 13), (7.5, 20), (10.5, 18.8), (7.5, 12), (13, 12)]
    poly = [(x + px * s, y + py * s) for px, py in pts]
    draw.polygon(poly, fill=(255, 255, 255), outline=(0, 0, 0))


def jpeg(img, q):
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=q, optimize=False, subsampling=2)
    return buf.getvalue()


def main():
    threading.Thread(target=read_stdin, daemon=True).start()
    grabber = mss.MSS() if hasattr(mss, "MSS") else mss.mss()
    mon = grabber.monitors[1]
    scr = Screen(mon)
    prev = None
    size = None
    frame_no = 0
    acked = 0
    last_send = 0.0
    last_full = 0.0
    want_full = True
    busy = 0  # whole-screen changes in a row: a video or a game is playing

    while True:
        # Everything the phone sent since last time: taps, keys, acks...
        try:
            while True:
                m = inbox.get_nowait()
                t = m.get("t")
                if t == "bye":
                    return
                if t == "ack":
                    acked = max(acked, int(m.get("f", 0)))
                elif t == "full":
                    want_full = True
                elif t == "cfg":
                    before = dict(cfg)
                    for k in ("maxW", "q", "fps"):
                        if m.get(k):
                            cfg[k] = int(m[k])
                    cfg["maxW"] = min(max(cfg["maxW"], 480), 1920)
                    cfg["q"] = min(max(cfg["q"], 20), 85)
                    cfg["fps"] = min(max(cfg["fps"], 1), 8)
                    if cfg != before:
                        want_full, size = True, None
                else:
                    try:
                        hands.check_stop()
                        handle(m, scr)
                        last_send = 0.0  # show the result of the tap straight away
                    except hands.Stopped:
                        out({"t": "stopped", "why": "Stopped at the laptop (Ctrl+Alt+J)."})
                        return
        except queue.Empty:
            pass
        try:
            hands.check_stop()
        except hands.Stopped:
            out({"t": "stopped", "why": "Stopped at the laptop (Ctrl+Alt+J)."})
            return

        now = time.monotonic()
        # At most two updates waiting for the phone (or a 2 s grace, in case an ack got lost).
        if frame_no - acked >= 2 and now - last_send < 2.0:
            time.sleep(0.02)
            continue
        # Motion mode: while most of the screen keeps changing, fewer and softer pictures - a
        # playing video would otherwise use the month's data in an hour or two.
        fps = min(cfg["fps"], 2) if busy >= 3 else cfg["fps"]
        if now - last_send < 1.0 / fps:
            time.sleep(0.01)
            continue

        shot = grabber.grab(mon)
        img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        scale = min(1.0, (min(cfg["maxW"], 960) if busy >= 3 else cfg["maxW"]) / img.width)
        if scale < 1:
            img = img.resize((round(img.width * scale), round(img.height * scale)), Image.BILINEAR)
        pt = wintypes.POINT()
        hands.user32.GetCursorPos(ctypes.byref(pt))
        cursor(ImageDraw.Draw(img), (pt.x - scr.x0) * scale, (pt.y - scr.y0) * scale, max(0.7, scale * 1.2))
        arr = np.asarray(img)
        W, H = img.size

        full = want_full or size != (W, H) or now - last_full > 60
        tiles = []
        if not full:
            changed = []
            for ty in range(0, H, TILE):
                for tx in range(0, W, TILE):
                    a = arr[ty:ty + TILE, tx:tx + TILE]
                    if not np.array_equal(a, prev[ty:ty + TILE, tx:tx + TILE]):
                        changed.append((tx, ty, a.shape[1], a.shape[0]))
            total = ((W + TILE - 1) // TILE) * ((H + TILE - 1) // TILE)
            if not changed:
                if now - last_send < 5:
                    time.sleep(1.0 / cfg["fps"])
                    continue
                # Nothing changed for a while: a tiny "still here" update (one tile).
                changed = [(0, 0, min(TILE, W), min(TILE, H))]
            if len(changed) > total * 0.5:
                full = True
                busy += 1
            else:
                busy = 0
                sent = 0
                for tx, ty, tw, th in changed:
                    data = jpeg(img.crop((tx, ty, tx + tw, ty + th)), cfg["q"])
                    sent += len(data)
                    if sent > 140_000:  # too much changed for one message: send the whole picture instead
                        full, tiles = True, []
                        break
                    tiles.append([tx, ty, tw, th, base64.b64encode(data).decode()])
        if full:
            q = max(25, cfg["q"] - 15) if busy >= 3 else cfg["q"]
            data = jpeg(img, q)
            while len(data) > 150_000 and q > 20:  # stay well under Supabase's 256 KB per message, encrypted
                q -= 10
                data = jpeg(img, q)
            tiles = [[0, 0, W, H, base64.b64encode(data).decode()]]
            last_full = now
            want_full = False
        frame_no += 1
        out({"t": "frame", "f": frame_no, "w": W, "h": H, "sw": scr.w, "sh": scr.h, "full": full, "tiles": tiles})
        prev, size, last_send = arr, (W, H), now


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        out({"t": "stopped", "why": f"{type(e).__name__}: {e}"})
