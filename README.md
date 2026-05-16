# SMB 家庭相册 (SmbPhotoLibrary)

Android 家庭相册应用，通过 SMB 协议访问 NAS / Windows 共享目录，支持 HEIC、JPEG、视频等多种格式，提供时间轴（按 EXIF 拍摄时间）快速定位功能。

---

## 功能特性

| 功能 | 说明 |
|------|------|
| SMBv2/v3 连接 | 基于 SMBJ 0.14.0，支持账号密码认证与匿名连接 |
| 多格式图片 | JPEG / PNG / WEBP / GIF / BMP / **HEIC / HEIF** |
| 视频支持 | MP4 / MOV / AVI / MKV / WEBM 等，流式播放（ExoPlayer + HTTP代理） |
| 相簿分组 | 自动扫描子目录作为相簿，显示封面与数量 |
| 时间轴 | 按 **EXIF 拍摄时间**分组，无 EXIF 则回退到文件修改时间 |
| EXIF 索引缓存 | 首次加载创建索引，**数量变化时自动重建**，大幅提升二次加载速度 |
| 应用更新 | 启动时检测 Gitee Releases，弹窗提示更新 |
| 手势缩放 | PhotoView 双击 / 捏合缩放 |
| 保存本地 | 一键保存图片/视频到 Android 系统相册 |
| MVVM + Hilt | 清晰的分层架构，依赖注入 |
| Material 3 | CollapsingToolbar、SwipeRefresh、深色模式自适应 |
| TV 支持 | 适配 Android TV / JUUI 系统 |

---

## 项目结构

```
app/src/main/java/com/example/smbphoto/
├── data/
│   ├── model/
│   │   ├── SmbImageFile.kt      # 文件模型（含 takenAt EXIF 拍摄时间）
│   │   ├── PhotoAlbum.kt        # 相簿模型
│   │   ├── ServerConfig.kt      # 服务器配置
│   │   └── UiState.kt           # UI 状态封装
│   └── repository/
│       ├── SmbRepository.kt     # 仓库接口
│       └── SmbRepositoryImpl.kt # 仓库实现
├── smb/
│   ├── SmbManager.kt            # SMB 核心（含 EXIF 读取、HEIC 扫描）
│   └── ExifIndexManager.kt      # EXIF 索引缓存管理
├── util/
│   └── UpdateChecker.kt        # Gitee Releases 版本检测
├── glide/
│   ├── HeicBitmapDecoder.kt     # HEIC/HEIF 解码（ImageDecoder / BitmapFactory）
│   ├── SmbModelLoader.kt        # Glide 自定义 SMB ModelLoader
│   ├── SmbDataFetcher.kt        # SMB 数据获取器
│   ├── SmbGlideModule.kt       # Glide 模块注册
│   └── VideoThumbnailLoader.kt  # 视频缩略图提取
├── streaming/
│   ├── SmbProxyServer.kt        # HTTP 代理服务器（SMB → ExoPlayer）
│   └── StreamingVideoPlayerActivity.kt
├── ui/
│   ├── activity/
│   │   ├── MainActivity.kt      # 主界面（相簿层 + 图片层 + 时间轴）
│   │   ├── PhotoDetailActivity.kt
│   │   ├── ServerConfigActivity.kt
│   │   └── TvMainActivity.kt
│   ├── adapter/
│   │   ├── AlbumAdapter.kt
│   │   └── PhotoAdapter.kt
│   └── viewmodel/
│       └── PhotoViewModel.kt
└── di/
    └── AppModule.kt             # Hilt 依赖注入模块
```

---

## 快速开始

### 安装 APK（推荐）

直接下载 Release 页面的 `app-debug.apk` 安装即可。

### 从源码构建

**环境要求：**
- JDK 17 或 JDK 21
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`

```bash
# Windows
set JAVA_HOME=C:\path\to\jdk-17
.\gradlew.bat assembleDebug

# macOS / Linux
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

---

## SMB 服务器配置

| 字段 | 说明 |
|------|------|
| 服务器 IP | NAS 或 Windows 主机的局域网 IP |
| 共享名称 | Windows 共享文件夹名（如 `photos`） |
| 根目录路径 | 共享内的相对路径（留空表示共享根目录） |
| 用户名/密码 | 留空表示匿名连接 |

