# Sxplore 设计文档

## 1. 总体架构

```
┌─────────────────────────────────────────────────────┐
│ UI (Compose)                                         │
│  MainActivity ─ PermissionGate ─ AppRoot             │
│    ├─ ScrollableTabRow + HorizontalPager (多 Tab)     │
│    ├─ FileTreeScreen (层级树, LazyColumn 扁平化)       │
│    ├─ Dialogs (连接/文件操作/传输进度)                  │
│    └─ PlayerActivity (Media3 内置播放器)               │
├─────────────────────────────────────────────────────┤
│ AppViewModel (状态中枢)                               │
│  tabs / clipboard / transfer / dialog 状态            │
├─────────────────────────────────────────────────────┤
│ VFS 抽象层                                            │
│  Vfs 接口: list/stat/openRandomAccess/openInput/      │
│           openOutput/mkdir/delete/rename             │
│  ├─ LocalVfs  (Environment.getExternalStorageDirectory)│
│  └─ SmbVfs    (smbj, SMB2/3, 自动重连)                │
├─────────────────────────────────────────────────────┤
│ StreamProxy (NanoHTTPD, 127.0.0.1:随机端口)           │
│  HTTP Range → VfsRandomAccess.readAt(offset)         │
├─────────────────────────────────────────────────────┤
│ ConnectionStore (EncryptedSharedPreferences)         │
└─────────────────────────────────────────────────────┘
```

## 2. 关键设计决策

### 2.1 为什么用本地 HTTP 代理而不是 ExoPlayer 自定义 DataSource？

自定义 `DataSource` 只能服务内置 ExoPlayer；外部播放器（MX/VLC）无法注入代码。
本地 HTTP 代理把 SMB 随机读暴露为标准 HTTP Range 语义：

- 内置 ExoPlayer：`DefaultHttpDataSource` 直接消费代理 URL
- 外部播放器：`ACTION_VIEW` + 代理 URL，任何支持 HTTP 的播放器都能流播 + seek
- 一条链路，两种播放器，零落盘

安全边界：
- 仅绑定 `127.0.0.1`，局域网其他设备不可达
- URL 含 128-bit 随机 token，不可枚举
- token LRU 上限 32 个，播放器退出后自然淘汰
- `network_security_config` 仅对本机回环地址放行明文 HTTP

### 2.2 Range 请求处理

```
GET /s/{token}/{name}
Range: bytes=1048576-

→ 206 Partial Content
  Content-Range: bytes 1048576-{size-1}/{size}
  Content-Length: {size-1048576}
  body: 从 offset=1048576 起流式读取 SMB

支持: bytes=N- / bytes=N-M / bytes=-N (suffix)
HEAD: 返回 Content-Length + Accept-Ranges（外部播放器探测用）
```

每个 HTTP 请求独占一个 `VfsRandomAccess` 句柄：
- SMB：每请求 `openFile(GENERIC_READ)`，READ 携带绝对偏移，无共享游标，天然并发安全
- 本地：`RandomAccessFile.seek + read`

### 2.3 树视图状态模型

每个 Tab 一份 `TabUiState`：
- `rootEntries` + `children: Map<path, List<VfsEntry>>`（懒加载缓存）
- `expanded: Set<path>`（展开状态）
- 渲染时把树扁平化为 `List<TreeRow(entry, depth)>`，交给 `LazyColumn`（key=path）
- 展开/折叠 = 改 Set，Compose 自动重组；未加载目录点击时异步拉取

### 2.4 SMB 会话管理

- 每个 Tab 独占一个 `SmbVfs`（独立会话），关 Tab 即释放
- 操作失败 → 关闭旧会话 → 重建 → 重试一次（`withRetry`）
- 匿名：`AuthenticationContext.ANONYMOUS`；否则用户名/密码/域
- 凭据持久化于 EncryptedSharedPreferences（AES256-GCM），明文不落盘

### 2.5 跨源复制/移动

剪贴板模型：源（VFS + path + move 标记）→ 目标 Tab 的 `currentDir` 粘贴。
`TransferEngine`：递归收集清单 → 逐文件流式拷贝（128KB 缓冲）→ 进度回调（1MB 节流）。
移动 = 复制成功后删除源。

## 3. 已知限制（MVP）

- 播放器退出 Activity 即停止（无后台播放/MediaSession）
- Tab 状态不跨进程持久化
- 目录复制为串行流式，无并发优化
- SMB1 不支持（smbj 仅 SMB2/3；现代 NAS/Windows 均默认 SMB2+）
- 外部播放器对 http seek 的支持取决于播放器自身（MX/VLC 已验证语义兼容）

## 4. 后续扩展点

- 新协议：实现 `Vfs` 接口即可（WebDAV 可用 Sardine/OkHttp，SFTP 用 sshj）
- 双栏：`TabUiState` 拆为左右两份，共享剪贴板模型不变
- 后台播放：迁移 PlayerActivity → MediaSessionService + Compose 控制 UI
