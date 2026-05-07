# 家庭相册 (Family Album)

[![Android](https://img.shields.io/badge/Android-5.0%2B-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-blue.svg)](https://kotlinlang.org/)
[![AGP](https://img.shields.io/badge/AGP-8.3.2-brightgreen.svg)](https://developer.android.com/studio)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

一款通过 **SMB 协议**浏览 NAS / 局域网服务器照片和视频的 Android 应用，支持**手机、平板** 两端，内置**流式视频边下边播**，无需下载完整文件即可流畅播放。

---

## ✨ 功能特性

### 📷 相册浏览
- 自动扫描 SMB 共享目录中的图片，按文件夹分组成相簿
- 支持 **JPG / PNG / GIF / WebP / HEIC** 等主流格式
- 三种排序方式：按名称 / 日期 / 大小
- 下拉刷新，随时同步最新照片

### 🎬 视频播放
- **ExoPlayer 流式播放**，支持大文件边下边播
- 真·随机 Seek，拖动进度条毫秒级定位（已修复旧版"傻跳"导致的 8 秒卡死 bug）
- 支持横屏全屏播放

### 📱 多端适配
- **手机**：底部导航 + 抽屉菜单，单手操作友好
- **平板**：响应式双栏布局，自动适配大屏
- **Android TV**：Leanback 框架，遥控器 D-Pad 导航，支持 Banner 展示

### 🌓 日夜间模式
- 跟随系统自动切换，也可手动设置
- 完美适配所有界面组件

### 🔧 文件管理
- 查看图片详情，支持**手势缩放**（双击放大/缩小）
- **保存到相册**、**分享**给第三方应用
- 删除、重命名、复制、移动文件
- 批量下载相簿照片到本地

### 🔐 安全存储
- SMB 密码使用 Android Jetpack Security **加密存储**（AES-256）
- 支持匿名访问，也支持用户名/密码认证

---

## 📦 下载安装

### 最新版本

| 版本 | 日期 | 说明 |
|------|------|------|
| **v1.0.0** | 2026-05-07 | 修复视频 Seek 卡死 bug，优化流式播放 |

> **APK 文件**：`main-app.apk`（已签名，可直接安装）

### 安装要求
- Android 5.0（API 21）及以上
- ARM64 或 ARMv7 架构设备
- 首次使用需配置 SMB 服务器地址

---

## 🛠️ 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **语言** | Kotlin | 1.9.23 |
| **构建工具** | Android Gradle Plugin | 8.3.2 |
| **架构** | MVVM + Hilt DI | - |
| **异步** | Kotlin Coroutines | 1.8.1 |
| **图片加载** | Glide | 4.16.0 |
| **SMB 协议** | SMBJ | 0.14.0 |
| **视频播放** | Media3 ExoPlayer | 1.3.1 |
| **TV 框架** | Android Leanback | 1.0.0 |
| **手势缩放** | PhotoView | 2.3.0 |
| **加密存储** | Jetpack Security | 1.1.0-alpha06 |

### 系统要求
- **minSdk**：21（Android 5.0）
- **targetSdk**：30（Android 11，避免高版本额外权限审查）
- **compileSdk**：34
- **架构**：`arm64-v8a` / `armeabi-v7a`

---

## 🚀 从源码构建

### 前置条件
- JDK 17+
- Android Studio Hedgehog 或更高版本
- 已配置 `ANDROID_HOME` 环境变量

### 构建步骤

```bash
# 1. 克隆项目
git clone https://github.com/jiajin20/SmbPhotoLibrary.git
cd SmbPhotoLibrary

# 2. 配置本地 SDK 路径（如需要）
# 编辑 local.properties
# sdk.dir=/path/to/your/android-sdk

# 3. 构建 Debug 包
./gradlew.bat assembleDebug

# 4. 构建 Release 包（需配置签名）
# 将 release.jks 放置于项目根目录，或修改 app/build.gradle.kts 中的签名配置
./gradlew.bat assembleRelease
```

构建完成后，APK 位于：
- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`

---

## 🖥️ SMB 服务器配置指南

### 支持的文件服务器
- **Windows 文件共享（SMB）**：Windows 自带，开启"网络共享"即可
- **NAS**：群晖 DSM、威联通 QNAP、铁威马 TerraMaster 等
- **Linux**：Samba 服务
- **macOS**：文件共享（SMB）

### 配置步骤

1. 打开应用 → 点击左上角菜单 → **管理服务器**
2. 点击 **添加新服务器**，填写：
   - **服务器名称**：随意（如"我家 NAS"）
   - **IP 地址**：如 `192.168.1.100`
   - **共享目录**：如 `photos`（不含 `\\server\` 前缀）
   - **用户名/密码**：匿名访问可留空
   - **根路径**：可选，如 `2024/旅行`（子目录浏览）
3. 点击 **连接**，等待加载完成

### 常见 SMB 版本兼容

| 服务器类型 | SMB 版本 | 兼容性 |
|-----------|----------|--------|
| Windows 10/11 | SMB2/SMB3 | ✅ 完全支持 |
| 群晖 DSM 7.x | SMB2/SMB3 | ✅ 完全支持 |
| 威联通 QNAP | SMB2/SMB3 | ✅ 完全支持 |
| macOS | SMB2/SMB3 | ✅ 完全支持 |
| 老旧 NAS（仅 SMB1） | SMB1 | ❌ 不支持（不安全） |

---

## 🔒 权限说明

| 权限 | 用途 | 是否必须 |
|------|------|---------|
| `INTERNET` | 访问 SMB 服务器 | ✅ 必须 |
| `ACCESS_NETWORK_STATE` | 检测网络连接状态 | ✅ 必须 |
| `READ_EXTERNAL_STORAGE` | Android 5-12 读取本地相册（保存图片用） | ⚠️ 部分版本需要 |
| `READ_MEDIA_IMAGES` | Android 13+ 读取本地相册 | ⚠️ Android 13+ 需要 |

> 应用**不收集**任何用户数据，所有 SMB 密码仅加密存储在本地设备。

---

## 📂 项目结构

```
SmbPhotoLibrary/
├── app/
│   └── src/main/
│       ├── java/com/example/smbphoto/
│       │   ├── smb/           # SMB 连接管理
│       │   ├── streaming/     # ExoPlayer 流式播放
│       │   ├── ui/            # 界面（Activity/Fragment/ViewModel）
│       │   ├── data/          # 数据层（Repository/Model）
│       │   ├── di/            # Hilt 依赖注入
│       │   └── util/          # 工具类
│       └── res/               # 资源文件
├── gradle/
├── build.gradle.kts           # 顶级构建配置
└── settings.gradle.kts
```

---

## 🐛 已知问题与修复记录

### v1.0.0（2026-05-07）
- ✅ **修复**：视频拖动进度条到末尾（325MB 处）导致的 8 秒假死 bug
  - 根因：旧版使用 `while(read())` 循环"傻跳"大量数据
  - 修复：启用 SMBJ 真·Seek（`File.read(buffer, offset)`），O(1) 定位
- ✅ **修复**：缓存范围判断错误（忽略 `cacheStartPos`）
- ✅ **优化**：日夜间模式切换稳定性

---

## 📄 开源协议

本项目采用 **MIT 协议** 开源。

---

## 💡 致谢

- [SMBJ](https://github.com/hierynomus/smbj) - Java SMB 客户端库
- [Glide](https://github.com/bumptech/glide) - 图片加载框架
- [Media3 ExoPlayer](https://developer.android.com/media/media3) - 流式视频播放
- [PhotoView](https://github.com/chrisbanes/PhotoView) - 手势缩放控件

---

## 📬 联系与反馈

如有问题或建议，欢迎提交 [Issue](https://github.com/jiajin20/SmbPhotoLibrary/issues) 或 Pull Request！

---

> 📱 让家庭照片触手可及 —— 随时随地浏览你的美好回忆。
