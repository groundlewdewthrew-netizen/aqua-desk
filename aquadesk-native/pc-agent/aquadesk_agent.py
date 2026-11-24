#!/usr/bin/env python3
"""
AquaDesk PC Agent
-----------------
Runs on your computer. Streams the screen to the AquaDesk phone app and applies
mouse / keyboard input sent from the phone.

Protocol (WebSocket, one client at a time):
  phone -> pc  (text/JSON)
    {"t":"auth","pin":"123456"}                      must be first message
    {"t":"move","x":0.5,"y":0.5}                     normalised 0..1 coords
    {"t":"click","x":..,"y":..,"b":"left|right|middle","n":1|2}
    {"t":"down"/"up","b":"left"}                     for drag
    {"t":"scroll","dy":-3}                           +down / -up (notches)
    {"t":"key","k":"enter"}                          special key name
    {"t":"text","s":"hello"}                         type text
    {"t":"cfg","fps":10,"q":55,"scale":0.6}          stream settings
  app control (separate short connection, works while the viewer is open):
    {"t":"auth","pin":"123456","mode":"cmd"}   then   {"t":"cmd","a":"open|close|list|shutdown|restart|sleep|lock|cancel","app":"chrome"}
    reply {"t":"cmdres","ok":true,"m":"Opened chrome"}
  pc -> phone
    text   {"t":"hello","w":1920,"h":1080}  /  {"t":"auth","ok":false}
    binary JPEG frame

Dependencies:  pip install pillow mss pynput      (mss + pynput recommended)
Usage:         python aquadesk_agent.py            (random PIN each run)
               python aquadesk_agent.py --pin 482913 --port 8765
"""
import threading, argparse, asyncio, base64, hashlib, hmac, io, json, os, platform, secrets, shutil, socket, struct, subprocess, sys, time, zlib

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


# ----------------------------------------------------------- screen + input --
class Screen:
    def __init__(self, fake=False):
        self.fake = fake
        self.sct = None
        if not fake:
            try:
                import mss
                self.sct = mss.mss()
            except Exception:
                self.sct = None
        from PIL import Image
        self.Image = Image

    def size(self):
        if self.fake:
            return 800, 600
        if self.sct:
            m = self.sct.monitors[1]
            return m["width"], m["height"]
        from PIL import ImageGrab
        return ImageGrab.grab().size

    def grab(self):
        """Returns (PIL image, fingerprint). Same fingerprint == screen unchanged."""
        if self.fake:
            c = int(time.time() * 50) % 255
            return self.Image.new("RGB", (800, 600), (c, 80, 160)), c
        if self.sct:
            m = self.sct.monitors[1]
            s = self.sct.grab(m)
            raw = s.bgra
            return self.Image.frombytes("RGB", s.size, raw, "raw", "BGRX"), zlib.crc32(raw)
        from PIL import ImageGrab
        img = ImageGrab.grab().convert("RGB")
        return img, zlib.crc32(img.tobytes())


class Input:
    def __init__(self, fake=False):
        self.fake = fake
        self.log = []
        self.mouse = self.kb = self.Key = self.Button = None
        if not fake:
            try:
                from pynput import mouse, keyboard
                self.mouse, self.kb = mouse.Controller(), keyboard.Controller()
                self.Key, self.Button = keyboard.Key, mouse.Button
            except Exception as e:
                print("WARNING: pynput unavailable (%s) - view-only mode." % e)

    def _btn(self, b):
        return {"left": self.Button.left, "right": self.Button.right,
                "middle": self.Button.middle}.get(b, self.Button.left)

    def do(self, ev, w, h):
        t = ev.get("t")
        if self.fake:
            self.log.append(ev)
            return
        if not self.mouse:
            return
        def pos():
            x = min(max(float(ev.get("x", 0)), 0), 1)
            y = min(max(float(ev.get("y", 0)), 0), 1)
            self.mouse.position = (int(x * (w - 1)), int(y * (h - 1)))
        if t == "move":
            pos()
        elif t == "click":
            pos(); self.mouse.click(self._btn(ev.get("b")), int(ev.get("n", 1)))
        elif t == "down":
            pos(); self.mouse.press(self._btn(ev.get("b")))
        elif t == "up":
            pos(); self.mouse.release(self._btn(ev.get("b")))
        elif t == "scroll":
            self.mouse.scroll(0, -int(ev.get("dy", 0)))
        elif t == "text":
            self.kb.type(str(ev.get("s", ""))[:500])
        elif t == "key":
            name = str(ev.get("k", "")).lower()
            key = getattr(self.Key, name, None) if len(name) > 1 else name
            if key:
                self.kb.press(key); self.kb.release(key)



