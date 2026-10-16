# 云村换源 (NetEase UNM LSPosed Module)

基于 **LSPosed API 102** 与 **QuickJS** 的网易云音乐音源替换模块。

---

## 🛠️ 核心架构与技术特性

1. **进程内执行与嵌入式引擎**：
   - 基于嵌入式 **QuickJS** 引擎执行 JavaScript 音源解析规则，无独立守护进程依赖。
2. **内存层网络拦截与协议改写**：
   - 支持 **Cronet** (`org.chromium.net.urlconnection.CronetHttpURLConnection`) 与 **OkHttp** 网络栈拦截。
   - 覆盖 `/xeapi/` (HTTP/2)、`/eapi/` 及 `/api/v6/playlist/detail`，支持透明 GZIP 编解码与 AES-128-ECB 加解密。
   - 递归改写歌曲权益字段（`st=0, cp=1, fee=0, pl=320000`）。
3. **多源解析与异常音频过滤**：
   - 内置原生解析实现（波点音乐、酷我音乐、咪咕音乐、酷狗音乐）。
   - 针对下架音频标识及试听片段进行自动校验与过滤。
4. **动态规则热更新**：
   - 支持通过 HTTP/HTTPS 订阅远程 JavaScript 规则文件（默认规则源：`https://raw.githubusercontent.com/jnshuiji/unm/refs/heads/main/rules/unm_rule.js`）。
   - 离线状态下自动使用内置规则作为默认实现。

---

## ⚡ 快速构建与安装

使用 [mise](https://mise.jdx.dev/) 或 Gradle 进行构建：

```bash
# 1. 编译 Debug APK
./gradlew assembleDebug

# 2. 安装至已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📂 项目结构

```text
app/src/main/
├── java/pochita/
│   ├── crypto/
│   │   └── EApiCrypto.kt        # AES-128-ECB 加解密与报文解析
│   ├── hook/
│   │   ├── MainHook.kt          # LSPosed API 102 入口与作用域配置
│   │   └── NetEaseHook.kt       # Cronet 与 OkHttp 拦截与响应改写
│   ├── model/
│   │   └── SongModels.kt        # 歌曲与解析结果数据模型
│   ├── source/
│   │   ├── QuickJsEngine.kt     # 嵌入式 QuickJS 执行引擎与 Native 桥接
│   │   ├── RuleManager.kt       # 规则持久化、远程同步与音源调度
│   │   └── NativeSources.kt     # 波点/酷我/咪咕/酷狗原生解析器
│   ├── ui/main/
│   │   ├── MainScreen.kt        # Compose 管理界面
│   │   └── MainScreenViewModel.kt
│   └── data/
│       └── DataRepository.kt    # 配置存储与状态流
└── resources/META-INF/xposed/
    ├── module.prop              # staticScope=true, minApi=102
    └── scope.list               # 作用域: com.netease.cloudmusic
rules/
└── unm_rule.js                  # 独立动态 JS 规则文件
```
