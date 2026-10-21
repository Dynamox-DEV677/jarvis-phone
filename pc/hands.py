"""JARVIS's hands on the laptop: an MCP server (stdio) that lets the JARVIS agent use desktop apps
the way you do - find and focus windows, read what's on them, click, type, press keys, scroll -
and type a prompt straight into the Claude desktop app.

It works the same whether you asked with "Hey Jarvis" at the laptop or from your phone.

You stay in charge:
  - Ctrl+Alt+J (or pushing the mouse into the top-left corner of the screen) stops JARVIS's hands
    at once: every action refuses for the next two minutes, or until you ask JARVIS something new.
  - It never types where it can't see a window it was told to use, and the caption on the laptop
    says what it's doing.

Register it in the JARVIS plugin's mcp_config.json as the "hands" server:
    "hands": {"command": "python", "args": ["C:/Users/sathy/jarvis-phone/pc/hands.py"]}
and allow it in ~/.gemini/antigravity-cli/settings.json:  "mcp(jarvis_hands/*)"

Try it without agy:  python hands.py --test windows | read Claude | focus Notepad
Needs:  python -m pip install --user uiautomation
"""
import ctypes
import json
import os
import sys
import time
from ctypes import wintypes

STOP_FILE = os.path.join(os.path.expanduser("~"), ".jarvis-phone", "hands.stop")
STOP_FOR = 120  # seconds

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)  # real pixels, the same as JARVIS's screenshots
except Exception:
    ctypes.windll.user32.SetProcessDPIAware()

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
dwmapi = ctypes.WinDLL("dwmapi")
ULONG_PTR = ctypes.c_size_t


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]


class INPUT(ctypes.Structure):
    class _U(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]
    _fields_ = [("type", wintypes.DWORD), ("u", _U)]


WNDENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
user32.EnumWindows.argtypes = [WNDENUMPROC, wintypes.LPARAM]
user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
user32.IsWindowVisible.argtypes = [wintypes.HWND]
user32.IsIconic.argtypes = [wintypes.HWND]
user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
user32.GetWindowLongW.argtypes = [wintypes.HWND, ctypes.c_int]
user32.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
user32.GetForegroundWindow.restype = wintypes.HWND
user32.SetForegroundWindow.argtypes = [wintypes.HWND]
user32.BringWindowToTop.argtypes = [wintypes.HWND]
user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
user32.SetCursorPos.argtypes = [ctypes.c_int, ctypes.c_int]
user32.GetCursorPos.argtypes = [ctypes.POINTER(wintypes.POINT)]
user32.AttachThreadInput.argtypes = [wintypes.DWORD, wintypes.DWORD, wintypes.BOOL]
kernel32.OpenProcess.restype = wintypes.HANDLE
kernel32.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
dwmapi.DwmGetWindowAttribute.argtypes = [wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]


class Stopped(Exception):
    pass


def check_stop():
    """Darshan stopped JARVIS (Ctrl+Alt+J, or the mouse in the top-left corner)."""
    pt = wintypes.POINT()
    user32.GetCursorPos(ctypes.byref(pt))
    if pt.x <= 0 and pt.y <= 0:
        os.makedirs(os.path.dirname(STOP_FILE), exist_ok=True)
        open(STOP_FILE, "w").close()
    try:
        if time.time() - os.path.getmtime(STOP_FILE) < STOP_FOR:
            raise Stopped("Darshan stopped JARVIS's hands (Ctrl+Alt+J or the mouse in the corner). Don't try again; tell him it was stopped.")
    except FileNotFoundError:
        pass


# ── windows ────────────────────────────────────────────────────────────────────

def process_name(pid):
    h = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(520)
        n = wintypes.DWORD(520)
        return os.path.basename(buf.value) if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(n)) else ""
    finally:
        kernel32.CloseHandle(h)


