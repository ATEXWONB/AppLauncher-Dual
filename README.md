# 直装分身 (AppLauncher Dual)

一个基于 VirtualApp 的「单分身」启动器：**不用打包目标 APK**，直接把已安装的游戏/应用克隆进沙箱里跑，可选开启 Xposed / LSPosed 模块，并可给目标进程注入自定义原生库（`.so`）。

- 应用名：直装分身
- 包名：`com.applauncher.dual`
- 作者：**BY: ATEXWONB**

> 本项目仅供学习与技术研究使用。请勿用于绕过付费、外挂、盗号等任何违法用途。

## 特性

| 能力 | 说明 |
| --- | --- |
| 免打包分身 | 直接以 guest 形式启动已安装的 APK，原包不动、不重签 |
| Xposed | 在 guest 进程内注入 `LspVector` 载荷，兼容 legacy Xposed API 与 modern 101/102（LSPosed 模块） |
| SO 注入 | 支持内置 `libmcmod.so` 或用户自选 `.so`，在 guest 进程 `System.load` 加载 |
| 架构校验 | 加载前读 ELF `e_machine`，架构不匹配自动跳过并写日志，避免全架构包崩溃 |
| 数据目录 | 默认走沙箱外部目录 `Android/data/com.applauncher.dual/...`（Minecraft 等可正常读写存档）；另有「媒体目录」开关，落到文件管理器可见的 `Android/media/...` |

### 关于数据目录（重要）

两条外部路径在 Android 11+ 的 FUSE 语义下并不等价：

- `Android/data/com.applauncher.dual/files/Android_va/0/Android/data/<guestPkg>/` —— 绕过 MediaProvider FUSE，LevelDB 的 `fcntl` 锁、`fsync`、`O_DIRECT` 语义正常，**Minecraft 能进世界**。
  - 缺点：普通文件管理器看不到该目录。
- `Android/media/com.applauncher.dual/...` —— 文件管理器可见，但走 MediaProvider FUSE，LevelDB 打开会报 `IO error: Error: generic: Invalid argument (22)`，**Minecraft 卡在加载世界**。

所以默认值是前者；主界面「数据目录」按钮可以切换并查看说明。

## 目录结构

```
app/                      主模块（启动器 UI、沙箱管理）
app/sdk/lib-release.aar   沙箱核心（VirtualApp 定制，构建前需打补丁，见下）
app/src/main/assets/      npatch 载荷（loader.bin / metaloader.dex / libnpatch-*.so）、内置 libmcmod.so
app/src/main/jni/         各架构原生库
hooklib/  xposedcompat/  lib/   VA 相关支撑模块
tools/patch/              AAR 字节码补丁工具（见 scripts/patch_aar.sh）
scripts/                  构建脚本
```

## 构建

环境：JDK 17、Gradle 7.3.3、Android SDK（compileSdk 见 `app/build.gradle`）、Android NDK（jni 里的原生库需预编译，仓库已带成品）。

关键一步：**`app/sdk/lib-release.aar` 必须先打补丁**，否则 Xposed 注入不生效。补丁做四件事：

1. 在 `VClient#bindApplicationMainThread` 入口插入 `LspVector.onBindApplicationStart`；
2. 处理微信类加固进程的 hook 入口；
3. 处理 `:ext` 等多进程入口；
4. 把 xposed 占位类重命名到 `com.lody.xpstub.xposed/`，避免与真机上的框架冲突。

```bash
# 1) 打补丁
bash scripts/patch_aar.sh

# 2) 出单架构包（abi = arm64-v8a | armeabi-v7a | x86 | x86_64）
ABI_LIST=arm64-v8a MCMOD=on OUTVER=v1.0 bash scripts/build_apk.sh

# 3) 一次出全部 5 个包
bash scripts/build_all.sh
```

`MCMOD=off` 会删掉 `assets/libmcmod.so`（非 arm64 变体用）。`-Pabi=` 由 `app/build.gradle` 读取，用于 `ndk.abiFilters`。

签名：`scripts/` 里的口令/别名是按本地调试 keystore 写的，自己构建请换成你自己的 keystore。

## 发行版里的 5 个 APK

| 包 | ABI | 内置 `libmcmod.so` | 说明 |
| --- | --- | --- | --- |
| `直装分身-v1.0-arm64-v8a.apk` | arm64-v8a | ✅ ARM64 | 绝大多数手机（推荐） |
| `直装分身-v1.0-armeabi-v7a.apk` | armeabi-v7a | ✅ ARM32 | 老 32 位设备；沙箱可用，模组能加载但偏移按 arm64 反推、无实际效果；npatch 载荷缺失会自动跳过 Xposed 注入 |
| `直装分身-v1.0-x86.apk` | x86 | ✅ x86 | 模拟器；**沙箱核心库无 x86 版本，无法正常运行**，仅作编译产物保留 |
| `直装分身-v1.0-x86_64.apk` | x86_64 | ✅ x86_64 | 模拟器；同上，无 x86_64 沙箱库 |
| `直装分身-v1.0-universal-arm64-v8a,armeabi-v7a,x86,x86_64.apk` | 全架构 | ✅ ARM64（默认） | 一个包装全部 ABI，`libmcmod.so` 默认 ARM64，其他架构进程因 ELF 校验自动跳过 |

`libmcmod.so` 来自配套项目 [MCPE-ImGuiMod](https://github.com/ATEXWONB/MCPE-ImGuiMod)；四个架构的 `.so` 已随各架构 APK 打包（universal 仍为 ARM64 默认）。

**动态库加载器**：启动器的动态库注入能力参考/引用了四架构移植版 [TInjector](https://github.com/ATEXWONB/TInjector)（`tinjector` / `libtcore.so` / 四架构预编译产物）。该项目本体 © Mrack，配套 Dobby（Apache-2.0）、ELFIO（MIT）、nlohmann/json（MIT）。

## 腾讯系应用启动提醒

在应用列表选中某个应用后点「启动」，如果包名包含 `tencent`（QQ、微信、腾讯游戏等），会先弹一次风险提示：

> 此程序为腾讯的APP，启动可能会导致您的账号被封号或存在风控不可控因素。

点「仍要启动」才继续，点「取消」不启动。其它应用不受影响；启动失败后的自动重试不会重复弹窗。

## 致谢

- [VirtualApp](https://github.com/asLody/VirtualApp)（本项目沙箱基础）
- NPatch（Xposed 载荷思路）
- Dear ImGui（配套 mod 的 UI）

## 许可与第三方代码

- 沙箱内核来自 **VirtualApp**（https://github.com/asLody/VirtualApp ）及其 V14 fork（https://github.com/ServenScorpion/VirtualApp ），均为 **GPL-3.0**；`xposedcompat` 来自 LSPosed。因此本仓库整体按 **GPL-3.0** 发布，根目录 `LICENSE` 为 GPL-3.0 全文。
- **能否商用**：GPL-3.0 **允许商业使用**，但分发时必须同样以 GPL-3.0 公开完整对应源码；**闭源商用不允许**（除非另行取得 VirtualApp 商业授权，本项目无权代授）。
- 完整的逐项清单（每个组件、上游仓库、仓库内实际版本、许可、商用条件，以及 Java 依赖表）见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
