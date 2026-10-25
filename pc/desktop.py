"""JARVIS on the laptop: say "Hey Jarvis" (or press Ctrl+Alt+J) and it answers out loud, with the
same orange glow round the screen edges as on the phone.

pc/worker.mjs runs this and does the thinking: your JARVIS agent in Antigravity, in the same
conversation as the phone. This file does everything you see and hear on the laptop:
  - listens for the wake word, offline (Vosk, the same model as the phone),
  - records the question and turns it into text on this laptop (faster-whisper),
  - speaks the answer in JARVIS's voice (edge-tts) while the glow blinks on each word.

It talks to the worker in JSON lines.
  out: {"event":"ready"}  {"event":"ask","id":1,"text":"..."}  {"event":"log","text":"..."}
  in:  {"cmd":"status","text":"Searching the web..."}  {"cmd":"reply","id":1,"text":"..."}
       {"cmd":"hear","text":"..."} (as if spoken - for testing)

Try the glow on its own:  python desktop.py --demo
Needs:  python -m pip install --user vosk sounddevice faster-whisper pillow edge-tts
"""
import asyncio
import collections
import ctypes
import gc
import json
import math
import os
import queue
import re
import sys
import tempfile
import threading
import time
import urllib.request
import wave
import winsound
import zipfile
from ctypes import wintypes

import numpy as np

HOME = os.path.expanduser("~")
MODELS = os.path.join(HOME, ".jarvis-phone", "models")
WAKE_MODEL = "vosk-model-small-en-in-0.4"  # small, offline, and it knows the word "jarvis"
WAKE_URL = f"https://alphacephei.com/vosk/models/{WAKE_MODEL}.zip"
WHISPER = "base.en"
RATE = 16000
HANDS_STOP = os.path.join(HOME, ".jarvis-phone", "hands.stop")  # see pc/hands.py
STOP_WORDS = {"stop", "cancel", "never mind", "nevermind", "nothing", "shut up", "be quiet", "that's all", "thats all", "thank you", "thanks"}


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv[:-1] else default


VOICE = arg("--voice", "en-GB-RyanNeural")

# ── talking to the worker ──────────────────────────────────────────────────────

_out = threading.Lock()


def emit(event, **kw):
    with _out:
        sys.stdout.write(json.dumps({"event": event, **kw}) + "\n")
        sys.stdout.flush()


def log(text):
    emit("log", text=text)


# ── Win32: click-through windows we draw ourselves ─────────────────────────────

user32 = ctypes.WinDLL("user32", use_last_error=True)
gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
winmm = ctypes.WinDLL("winmm")
LRESULT = ctypes.c_ssize_t
WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)


class WNDCLASSEXW(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.UINT), ("style", wintypes.UINT), ("lpfnWndProc", WNDPROC), ("cbClsExtra", ctypes.c_int),
                ("cbWndExtra", ctypes.c_int), ("hInstance", wintypes.HINSTANCE), ("hIcon", wintypes.HICON), ("hCursor", wintypes.HANDLE),
                ("hbrBackground", wintypes.HBRUSH), ("lpszMenuName", wintypes.LPCWSTR), ("lpszClassName", wintypes.LPCWSTR), ("hIconSm", wintypes.HICON)]


class BLENDFUNCTION(ctypes.Structure):
    _fields_ = [("BlendOp", ctypes.c_ubyte), ("BlendFlags", ctypes.c_ubyte), ("SourceConstantAlpha", ctypes.c_ubyte), ("AlphaFormat", ctypes.c_ubyte)]


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG), ("biHeight", wintypes.LONG), ("biPlanes", wintypes.WORD),
                ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD), ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
                ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD), ("biClrImportant", wintypes.DWORD)]


class BITMAPINFO(ctypes.Structure):
    _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", wintypes.DWORD * 3)]


user32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.DefWindowProcW.restype = LRESULT
user32.RegisterClassExW.argtypes = [ctypes.POINTER(WNDCLASSEXW)]
user32.CreateWindowExW.argtypes = [wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                   ctypes.c_int, wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID]
user32.CreateWindowExW.restype = wintypes.HWND
user32.UpdateLayeredWindow.argtypes = [wintypes.HWND, wintypes.HDC, ctypes.POINTER(wintypes.POINT), ctypes.POINTER(wintypes.SIZE), wintypes.HDC,
                                       ctypes.POINTER(wintypes.POINT), wintypes.COLORREF, ctypes.POINTER(BLENDFUNCTION), wintypes.DWORD]
