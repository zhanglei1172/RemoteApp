# 测试记录

## 当前测试约定

按用户 2026-11-04 的要求，后续默认只在当时连接的安卓真机上验证一轮；除非用户另行要求，或该轮发现失败需要修复验证，不自动扩展模拟器、版本矩阵或重复压力测试。以下跨版本结果是在该约定前已经完成的验证记录。

## 2026-11-04：兼容改造与 Agent Skill

最终 debug APK 已安装到连接的小米 MI 5（Android 8 / API 26，1080×1920）。全部版本现统一使用 MediaProjection，输入由无障碍服务执行。模拟器使用 ARM64 AOSP 镜像；应用 compile/target SDK 为 35，API 36 的结果表示在 Android 16 上运行现有 target 35 应用。

| 验证范围 | 结果 |
| --- | --- |
| JVM 单元测试 | 14 项通过：原认证/输入校验 10 项，新增源屏坐标、等比缩放、旋转和非法尺寸 4 项 |
| Android 构建与 Lint | debug 构建通过；Lint 0 errors、8 warnings（依赖更新建议、备份配置建议及应用 Context 持有提示） |
| Web | Vite build 和 TypeScript 检查通过；最终 APK 内网页与构建产物逐字节一致 |
| Android 8 真机 API | 28 项检查通过，包含认证、未授权拒绝、JPEG、错误 JSON/坐标、旧版本拒绝、WebSocket 及票据单次使用 |
| Android 8 真机控制 | HOME 实际返回桌面；BACK/RECENTS、长按、滑动通过；截图后点按复制按钮并读取 Android 剪贴板确认 Key 完全一致；手势响应确认 completed |
| 手机网页 | 320×568、390×844、844×390、1365×900 Chrome 视口均通过比例、无溢出、坐标与 revision 映射、离线禁止旧图输入、恢复连接；无脚本错误 |
| Android 8 旋转 | 横屏/竖屏、截图尺寸、旋转角度和画面流元数据同步；旧 revision 输入被拒绝 |
| Android 11 / API 30 模拟器 | 下述版本流程全部通过 |
| Android 14 / API 34 模拟器 | 下述版本流程全部通过；系统整屏投影授权与前台服务正常 |
| Android 15 / API 35 模拟器 | 下述版本流程全部通过；源屏 1080×2400 与 JPEG 864×1920 分别报告 |
| Android 16 / API 36 模拟器 | 下述版本流程全部通过；系统“SHARE SCREEN”授权后正常捕获与控制 |
| RemoteApp Skill | 5 项客户端协议/文件测试通过；真机验证 health、device、截图、直接 Key 画面流、票据流及重复票据拒绝、像素坐标点按复制、长按、滑动、HOME 和通用 request |

每个模拟器版本完成：19 项不发送有效手势的 HTTP 契约检查；带截图版本的点按及完成确认；并发手势返回 INPUT_BUSY；0/90/180/270° 旋转及旧坐标拒绝；1440×3200 与 720×1280 运行时分辨率切换和1920长边限制；5次停止/重新授权/启动后 Key 保留且旧会话 revision 失效；无障碍断开关闭端口及恢复；熄屏关闭服务、唤醒后重新授权恢复；最终正常停止。

Skill 的本地测试覆盖认证头选择、禁止跟随重定向、密码空格保留、会话/票据私密保存、会话撤销不会误用 Key、像素坐标及版本读取、WebSocket 握手/分片/ping/客户端掩码。没有使用手机用户的密码做猜测或登录测试。

测试 UI 抓取使用保留其他无障碍服务的 UiAutomation 标志，避免测试工具临时压制无障碍导致误判；该工具仅用于本次外部 QA，不打包进 App。

## 可重复检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
cd web
npm run build
npx tsc --noEmit --target ES2020 --module ESNext --moduleResolution bundler --lib ES2020,DOM src/main.ts
```

已连接手机启动服务并配置环境变量后，可运行 `python3 scripts/agent-smoke.py --url http://手机IP:8765`。脚本不发送有效手势、不改变认证配置、不保存屏幕。Skill 客户端测试为 `python3 skills/remoteapp-control/scripts/test_remoteapp.py`，仅使用本地测试服务器，不连接手机。

## 已知边界与清理