---

## HEIC / HEIF 支持说明

三层管道均已配置：

1. **文件扫描**（`SmbManager.isMediaFile()`）：已包含 `.heic` / `.heif` 扩展名
2. **数据模型**（`SmbImageFile.isImage`）：已包含 `.heic` / `.heif`
3. **Glide 解码**（`HeicBitmapDecoder`）：
   - Android 9+（API 28+）：使用 `ImageDecoder`
   - Android 8 及以下：使用 `BitmapFactory`（依赖系统 HEIC 支持）

---

## 时间轴说明

相簿内图片视图右侧显示时间轴快速定位条：

- **优先使用 EXIF 拍摄时间**（`DateTimeOriginal` 标签）
- 无 EXIF 或 EXIF 中无拍摄时间时，回退到 SMB 文件修改时间
- 按日期分组，最新在前，点击可跳转到对应位置

技术实现：通过 `androidx.exifinterface` 读取 JPEG/HEIC/WEBP 等格式的 EXIF 数据，使用 `LimitedInputStream` 限制读取量（最多 512KB）以减少网络 IO。

### EXIF 索引缓存

为提升加载速度，应用实现了 EXIF 索引缓存系统：

| 场景 | 行为 |
|------|------|
| 首次进入相簿 | 显示索引进度条，读取所有 EXIF 并保存 |
| 二次进入相簿 | 直接读取缓存，秒开 |
| 图片数量变化 | 自动检测并重建索引 |
| 删除/新增图片 | 同步更新索引缓存 |

存储位置：`SharedPreferences`（按相簿路径隔离）

---

## 自动更新

应用启动时自动检测 Gitee Releases 是否有新版本：

- **检测频率**：每 6 小时检查一次
- **版本比对**：智能解析版本号（支持 v1.0.0 / app-debug 等格式）
- **更新提示**：弹窗显示更新日志，可选择「立即更新」「稍后」「忽略此版本」
- **下载安装**：调用系统浏览器下载 APK，支持 Android 8.0+ 安装未知应用权限

---

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | SMB 网络连接 |
| `READ_EXTERNAL_STORAGE` | 读取本地存储（Android 12 以下） |
| `WRITE_EXTERNAL_STORAGE` | 保存图片到相册（Android 9 以下） |
| `READ_MEDIA_IMAGES` | 读取媒体图片（Android 13+） |

---

## 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 1.9.23 |
| Gradle | 8.6 |
| compileSdk | 34 |
| minSdk | 21 |
| SMBJ | 0.14.0 |
| Glide | 4.16.0 |
| Hilt | 2.51.1 |
| ExifInterface | 1.3.7 |
| Media3 ExoPlayer | 1.3.1 |
| PhotoView | 2.3.0 |

---

## 已知修复记录

| 版本 | 修复内容 |
|------|----------|
| v1.0.0beta | **正式版** 修复视频播放完全失效问题：HTTP chunked encoding 格式错误、SMB 文件大小读取失败（size=0）、per-chunk flush 导致数据丢失；修复应用假死卡顿（SmbConnectionPool.tryReconnect 持有锁 30 秒） |
| v1.1beta（未发布） | 修复相簿无法显示 HEIC 格式照片（`SmbManager.isMediaFile()` 漏掉 `.heic/.heif` 扩展名） |
| v1.2beta（未发布） | 修复时间轴使用文件创建时间的问题，改为优先读取 EXIF 拍摄时间（`DateTimeOriginal`） |
| v1.3beta（未发布） | 修复相簿列表加载时陷入循环的问题：扫描相簿时跳过 EXIF 读取以提升性能，EXIF 仅在进入相簿查看具体图片时才读取 |
| v1.4beta（未发布） | 新增 EXIF 索引缓存系统：首次加载创建索引，数量变化时自动重建，二次加载秒开 |
| v1.5beta（未发布） | 新增应用自动更新功能：启动时检测 Gitee Releases，弹窗提示更新 |

---

## License

MIT License © 2026 家庭相册