user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.UINT]
user32.GetDC.argtypes = [wintypes.HWND]
user32.GetDC.restype = wintypes.HDC
user32.ReleaseDC.argtypes = [wintypes.HWND, wintypes.HDC]
user32.GetMessageW.argtypes = [ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT]
user32.PeekMessageW.argtypes = [ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT, wintypes.UINT]
user32.TranslateMessage.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.DispatchMessageW.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.PostThreadMessageW.argtypes = [wintypes.DWORD, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.SetTimer.argtypes = [wintypes.HWND, ctypes.c_size_t, wintypes.UINT, ctypes.c_void_p]
user32.SetTimer.restype = ctypes.c_size_t
user32.KillTimer.argtypes = [wintypes.HWND, ctypes.c_size_t]
user32.RegisterHotKey.argtypes = [wintypes.HWND, ctypes.c_int, wintypes.UINT, wintypes.UINT]
gdi32.CreateCompatibleDC.argtypes = [wintypes.HDC]
gdi32.CreateCompatibleDC.restype = wintypes.HDC
gdi32.CreateDIBSection.argtypes = [wintypes.HDC, ctypes.POINTER(BITMAPINFO), wintypes.UINT, ctypes.POINTER(ctypes.c_void_p), wintypes.HANDLE, wintypes.DWORD]
gdi32.CreateDIBSection.restype = wintypes.HBITMAP
gdi32.SelectObject.argtypes = [wintypes.HDC, wintypes.HGDIOBJ]
gdi32.SelectObject.restype = wintypes.HGDIOBJ
gdi32.DeleteObject.argtypes = [wintypes.HGDIOBJ]
kernel32.GetModuleHandleW.restype = wintypes.HMODULE
winmm.mciSendStringW.argtypes = [wintypes.LPCWSTR, wintypes.LPWSTR, wintypes.UINT, wintypes.HANDLE]

WS_POPUP = 0x80000000
EX_STYLE = 0x00080000 | 0x00000020 | 0x00000008 | 0x00000080 | 0x08000000  # layered, click-through, topmost, no taskbar, never focused
WM_TIMER, WM_HOTKEY, WM_APP, WM_QUIT = 0x0113, 0x0312, 0x8000, 0x0012
HWND_TOPMOST = wintypes.HWND(-1)

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)  # real pixels, so the glow is sharp at 150% scaling
except Exception:
    user32.SetProcessDPIAware()

HINST = kernel32.GetModuleHandleW(None)
_wndproc = WNDPROC(lambda h, m, w, l: user32.DefWindowProcW(h, m, w, l))
_wc = WNDCLASSEXW(cbSize=ctypes.sizeof(WNDCLASSEXW), lpfnWndProc=_wndproc, hInstance=HINST, lpszClassName="JarvisGlow")
user32.RegisterClassExW(ctypes.byref(_wc))


class Layer:
    """One click-through, always-on-top window whose pixels (with alpha) we draw ourselves."""

    def __init__(self):
        self.hwnd = user32.CreateWindowExW(EX_STYLE, "JarvisGlow", "JARVIS", WS_POPUP, 0, 0, 1, 1, None, None, HINST, None)
        self.memdc = None
        self.bmp = None
        self.w = self.h = 1
        self.shown = False

    def alloc(self, w, h):
        """A w x h drawing surface. Returns its pixels as an (h, w, 4) BGRA array (premultiplied alpha)."""
        screen = user32.GetDC(None)
        if self.memdc is None:
            self.memdc = gdi32.CreateCompatibleDC(screen)
        bmi = BITMAPINFO()
        bmi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
        bmi.bmiHeader.biWidth, bmi.bmiHeader.biHeight = w, -h  # top-down
        bmi.bmiHeader.biPlanes, bmi.bmiHeader.biBitCount = 1, 32
        bits = ctypes.c_void_p()
        bmp = gdi32.CreateDIBSection(screen, ctypes.byref(bmi), 0, ctypes.byref(bits), None, 0)
        gdi32.SelectObject(self.memdc, bmp)
        if self.bmp:
            gdi32.DeleteObject(self.bmp)
        self.bmp, self.w, self.h = bmp, w, h
        user32.ReleaseDC(None, screen)
        return np.ctypeslib.as_array(ctypes.cast(bits, ctypes.POINTER(ctypes.c_uint8)), shape=(h, w, 4))

    def commit(self, x, y, alpha):
        """Shows what's been drawn into the surface."""
        blend = BLENDFUNCTION(0, 0, max(0, min(255, int(alpha))), 1)
        user32.UpdateLayeredWindow(self.hwnd, None, ctypes.byref(wintypes.POINT(int(x), int(y))), ctypes.byref(wintypes.SIZE(self.w, self.h)),
                                   self.memdc, ctypes.byref(wintypes.POINT(0, 0)), 0, ctypes.byref(blend), 2)

    def set_pixels(self, bgra, x, y, alpha):
        h, w = bgra.shape[:2]
        self.alloc(w, h)[:] = bgra
        self.commit(x, y, alpha)

    def move(self, x, y, alpha):
        """Moves the window and fades it, without redrawing a pixel - that's what keeps the glow cheap."""
        blend = BLENDFUNCTION(0, 0, max(0, min(255, int(alpha))), 1)
        user32.UpdateLayeredWindow(self.hwnd, None, ctypes.byref(wintypes.POINT(int(x), int(y))), None, None, None, 0, ctypes.byref(blend), 2)

    def show(self):
        if not self.shown:
            user32.ShowWindow(self.hwnd, 4)  # SW_SHOWNOACTIVATE
            user32.SetWindowPos(self.hwnd, HWND_TOPMOST, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)  # no size, no move, no activate
            self.shown = True

    def hide(self):
        if self.shown:
            user32.ShowWindow(self.hwnd, 0)
            self.shown = False


# ── the glow ───────────────────────────────────────────────────────────────────
# The same light as the phone (EdgeGlowView): a hot amber band at the left and right edges with a
# soft orange bloom behind it. It slides in with a small overshoot, swells with your voice, has
# sparks running round the screen while thinking, and blinks on every spoken word.

# Across the edge: hot amber at the very edge, orange, fading to nothing (RGBA).
EDGE_AT = np.array([0.0, 0.16, 0.42, 0.72, 1.0])
EDGE_RGBA = np.array([[255, 200, 112, 255], [255, 138, 31, 242], [255, 106, 0, 153], [255, 82, 0, 51], [255, 82, 0, 0]], np.float32)


