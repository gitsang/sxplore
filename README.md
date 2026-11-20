# Sxplore

Android file manager inspired by X-plore. **Headline feature: SMB streaming playback — play directly over the network, never download to local storage first.**

## Features

- 🌲 **Hierarchical tree view**: expand/collapse folders in place, lazy-load children, smooth with tens of thousands of files
- 🗂 **Multi-tab**: each tab browses local storage or a different SMB server independently; swipe left/right to switch
- 🖧 **SMB2/3**: powered by [smbj](https://github.com/hierynomus/smbj), anonymous and username/password auth, automatic reconnect
- ▶️ **Built-in player**: Media3/ExoPlayer with seek, audio-track and subtitle selection
- ↗️ **External players**: hand off to MX Player / VLC etc. through the same streaming proxy — still no download
- 📋 **File management**: copy / cut / paste (across local and SMB), rename, delete, new folder, properties
- 🔐 **Encrypted credentials**: SMB accounts stored with EncryptedSharedPreferences (AES256-GCM)
- 🧠 **AI live subtitles**: Whisper speech recognition via OpenAI-compatible API, with optional AI translation for bilingual subtitles (configurable base URL / key / model)
- 💾 **Session restore**: optional remember-tabs-and-paths switch restores the previous layout on launch
- ⚙️ **Full settings page**: general, AI subtitles and translation configuration

## How streaming playback works

```
┌────────────┐   HTTP Range    ┌──────────────────┐   SMB2 READ(offset)   ┌────────────┐
│ ExoPlayer / │ ─────────────▶ │ Local HTTP proxy  │ ────────────────────▶ │ SMB server  │
│ MX / VLC    │ ◀───────────── │ 127.0.0.1:rand    │ ◀──────────────────── │             │
└────────────┘   206 stream     └──────────────────┘      chunks           └────────────┘
```

- A lightweight HTTP server (NanoHTTPD) listens only on `127.0.0.1`
- Each player `Range: bytes=start-end` request becomes an offset SMB READ
- Data is read and forwarded on the fly — **zero bytes written to disk**; seeking just reads from a new offset
- Server-negotiated multi-MB read buffers + a 1MB×4 prefetch pipeline hide network latency, so playback streams smoothly instead of re-buffering
- URLs carry a random token and are reachable only from this device, so other LAN devices cannot read them
- The built-in player and external players share the exact same proxy URL

## Tech stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin 2.0 + Jetpack Compose + Material3 |
| Architecture | MVVM + VFS abstraction (one interface for local / SMB, extensible to WebDAV/FTP/SFTP) |
| SMB | smbj 0.13 (SMB2/3) |
| Streaming proxy | NanoHTTPD (127.0.0.1, HTTP Range → positional read) |
| Playback | Media3 / ExoPlayer 1.4 |
| Credential storage | androidx.security EncryptedSharedPreferences |
| minSDK / target | 26 (Android 8.0) / 34 |

## Build

```bash
# Requires JDK 17 and Android SDK (platform 34)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

On first launch, grant "All files access" (Android 11+) to browse local files; SMB needs no storage permission.

## Notes

- The tab strip is a custom scrollable composable rather than Material3 `ScrollableTabRow`, which throws `IndexOutOfBoundsException` when the tab count changes (stale indicator position list).

## Roadmap

- [ ] Dual-pane layout (classic X-plore form)
- [ ] On-device whisper.cpp recognition (currently API-based)
- [ ] WebDAV / FTP / SFTP
- [ ] Background playback / notification controls (MediaSession)
- [ ] Playlists and continuous playback
- [ ] Bookmarks / history
- [ ] Archive browsing (zip/7z)
- [ ] File search
- [ ] Persist tab state

## License

MIT
