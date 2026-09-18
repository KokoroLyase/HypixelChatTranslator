# 参与开发（CONTRIBUTING）

这是一个**纯客户端**的 Minecraft 聊天翻译模组：把**任何英文服务器**的聊天译成中文，把你打的中文译成
英文再发出去（不限于 Hypixel）。欢迎 issue 与 PR，但本仓库对「什么算改完」有比较明确的要求 —— 请先花两分钟读完这一页，
它只做「指路」，具体规则都在别的文档里（不重复一遍，免得两边说法不一致）。

## 仓库里有两条线（v2.3.0 起，v3.0.0 起改了产物名与命令）

| 线 | 构建目录 | MC / 加载器 | 工具链 | 产物 |
| --- | --- | --- | --- | --- |
| Fabric | 仓库根目录 | 26.3 / Fabric | JDK 25 + Gradle 9 + Loom | `Server-Chat-Translator_<版本>_mc26.3-fabric.jar` |
| Forge | `forge-1.8.9/` | 1.8.9 / Forge | **JDK 8** + Gradle 2.14.1 + ForgeGradle 2.1 | `Server-Chat-Translator_<版本>_mc1.8.9-forge.jar` |

两条线**编译同一份 `src/shared/java`**，只有装配层不同（Fabric 在 `src/main/java`，
Forge 在 `forge-1.8.9/src/main/java`）。这意味着一条硬约束：**共享层只能用 Java 8 的语法与 API**。
改共享层之前请务必读 [RELEASING.md](RELEASING.md) §10。

## 提 issue 之前

- 先确认**用的是最新版**，并说清你玩的是哪条线（26.3 / Fabric 还是 1.8.9 / Forge），
  以及模组是哪个版本（`/translator status` 里能看到）；
- 安装、配置、命令、以及一批常见症状都写在 [README](README.md) 里，尤其是 FAQ 那一节；
- 报 bug 请用 [Bug 模板](.github/ISSUE_TEMPLATE/bug_report.yml)，它会问你要 `status` 与 `debug on`
  的输出 —— 那两样基本能直接定位到代码里的哪个分支；
- **1.8.9 线是核心插件**：如果「打中文没被翻译」而其它功能正常，多半是字节码注入没生效，
  日志里会有一行 `[server_chat_translator] EntityPlayerSP 字节码注入失败…`，请把它一并贴上；
- **不要把 API Key 贴进 issue**（配置文件里是明文），细节见 [SECURITY.md](.github/SECURITY.md)。

## 改代码时

| 你要做的事 | 看哪份文档 |
| --- | --- |
| 版本号怎么涨、什么时候发 Release、文件名与 tag 怎么起 | [RELEASING.md](RELEASING.md) §1–§3 |
| 两条线怎么并行、共享层能用什么不能用什么 | [RELEASING.md](RELEASING.md) §10 |
| 换 Minecraft 版本（最容易漏步骤的事） | [RELEASING.md](RELEASING.md) §9 的清单 |
| 配置迁移、绝不覆盖用户资产的要求 | [RELEASING.md](RELEASING.md) §5 |
| 日志用哪个出口、自检类路径为什么收紧 | [RELEASING.md](RELEASING.md) §8 |
| 提交信息格式 | [RELEASING.md](RELEASING.md) §7 |

四条最容易踩的：

1. **先补用例再修 bug。** `tools/VerifyCore.java` 是离线自检，已接进**两个**构建：
   两条线的 `build` 都会顺带跑完（CI 用的同一条命令），失败即构建失败；
   **两条线跑的是同一份断言**，所以新用例只需要写一次、两个版本同时被保护；
2. **决策逻辑不要写进依赖 Minecraft 的类里。** 纯逻辑放在共享层的 `util/`、`core/`、`config/`，
   或实现 `chat/ChatClientPort` / `chat/FeedbackPort` 端口 —— 两条线的自检类路径都**刻意剔除了
   Minecraft 与各自的加载器**，在那里写游戏 API 会在构建时直接报错（这是有意的，见 `RELEASING.md` §8）；
3. **别在共享层用 Java 9+ 的语法或 API**（`record`、文本块、`List.of`、switch 表达式、
   `String.isBlank`、`Files.readString`…）。Fabric 那条线用 JDK 25 编译，**不会报错**；
   只有 Forge 构建用 JDK 8 编译共享层时才会炸。所以改完共享层，**两个构建都要跑一遍**；
4. **自检覆盖不到的地方要说明你人工验证了什么**：`GameClient` / `HxTranslateClient`（Fabric 装配）、
   `ForgeClient` / `HxTranslateForge`（Forge 装配）、核心插件的游戏内效果、提示词的实际翻译效果。
   PR 模板里有这一栏。

## 本地构建

```bash
# Fabric 线（JDK 25）
JAVA_HOME=/path/to/jdk-25 ./gradlew clean build   # 构建 + 自动跑自检
JAVA_HOME=/path/to/jdk-25 ./gradlew verifyCore    # 只跑自检（几秒）

# Forge 1.8.9 线（JDK 8；Gradle 版本由 wrapper 固定）
cd forge-1.8.9
JAVA_HOME=/path/to/jdk-8 ./gradlew clean build    # 构建 + 自检 + 核心插件验证
```

> **Windows**：上面是 POSIX shell 写法。cmd / PowerShell 里换成
> `set "JAVA_HOME=C:\path\to\jdk-25"` / `$env:JAVA_HOME = 'C:\path\to\jdk-25'`，
> 构建命令用 `gradlew.bat clean build`。两条线的产物与 Linux **一致** ——
> 源码编码与资源过滤编码都由构建脚本显式钉死，不靠平台默认值（v3.0.5 / v3.0.6）。

产物分别在 `build/libs/Server-Chat-Translator_<版本>_mc26.3-fabric.jar`
与 `forge-1.8.9/build/libs/Server-Chat-Translator_<版本>_mc1.8.9-forge.jar`。

## 许可

提交即表示同意你的贡献按本仓库的 [MIT 许可](LICENSE) 发布。
