# Readest Lite Android

一个基于 **Tauri v2** 的极简 Android 阅读壳 App。它不内置 Chromium，直接调用 Android 系统 WebView 加载你硬编码的阅读站点地址，体积小、启动快、行为可控。

适合所有自部署的网页版阅读器（如 [Readest](https://github.com/readest/readest)、Kavita、Komga、Calibre-Web 等），把任意阅读站点"封装"成原生 App 体验。

---

## 特性概览

| 类别 | 行为 |
|------|------|
| 内核 | 系统 WebView（不内置 Chromium） |
| 启动 | 直接加载硬编码 `HOME_URL`，无地址栏 / 无导航页 |
| UI | 全屏渲染，完全依赖系统原生导航条，不自定义状态栏 |
| 屏幕方向 | 强制竖屏 |
| 跳转拦截 | `_blank` / `target=_blank` 统一在 App 内页面栈加载，不会跳出浏览器 |
| 剪贴板检测 | 前台切换 / 冷启动时读取剪贴板，匹配到 `HOME_URL` 域名链接弹系统询问框；进程内去重，进程销毁自动失效 |
| 返回键 | 优先弹出 WebView 页面栈；仅剩主页时弹"确定退出应用？" |
| 文件上传 | 唤起系统文件选择器 |
| 文件下载 | 调用系统 DownloadManager 落盘到公共 `Download/` 目录 |
| 深链 | 注册 `readest://share/ID`，点击后自动在 App 内打开对应分享页 |
| 持久化 | 登录会话、阅读偏好、书架配置全部持久化 |
| 权限 | 仅 `INTERNET` + `ACCESS_NETWORK_STATE`（API ≤ 28 附加 `WRITE_EXTERNAL_STORAGE` 用于下载） |
| 最低 Android | 8.0 (API 26) |
| CPU 架构 | arm64-v8a / armeabi-v7a（覆盖 99% 安卓设备） |

---

## 下载现成 APK

如果你只是想用现成的（内置某个示例站点），可以从 [Releases](https://github.com/cshdotcom/Readest-lite-Android/releases) 页面下载：

- `readest-lite-arm64-v8a-release.apk` — 64 位 ARM，**绝大多数现代手机用这个**
- `readest-lite-armeabi-v7a-release.apk` — 32 位 ARM，老机型备用

**但是！** 仓库内的 Release APK 是仓库作者用自己的阅读站点地址编译的。你要用自己部署的阅读站点，必须按下面教程重新编译。这就是本项目的设计：硬编码地址，每个用户自己编译自己的版本。

---

## 自定义编译完整教程

### 0. 你需要准备的东西

- 一台 Linux / macOS / WSL2 机器（Windows 原生未验证，建议用 WSL2）
- 一个已经部署好、可以公网访问的阅读站点 URL（例如 `https://my-reader.example.com`）
- 大约 5GB 磁盘空间 + 30 分钟编译时间（首次）

### 1. 安装基础工具

```bash
# Node.js 18+ (推荐 20 LTS)
# - macOS: brew install node
# - Linux: 参见 https://github.com/nodesource/distributions
# - WSL2: 同 Linux

# Rust 工具链
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "$HOME/.cargo/env"

# 添加 4 个 Android Rust target（其实只需要 arm64 和 armv7 两个，但全装也无害）
rustup target add aarch64-linux-android armv7-linux-androideabi

# Tauri CLI（用 npm 版更快，避免从源码编译 cargo 版）
npm install -g @tauri-apps/cli@^2
```

### 2. 安装 JDK 17

Tauri v2 要求 JDK 17。推荐 [Eclipse Temurin](https://adoptium.net/)：

```bash
# Linux
wget https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz
tar xzf OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz -C $HOME/tools
export JAVA_HOME=$HOME/tools/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH

# macOS (Apple Silicon)
# 用 brew: brew install --cask temurin@17
# 或下载 aarch64 版本手动解压

# 验证
javac -version   # 应输出 javac 17.0.x
```

### 3. 安装 Android SDK + NDK

最简单的方式是用 Android Studio 的 SDK Manager。这里给出命令行版：

```bash
# 下载 cmdline-tools
mkdir -p $HOME/tools/android-sdk/cmdline-tools
cd $HOME/tools
curl -L -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip cmdline-tools.zip
mv cmdline-tools android-sdk/cmdline-tools/latest
rm cmdline-tools.zip

# 配置环境变量（写入 ~/.bashrc 或 ~/.zshrc）
export ANDROID_HOME=$HOME/tools/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

# 接受所有 license 并安装
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018"
```

### 4. 克隆本项目

```bash
git clone https://github.com/cshdotcom/Readest-lite-Android.git
cd Readest-lite-Android
npm install
```

### 5. ⚠️ 修改硬编码首页地址（关键步骤！必须改两处）

这个 App 把首页地址硬编码在两个地方，**两处必须改成同一个 URL**，否则会白屏。

#### 第一处：`src-tauri/tauri.conf.json`

找到 `app.windows[0].url` 字段：

```json
"windows": [
  {
    "title": "阅读",
    "url": "https://YOUR_READER_DOMAIN.example.com"
  }
]
```

改成你的地址，例如：

```json
"url": "https://my-reader.example.com"
```

**这处决定 Tauri 启动时 WebView 实际加载的 URL**（Rust 侧编译期写入 .so）。

#### 第二处：`src-tauri/gen/android/app/src/main/java/com/readest/app/MainActivity.kt`

找到 `HOME_URL` 常量（约第 74 行）：

```kotlin
private const val HOME_URL = "https://YOUR_READER_DOMAIN.example.com"
```

改成和第一处一样的地址：

```kotlin
private const val HOME_URL = "https://my-reader.example.com"
```

**这处用于 Kotlin 侧的深链转换、剪贴板域名匹配、加载兜底**。剪贴板分享链接的域名匹配会自动从 `HOME_URL` 解析 host，无需另外配置。

> ⚠️ **两处必须完全一致**，包括 `https://` 还是 `http://`、是否带末尾 `/`、域名大小写。建议直接复制粘贴。

#### 同时建议修改的（可选）

- **App 名称**：`src-tauri/gen/android/app/src/main/res/values/strings.xml` 里的 `<string name="app_name">阅读</string>`
- **App 图标**：把 `src-tauri/icons/icon.png` 等文件替换成你自己的 1024×1024 PNG，然后重新跑 `npm run tauri android init`（会重新生成 mipmap）
- **包名**：如果你想避免和原作者冲突，修改 `tauri.conf.json` 的 `identifier` 和 `app/build.gradle.kts` 的 `applicationId`、`namespace`（三处必须一致，且包名不要以 `.app` 结尾）

### 6. 初始化 Android 工程（仅首次）

```bash
npm run tauri android init
```

这会在 `src-tauri/gen/android/` 下生成完整的 Android Studio 工程。

### 7. 生成 release 签名 keystore

自签一份 keystore 用于 release APK 签名（只做一次，之后所有版本都用同一个）：

```bash
cd src-tauri/gen/android

keytool -genkeypair -v \
  -keystore release.keystore \
  -alias readest \
  -storetype PKCS12 \
  -keyalg RSA -keysize 2048 \
  -validity 36500 \
  -storepass 你的store密码 \
  -keypass 你的key密码 \
  -dname "CN=Your Name, OU=App, O=Your Org, L=City, ST=State, C=CN"

# 把密码写到 keystore.properties（不要提交到 git！）
cat > keystore.properties << EOF
storeFile=release.keystore
storePassword=你的store密码
keyAlias=readest
keyPassword=你的key密码
EOF

cd ../../..
```

`release.keystore` 和 `keystore.properties` 都已在 `.gitignore` 里，不会被提交。

### 8. 编译 APK

```bash
# 编译 2 个 ABI 的独立 APK（推荐：体积小，启动快）
npm run tauri android build -- --apk --target aarch64 armv7 --split-per-abi --ci
```

构建成功后，APK 在：

```
src-tauri/gen/android/app/build/outputs/apk/
├── arm64/release/app-arm64-release.apk        # 64 位 ARM
└── arm/release/app-arm-release.apk            # 32 位 ARM
```

#### 编译参数说明

| 参数 | 作用 |
|------|------|
| `--apk` | 输出 APK（不加这个默认输出 AAB，用于上架 Google Play） |
| `--target aarch64 armv7` | 只编译这两个 Rust target |
| `--split-per-abi` | 按 ABI 拆分成独立 APK，每个文件只含一份 .so |
| `--ci` | 跳过交互提示，自动选择默认值 |

如果想要一个包含所有 ABI 的"大一统 APK"（不推荐，体积翻倍）：

```bash
npm run tauri android build -- --apk --target aarch64 armv7 --ci
```

去掉 `--split-per-abi` 即可，输出在 `universal/release/app-universal-release.apk`。

### 9. 安装到手机

```bash
# 用 adb 安装（需要先 adb connect 或 USB 连接）
adb install -r src-tauri/gen/android/app/build/outputs/apk/arm64/release/app-arm64-release.apk

# 或者把 APK 文件传到手机（微信/数据线/网盘都行），手机上点击安装
```

手机端需要先在「设置 → 安全 → 未知来源应用安装」允许从文件管理器安装。

### 10. 调试模式（可选）

如果想看 console.log 排查问题，编译 debug 版：

```bash
npm run tauri android build -- --apk --target aarch64 --debug --ci
```

debug APK 的 WebView 可以通过 `chrome://inspect`（Chrome 浏览器地址栏输入）远程调试。

---

## 项目结构

```
Readest-lite-Android/
├── src/                              # 前端启动屏（加载中动画，加载完后被 WebView 替换）
│   └── index.html
├── src-tauri/
│   ├── Cargo.toml                    # Rust 依赖
│   ├── tauri.conf.json               # Tauri 主配置
│   ├── capabilities/default.json     # Tauri 权限配置
│   ├── icons/                        # 应用图标（所有尺寸）
│   ├── src/
│   │   ├── main.rs                   # Rust 入口
│   │   └── lib.rs                    # Rust 后端命令
│   └── gen/android/                  # 生成的 Android 工程
│       ├── app/
│       │   ├── build.gradle.kts      # Gradle 配置（minSdk/ABI/签名）
│       │   ├── proguard-rules.pro    # 代码混淆规则
│       │   └── src/main/
│       │       ├── AndroidManifest.xml        # 权限/竖屏/深链
│       │       ├── java/com/readest/app/
│       │       │   ├── MainActivity.kt        # ⭐ 核心：WebView 配置 + 所有业务逻辑
│       │       │   └── ClipboardMemory.kt     # 剪贴板去重标记单例
│       │       └── res/                        # 资源（图标、字符串、主题）
│       └── keystore.properties      # ⚠️ 不提交，签名配置
├── package.json
└── README.md
```

---

## 关键文件说明

### `MainActivity.kt`

整个 App 90% 的逻辑都在这里。关键代码段：

- **`HOME_URL` 常量**：你**必须改**的地方之一（另一个在 `tauri.conf.json`）
- **`configureWebSettings()`**：WebView 完整配置（JS、DOM Storage、Cookie、本地文件访问等）
- **`installCustomClients()`**：设置自定义 WebViewClient / WebChromeClient / DownloadListener
  - ⚠️ **必须用 `webView.post { }` 推迟调用**，否则会被 wry 在 `onWebViewCreate` 之后设置的 `RustWebViewClient` / `RustWebChromeClient` 覆盖，导致白屏
- **`onWebViewCreate()`**：Tauri 创建完 RustWebView 后的回调，在这里接管 WebView
- **`ShellWebViewClient`**：URL 路由 / `_blank` 拦截 / 历史栈管理
- **`ShellWebChromeClient`**：文件上传选择器
- **`ShellDownloadListener`**：文件下载（DownloadManager 落盘）
- **`checkClipboardForShareLink()`**：剪贴板检测 + 弹窗
- **`onBackPressedDispatcher`**：返回键栈逻辑 + 退出确认
- **`convertReadestDeepLink()`**：`readest://` 深链转换

#### ⚠️ wry 初始化时序（重要，避免白屏）

Tauri Android 底层用 wry 创建 WebView，初始化顺序是：

```
1. wry 创建 RustWebView
2. wry 调用 activity.setWebView(webview)  ← onWebViewCreate 在这里
3. wry 调用 webview.loadUrl(初始URL)       ← 加载 tauri.conf.json 配置的 url
4. wry 创建并 setWebViewClient(RustWebViewClient)  ← 会覆盖你的 client
5. wry 调用 setWebChromeClient(RustWebChromeClient) ← 会覆盖你的 chrome client
6. wry 调用 addJavascriptInterface(ipc)
7. wry 调用 setContentView(webview)
```

**所以 `onWebViewCreate` 里直接 `setWebViewClient` 会被步骤 4/5 覆盖**，导致自定义 URL 拦截、文件上传、下载等全部失效，WebView 卡在初始页白屏。

本项目的解决方案：
1. `tauri.conf.json` 把 `app.windows[0].url` 设为你的阅读站点 External URL，让 wry 在步骤 3 直接加载 HOME_URL（而不是 `tauri://localhost`）
2. `onWebViewCreate` 里用 `webView.post { installCustomClients(webView) }` 把 client 设置推迟到下一个事件循环，等 wry 步骤 4/5 执行完后再覆盖回来
3. post 里还做了兜底：如果当前 URL 仍是 `tauri://` 或 `about:blank`，强制 `loadUrl(HOME_URL)`

### `ClipboardMemory.kt`

一个 `object` 单例，用 `MutableSet<String>` 记录本次进程已弹窗过的链接。

- 进程不销毁 → 单例存活 → 同链接不重弹
- 进程销毁 → 单例随 JVM 销毁 → 重启自动清空

无需显式 reset，进程生命周期天然就是标记有效期。

### `AndroidManifest.xml`

- `minSdk="26"` — Android 8.0
- `screenOrientation="portrait"` — 竖屏锁定
- `hardwareAccelerated="true"` — 硬件加速
- `intent-filter` 注册 `readest://` 深链
- 仅声明 `INTERNET` + `ACCESS_NETWORK_STATE` + （≤28）`WRITE_EXTERNAL_STORAGE`

### `build.gradle.kts`

- `minSdk = 26`
- `abiFilters = ["arm64-v8a", "armeabi-v7a"]`
- release 配置：minify + shrinkResources + 自签 keystore

---

## 常见问题

### Q1. 编译报错 `Keystore file not found`

`keystore.properties` 里的 `storeFile` 是相对 `src-tauri/gen/android/` 目录的路径。确保 `release.keystore` 文件在 `src-tauri/gen/android/release.keystore`。

### Q2. 编译报错 `failed to build WebSocket client: Connection refused`

Tauri CLI 的 daemon 模式偶发问题。解决：

```bash
# 杀掉所有遗留的 tauri/gradle 进程
pkill -f tauri; pkill -f gradle; pkill -f kotlin

# 重新跑构建命令
```

### Q3. APK 装上后白屏

按优先级排查：

1. **两处 URL 是否一致**：`tauri.conf.json` 的 `app.windows[0].url` 和 `MainActivity.kt` 的 `HOME_URL` **必须完全一致**（包括 `https://` / 末尾 `/` / 大小写）。两处不一致是最常见的白屏原因。
2. **是否忘记重新编译**：改了 `tauri.conf.json` 后必须重新跑 `tauri android build`，因为 URL 是编译期写入 `.so` 的。
3. **是否清理了旧 jniLibs**：如果之前编译过，删掉 `src-tauri/gen/android/app/src/main/jniLibs/` 再重新编译，否则可能用旧的 .so。
4. **站点是否 HTTPS 可访问**：系统 WebView 默认拒绝 http 明文。如果一定要加载 http 站点，修改 `AndroidManifest.xml` 的 `usesCleartextTraffic` 为 `"true"`（debug 构建已默认开启）。
5. **是否被 wry 的 client 覆盖**：如果你修改了 `onWebViewCreate`，确保 `installCustomClients` 是用 `webView.post { }` 推迟调用的，不能直接同步调用。详见上文「wry 初始化时序」章节。
6. **远程调试看 JS 报错**：用 `chrome://inspect`（Chrome 浏览器地址栏）连接 App 的 WebView，看 console 有没有 JS 异常。

### Q4. 剪贴板检测不生效

- 仅 App **冷启动** / **从后台切回前台** 时检测，运行中复制不会触发
- 剪贴板必须包含 `http://` 或 `https://` 开头的完整 URL
- URL 必须包含你 `HOME_URL` 的 host（例如 `HOME_URL=https://my-reader.example.com` 则剪贴板 URL 必须包含 `my-reader.example.com`）
- 同一链接本次进程只弹一次，要再弹必须完全退出 App（系统任务管理器划掉）后重启

### Q5. 文件下载失败

- API ≤ 28 需要存储权限，App 会在首次下载时弹权限请求
- API 29+ 走系统 DownloadManager 公共 Download 目录，无需权限
- 下载进度看通知栏

### Q6. 想让 App 支持横屏

修改 `AndroidManifest.xml` 里 `MainActivity` 的 `android:screenOrientation="portrait"`，改为 `"unspecified"` 或 `"fullSensor"`。

### Q7. 想加回 x86 / x86_64（模拟器支持）

修改两处：

1. `build.gradle.kts` 的 `abiFilters`：
```kotlin
abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
```

2. 安装额外两个 Rust target：
```bash
rustup target add x86_64-linux-android i686-linux-android
```

3. 编译命令加 target：
```bash
npm run tauri android build -- --apk --target aarch64 armv7 x86_64 i686 --split-per-abi --ci
```

### Q8. 想上架 Google Play

需要 AAB 格式而非 APK：

```bash
npm run tauri android build -- --aab --target aarch64 armv7 --ci
```

输出在 `app/build/outputs/bundle/universalRelease/app-universal-release.aab`。然后还需要 Google Play App Signing（上传你的签名密钥到 Google 托管）。

---

## 升级 Tauri / 依赖

```bash
# 升级 npm 包
npm update @tauri-apps/cli

# 升级 Cargo 包
cd src-tauri && cargo update && cd ..

# 重新初始化 Android 工程（仅当 Tauri 大版本升级时）
rm -rf src-tauri/gen/android
npm run tauri android init
# ⚠️ 重新配置 build.gradle.kts、AndroidManifest.xml、MainActivity.kt
# （或者把现有修改 patch 上去，建议保留 git 历史便于对比）
```

---

## 开发协议

MIT License — 随便用，作者不承担任何责任。

## 致谢

- [Tauri](https://tauri.app) — 极简跨平台桌面/移动应用框架
- [wry](https://github.com/tauri-apps/wry) — Tauri 的 WebView 抽象层
- [Readest](https://github.com/readest/readest) — 灵感来源（本项目用于把 Readest 网页版封装成 Android App）

---

**祝阅读愉快 📖**
