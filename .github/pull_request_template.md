## 这个 PR 做了什么

<!-- 一两句话。如果修的是 bug，请写清根因（哪个文件、什么条件触发）。 -->

## 检查清单

- [ ] 本地跑过 `./gradlew clean build`，**自检全绿**（CI 用的是同一条命令，失败即构建失败）
- [ ] 修 bug / 加功能时，**在 `tools/VerifyCore.java` 补了用例**（先复现再修）
- [ ] 按 `RELEASING.md §1` 的规则确定了版本号；需要发布时已更新 `gradle.properties`
- [ ] 更新了 `CHANGELOG.md`（纯文档改动可不占版本号，见 `RELEASING.md §1`）

## 关于「没有自动化覆盖」的部分

如果改动涉及以下任一位置，请在下面说明**你人工验证了什么**（自检覆盖不到它们）：

- `chat/GameClient.java`、`HxTranslateClient.java` —— 依赖 Minecraft 类，靠编译期报错 + 代码审查
- `config/incomingSystemPrompt` / `outgoingSystemPrompt` —— 提示词的实际翻译效果需要真实 API Key
- 换 `minecraft_version` 时 —— 请对照 `RELEASING.md §9` 的清单逐条确认

<!-- 例：改了注册面 → 在游戏里启动一次，确认 F6 能开关、收到的英文会翻译 -->

## 关联

<!-- 关联的 issue 号，例如 Closes #12；没有就删掉这一节。 -->