模拟器不能代替三星、HyperOS、ColorOS、OriginOS 等厂商真机的电池管理、后台存活、触摸和性能验证。没有完成折叠屏实机、多显示器、厂商浏览器、Safari、受保护窗口和30分钟压力验收；高分辨率测试使用运行时显示尺寸覆盖，不等同于相应物理屏幕。网络请求耗时也不能代表画面到操作的端到端延迟。

真机完成后停止服务、关闭 LAN 并移除测试端口转发；保留手机当前密码和 Key 配置，不用测试备份覆盖其后发生的配置变化。临时认证材料、UI 内容和截图不纳入仓库。所有本次模拟器均已关闭。

## 历史认证验证

2026-11-03 的原实现曾在相同 Android 8 设备验证密码设置/登录、错误密码退避、密码格式迁移、Key 轮换使旧流和票据失效、会话撤销与 Key 相互独立、进程重启持久化，以及真实 LAN 直连认证与关闭 LAN 后拒绝连接。该轮临时密码和 Key 已恢复、清理；这些结果不代替新版厂商设备验证。

## 2026-11-04 网页剪贴板验证

仅在当前连接的 MI 5（Android 8 / API 26）进行一轮真机验证，安装更新不清除应用数据、不变更密码或 API Key。通过 GET/POST `/api/v1/clipboard` 验证中文、多行、emoji、首尾空白、后台读写和空文本覆盖；未认证 GET/POST 返回401，缺少text返回400，超过65536 UTF-16单元返回413。测试后恢复原剪贴板文字、停止服务并清理转发与私密测试材料。

Chrome 320×568移动视口连接该真机服务，验证网页实际读写、空文本覆盖、无横向溢出和至少44px按钮；另用响应模拟验证Android10+前台读取限制的提示、401后清空文本及无页面异常。此项是桌面Chrome移动视口检查，不等同于厂商手机浏览器实测；Android10+系统行为未另做真机验收。

Web构建、TypeScript类型检查、Android打包、14项JVM单测及lint通过（0错误，8项既有警告）；Skill客户端5项本地测试及skill校验通过。

## 2026-11-04 熄屏保持连接与主动锁屏验收

最终版本完成Web构建/TypeScript检查、APK打包、14项JVM测试、Android lint（0错误、8项既有警告）、Skill客户端5项测试及校验。仅使用同一台MI 5，USB断开后改用该设备既有无线ADB；没有运行其他设备或模拟器。

初步验证观察到熄屏后HTTP仍可读取设备状态、captureState=screen_off且controlReady=false。该轮发现广播切换前的状态竞争，已改为同时检查系统实际亮屏状态。随后在同一MI 5继续验证：用调试器停止当前MediaProjection（测试专用，无生产API），确认HTTP、认证和原WebSocket保留，网页显示重新授权提示；在手机正常同意新的共享授权后，controlReady恢复、新revision生效且原WebSocket未重连。熄屏时画面/输入均返回503、网页清除旧画面并保留连接。

MIUI拒绝了WakeActivity的锁屏显示权限，已改为Android8–12L使用最长1.5秒的旧版系统亮屏锁，Android13+继续使用独立唤醒页面。短暂熄屏广播延迟导致revision未更新的问题，已通过采样系统实际亮屏状态时同步清空旧帧并推进revision修复。最终版打包、14项单测和lint通过并已安装。已清理临时Key/UI数据、测试dex和端口转发，没有修改手机密码或API Key。

用户在手机系统界面激活Android8仅force-lock的设备管理权限后，最终通过同一台MI 5验证：320×568网页实际调用power/lock锁屏、power/wake亮屏；HTTP/认证/同一条WebSocket全程保留；熄屏截图和输入返回503，网页清除旧图；亮屏新画面恢复且revision变化，旧坐标返回409；未认证锁屏/亮屏请求返回401；亮屏后keyguardLocked仍为true，没有解除系统密码锁。网页无横向溢出或JavaScript异常。文案断言由固定提示词改为真实连接/画面状态，未因此修改产品行为。

本轮只使用当前MI 5；录屏撤销和新授权恢复用例在同一轮较早版本通过（随后修改仅限亮屏兼容、屏幕状态采样和新增锁屏）。未对Android9+无障碍锁屏、Android13+亮屏Activity、其他厂商、Doze长时间网络保活作新增真机声明。最终保留用户已启用的锁屏权限和远程服务运行，LAN保持关闭；移除临时调试连接/端口转发、测试dex及私密Key/UI材料。未修改手机系统密码或API Key。
