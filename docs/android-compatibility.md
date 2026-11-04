# Android 兼容性增强方案

状态：核心方案已实施，包括统一投影、前台服务顺序、几何映射、单生产者及输入完成反馈。本文保留设计决策和完整验收目标；实际完成的版本与测试范围以 [testing.md](testing.md) 为准，未测试的机型不视为已支持。

## 1. 推荐决策

以 Android 8（API 26）及以上、默认内置屏幕、用户主动授权后的远程控制为支持范围。保留 Kotlin、JPEG、HTTP/WebSocket、密码和永久 API Key。

**所有版本统一使用 MediaProjection 获取整屏画面，无障碍服务负责输入。** 复用现有低版本截图路径，取消“Android 11+ 必须走无障碍截图”的限制。新版本同样在每次新的捕获会话开始时显示系统授权弹窗。

选择该方案的原因：

- 截图和前台服务均属于真实的 `mediaProjection` 用途，Android 14+ 的权限、授权和服务类型可以一致。
- 一条截图路径覆盖更多版本，避免无障碍截图的最小调用间隔、厂商实现和能力声明差异。
- 不引入视频编解码、ADB/root、隐藏 API 或 EasyControl 依赖。

代价：Android 11+ 不再依靠无障碍授权直接开画面；服务重启或捕获被系统停止后，需要手机用户再次确认捕获。永久 Key 仅代表认证长期有效，不代表截图授权永久有效。

无障碍截图暂不作为正式运行后端。若后续恢复该模式，必须增加 API 30+ 的 `canTakeScreenshot` 配置、运行时能力探测、统一限频和错误分类，并另外解决 HTTP 服务的合法生命周期；不能继续冒用 `mediaProjection` 前台服务类型，也不能用 `dataSync` 或 `specialUse` 绕过类型约束。

## 2. 版本矩阵

| Android / API | 截图与服务策略 | 额外处理 |
| --- | --- | --- |
| 8–8.1 / 26–27 | 用户授权后启动前台服务，使用 MediaProjection | 通知渠道；旧版显示尺寸 API；保留现有 rowStride 裁剪 |
| 9 / 28 | 同上 | 声明 `FOREGROUND_SERVICE` 权限 |
| 10–12L / 29–32 | 启动具有 `mediaProjection` 类型的前台服务，再获取投影对象 | 从可见 Activity 主动启动；遵守 Android 12 后台启动限制 |
| 13 / 33 | 同上 | 请求通知权限并说明用途；拒绝通知权限不等同于已获得捕获授权，也不机械判为服务不可启动；侧载时引导用户检查“允许受限设置” |
| 14 / 34 | 声明 `FOREGROUND_SERVICE_MEDIA_PROJECTION`；每个新会话重新授权 | 授权结果仅消费一次；注册回调后仅创建一次 VirtualDisplay；请求默认显示器整屏捕获 |
| 15 / 35 | 延续 API 34 流程 | 不从开机广播启动投影；处理锁屏、系统停止共享、捕获被其他应用取代等终止情况 |
| 16 / 36 及以后 | 沿用已验证的公开 API，逐版本回归 | API 36 列为专项验证目标；compile/target 升级单独评估，不以“能安装”作为通过标准 |

运行时根据 SDK 和实际授权能力判断，厂商名称只用于显示设置指引。Android 衍生系统必须具备相应 Android API；非 Android 兼容系统不在范围内。

## 3. 授权、前台服务与资源生命周期

启动顺序固定为：

