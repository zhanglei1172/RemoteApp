# RemoteApp API 与客户端命令

路径前缀 `/api/v1`。除 health、密码登录和配对外都需要认证。JSON 使用 `Content-Type: application/json`。`X-API-Key`、`Authorization: ApiKey <key>` 和 `Authorization: Bearer <key>` 均支持 API Key；会话使用 Bearer。API Key 永不自动过期；手机重新生成、清除数据或卸载会使它失效。更改密码、停止服务或重启不会删除 Key。

## 接口映射

| HTTP | 路径 | Python 客户端子命令 | 请求/结果 |
| --- | --- | --- | --- |
| GET | `/health` | `health` | 无需认证；ok/server/port |
| GET | `/device` | `device` | 无障碍、捕获方式、画面尺寸/旋转/revision、LAN；新版本含 controlReady、captureState、sourceWidth/Height、displayId、frameSequence、lastError |
| GET | `/clipboard` | `request GET /api/v1/clipboard --output file` | `{text,hasText}`；Android10+需RemoteApp前台焦点 |
| POST | `/clipboard` | `request POST /api/v1/clipboard --json-file file` | `{text}`；上限65536 UTF-16单元，空文本覆盖 |
| GET | `/screen` | `screen --output path.jpg` | JPEG；保存头信息至 path.jpg.json |
| POST | `/input/tap` | `tap x y --meta file` | x/y、可选 space=normalized、screenRevision |
| POST | `/input/long-press` | `long-press x y --meta file` | 同 tap，约 700ms |
| POST | `/input/swipe` | `swipe x y to_x to_y --meta file` | from/to、durationMs（80–10000，默认450）、screenRevision |
| POST | `/power/lock` | `request POST /api/v1/power/lock` | 主动锁屏；Android8需本地设备管理授权，缺少时409 LOCK_PERMISSION_REQUIRED |
| POST | `/power/wake` | `request POST /api/v1/power/wake` | 请求亮屏；返回accepted/screenInteractive/keyguardLocked，不解除安全锁屏 |
| POST | `/system-action` | `action BACK` | BACK/HOME/RECENTS/NOTIFICATIONS |
| POST | `/auth/login` | `login --save-session file` | password；返回 token，脚本保存而不打印 |
| POST | `/pairing/claim` | `pair --save-session file` | code；一次性6位配对码，手机生成后5分钟内有效 |
| POST | `/session/revoke` | `revoke-session` | 仅接受有效会话；客户端要求 session token，不能用永久 Key 撤销自己 |
| POST | `/session/revoke-all` | `revoke-all` | 撤销所有会话，保留 API Key；会影响其他浏览器，需属于用户请求范围 |
| POST | `/stream-ticket` | `ticket --save-ticket file` | 30秒单次票据，绑定原凭据 |
| WS | `/stream` | `stream --output-dir dir` | 认证头直接连接，或 --ticket-file 使用票据 |

`--url`、`--key-file`、`--token-file`、`--timeout` 是全局选项，写在子命令之前。认证环境变量为 `REMOTEAPP_API_KEY` 和 `REMOTEAPP_SESSION_TOKEN`，两者都有时默认优先 Key；撤销单会话强制使用 session。HTTP 客户端不跟随重定向，不自动重试任何手势或认证请求。

## 登录、配对和会话

密码通过 `REMOTEAPP_PASSWORD` 或 `login --password-file file` 提供；保留密码中的空格和换行，文件应只包含准确密码。配对码通过 `REMOTEAPP_PAIRING_CODE` 或 `pair --code-file file` 提供。登录失败有 1–32 秒退避，不通过重复尝试来探测密码。

```bash
python3 <skill-dir>/scripts/remoteapp.py --url IP:PORT login --password-file /private/path/password --save-session /private/path/session
python3 <skill-dir>/scripts/remoteapp.py --url IP:PORT --token-file /private/path/session device
python3 <skill-dir>/scripts/remoteapp.py --url IP:PORT --token-file /private/path/session revoke-session
```

会话文件仅保存原始 token。`revoke-all` 不删除 Key；当前没有远程生成、读取或轮换永久 Key 的接口。手机 UI 中生成/复制 Key，不能通过网络自行获取。

## 画面与坐标

