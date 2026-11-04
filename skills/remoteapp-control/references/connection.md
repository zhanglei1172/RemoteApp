# 隧道、HTTPS 与认证输入

仅排查用户给出的地址。先用不含凭据的 `health` 判断服务，再调用 `device` 和 `screen`。不要将网络错误当作 Key 无效而反复认证，也不要自动改用 ADB、扫描端口或修改手机/隧道配置。

## SakuraFrp 自动 HTTPS

省略协议的地址默认按 HTTP 访问。若返回 `501 HTTPS_REQUIRED`，且错误页明确写着启用“自动 HTTPS”，改用**同一主机和端口**的显式 `https://` 再检查 health。这不是 RemoteApp 的 API 错误，也不说明 Key 错误。客户端不跟随重定向，不自动重放请求；尤其不能因错误页或跳转重发已认证的写操作。

```bash
python3 <skill-dir>/scripts/remoteapp.py --url https://phone.example:21797 health
```

## 自签名证书与服务名

自动 HTTPS 可能使用隧道生成的自签名证书，且证书 SAN 中的域名与用户提供的连接域名不同。报 `TLS_CERTIFICATE_INVALID` 时检查证书、部署配置和有效期；不要把它当成手机离线。

- `--ca-file /private/path/tunnel.pem`：信任指定 PEM CA/自签名证书，仍验证签名和有效期。
- `--tls-server-name service.example`：以已确认的 TLS 服务名进行 SNI 和证书名称验证；TCP 连接地址、端口与 HTTP Host 保持用户原地址。
- HTTP 和 WSS 使用相同 TLS 配置，不需 monkey patch、关闭证书校验或复制客户端实现。

优先使用用户提供或已知部署中的证书。诊断时可以在**不携带凭据**的 TLS 握手中获取目标公开证书，查看 SAN、指纹、签发者和有效期；从网络取到证书本身不能证明其可信。不要自动把所有证书加入系统信任，也不要根据证书中的其他域名改向另一台服务器发送 Key。若凭已有部署信息无法确认，应向用户说明具体证书问题和所需信息。

```bash
python3 <skill-dir>/scripts/remoteapp.py --url https://phone.example:21797 --ca-file /private/path/tunnel.pem --tls-server-name service.example health
python3 <skill-dir>/scripts/remoteapp.py --url https://phone.example:21797 --ca-file /private/path/tunnel.pem --tls-server-name service.example --key-file /private/path/key device
```

证书文件不是 API Key；不要把本次真实地址、Key、指纹或临时路径固化到技能。证书变化时重新检查，不永久跳过验证。

## 系统代理

HTTP(S) 默认读取 Python 的系统/环境代理，代理可能返回错误页或改变连接路径。必要时用 `--no-proxy health` 对**同一地址**做一次无认证直连对照，区分代理问题与目标隧道问题。仅影响当前命令，不修改全局代理/VPN。`stream` 的原始 WebSocket 连接一直是直连，不支持代理 CONNECT；网络必须经过代理时应说明这一限制。

## 用户在聊天中提供 Key

不要复述 Key，也不要把它写进 URL、shell 命令、临时 Python 源码、日志或仓库。已有私密文件/环境变量优先；需要把聊天中的 Key 交给客户端时，使用 `--ask-key` 启动带 TTY 的进程，等待隐藏输入提示，再通过工具的标准输入写入。不要让用户因为已经提供过 Key 而重复提供。

```bash
python3 <skill-dir>/scripts/remoteapp.py --url https://phone.example:21797 --ask-key screen --output /private/task/screen.jpg
```

上述示例可组合证书/代理参数，均放在子命令前。`--ask-key` 无 TTY 时直接拒绝，避免回退到明文回显。临时认证材料用后清理；用户要求查看的截图及元数据可保留在权限受限的任务目录，实际查看图片后向用户展示或提供文件链接。