1. 手机用户在可见 Activity 点击启动；确认无障碍服务已实际连接，不能只读取设置中的启用列表。
2. Activity 发起捕获授权；取消则回到未启动状态，不开放端口。
3. 授权成功后调用 `startForegroundService()`，服务及时调用 `ServiceCompat.startForeground()`；API 29+ 显式传入 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`。
4. 前台状态建立后调用 `getMediaProjection()`，先注册 `MediaProjection.Callback`，再创建 ImageReader 和唯一的 VirtualDisplay。
5. 收到有效首帧且坐标映射确定后启动 HTTP 服务，UI 显示“运行中”。启动超时或异常须释放全部资源，并在手机显示原因。

服务 `onCreate()` 只创建通知渠道等基础资源；在收到合法启动参数后进入前台。没有授权结果的重复 Intent、`null` Intent、重复点按和停止 Intent 均单独处理，不意外创建新捕获会话。

建议使用小型状态机：`Idle → AwaitingConsent → Starting → Running → Stopping → Idle`，失败进入 `Error`；Activity 通过 StateFlow 观察真实状态。Android 旋转导致 Activity 重建时，运行中的捕获由服务持有；待授权过程通过 Activity Result 和保存的非敏感状态恢复，禁止保存并重放授权 Intent。

投影 `onStop()`、无障碍断开、用户停止或不可恢复错误统一进入同一幂等清理函数：立即禁止输入、清空旧画面、关闭画面流和监听端口，再释放 DisplayListener、VirtualDisplay、ImageReader、投影和线程。不要只依赖 `Service.onDestroy()`；后台杀进程不保证调用它。服务继续使用 `START_NOT_STICKY`，不静默重建投影。

截图授权、锁屏暂停和进程存活属于运行状态；密码和 API Key 独立保存，不因上述清理而轮换或删除。用户重开 App 后明确显示需要恢复的授权。

## 4. 分辨率、旋转和坐标统一

- 捕获默认内置显示器。API 30+ 使用与该显示器对应的最大 WindowMetrics，旧版使用 Display real metrics；不能把 Activity 当前窗口大小当整屏大小。
- API 34+ 用 `MediaProjectionConfig.createConfigForDefaultDisplay()` 请求整屏，向用户说明整机控制需要整屏共享。单应用共享缺少全局位置，不能直接映射成整屏点击；若 OEM 行为或捕获范围无法确认，禁止坐标输入并提示重新整屏授权。
- API 34+ 监听 `onCapturedContentResize()`，旧版监听显示变化。旋转、折叠展开和系统分辨率切换均通过同一串行路径更新；API 34+ 复用现有 VirtualDisplay 的 `resize()` / `setSurface()`，不能再次 `createVirtualDisplay()`。
- 使用不可变几何快照同时记录源屏尺寸、旋转、捕获内容范围、JPEG 尺寸和 revision。切换时清空旧帧，只有新几何对应首帧就绪后才重新允许输入。
- 输入按 `x × 源屏宽度`、`y × 源屏高度` 转换并夹到有效像素。归一化坐标不随 JPEG 缩放改变；状态栏、导航栏已包含在整屏中，不额外减掉高度。画面裁剪或 letterbox 必须显式记录变换，不能猜偏移。
- revision 覆盖尺寸、旋转和映射变化，包括宽高不变的 180° 旋转；新捕获会话不能从相同 revision 重新起步。可选用 JavaScript 安全整数范围内的随机起始版本再递增，保持现有数值字段兼容。
- 截图响应头、WebSocket hello 和输入校验读取同一份几何快照。浏览器为每张待解码帧绑定其元数据，完成绘制后才更新“当前可点击版本”，避免异步解码时混用新版本和旧画面。

首轮正式支持普通手机和平板默认屏幕。折叠屏支持需通过展开/折叠回归；外接屏、桌面模式、多显示器输入不宣称支持。应用本身的分屏窗口可滚动适配，但捕获与控制仍针对默认整屏。

## 5. 帧调度与 Agent 调用

引入一个共享帧生产者，HTTP 截图和所有 WebSocket 订阅者复用同一张编码结果。不要为每个连接重新截图、创建 Bitmap 或独立编码。

- 初始目标 5 FPS、JPEG 质量 65；这些是待基准测试的默认值，不是性能保证。WebSocket 的 FPS 表示发送上限，不能迫使底层重复捕获。
- ImageReader 获取最新帧并及时关闭 Image；编码队列、慢客户端发送队列均最多保留最新一帧，阻止积压；慢连接超时关闭。
- 先完成原尺寸正确性。第二阶段按内存和编码耗时，在创建 Surface 时进行内部等比降采样，例如限制长边 1920；单独保留源屏尺寸用于输入映射，不增加面向用户的分辨率设置。仅在 Bitmap 编码前缩小，不能解决原始高分辨率缓冲区的内存占用。
- 限制同时连接数和手势并发，编码/缓冲区分配失败返回可解释错误。禁止持续高频重试导致发热或内存增长。
- 新增帧序号和帧时间供诊断；投影失效不能继续返回旧 JPEG 的 200 成功。静止屏幕可能不产生新 Image，不能仅因画面年龄较大就误判失效，应结合捕获会话和回调状态判断。

输入在主线程通过无障碍执行，等待 `GestureResultCallback` 确认完成或取消；同一时刻只执行一个手势，忙时明确返回可重试状态，避免新请求取消旧手势。保留已有 `accepted` 字段，可加 `completed`；超时和取消应有独立错误，不能一概称为“无障碍未连接”。

建议在 `/device` 增加可选能力字段：`captureState`、`controlReady`、`sourceWidth/sourceHeight`、`displayId`、`frameSequence`、`lastError`。保留现有认证、路径和归一化坐标契约；字段含义明确后同步更新 Agent 文档和网页。

Agent 标准流程：读取能力 → 截图并保留 revision → 判断目标 → 带 revision 输入 → 确认手势完成 → 再截图检查。409 后重新观察；503 后检查状态；服务被终止时返回手机重新授权。不能把输入请求自动重放到新方向、新会话或新画面。

## 6. 厂商系统和 UI

- 权限页区分无障碍“未启用”“已启用但未连接”、捕获未授权、后台限制、通知权限和网络状态，提供对应系统设置入口；厂商专用入口需捕获启动失败并退回通用设置。
- 按需提示小米/HyperOS、OPPO/ColorOS、vivo/OriginOS、三星等系统的后台运行、自启动及电池设置，不强制修改系统选项，不承诺关闭优化即可永远在线。
- 熄屏/锁屏采取可预期的暂停或停止策略，并同步禁止盲操作。首版默认锁屏停止控制，不自动解锁、不使用全屏常亮保证后台存活；系统终止投影后必须重新授权。
- App 保留滚动、安全区和键盘避让；覆盖字体缩放 1.0/1.3/2.0、横屏和小窗口。生成/复制、启停等按钮在窄屏可换行，Key 文本不能撑出页面。
- 网页保留等比画布和 44px 点击目标；刷新、断线重连和恢复页面时重新确认会话和画面版本。兼顾现代 Chrome、Android WebView/厂商浏览器和 iOS Safari，不使用旧帧自动恢复点击。
- LAN 默认关闭；区分监听启动失败、IP 改变、VPN/热点/AP 隔离与鉴权失败。展示当前可用地址，网络变化不扩大已选择的监听范围。HTTP 仍限可信网络或 VPN。

## 7. 实施顺序与文件范围

| 阶段 | 交付内容 | 主要文件 |
| --- | --- | --- |
| P0：跨版本可启动 | 所有版本投影授权；前台服务顺序/类型；投影回调和幂等清理；真实状态机 | MainActivity、RemoteRuntime、RemoteControlForegroundService、CaptureManager、Manifest |
| P1：画面与控制正确 | 整屏选择、旋转/折叠几何、跨会话 revision、浏览器帧元数据同步、手势完成反馈 | CaptureManager、HttpServer、RemoteAccessibilityService、Models、web/src/main.ts |
| P2：性能与使用体验 | 单生产者、有界队列、性能降级、能力状态、权限及网络指引 | capture/、server/、MainActivity、web/、Agent 文档 |

版本判断集中到一个小型 `PlatformCapabilities` 辅助类；CaptureManager 继续承载一个正式截图后端，不建设复杂插件框架。每阶段都保留 API Key 和密码数据，并回归 Android 8 真机；任何阶段未过核心验收不扩大支持声明。

## 8. 验收矩阵

以下为完整覆盖候选，不作为每次改动的默认执行清单。按用户后续要求，默认仅在已连接的安卓真机上验证一轮；扩大版本/模拟器/压力测试范围需用户另行要求，或由实际失败决定必要的修复复测。已完成记录见 testing.md。

模拟器覆盖 API 26、29、30、33、34、35、36；可用镜像不足时明确标注缺口。真机至少保留现有 Android 8，再覆盖一台 Android 11–13 和一台 Android 14+；原生系统和常见厂商系统均需有样本。模拟器负责系统 API 回归，厂商后台行为必须真机验证。

| 范围 | 必测内容和通过条件 |
| --- | --- |
| 安装/授权 | 首装、拒绝再允许、通知拒绝、无障碍禁用/重连、授权中旋转、取消授权；不崩溃、不遗留端口 |
| 显示 | 720p、1080p、1440p，平板比例；0/90/180/270°；导航模式切换、字体/显示缩放、折叠切换；尺寸与 JPEG 一致 |
| 坐标 | 使用专门测试页面的中心及边缘网格目标，通过界面状态变化确认命中；不同缩放下误差不超过约定像素容差；旧 revision 全部拒绝 |
| 新系统限制 | API 34+ 只消费一次授权、只创建一次 VirtualDisplay；整屏/单应用选择、系统停止共享、锁屏终止；无 SecurityException |
| 生命周期 | 返回桌面、旋转、通知停止、无障碍断开、强停和重新启动；资源释放、原密码/Key 保留、不会自动复用旧授权 |
| Agent/API | 现有 28 项回归；手势完成/取消、断网重连、跨会话旧坐标、重复请求；行为和错误契约一致 |
| 稳定性 | 默认 FPS 连续 30 分钟和至少 20 次启停，观察内存是否回落、线程/显示资源是否泄漏；慢客户端不使队列持续增长 |
| 网页 | 320px 小屏、横屏、平板、Safari/Chrome、后台恢复和网络切换；无溢出、坐标映射正确、失效画面不能误触 |

测试需记录机型、系统/API、targetSdk、捕获路径、源屏/JPEG 尺寸、请求延迟、帧更新延迟和内存峰值。不能把缓存 JPEG 的 HTTP 响应耗时当成端到端画面延迟，也不能把单台设备通过当作全部机型兼容。

## 9. 平台依据

- [MediaProjection：授权、前台服务、单次令牌、尺寸回调及生命周期](https://developer.android.com/media/grow/media-projection)
- [前台服务类型：Media projection 的前提和权限](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection)
- [Android 15：开机广播启动前台服务的限制](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed)
- [无障碍截图 API 与失败类型](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
