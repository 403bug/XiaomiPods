# XiaomiPods for ColorOS

在 ColorOS 系统蓝牙详情页里直接控制小米 / Redmi 蓝牙耳机，不安装、不启动小米耳机 App。

[![Release](https://img.shields.io/github/v/release/403bug/XiaomiPods?display_name=tag&sort=semver)](https://github.com/403bug/XiaomiPods/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#)
[![LSPosed API 102](https://img.shields.io/badge/LSPosed%20API-102-6C5CE7)](#)

[功能](#功能) · [安装](#安装) · [兼容性](#已实测环境) · [构建](#构建) · [协议](#协议摘要) · [故障排查](#故障排查) · [English](#english)

这是一个 LSPosed / libxposed API 102 模块。它把控制面板注入
`com.oplus.wirelesssettings`，在系统设置进程内直接通过经典蓝牙 SPP 和耳机通信。
可以把它看作 [OppoPods](https://github.com/1812z/OppoPods) 的反向实践：不是把 OPPO
耳机接进小米手机，而是把小米耳机接进 ColorOS 原生蓝牙页面。

> [!WARNING]
> 当前是单机型、单系统版本的实验性实现，不是通用的小米耳机驱动。
> 刷机或系统升级前请先确认版本兼容性，使用风险自负。

## 下载

从 [Releases](https://github.com/403bug/XiaomiPods/releases/latest) 下载最新 APK：

```text
XiaomiPods-for-ColorOS-v0.1.0.apk
```

安装前需要：Android 12+、ColorOS 16（实测）、已安装并启用 LSPosed。

## 功能

在系统原生 `PreferenceScreen` 中注入「耳机功能」分类：

- ✅ 读取设备名、VID / PID、固件版本
- ✅ 读取左耳、右耳、充电盒电量
- ✅ 分别设置左右耳的单击、双击、三击、长按动作
- ✅ 切换音效模式（默认 / 人声增强 / 高音增强）
- ✅ 读取和切换双设备连接
- ✅ 页面每 15 秒刷新电量，设置写入后立即更新界面
- ⛔ 暂未实现：ANC 档位、固件更新、查找耳机、恢复出厂

协议实现直接运行在系统设置进程内，复用宿主的蓝牙权限，因此模块 APK 本身不申请
`BLUETOOTH_CONNECT` 等附近设备权限。

## 已实测环境

| 项目 | 值 |
|---|---|
| 手机 | OnePlus PJD110 |
| 系统 | ColorOS 16.0.0.201(CN01)，Android 16 |
| 宿主 | `com.oplus.wirelesssettings` 16.6.0 |
| 耳机 | 小米开放式耳机 |
| 耳机标识 | VID `0x2717` / PID `0x507F` |
| 耳机固件 | 1.0.4.7 |
| 框架 | LSPosed，libxposed API 102 |

不同 ColorOS、WirelessSettings 版本或耳机型号可能使用不同的混淆类、配置 ID
和可用功能，不能仅凭设备名假定完全兼容。

## 工作原理

```text
ColorOS 设置
└── com.oplus.wirelesssettings
    └── 蓝牙详情页 Fragment: h2.F
        └── LSPosed Hook（模块注入「耳机功能」）
            └── XiaomiPodsClient
                └── 经典蓝牙 SPP / JieLi RCSP
                    └── 小米 / Redmi 耳机
```

模块 APK 只负责提供 Hook 入口和 UI，真正的蓝牙通信在宿主设置进程内完成。

## 安装

1. 从 Releases 下载并安装 APK。
2. 在 LSPosed Manager 中启用模块。
3. 作用域只勾选「无线设置」`com.oplus.wirelesssettings`。
4. 首次启用后重启一次，或强制停止宿主进程。
5. 连接小米耳机，打开 设置 → 蓝牙 → 已配对设备 → 耳机功能。

也可以用 adb 完成部分步骤：

```sh
adb install -r XiaomiPods-for-ColorOS-v0.1.0.apk
adb shell am force-stop com.oplus.wirelesssettings
```

### 小米耳机 App 冲突

小米耳机的 SPP 控制通道同一时间通常只接受一个客户端。如果已安装
`com.mi.earphone`，它可能抢占通道，导致面板显示「连接失败」或
「控制通道被占用」。

建议停用小米耳机 App：

```sh
adb shell pm disable-user --user 0 com.mi.earphone
```

需要恢复时：

```sh
adb shell pm enable com.mi.earphone
```

仅强制停止 App 可能只是暂时释放通道；如果它被系统重新拉起，应选择停用或卸载。
使用本模块并不要求安装小米耳机 App。

## 作用域

**只需要 `com.oplus.wirelesssettings`，不需要 `com.oplus.melody`。**

当前实测的调用链是：

```text
设置 → 蓝牙 → 已配对设备 → 蓝牙详情页
宿主进程: com.oplus.wirelesssettings
页面 Activity: com.oplus.wirelesssettings/.SubSettingsExt
目标 Fragment: h2.F
```

`com.oplus.melody` 是 ColorOS 的耳机系统组件，没有可供本模块注入的独立 App 界面。
实际的蓝牙详情页及其 Fragment 生命周期都位于 `com.oplus.wirelesssettings`。
因此：

- `META-INF/xposed/scope.list` 只写 `com.oplus.wirelesssettings`
- `XiaomiPodsModule` 也只对 `com.oplus.wirelesssettings` 安装 Hook
- 把 `com.oplus.melody` 加进作用域不会让面板出现，只会扩大不必要的 Hook 范围

> `h2.F` 是当前版本的运行时混淆名。反编译工具可能把它显示为
> `h2.C0775F`；Hook 使用的是运行时 DEX 名称 `h2.F`。

## 已知限制

- Hook 依赖 `WirelessSettings` 的内部混淆类；系统升级后 `h2.F` 可能改名或改变生命周期。
- 蓝牙详情页关闭时会主动断开 SPP，这是当前实现的设计；重新打开页面会重新连接。
- 同一时间只能有一个控制客户端占用 SPP。面板打开时，小米耳机 App 无法同时控制耳机。
- 当前只验证了上表中的一台手机、一个系统版本和一款耳机。
- 当前没有实现固件更新、恢复出厂、查找耳机和 ANC 档位切换，也没有发送这些危险命令。
- 设备识别目前依据设备名中的 Xiaomi / Redmi / 小米 / 红米标记和蓝牙音频设备类型，
  再通过 VID 校验。其它型号的功能集合可能不同。

## 故障排查

**页面没有「耳机功能」**

- 确认作用域只有/包含 `com.oplus.wirelesssettings`
- 确认模块对当前宿主进程已生效
- 强制停止 `com.oplus.wirelesssettings` 后重新打开蓝牙详情页
- 检查当前 `WirelessSettings` 版本是否仍使用 `h2.F`

**显示「连接失败」或「控制通道被占用」**

- 停用 `com.mi.earphone`
- 断开并重新连接耳机
- 关闭其它可能连接该耳机的手机或电脑

**修改后没有回读**

- 退出蓝牙详情页再进入，确认写入值
- 若只更新了界面但设备未变，检查 `0xF2` 写入响应和耳机固件兼容性

抓取日志：

```sh
adb logcat -s XiaomiPods
```

## 构建

要求：

| 依赖 | 版本 |
|---|---|
| JDK | 17+ |
| Android SDK Platform | 36 |
| Gradle Wrapper | 9.7.1（仓库已包含） |
| Android Gradle Plugin | 9.1.1 |

设置 `JAVA_HOME` 后构建：

```sh
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
./gradlew assembleRelease
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

`libxposed:api:102.0.0` 是 `compileOnly` 依赖，不会被打包进 APK。

### 发布签名

公开分发的 APK 使用项目 release keystore 签名，不使用 debug 证书。签名配置从仓库根目录
的 `keystore.properties` 读取，该文件和密钥都不进入版本库：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=xiaomipods
keyPassword=...
```

没有 `keystore.properties` 时，`assembleRelease` 会回退到 debug 签名，只适合本地测试。
正式发布应始终使用同一把 release key，否则用户无法覆盖安装升级。

### 发布流程

推送 `v*` 标签会触发 GitHub Actions，自动构建签名 APK、生成 SHA-256 校验文件并创建
GitHub Release：

```sh
git tag v0.1.0
git push origin v0.1.0
```

仓库需要配置以下 Actions secrets：

| Secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | release keystore 的 Base64 编码 |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥密码 |

## 项目结构

```text
app/src/main/java/io/github/xiaomipods/coloros/
├── XiaomiPodsModule.java      LSPosed 模块入口
├── WirelessSettingsHook.java  宿主 Fragment 生命周期 Hook
├── SettingsPanel.java         原生 Preference 面板注入
├── XiaomiPodsClient.java      SPP / RCSP 客户端
├── XiaomiAuth.java            小米 / JieLi 认证变换
├── RcspPacket.java            RCSP 帧编解码
└── TargetDevice.java          小米耳机识别
```

## 协议摘要

实现基于小米 / JieLi 的 RCSP 协议：

| 项目 | 值 |
|---|---|
| SPP UUID | `0000FD2D-0000-1000-8000-00805F9B34FB` |
| 帧 | `FE DC BA | flags | opcode | length(BE) | body | EF` |
| 命令 flags | `0xC4` |
| 响应 flags | `0x04` |
| 认证 | `0x50` challenge/response，随后 `0x51` 确认 |
| 读取配置 | `0xF3` |
| 写入配置 | `0xF2` |
| 手势配置 | ID 2 |
| 双设备连接 | ID 4 |
| 音效模式 | ID 7 |

电量从 Target Info（`0x02`，参数 `FF FF FF FF`）的 type 7 读取。
响应包序号位于 body 第 1 字节，不是第 0 字节。

认证变换和协议字段调研来自
[redmi-buds-linux](https://github.com/creeperkafasi/redmi-buds-linux)，其许可见
[NOTICE](NOTICE)。本仓库不包含厂商 APK、固件、厂商原生库或耳机图片资源。

## 参与贡献

- 提交 Issue 时请附上手机型号、ColorOS 版本、WirelessSettings 版本、耳机型号和
  `adb logcat -s XiaomiPods` 日志
- 提交 PR 前请先运行 `./gradlew assembleDebug`
- 公开日志前请移除蓝牙 MAC 地址等个人信息

## 致谢

- [redmi-buds-linux](https://github.com/creeperkafasi/redmi-buds-linux)：RCSP 协议、
  认证变换与配置字段调研
- [OppoPods](https://github.com/1812z/OppoPods)：ColorOS 原生蓝牙面板的设计参考
- [libxposed API](https://github.com/libxposed/api)：模块 API

## 免责声明

本项目是非官方互操作项目，与 Xiaomi、Redmi、OPPO、OnePlus、ColorOS、
LSPosed 及其关联公司无关联，也未获其授权或认可。修改耳机配置可能导致设置异常；
必要时可通过官方 App 或重新配对恢复。使用风险自负。

## 许可证

本项目源代码使用 [MIT License](LICENSE)。第三方归属与许可全文见 [NOTICE](NOTICE)。

## English

An experimental LSPosed module that adds Xiaomi / Redmi earbud controls to the
native ColorOS Bluetooth settings page. It talks to the earbuds over Bluetooth
SPP from inside `com.oplus.wirelesssettings`, so no nearby-device permission or
separate earbud app is required.

Tested only on OnePlus PJD110 / ColorOS 16.0.0.201 / Xiaomi OpenWear earbuds.
Download the latest APK from [Releases](https://github.com/403bug/XiaomiPods/releases/latest).
Licensed under MIT.
