# RemoteApp

RemoteApp 是一个需要用户明确授权的安卓被控端：通过无障碍服务执行手势和系统动作，通过局域网 HTTP/WebSocket 服务提供屏幕查看与控制，并附带一个同源网页控制台。

完整方案见 [docs/architecture.md](docs/architecture.md)。

真机验证范围、结果及已知边界见 [测试记录](docs/testing.md)。

其他 Android 版本的改造设计见 [兼容性增强方案](docs/android-compatibility.md)。

## 构建

需要 JDK 17、Android SDK 35 和 Node.js 20+：

```bash
./gradlew :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。网页源码在 `web/`，APK 内置的静态控制页在 `app/src/main/assets/web/`；修改网页后运行 `npm install && npm run build`，再把构建产物同步到 assets。

## 使用

1. 安装 APK，打开应用并进入系统无障碍设置，启用“RemoteApp 控制服务”。
2. 返回应用，设置访问密码；页面可上下滚动。按需打开“允许局域网访问”，点击“启动远程服务”，在系统弹窗中允许整屏录制。
3. 在同一设备上访问 `http://127.0.0.1:8765`，或在同一局域网通过手机 IP 访问端口 8765。
4. 输入访问密码登录，或展开“一次性配对码”并输入手机上显示的配对码，即可查看画面和发送操作。
5. 使用手机常驻通知或应用中的“停止远程服务”关闭控制端口。

所有 Android 版本均需在每个新捕获会话确认录屏授权。熄屏/锁屏保留远程服务，可在网页点击“唤醒屏幕”；若系统保留录屏授权，亮屏后自动恢复画面。部分系统会在锁屏时撤销录屏授权，此时连接继续保留，需在手机点击“重新授权屏幕共享”。安全锁屏仍需正常解锁；无障碍断开或进程被杀后需重新启动，密码和 Key 保留。

## Agent API

完整接口、Python 示例和错误处理见 [Agent 调用指南](docs/agent-api.md)。

在应用的“Agent API Key”中点击“生成 API Key”及“复制”。Key 不设有效期，停止服务、重启应用或手机后仍可使用和复制；重新生成、清除应用数据或卸载会使旧 Key 失效。修改密码和撤销网页会话不影响 API Key。

```bash
# 将手机中复制的 Key 放入 REMOTEAPP_API_KEY 环境变量。
curl -H "X-API-Key: $REMOTEAPP_API_KEY" http://手机IP:8765/api/v1/device
curl -H "X-API-Key: $REMOTEAPP_API_KEY" http://手机IP:8765/api/v1/screen -o screen.jpg
curl -X POST -H "X-API-Key: $REMOTEAPP_API_KEY" -H 'Content-Type: application/json' \
  -d '{"x":0.5,"y":0.5}' http://手机IP:8765/api/v1/input/tap
```

也支持 `Authorization: ApiKey <key>` 或 `Authorization: Bearer <key>`。密码登录接口为 `POST /api/v1/auth/login`，请求 `{"password":"访问密码"}`，响应的 `token` 可用作 Bearer 会话。`/session/revoke` 只撤销会话，API Key 由手机上的“重新生成”替换。

画面流仍使用 JPEG。Agent 在 `/api/v1/stream` 的 WebSocket 握手中携带相同认证头；浏览器先通过 `POST /api/v1/stream-ticket` 获取 30 秒一次性票据，再连接 `/api/v1/stream?ticket=...`。

当前版本使用 HTTP，密码、Key、token 和屏幕内容会以明文经过局域网；只应在可信网络使用，公网访问请先使用 VPN。

## Agent Skill

仓库提供可安装的 [remoteapp-control skill](skills/remoteapp-control/SKILL.md)，包含全部现有 API 的客户端和操作规范。已配置 `REMOTEAPP_API_KEY` 后，可向 Agent 提供 IP、端口和任务，例如：

```text
$remoteapp-control 连接 192.168.1.20:8765，查看当前画面并返回桌面。
```

Skill 支持密码/配对会话、截图和画面流、点按/长按/滑动、系统动作、票据和会话撤销；截图自动保存对应版本，避免旋转后复用旧坐标。仅 IP 和端口不能替代认证。

### 网页手机剪贴板

控制页下方可读取/写入手机剪贴板，支持多行 Unicode 文本及空文本覆盖。Android 10 及以上读取时需将手机上的 RemoteApp 切到前台。接口及系统限制见 [Agent 调用指南](docs/agent-api.md#手机剪贴板)。

### 远程亮屏与锁屏

网页工具栏提供“唤醒屏幕”和“锁定屏幕”，Agent也可调用 `POST /api/v1/power/wake`、`POST /api/v1/power/lock`。Android9+锁屏使用无障碍服务；Android8需在App中启用一次锁屏权限并完成系统确认。亮屏不会解除安全锁屏，系统撤销的录屏授权需在手机重新同意。