def windows():
    """Visible top-level app windows: [{hwnd, title, app, rect, minimised}], front-most first."""
    out = []

    def each(hwnd, _):
        if not user32.IsWindowVisible(hwnd):
            return True
        n = user32.GetWindowTextLengthW(hwnd)
        if n == 0:
            return True
        if user32.GetWindowLongW(hwnd, -20) & 0x80:  # WS_EX_TOOLWINDOW: palettes, popups, our glow
            return True
        cloaked = ctypes.c_int(0)
        dwmapi.DwmGetWindowAttribute(hwnd, 14, ctypes.byref(cloaked), 4)  # DWMWA_CLOAKED: hidden store apps
        if cloaked.value:
            return True
        title = ctypes.create_unicode_buffer(n + 1)
        user32.GetWindowTextW(hwnd, title, n + 1)
        cls = ctypes.create_unicode_buffer(64)
        user32.GetClassNameW(hwnd, cls, 64)
        if cls.value in ("Progman", "WorkerW", "Shell_TrayWnd"):
            return True
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        r = wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(r))
        out.append({"hwnd": hwnd, "title": title.value, "app": process_name(pid.value), "rect": (r.left, r.top, r.right, r.bottom),
                    "minimised": bool(user32.IsIconic(hwnd))})
        return True

    user32.EnumWindows(WNDENUMPROC(each), 0)
    return out


def find_window(query):
    """A window by its title or app name ("Claude", "chrome", "Notepad", "File Explorer")."""
    q = query.lower().strip().removesuffix(".exe")
    wins = windows()
    for test in (lambda w: w["title"].lower() == q,
                 lambda w: w["app"].lower().removesuffix(".exe") == q,
                 lambda w: q in w["title"].lower(),
                 lambda w: q in w["app"].lower()):
        hits = [w for w in wins if test(w)]
        if hits:
            # the biggest one: an app's main window rather than a small popup
            return max(hits, key=lambda w: (w["rect"][2] - w["rect"][0]) * (w["rect"][3] - w["rect"][1]))
    names = ", ".join(sorted({w["title"][:40] for w in wins})[:15])
    raise LookupError(f'No open window matches "{query}". Open windows: {names}')


def focus(w):
    hwnd = w["hwnd"]
    if user32.IsIconic(hwnd):
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    for attempt in range(2):
        if attempt:
            # Windows only lets the app in front hand over focus; a tap of Alt counts as "the user did it".
            key(0x12, down=True)
            key(0x12, down=False)
        fg = user32.GetForegroundWindow()
        me, them = kernel32.GetCurrentThreadId(), user32.GetWindowThreadProcessId(fg, None)
        user32.AttachThreadInput(me, them, True)
        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
        user32.AttachThreadInput(me, them, False)
        for _ in range(8):
            if user32.GetForegroundWindow() == hwnd:
                time.sleep(0.15)
                return True
            time.sleep(0.05)
    return False


def front_window():
    fg = user32.GetForegroundWindow()
    for w in windows():
        if w["hwnd"] == fg:
            return w
    raise LookupError("No app window is in front.")


# ── keyboard and mouse ─────────────────────────────────────────────────────────

def _send(*inputs):
    arr = (INPUT * len(inputs))(*inputs)
    user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5C, 0x6F, 0x90, 0xA3, 0xA5}


def key(vk, down=True):
    flags = (0 if down else 2) | (1 if vk in EXTENDED else 0)
    _send(INPUT(type=1, u=INPUT._U(ki=KEYBDINPUT(wVk=vk, dwFlags=flags))))


VK = {"enter": 0x0D, "return": 0x0D, "esc": 0x1B, "escape": 0x1B, "tab": 0x09, "space": 0x20, "backspace": 0x08, "delete": 0x2E, "del": 0x2E,
      "insert": 0x2D, "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22, "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
      "ctrl": 0x11, "control": 0x11, "shift": 0x10, "alt": 0x12, "win": 0x5B, "windows": 0x5B, "capslock": 0x14, "printscreen": 0x2C,
      "volumeup": 0xAF, "volumedown": 0xAE, "mute": 0xAD, "playpause": 0xB3, "next": 0xB0, "previous": 0xB1, "prev": 0xB1,
      "/": 0xBF, ".": 0xBE, ",": 0xBC, "-": 0xBD, "=": 0xBB, ";": 0xBA, "'": 0xDE, "[": 0xDB, "]": 0xDD, "\\": 0xDC, "`": 0xC0}
