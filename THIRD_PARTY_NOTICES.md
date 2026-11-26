# 第三方代码与许可说明（THIRD-PARTY NOTICES）

「直装分身」是在 VirtualApp 基础上做的定制启动器，**分身/沙箱这一核心能力不是本项目原创**，来自下列开源项目。本表列出每个组件的上游来源、仓库内实际版本、许可与商用条件；升级依赖请按本表版本对齐。

## 一、核心（决定整体许可的部分）

| 组件 | 上游仓库 | 仓库内版本 / 标识 | 许可 | 可否商用 |
| --- | --- | --- | --- | --- |
| VirtualApp（沙箱内核） | https://github.com/asLody/VirtualApp | 通过 `app/sdk/lib-release.aar` 引入（定制版），配套源码模块 `lib/`、`hooklib/`、`annotation/`、`xposedcompat/` 同源 | **GPL-3.0** | ⚠️ **可以商用，但必须整体开源**：分发衍生作品时需以 GPL-3.0 公开完整对应源码、保留版权声明、不得追加限制。闭源商用**不允许** |
| VirtualApp14 fork | https://github.com/ServenScorpion/VirtualApp | 本仓库的 gradle 结构、`VAConfig.gradle`、`AppConfig.gradle`、`xposedcompat` 等来自该 fork（V14 系列，原 README 提到 `app-2.3.2.apk` 为 v2.3.2 分支产物） | GPL-3.0（继承 VirtualApp） | 同上，GPL-3.0 传染 |
| Xposed 兼容层（xposedcompat） | https://github.com/LSPosed/LSPosed （xposedcompat 模块） | 仓库 `xposedcompat/` 模块 | GPL-3.0 | 同上 |
| ShadowHook（内联 hook） | https://github.com/bytedance/android-inline-hook | 打包在 `app/sdk/lib-release.aar` 内（`libshadowhook.so` / `libshadowhook_64.so`） | MIT | ✅ 可商用，保留声明 |
| NPatch 载荷 | NPatch 项目（Xposed 免 root 注入框架） | `app/src/main/assets/npatch/`：`loader.bin`、`metaloader.dex`、`libnpatch-arm64.so`、`libnpatch-x86_64.so`（**无 arm32/x86 版**，缺失载荷时启动器自动跳过注入） | 上游声明为 GPL-3.0（**分发前请以 NPatch 仓库 LICENSE 复核**） | 按 GPL-3.0 处理：可商用但需开源 |
| `app/libs/libtcore.so`、`libtinjector.so` | 来自上述 VA 生态（非本项目编写） | `app/libs/arm64-v8a/` | 随 VA 的 GPL-3.0 一同分发 | 同上 |

> 结论：**整个仓库按 GPL-3.0 发布**（根目录 `LICENSE` 为 GPL-3.0 全文）。GPL-3.0 允许商业使用，但要求下游同样开源；想闭源商用必须联系 VirtualApp 原作者取得另外授权（本项目无权代授）。

## 二、本项目原创部分（同样以 GPL-3.0 发布，因为与 VA 同处一个作品）

- 应用层：`app/src/main/java/com/applauncher/dual/`（`LauncherActivity`、`AppListActivity`、`LspVector`、`SoBootstrap`、数据目录重定向、SO 选择/注入、免责弹窗、关于页）
- 资源：`app/src/main/res/` 布局、图标、收款码等
- 构建/补丁工具：`tools/patch/*.java`（AAR 字节码补丁：`PatchVClient`、`PatchExtProcess`、`PatchWeChatHook`、`RenameXposedStubs`）、`scripts/*.sh`
- 内置模组 `app/src/main/assets/libmcmod.so`：来自 https://github.com/ATEXWONB/MCPE-ImGuiMod （MIT，其第三方依赖见该仓库 `THIRD_PARTY_NOTICES.md`）
- 动态库加载器（四架构 TInjector 移植版）：https://github.com/ATEXWONB/TInjector ，启动器的「SO 注入 / 动态库加载」能力参考并引用该项目。该项目中：TInjector 本体 © Mrack（原项目未声明许可，商用需作者授权）、Dobby（Apache-2.0）、ELFIO（MIT）、nlohmann/json（MIT）、本次移植与重写的 `tinjector`（Apache-2.0），逐项说明见该仓库 `THIRD_PARTY_NOTICES.md`

## 三、Java / Android 依赖（均为 Apache-2.0 等宽松许可，可商用）

| 组件 | 版本 | 许可 |
| --- | --- | --- |
| AndroidX appcompat | 1.2.0 | Apache-2.0 |
| AndroidX recyclerview | 1.1.0 | Apache-2.0 |
| AndroidX cardview | 1.0.0 | Apache-2.0 |
| AndroidX constraintlayout | 2.1.2 | Apache-2.0 |
| AndroidX percentlayout | 1.0.0 | Apache-2.0 |
| AndroidX lifecycle（runtime / extensions / common-java8） | 2.2.0 | Apache-2.0 |
| AndroidX room-runtime | 2.2.5 | Apache-2.0 |
| Material Components | 1.2.1 | Apache-2.0 |
| RxJava2 / RxAndroid | 2.2.21 / 2.1.1 | Apache-2.0 |
| Retrofit / converter-gson / converter-scalars / adapter-rxjava2 | 2.9.0 | Apache-2.0 |
| OkHttp / logging-interceptor | 3.4.1 / 4.9.0 | Apache-2.0 |
| Gson | 2.8.4 | Apache-2.0 |
| jdeferred-android-aar | 1.2.4 | Apache-2.0 |
| StringFog（stringfog / gradle-plugin） | 1.1.0 / 2.2.1 | Apache-2.0 |
| fastjson（阿里巴巴） | 1.2.59 | Apache-2.0 |
| Glide | 4.7.1 | BSD-3-Clause / MIT / Apache-2.0（Glide 自身为 BSD-3 + 部分 Apache） |
| pinyin4j | 2.5.0（`app/libs/pinyin4j-2.5.0.jar`，jar 内未附许可文件） | 上游 SourceForge `pinyin4j` 采用 BSD 类许可；**商用前请以原始发布页为准** |
| AndroidX / AGP / Gradle（构建期） | AGP 7.2.0、Gradle 7.3.3 | Apache-2.0 |

这些 Java 依赖本身是宽松许可（可商用），但因为与 VirtualApp 一起编进同一个 APK，**整个 APK 仍受 GPL-3.0 约束**。

## 四、能否商用（一句话总结）

- **个人/学习/免费分发**：可以，按 GPL-3.0 公开源码即可。
- **商业使用（收费、内置广告、企业内分发）**：GPL-3.0 不禁止，但你必须把修改后的完整源码同样以 GPL-3.0 公开；做不到就只能去拿 VirtualApp 的商业授权。
- **闭源商业产品**：不行（除非另行授权）。
- 另外，用本启动器跑《我的世界》并搭配 `libmcmod.so` 属于修改游戏客户端，**违反 Minecraft EULA**，与开源许可无关；开源不等于允许外挂分发。

## 五、许可文本位置

- GPL-3.0：根目录 `LICENSE`
- Apache-2.0 / BSD：见各依赖上游仓库；`app/sdk/lib-release.aar` 内的第三方 `.so` 以各上游为准