# ------------------------------------------------------------ app control --
OS = {"Windows": "win", "Darwin": "mac"}.get(platform.system(), "linux")

# name -> {os: (what to launch, process name(s) to close, "|"-separated or None = never close)}
# Only apps listed here (or in apps.json next to this script) can be opened/closed.
BUILTIN = {
    "chrome":     {"win": ("chrome", "chrome.exe"), "mac": ("Google Chrome", "Google Chrome"), "linux": ("google-chrome", "chrome")},
    "firefox":    {"win": ("firefox", "firefox.exe"), "mac": ("Firefox", "Firefox"), "linux": ("firefox", "firefox")},
    "edge":       {"win": ("msedge", "msedge.exe"), "mac": ("Microsoft Edge", "Microsoft Edge"), "linux": ("microsoft-edge", "msedge")},
    "notepad":    {"win": ("notepad", "notepad.exe"), "mac": ("TextEdit", "TextEdit"), "linux": ("gedit", "gedit")},
    "calculator": {"win": ("calc", "CalculatorApp.exe|Calculator.exe"), "mac": ("Calculator", "Calculator"), "linux": ("gnome-calculator", "gnome-calculator")},
    "spotify":    {"win": ("spotify", "Spotify.exe"), "mac": ("Spotify", "Spotify"), "linux": ("spotify", "spotify")},
    "discord":    {"win": ("discord", "Discord.exe"), "mac": ("Discord", "Discord"), "linux": ("discord", "Discord")},
    "vscode":     {"win": ("code", "Code.exe"), "mac": ("Visual Studio Code", "Visual Studio Code"), "linux": ("code", "code")},
    "vlc":        {"win": ("vlc", "vlc.exe"), "mac": ("VLC", "VLC"), "linux": ("vlc", "vlc")},
    "steam":      {"win": ("steam", "steam.exe"), "mac": ("Steam", "steam_osx"), "linux": ("steam", "steam")},
    "word":       {"win": ("winword", "WINWORD.EXE"), "mac": ("Microsoft Word", "Microsoft Word"), "linux": ("libreoffice --writer", "soffice.bin")},
    "excel":      {"win": ("excel", "EXCEL.EXE"), "mac": ("Microsoft Excel", "Microsoft Excel"), "linux": ("libreoffice --calc", "soffice.bin")},
    "powerpoint": {"win": ("powerpnt", "POWERPNT.EXE"), "mac": ("Microsoft PowerPoint", "Microsoft PowerPoint"), "linux": ("libreoffice --impress", "soffice.bin")},
    "terminal":   {"win": ("wt", "WindowsTerminal.exe"), "mac": ("Terminal", "Terminal"), "linux": ("x-terminal-emulator", None)},
    # open-only (closing these would break the desktop):
    "files":      {"win": ("explorer", None), "mac": ("Finder", None), "linux": ("xdg-open .", None)},
    "settings":   {"win": ("ms-settings:", None), "mac": ("System Settings", None), "linux": ("gnome-control-center", None)},
    "task manager": {"win": ("taskmgr", "Taskmgr.exe"), "mac": ("Activity Monitor", "Activity Monitor"), "linux": ("gnome-system-monitor", "gnome-system-monitor")},
}
ALIASES = {"google chrome": "chrome", "browser": "chrome", "internet": "chrome", "microsoft edge": "edge",
           "text editor": "notepad", "calc": "calculator", "code": "vscode", "visual studio code": "vscode",
           "vs code": "vscode", "file explorer": "files", "explorer": "files", "finder": "files",
           "file manager": "files", "powerpoint": "powerpoint", "power point": "powerpoint", "cmd": "terminal",
           "command prompt": "terminal", "console": "terminal", "music": "spotify", "system settings": "settings"}