def edge_row(width, thick):
    """One row of the left edge glow, premultiplied BGRA: the bright band over a wider, softer bloom."""
    def band(t, strength):
        u = np.arange(width, dtype=np.float32) / max(t, 1e-3)
        rgba = np.stack([np.interp(u, EDGE_AT, EDGE_RGBA[:, i]) for i in range(4)], -1)
        rgba[:, 3] *= strength
        rgba[:, :3] *= rgba[:, 3:4] / 255
        return rgba
    top, bloom = band(thick, 1.0), band(thick * 2.6, 0.32)
    out = top + bloom * (1 - top[:, 3:4] / 255)  # band over bloom
    return np.clip(out[:, [2, 1, 0, 3]], 0, 255).astype(np.uint8)


def spark_pixels(rx, ry):
    """A bright oval of light that runs along the edge (premultiplied BGRA)."""
    xs = (np.arange(2 * rx, dtype=np.float32) - rx + 0.5) / rx
    ys = (np.arange(2 * ry, dtype=np.float32) - ry + 0.5) / ry
    r = np.sqrt(xs[None, :] ** 2 + ys[:, None] ** 2)
    stops = np.array([[255, 240, 200, 255], [255, 192, 96, 170], [255, 154, 48, 0]], np.float32)
    rgba = np.stack([np.interp(r, [0, 0.35, 1], stops[:, i]) for i in range(4)], -1)
    rgba[..., :3] *= rgba[..., 3:4] / 255
    return np.clip(rgba[..., [2, 1, 0, 3]], 0, 255).astype(np.uint8)


def ease_out_back(t):
    u = t - 1
    return max(0.0, 1 + 2.4 * u ** 3 + 1.4 * u ** 2)


