# APK Unifier

把各种分包装安装包容器统一合并、签名成**单个可直接安装的 APK**。

支持的输入格式：`XAPK` / `APKX` / `APKZ` / `APKM` / `APKS`，以及普通 `APK`。

合并后调用 [apksig](https://github.com/google/apksig) 做 v1/v2/v3 签名，产物可直接安装。

## 仓库结构

| 目录 | 说明 |
| --- | --- |
| [`desktop/`](desktop) | Java 桌面版（Swing GUI + 命令行），Maven 工程 |
| [`android/`](android) | Android 手机版（原生 App），Gradle 工程 |

## 下载

到 [Releases](../../releases) 页面下载第一个版本 `v1.0.0`：

- `APK-Unifier-1.0.0.apk` —— Android 安装包（Android 8.0+）
- `apkunifier_1.0.0_amd64.deb` —— Linux 桌面安装包（Debian/Ubuntu）
- `apk-unifier-1.0.0-linux-portable.tar.gz` —— Linux 免安装便携版（自带 JRE）
- `apk-unifier-1.0.0.jar` —— 跨平台轻量版（需已装 Java）

## 使用

### Android

把 `APK-Unifier-1.0.0.apk` 传到手机安装（需在系统设置中允许「安装未知应用」）。
打开后选择安装包 → 开始转换 → 完成后可直接安装或分享产物。

### 桌面版

```bash
# 图形界面（默认）
java -jar apk-unifier-1.0.0.jar

# 命令行批量处理
java -jar apk-unifier-1.0.0.jar --cli <文件或目录...> [-o 输出目录] [--no-sign]
```

## 从源码构建

### 桌面版（需要 JDK 17+ 与 Maven）

```bash
cd desktop
mvn package          # 产出 target/apk-unifier.jar
```

### Android 版（需要 JDK 17 + Android SDK 34）

```bash
cd android
# 先在项目根目录创建 local.properties，写入：sdk.dir=/path/to/android-sdk
gradle assembleRelease
```

发布签名的密钥库与口令不随仓库分发（见 `android/keystore.properties`，本地自备）。

## 工作原理

1. 按 ZIP 条目特征识别容器格式与内部 APK / OBB。
2. 解包并按安全路径还原 OBB 到 `Android/obb/<包名>/`。
3. 调用 [APKEditor](https://github.com/REAndroid/APKEditor) 合并 base 与各 split。
4. 用 `apksig` 对合并结果做 v1/v2/v3 签名。

## 第三方组件

- [APKEditor](https://github.com/REAndroid/APKEditor)（REAndroid）—— split APK 合并
- [apksig](https://github.com/google/apksig)（Google）—— APK 签名

请遵循上述组件各自的许可协议。
