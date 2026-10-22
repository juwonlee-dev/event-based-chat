import json, os, queue, sys, threading, time

import websocket  # websocket-client(requirements.txt)

URL = os.environ.get("STOMP_URL", "ws://127.0.0.1:8080/ws")


class Frame:
    def __init__(self, command, headers, body):
        self.command, self.headers, self.body = command, headers, body

    def json(self):
        return json.loads(self.body) if self.body else None

    def __repr__(self):
        return f"{self.command} {self.headers} {self.body[:200]}"


def parse(text):
    head, _, body = text.partition("\n\n")
    lines = head.split("\n")
    headers = {}
    for line in lines[1:]:
        k, _, v = line.partition(":")
        headers.setdefault(k, v)
    return Frame(lines[0], headers, body.rstrip("\x00"))


class Stomp:
    def __init__(self, token=None, heartbeat="5000,10000", connect=True, extra=None):
        self.ws = websocket.create_connection(URL, suppress_origin=True, timeout=30)
        self.upstream = (self.ws.getheaders() or {}).get("x-upstream")
        self.frames = queue.Queue()
        self.backlog = []
        self.close_code = None
        self.paused = False
        self.closed = threading.Event()
        self.connected = None
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._beat, daemon=True).start()
        if connect:
            headers = {"accept-version": "1.2", "host": "localhost", "heart-beat": heartbeat}
            if token:
                headers["Authorization"] = "Bearer " + token
            headers.update(extra or {})
            self.frame("CONNECT", headers)
            self.connected = self.expect(lambda f: f.command in ("CONNECTED", "ERROR"), 10)

    def _read(self):
        try:
            while True:
                opcode, data = self.ws.recv_data(control_frame=True)
                if opcode == websocket.ABNF.OPCODE_CLOSE:
                    self.close_code = int.from_bytes(data[:2], "big") if len(data) >= 2 else None
                    break
                if opcode in (websocket.ABNF.OPCODE_TEXT, websocket.ABNF.OPCODE_BINARY):
                    text = data.decode("utf-8")
                    if text.strip():
                        self.frames.put(parse(text))
        except Exception:
            pass
        finally:
            self.closed.set()

    def _beat(self):
        while not self.closed.is_set():
            time.sleep(4)
            if self.paused:
                continue
            try:
                self.ws.send("\n")
            except Exception:
                return

    def frame(self, command, headers, body=""):
        text = command + "\n" + "".join(f"{k}:{v}\n" for k, v in headers.items()) + "\n" + body + "\x00"
        self.ws.send(text)

    def subscribe(self, destination, sub_id, **extra):
        headers = {"id": sub_id, "destination": destination}
        headers.update({k.replace("_", "-"): str(v) for k, v in extra.items()})
        self.frame("SUBSCRIBE", headers)

    def send(self, destination, body):
        self.frame("SEND", {"destination": destination, "content-type": "application/json"}, json.dumps(body))

    def expect(self, pred, timeout=5):
        for i, f in enumerate(self.backlog):
            if pred(f):
                return self.backlog.pop(i)
        end = time.time() + timeout
        while time.time() < end:
            try:
                f = self.frames.get(timeout=max(0.01, end - time.time()))
            except queue.Empty:
                break
            if pred(f):
                return f
            self.backlog.append(f)
        return None

    def drain(self, wait=0.5):
        time.sleep(wait)
        while True:
            try:
                self.backlog.append(self.frames.get_nowait())
            except queue.Empty:
                break
        out, self.backlog = self.backlog, []
        return out

    def wait_closed(self, timeout=5):
        return self.closed.wait(timeout)

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def on_sub(sub_id):
    return lambda f: f.command == "MESSAGE" and f.headers.get("subscription") == sub_id


def receipt(pred=lambda j: True):
    return lambda f: f.command == "MESSAGE" and f.headers.get("subscription") == "receipts" and pred(f.json())
