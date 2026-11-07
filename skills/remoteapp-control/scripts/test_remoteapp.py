"""Deterministic client protocol checks; no phone or credentials required."""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import struct
import ssl
import subprocess
import shutil
from unittest import mock
import tempfile
import threading
import unittest

import remoteapp


class ClientTests(unittest.TestCase):
    def test_origin_rejects_embedded_credentials_and_queries(self):
        self.assertEqual(remoteapp.base_url("192.168.1.20:8765"), "http://192.168.1.20:8765")
        self.assertEqual(remoteapp.base_url("[::1]:8765"), "http://[::1]:8765")
        for url in ("http://key@host:8765", "http://host:8765/?key=secret", "file:///tmp/a", "http://host:0"):
            with self.assertRaises((remoteapp.ClientError, ValueError)):
                remoteapp.base_url(url)

    def test_pixels_use_encoded_dimensions_and_matching_revision(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp, "screen.json")
            path.write_text(json.dumps({"width": 864, "height": 1920, "sourceWidth": 1080, "sourceHeight": 2400, "screenRevision": 2**51 + 7}))
            args = argparse.Namespace(meta=str(path), pixels=True)
            self.assertEqual(remoteapp.point(432, 960, args), {"x": .5, "y": .5})
            self.assertEqual(remoteapp.revision(args), 2**51 + 7)
            with self.assertRaises(remoteapp.ClientError):
                remoteapp.point(float("nan"), 0, args)

    def test_http_auth_redirects_and_private_secret_files(self):
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_GET(self):
                if self.headers.get("User-Agent") != "RemoteAppControl/1.0":
                    self.send_response(403)
                    self.end_headers()
                    return
                calls.append((self.path, self.headers.get("X-API-Key"), self.headers.get("Authorization")))
                if self.path.endswith("redirect"):
                    self.send_response(302)
                    self.send_header("Location", "/must-not-follow")
                    self.end_headers()
                    return
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"ok":true}')
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            client = remoteapp.Client(f"127.0.0.1:{server.server_port}", "test-key", "test-session")
            self.assertTrue(client.json("GET", "/api/v1/device")["ok"])
            client.json("GET", "/api/v1/health", auth="none")
            client.json("GET", "/api/v1/device", auth="session")
            with self.assertRaises(remoteapp.ClientError):
                client.json("GET", "/api/v1/redirect")
            self.assertEqual(calls[:3], [("/api/v1/device", "test-key", None), ("/api/v1/health", None, None), ("/api/v1/device", None, "Bearer test-session")])
            self.assertFalse(any(path == "/must-not-follow" for path, _, _ in calls))
            args = remoteapp.parser().parse_args(["--url", f"127.0.0.1:{server.server_port}", "--no-proxy", "--ask-key", "device"])
            with mock.patch("sys.stdin.isatty", return_value=False), mock.patch("getpass.getpass") as prompt:
                with self.assertRaisesRegex(remoteapp.ClientError, "TTY"):
                    remoteapp.run(args)
                prompt.assert_not_called()
            with mock.patch("sys.stdin.isatty", return_value=True), mock.patch("getpass.getpass", return_value="hidden-key"):
                self.assertTrue(remoteapp.run(args)["ok"])
            self.assertEqual(calls[-1], ("/api/v1/device", "hidden-key", None))
        finally:
            server.shutdown()
            server.server_close()
        with tempfile.TemporaryDirectory() as temp:
            file = remoteapp.private_write(Path(temp, "token"), "private")
            self.assertEqual(os.stat(file).st_mode & 0o777, 0o600)
            self.assertEqual(remoteapp.redact({"token": "private", "ticket": "private", "ok": True}), {"token": "[redacted]", "ticket": "[redacted]", "ok": True})

    def test_secret_login_pair_ticket_and_session_revoke_use_correct_credentials(self):
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                calls.append((self.path, body, self.headers.get("X-API-Key"), self.headers.get("Authorization")))
                result = {"ticket": "test-ticket", "expiresInSeconds": 30} if self.path.endswith("stream-ticket") else {"token": "test-session", "expiresAt": 0} if self.path.endswith(("login", "claim")) else {"revoked": True}
                self.send_response(200)
                self.end_headers()
                self.wfile.write(json.dumps(result).encode())
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                folder = Path(temp)
                key = remoteapp.private_write(folder / "key", "test-key")
                password = remoteapp.private_write(folder / "password", " space-sensitive password ")
                code = remoteapp.private_write(folder / "code", "123456")
                session = str(folder / "session")
                ticket = str(folder / "ticket")
                common = ["--url", f"127.0.0.1:{server.server_port}", "--key-file", key]
                result = remoteapp.run(remoteapp.parser().parse_args(common + ["login", "--password-file", password, "--save-session", session]))
                self.assertNotIn("test-session", json.dumps(result))
                self.assertEqual(Path(session).read_text(), "test-session")
                self.assertEqual(calls[-1][1], {"password": " space-sensitive password "})
                self.assertIsNone(calls[-1][2])
                remoteapp.run(remoteapp.parser().parse_args(common + ["pair", "--code-file", code, "--save-session", session]))
                self.assertEqual(calls[-1][1], {"code": "123456"})
                remoteapp.run(remoteapp.parser().parse_args(common + ["ticket", "--save-ticket", ticket]))
                self.assertEqual(Path(ticket).read_text(), "test-ticket")
                remoteapp.run(remoteapp.parser().parse_args(common + ["--token-file", session, "revoke-session"]))
                self.assertEqual(calls[-1][2:], (None, "Bearer test-session"))
                remoteapp.run(remoteapp.parser().parse_args(common + ["revoke-all"]))
                self.assertEqual(calls[-1][2:], ("test-key", None))
        finally:
            server.shutdown()
            server.server_close()

    def test_tunnel_https_required_and_proxy_bypass(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_GET(self):
                if self.path.endswith("health"):
                    self.send_response(501)
                    self.end_headers()
                    self.wfile.write("SakuraFrp 自动 HTTPS".encode())
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{"ok":true}')
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            url = f"127.0.0.1:{server.server_port}"
            client = remoteapp.Client(url, "test-key", no_proxy=True)
            with self.assertRaisesRegex(remoteapp.ClientError, "HTTPS_REQUIRED"):
                client.json("GET", "/api/v1/health", auth="none")
            with mock.patch("urllib.request.getproxies", return_value={"http": "http://127.0.0.1:1"}), mock.patch("urllib.request.proxy_bypass", return_value=False):
                with self.assertRaisesRegex(remoteapp.ClientError, "CONNECTION_FAILED"):
                    remoteapp.Client(url, "test-key", timeout=1).json("GET", "/api/v1/device")
                self.assertTrue(remoteapp.Client(url, "test-key", no_proxy=True).json("GET", "/api/v1/device")["ok"])
        finally:
            server.shutdown()
            server.server_close()

    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL is needed to generate a local TLS fixture")
    def test_custom_certificate_and_service_name_verify_http_and_websocket(self):
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_GET(self):
                requests.append((self.path, self.headers.get("X-API-Key")))
                if self.headers.get("User-Agent") != "RemoteAppControl/1.0":
                    self.send_response(403)
                    self.end_headers()
                    return
                if self.path.endswith("stream"):
                    nonce = self.headers["Sec-WebSocket-Key"]
                    accept = base64.b64encode(hashlib.sha1((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
                    self.send_response(101)
                    self.send_header("Upgrade", "websocket")
                    self.send_header("Connection", "Upgrade")
                    self.send_header("Sec-WebSocket-Accept", accept)
                    self.end_headers()
                    meta = json.dumps({"type": "frame", "revision": 19, "width": 10, "height": 20}).encode()
                    self.wfile.write(bytes([0x81, len(meta)]) + meta + b"\x82\x06\xff\xd8ok\xff\xd9")
                    self.wfile.flush()
                    for _ in range(2):
                        first, second = remoteapp.read_exact(self.connection, 2)
                        remoteapp.read_exact(self.connection, 4 + (second & 127))
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{"ok":true}')
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            config = folder / "openssl.cnf"
            config.write_text("[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=phone.example.test\n[ext]\nsubjectAltName=DNS:phone.example.test\nbasicConstraints=critical,CA:TRUE\n")
            cert, key = folder / "cert.pem", folder / "key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-config", str(config), "-keyout", str(key), "-out", str(cert)], check=True, capture_output=True)
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(cert, key)
            server.socket = context.wrap_socket(server.socket, server_side=True)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                url = f"https://127.0.0.1:{server.server_port}"
                for options in ({}, {"ca_file": cert}, {"ca_file": cert, "tls_server_name": "wrong.example.test"}):
                    with self.assertRaisesRegex(remoteapp.ClientError, "TLS_CERTIFICATE_INVALID"):
                        remoteapp.Client(url, "not-sent", no_proxy=True, **options).json("GET", "/api/v1/device")
                self.assertEqual(requests, [])
                client = remoteapp.Client(url, "test-key", ca_file=cert, tls_server_name="phone.example.test", no_proxy=True)
                self.assertTrue(client.tls_context.check_hostname)
                self.assertTrue(client.json("GET", "/api/v1/device")["ok"])
                result = remoteapp.capture_stream(client, str(folder / "frames"), frames=1)
                self.assertEqual(result["metadata"]["revision"], 19)
                # Route the standard HTTPS port to our local TLS fixture while preserving
                # actual TLS verification, HTTP Host and WebSocket handshaking.
                connect = socket.create_connection
                def route_default_https(address, **kwargs):
                    self.assertEqual(address, ("127.0.0.1", 443))
                    return connect(("127.0.0.1", server.server_port), **kwargs)
                default_port_client = remoteapp.Client("https://127.0.0.1", "test-key",
                    ca_file=cert, tls_server_name="phone.example.test", no_proxy=True)
                with mock.patch("remoteapp.socket.create_connection", side_effect=route_default_https):
                    result = remoteapp.capture_stream(default_port_client, str(folder / "default-port"), frames=1)
                self.assertEqual(result["metadata"]["revision"], 19)
                self.assertEqual(requests, [("/api/v1/device", "test-key"), ("/api/v1/stream", "test-key"), ("/api/v1/stream", "test-key")])
                with self.assertRaisesRegex(remoteapp.ClientError, "https://"):
                    remoteapp.Client("http://127.0.0.1:1234", ca_file=cert)
            finally:
                server.shutdown()
                server.server_close()

    def test_large_binary_file_roundtrip_and_incomplete_download_cleanup(self):
        digest = hashlib.sha256()
        size = 20 * 1024 * 1024 + 123
        received = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                remaining = int(self.headers["Content-Length"])
                received.append((self.path, remaining, self.headers.get("X-API-Key")))
                while remaining:
                    block = self.rfile.read(min(64 * 1024, remaining))
                    if not block:
                        break
                    digest.update(block)
                    remaining -= len(block)
                self.send_response(201)
                self.end_headers()
                self.wfile.write(b'{"id":"file-id"}')
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", str(size))
                self.end_headers()
                if self.path.endswith("interrupted"):
                    self.wfile.write(b"partial")
                    return
                remaining = size
                while remaining:
                    chunk = b"x" * min(64 * 1024, remaining)
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            client = remoteapp.Client(f"127.0.0.1:{server.server_port}", "test-key", no_proxy=True)
            with tempfile.TemporaryDirectory() as temp:
                folder = Path(temp)
                source = folder / "source.bin"
                with source.open("wb") as output:
                    remaining = size
                    while remaining:
                        chunk = b"x" * min(64 * 1024, remaining)
                        output.write(chunk)
                        remaining -= len(chunk)
                self.assertEqual(remoteapp.upload_file(client, source, "中文 +.bin")["id"], "file-id")
                self.assertIn("%E4%B8%AD%E6%96%87%20%2B.bin", received[0][0])
                self.assertEqual(received[0][1:], (size, "test-key"))
                result = remoteapp.download_file(client, "file-id", folder / "download.bin")
                self.assertEqual(result["bytes"], size)
                with (folder / "download.bin").open("rb") as data:
                    self.assertEqual(hashlib.file_digest(data, "sha256").digest(), digest.digest())
                self.assertEqual(os.stat(folder / "download.bin").st_mode & 0o777, 0o600)
                with self.assertRaisesRegex(remoteapp.ClientError, "already exists"):
                    remoteapp.download_file(client, "file-id", folder / "download.bin")
                with self.assertRaisesRegex(remoteapp.ClientError, "interrupted"):
                    remoteapp.download_file(client, "interrupted", folder / "partial.bin")
                self.assertFalse((folder / "partial.bin").exists())
                self.assertFalse(any(path.name.startswith(".remoteapp-download-") for path in folder.iterdir()))
        finally:
            server.shutdown()
            server.server_close()

    def test_paused_stream_fails_promptly_without_saving_stale_pixels(self):
        connection, peer = socket.socketpair()
        def server():
            with peer:
                raw = bytearray()
                while not raw.endswith(b"\r\n\r\n"):
                    raw.extend(peer.recv(1))
                nonce = next(line.split(": ", 1)[1] for line in raw.decode().split("\r\n") if line.startswith("Sec-WebSocket-Key:"))
                accept = base64.b64encode(hashlib.sha1((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
                peer.sendall(f"HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: {accept}\r\n\r\n".encode())
                state = b'{"type":"state","captureState":"screen_off"}'
                peer.sendall(bytes([0x81, len(state)]) + state)
                peer.settimeout(3)
                peer.recv(1024)
        worker = threading.Thread(target=server, daemon=True)
        worker.start()
        with tempfile.TemporaryDirectory() as temp, mock.patch("remoteapp.socket.create_connection", return_value=connection):
            with self.assertRaisesRegex(remoteapp.ClientError, "screen is off"):
                remoteapp.capture_stream(remoteapp.Client("127.0.0.1:1234", "test-key"), temp)
            self.assertEqual(list(Path(temp).iterdir()), [])
        worker.join(4)
        self.assertFalse(worker.is_alive())

    def test_websocket_handshake_fragmentation_ping_and_capture(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        errors = []
        def frame(opcode, data, final=True):
            return bytes([(0x80 if final else 0) | opcode, len(data)]) + data
        def server():
            try:
                with listener.accept()[0] as connection:
                    connection.settimeout(5)
                    raw = bytearray()
                    while not raw.endswith(b"\r\n\r\n"):
                        raw.extend(connection.recv(1))
                    headers = raw.decode().split("\r\n")
                    self.assertIn("X-API-Key: test-key", headers)
                    nonce = next(line.split(": ", 1)[1] for line in headers if line.startswith("Sec-WebSocket-Key:"))
                    accept = base64.b64encode(hashlib.sha1((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
                    connection.sendall(f"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n".encode())
                    metadata = json.dumps({"type": "frame", "revision": 9, "width": 10, "height": 20}).encode()
                    connection.sendall(frame(1, metadata[:25], False) + frame(9, b"ping") + frame(0, metadata[25:]) + frame(2, b"\xff\xd8test\xff\xd9"))
                    # Every client command/control frame must be masked.
                    for expected_opcode in (1, 10, 8):
                        first, second = remoteapp.read_exact(connection, 2)
                        self.assertEqual(first & 15, expected_opcode)
                        self.assertTrue(second & 128)
                        remoteapp.read_exact(connection, 4 + (second & 127))
            except BaseException as error:
                errors.append(error)
        worker = threading.Thread(target=server, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                result = remoteapp.capture_stream(remoteapp.Client(f"127.0.0.1:{listener.getsockname()[1]}", "test-key"), temp, 1)
                self.assertEqual(result["metadata"]["revision"], 9)
                self.assertTrue(Path(result["frames"][0]).read_bytes().startswith(b"\xff\xd8"))
                self.assertEqual(json.loads(Path(result["frames"][0] + ".json").read_text())["revision"], 9)
        finally:
            worker.join(6)
            listener.close()
        self.assertFalse(worker.is_alive())
        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