截图响应头：`X-Screen-Revision`、`X-Screen-Width/Height`、`X-Screen-Rotation`。新版还含 `X-Screen-Source-Width/Height`、`X-Frame-Sequence`、`X-Frame-Age-Ms`。JPEG 长边可能限制为1920；输入总是按整张 JPEG 归一化，由手机映射至源屏。旋转/几何变化会更新 revision；普通画面刷新保持稳定。新捕获会话使用新版本，不能复用先前 session 的坐标。

客户端使用 `--meta` 或 `--revision` 二选一；前者优先用于观察操作闭环。像素输入 `--pixels` 必须使用对应 meta，除以 JPEG 宽高。手势成功在新版返回 `accepted:true, completed:true`；旧版可能仅返回 accepted。系统动作的 accepted 仅表示系统接受，仍需观察画面。

WS 首条文本为 `type:hello`，含 width/height/rotation/revision/format=jpeg。每条二进制消息是一张完整 JPEG。新版每张 JPEG 前提供 `type:frame` 文本，含同帧 revision、JPEG尺寸、sourceWidth/Height、sequence；旧版只有 hello，直到旋转后再次更新 hello。客户端接受这两种协议。把元数据绑定到其后的 JPEG，而不是使用稍后收到的版本。

发送 `{"type":"setQuality","fps":5}` 设置1–15 FPS的发送上限；新版底层有效编码最多约5 FPS。静止画面重复帧不代表新状态，也不能仅凭帧年龄较大判断投影失效。最多4路流，超限关闭码1013。原凭据撤销或Key轮换会关闭相关流（1008）。票据单次使用，握手失败或连接中断后重新申请，不能重用旧票据。

`stream --ticket-file file --output-dir dir` 使用票据；默认 stream 使用认证头，不把永久凭据放进 URL。脚本处理服务器 ping/pong、文本/二进制分片，采够指定数量后正常关闭。

## 错误契约

错误 JSON 为 `error/message/requestId`，客户端失败以非零退出并打印不含凭据的错误摘要。

| 状态 / 错误 | Agent 行为 |
| --- | --- |
| 400 INVALID_COORDINATE、INVALID_REQUEST | 修正坐标范围、字段或JSON，不重放无效调用 |
| 400 UNSUPPORTED_ACTION | 只使用四种已支持系统动作 |
| 401 UNAUTHORIZED、AUTH_FAILED、PAIRING_FAILED | 检查认证方式/用户凭据；不猜测密码或配对码 |
| 409 SCREEN_CHANGED | 重新观察新截图，重新选择目标 |
| 409 INPUT_BUSY | 等待当前手势，随后确认界面 |
| 409 GESTURE_CANCELLED | 手势可能部分执行，先观察再决定 |
| 415 UNSUPPORTED_MEDIA_TYPE | 使用JSON请求体 |
| 429 TICKET_UNAVAILABLE | 等待后重新申请；最多128张待用票据 |
| 503 CAPTURE_UNAVAILABLE、ACCESSIBILITY_UNAVAILABLE | 检查手机服务/授权；不操作旧图 |
| 504 GESTURE_TIMEOUT | 完成状态未知，控制可能已停止，回手机恢复 |

熄屏保留HTTP/认证连接，captureState=screen_off；请求power/wake后检查screenInteractive和controlReady并重新观察截图。系统撤销投影时captureConsentRequired=true，连接保留，需手机用户重新授权共享；无障碍断开仍关闭服务，需手机恢复权限和重启。网络超时也可能来自 AP 隔离、VPN 或地址变化；按用户给出的地址排查，不自动扩大网络暴露。

剪贴板错误：409 CLIPBOARD_FOREGROUND_REQUIRED / CLIPBOARD_NOT_TEXT，413 CLIPBOARD_TOO_LARGE，403 CLIPBOARD_ACCESS_DENIED。读取/写入不自动粘贴或同步，不应记录响应内容。

画面流可发送 `{"type":"state","captureState":"screen_off|starting|unavailable"}`（实际字段取单一状态）。收到状态消息应废弃旧图/坐标；只有后续hello/frame提供可用于JPEG的几何信息。亮屏请求不解除安全锁屏，也不恢复系统已撤销的录屏授权。

`/device`提供lockAvailable和lockPermissionRequired。主动锁屏是明确改变设备状态的动作，应属于用户任务范围；Android8首次需手机用户激活仅force-lock的管理员权限，不能通过API批准。LOCK_REJECTED/LOCK_DENIED表示系统拒绝，不盲目重试。锁屏后按screen_off状态处理，保留连接但不得盲用旧坐标。
