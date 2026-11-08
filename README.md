# 易备份（SmbBackup）

把手机上的**照片、视频、自定义文件夹**增量备份到 **SMB / NAS**（群晖、威联通、OpenWrt、
Windows 共享、各类家庭 NAS 等）的安卓应用。

- 界面：简体中文
- 兼容：Android 8.0+（API 26），targetSdk 34
- SMB 协议：SMB2 / SMB3（通过 smbj 0.13 实现，**不支持已淘汰的 SMB1**）

---

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 备份照片 | 所有相册图片（MediaStore），远端按 `照片/年/月/` 归档 |
| 备份视频 | 所有相册视频，远端按 `视频/年/月/` 归档 |
| **按图集（相册）筛选** | 图片/视频可分别勾选“只备份哪些相册”，避免把各 App 的缓存/缩略目录一起传上去；不勾选 = 备份全部 |
| **USB 直连电脑** | 手机 USB 连电脑后开“USB 网络共享”（无需 USB 调试/ADB），App 自动找电脑 IP，用同一套共享/目录设置备份到电脑共享文件夹 |
| **热点直连电脑** | 无法用 USB 网络共享的环境（如鸿蒙卓易通）：手机开热点、电脑连接手机热点，App 自动探测热点网段(445)里的电脑共享主机，也可手动填电脑 IP |
| 多目标隔离 | Wi-Fi SMB、USB、热点 各自维护登记表/内容索引，来回切换目标不会误判“已备份” |
| **内容去重** | 同一内容的文件（如一张图在多个相册的副本）在远端只保留一份，重复内容自动跳过并记录 |
| **暂停/继续/换目标** | 文件级暂停（保存剩余待办），随时“继续”；暂停后可切换到另一目标（Wi-Fi/USB）继续备份剩余文件 |
| 自定义文件夹 | 用系统目录选择器（SAF）任选文件夹，远端保持目录结构备份到 `自定义/<名称>/...` |
| 路径模式文件夹（可选） | 授予“所有文件访问”后可直接填 `/storage/emulated/0/DCIM/Camera` 这类绝对路径 |
| 增量备份 | 本地登记表 + 远端目录索引 + 内容哈希三重比对，重复运行只传新增/变化文件 |
| 保留修改时间 | 可把远端文件时间设为与源文件一致（便于 NAS 按时间整理/检索） |
| 手动/自动备份 | 前台服务 + 进度通知，可随时停止/暂停；自动备份（每天/3 天/每周）可限制“仅在 Wi-Fi” |
| **实时速度** | 上传过程中实时显示速度（主页与通知里显示 MB/s 级速率） |
| **后台保活** | 备份期间前台通知 + 运行锁；可一键请求系统“忽略电池优化”；即使被系统杀进程，重启后自动继续备份 |
| 连接测试 | SMB 设置页一键测试（USB 模式自动探测电脑并测试），错误给出中文提示 |
| 备份日志 | 每次运行记录扫描/上传/失败明细，可查看与清空 |

### 远端目录结构示例

假设共享 `backup`，远端根目录填 `手机`：

```
\\NAS\backup\手机\
├── 照片\2025\01\IMG_20250101_123456.jpg
├── 视频\2024\12\VID_20241231_180000.mp4
└── 自定义\DCIM\Camera\PANO_20230101.jpg
```

文件名中的非法字符会被替换为 `_`；同一目录下重名文件自动追加 ` (2)` 序号，
**不会覆盖**同名但不同的文件。已存在的远端文件不会被删除（本应用只增不改远端历史）。

---

## 构建

### 方式一：命令行（Windows）

前置：JDK 17+、Android SDK（platform 34 + build-tools 34.0.0）。
本机路径已在 `build.bat` / `local.properties` / `gradle.properties` 中预设，直接：

```bat
build.bat debug      :: 仅 debug
build.bat release    :: 仅 release
build.bat            :: debug + release
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`（release 在 `...\apk\release\`）。
构建过程会把依赖缓存在项目内 `.gradle-home`，不会污染系统目录。

### 已构建产物（本机已通过）

- `app\build\outputs\apk\debug\app-debug.apk` — 调试包（自动签名），可用 `adb install` 直接装。
- `app\build\outputs\apk\release\app-release.apk` — 发布包，用项目内 `smbbak.keystore`
  （口令 `smbbak2024`）签名（v1+v2）。**正式上架前请换成你自己的密钥**并修改
  `app/build.gradle` 里的 `signingConfigs`；该 keystore 已被 `.gitignore` 排除。

### 方式一之二：命令行（macOS，本机已验证）

本机工具链在 `/Users/a123456/AndroidTools`（JDK 21 / Gradle 8.9 / Android SDK platform-34 + build-tools 34）。

```bash
cd /Users/a123456/Documents/ybf
./build-mac.sh            # debug + release
./build-mac.sh debug      # 只出 debug
```

产物：`app/build/outputs/apk/{debug,release}/`，本项目另复制一份到 `dist/`。

macOS 上做的配置改动（Windows 原文件都留了 `*.win.bak`）：

- `local.properties`：`sdk.dir=/Users/a123456/AndroidTools/sdk`
- `gradle.properties`：去掉写死的 `org.gradle.java.home`（改由 `build-mac.sh` 的 `JAVA_HOME` 指定）
- `build.gradle`：仓库从 http 镜像换成 `https://dl.google.com/android/maven2/` + `mavenCentral()`
- release 签名沿用 `smbbak.keystore`（v2 签名，`apksigner verify` 通过）；release 构建若卡在 lint：
  `gradle assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease`

