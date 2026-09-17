## 这个 PR 做了什么

<!-- 一两句话。如果修的是 bug，请写清根因（哪个文件、什么条件触发）。 -->

## 检查清单

- [ ] **两条线的构建都跑过**：根目录 `./gradlew clean build`（JDK 25）与
      `forge-1.8.9` 下的 `./gradlew clean build`（JDK 8），**自检全绿**
      （CI 用的是这两条命令，失败即构建失败）
- [ ] 改过 `src/shared/java` 的话，确认没有用 Java 9+ 的语法或 API
      （`record`、文本块、`List.of`、switch 表达式、`String.isBlank`、`Files.readString`…），
      见 `RELEASING.md §10.1`
- [ ] 修 bug / 加功能时，**在 `tools/VerifyCore.java` 补了用例**（先复现再修）
- [ ] 改过 `forge-1.8.9/.../asm/` 时，`verifyCoremod` 通过（`check` 会自动带上）
- [ ] 按 `RELEASING.md §1` 的规则确定了版本号；需要发布时已更新 `gradle.properties`
- [ ] 更新了 `CHANGELOG.md`（纯文档改动可不占版本号，见 `RELEASING.md §1`）
- [ ] 改过 **mod id / 显示名 / 配置文件名 / 日志前缀** 的话，两条线的元数据与四份文档已同步
      （自检有两条门禁盯着：`metadataSelfConsistency()` 与日志前缀一致性，见 `RELEASING.md` §8、§10.5）

## 关于「没有自动化覆盖」的部分

如果改动涉及以下任一位置，请在下面说明**你人工验证了什么**（自检覆盖不到它们）：

- Fabric 装配面：`HxTranslateClient.java`、`chat/GameClient.java`、`chat/GameFeedback.java`、
  `command/TranslateCommand.java` —— 依赖 Minecraft 类，靠编译期报错 + 代码审查
- Forge 装配面：`HxTranslateForge.java`、`ForgeClient.java`、`ForgeFeedback.java`、
  `ForgeChatCommand.java` —— 同上
- 核心插件的**游戏内**实际效果 —— 离线只能验字节码是否合法，跑不跑得通要进游戏试
  （打一句中文，看有没有一秒后以英文发出）
- `config` 里的 `incomingSystemPrompt` / `outgoingSystemPrompt` —— 提示词的实际翻译效果需要真实 API Key
- **单人闸门**（`isSingleplayer()` 的两处装配实现）—— 自检用的是假端口，所以
  `Minecraft.hasSingleplayerServer()` / `Minecraft.isSingleplayer()` 这两处**只有方法级核对**：
  请进一次**单人存档**确认默认不翻译、`/translator singleplayer on` 之后恢复，
  再进一次**多人服**确认不受影响（「对局域网开放的存档」也应当照常翻译）
- 换 `minecraft_version` 时 —— 请对照 `RELEASING.md §9` 的清单逐条确认

<!-- 例：改了注册面 → 在游戏里启动一次，确认 F6 能开关、收到的英文会翻译 -->

## 关联

<!-- 关联的 issue 号，例如 Closes #12；没有就删掉这一节。 -->
