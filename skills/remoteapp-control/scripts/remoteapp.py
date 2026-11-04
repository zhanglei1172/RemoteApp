#!/usr/bin/env python3
"""RemoteApp HTTP and finite WebSocket capture client. Python standard library only."""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import socket
import ssl
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request

MAX_MESSAGE = 16 * 1024 * 1024


class ClientError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def private_write(path, data):
    path = Path(path).expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb", closefd=False) as output:
            output.write(data if isinstance(data, bytes) else data.encode())
    finally:
        os.close(fd)
    return str(path.resolve())


def secret_value(path, env_name, header=True):
    value = Path(path).expanduser().read_text() if path else os.environ.get(env_name, "")
    if not header:
        return value
    value = value.strip()
    if "\r" in value or "\n" in value:
        raise ClientError("Credential must contain a single value")
    return value


def base_url(value):
    parsed = urllib.parse.urlsplit(value if "://" in value else "http://" + value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ClientError("Use http(s)://host:port without credentials")
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise ClientError("Provide the server origin only, without a path, query or fragment")
    port = parsed.port if parsed.port is not None else (443 if parsed.scheme == "https" else 8765)
    if not 1 <= port <= 65535:
        raise ClientError("Port must be between 1 and 65535")
    host = "[" + parsed.hostname + "]" if ":" in parsed.hostname else parsed.hostname
    return f"{parsed.scheme}://{host}:{port}"


def redact(value):
    if isinstance(value, dict):
        return {key: "[redacted]" if key.lower().replace("_", "") in
                {"token", "ticket", "password", "apikey", "code", "authorization"} else redact(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


class Client:
    def __init__(self, url, key="", token="", timeout=15):
        self.url = base_url(url)
        self.key = key
        self.token = token
        self.timeout = timeout
        self.opener = urllib.request.build_opener(NoRedirect)

    def headers(self, auth="auto"):
        if auth == "none":
            return {}
        if auth != "session" and self.key:
            return {"X-API-Key": self.key}
        if self.token:
            return {"Authorization": "Bearer " + self.token}
        raise ClientError("Authentication required: set REMOTEAPP_API_KEY or a session token file")

    def request(self, method, path, body=None, auth="auto"):
        if not path.startswith("/api/v1/") or any(char in path for char in ("?", "#", "\r", "\n")):
            raise ClientError("API path must begin /api/v1/ and contain no query or fragment")
        headers = {"Content-Type": "application/json", **self.headers(auth)}
        data = json.dumps(body, allow_nan=False).encode() if body is not None else None
        request = urllib.request.Request(self.url + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=self.timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read(MAX_MESSAGE + 1)
            if len(payload) > MAX_MESSAGE:
                raise ClientError("Response exceeds the capture size limit")
            if not 200 <= response.code < 300:
                try:
                    detail = redact(json.loads(payload))
                except (ValueError, UnicodeError):
                    detail = {"error": "NON_JSON_ERROR"}
                message = json.dumps({"status": response.code, "detail": detail}, ensure_ascii=False)
                for secret in (self.key, self.token, *(body.get(field, "") for field in ("password", "code") if isinstance(body, dict))):
                    if isinstance(secret, str) and secret:
                        message = message.replace(secret, "[redacted]")
                raise ClientError(message)
            return response.headers, payload

    def json(self, method, path, body=None, auth="auto"):
        _, payload = self.request(method, path, body, auth)
        return json.loads(payload)


def read_exact(connection, count):
    chunks = bytearray()
    while len(chunks) < count:
        chunk = connection.recv(count - len(chunks))
        if not chunk:
            raise ClientError("Screen stream disconnected")
        chunks.extend(chunk)
    return bytes(chunks)


def send_frame(connection, opcode, payload=b""):
    mask = secrets.token_bytes(4)
    size = len(payload)
    header = bytes([0x80 | opcode, 0x80 | size]) if size < 126 else bytes([0x80 | opcode, 0xFE]) + struct.pack(">H", size)
    connection.sendall(header + mask + bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload)))


def read_message(connection):
    fragments = bytearray()
    message_opcode = None
    while True:
        first, second = read_exact(connection, 2)
        if first & 0x70 or second & 0x80:
            raise ClientError("Unexpected WebSocket extension or masked server frame")
        opcode, final, size = first & 0x0F, bool(first & 0x80), second & 0x7F
        if size == 126:
            size = struct.unpack(">H", read_exact(connection, 2))[0]
        elif size == 127:
            size = struct.unpack(">Q", read_exact(connection, 8))[0]
        if size > MAX_MESSAGE or len(fragments) + size > MAX_MESSAGE:
            raise ClientError("Screen stream message exceeds size limit")
        payload = read_exact(connection, size)
        if opcode >= 8:
            if not final or size > 125:
                raise ClientError("Invalid WebSocket control frame")
            if opcode == 8:
                code = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else 1005
                raise ClientError(f"Screen stream closed ({code})")
            if opcode == 9:
                send_frame(connection, 10, payload)
            elif opcode != 10:
                raise ClientError("Unsupported WebSocket control frame")
            continue
        if opcode in (1, 2) and message_opcode is None:
            message_opcode = opcode
        elif opcode != 0 or message_opcode is None:
            raise ClientError("Invalid fragmented WebSocket message")
        fragments.extend(payload)
        if final:
            return message_opcode, bytes(fragments)


def capture_stream(client, output_dir, frames=3, fps=5, ticket=""):
    origin = urllib.parse.urlsplit(client.url)
    connection = socket.create_connection((origin.hostname, origin.port), timeout=client.timeout)
    try:
        if origin.scheme == "https":
            connection = ssl.create_default_context().wrap_socket(connection, server_hostname=origin.hostname)
        nonce = base64.b64encode(secrets.token_bytes(16)).decode()
        path = "/api/v1/stream" + ("?ticket=" + urllib.parse.quote(ticket, safe="") if ticket else "")
        headers = {"Host": origin.netloc, "Upgrade": "websocket", "Connection": "Upgrade",
                   "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": nonce,
                   **({} if ticket else client.headers())}
        connection.sendall((f"GET {path} HTTP/1.1\r\n" + "".join(f"{key}: {value}\r\n" for key, value in headers.items()) + "\r\n").encode())
        raw = bytearray()
        while not raw.endswith(b"\r\n\r\n"):
            raw.extend(read_exact(connection, 1))
            if len(raw) > 16384:
                raise ClientError("Invalid screen-stream handshake")
        lines = raw.decode("iso-8859-1").split("\r\n")
        if lines[0].split()[1] != "101":
            raise ClientError("Screen-stream handshake failed: HTTP " + lines[0].split()[1])
        received = {key.lower(): value.strip() for key, value in (line.split(":", 1) for line in lines[1:] if ":" in line)}
        expected = base64.b64encode(hashlib.sha1((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        if received.get("sec-websocket-accept", "").strip() != expected:
            raise ClientError("Screen-stream handshake verification failed")
        send_frame(connection, 1, json.dumps({"type": "setQuality", "fps": fps}).encode())
        metadata = None
        paths = []
        for _ in range(frames * 10 + 20):
            opcode, payload = read_message(connection)
            if opcode == 1:
                message = json.loads(payload)
                if message.get("type") in ("hello", "frame"):
                    metadata = message
                continue
            if opcode != 2 or not payload.startswith(b"\xff\xd8") or metadata is None:
                raise ClientError("Screen stream did not provide JPEG with geometry metadata")
            path = Path(output_dir) / f"frame-{len(paths) + 1:04d}.jpg"
            paths.append(private_write(path, payload))
            private_write(str(path) + ".json", json.dumps(metadata, ensure_ascii=False))
            if len(paths) == frames:
                send_frame(connection, 8, struct.pack(">H", 1000))
                return {"frames": paths, "metadata": metadata}
        raise ClientError("Screen stream did not deliver the requested frame count")
    finally:
        connection.close()


def point(x, y, args):
    if getattr(args, "pixels", False):
        if not args.meta:
            raise ClientError("Pixel coordinates require --meta from the corresponding screenshot")
        meta = json.loads(Path(args.meta).read_text())
        if meta.get("width", 0) <= 0 or meta.get("height", 0) <= 0:
            raise ClientError("Screenshot metadata has no valid JPEG dimensions")
        x, y = x / meta["width"], y / meta["height"]
    if not all(math.isfinite(value) and 0 <= value <= 1 for value in (x, y)):
        raise ClientError("Coordinates must be finite values in [0,1]")
    return {"x": x, "y": y}


def revision(args):
    if args.meta:
        metadata = json.loads(Path(args.meta).read_text())
        value = metadata.get("screenRevision", metadata.get("revision"))
    else:
        value = args.revision
    if isinstance(value, bool) or not isinstance(value, int) or not 0 < value < 2**53:
        raise ClientError("A valid screenshot revision is required")
    return value


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--url", default=os.environ.get("REMOTEAPP_URL", ""), help="IP:port or http(s)://host:port")
    root.add_argument("--key-file", help="Private file containing API Key; alternatively REMOTEAPP_API_KEY")
    root.add_argument("--token-file", help="Private file containing session token; alternatively REMOTEAPP_SESSION_TOKEN")
    root.add_argument("--timeout", type=float, default=15)
    sub = root.add_subparsers(dest="command", required=True)
    for command in ("health", "device", "revoke-session", "revoke-all"):
        sub.add_parser(command)
    for command in ("login", "pair"):
        p = sub.add_parser(command)
        p.add_argument("--password-file" if command == "login" else "--code-file")
        p.add_argument("--save-session", required=True)
    p = sub.add_parser("screen")
    p.add_argument("--output", required=True, help="JPEG path; metadata is saved beside it as .json")
    p = sub.add_parser("ticket")
    p.add_argument("--save-ticket", required=True)
    p = sub.add_parser("stream")
    p.add_argument("--output-dir", required=True)
    p.add_argument("--frames", type=int, default=3)
    p.add_argument("--fps", type=int, default=5)
    p.add_argument("--ticket-file")
    for command in ("tap", "long-press", "swipe"):
        p = sub.add_parser(command)
        for name in (("x", "y", "to_x", "to_y") if command == "swipe" else ("x", "y")):
            p.add_argument(name, type=float)
        group = p.add_mutually_exclusive_group(required=True)
        group.add_argument("--meta", help="Screenshot/stream .json metadata sidecar")
        group.add_argument("--revision", type=int)
        p.add_argument("--pixels", action="store_true")
        if command == "swipe":
            p.add_argument("--duration-ms", type=int, default=450)
    p = sub.add_parser("action")
    p.add_argument("action", choices=["BACK", "HOME", "RECENTS", "NOTIFICATIONS"])
    p = sub.add_parser("request", help="Call another existing /api/v1 endpoint; no retries")
    p.add_argument("method", choices=["GET", "POST"])
    p.add_argument("path")
    p.add_argument("--json-file")
    p.add_argument("--output", help="Save raw response privately instead of printing redacted JSON")
    return root


def run(args):
    if not args.url:
        raise ClientError("Provide the phone IP and port with --url or REMOTEAPP_URL")
    if not 0 < args.timeout <= 120:
        raise ClientError("Timeout must be in (0,120] seconds")
    client = Client(args.url, secret_value(args.key_file, "REMOTEAPP_API_KEY"),
                    secret_value(args.token_file, "REMOTEAPP_SESSION_TOKEN"), args.timeout)
    command = args.command
    if command in ("health", "device"):
        return client.json("GET", "/api/v1/" + command, auth="none" if command == "health" else "auto")
    if command in ("login", "pair"):
        value = secret_value(args.password_file, "REMOTEAPP_PASSWORD", header=False) if command == "login" else secret_value(args.code_file, "REMOTEAPP_PAIRING_CODE")
        if not value:
            raise ClientError("Supply the password or pairing code via its environment variable or private file")
        data = client.json("POST", "/api/v1/auth/login" if command == "login" else "/api/v1/pairing/claim",
                           {"password" if command == "login" else "code": value}, auth="none")
        return {"authenticated": True, "sessionFile": private_write(args.save_session, data["token"])}
    if command == "screen":
        headers, payload = client.request("GET", "/api/v1/screen")
        if not payload.startswith(b"\xff\xd8"):
            raise ClientError("Screenshot response is not JPEG")
        fields = {"screenRevision": "X-Screen-Revision", "width": "X-Screen-Width", "height": "X-Screen-Height",
                  "rotation": "X-Screen-Rotation", "sourceWidth": "X-Screen-Source-Width", "sourceHeight": "X-Screen-Source-Height",
                  "sequence": "X-Frame-Sequence", "frameAgeMs": "X-Frame-Age-Ms"}
        metadata = {key: int(headers[header]) for key, header in fields.items() if headers.get(header) is not None}
        return {"image": private_write(args.output, payload), "metadataFile": private_write(args.output + ".json", json.dumps(metadata)), **metadata}
    if command in ("tap", "long-press", "swipe"):
        coordinate = point(args.x, args.y, args)
        body = {**coordinate, "screenRevision": revision(args)}
        if command == "swipe":
            if not 80 <= args.duration_ms <= 10000:
                raise ClientError("Swipe duration must be between 80 and 10000ms")
            body = {"from": coordinate, "to": point(args.to_x, args.to_y, args),
                    "durationMs": args.duration_ms, "screenRevision": revision(args)}
        return client.json("POST", "/api/v1/input/" + command, body)
    if command == "action":
        return client.json("POST", "/api/v1/system-action", {"action": args.action})
    if command in ("revoke-session", "revoke-all"):
        return client.json("POST", "/api/v1/session/" + ("revoke" if command == "revoke-session" else "revoke-all"),
                           {}, auth="session" if command == "revoke-session" else "auto")
    if command == "ticket":
        data = client.json("POST", "/api/v1/stream-ticket", {})
        return {"ticketFile": private_write(args.save_ticket, data["ticket"]), "expiresInSeconds": data.get("expiresInSeconds", 30)}
    if command == "stream":
        if not 1 <= args.frames <= 300 or not 1 <= args.fps <= 15:
            raise ClientError("Choose 1–300 frames and 1–15 FPS")
        return capture_stream(client, args.output_dir, args.frames, args.fps,
                              secret_value(args.ticket_file, "REMOTEAPP_STREAM_TICKET") if args.ticket_file else "")
    body = json.loads(Path(args.json_file).read_text()) if args.json_file else None
    _, payload = client.request(args.method, args.path, body)
    return {"output": private_write(args.output, payload)} if args.output else redact(json.loads(payload))


def main():
    args = parser().parse_args()
    try:
        print(json.dumps(redact(run(args)), ensure_ascii=False))
    except (ClientError, OSError, ValueError, KeyError, ssl.SSLError) as error:
        message = str(error)
        for name in ("REMOTEAPP_API_KEY", "REMOTEAPP_SESSION_TOKEN", "REMOTEAPP_PASSWORD", "REMOTEAPP_PAIRING_CODE"):
            value = os.environ.get(name)
            if value:
                message = message.replace(value, "[redacted]")
        print(json.dumps({"ok": False, "error": message}, ensure_ascii=False), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
