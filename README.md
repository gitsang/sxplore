# Sxplore

Android 文件管理器，灵感来自 X-plore。**核心特性：SMB 流式播放 —— 不下载到本地，直接边读边播。**

## 功能

- 🌲 **层级树视图**：目录原地展开/折叠，懒加载子目录，万级文件不卡顿
- 🗂 **多 Tab**：每个标签页独立浏览本地存储或不同 SMB 服务器，左右滑动切换
- 🖧 **SMB2/3**：基于 [smbj](https://github.com/hierynomus/smbj)，支持匿名与账号密码认证，断线自动重连
- ▶️ **内置播放器**：Media3/ExoPlayer，支持拖动 seek、音轨/字幕选择
- ↗️ **外部播放器**：一键唤起 MX Player / VLC 等，同样走流式代理，无需下载
- 📋 **文件管理**：复制 / 剪切 / 粘贴（跨本地与 SMB）、重命名、删除、新建文件夹、属性
- 🔐 **凭据加密存储**：SMB 账号密码使用 EncryptedSharedPreferences（AES256-GCM）保存

## 流式播放原理

```
┌────────────┐   HTTP Range    ┌──────────────────┐   SMB2 READ(offset)   ┌────────────┐
│ ExoPlayer / │ ─────────────▶ │ 本地 HTTP 代理     │ ────────────────────▶ │ SMB 服务器  │
│ MX / VLC    │ ◀───────────── │ 127.0.0.1:随机端口 │ ◀──────────────────── │            │
└────────────┘   206 流式响应   └──────────────────┘      数据块            └────────────┘
```

- App 内启动仅监听 `127.0.0.1` 的轻量 HTTP 服务（NanoHTTPD）
- 播放器的每个 `Range: bytes=start-end` 请求被翻译为 SMB 带偏移的 READ
- 数据边读边发，**全程零落盘**；seek 即从新偏移读取
- URL 携带随机 token，仅本机可达，局域网内其他设备无法访问
- 内置播放器与外部播放器共用同一条代理 URL

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.0 + Jetpack Compose + Material3 |
| 架构 | MVVM + VFS 抽象层（本地 / SMB 统一接口，可扩展 WebDAV/FTP/SFTP） |
| SMB | smbj 0.13（SMB2/3） |
| 流式代理 | NanoHTTPD（127.0.0.1，HTTP Range → 随机读） |
| 播放 | Media3 / ExoPlayer 1.4 |
| 凭据存储 | androidx.security EncryptedSharedPreferences |
| minSDK / target | 26 (Android 8.0) / 34 |

## 构建

```bash
# 需要 JDK 17 与 Android SDK（platform 34）
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

首次运行需授予"所有文件访问"权限（Android 11+）用于本地文件浏览；SMB 功能不需要存储权限。

## Roadmap

- [ ] 双栏面板（X-plore 经典形态）
- [ ] WebDAV / FTP / SFTP
- [ ] 后台播放 / 通知栏控制（MediaSession）
- [ ] 播放列表与连续播放
- [ ] 书签 / 历史
- [ ] 压缩包浏览（zip/7z）
- [ ] 文件搜索
- [ ] Tab 状态持久化

## License

MIT
