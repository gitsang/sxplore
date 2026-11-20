# Sxplore Design

## 1. Architecture

```
┌─────────────────────────────────────────────────────┐
│ UI (Compose)                                         │
│  MainActivity ─ PermissionGate ─ AppRoot             │
│    ├─ SxploreTabRow + HorizontalPager (multi-tab)     │
│    ├─ FileTreeScreen (tree, flattened LazyColumn)     │
│    ├─ Dialogs (connect / file ops / transfer)         │
│    └─ PlayerActivity (Media3 built-in player)         │
├─────────────────────────────────────────────────────┤
│ AppViewModel (state hub)                             │
│  tabs / clipboard / transfer / dialog state          │
├─────────────────────────────────────────────────────┤
│ VFS abstraction                                      │
│  Vfs: list/stat/openRandomAccess/openInput/          │
│       openOutput/mkdir/delete/rename                 │
│  ├─ LocalVfs  (external storage root)                │
│  └─ SmbVfs    (smbj, SMB2/3, auto-reconnect)          │
├─────────────────────────────────────────────────────┤
│ StreamProxy (NanoHTTPD, 127.0.0.1:random port)        │
│  HTTP Range → VfsRandomAccess.readAt(offset)         │
├─────────────────────────────────────────────────────┤
│ ConnectionStore (EncryptedSharedPreferences)         │
└─────────────────────────────────────────────────────┘
```

## 2. Key decisions

### 2.1 Why a local HTTP proxy instead of a custom ExoPlayer DataSource?

A custom `DataSource` only serves the built-in ExoPlayer; external players (MX/VLC) cannot be injected with code.
The local HTTP proxy exposes SMB positional reads as standard HTTP Range semantics:

- Built-in ExoPlayer: `DefaultHttpDataSource` consumes the proxy URL directly
- External players: `ACTION_VIEW` + proxy URL; any HTTP-capable player can stream and seek
- One path, two player types, zero disk writes

Security boundary:
- Binds only to `127.0.0.1`, unreachable from other LAN devices
- URL carries a 128-bit random token, not enumerable
- Token LRU capped at 32; evicted naturally after the player exits
- `network_security_config` permits cleartext HTTP only for the loopback address

### 2.2 Range request handling

```
GET /s/{token}/{name}
Range: bytes=1048576-

→ 206 Partial Content
  Content-Range: bytes 1048576-{size-1}/{size}
  Content-Length: {size-1048576}
  body: streamed from offset=1048576 over SMB

Supports: bytes=N- / bytes=N-M / bytes=-N (suffix)
HEAD: returns Content-Length + Accept-Ranges (external player probing)
```

Each HTTP request owns a `VfsRandomAccess` handle:
- SMB: `openFile(GENERIC_READ)` per request; READ carries an absolute offset, no shared cursor, concurrency-safe
- Local: `RandomAccessFile.seek + read`

### 2.3 Tree view state model

One `TabUiState` per tab:
- `rootEntries` + `children: Map<path, List<VfsEntry>>` (lazy-load cache)
- `expanded: Set<path>` (expansion state)
- At render time the tree is flattened to `List<TreeRow(entry, depth)>` for `LazyColumn` (key=path)
- Expand/collapse = mutate the Set; Compose recomposes; unloaded folders fetch asynchronously on click

### 2.4 SMB session management

- Each tab owns a `SmbVfs` (independent session); closing the tab releases it
- Operation fails → close old session → rebuild → retry once (`withRetry`)
- Anonymous: `AuthenticationContext.anonymous()`; otherwise username/password/domain
- Credentials persisted in EncryptedSharedPreferences (AES256-GCM), never plaintext

### 2.5 Cross-source copy/move

Clipboard model: source (VFS + path + move flag) → paste into the target tab's `currentDir`.
`TransferEngine`: recursive plan → streamed file-by-file copy (128KB buffer) → progress callback (1MB throttle).
Move = copy, then delete source on success.

### 2.6 Performance: negotiated buffers + prefetch pipeline

Desktop SMB clients feel instant on the same NAS/Wi-Fi because they negotiate
multi-MB read sizes and pipeline requests. smbj defaults to 64KB buffers, which
turns streaming into hundreds of tiny round-trips (throughput ≈ 64KB / RTT) and
makes directory listings crawl. Fixes:

- `SmbConfig.withNegotiatedBufferSize()`: read/write/transact buffers follow the
  server-negotiated maxima (typically 1–8MB), same as desktop clients
- `PrefetchInputStream` in the proxy: a background worker reads ahead in 1MB
  chunks into a 4-slot queue; the HTTP response consumes buffered chunks while
  the next reads are already in flight, hiding RTT and Wi-Fi jitter
- Low-latency Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY`) while the built-in player
  is active, keeping the radio out of power-save

### 2.7 AI live captions + bilingual translation

`CaptionEngine` opens a second stream of the playing media, demuxes/decodes the
audio track (MediaExtractor + MediaCodec), resamples to 16kHz mono PCM and sends
~8s WAV chunks to an OpenAI-compatible `/audio/transcriptions` endpoint
(Whisper; OpenAI or self-hosted). `verbose_json` segment timestamps are mapped
to absolute media time. The decode loop runs up to 60s AHEAD of playback so
captions are ready before the audio is heard. Optional translation uses
`/chat/completions` with a line-preserving prompt; original + translated lines
render as a bilingual overlay. Seek / next item restarts the engine at the new
position. All endpoints/keys/models are configured in the settings page.

### 2.8 Session restore

With the "Remember tabs and paths" switch on, a debounced `snapshotFlow` watcher
serializes tab layout (kind, saved SMB connection id, expanded paths, current
dir, active index) to preferences; on launch the ViewModel rebuilds tabs and
reloads root + expanded folders. SMB tabs restore only saved connections.

### 2.9 Custom tab strip (crash fix)

Material3 `ScrollableTabRow` throws `IndexOutOfBoundsException` at `TabRow.kt:502` when the tab count changes:
its indicator reads `tabPositions[selectedTabIndex]` before the position list is re-measured for the new count
(e.g. adding a second tab sets `selectedTabIndex=1` while `tabPositions` still has length 1).

Fix: `SxploreTabRow`, a hand-rolled `Row` + `horizontalScroll` strip. Each tab draws its own indicator from its own
`selected` flag, so there is no shared position list to go stale. `onGloballyPositioned` records each tab's x offset
and a `LaunchedEffect` auto-scrolls the active tab into view.

## 3. Known limitations (MVP)

- Playback stops when the player Activity exits (no background playback / MediaSession)
- Tab state is not persisted across process death
- Directory copy is serial/streamed, no concurrency optimization
- SMB1 not supported (smbj is SMB2/3 only; modern NAS/Windows default to SMB2+)
- External-player http seek support depends on the player itself (MX/VLC semantics verified)

## 4. Extension points

- New protocol: implement `Vfs` (WebDAV via Sardine/OkHttp, SFTP via sshj)
- Dual pane: split `TabUiState` into left/right; the shared clipboard model is unchanged
- Background playback: migrate PlayerActivity → MediaSessionService + Compose control UI
