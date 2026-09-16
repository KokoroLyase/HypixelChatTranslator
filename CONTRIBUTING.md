# 参与开发（CONTRIBUTING）

这是一个**纯客户端**的 Minecraft Fabric 模组：把 Hypixel 的英文聊天译成中文，把你打的中文译成
英文再发出去。欢迎 issue 与 PR，但本仓库对「什么算改完」有比较明确的要求 —— 请先花两分钟读完这一页，
它只做「指路」，具体规则都在别的文档里（不重复一遍，免得两边说法不一致）。

## 提 issue 之前

- 先确认**用的是最新版**，且**模组版本与 MC 版本对应**（v2.x 只支持 MC 26.3；26.2 用 v1.1.3）；
- 安装、配置、命令、以及一批常见症状都写在 [README](README.md) 里，尤其是 FAQ 那一节；
- 报 bug 请用 [Bug 模板](.github/ISSUE_TEMPLATE/bug_report.yml)，它会问你要 `status` 与 `debug on`
  的输出 —— 那两样基本能直接定位到代码里的哪个分支；
- **不要把 API Key 贴进 issue**（配置文件里是明文），细节见 [SECURITY.md](.github/SECURITY.md)。

## 改代码时

| 你要做的事 | 看哪份文档 |
| --- | --- |
| 版本号怎么涨、什么时候发 Release、文件名怎么起 | [RELEASING.md](RELEASING.md) §1–§3 |
| 换 Minecraft 版本（最容易漏步骤的事） | [RELEASING.md](RELEASING.md) §9 的清单 |
| 配置迁移、绝不覆盖用户资产的要求 | [RELEASING.md](RELEASING.md) §5 |
| 日志用哪个出口、自检类路径为什么收紧 | [RELEASING.md](RELEASING.md) §8 |
| 提交信息格式 | [RELEASING.md](RELEASING.md) §7 |

三条最容易踩的：

1. **先补用例再修 bug。** `tools/VerifyCore.java` 是离线自检，已接进构建：
   `./gradlew clean build` 会顺带跑完（CI 用的同一条命令），失败即构建失败；
2. **决策逻辑不要写进依赖 Minecraft 的类里。** 纯逻辑放在 `util/`、`core/`、`config/`，
   或实现 `chat/ChatClientPort` / `chat/FeedbackPort` 端口 —— 自检的类路径**刻意剔除了
   Minecraft 与 Fabric**，在那里写游戏 API 会在构建时直接报错（这是有意的，见 `RELEASING.md` §8）；
3. **自检覆盖不到的地方要说明你人工验证了什么**：`GameClient`、`HxTranslateClient` 的装配、
   提示词的实际翻译效果。PR 模板里有这一栏。

## 本地构建

需要 **JDK 25**：

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew clean build   # 构建 + 自动跑自检
JAVA_HOME=/path/to/jdk-25 ./gradlew verifyCore    # 只跑自检（几秒）
```

产物在 `build/libs/hx-chat-translator-<版本>+mc26.3-fabric.jar`。

## 许可

提交即表示同意你的贡献按本仓库的 [MIT 许可](LICENSE) 发布。
