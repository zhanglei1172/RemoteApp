# RemoteApp 安卓被控端架构

## 产品与授权边界

RemoteApp 面向 Android 8（API 26）及以上：手机用户启用无障碍服务、确认系统整屏录制授权并启动服务后，Agent 或浏览器通过 HTTP/WebSocket 查看 JPEG 画面、执行手势和系统动作。默认仅监听 `127.0.0.1:8765`；用户打开 LAN 后监听 `0.0.0.0:8765`。HTTP 仅适用于可信网络或 VPN。

密码和 API Key 长期保留，但捕获授权按会话申请。锁屏、熄屏、无障碍断开、系统停止共享或进程被杀后，不静默恢复控制，也不重放旧授权。受保护的窗口可能黑屏；不提供解锁、任意 shell、文件系统、多指事件或剪贴板控制接口。

兼容性决策及验收清单见 [android-compatibility.md](android-compatibility.md)，已执行结果见 [testing.md](testing.md)。

## 组件与数据流

```mermaid
flowchart LR
    UI[MainActivity] -->|用户确认整屏录制| F[RemoteControlForegroundService]
    F --> C[CaptureManager / MediaProjection]
    C --> J[共享最新 JPEG 和不可变几何快照]
    H[HttpServer] --> J
    A[Agent / 网页] -->|密码会话或 API Key| H
    H --> I[RemoteAccessibilityService]
    I -->|完成或取消回调| H
    F --> H
    UI <-->|StateFlow| R[RemoteRuntime]
```

- `platform/PlatformCapabilities` 集中处理 SDK 差异、整屏授权 Intent 和默认显示器尺寸。
- `RemoteRuntime` 保存运行状态和服务引用；`HostState` 包含 Idle、AwaitingConsent、Starting、Running、Stopping、Error。
- `CaptureManager` 是所有 Android 版本共用的单一截图生产者。
- `RemoteAccessibilityService` 只负责输入和连接状态；不再使用 API 30+ 的无障碍截图路径。
- `HttpServer` 提供认证、截图、画面流、归一化输入及能力状态。具体接口见 [Agent 调用指南](agent-api.md)。

## 启动和停止

1. 可见 Activity 检查无障碍实际连接状态，发起系统捕获授权。API 34+ 使用 `MediaProjectionConfig.createConfigForDefaultDisplay()` 请求整屏。
2. 授权成功后启动前台服务；API 29+ 显式使用 `mediaProjection` 类型，Manifest 声明对应普通权限及 API 34+ 类型权限。
3. 服务进入前台后获取 MediaProjection，先注册生命周期回调，再创建唯一 VirtualDisplay。重复启动不重复消费授权或创建显示器。
4. 有效首帧就绪后才开启 HTTP 监听并发布 Running；首帧超时、无障碍未连接、手机锁定或启动异常时停止服务并显示原因。
5. 用户停止、熄屏、无障碍断开或投影 `onStop()` 时，先禁止读取旧画面和提交新输入，再停止监听并释放投影、ImageReader、Bitmap、线程及回调。

前台服务使用 `START_NOT_STICKY`，不通过开机广播恢复录屏。Activity 重建不重启运行中的捕获；仅保存待授权的 LAN 选择等非敏感界面状态。运行状态通过 StateFlow 更新，错误不会被“服务已启动”的乐观显示覆盖。

已经提交给系统的手势没有通用的撤回 API；停止后禁止新手势，Agent 不应假设先前提交的手势被自动回滚。

## 捕获、性能和屏幕几何

MediaProjection 使用默认内置显示器，VirtualDisplay + RGBA ImageReader + JPEG。捕获缓冲区长边最多 1920 像素，维持整屏比例；这属于内部资源限制，不增加用户分辨率设置。源屏尺寸与 JPEG 尺寸分别记录，不能混用。

一个 HandlerThread 串行处理显示变化和编码，最多每 200ms 编码一次（约 5 FPS）。ImageReader 缓冲上限为 2，Bitmap 和紧凑像素缓冲按几何复用，所有 HTTP/画面流客户端共享最新 JPEG。逐行复制跳过 rowStride padding，包括没有末行 padding 的缓冲区。静止画面可复用最后有效帧；捕获已终止时立即失效，不以旧 JPEG 冒充可用捕获。