POWER_DELAY = 30   # seconds before shutdown/restart actually happens (cancellable)
POWER_CMDS = {
    "win":   {"shutdown": ["shutdown", "/s", "/t", "0"], "restart": ["shutdown", "/r", "/t", "0"],
              "sleep": ["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"],
              "lock": ["rundll32.exe", "user32.dll,LockWorkStation"]},
    "mac":   {"shutdown": ["osascript", "-e", 'tell app "System Events" to shut down'],
              "restart": ["osascript", "-e", 'tell app "System Events" to restart'],
              "sleep": ["pmset", "sleepnow"], "lock": ["pmset", "displaysleepnow"]},
    "linux": {"shutdown": ["systemctl", "poweroff"], "restart": ["systemctl", "reboot"],
              "sleep": ["systemctl", "suspend"], "lock": ["loginctl", "lock-session"]},
}


class Apps:
    def __init__(self, fake=False, allow=True, power=True):
        self.fake, self.allow, self.power, self.log = fake, allow, power, []
        self.pending = None            # threading.Timer for a delayed shutdown/restart
        self.user = {}
        f = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apps.json")
        try:
            with open(f, encoding="utf-8") as fh:        # {"obs": "C:\\path\\to\\obs64.exe", ...}
                for k, v in json.load(fh).items():
                    self.user[k.lower().strip()] = str(v)
        except Exception:
            pass

    def names(self):
        return sorted(set(BUILTIN) | set(self.user))

    def resolve(self, raw):
        n = " ".join(str(raw).lower().split())
        if n.startswith("the "): n = n[4:]
        n = ALIASES.get(n, n)
        if n in self.user:
            path = self.user[n]
            base = os.path.basename(path)
            return n, (path, base if OS == "win" else os.path.splitext(base)[0])
        if n in BUILTIN:
            return n, BUILTIN[n].get(OS) or BUILTIN[n]["linux"]
        return n, None

    def run_power(self, action):
        if not self.allow:
            return False, "Agent is in view-only mode."
        if not self.power:
            return False, "Power commands are disabled (agent started with --no-power)."
        if action == "cancel":
            if self.pending:
                self.pending.cancel(); self.pending = None
                self.log.append(("cancel",)); return True, "Cancelled. Your PC will stay on."
            return False, "Nothing to cancel."
        if self.pending:
            self.pending.cancel(); self.pending = None
        cmd = POWER_CMDS[OS][action]
        def go():
            self.pending = None
            if not self.fake:
                try: subprocess.Popen(cmd)
                except Exception as e: print("power command failed:", e)
        self.log.append((action,))
        if action in ("sleep", "lock"):        # harmless / reversible: do it now
            go(); return True, {"sleep": "Putting the PC to sleep.", "lock": "PC locked."}[action]
        self.pending = threading.Timer(POWER_DELAY, go); self.pending.daemon = True; self.pending.start()
        word = "Shutting down" if action == "shutdown" else "Restarting"
        return True, "%s in %d seconds. Say \"cancel shutdown\" to stop it." % (word, POWER_DELAY)

    def run(self, action, app):
        if action in ("shutdown", "restart", "sleep", "lock", "cancel"):
            return self.run_power(action)
        if action == "list":
            return True, "I can open/close: " + ", ".join(self.names())
        if not self.allow:
            return False, "Agent is in view-only mode."
        name, entry = self.resolve(app)
        if not entry:
            return False, "I don't know an app called '%s'. Add it to apps.json next to the agent. Known: %s" % (app, ", ".join(self.names()))
        launch, proc = entry
        if action == "close" and not proc:
            return False, "I won't close %s (it would disrupt the desktop)." % name
        if self.fake:
            self.log.append((action, name)); return True, "%s %s (fake)" % (action, name)
        try:
            if action == "open":
                return self._open(name, launch)
            if action == "close":
                return self._close(name, proc)
        except Exception as e:
            return False, "Failed to %s %s: %s" % (action, name, e)
        return False, "Unknown action"

    def _open(self, name, launch):
        if OS == "win":
            subprocess.Popen(["cmd", "/c", "start", "", launch], creationflags=0x08000000)   # CREATE_NO_WINDOW
        elif OS == "mac":
            subprocess.Popen(["open", "-a", launch])
        else:
            parts = launch.split()
            if not shutil.which(parts[0]):
                return False, "%s isn't installed on the PC (%s not found)" % (name, parts[0])
            subprocess.Popen(parts, start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True, "Opened " + name

    def _close(self, name, proc):
        if not proc:
            return False, "I won't close %s (it would disrupt the desktop)." % name
        if OS == "win":    # no /F: asks the app to close politely, so unsaved-work prompts still appear
            ok = any(subprocess.run(["taskkill", "/IM", p], capture_output=True).returncode == 0 for p in proc.split("|"))
        elif OS == "mac":
            ok = subprocess.run(["osascript", "-e", "on run argv", "-e", "tell application (item 1 of argv) to quit",
                                 "-e", "end run", proc], capture_output=True).returncode == 0
        else:              # SIGTERM = polite
            ok = subprocess.run(["pkill", "-x", proc], capture_output=True).returncode == 0
        return (True, "Closed " + name) if ok else (False, "%s doesn't seem to be running" % name)


# ----------------------------------------------- minimal RFC6455 websocket --
async def handshake(reader, writer):
    head = await reader.readuntil(b"\r\n\r\n")
    lines = head.decode("latin1").split("\r\n")
    hdr = {l.split(":", 1)[0].strip().lower(): l.split(":", 1)[1].strip()
           for l in lines[1:] if ":" in l}
    key = hdr.get("sec-websocket-key")
    if not key or hdr.get("upgrade", "").lower() != "websocket":
        writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: 14\r\n\r\nAquaDesk agent")
        await writer.drain()
        return False
    acc = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    writer.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                  "Connection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % acc).encode())
    await writer.drain()
    return True


def frame(opcode, payload: bytes) -> bytes:
    n = len(payload)
    h = bytes([0x80 | opcode])
    if n < 126: h += bytes([n])
    elif n < 65536: h += bytes([126]) + struct.pack(">H", n)
    else: h += bytes([127]) + struct.pack(">Q", n)
    return h + payload


async def read_frame(reader):
    b1, b2 = await reader.readexactly(2)
    op, n = b1 & 0x0F, b2 & 0x7F
    if n == 126: n = struct.unpack(">H", await reader.readexactly(2))[0]
    elif n == 127: n = struct.unpack(">Q", await reader.readexactly(8))[0]
    if n > 1 << 20:
        raise ConnectionError("frame too large")
    mask = await reader.readexactly(4) if b2 & 0x80 else None
    data = await reader.readexactly(n)
    if mask:
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    return op, data


# ------------------------------------------------------------------ server --
class Server:
    def __init__(self, pin, screen, inp, apps=None):
        self.pin, self.screen, self.inp = pin, screen, inp
        self.apps = apps or Apps(fake=True)
        self.busy = False
        self.fails = {}

    async def handle(self, reader, writer):
        ip = writer.get_extra_info("peername")[0]
        try:
            if not await handshake(reader, writer):
                return
            if self.fails.get(ip, 0) >= 5:
                writer.write(frame(8, b"")); return
            # --- auth (10 s window) ---
            op, data = await asyncio.wait_for(read_frame(reader), 10)
            try: msg = json.loads(data)
            except Exception: msg = {}
            ok = msg.get("t") == "auth" and hmac.compare_digest(str(msg.get("pin", "")), self.pin)
            writer.write(frame(1, json.dumps({"t": "auth", "ok": ok}).encode()))
            await writer.drain()
            if not ok:
                self.fails[ip] = self.fails.get(ip, 0) + 1
                print("Rejected connection from", ip)
                return
            self.fails[ip] = 0
            if msg.get("mode") == "cmd":          # app open/close: short connection, never blocks the viewer
                await self.command_session(reader, writer)
                return
            if self.busy:
                writer.write(frame(1, b'{"t":"error","m":"busy"}')); writer.write(frame(8, b"")); return
            self.busy = True
            print("Phone connected:", ip)
            await self.session(reader, writer)
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError):
            pass
        finally:
            writer.close()

    async def command_session(self, reader, writer):
        op, data = await asyncio.wait_for(read_frame(reader), 10)
        try: ev = json.loads(data)
        except Exception: ev = {}
        if ev.get("t") != "cmd":
            ok, m = False, "expected a cmd message"
        else:
            ok, m = await asyncio.get_running_loop().run_in_executor(
                None, self.apps.run, str(ev.get("a", "")), str(ev.get("app", "")))
        print("Command %s %r -> %s" % (ev.get("a"), ev.get("app"), m))
        writer.write(frame(1, json.dumps({"t": "cmdres", "ok": ok, "m": m}).encode()))
        writer.write(frame(8, b""))
        await writer.drain()

    async def session(self, reader, writer):
        w, h = self.screen.size()
        cfg = {"fps": 10, "q": 55, "scale": 0.6}   # ceilings (what the phone's Quality button asks for)
        cur = dict(cfg)                            # what is actually used; auto-tuned to your network
        sent, stats = [], {"rtt": 0.0, "n": 0, "bytes": 0}
        writer.write(frame(1, json.dumps({"t": "hello", "w": w, "h": h}).encode()))
        # Keep the socket buffer small so frames never queue up behind a slow phone.
        writer.transport.set_write_buffer_limits(high=64 * 1024, low=16 * 1024)
        alive = True
        MAX_INFLIGHT = 2                       # frames sent but not yet acked by the phone
        st = {"inflight": 0, "last": None, "move": None}
        slot = asyncio.Event()                 # set whenever an ack arrives / a cfg change happens
        move_evt = asyncio.Event()
        loop = asyncio.get_running_loop()

        async def sender():
            while alive:
                t0 = time.time()
                try:
                    # Flow control: wait for the phone to catch up (1 s safety timeout so a
                    # client that never acks still works, just without the latency guard).
                    if st["inflight"] >= MAX_INFLIGHT:
                        slot.clear()
                        try: await asyncio.wait_for(slot.wait(), 1.0)
                        except asyncio.TimeoutError: st["inflight"] = 0
                        continue
                    img, key = await loop.run_in_executor(None, self.grab_encode, cur, st["last"])
                    if img is not None:        # None => screen unchanged, nothing to send
                        st["last"] = key
                        writer.write(frame(2, img))
                        st["inflight"] += 1
                        sent.append(time.time()); stats["n"] += 1; stats["bytes"] += len(img)
                        await writer.drain()
                except Exception:
                    return
                await asyncio.sleep(max(0.005, 1 / cur["fps"] - (time.time() - t0)))

        async def mover():
            # Mouse-move events arrive far faster than the OS can use them; only the newest matters.
            while alive:
                await move_evt.wait(); move_evt.clear()
                ev, st["move"] = st["move"], None
                if ev: await loop.run_in_executor(None, self.inp.do, ev, w, h)

        async def tuner():
            # Once a second: if frames take too long to reach the phone, send smaller/fewer; if the
            # link is fast, creep back up toward the ceiling. Also reports speed to the phone.
            while alive:
                await asyncio.sleep(1.0)
                rtt, n, kb = stats["rtt"], stats["n"], stats["bytes"] / 1024
                if n and rtt > 0.30:
                    cur["scale"] = max(0.3, cur["scale"] * 0.85); cur["q"] = max(30, cur["q"] - 8)
                    cur["fps"] = max(4, cur["fps"] - 1)
                elif n and rtt < 0.10:
                    cur["scale"] = min(cfg["scale"], cur["scale"] * 1.08); cur["q"] = min(cfg["q"], cur["q"] + 3)
                    cur["fps"] = min(cfg["fps"], cur["fps"] + 1)
                try:
                    writer.write(frame(1, json.dumps({"t": "stat", "fps": n, "rtt": int(rtt * 1000),
                                                      "kb": int(kb / max(n, 1)), "q": int(cur["scale"] * 100)}).encode()))
                except Exception:
                    return
                stats["n"] = 0; stats["bytes"] = 0

        tasks = [asyncio.create_task(sender()), asyncio.create_task(mover()), asyncio.create_task(tuner())]
        try:
            while True:
                op, data = await read_frame(reader)
                if op == 8: break
                if op == 9: writer.write(frame(10, data)); continue
                if op != 1: continue
                try: ev = json.loads(data)
                except Exception: continue
                t = ev.get("t")
                if t == "ack":
                    st["inflight"] = max(0, st["inflight"] - 1); slot.set()
                    if sent:
                        r = time.time() - sent.pop(0)
                        stats["rtt"] = r if not stats["rtt"] else 0.7 * stats["rtt"] + 0.3 * r
                elif t == "cfg":
                    cfg["fps"] = min(max(int(ev.get("fps", cfg["fps"])), 1), 30)
                    cfg["q"] = min(max(int(ev.get("q", cfg["q"])), 20), 90)
                    cfg["scale"] = min(max(float(ev.get("scale", cfg["scale"])), 0.2), 1.0)
                    cur.update(cfg)            # new ceilings; the tuner adjusts from here
                    st["last"] = None          # force a fresh frame at the new settings
                elif t == "move":
                    st["move"] = ev; move_evt.set()
                else:
                    if st["move"]:             # keep ordering: apply the pending move first
                        pm, st["move"] = st["move"], None
                        self.inp.do(pm, w, h)
                    self.inp.do(ev, w, h)
        finally:
            alive = False
            for t in tasks: t.cancel()
            self.busy = False
            print("Phone disconnected")

    def grab_encode(self, cfg, last_key):
        """Capture + (optionally) downscale + JPEG. Returns (None, key) if the screen hasn't changed."""
        img, key = self.screen.grab()
        if key == last_key:
            return None, key
        if cfg["scale"] < 1:
            # BILINEAR + reducing_gap is several times faster than the default BICUBIC resize.
            img = img.resize((int(img.width * cfg["scale"]), int(img.height * cfg["scale"])),
                             self.screen.Image.BILINEAR, reducing_gap=2.0)
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=cfg["q"])
        return buf.getvalue(), key


