# 🚴 鸡翅幸哲迈进OB

**iGPSPORT / 行者 / 迈金 → Outbase 运动数据同步工具**

[![Android](https://img.shields.io/badge/Platform-Android-green)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)
[![Version](https://img.shields.io/badge/Version-v6.1.0-brightgreen)]()

一款 Android 数据迁移工具，将骑行/跑步等运动记录从三个国内平台批量同步到 Outbase 平台。

---

## 🧪 开发体验版（欢迎体验）

> 正式版主打**稳定**，仅覆盖「iGPSPORT / 行者 / 迈金 → Outbase」单向链路。
> 想体验**六平台互传**等更多新功能？欢迎体验 **鸡翅幸哲迈进OB(开发体验版)**！

| 对比项 | 正式版（本仓库） | 开发体验版 |
|:------|:---------------|:----------|
| 支持平台 | iGPSPORT / 行者 / 迈金 → Outbase | **六平台**：iGPSPORT / 行者 / 迈金 / 黑鸟单车 / 百锐腾 / Outbase |
| 同步方向 | 单向同步到 Outbase | **平台间双向互传** |
| 黑鸟单车 | 不支持 | ✅ 新增，支持下载 + 上传 |
| 百锐腾 | 不支持 | ✅ 新增平台 |
| 迈金 | 仅下载 | ✅ 下载（上传开发中） |
| 最新版本 | v6.1.0 | **v6.4.3** |

**开发体验版项目地址**：[GitHub - sports-data-sync-multiplatform（开发测试版）](https://github.com/Anathleticbicyclist/sports-data-sync-multiplatform)

> 🧪 **欢迎大家体验开发版！** 开发版功能更全、迭代更快，但**不稳定且用且珍惜**，仅供尝鲜测试，正式使用请用本仓库的正式版。遇到问题欢迎提交 Issue 反馈。

---

## ✨ 功能特性

### 支持平台

| 平台 | 登录方式 | 文件格式 | 说明 |
|------|---------|---------|------|
| **iGPSPORT** | WebView (Bearer Token) | FIT | 活动列表分页、原生FIT下载 |
| **行者** | WebView (sessionid Cookie) | GPX→FIT | GPX下载、端内格式转换（含北京时间修正） |
| **迈金/顽鹿OTM** | WebView (JWT) | FIT | 双通道下载（七牛直链+fit_content通用接口） |
| **Outbase** | WebView (sessionId) | - | CDN上传、注册接口入库 |

### 核心功能

- 🔐 **四平台 WebView 登录** — 自动提取凭证，独立存储互不影响
- 📥 **批量同步** — 支持1~1000条记录，可跳过前N条
- 🔄 **GPX→FIT 转换** — 行者专用，本地WebView内完成，含UTC→北京时间修正
- 📤 **多策略上传** — Outbase CDN h5端点 + WebView回退通道
- 📋 **详细日志** — 全过程记录，一键复制，失败原因分类
- 🎯 **单平台/全部来源选择** — 灵活控制同步范围
- 🧭 **迈金 GCJ-02→WGS84 坐标转换** — 基于开源验证方案，fit_content通道自动修正坐标偏移
- 💾 **数据来源记忆** — 重启APP自动恢复上次选择的来源
- 📂 **文件本地存储** — 同步文件自动保存至手机 Download/鸡翅幸哲迈进OB/ 目录
- 🔄 **同步记忆** — 已同步记录不再重复下载，跳过上限提升至10000条
- ⏰ **后台自动同步** — 可配置检测间隔（30秒~1小时），附后台保活指引

---

## 🛠️ 技术栈

- **语言**: Kotlin 2.2.0
- **最低SDK**: Android 8.0 (API 26)
- **目标SDK**: Android 14 (API 36)
- **构建工具**: Gradle 8.13 + AGP 8.13.0
- **网络**: OkHttp 4.12
- **协程**: Kotlinx Coroutines 1.7.3
- **UI**: Material Components

---

## 📁 项目结构

```
app/
├── build.gradle                  # 应用模块配置
└── src/main/
    ├── AndroidManifest.xml
    ├── assets/
    │   ├── bridge.html           # WebView桥页面（GPX转FIT+上传回退）
    │   ├── gpx2fit.js            # Outbase官方GPX→FIT转换库
    │   └── magene_fix.js         # 迈金GCJ-02→WGS84坐标修正（WebView内执行）
    ├── java/com/jichi/ob/
    │   ├── MainActivity.kt       # 主界面+同步调度
    │   ├── SyncEngine.kt         # 同步引擎核心逻辑
    │   ├── AutoSyncService.kt    # 后台自动同步服务
    │   ├── api/
    │   │   ├── IgpsportApi.kt    # iGPSPORT接口
    │   │   ├── XingzheApi.kt     # 行者接口
    │   │   ├── MageneApi.kt      # 迈金OTM接口（含通道标识）
    │   │   └── OutbaseApi.kt     # Outbase上传
    │   ├── model/Activity.kt     # 数据模型
    │   ├── ui/LoginWebActivity.kt# 四平台WebView登录
    │   └── util/
    │       ├── PrefsManager.kt   # 凭证存储+同步记忆
    │       └── WebBridge.kt      # WebView桥管理
    └── res/                      # 布局、配色、字符串、图标
```

---

## 🚀 快速开始

### 环境要求

- **JDK 21**（完整JDK，含javac）
- **Android SDK**: platforms;android-36 + build-tools;36.0.0
- **Gradle 8.13**（项目自带gradle wrapper）

### 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/Anathleticbicyclist/sync-igpsport-magene-onelap-xingzhe-data-to-outbase.git
cd sync-igpsport-magene-onelap-xingzhe-data-to-outbase

# 2. 生成签名密钥（首次需要）
keytool -genkey -v -keystore jichi-ob-release.keystore \
  -alias jichiob -keyalg RSA -keysize 2048 -validity 10000

# 3. 修改 app/build.gradle 中的签名密码
# storePassword '你的密码'
# keyPassword '你的密码'

# 4. 构建
./gradlew assembleRelease

# 5. 产物位置
# app/build/outputs/apk/release/app-release.apk
```

### 注意事项

- ⚠️ 工程需放在**本地磁盘**编译，网络挂载文件系统（如OSS/FUSE）不支持Gradle校验服务
- ⚠️ 首次构建需要下载依赖，建议配置阿里云镜像（已内置在settings.gradle）

---

## 📖 使用说明

1. **安装APK** 到 Android 设备
2. **登录各平台** — 点击对应平台的登录按钮，在WebView中完成登录
3. **设置同步数量** — 滑块选择1~1000条
4. **选择来源** — 单平台或全部来源（选择会被记忆，下次自动恢复）
5. **开始同步** — 点击"开始同步到Outbase"
6. **查看日志** — 实时显示同步进度和结果
7. **后台自动同步**（可选）— 在设置中开启，配置检测间隔，按指引设置后台保活

---

## 🔧 核心机制

### GPX→FIT 本地转换（行者专用）

Outbase只接受FIT格式。行者GPX经打包进assets的Outbase官方`gpx2fit.js`在WebView内本地转换，不依赖网络。

关键点：
- `gpx2fitEncoder` 是异步函数（返回Promise），桥接代码必须 `Promise.resolve().then()` 处理
- Android WebView自带DOMParser，该库浏览器分支可直接运行
- GPX为UTC时间，转换前对所有`<time>`标签+8小时，使Outbase展示为北京时间

### 迈金 GCJ-02→WGS84 坐标修正

迈金fit_content接口返回的FIT文件使用GCJ-02坐标系（国测局坐标），与WGS84存在约450米偏移。修正方案基于开源项目 [magene-fit-strava-fix](https://github.com/dwmer0308-a11y/magene-fit-strava-fix) 的验证算法，打包在 `assets/magene_fix.js`，WebView内执行。

- **七牛直链下载**：已是WGS84坐标，无需转换
- **fit_content接口下载**：自动执行GCJ-02→WGS84转换
- 转换开关在同步设置中，默认关闭

### Outbase 上传（h5端点 + 回退）

- **主通道**: OkHttp直连 `resource/h5/upload`，浏览器风格请求头，无需鉴权
- **回退通道**: WebView内执行fetch，与官方网页请求环境完全一致

### FIT下载双通道（迈金专用）

- **通道①**: 七牛直链 — 老格式fileKey记录可用
- **通道②**: `fit_content` 接口 — 官方网页端同款，全格式通用

---

## 📊 平台接口说明

### iGPSPORT
- 活动列表: `GET /web-gateway/web-analyze/activity/queryMyActivity`
- FIT下载: `GET /web-gateway/web-analyze/activity/getDownloadUrl/{rideId}`

### 行者
- 活动列表: `GET /pgworkout/?offset=N&limit=M`
- GPX下载: `GET /pgworkout/{id}/gpx`

### 迈金（顽鹿OTM）
- 登录: `POST /api/login`（MD5密码）
- 活动列表: `POST /api/otm/ride_record/list`
- FIT下载: `GET /api/otm/ride_record/analysis/fit_content/{base64(fitUrl)}`

### Outbase
- CDN上传: `POST /zeusfit/resource/h5/upload`
- 注册入库: JSON Body + Sessionid头

---

## 📋 更新日志

### v6.1.0 (2026-08-18)

1. **数据来源记忆** — 重启APP自动恢复上次选择的数据来源
2. **文件本地存储** — 同步文件自动保存至手机 `Download/鸡翅幸哲迈进OB/` 目录，设置卡片显示路径
3. **迈金坐标转换** — 新增 GCJ-02→WGS84 坐标转换开关（基于 [magene-fit-strava-fix](https://github.com/dwmer0308-a11y/magene-fit-strava-fix) 开源验证方案移植），七牛直链不动、fit_content通道自动转换
4. **同步记忆** — 已同步记录本地记账，再同步直接跳过不重复下载；跳过上限 3000→10000
5. **后台自动同步** — 新增自动同步卡片（开关+检测间隔滑块30秒~1小时+后台常驻指引），含电池优化白名单申请、各品牌自启动设置路径，运行时通知栏常驻通知显示最近检测结果

### v6.0.9

- 初始开源版本
- 四平台WebView登录+批量同步
- GPX→FIT本地转换（行者专用）
- 迈金双通道下载（七牛直链+fit_content）
- Outbase多策略上传（CDN h5 + WebView回退）

---

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源。

---

## 🙏 鸣谢

感谢以下平台为热爱运动的用户提供的数据记录与存储服务：

- **[iGPSPORT](https://www.igpsport.com/)** — 专业骑行数据平台
- **[行者](https://www.imxingzhe.com/)** — 运动记录与社区平台
- **[迈金/顽鹿OTM](https://www.magene.com/)** — 智能骑行设备与数据平台
- **[Outbase](https://outbase.cn/)** — 运动数据聚合平台

感谢以下骑友(均为骑行爱称)为软件测试提供的帮助：素甲粉、青岛AUV阿哲、清茶、萧、洪斌大哥、鸽子王腰果、rockozhao、胶州一哥大沽河河长赵铁柱、海参

感谢开源项目 [magene-fit-strava-fix](https://github.com/dwmer0308-a11y/magene-fit-strava-fix) 提供的迈金坐标修正算法参考。

---

## 📞 联系方式

如有问题或建议，欢迎提交 Issue。

---

**鸡翅幸哲迈进OB** — 让运动数据自由流动 🚴♂️
