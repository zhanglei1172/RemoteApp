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
| `GET /clipboard` | — | `{text,hasText}`，读取手机剪贴板文本 |
| `POST /clipboard` | `{"text":"多行文本\n内容"}` | 写入手机剪贴板，返回 `{accepted:true}` |
| `GET /screen` | — | JPEG；尺寸和 revision 见响应头 |
| `POST /input/tap` | `{"x":0.5,"y":0.5}` | 点按 |
| `POST /input/long-press` | `{"x":0.5,"y":0.5}` | 长按约 700ms |
| `POST /input/swipe` | `{"from":{"x":0.5,"y":0.8},"to":{"x":0.5,"y":0.2},"durationMs":450}` | 滑动，时长限制在 80–10000ms |
| `POST /power/lock` | 无请求体 | 主动锁屏，返回 `{accepted,screenInteractive,keyguardLocked}` |
| `POST /power/wake` | 无请求体 | 请求亮屏，返回 `{accepted,screenInteractive,keyguardLocked}`；不会解除安全锁屏 |
| `POST /system-action` | `{"action":"HOME"}` | `BACK`、`HOME`、`RECENTS`、`NOTIFICATIONS` |
| `POST /auth/login` | `{"password":"手机预设密码"}` | 返回会话 `token`，通过 Bearer 使用 |
| `POST /session/revoke` | Bearer 会话 | 撤销当前会话；不接受 API Key 作为待撤销会话 |
| `POST /session/revoke-all` | 已认证 | 撤销全部会话，API Key 保留 |
| `POST /stream-ticket` | 已认证 | 返回 30 秒有效的单次画面流票据 |

坐标为归一化坐标，范围 `[0,1]`，左上角 `(0,0)`、右下角 `(1,1)`，`space` 默认且仅支持 `normalized`。从截图像素转换时使用 `x / 图片宽度`、`y / 图片高度`。点按、长按和滑动成功返回 `{"accepted":true,"completed":true}`，表示系统已确认手势完成；仍应再次截图确认目标界面结果。并发手势返回 `409 INPUT_BUSY`。系统动作的 `{"accepted":true}` 仅表示动作已提交。

`/screen` 返回 `X-Screen-Width`、`X-Screen-Height`、`X-Screen-Rotation`（角度）、`X-Screen-Source-Width/Height`、`X-Frame-Sequence`、`X-Frame-Age-Ms` 和 `X-Screen-Revision`。revision 只在尺寸或旋转变化时更新，普通画面刷新保持稳定。JPEG 长边最多 1920 像素，坐标仍按整张 JPEG 归一化，服务端映射到源屏尺寸；不要再手动乘缩放比例。输入可带同一截图的 `screenRevision`，防止旋转或新捕获会话中误触。

`/device` 另提供 `captureState`、`controlReady`、`sourceWidth/sourceHeight`、`displayId`、`frameSequence` 和 `lastError`。捕获失效后不返回旧截图作为成功结果。熄屏保留端口和会话，`captureState=screen_off` 且 `controlReady=false`，可以请求 `/power/wake`。亮屏后若录屏授权仍有效则自动恢复新画面；若系统停止共享，则 `captureConsentRequired=true`，需在手机点击“重新授权屏幕共享”，无需重启HTTP服务。无障碍断开仍关闭端口，需要恢复权限并重启；Key 无需重新生成。

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

当前服务使用 HTTP，仅在可信网络或 VPN 内使用。受 Android 保护的窗口可能无法截图；普通应用权限不提供任意键码、shell、多指实时事件控制。

## 可重复 API 检查

服务启动后运行 `python3 scripts/agent-smoke.py --url http://手机IP:8765`，从环境变量读取 `REMOTEAPP_API_KEY`。脚本验证认证、JPEG、源屏几何和错误响应，不发送有效屏幕手势、不修改凭据，也不保存截图。可用 `--report /tmp/remoteapp-report.json` 写入不含凭据的结果。

## 手机剪贴板

