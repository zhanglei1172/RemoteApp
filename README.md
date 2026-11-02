# RemoteApp

RemoteApp 是一个需要用户明确授权的安卓被控端：通过无障碍服务执行手势和系统动作，通过局域网 HTTP/WebSocket 服务提供屏幕查看与控制，并附带一个同源网页控制台。

完整方案见 [docs/architecture.md](docs/architecture.md)。

## 构建

需要 JDK 17、Android SDK 35 和 Node.js 20+：

```bash
./gradlew :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。网页源码在 `web/`，APK 内置的静态控制页在 `app/src/main/assets/web/`；修改网页后运行 `npm install && npm run build`，再把构建产物同步到 assets。

## 使用

1. 安装 APK，打开应用并进入系统无障碍设置，启用“RemoteApp 控制服务”。
2. 返回应用，按需打开“允许局域网访问”，点击“启动远程服务”。
3. 在同一设备上访问 `http://127.0.0.1:8765`，或在同一局域网通过手机 IP 访问端口 8765。
4. 输入手机上显示的一次性配对码，配对后即可查看画面和发送操作。
5. 使用手机常驻通知或应用中的“停止远程服务”关闭控制端口。

当前版本使用 HTTP，屏幕和 token 会以明文经过局域网；只应在可信网络使用，公网访问请先使用 VPN。