VK.update({f"f{i}": 0x6F + i for i in range(1, 13)})
MODIFIERS = {0x11, 0x10, 0x12, 0x5B}


def vk_of(name):
    n = name.lower().strip()
    if n in VK:
        return VK[n]
    if len(n) == 1 and n.isalnum():
        return ord(n.upper())
    raise ValueError(f'Unknown key "{name}"')


def press(combo):
    """One combo like "ctrl+shift+t", "enter" or "alt+f4"."""
    vks = [vk_of(k) for k in combo.replace(" ", "").split("+") if k]
    if {vk for vk in vks} >= {0x11, 0x12, 0x2E}:
        raise ValueError("Ctrl+Alt+Delete is for Darshan to press.")
    for vk in vks:  # a moment between keys: some apps miss a shortcut that arrives all at once
        key(vk, True)
        time.sleep(0.03)
    for vk in reversed(vks):
        key(vk, False)
        time.sleep(0.02)
    time.sleep(0.05)


def type_text(text, newline="shift+enter"):
    """Types text as if from the keyboard (any language or emoji). New lines become Shift+Enter, so chat boxes don't send early."""
    chunk = []

    def flush():
        if chunk:
            _send(*chunk)
            chunk.clear()
            time.sleep(0.01)

    for ch in text.replace("\r\n", "\n"):
        if ch == "\n":
            flush()
            press(newline)
            continue
        if ch == "\t":
            flush()
            press("tab")
            continue
        units = ch.encode("utf-16-le")
        for i in range(0, len(units), 2):
            code = int.from_bytes(units[i:i + 2], "little")
            chunk.append(INPUT(type=1, u=INPUT._U(ki=KEYBDINPUT(wScan=code, dwFlags=4))))      # KEYEVENTF_UNICODE
            chunk.append(INPUT(type=1, u=INPUT._U(ki=KEYBDINPUT(wScan=code, dwFlags=4 | 2))))  # ... key up
        if len(chunk) >= 80:
            flush()
            check_stop()
    flush()


def click(x, y, button="left", double=False):
    user32.SetCursorPos(int(x), int(y))
    time.sleep(0.05)
    down, up = {"left": (2, 4), "right": (8, 16), "middle": (32, 64)}[button]
    for _ in range(2 if double else 1):
        _send(INPUT(type=0, u=INPUT._U(mi=MOUSEINPUT(dwFlags=down))), INPUT(type=0, u=INPUT._U(mi=MOUSEINPUT(dwFlags=up))))
        time.sleep(0.06)


def scroll(direction, amount, x=None, y=None):
    if x is not None and y is not None:
        user32.SetCursorPos(int(x), int(y))
    if direction in ("up", "down"):
        delta, flag = (120 if direction == "up" else -120) * amount, 0x0800
    else:
        delta, flag = (120 if direction == "right" else -120) * amount, 0x1000
    _send(INPUT(type=0, u=INPUT._U(mi=MOUSEINPUT(mouseData=delta & 0xFFFFFFFF, dwFlags=flag))))


# ── what's on a window (Windows UI Automation) ─────────────────────────────────

INTERACTIVE = {"Button", "Edit", "Document", "Hyperlink", "ListItem", "MenuItem", "TabItem", "RadioButton", "CheckBox", "ComboBox", "TreeItem", "SplitButton"}


