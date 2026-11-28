# VieY Browser

A lightweight Android browser for smolnet

English | [简体中文](README_zh.md)

- Project page: <https://palhube666.wodemo.net/>
- Downloads: [Lanzou Cloud](https://dmpap.lanzouw.com/b02dc84ch) · [123 Cloud](https://www.123pan.com/s/UML9jv-fk4bh.html) · [Telegram](https://t.me/Kawaii_V)
- Current version: 0.11.4-dev, package size approximately 3.2 MB

---

## 1. Project Overview

VieY Browser is an open-source Android browser for smolnet protocols, initiated by Daimao Piao A Piao. It derives from the author's earlier project Vie, keeps the capabilities related to small-network browsing, removes modules unrelated to the browsing core, and adds independently developed features including feed reading, multi-source downloading and BitTorrent.

The project is written in Java, supports Android 5.0 (API 21) and above, and targets Android 14 (API 34). All functionality is delivered in a single package without external runtimes.

---

## 2. Features

### 2.1 Browsing Core

- General web browsing over HTTP and HTTPS
- Pull-to-refresh
- Long-press shortcuts on the bottom action bar
- Full-screen video playback
- Page access to location, microphone and camera
- External applications may access internal files through local storage
- Built-in run log, categorized by page, resource, script, MQTT and FTP
- Safe browsing interception with a warning page offering return or continue
- Image viewer: tap an image to enlarge, tap again to dismiss

### 2.2 smolnet Identity and Protocols

- Identity certificates are attached to requests; import formats include `.bks`, `.p12`, `.pem`, `.crt` and `.key`
- Protocol coverage includes Gemini, Gopher, Finger, Spartan, Nex, Text, Scroll, Titan, Misfin, Kepler, Scorpion, Molerat and Nps; see Section 3
- TLS variants are supported (`gophers://`, `scorpions://`, `keplers://`)

### 2.3 Feeds and Reading

- Source management: RSS and Atom feeds, with fields compatible with the Legado reader's `rssSources.json`; subscriptions can be imported from and exported to `backup.zip`
- Aggregate timeline: concurrent fetching across sources, ordered by publication time; long-press an entry to open the original page, favorite it, mark it unread or copy the link
- In-app reading: the Legado `ruleContent` rule is applied first; otherwise the main container is selected by text density, with scripts, navigation and advertising blocks removed
- Rule editor: all source fields are editable; `ruleContent` accepts `@css:`, `@@css:` selectors and `@js:` scripts, with a test action that previews the first five entries and the article body
- Scheduled refresh: a JobScheduler job refreshes at the configured interval (minimum 15 minutes); unread counts are computed offline and shown as "订阅 (n) · 未读 N"
- Reading details: offline body cache with automatic fallback; text size cycles through 15, 17, 19 and 21; colors follow the night mode; read entries are dimmed; favorites persist

### 2.4 Downloads

- Multi-source segmented download over HTTP(S) Range, with multiple connections per task (default 8, configurable 1 to 16) and automatic mirror switching
- Dynamic splitting: when more than 4 MB remains and the connection limit is not reached, the remaining range is halved
- Resume: `.part` and `.part.state` record per-segment progress
- Integrity: optional SHA-256 verification before the file is renamed
- Isolated download process: the download service runs in the `:dl` process with the `dataSync` foreground type; the main process and the download process communicate through an atomically written task file, so UI stalls or reclaim do not affect transfers
- Fault tolerance: size detection trusts only `206` responses with `Content-Range`; `403`, `408`, `429` and `5xx` are treated as rate limiting and retried with exponential backoff; a segment total that does not match the file length aborts explicitly, preventing "completed but incomplete" results

### 2.5 BitTorrent

- Two entry points: `.torrent` files (BEP-3, single and multi-file) and `magnet:` links (BEP-9 metadata extension)
- Peer discovery: HTTP(S) tracker announce with compact peer lists; magnet links without trackers are completed with three public trackers
- Transport: TCP PeerWire handshake, block requests and per-piece SHA-1 verification; failed pieces return to the queue, with a full re-verify at the end
- Scheduling: the peer pool refills continuously, connections are recycled and re-announced when exhausted, and the peer that supplied the metadata is preferred
- HTTP seeds: BEP-19 `url-list` and `httpseeds`, fetched by Range requests when the tracker reports no peers, verified the same way
- Resume: `.bt.state` records piece-level progress
- Deep links: `magnet:` and `.torrent` are registered as `ACTION_VIEW`, opening the download page and starting automatically
- Limits: no seeding or upload, no DHT or PEX, HTTP(S) trackers only (no UDP tracker), HTTP seeds limited to single-file torrents; rare torrents may find no peers without DHT

### 2.6 Extensions and Tools

- User scripts: `.user.js` import by pasted content or URL, `@match` and `@include` matching, enable and disable, configurable script market URL
- Web font injection: `shouldInterceptRequest` serves a same-origin `/__vie__/font.ttf` with a companion script, allowing fonts to load within a CSP whitelist
- Same-origin script injection: `/__vie__/js/<name>.js` is served by the interceptor, falling back to local execution when `script-src` is restricted
- Floating window browsing: a service hosts a WebView overlay with a draggable title bar, reusing the font and resource interception chain
- MQTT: connection, subscription and message list based on Eclipse Paho
- FTP: directory browsing and single-file download based on Apache Commons Net
- uTP transport skeleton: BEP-29 header, connection setup, MTU-sized stop-and-wait acknowledgement with retransmission, FIN and RESET, plus a loopback self-test; congestion control and BitTorrent peer state are not implemented

---

## 3. Protocol Support

The table lists implemented smolnet protocols and their completeness. "Available" means basic interaction is supported.

| Protocol | Status | Notes |
|---|---|---|
| `http://`, `https://` | Available | Includes HTTP over TLS |
| `file://` | Available | Local file access |
| `gemini://` | Available | Identity certificates supported; uploads use `titan://` |
| `gopher://` | Available | Text rendering and file download; Gopher+ and `gophers://` supported |
| `finger://` | Available | Automatic text-to-link conversion not supported |
| `spartan://` | Available | Upload not supported |
| `nex://` | Available | Uploads use `nps://` |
| `text://` | Available | `data:` links supported |
| `scroll://` | Available | Language list and metadata requests; streaming rendering pending |
| `titan://` | Available | Text and file upload with identity certificates; `;edit` pending |
| `misfin://` | Available | Forms A, B and C; C follows draft 9 |
| `kepler://` | Available | Identity certificates and `keplers://`; page cache pending |
| `scorpion://` | Available | R sub-protocol and `scorpions://`; other sub-protocols are written but lack test links, so they remain disabled |
| `molerat://` | Available | Page cache supported; correctness not fully verified for lack of test links |
| `nps://` | Available | Upload companion to `nex://` |

Several further protocols are under research or set aside: `guppy://`, `cso://`, `fsp://`, `nimigem://`, `mark://`, `terse://`, `inimeg://`, `iapetus://`, `mercury://`, `gequilla://` and `fist://`. Among them `mark://` depends on QUIC and may significantly increase package size; the specifications for `inimeg://` and `iapetus://` are deprecated; `mercury://`, `gequilla://` and `fist://` exist only as proposals without formal specifications.

For broader protocol coverage such as `rtsp://`, `sftp://`, `webdav://` or `irc://`, the parent project Vie is the appropriate choice.

---

## 4. Comparison with Other smolnet Browsers

| Item | VieY | Vie | Lagrange | Zapri | portal.mozz.us |
|---|---|---|---|---|---|
| Size | ~3.2 MB | ~5 MB | ~20 MB | ~20 MB | No install |
| Platform | Android | Android | Multi-platform | Android | Multi-platform |
| Identity certificates | `.bks`, `.p12`, `.pem`, `.crt/.key` | `.bks`, `.p12` | `.pem` | Not supported | `.pem` |
| Languages | Simplified Chinese, Traditional Chinese, English | Simplified Chinese | Multiple (Chinese rendering broken) | English | English |
| Strengths | Broad protocol coverage, general web pages work | User scripts, suitable as a daily browser | Multi-platform | Simple | smolnet access through an HTTP proxy |
| Weaknesses | Plain interface | Chinese only | Dated interface | No identity certificates | Depends on a server; unavailable when it is down |
| `http(s)://` | Yes | Yes | No | No | Yes |
| `gemini://` | Yes | Yes | Yes | Yes | Yes |
| `gopher(s)://` | Yes | Yes | Yes | No | Yes |
| `finger://` | Yes | Yes | Yes | Yes | Yes |
| `nex://` | Yes | Yes | Yes | No | Yes |
| `text://` | Yes | Yes | No | Yes | Yes |
| `spartan://` | Yes | Yes | Yes | No | Yes |
| `kepler(s)://` | Yes | Yes | No | No | No |
| `scorpion(s)://` | Yes | Yes | No | No | No |
| `titan://` | Yes | Yes | Yes | No | Yes |
| `misfin://` | Yes | Yes | Yes | No | No |

---

## 5. Build and Development

### 5.1 Build

The project builds with Gradle and Android Gradle Plugin 7.4.2, and requires JDK 17 and Android SDK Build-Tools 34.0.0.

```bash
./gradlew assembleRelease    # release build with R8 and resource shrinking
./gradlew assembleDebug      # debug build, no minification
./gradlew lintRelease        # static analysis
```

Artifacts are written to `build/outputs/apk/`. Release builds are signed with `keystore/viey-release.jks`; the credentials can be overridden through the `VIEY_STORE_PASS`, `VIEY_KEY_ALIAS` and `VIEY_KEY_PASS` environment variables.

Continuous integration is configured in `.github/workflows/build.yml`. Pushes to `main`, `feature/**` or `fix/**` build the release and debug packages and verify the signature.

### 5.2 Project Layout

The source layout follows the convention of iApp Studio, and the build script maps it through `sourceSets`:

| Path | Content |
|---|---|
| `main/AndroidManifest.xml` | Manifest |
| `src/` | Java source root |
| `main/res/` | Resources |
| `main/assets/` | Static assets and injected scripts |
| `keystore/` | Release signing material |

To import the project into another IDE, remap the source, resource and dependency paths to the conventional Android layout.

### 5.3 Branch Policy

The repository keeps two categories of branches, and each name states its focus directly:

| Branch | Purpose |
|---|---|
| `main` | Mainline, always buildable and releasable |
| `feature/rss-subscription` | RSS subscription and reading work |

New feature branches use `feature/<focus>` and fixes use `fix/<focus>`; ambiguous names are avoided. A feature branch is merged into `main` once complete.

---

## 6. Usage

1. Use it as an ordinary browser by entering a URL.
2. Long-press each button on the bottom action bar for its shortcut.
3. Identity certificates are imported from the settings page and several formats are accepted.
4. Feed features are under "订阅源管理" in settings: add sources, edit rules, import and export backups, and enable scheduled background refresh.
5. Download features are under "下载" in settings, covering multi-source HTTP downloads and BitTorrent.

---

## 7. Tech Stack

- Android Java, with HTML and JavaScript for the interface layer
- Gradle build with GitHub Actions continuous integration
- jsoup for HTML parsing and article extraction
- BouncyCastle for certificate and key handling
- Eclipse Paho for MQTT and Apache Commons Net for FTP
- Custom URL handling and modular protocol implementations

---

## 8. FAQ

**Why does the feature set differ from the parent project Vie?**

This project targets smolnet access and prioritizes protocol coverage and a clean browsing experience, so modules unrelated to the browsing core were deliberately trimmed. Parts of Vie may later be rewritten in Java and merged in.

**What is smolnet?**

smolnet refers to a family of text-oriented, advertisement-free and lightweight network protocols and communities, including Gemini, Gopher, Finger and Nex. Pages are structurally simple, which suits low-bandwidth and low-power devices.

**Why is the source sparsely commented?**

This is a personal interest project developed in limited time, and some code and page assets come directly from the parent project Vie. Comments are being added progressively, and the key modules for downloads, BitTorrent and feeds already carry reasonably complete documentation.

**Why does the project layout differ from a conventional Android project?**

The project was originally developed in iApp Studio and kept its directory convention. A Gradle build script has since been added, so it builds directly; developing in Android Studio requires remapping the source and resource paths and declaring dependencies locally.

**Why is the name VieY?**

The parent project is named Vie, also known as VieK. The prefix is retained, and the suffix Y comes from the name of a friend of the author.

**Why is the app not published on app stores?**

It is an interest-driven open-source project that does not target broad distribution, so packages are published through the author's own channels.

**Why are all certificates trusted instead of using TOFU?**

Certificate pinning was not implemented in early versions, so self-signed certificates are currently trusted unconditionally. TOFU and certificate fingerprint verification are planned.

**Why was iApp Studio chosen originally instead of a conventional compiler?**

iApp Studio offers a Chinese interface and can build and package Java projects directly on a mobile device, which suited development in short sessions. The project now builds with Gradle, so this constraint no longer applies.

**How can I report a problem or suggest a feature?**

See the contact details in Section 10.

---

## 9. License

This project is licensed under the Apache License 2.0.

- Commercial use, modification, private deployment and redistribution are permitted
- The original copyright notice must be retained, and modified files must be marked as changed
- A patent license is granted automatically to users
- The project name may not be used for promotion without permission

When building on this project, include the following notice in the acknowledgements, license section or another visible location:

> This project is based on the open-source VieY Browser by Daimao Piao A Piao, originally published at <https://gitee.com/awzx3214/VieYBrowser/>

If only parts of the code are used, citing the project in the license section is sufficient.

---

## 10. Contact

For usage questions, problem reports, feature requests or access to the latest source, contact the author through any of the following:

- QQ: 2171802813
- Email: <VieBrowser@hotmail.com> (note that the domain contains no letter Y)
- Coolapk: [@呆毛飘啊飘](https://www.coolapk.com/u/1318094)
- Telegram: @Kawaii_R
