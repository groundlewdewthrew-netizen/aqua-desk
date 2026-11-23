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
  pc -> phone
    text   {"t":"hello","w":1920,"h":1080}  /  {"t":"auth","ok":false}
    binary JPEG frame

Dependencies:  pip install pillow mss pynput      (mss + pynput recommended)
Usage:         python aquadesk_agent.py            (random PIN each run)
               python aquadesk_agent.py --pin 482913 --port 8765
"""
import argparse, asyncio, base64, hashlib, hmac, io, json, secrets, socket, struct, sys, time

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
        if self.fake:
            return self.Image.new("RGB", (800, 600), (int(time.time() * 50) % 255, 80, 160))
        if self.sct:
            m = self.sct.monitors[1]
            s = self.sct.grab(m)
            return self.Image.frombytes("RGB", s.size, s.bgra, "raw", "BGRX")
        from PIL import ImageGrab
        return ImageGrab.grab().convert("RGB")


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
    def __init__(self, pin, screen, inp):
        self.pin, self.screen, self.inp = pin, screen, inp
        self.busy = False
        self.fails = {}

    async def handle(self, reader, writer):
        ip = writer.get_extra_info("peername")[0]
        try:
            if not await handshake(reader, writer):
                return
            if self.fails.get(ip, 0) >= 5:
                writer.write(frame(8, b"")); return
            if self.busy:
                writer.write(frame(1, b'{"t":"error","m":"busy"}')); writer.write(frame(8, b"")); return
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
            self.busy = True
            print("Phone connected:", ip)
            await self.session(reader, writer)
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError):
            pass
        finally:
            writer.close()

    async def session(self, reader, writer):
        w, h = self.screen.size()
        cfg = {"fps": 8, "q": 55, "scale": 0.6}
        writer.write(frame(1, json.dumps({"t": "hello", "w": w, "h": h}).encode()))
        alive = True

        async def sender():
            while alive:
                t0 = time.time()
                try:
                    img = await asyncio.get_running_loop().run_in_executor(None, self.encode, cfg)
                    writer.write(frame(2, img))
                    await writer.drain()
                except Exception:
                    return
                await asyncio.sleep(max(0, 1 / cfg["fps"] - (time.time() - t0)))

        task = asyncio.create_task(sender())
        try:
            while True:
                op, data = await read_frame(reader)
                if op == 8: break
                if op == 9: writer.write(frame(10, data)); continue
                if op != 1: continue
                try: ev = json.loads(data)
                except Exception: continue
                if ev.get("t") == "cfg":
                    cfg["fps"] = min(max(int(ev.get("fps", cfg["fps"])), 1), 30)
                    cfg["q"] = min(max(int(ev.get("q", cfg["q"])), 20), 90)
                    cfg["scale"] = min(max(float(ev.get("scale", cfg["scale"])), 0.2), 1.0)
                else:
                    self.inp.do(ev, w, h)
        finally:
            alive = False
            task.cancel()
            self.busy = False
            print("Phone disconnected")

    def encode(self, cfg):
        img = self.screen.grab()
        if cfg["scale"] < 1:
            img = img.resize((int(img.width * cfg["scale"]), int(img.height * cfg["scale"])))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=cfg["q"])
        return buf.getvalue()


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1)); return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--pin", default=None, help="default: random 6 digits")
    ap.add_argument("--view-only", action="store_true")
    ap.add_argument("--fake", action="store_true", help="synthetic screen/input (testing)")
    a = ap.parse_args()
    pin = a.pin or "%06d" % secrets.randbelow(10**6)
    srv = Server(pin, Screen(a.fake), Input(a.fake or a.view_only))
    server = await asyncio.start_server(srv.handle, a.host, a.port)
    print("=" * 46)
    print(" AquaDesk PC Agent running")
    print("   Address : %s:%d" % (lan_ip(), a.port))
    print("   PIN     : %s" % pin)
    print("=" * 46)
    print("Unencrypted - use on home Wi-Fi or a VPN (e.g. Tailscale). Ctrl+C to stop.")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try: asyncio.run(main())
    except KeyboardInterrupt: pass