def elements(w, query=None, limit=80, budget=6.0):
    """Named or typeable things on a window that are on screen: [{type, name, x, y, w, h, value}]."""
    import uiautomation as auto
    root = auto.ControlFromHandle(w["hwnd"])
    sw, sh = user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)
    q = (query or "").lower()
    found = []
    for attempt in range(2):
        found.clear()
        t0 = time.time()
        for c, _depth in auto.WalkControl(root, maxDepth=45):
            if time.time() - t0 > budget:
                break
            t = c.ControlTypeName.removesuffix("Control")
            name = (c.Name or "").replace("\n", " ").strip()
            if not name and t not in ("Edit", "Document"):
                continue
            r = c.BoundingRectangle
            if r.width() < 3 or r.height() < 3 or r.right <= 0 or r.bottom <= 0 or r.left >= sw or r.top >= sh:
                continue  # scrolled out of sight or hidden
            if t == "Document" and not c.IsKeyboardFocusable:
                continue
            if q and q not in name.lower():
                continue
            value = ""
            if t in ("Edit", "ComboBox", "Document"):
                try:
                    value = c.GetValuePattern().Value or ""
                except Exception:
                    pass
                if not value:  # rich editors (the new Notepad, Word...) only give their text this way
                    try:
                        value = (c.GetTextPattern().DocumentRange.GetText(2000) or "").strip()
                    except Exception:
                        pass
            found.append({"type": t, "name": name, "x": r.xcenter(), "y": r.ycenter(), "w": r.width(), "h": r.height(), "value": value, "ctl": c})
        # Chrome and Electron apps (Claude, VS Code, Discord...) build this only once something asks for it.
        if len(found) > 6 or query or attempt:
            break
        time.sleep(1.2)
    return found[:limit] if limit else found


def find_element(w, name, prefer_types=None):
    q = name.lower().strip()
    els = elements(w, limit=0)
    for test in (lambda e: e["name"].lower() == q, lambda e: e["name"].lower().startswith(q), lambda e: q in e["name"].lower()):
        hits = [e for e in els if test(e) and not e["name"].lower().startswith(("more options for", "new session in"))]
        if prefer_types:
            hits = [e for e in hits if e["type"] in prefer_types] or hits
        hits = [e for e in hits if e["type"] in INTERACTIVE] or hits
        if hits:
            return hits[0]
    near = ", ".join(sorted({e["name"][:30] for e in els if e["type"] in INTERACTIVE and e["name"]})[:25])
    raise LookupError(f'Nothing called "{name}" on {w["title"]}. Things you can click there: {near}')


def describe(e):
    v = f' = "{e["value"][:60]}"' if e["value"] else ""
    return f'{e["type"].lower()} "{e["name"][:80]}"{v} at ({e["x"]}, {e["y"]})'


# ── the tools ──────────────────────────────────────────────────────────────────

def t_windows(a):
    fg = user32.GetForegroundWindow()
    lines = [f'- "{w["title"][:70]}" ({w["app"]}){" - minimised" if w["minimised"] else ""}{"  <- in front" if w["hwnd"] == fg else ""}'
             for w in windows()]
    return "Open windows:\n" + "\n".join(lines)


def t_focus(a):
    w = find_window(a["window"])
    return f'"{w["title"]}" is in front.' if focus(w) else f'Tried to bring "{w["title"]}" to the front, but Windows kept another window there.'


def target(a):
    if a.get("window"):
        w = find_window(a["window"])
        if not focus(w):
            raise RuntimeError(f'Could not bring "{w["title"]}" to the front, so nothing was done.')
        return w
    return front_window()


def t_read(a):
    w = target(a)
    els = elements(w, a.get("query"), limit=int(a.get("limit") or 80))
    if not els:
        return f'Nothing{" matching " + repr(a["query"]) if a.get("query") else ""} found on "{w["title"]}". Take a screenshot to look instead.'
    return f'On "{w["title"]}" ({w["app"]}), screen positions in pixels:\n' + "\n".join("- " + describe(e) for e in els)


def t_click(a):
    w = target(a)
    if a.get("element"):
        e = find_element(w, a["element"])
        click(e["x"], e["y"], a.get("button", "left"), bool(a.get("double")))
        return f'Clicked {describe(e)} on "{w["title"]}".'
    if a.get("x") is None or a.get("y") is None:
        raise ValueError("Give either element (its name) or x and y (screen pixels, as in a screenshot).")
    click(a["x"], a["y"], a.get("button", "left"), bool(a.get("double")))
    return f'Clicked at ({a["x"]}, {a["y"]}) on "{w["title"]}".'