几何快照包含源屏宽高、JPEG 宽高、旋转和 revision。API 30+ 查询默认显示器最大 WindowMetrics，旧版本查询 real metrics。API 34+ 同时处理捕获尺寸回调；普通显示变化使用 DisplayListener。旋转或分辨率改变时清空旧帧、替换 Surface 并复用 VirtualDisplay，避免 Android 14+ 重复创建的授权异常。检测到捕获范围与整屏不符时停止控制，不猜测单应用窗口偏移。

坐标为 `[0,1]` 的归一化整屏坐标，乘以源屏尺寸并夹到最后有效像素；不减状态栏或导航栏高度。revision 在映射变化时递增，每次捕获会话使用新的随机安全整数起点，防止进程重启后旧版本再次碰巧有效。手势提交前在主线程再次检查 revision。

## 输入与 Agent 行为

点按、长按和滑动在无障碍主线程执行，等待 `GestureResultCallback`；完成返回 `accepted:true, completed:true`。一个设备同一时刻只接受一个屏幕手势，忙时返回 `409 INPUT_BUSY`，取消返回 `409 GESTURE_CANCELLED`。请求断开不会提前释放手势门闩。若系统超时未确认完成，停止控制，要求用户重新启动，防止未知状态下继续输入。

系统动作仍返回 `accepted:true`，仅表示 Android 接受动作，不保证目标界面已绘制完成。Agent 在每次操作后应重新截图确认。旧 revision 返回 `409 SCREEN_CHANGED`，必须重新观察目标，不能自动重放原坐标。

`/device` 暴露实际捕获状态、是否可控制、源屏/JPEG 尺寸、默认显示器 ID 和帧序号。截图头包含同一帧的版本、几何和帧年龄；帧年龄是诊断数据，静止画面年龄较大不等于会话失效。

## 网页与画面流

WebSocket 升级前校验认证，最多同时 4 路画面流；慢连接发送有超时。默认发送上限为 5 FPS，客户端可请求 1–15 FPS，但底层编码仍最多约 5 FPS，不保证更高的有效更新率。

首条 `hello` 描述几何，几何变化后再次发送 hello。每个 JPEG 前发送 `type:frame` 元数据，包含该二进制 JPEG 的 revision、尺寸、源屏尺寸和 sequence。网页只保留最新待解码帧，绘制完成后才更新可点击版本；异步旧帧、旧连接和过期 HTTP 响应不能覆盖新会话。断线、页面隐藏和离线期间禁止画布输入，恢复后重新获取画面和流票据。

App 设置页可滚动、避让安全区和键盘，Key 按钮允许换行；运行时显示本机或 LAN 地址。网页维持画布比例和至少 44px 操作目标，兼顾手机横竖屏和桌面窗口。

## 认证与持久化

手机设置的 6–128 位密码通过随机盐 + PBKDF2-HMAC-SHA256（100,000 次）验证，在后台线程计算；失败登录有 1–32 秒递增退避。旧密码格式在成功登录时升级。一次性配对码继续支持，5 分钟有效。

手机生成 256 位随机永久 API Key，支持 `X-API-Key`、`Authorization: ApiKey` 和 Bearer；随机会话使用 Bearer。Key 原值和验证摘要保存在 Android Keystore 保护的 EncryptedSharedPreferences，允许重启后再次复制。更新密码撤销会话；撤销会话、停止服务和重新录屏不轮换 Key。手机重新生成 Key 会使旧 Key 及相关流凭据失效。卸载或清除数据会删除凭据。

浏览器先通过认证接口获取 30 秒单次流票据，避免将永久 Key 或会话 token 放入 URL。流持续检查原凭据；撤销后关闭连接。API 响应禁止缓存。日志和仓库不保存密码、Key、token 或屏幕内容。

## 已知边界

厂商可能回收进程或禁用无障碍，设置页提供排查说明，但不承诺后台永久在线。没有完成验收的折叠屏、外接屏、多显示器和厂商浏览器不声明支持。模拟器验证系统 API，不能替代厂商设备上的电池策略、性能、锁屏和输入行为测试。
