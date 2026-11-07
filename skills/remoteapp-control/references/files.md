# 文件双向传输

文件只通过手机 App 的独立传输区收发，不提供任意路径浏览、覆盖或执行。Agent 沿用现有认证。手机原文件需先在 RemoteApp 点击“从手机选择文件”，通过系统文件选择器导入副本；Agent 上传的文件可在手机 App 点击“保存到手机”，选择保存位置。无需全盘存储权限，支持 Android 8+ 的系统文档接口。

## 客户端命令

认证、地址和TLS选项均放在子命令之前：

```bash
python3 <skill-dir>/scripts/remoteapp.py --url IP:PORT --key-file /private/key files
python3 <skill-dir>/scripts/remoteapp.py --url IP:PORT --key-file /private/key --timeout 120 file-upload --file /private/local/report.pdf
python3 <skill-dir>/scripts/remoteapp.py --url IP:PORT --key-file /private/key --timeout 120 file-download FILE_ID --output /private/task/report.pdf
python3 <skill-dir>/scripts/remoteapp.py --url IP:PORT --key-file /private/key file-remove FILE_ID
```

上传返回id/name/size/createdAt；`--name`可指定显示名，同名文件仍各有独立ID，不覆盖。下载使用明确的本地目标路径，不根据远程文件名拼接路径，拒绝覆盖现有文件，以0600保存，完成校验长度后原子发布；失败移除本地临时文件。

文件命令采用64KiB缓冲流式传输，支持大于通用`request`的16MiB响应上限。**不要用通用request下载大文件，不用base64或JSON包文件内容**。超时参数为单次网络I/O等待上限，慢网络可按需调大至120秒；不要自动重放失败上传，先查看files确认是否已完成。

## API

| 方法 / 路径（前缀 /api/v1） | 用途 |
| --- | --- |
| GET /files | 返回files、maxFileBytes、maxTotalBytes、usedBytes |
| POST /files?name=URL编码文件名 | 原始二进制请求体，必须提供准确的Content-Length（空文件为0），建议application/octet-stream；成功201及文件元数据 |
| GET /files/{id} | 已认证下载，application/octet-stream + attachment；Content-Length为文件长度 |
| DELETE /files/{id} | 移除传输副本，保留手机原文件及已另存的文件 |
| POST /files/{id}/download-ticket | 认证后获取30秒、单次、绑定文件和凭据的ticket |
| GET /file-download/{ticket} | 浏览器原生下载；只接受对应有效票据，不需要永久Key出现在URL |

浏览器使用短时票据进行原生下载，避免将大文件读成Blob占用移动浏览器内存。Agent优先携认证头直接GET文件，无需票据。不要复用已消费票据；下载中断后重新申请。

## 边界和错误

单文件最多100MiB，传输区总计512MiB、256个文件。最多2个未完成上传和2个同时下载；取消/失败的上传不出现在列表，App重启清理遗留临时文件。传输区副本跨服务重启保存，清除App数据/卸载会删除；要长期保留应在手机另存。

- 400 INVALID_FILENAME：仅文件显示名，不能含路径分隔符、控制字符或超过240字节UTF-8。
- 400 INCOMPLETE_UPLOAD / INVALID_FILE_LENGTH：收到的字节与声明长度不符，或长度无效；不发布不完整副本。
- 411 LENGTH_REQUIRED：必须发送Content-Length；不支持未知长度的chunked上传。
- 401：凭据失效或下载票据已使用/过期；停止认证重试。
- 404 FILE_NOT_FOUND：文件已移除或ID无效，刷新列表，不构造手机路径。
- 413 FILE_TOO_LARGE：超过单文件限制。
- 429 TRANSFER_BUSY：并发或待用票据过多，稍后检查状态。
- 507 TRANSFER_LIMIT / STORAGE_FULL / STORAGE_UNAVAILABLE：额度、剩余空间或存储不可用；按任务范围移除副本或请用户处理。

收发文件及移除副本须属于用户任务范围，不自动收集手机文件。上传成功只表示传输区接收完成，不表示已保存到公共下载目录、安装APK或执行文件。