def t_type(a):
    w = target(a)
    if a.get("element"):
        e = find_element(w, a["element"], {"Edit", "Document", "ComboBox"})
        click(e["x"], e["y"])
        time.sleep(0.15)
    type_text(a["text"])
    if a.get("submit"):
        time.sleep(0.1)
        press("enter")
    return f'Typed {len(a["text"])} characters into "{w["title"]}"{" and pressed Enter" if a.get("submit") else ""}.'


def t_keys(a):
    w = target(a)
    combos = [c for c in a["keys"].replace(",", " ").split() if c]
    for c in combos:
        check_stop()
        press(c)
    return f'Pressed {", ".join(combos)} on "{w["title"]}".'


def t_scroll(a):
    w = target(a)
    scroll(a.get("direction", "down"), int(a.get("amount") or 5), a.get("x"), a.get("y"))
    return f'Scrolled {a.get("direction", "down")} on "{w["title"]}".'


def t_claude(a):
    """Types a prompt into the Claude desktop app."""
    w = find_window("claude")
    if w["app"].lower() != "claude.exe":
        raise LookupError("The Claude desktop app isn't open. Open it first (the open tool: Claude), then try again.")
    if not focus(w):
        raise RuntimeError("Could not bring Claude to the front, so nothing was typed.")
    done = []
    tab = (a.get("tab") or "").lower()
    if tab:
        e = find_element(w, "code" if tab == "code" else "chat", {"RadioButton", "TabItem", "Button"})
        click(e["x"], e["y"])
        time.sleep(0.8)
        done.append(f'opened the {e["name"].split(",")[0]} tab')
    if a.get("session"):
        e = find_element(w, a["session"], {"Button", "ListItem"})
        click(e["x"], e["y"])
        time.sleep(1.2)
        done.append(f'opened "{e["name"]}"')
    elif a.get("new"):
        for name in ("New chat", "New session", "New"):
            try:
                e = find_element(w, name, {"Button"})
            except LookupError:
                continue
            click(e["x"], e["y"])
            time.sleep(1.2)
            done.append("started a new one")
            break
    check_stop()
    boxes = [e for e in elements(w, limit=0) if e["type"] in ("Edit", "Document") and any(k in e["name"].lower() for k in ("prompt", "message", "reply", "ask", "write"))]
    if not boxes:
        raise LookupError("Couldn't find Claude's prompt box. Take a screenshot to see what Claude is showing.")
    box = max(boxes, key=lambda e: e["y"])  # the one at the bottom
    click(box["x"], box["y"])
    time.sleep(0.2)
    type_text(a["prompt"])
    send = a.get("send", True)
    if send:
        time.sleep(0.15)
        press("enter")
    done.append(("typed and sent" if send else "typed (not sent)") + f' the prompt ({len(a["prompt"])} characters)')
    return "In Claude: " + ", then ".join(done) + "."