### 方式二：Android Studio

1. `File → Open` 选择本项目根目录；
2. 若提示缺少 Gradle wrapper，请把 Gradle 指向本机 `G:\dxhc\tools\gradle-8.9`（`Settings → Build Tools → Gradle → Use local Gradle distribution`）；
3. 同步后 `Run ▶` 安装到手机即可（debug 自动签名）。

> 仓库未包含 Gradle wrapper 二进制，因此命令行请用 `build.bat`。

---

## 使用步骤

1. **配置目标（SMB 服务器）**：主页点“SMB 服务器”：
   - 先在顶部选**连接方式**：`Wi-Fi` / `USB` / `热点` 三种；
   - 填服务器/共享/目录信息：Wi-Fi 模式填 NAS 地址；USB/热点模式**共享名/用户名/密码/远端目录填电脑共享的信息**，
     电脑 IP 留空=自动检测/探测（也可手动填）；
   - 点“测试连接”确认能通后保存。
2. **选择备份内容**：主页点“备份内容”：
   - 打开“照片 / 视频”开关（首次会请求系统媒体权限）；
   - （推荐）点“▸ 图集范围”，只勾选要备份的相册（如 Camera/相机、Screenshots/截屏、微信保存的图片等），
     未勾选的可疑缓存目录（thumbnails、cache、temp 等）就不会被备份；**全部不勾选 = 备份全部图集**；
   - 点“+ 添加文件夹”，用系统目录选择器选想备份的目录（如 DCIM、Download）；
   - （可选）授予“所有文件访问”后，用“+ 添加路径文件夹”直接输入绝对路径。
3. **立即备份 / 暂停 / 继续 / 换目标**：
   - 点“立即备份”开始（实时进度）；
   - 运行中点 **暂停** 可在当前文件处停下并保存剩余待办；点 **继续备份** 从断点续传；
   - 暂停后点 **换目标…** 可切到 Wi-Fi SMB / USB / 热点 继续备份剩余文件；
   - 点 **停止** 放弃本次任务；点 **放弃待办** 丢弃暂停队列。
4. **自动备份（可选）**：“选项”里选 每天/每 3 天/每周，并建议保持“仅在 Wi-Fi”。

首次备份会把所选内容全量上传；之后每次只上传新增或变化的文件。内容相同的文件（多副本）
只会向同一目标传一份，其余自动跳过。

> **关于升级旧版本**：新版本登记表按“传输目标”隔离并带格式升级。升级后第一次备份会自动迁移
> 旧版登记表，并对照远端“同路径同大小”二次校验——**旧版已上传的文件会被识别并跳过，不会覆盖重传**；
> 之后纯增量。文件在远端始终使用**原始文件名**（非法字符替换为 `_`、重名加 `(2)` 序号），
> 不会被打乱成哈希值；SHA-256 只用于内部内容去重判断，不上传到文件名/路径里。

### USB 直连电脑（无需 ADB）步骤

1. 用数据线把手机连上电脑（普通充电口数据线即可，**不需要**开“USB 调试/开发者选项”）；
2. 手机上打开「设置 → 网络/连接 → USB 网络共享」（不同机型叫法略不同）；
3. 电脑上把要备份到的文件夹设为共享（右键 → 属性 → 共享），并确认可写；
4. 应用里“SMB 服务器”选 **USB**，填电脑共享的 共享名/用户名/密码/远端目录，
   电脑 IP 留空自动检测（部分电脑需手动填，如 `192.168.137.1`）；
5. 测试连接通过后即可备份。Wi-Fi / USB / 热点 三者是相互独立的目标记录，可随时切换续传。

### 热点直连电脑步骤（适合鸿蒙卓易通等无法开 USB 网络共享的环境）

1. 手机端打开 **热点**（个人热点），电脑 Wi-Fi 连接手机热点；
2. 电脑上把目标文件夹设为共享（右键 → 属性 → 共享），并放行专用网络的 SMB（445 端口）；
3. 在电脑上用 `ipconfig` 查一下电脑 IP（通常为 `192.168.43.x`）——也可以不填让应用自动探测；
4. 应用里“SMB 服务器”选 **热点**，填电脑共享的 共享名/用户名/密码/远端目录，
   电脑 IP 留空 = 应用自动探测热点网段里的共享主机（或手动填写电脑 IP，更可靠）；
