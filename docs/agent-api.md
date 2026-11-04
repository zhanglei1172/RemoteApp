# Agent 调用指南

## 连接和认证

在被控手机启用无障碍服务、启动远程服务；所有 Android 版本都需允许系统整屏捕获。跨设备访问时打开“允许局域网访问”，地址为 `http://手机IP:8765`。MIUI 强制停止应用后可能移除无障碍授权；重新打开 App 检查权限、再次启动并允许屏幕捕获即可，密码和 API Key 保留。USB 调试时可使用 `adb -s 设备序列号 forward tcp:18765 tcp:8765`，随后访问 `http://127.0.0.1:18765`。

在 App 中生成、复制 API Key，放入环境变量 `REMOTEAPP_API_KEY`。每个请求携带 `X-API-Key: <key>`，也支持 `Authorization: ApiKey <key>` 和 `Authorization: Bearer <key>`。Key 永不自动过期；重启和修改密码不影响 Key。重新生成会立即替换旧 Key，清除应用数据或卸载同样会删除 Key。Key 只在手机 UI 中生成/复制。

## 常用接口

以下路径均以 `/api/v1` 开头，JSON 请求需带 `Content-Type: application/json`。

| 方法和路径 | 请求 | 响应或用途 |
| --- | --- | --- |
| `GET /health` | 无需认证 | 服务健康状态 |
| `GET /device` | — | 设备、无障碍连接、捕获方式、尺寸、旋转和 `screenRevision` |
| `GET /screen` | — | JPEG；尺寸和 revision 见响应头 |
| `POST /input/tap` | `{"x":0.5,"y":0.5}` | 点按 |
| `POST /input/long-press` | `{"x":0.5,"y":0.5}` | 长按约 700ms |
| `POST /input/swipe` | `{"from":{"x":0.5,"y":0.8},"to":{"x":0.5,"y":0.2},"durationMs":450}` | 滑动，时长限制在 80–10000ms |
| `POST /system-action` | `{"action":"HOME"}` | `BACK`、`HOME`、`RECENTS`、`NOTIFICATIONS` |
| `POST /auth/login` | `{"password":"手机预设密码"}` | 返回会话 `token`，通过 Bearer 使用 |
| `POST /session/revoke` | Bearer 会话 | 撤销当前会话；不接受 API Key 作为待撤销会话 |
| `POST /session/revoke-all` | 已认证 | 撤销全部会话，API Key 保留 |
| `POST /stream-ticket` | 已认证 | 返回 30 秒有效的单次画面流票据 |

坐标为归一化坐标，范围 `[0,1]`，左上角 `(0,0)`、右下角 `(1,1)`，`space` 默认且仅支持 `normalized`。从截图像素转换时使用 `x / 图片宽度`、`y / 图片高度`。点按、长按和滑动成功返回 `{"accepted":true,"completed":true}`，表示系统已确认手势完成；仍应再次截图确认目标界面结果。并发手势返回 `409 INPUT_BUSY`。系统动作的 `{"accepted":true}` 仅表示动作已提交。

`/screen` 返回 `X-Screen-Width`、`X-Screen-Height`、`X-Screen-Rotation`（角度）、`X-Screen-Source-Width/Height`、`X-Frame-Sequence`、`X-Frame-Age-Ms` 和 `X-Screen-Revision`。revision 只在尺寸或旋转变化时更新，普通画面刷新保持稳定。JPEG 长边最多 1920 像素，坐标仍按整张 JPEG 归一化，服务端映射到源屏尺寸；不要再手动乘缩放比例。输入可带同一截图的 `screenRevision`，防止旋转或新捕获会话中误触。

`/device` 另提供 `captureState`、`controlReady`、`sourceWidth/sourceHeight`、`displayId`、`frameSequence` 和 `lastError`。捕获失效后不返回旧截图作为成功结果。熄屏、无障碍断开或系统停止共享会关闭端口，需要手机用户恢复授权后重新启动；Key 无需重新生成。

## Python 调用示例

