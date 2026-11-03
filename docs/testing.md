# 真机测试记录

## 环境与构建

2026-11-03，使用已连接的小米 MI 5（Android 8 / API 26，1080 × 1920）安装并验证 debug APK。通过 USB 端口转发和同一局域网直连访问真实手机服务，屏幕捕获使用 MediaProjection，手势使用 Accessibility。

以下检查全部通过：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
cd web
npm run build
npx tsc --noEmit --target ES2020 --module ESNext --moduleResolution bundler --lib ES2020,DOM src/main.ts
```

网页构建产物已同步到 APK 的 assets；最终 APK 已重新安装。`git diff --check` 通过。

## 验证结果

| 范围 | 结果 |
| --- | --- |
| JVM 单元测试 | 10 项通过，涵盖密码格式迁移、登录退避、Key 持久化/轮换、会话撤销、票据过期和坐标校验 |
| HTTP / WebSocket | 28 项通过：三种 Key 认证头、未授权拒绝、JPEG、参数/JSON 错误、旧版本坐标拒绝、升级前鉴权、帧接收、票据单次使用及连续截图 |
| 凭据生命周期 | 5 项通过：单会话撤销、全部会话撤销、Key 不被会话撤销影响、Key 轮换使旧票据与连接失效、密码登录保留 |
| 重启与网络 | 5 项通过：错误密码退避、强停后凭据保留、停止服务关闭端口、局域网直连鉴权、服务重启后 Key 可用；另验证关闭 LAN 后直连失败 |
| 真机控制 | HOME 实际返回桌面；RECENTS、BACK、长按成功提交；API 滑动到最下方停止按钮；截图取得 revision 后点按“复制”，读取 Android 剪贴板确认内容与 Key 完全一致 |
| 旋转 | 竖屏 → 横屏 → 竖屏尺寸和旋转同步；WebSocket 更新元数据；普通帧 revision 稳定，旧方向输入返回 409 |
| 网页 | Chrome 手机视口 320×568、390×844、844×390 和桌面 1365×900 全部通过：登录、无横向溢出、画面等比、25% 位置点按映射、输入携带 revision、撤销会话；无页面脚本异常 |

最终一轮 USB 测量：连续 10 次截图请求耗时 23–36ms，平均 32ms；密码登录约 2.8 秒。这是读取已捕获画面的请求耗时，不代表完整手势执行或画面端到端延迟。

## 修复与边界

测试中修复了横屏捕获尺寸未更新、画布比例导致坐标偏差、WebSocket 默认元数据缺失、错误 JSON 响应，以及屏幕授权返回后服务按钮状态不同步的问题。保留 JPEG 和无障碍手势方案。

MIUI 强制停止可能移除无障碍授权；重新启用权限并启动服务后，原密码和 Key 仍可使用。API 返回 `accepted` 代表手势提交成功，Agent 应再次截图确认结果；推荐流程见 [Agent 调用指南](agent-api.md)。

本次没有第二台 Android 11+ 设备，Accessibility 截图分支未完成真机验证；手机浏览器布局使用 Chrome 视口仿真，未覆盖各厂商手机浏览器。测试后恢复原有认证配置，停止服务并关闭 LAN；测试凭据及屏幕内容不纳入仓库。