5. 点“测试连接”，通了即可备份。

---

## 权限说明

| 权限 | 用途 | 何时申请 |
| --- | --- | --- |
| 照片 / 视频（Android 13+：`READ_MEDIA_IMAGES/VIDEO`；更低版本：`READ_EXTERNAL_STORAGE`） | 备份相册媒体 | 打开照片/视频开关时 |
| 通知 `POST_NOTIFICATIONS` | 备份进度与结果通知 | 首次打开应用 / 开启自动备份时 |
| “忽略电池优化”（系统设置跳转，非运行时弹窗） | 后台保活：防止长备份被系统/厂商省电策略杀掉 | “选项→后台保活”一键跳转系统设置，或首次备份时的提示条 |
| “所有文件访问” `MANAGE_EXTERNAL_STORAGE`（**可选**） | 路径模式读取任意文件夹 | 只在你在“备份内容→路径模式文件夹”里点击授予时使用，普通 SAF 模式不需要 |

本应用不申请定位、通讯录等无关权限；数据只发往你自己配置的 SMB 服务器。

---

## SMB 常见问题

- **连接失败：找不到主机** — 检查 IP/主机名，手机与 NAS 需在同一局域网。
- **连接超时** — 检查 NAS 的 SMB 服务是否开启、端口 445 是否可达（有的路由器/防火墙拦截）。
- **用户名或密码错误** — 确认 NAS 账户；部分 NAS（如群晖）需在控制面板允许该用户访问 SMB。
- **共享不存在** — 共享名要和 NAS 上一致（大小写敏感），例如 `backup` 而非 `Backup`。
- **权限不足** — 该账户对该共享没有读写权限。
- **NAS 只支持 SMB1** — 老设备（约 2016 年前）无法连接；请在 NAS 上开启 SMB2/3（现代群晖、威联通默认支持）。
- **手机端网络**：请用 2.4G/5G Wi-Fi 或同网有线设备测试；公司/访客网络可能禁止 445 端口。

---

## 工程结构

```
app/src/main/java/com/ybf/smbbak/
├── MainActivity.kt           主页：状态/进度/入口
├── Notifier.kt               通知（进度、结果）
├── data/                     AppConfig / Prefs（配置持久化）
├── backup/
│   ├── BackupEngine.kt       备份引擎（增量、上传、登记）
│   ├── BackupEvents.kt       运行状态单例（UI/服务/Worker 共享）
│   ├── BackupWorker.kt       自动备份 Worker
│   ├── Scheduler.kt          周期调度（WorkManager）
│   ├── SmbSession.kt         smbj 封装（连接/目录/上传）
│   ├── FileRegistry.kt       增量登记表（JSON）
│   ├── MediaScanner.kt       照片/视频扫描（MediaStore）
│   └── FolderScanner.kt      SAF / 路径文件夹扫描
├── service/BackupService.kt  手动备份前台服务
└── ui/                       SMB设置 / 备份内容 / 选项 / 日志 页面
```

- 依赖：AGP 8.5.2 / Kotlin 1.9.24 / smbj 0.13.0（含 BouncyCastle）/ AndroidX（appcompat、material、work-runtime-ktx、documentfile）/ kotlinx-coroutines。
- 依赖仓库使用本机可访问的 HTTP 镜像（Google Maven、华为云），见根 `build.gradle`。

## 已知限制

- SMB1 不支持；SMB 服务器需开启 SMB2/3。
- Android 14 的“选择照片”部分授权只覆盖你勾选的媒体。
- 远端“已删除文件的清理（镜像删除）”未实现，以免误删——需要清理请在 NAS 端操作。
- USB 模式依赖“USB 网络共享”链路（手机给电脑当网卡或反向共享），不同手机/电脑的 IP 分配
  方式不同，若自动检测不到电脑 IP，请在 SMB 设置里手动填写（如 Windows ICS 常用 192.168.137.1）。
- 内容去重对“本版本起新上传的文件”生效：已存在于远端的历史重复不会自动清理；同一目标内
  相同内容的文件只保留第一份。
- 暂停为文件级断点：正在传的大文件会停在当前位置，其半截残留会在“继续”时自动覆盖补全。
- 自动备份为系统级周期任务，实际执行时机由系统调度决定，并非精确到分钟。

---

## 赞赏 / 支持作者

这个工具是自用的，顺手开源出来。如果它帮到了你，可以扫码请作者喝杯咖啡 ☕（微信支付，金额随意，不影响功能）：

<p align="center">
  <img src="docs/wechat-pay.png" alt="微信支付收款码" width="260">
</p>

有问题或想要的功能，也可以开 Issue 说一声。