网页提供独立文本框及读取/写入按钮，不自动同步，也不依赖浏览器本地剪贴板权限。API 沿用会话/API Key 认证和 `Cache-Control: no-store`。仅处理第一项的文本，不解引用 URI；保留空格、换行及 Unicode，最多 65536 个 UTF-16 单元，超限返回 `413 CLIPBOARD_TOO_LARGE`。写入空字符串可清空文字（仍为一项空文本）；真正无剪贴板内容时读取返回 `{"text":"","hasText":false}`。

Android 10+ 读取要求 RemoteApp 窗口位于手机前台并具有焦点，否则返回 `409 CLIPBOARD_FOREGROUND_REQUIRED`；可先通过最近任务切回 RemoteApp。非文本内容返回 `409 CLIPBOARD_NOT_TEXT`，系统拒绝访问返回 `403 CLIPBOARD_ACCESS_DENIED`。写入通常可在后台进行，厂商限制以系统结果为准。Android 13+ 可能显示系统剪贴板预览并自动清理旧内容。写入成功不等同于已在目标输入框粘贴；仍需操作粘贴并观察结果。

## 锁屏、熄屏和亮屏

`/device` 增加 `screenInteractive`、`keyguardLocked`、`keyguardSecure` 和 `captureConsentRequired`。API Key/会话在熄屏后保留。`POST /power/wake` 必须认证；`accepted=true` 不保证已亮屏或已解锁，应检查返回的 `screenInteractive` 和 `/device`。若始终为false或返回403 WAKE_DENIED，请在手机按电源键；不要无限重试。

熄屏时 `/screen` 和坐标输入返回503，不提供旧图供盲操作。画面流保持连接，发送 `type:state` 消息，captureState为screen_off、starting或unavailable；只有hello/frame携带JPEG几何信息，状态消息后不能继续使用旧图/旧坐标。亮屏恢复后重新观察新图；安全锁屏保护画面可能为黑屏，仍需正常解锁。系统撤销投影后即使亮屏也不能自动恢复授权，应提示手机用户重新允许共享。

### 主动锁屏

`POST /api/v1/power/lock` 沿用密码会话/API Key认证，无需请求体，不要求当前有截图。Android9+使用无障碍GLOBAL_ACTION_LOCK_SCREEN；Android8需先在手机RemoteApp点击“启用锁屏权限（Android 8）”，并在系统界面激活仅含force-lock策略的设备管理权限。未激活返回 `409 LOCK_PERMISSION_REQUIRED`，系统拒绝返回 `409 LOCK_REJECTED` 或 `403 LOCK_DENIED`。`/device`提供 `lockAvailable`、`lockPermissionRequired` 供Agent预判。

锁屏请求成功仍只表示系统接受，应结合/device确认实际状态；HTTP/认证/画面流按熄屏规则保留，系统是否撤销录屏授权依Android版本而定。锁屏不会设置/更改密码。Android8设备管理锁屏后可能要求PIN/密码而暂不能使用指纹。停用旧版锁屏能力或卸载应用前，可在系统设备管理设置中停用RemoteApp管理员；Android9+无需该权限。

## 文件双向传输

`GET /api/v1/files`列出传输副本和额度；`POST /api/v1/files?name=URL编码文件名`以原始二进制上传，返回201及`id/name/size/createdAt`；`GET /api/v1/files/{id}`下载，`DELETE`移除副本。全部沿用现有认证。手机文件需先在App“从手机选择文件”中导入，收到的副本可“保存到手机”另存；不存在任意手机路径接口。

单文件100MiB、总量512MiB、256个文件，2路上传/2路下载；失败上传清理，下载端应校验Content-Length并清理半成品。网页用`POST /files/{id}/download-ticket`换取绑定文件/凭据的30秒单次票据，原生下载`GET /file-download/{ticket}`。上传必须提供准确的Content-Length；缺少返回411 LENGTH_REQUIRED，长度无效或截断返回400 INVALID_FILE_LENGTH/INCOMPLETE_UPLOAD，不发布副本。错误另含400 INVALID_FILENAME、404 FILE_NOT_FOUND、413 FILE_TOO_LARGE、429 TRANSFER_BUSY、507 TRANSFER_LIMIT/STORAGE_FULL/STORAGE_UNAVAILABLE。Skill专用命令流式处理大文件，详见[文件传输](../skills/remoteapp-control/references/files.md)。
