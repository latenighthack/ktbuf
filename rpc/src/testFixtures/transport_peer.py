"""Loopback HTTP/WebSocket peer for actual JavaScript and Foundation regressions."""
import base64
import hashlib
import select
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

cancelled = set()
started = set()
handshakes = {}
lock = threading.Lock()
PROTOCOL = "ktbuf.stream.v1"
END = "ktbuf.requests.end"

class Peer(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_): pass
    def cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "authorization, content-type, x-caller")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Expose-Headers", "grpc-status, x-colon, x-repeat, x-empty")
    def reply(self, code, body=b"", headers=()):
        self.send_response(code)
        self.cors()
        self.send_header("Content-Length", str(len(body)))
        for name, value in headers: self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)
    def do_OPTIONS(self): self.reply(204)
    def do_POST(self): self.handle_http()
    def do_GET(self):
        if self.headers.get("Upgrade", "").lower() == "websocket": self.websocket()
        else: self.handle_http()
    def handle_http(self):
        parsed = urlsplit(self.path)
        identifier = parse_qs(parsed.query).get("id", [""])[0]
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            if parsed.path.endswith("/started"):
                with lock: result = identifier in started
                self.reply(200, str(result).lower().encode())
            elif parsed.path.endswith("/cancelled"):
                with lock: result = identifier in cancelled
                self.reply(200, str(result).lower().encode())
            elif parsed.path.endswith("/handshake"):
                with lock: result = handshakes.get(identifier, "")
                self.reply(200, result.encode())
            elif parsed.path.endswith("/Hang"):
                with lock: started.add(identifier)
                deadline = time.monotonic() + 3
                while time.monotonic() < deadline:
                    readable, _, _ = select.select([self.connection], [], [], 0.03)
                    if readable and not self.connection.recv(1, socket.MSG_PEEK):
                        with lock: cancelled.add(identifier)
                        self.close_connection = True
                        return
                self.reply(200, body)
            elif parsed.path.endswith("/Error"):
                self.reply(409, b"arbitrary error detail", [("grpc-status", "6")])
            else:
                self.reply(200, body, [("X-Colon", "value:with:colons"), ("X-Repeat", "first"), ("X-Repeat", "second"), ("X-Empty", "")])
        except (OSError, BrokenPipeError):
            if parsed.path.endswith("/Hang"):
                with lock: cancelled.add(identifier)
    def frame(self, opcode, payload=b""):
        size = len(payload)
        header = bytes([0x80 | opcode])
        header += bytes([size]) if size < 126 else bytes([126]) + struct.pack("!H", size) if size < 65536 else bytes([127]) + struct.pack("!Q", size)
        self.connection.sendall(header + payload)
    def exact(self, count):
        result = b""
        while len(result) < count:
            data = self.connection.recv(count - len(result))
            if not data: raise EOFError()
            result += data
        return result
    def websocket(self):
        parsed = urlsplit(self.path)
        identifier = parse_qs(parsed.query).get("id", [""])[0]
        if parsed.path.endswith("/FailHandshake"):
            self.reply(403, b"denied", [("grpc-status", "7")]); return
        accept = base64.b64encode(hashlib.sha1((self.headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        offered = [value.strip() for value in self.headers.get("Sec-WebSocket-Protocol", "").split(",")]
        if PROTOCOL in offered: self.send_header("Sec-WebSocket-Protocol", PROTOCOL)
        self.end_headers()
        with lock:
            handshakes[identifier] = self.headers.get("Authorization", "")
            started.add(identifier)
        self.connection.settimeout(10)
        self.close_connection = True
        try:
            if parsed.path.endswith("/Burst"):
                for number in range(1, 33): self.frame(2, bytes([number]))
                self.frame(8, struct.pack("!H", 1000))
            elif parsed.path.endswith("/Text"):
                self.frame(1, b"not protobuf")
            elif parsed.path.endswith("/Error"):
                self.frame(8, struct.pack("!H", 4013) + b"arbitrary error detail")
            while True:
                first, second = self.exact(2)
                opcode = first & 15
                size = second & 127
                if size == 126: size = struct.unpack("!H", self.exact(2))[0]
                elif size == 127: size = struct.unpack("!Q", self.exact(8))[0]
                if size > 16777216: raise ValueError("oversized fixture request")
                mask = self.exact(4) if second & 128 else b""
                data = self.exact(size)
                if mask: data = bytes(value ^ mask[index % 4] for index, value in enumerate(data))
                if opcode == 8:
                    if not parsed.path.endswith(("/Burst", "/Error")): self.frame(8, struct.pack("!H", 1000))
                    break
                if opcode == 9: self.frame(10, data)
                elif opcode == 2: self.frame(2, data)
                elif opcode == 1 and data.decode() == END:
                    self.frame(8, struct.pack("!H", 1000))
        except (EOFError, OSError): pass
        finally:
            with lock: cancelled.add(identifier)

server = ThreadingHTTPServer(("127.0.0.1", 0), Peer)
server.daemon_threads = True
print(f"http://127.0.0.1:{server.server_port}", flush=True)
server.serve_forever()