def lan_ips():
    """All usable IPv4 addresses of this PC, best guess first (never 127.x)."""
    found = []
    for target in ("8.8.8.8", "10.255.255.255", "192.168.1.1"):      # UDP "connect" sends nothing
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect((target, 1)); ip = s.getsockname()[0]
            if ip not in found: found.append(ip)
        except Exception:
            pass
        finally:
            s.close()
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if ip not in found: found.append(ip)
    except Exception:
        pass
    good = [ip for ip in found if not ip.startswith(("127.", "169.254."))]
    return good or ["(not found - run ipconfig on Windows or ifconfig on Mac/Linux)"]


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--pin", default=None, help="default: random 6 digits")
    ap.add_argument("--view-only", action="store_true")
    ap.add_argument("--no-power", action="store_true", help="disable shutdown/restart/sleep/lock commands")
    ap.add_argument("--fake", action="store_true", help="synthetic screen/input (testing)")
    a = ap.parse_args()
    pin = a.pin or "%06d" % secrets.randbelow(10**6)
    srv = Server(pin, Screen(a.fake), Input(a.fake or a.view_only), Apps(a.fake, not a.view_only, not a.no_power))
    server = await asyncio.start_server(srv.handle, a.host, a.port)
    print("=" * 46)
    print(" AquaDesk PC Agent running")
    ips = lan_ips()
    print("   Address : %s:%d" % (ips[0], a.port))
    if len(ips) > 1:
        print("   Others  : %s   (try these if the first doesn't connect)" % ", ".join(ips[1:]))
    print("   PIN     : %s" % pin)
    print("   Apps    : %s" % ", ".join(srv.apps.names()))
    print("=" * 46)
    print("Unencrypted - use on home Wi-Fi or a VPN (e.g. Tailscale). Ctrl+C to stop.")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try: asyncio.run(main())
    except KeyboardInterrupt: pass