def reactor_icon(size):
    """A small arc reactor, like the orb on the phone."""
    from PIL import Image, ImageDraw, ImageFilter
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    halo = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(halo).ellipse([S * 0.18, S * 0.18, S * 0.82, S * 0.82], fill=(255, 138, 31, 150))
    img.alpha_composite(halo.filter(ImageFilter.GaussianBlur(S * 0.09)))
    d = ImageDraw.Draw(img)
    c, w = S / 2, max(2, int(S * 0.055))
    d.ellipse([c - S * 0.36, c - S * 0.36, c + S * 0.36, c + S * 0.36], fill=(14, 15, 19, 255))
    for start in (-70, 50, 170):
        d.arc([c - S * 0.43, c - S * 0.43, c + S * 0.43, c + S * 0.43], start, start + 75, fill=(255, 154, 60, 255), width=w)
    d.ellipse([c - S * 0.27, c - S * 0.27, c + S * 0.27, c + S * 0.27], outline=(255, 200, 112, 200), width=max(1, w // 2))
    core = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(core).ellipse([c - S * 0.15, c - S * 0.15, c + S * 0.15, c + S * 0.15], fill=(255, 226, 170, 255))
    img.alpha_composite(core.filter(ImageFilter.GaussianBlur(S * 0.03)))
    return img.resize((size, size), Image.LANCZOS)


def caption_pixels(who, text, W, s):
    """The caption card: an arc reactor, who's talking, and what they said."""
    from PIL import Image, ImageDraw, ImageFilter, ImageFont
    fonts = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
    body = ImageFont.truetype(os.path.join(fonts, "segoeui.ttf"), int(18 * s))
    label = ImageFont.truetype(os.path.join(fonts, "seguisb.ttf"), int(11.5 * s))
    maxw = int(min(W * 0.42, 700 * s))
    lines, line = [], ""
    for wd in text.split():
        trial = (line + " " + wd).strip()
        if body.getlength(trial) <= maxw or not line:
            line = trial
        else:
            lines.append(line)
            line = wd
    if line:
        lines.append(line)
    if len(lines) > 3:
        lines = lines[:3]
        lines[-1] = lines[-1].rstrip(".,;:") + "…"
    lines = lines or [""]

    tag = " ".join(who.upper())  # letter-spaced
    pad, icon = int(18 * s), int(34 * s)
    lh = int(body.size * 1.36)
    text_w = int(max([body.getlength(ln) for ln in lines] + [label.getlength(tag)]))
    cw = pad + icon + int(14 * s) + text_w + pad
    ch = max(pad * 2 + int(label.size * 1.9) + lh * len(lines), pad * 2 + icon)
    m = int(28 * s)  # room for the shadow
    img = Image.new("RGBA", (cw + 2 * m, ch + 2 * m), (0, 0, 0, 0))

    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([m, m + int(6 * s), m + cw, m + ch + int(6 * s)], radius=int(20 * s), fill=(0, 0, 0, 150))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14 * s)))

    d = ImageDraw.Draw(img)
    d.rounded_rectangle([m, m, m + cw, m + ch], radius=int(20 * s), fill=(13, 14, 18, 238), outline=(255, 255, 255, 26), width=max(1, int(s)))
    # A thin orange light along the top edge of the card.
    glow_line = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow_line).line([m + int(30 * s), m + 1, m + cw - int(30 * s), m + 1], fill=(255, 150, 50, 200), width=max(1, int(1.5 * s)))
    img.alpha_composite(glow_line.filter(ImageFilter.GaussianBlur(0.6 * s)))

    img.alpha_composite(reactor_icon(icon), (m + pad, m + (ch - icon) // 2))
    x = m + pad + icon + int(14 * s)
    y = m + pad - int(2 * s)
    d.text((x, y), tag, font=label, fill=(255, 152, 56, 255) if who == "JARVIS" else (150, 155, 165, 255))
    y += int(label.size * 1.9)
    for ln in lines:
        d.text((x, y), ln, font=body, fill=(242, 240, 236, 255))
        y += lh

    rgba = np.asarray(img).astype(np.float32)
    out = np.empty(rgba.shape, np.uint8)
    out[..., 0:3] = (rgba[..., [2, 1, 0]] * rgba[..., 3:4] / 255).astype(np.uint8)
    out[..., 3] = rgba[..., 3].astype(np.uint8)
    return out


def live_pill_pixels(s):
    """The banner at the top while the phone has live control of this laptop."""
    from PIL import Image, ImageDraw, ImageFilter, ImageFont
    fonts = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
    bold = ImageFont.truetype(os.path.join(fonts, "seguisb.ttf"), int(12.5 * s))
    body = ImageFont.truetype(os.path.join(fonts, "segoeui.ttf"), int(12.5 * s))
    a, b = "LIVE", "Your phone is viewing and controlling this laptop  ·  Ctrl+Alt+J to stop"
    pad, dot = int(14 * s), int(8 * s)
    w = pad + dot + int(8 * s) + int(bold.getlength(a)) + int(10 * s) + int(body.getlength(b)) + pad
    h = int(32 * s)
    m = int(14 * s)
    img = Image.new("RGBA", (w + 2 * m, h + 2 * m), (0, 0, 0, 0))
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([m, m + int(3 * s), m + w, m + h + int(3 * s)], radius=h // 2, fill=(0, 0, 0, 140))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(6 * s)))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([m, m, m + w, m + h], radius=h // 2, fill=(14, 15, 19, 240), outline=(255, 138, 31, 170), width=max(1, int(s)))
    cy = m + h // 2
    x = m + pad
    d.ellipse([x, cy - dot // 2, x + dot, cy + dot // 2], fill=(255, 77, 61, 255))
    x += dot + int(8 * s)
    d.text((x, cy), a, font=bold, fill=(255, 152, 56, 255), anchor="lm")
    x += int(bold.getlength(a)) + int(10 * s)
    d.text((x, cy), b, font=body, fill=(232, 230, 226, 255), anchor="lm")
    rgba = np.asarray(img).astype(np.float32)
    px = np.empty(rgba.shape, np.uint8)
    px[..., 0:3] = (rgba[..., [2, 1, 0]] * rgba[..., 3:4] / 255).astype(np.uint8)
    px[..., 3] = rgba[..., 3].astype(np.uint8)
    return px


class Glow:
    """The two edge lights, the sparks and the caption. Lives on the UI thread; animates only while showing."""

    def __init__(self):
        self.W, self.H = user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)
        try:
            self.s = user32.GetDpiForSystem() / 96
        except Exception:
            self.s = 1.0
        W, H, s = self.W, self.H, self.s
        self.max_w = int(math.ceil(50 * s * 2.6 * 1.15))  # the widest the bloom gets, overshoot included
        self.left, self.right = Layer(), Layer()
        self.lpx, self.rpx = self.left.alloc(self.max_w, H), self.right.alloc(self.max_w, H)
        self.rx, self.ry = int(48 * s), int(H * 0.16)
        spark = spark_pixels(self.rx, self.ry)
        self.spark_l, self.spark_r = Layer(), Layer()
        self.spark_l.set_pixels(spark, -self.rx, -2 * self.ry, 0)
        self.spark_r.set_pixels(spark, W - self.rx, H, 0)
        self.pill = None
        self.cap = Layer()
        self.cap_xy = (0, 0)
        self.has_cap = False
        self.cap_in = 0.0
        self.cap_hide_at = 0.0
        self.mode = "listening"
        self.enter = self.enter_target = 0.0
        self.level = self.input = self.pulse_v = self.spark = 0.0
        self.drawn = -1.0
        self.timer = 0
        self.last = time.monotonic()

    def set(self, mode):
        self.mode = mode
        if mode == "speaking":
            self.pulse_v = 0.6
        self.enter_target = 1.0
        for layer in (self.left, self.right, self.spark_l, self.spark_r):
            layer.show()
        self._run()

    def hide(self, caption_after=2.5):
        self.enter_target = 0.0
        self.cap_hide_at = time.monotonic() + caption_after
        self._run()

    def set_live(self, on):
        if on:
            if self.pill is None:
                self.pill = Layer()
                px = live_pill_pixels(self.s)
                self.pill.set_pixels(px, (self.W - px.shape[1]) // 2, int(4 * self.s), 255)
            self.pill.show()
        elif self.pill:
            self.pill.hide()

    def set_level(self, v):
        self.input = v

    def pulse(self):
        self.pulse_v = 1.0

    def caption(self, who, text):
        px = caption_pixels(who, text, self.W, self.s)
        h, w = px.shape[:2]
        self.cap_xy = ((self.W - w) // 2, self.H - h - int(self.H * 0.085))
        self.cap.set_pixels(px, self.cap_xy[0], self.cap_xy[1] + 12 * self.s * (1 - self.cap_in), 255 * self.cap_in)
        self.has_cap = True
        self.cap_hide_at = 0.0
        self.cap.show()
        self._run()

    def _run(self):
        if not self.timer:
            self.last = time.monotonic()
            self.timer = user32.SetTimer(None, 0, 16, None)

    def tick(self):
        now = time.monotonic()
        dt = min(0.05, now - self.last)
        self.last = now

        # Slide in quickly with a slight overshoot; slide out a little slower.
        rate = 9 if self.enter_target > self.enter else 6
        self.enter += (self.enter_target - self.enter) * (1 - math.exp(-dt * rate))
        if self.enter_target == 0 and self.enter < 0.01:
            self.enter = self.level = self.input = self.pulse_v = 0.0
            self.drawn = -1.0
            for layer in (self.left, self.right, self.spark_l, self.spark_r):
                layer.hide()
        else:
            target = {"listening": 0.32 + 0.68 * self.input,
                      "thinking": 0.42 + 0.16 * math.sin(now * 2.6)}.get(self.mode, 0.34 + 0.66 * self.pulse_v)
            self.level += (target - self.level) * (1 - math.exp(-dt * (22 if self.mode == "speaking" else 10)))
            self.pulse_v *= math.exp(-dt * 6)
            self.input *= math.exp(-dt * 3)
            self.spark = (self.spark + dt * (0.55 if self.mode == "thinking" else 0.18)) % 1
            enter = min(1.0, self.enter)
            thick = (16 + 34 * self.level) * self.s * ease_out_back(enter)
            alpha = (0.5 + 0.5 * self.level) * enter * 255
            if abs(thick - self.drawn) >= 0.75:  # redraw only when the band has really changed width
                row = edge_row(self.max_w, thick)
                self.lpx[:] = row[None, :, :]
                self.rpx[:] = row[None, ::-1, :]
                self.left.commit(0, 0, alpha)
                self.right.commit(self.W - self.max_w, 0, alpha)
                self.drawn = thick
            else:
                self.left.move(0, 0, alpha)
                self.right.move(self.W - self.max_w, 0, alpha)
            # Sparks: one running down the left edge, one up the right, like light circling the screen.
            on = self.mode != "listening" or self.level > 0.6
            sa = alpha * (0.9 if self.mode == "thinking" else 0.55) if on else 0
            span = self.H + 2 * self.ry
            self.spark_l.move(-self.rx, -2 * self.ry + self.spark * span, sa)
            self.spark_r.move(self.W - self.rx, self.H - self.spark * span, sa)

        if self.has_cap:
            showing = self.enter_target > 0 or now < self.cap_hide_at
            self.cap_in += ((1.0 if showing else 0.0) - self.cap_in) * (1 - math.exp(-dt * 10))
            if not showing and self.cap_in < 0.02:
                self.cap.hide()
                self.has_cap = False
                self.cap_in = 0.0
            else:
                e = 1 - (1 - self.cap_in) ** 3
                self.cap.move(self.cap_xy[0], self.cap_xy[1] + 12 * self.s * (1 - e), 255 * self.cap_in)

        if self.enter == 0 and not self.has_cap:
            user32.KillTimer(None, self.timer)
            self.timer = 0


# ── UI thread plumbing ─────────────────────────────────────────────────────────

MAIN_TID = kernel32.GetCurrentThreadId()
_ui_q = queue.Queue()


def ui(fn):
    """Run fn on the UI thread (windows may only be touched by the thread that made them)."""
    _ui_q.put(fn)
    user32.PostThreadMessageW(MAIN_TID, WM_APP, 0, 0)


# ── ears: wake word, then the question ─────────────────────────────────────────

def ensure_wake_model():
    path = os.path.join(MODELS, WAKE_MODEL)
    if os.path.isdir(path):
        return path
    os.makedirs(MODELS, exist_ok=True)
    log("downloading the offline wake-word model (36 MB, once)...")
    tmp = os.path.join(MODELS, WAKE_MODEL + ".zip.part")
    urllib.request.urlretrieve(WAKE_URL, tmp)
    root = os.path.realpath(MODELS)
    with zipfile.ZipFile(tmp) as z:
        for m in z.namelist():  # never write outside the models folder
            if not os.path.realpath(os.path.join(root, m)).startswith(root + os.sep):
                raise RuntimeError("unsafe path in the model zip: " + m)
        z.extractall(root)
    os.remove(tmp)
    return path


class Agc:
    """Automatic gain: some laptop mics come in very quiet (Darshan's peaks near 40 of 32767 in a quiet
    room), and the wake-word model misses "Hey Jarvis" below a peak of about 300-800. Boost each 0.1 s
    chunk so speech peaks near TARGET, judged on the last 2 s including this chunk (so loud speech is
    never clipped), and never by more than MAX_GAIN."""
    TARGET, MAX_GAIN, FLOOR = 6000.0, 24.0, 60.0

    def __init__(self):
        self.peaks = collections.deque(maxlen=20)

    def __call__(self, data):
        x = np.frombuffer(data, np.int16).astype(np.float32)
        self.peaks.append(float(np.max(np.abs(x))) if len(x) else 0.0)
        gain = min(self.MAX_GAIN, max(1.0, self.TARGET / max(max(self.peaks), self.FLOOR)))
        return np.clip(x * gain, -32768, 32767).astype(np.int16).tobytes() if gain > 1.0 else data


class Ears:
    def __init__(self, on_wake):
        import sounddevice as sd
        from vosk import KaldiRecognizer, Model, SetLogLevel
        SetLogLevel(-1)
        self.on_wake = on_wake
        self.model = Model(ensure_wake_model())
        self.rec = KaldiRecognizer(self.model, RATE, json.dumps(["jarvis", "hey jarvis", "okay jarvis", "[unk]"]))
        self.rec.SetWords(True)
        self.q = queue.Queue(maxsize=300)
        self.mode = "wake"
        self.noise = 0.01
        self.on_level = lambda v: None
        self.agc = Agc()
        self.src_rate = RATE
        try:
            self.stream = sd.RawInputStream(samplerate=RATE, blocksize=RATE // 10, dtype="int16", channels=1, callback=self._cb)
        except Exception:  # the mic won't do 16 kHz: take its own rate and convert
            self.src_rate = int(sd.query_devices(kind="input")["default_samplerate"])
            self.stream = sd.RawInputStream(samplerate=self.src_rate, blocksize=self.src_rate // 10, dtype="int16", channels=1, callback=self._cb)
        self.stream.start()
        threading.Thread(target=self._loop, daemon=True).start()

    def _cb(self, data, frames, t, status):
        try:
            self.q.put_nowait(bytes(data))
        except queue.Full:
            pass

    def _loop(self):
        while True:
            data = self.q.get()
            if self.src_rate != RATE:
                x = np.frombuffer(data, np.int16).astype(np.float32)
                n = int(len(x) * RATE / self.src_rate)
                data = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.int16).tobytes()
            data = self.agc(data)
            level = float(np.sqrt(np.mean((np.frombuffer(data, np.int16).astype(np.float32) / 32768) ** 2)))
            if self.mode == "wake":
                self.noise = 0.98 * self.noise + 0.02 * min(level, 0.05)
                if self.rec.AcceptWaveform(data):
                    r = json.loads(self.rec.Result())
                    confs = [w.get("conf", 1) for w in r.get("result", []) if w.get("word") == "jarvis"]
                    if any(c >= 0.6 for c in confs):
                        log('heard "Jarvis"')
                        self.mode = "off"
                        self.on_wake("voice")
                    elif confs:  # so a missed wake word shows up in the log
                        log(f'heard something like "Jarvis" but wasn\'t sure ({max(confs):.2f}) - say it a little clearer')
            elif self.mode == "record":
                self._record(data, level)

    def listen_for_wake(self):
        self.rec.Reset()
        with self.q.mutex:
            self.q.queue.clear()  # drop anything heard while JARVIS was talking
        self.mode = "wake"

    def stop(self):
        self.mode = "off"

    def record(self, done, on_level):
        """Records until you stop talking (0.9 s of quiet), 12 s at most. done(pcm or None)."""
        self.buf, self.started, self.quiet, self.t = bytearray(), False, 0.0, 0.0
        self.done, self.on_level = done, on_level
        with self.q.mutex:
            self.q.queue.clear()
        self.mode = "record"

    def _record(self, data, level):
        self.buf += data
        self.t += len(data) / 2 / RATE
        self.on_level(min(1.0, level * 12))
        if level > max(0.012, self.noise * 2.5):
            self.started, self.quiet = True, 0.0
        elif self.started:
            self.quiet += len(data) / 2 / RATE
        if (self.started and self.quiet >= 0.9) or self.t >= 12 or (not self.started and self.t >= 6):
            self.mode = "off"
            pcm = bytes(self.buf) if self.started else None
            threading.Thread(target=self.done, args=(pcm,), daemon=True).start()


class Stt:
    """Speech to text on this laptop. Loaded on first use, let go after 10 quiet minutes to free memory."""

    def __init__(self):
        self.model = None
        self.used = 0
        self.lock = threading.Lock()
        self.fetching = threading.Lock()
        self.dir = os.path.join(MODELS, "whisper-" + WHISPER)
        threading.Thread(target=self._fetch, daemon=True).start()
        threading.Thread(target=self._reaper, daemon=True).start()

    def _fetch(self):
        with self.fetching:
            if not os.path.exists(os.path.join(self.dir, "model.bin")):
                from faster_whisper import download_model
                log(f"downloading the speech-to-text model ({WHISPER}, about 145 MB, once)...")
                download_model(WHISPER, output_dir=self.dir)
                log("speech-to-text model ready")

    def _reaper(self):
        while True:
            time.sleep(60)
            with self.lock:
                if self.model is not None and time.time() - self.used > 600:
                    self.model = None
                    gc.collect()

    def transcribe(self, pcm):
        with self.lock:
            if self.model is None:
                self._fetch()
                from faster_whisper import WhisperModel
                self.model = WhisperModel(self.dir, device="cpu", compute_type="int8", cpu_threads=4)
            self.used = time.time()
            audio = np.frombuffer(pcm, np.int16).astype(np.float32) / 32768
            segs, _ = self.model.transcribe(audio, language="en", beam_size=1, vad_filter=True, condition_on_previous_text=False, without_timestamps=True)
            text = " ".join(s.text.strip() for s in segs).strip()
        # Whisper sometimes "hears" these in near-silence.
        if re.fullmatch(r"(?i)\W*(thank you|thanks for watching|you|bye)\W*", text):
            return ""
        return text


# ── mouth: JARVIS's voice ──────────────────────────────────────────────────────

async def _synth(text):
    import edge_tts
    c = edge_tts.Communicate(text, VOICE, boundary="WordBoundary")
    audio, words = bytearray(), []
    async for chunk in c.stream():
        if chunk["type"] == "audio":
            audio += chunk["data"]
        elif chunk["type"] == "WordBoundary":
            words.append(chunk["offset"] / 10_000_000)  # 100 ns units -> s
    return bytes(audio), words


def speakable(s):
    s = re.sub(r"```[\s\S]*?```", " ", s)
    s = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", s)
    s = re.sub(r"https?://\S+", "a link", s)
    s = re.sub(r"[*_#`>|~]", "", s)
    s = re.sub(r"^\s*[-•]\s+", "", s, flags=re.M)
    return re.sub(r"\s+", " ", s).strip()


def mci(cmd):
    buf = ctypes.create_unicode_buffer(128)
    err = winmm.mciSendStringW(cmd, buf, 127, None)
    if err:
        raise RuntimeError(f"mci error {err}: {cmd.split()[0]}")
    return buf.value


class Mouth:
    def __init__(self):
        self.serial = 0
        self.dir = tempfile.mkdtemp(prefix="jarvis-voice-")

    def speak(self, text, on_word, done):
        self.serial += 1
        threading.Thread(target=self._run, args=(self.serial, speakable(text), on_word, done), daemon=True).start()

    def stop(self):
        self.serial += 1

    def _run(self, my, text, on_word, done):
        try:
            try:
                audio, words = asyncio.run(_synth(text))
            except Exception as e:  # no internet or the voice service changed: the laptop's own voice
                log(f"voice: {type(e).__name__}: {e} - using Windows' voice")
                self._windows_voice(my, text, on_word)
                return
            if my != self.serial:
                return
            path = os.path.join(self.dir, f"r{my}.mp3")
            with open(path, "wb") as f:
                f.write(audio)
            alias = f"jv{my}"
            mci(f'open "{path}" type mpegvideo alias {alias}')
            try:
                mci(f"set {alias} time format milliseconds")
                mci(f"play {alias}")
                i = 0
                while my == self.serial:
                    pos = int(mci(f"status {alias} position") or 0) / 1000
                    while i < len(words) and pos >= words[i]:
                        i += 1
                        on_word()
                    if mci(f"status {alias} mode") != "playing":
                        break
                    time.sleep(0.03)
            finally:
                mci(f"close {alias}")
                try:
                    os.remove(path)
                except OSError:
                    pass
        except Exception as e:
            log(f"voice failed: {e}")
        finally:
            if my == self.serial:
                done()

    def _windows_voice(self, my, text, on_word):
        import subprocess
        p = subprocess.Popen(["powershell", "-NoProfile", "-Command",
                              "Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak([Console]::In.ReadToEnd())"],
                             stdin=subprocess.PIPE, creationflags=0x08000000)
        p.stdin.write(text.encode("utf-8"))
        p.stdin.close()
        while p.poll() is None:
            if my != self.serial:
                p.kill()
                break
            on_word()
            time.sleep(0.35)


def make_chime():
    """A short two-note chime for "I'm listening", made here so nothing has to be downloaded."""
    path = os.path.join(tempfile.gettempdir(), "jarvis-chime.wav")
    sr = 22050
    t = np.arange(int(sr * 0.09)) / sr
    env = np.minimum(1, t / 0.01) * np.exp(-t * 28)
    tone = np.concatenate([np.sin(2 * np.pi * 880 * t) * env, np.sin(2 * np.pi * 1320 * t) * env])
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((tone * 0.28 * 32767).astype(np.int16).tobytes())
    return path


# ── JARVIS: one turn at a time ─────────────────────────────────────────────────

class Jarvis:
    def __init__(self, glow):
        self.glow = glow
        self.live = False
        self.state = "idle"
        self.turn = 0
        self.asks = {}
        self.ask_id = 0
        self.ears = None
        self.stt = Stt()
        self.mouth = Mouth()
        self.chime = make_chime()

    def start_ears(self):
        try:
            self.ears = Ears(on_wake=lambda src: self.activate(src))
            emit("ready")
        except Exception as e:
            log(f"can't listen for \"Hey Jarvis\": {type(e).__name__}: {e}. Ctrl+Alt+J still works.")
            emit("ready")

    def set_live(self, on):
        self.live = on
        ui(lambda: self.glow.set_live(on))

    def activate(self, source):
        """The wake word or the hotkey: talk if idle, otherwise stop - JARVIS's hands too (pc/hands.py)."""
        if source == "hotkey" and self.live:  # the phone has live control: Ctrl+Alt+J ends that first
            try:
                open(HANDS_STOP, "w").close()
            except OSError:
                pass
            emit("live_stop")
            return
        if self.state == "idle":
            self.listen()
        elif source == "hotkey":
            try:
                open(HANDS_STOP, "w").close()
            except OSError:
                pass
            ui(lambda: self.glow.caption("JARVIS", "Stopped."))
            self.finish()

    def listen(self):
        try:
            os.remove(HANDS_STOP)  # a new request: JARVIS may use its hands again
        except OSError:
            pass
        self.turn += 1
        my = self.turn
        self.state = "listening"
        winsound.PlaySound(self.chime, winsound.SND_FILENAME | winsound.SND_ASYNC | winsound.SND_NODEFAULT)
        ui(lambda: self.glow.set("listening"))
        ui(lambda: self.glow.caption("You", "Listening…"))
        if not self.ears:
            return self.finish()
        self.ears.record(done=lambda pcm: self.heard(my, pcm), on_level=lambda v: ui(lambda: self.glow.set_level(v)))

    def heard(self, my, pcm):
        if my != self.turn:
            return
        if pcm is None:
            # Woke up but no question followed: say so instead of vanishing.
            log("woke up but didn't hear a question - ask right after the chime")
            ui(lambda: self.glow.caption("You", "(I didn't hear a question - try again)"))
            return threading.Timer(1.8, lambda: my == self.turn and self.finish()).start()
        self.state = "thinking"
        ui(lambda: self.glow.set("thinking"))
        try:
            text = self.stt.transcribe(pcm)
        except Exception as e:
            log(f"speech-to-text failed: {e}")
            text = ""
        if my != self.turn:
            return
        if not text:
            ui(lambda: self.glow.caption("You", "(I didn't catch that)"))
            return self.finish()
        self.ask(text)

    def ask(self, text):
        my = self.turn
        self.state = "thinking"
        ui(lambda: self.glow.set("thinking"))
        ui(lambda: self.glow.caption("You", text))
        if text.lower().strip(" .!?,") in STOP_WORDS:
            return self.finish()
        self.ask_id += 1
        self.asks[self.ask_id] = my
        emit("ask", id=self.ask_id, text=text)

    def status(self, text):
        if self.state == "thinking" and text:
            ui(lambda: self.glow.caption("JARVIS", text))

    def reply(self, ask_id, text):
        my = self.asks.pop(ask_id, None)
        if my is None or my != self.turn:
            return
        self.say(text or "Done.")

    def say(self, text):
        my = self.turn
        self.state = "speaking"
        ui(lambda: self.glow.set("speaking"))
        ui(lambda: self.glow.caption("JARVIS", speakable(text)))

        def after():
            if my != self.turn:
                return
            # JARVIS asked you something: listen for the answer straight away.
            if text.rstrip().endswith("?"):
                self.listen()
            else:
                self.finish()

        self.mouth.speak(text, on_word=lambda: ui(self.glow.pulse), done=after)

    def finish(self):
        self.turn += 1
        self.state = "idle"
        emit("idle")
        self.mouth.stop()
        ui(lambda: self.glow.hide())
        if self.ears:
            # A moment's pause, so the end of JARVIS's own voice can't wake it up again.
            threading.Timer(0.6, lambda: self.state == "idle" and self.ears.listen_for_wake()).start()


def read_commands(j):
    for line in sys.stdin:
        try:
            m = json.loads(line)
        except ValueError:
            continue
        cmd = m.get("cmd")
        if cmd == "reply":
            j.reply(m.get("id"), m.get("text", ""))
        elif cmd == "status":
            j.status(m.get("text", ""))
        elif cmd == "hear":  # testing: as if this was just said
            j.turn += 1
            j.ask(m.get("text", ""))
        elif cmd == "say":
            j.turn += 1
            j.say(m.get("text", ""))
        elif cmd == "live":
            j.set_live(bool(m.get("on")))
    user32.PostThreadMessageW(MAIN_TID, WM_QUIT, 0, 0)  # the worker has gone: so do we


def quick_edit_off():
    """Clicking inside JARVIS's console window starts a text selection (Windows "QuickEdit"), and that
    freezes the whole worker - phone and "Hey Jarvis" both - until Esc is pressed. It happened once.
    Switch QuickEdit off for that window only. cmd.exe puts the mode back after each command it runs, and
    this helper runs in a hidden console of its own (the worker starts it with windowsHide), so a tiny
    child process attaches to the worker's console - the visible window - and changes it there."""
    code = ("import ctypes,sys\n"
            "k=ctypes.WinDLL('kernel32')\n"
            "k.FreeConsole()\n"
            "if not k.AttachConsole(int(sys.argv[1])): sys.exit(1)\n"
            "k.CreateFileW.restype=ctypes.c_void_p\n"
            "h=ctypes.c_void_p(k.CreateFileW('CONIN$',0xC0000000,3,None,3,0,None))\n"
            "m=ctypes.c_uint()\n"
            "if k.GetConsoleMode(h,ctypes.byref(m)): k.SetConsoleMode(h,(m.value|0x80)&~0x40)\n")
    import subprocess
    try:
        subprocess.run([sys.executable, "-c", code, str(os.getppid())], creationflags=0x08000000, timeout=10)  # CREATE_NO_WINDOW
    except Exception as e:
        log(f"couldn't switch off QuickEdit: {e}")


def main():
    quick_edit_off()
    msg = wintypes.MSG()
    user32.PeekMessageW(ctypes.byref(msg), None, 0, 0, 0)  # make this thread's message queue now
    glow = Glow()
    if "--demo" in sys.argv:
        steps = [(0.0, lambda: (glow.set("listening"), glow.caption("You", "Listening…"))),
                 (2.0, lambda: (glow.set("thinking"), glow.caption("You", "What's the weather in Chennai?"))),
                 (4.0, lambda: (glow.set("speaking"), glow.caption("JARVIS", "It's 31 degrees and humid in Chennai, sir, with a chance of rain this evening."))),
                 (7.0, lambda: glow.hide(0.5))]
        for at, fn in steps:
            threading.Timer(at, lambda fn=fn: ui(fn)).start()
        for k in range(14):
            threading.Timer(4.1 + k * 0.2, lambda: ui(glow.pulse)).start()
        threading.Timer(9.0, lambda: user32.PostThreadMessageW(MAIN_TID, WM_QUIT, 0, 0)).start()
        emit("ready")
    else:
        j = Jarvis(glow)
        if not user32.RegisterHotKey(None, 1, 0x0002 | 0x0001 | 0x4000, ord("J")):  # Ctrl+Alt+J, no auto-repeat
            log("Ctrl+Alt+J is taken by another app; say \"Hey Jarvis\" instead")
        threading.Thread(target=j.start_ears, daemon=True).start()
        threading.Thread(target=read_commands, args=(j,), daemon=True).start()
    while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
        if msg.message == WM_APP:
            while not _ui_q.empty():
                try:
                    _ui_q.get_nowait()()
                except Exception as e:
                    log(f"glow: {e}")
        elif msg.message == WM_TIMER and not msg.hWnd:
            glow.tick()
        elif msg.message == WM_HOTKEY:
            threading.Thread(target=j.activate, args=("hotkey",), daemon=True).start()
        else:
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))


if __name__ == "__main__":
    main()
