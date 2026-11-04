#!/usr/bin/env python3
"""Exercise a running device without changing credentials or sending screen gestures."""
import argparse
import json
import os
import struct
import urllib.error
import urllib.request


def jpeg_size(data):
    if data[:2] != b"\xff\xd8":
        raise AssertionError("Response is not JPEG")
    offset = 2
    while offset < len(data):
        if data[offset] != 0xFF:
            offset += 1
            continue
        while data[offset] == 0xFF:
            offset += 1
        marker = data[offset]
        offset += 1
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        length = struct.unpack_from(">H", data, offset)[0]
        if marker in (0xC0, 0xC1, 0xC2):
            height, width = struct.unpack_from(">HH", data, offset + 3)
            return width, height
        offset += length
    raise AssertionError("JPEG dimensions are missing")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=os.environ.get("REMOTEAPP_URL", "http://127.0.0.1:18765"))
    parser.add_argument("--report", help="Optional sanitized JSON report path")
    args = parser.parse_args()
    key = os.environ.get("REMOTEAPP_API_KEY")
    if not key:
        parser.error("Set REMOTEAPP_API_KEY; do not pass credentials as command-line arguments")
    results = []

    def request(path, body=None, headers=None):
        auth = {"X-API-Key": key} if headers is None else headers
        request_headers = {"Content-Type": "application/json", **auth}
        payload = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(args.url.rstrip("/") + "/api/v1/" + path, data=payload, headers=request_headers)
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.code, response.headers, response.read()

    def check(name, condition):
        results.append({"name": name, "ok": bool(condition)})
        print(("PASS " if condition else "FAIL ") + name)

    status, _, body = request("health", headers={})
    check("health without credentials", status == 200 and json.loads(body)["ok"])
    for name, headers in [
        ("X-API-Key", {"X-API-Key": key}),
        ("ApiKey", {"Authorization": "ApiKey " + key}),
        ("Bearer", {"Authorization": "Bearer " + key}),
    ]:
        status, _, body = request("device", headers=headers)
        check(name + " authentication", status == 200)
    device = json.loads(body)
    check("capture and control ready", device.get("captureMode") == "media_projection" and device.get("controlReady"))
    for path in ("device", "screen"):
        check("missing credentials: " + path, request(path, headers={})[0] == 401)
    check("invalid key rejected", request("device", headers={"X-API-Key": "invalid"})[0] == 401)
    status, headers, image = request("screen")
    check("JPEG dimensions match headers", status == 200 and jpeg_size(image) ==
          (int(headers["X-Screen-Width"]), int(headers["X-Screen-Height"])))
    revision = int(headers["X-Screen-Revision"])
    check("revision is a JavaScript safe integer", 0 < revision < 2**53)
    check("source geometry is available", int(headers["X-Screen-Source-Width"]) > 0 and int(headers["X-Screen-Source-Height"]) > 0)
    for body in ({"x": -0.1, "y": 0.5}, {"x": 0.5, "y": 1.1}, {"x": 0.5, "y": 0.5, "space": "physical"}):
        check("invalid coordinate rejected", request("input/tap", body)[0] == 400)
    status, _, body = request("input/tap", {"x": 0.5, "y": 0.5, "screenRevision": -1})
    check("stale revision rejected", status == 409 and json.loads(body)["error"] == "SCREEN_CHANGED")
    for malformed in (b"{", b"{}", b'{"x":"bad","y":0.5}'):
        status, _, body = request("input/tap", malformed)
        check("invalid JSON/schema is structured", status == 400 and json.loads(body)["error"] == "INVALID_REQUEST")
    check("unsupported system action rejected", request("system-action", {"action": "SHELL"})[0] == 400)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as report:
            json.dump(results, report, indent=2)
    raise SystemExit(0 if all(result["ok"] for result in results) else 1)


if __name__ == "__main__":
    main()