只使用 Python 标准库；Key 不写入代码、URL或日志。

```python
import json
import os
import urllib.error
import urllib.request

base = os.environ.get("REMOTEAPP_URL", "http://127.0.0.1:18765")
headers = {"X-API-Key": os.environ["REMOTEAPP_API_KEY"]}

# 获取 JPEG 和与其对应的屏幕几何版本。
with urllib.request.urlopen(urllib.request.Request(base + "/api/v1/screen", headers=headers), timeout=10) as response:
    revision = int(response.headers["X-Screen-Revision"])
    with open("screen.jpg", "wb") as output:
        output.write(response.read())

# Agent 查看截图并选定位置后，发送归一化坐标。
request = urllib.request.Request(
    base + "/api/v1/input/tap",
    data=json.dumps({"x": 0.5, "y": 0.5, "screenRevision": revision}).encode(),
    headers={**headers, "Content-Type": "application/json"},
    method="POST",
)
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        print(json.load(response))
except urllib.error.HTTPError as error:
    print(error.code, json.load(error))
```

## 画面流

Agent 可直接携带认证头连接 `ws://手机IP:8765/api/v1/stream`。首条文本消息为 `{"type":"hello","width":1080,"height":1920,"rotation":0,"revision":1,"format":"jpeg"}`，之后每条二进制消息是一张完整 JPEG。每张 JPEG 前有 `type:"frame"` 的文本元数据，包含 `revision`、`width/height`、`sourceWidth/sourceHeight` 和 `sequence`；应将它与紧随的 JPEG 绑定，完成绘制后再更新可点击版本。旋转后会再次发送更新的 hello。客户端可发送 `{"type":"setQuality","fps":5}` 请求 1–15 FPS 的发送上限，底层编码最多约 5 FPS；重复帧不代表新画面。最多同时 4 路流，超限以 1013 关闭。

浏览器使用已认证的 `POST /stream-ticket` 获取 `ticket`，再连接 `/api/v1/stream?ticket=...`，避免把永久凭据放入 URL。票据单次使用、30 秒过期并绑定原凭据；会话撤销或 Key 轮换会关闭对应连接。未认证的握手直接返回 HTTP 401。

## 错误处理

错误响应为 `{"error":"错误码","message":"说明","requestId":"追踪ID"}`。Agent 应按状态处理：

- `400 INVALID_COORDINATE / INVALID_REQUEST`：检查坐标范围、请求字段和 JSON 格式。
- `401 UNAUTHORIZED / AUTH_FAILED`：检查 Key、会话或密码；密码连续失败有 1–32 秒退避。
- `409 SCREEN_CHANGED`：重新截图、重新确定目标后发送新 revision，勿盲目重试原坐标。
- `409 INPUT_BUSY / GESTURE_CANCELLED`：等待已有操作结束或重新观察画面，不自动重放取消的操作。
- `504 GESTURE_TIMEOUT`：系统未确认完成，控制服务会停止；也可能直接表现为连接关闭，需要回到手机重新启动。
- `415 UNSUPPORTED_MEDIA_TYPE`：使用 JSON Content-Type。
- `429 TICKET_UNAVAILABLE`：暂缓申请票据；每个服务最多保留 128 张未使用票据。
- `503 CAPTURE_UNAVAILABLE / ACCESSIBILITY_UNAVAILABLE`：检查手机上的捕获授权、无障碍状态，旋转切换时可稍后重试。

当前服务使用 HTTP，仅在可信网络或 VPN 内使用。受 Android 保护的窗口可能无法截图；普通应用权限不提供任意键码、shell、多指实时事件或剪贴板控制。

## 可重复 API 检查

服务启动后运行 `python3 scripts/agent-smoke.py --url http://手机IP:8765`，从环境变量读取 `REMOTEAPP_API_KEY`。脚本验证认证、JPEG、源屏几何和错误响应，不发送有效屏幕手势、不修改凭据，也不保存截图。可用 `--report /tmp/remoteapp-report.json` 写入不含凭据的结果。