S = {"type": "string"}
TOOLS = [
    ("desktop_windows", "List the open app windows on the laptop and which one is in front.", {}, [], t_windows),
    ("desktop_focus", "Bring an app window to the front. window: part of its title or its app name, e.g. \"Claude\", \"chrome\", \"Notepad\", \"Visual Studio Code\".",
     {"window": S}, ["window"], t_focus),
    ("desktop_read", "Read what's on an app window without a screenshot: its buttons, text boxes, tabs, links and list items with their names and screen positions. "
     "Use it before clicking or typing. window: which app (default: the one in front). query: only things whose name contains this.",
     {"window": S, "query": S, "limit": {"type": "integer"}}, [], t_read),
    ("desktop_click", "Click something in an app. Either element (its name, as desktop_read shows it) or x and y (screen pixels, as in a screenshot). "
     "window: which app to bring to the front first. button: left (default), right or middle. double: true for a double-click.",
     {"window": S, "element": S, "x": {"type": "integer"}, "y": {"type": "integer"}, "button": S, "double": {"type": "boolean"}}, [], t_click),
    ("desktop_type", "Type text into an app as if from the keyboard (any language). window: which app to bring to the front first. element: the text box to click first "
     "(its name, as desktop_read shows it). submit: press Enter afterwards. New lines are typed as Shift+Enter. Never type passwords, keys or card numbers.",
     {"window": S, "element": S, "text": S, "submit": {"type": "boolean"}}, ["text"], t_type),
    ("desktop_keys", "Press keys or shortcuts in an app, e.g. \"ctrl+n\", \"enter\", \"alt+tab\", \"ctrl+shift+t\", several separated by spaces: \"ctrl+a delete\". "
     "Media keys: playpause, next, previous, volumeup, volumedown, mute. window: which app to bring to the front first.",
     {"window": S, "keys": S}, ["keys"], t_keys),
    ("desktop_scroll", "Scroll in an app. direction: up, down, left or right. amount: notches (default 5). x, y: where (default: where the mouse is).",
     {"window": S, "direction": S, "amount": {"type": "integer"}, "x": {"type": "integer"}, "y": {"type": "integer"}}, [], t_scroll),
    ("claude_desktop", "Type a prompt into the Claude desktop app (Claude Code or Claude chat) and send it - for \"ask Claude to...\", \"tell Claude Code to...\". "
     "session: open this session first (its name in Claude's sidebar). new: start a new chat/session first. tab: \"code\" or \"chat\". "
     "send: false to type it without sending. Without session or new, it goes to whatever Claude has open.",
     {"prompt": S, "session": S, "new": {"type": "boolean"}, "tab": S, "send": {"type": "boolean"}}, ["prompt"], t_claude),
]
BY_NAME = {t[0]: t for t in TOOLS}


def call(name, args):
    if name not in BY_NAME:
        raise ValueError(f"Unknown tool {name}")
    if name != "desktop_windows" and name != "desktop_read":
        check_stop()
    return BY_NAME[name][4](args or {})


# ── MCP over stdio (JSON-RPC, one message per line) ───────────────────────────

def reply(msg_id, result=None, error=None):
    m = {"jsonrpc": "2.0", "id": msg_id}
    if error:
        m["error"] = error
    else:
        m["result"] = result
    sys.stdout.write(json.dumps(m) + "\n")
    sys.stdout.flush()


def serve():
    for line in sys.stdin:
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        method, mid = msg.get("method"), msg.get("id")
        if mid is None:
            continue  # a notification
        if method == "initialize":
            reply(mid, {"protocolVersion": msg.get("params", {}).get("protocolVersion", "2024-11-05"), "capabilities": {"tools": {}},
                        "serverInfo": {"name": "jarvis-hands", "version": "1.0.0"}})
        elif method == "tools/list":
            reply(mid, {"tools": [{"name": n, "description": d, "inputSchema": {"type": "object", "properties": p, "required": r}} for n, d, p, r, _ in TOOLS]})
        elif method == "tools/call":
            p = msg.get("params", {})
            try:
                text, err = call(p.get("name"), p.get("arguments")), False
            except (Stopped, LookupError, ValueError, RuntimeError) as e:
                text, err = str(e), True
            except Exception as e:
                text, err = f"{type(e).__name__}: {e}", True
            reply(mid, {"content": [{"type": "text", "text": text}], "isError": err})
        elif method == "ping":
            reply(mid, {})
        else:
            reply(mid, error={"code": -32601, "message": f"Method not found: {method}"})


if __name__ == "__main__":
    if "--test" in sys.argv:  # python hands.py --test read Claude
        what = sys.argv[sys.argv.index("--test") + 1:]
        tool = {"windows": "desktop_windows", "read": "desktop_read", "focus": "desktop_focus"}[what[0]]
        print(call(tool, {"window": " ".join(what[1:])} if len(what) > 1 else {}))
    else:
        serve()
