# 发布约定（RELEASING）

本仓库的版本、命名与发布规则。改动这个仓库时按这里的约定执行。

## 1. 版本号

跟随 `gradle.properties` 里的 `mod_version`：

| 改动类型 | 版本号 | 是否发 Release |
| --- | --- | --- |
| 修 bug、改提示词、调默认值 | 末位 +1（`1.0.3` → `1.0.4`） | ✅ 发 |
| 新增功能、新增配置项 | 中位 +1（`1.0.x` → `1.1.0`） | ✅ 发 |
| 不兼容变更（改配置结构且不做迁移、换 MC 版本等） | 首位 +1（`1.x` → `2.0.0`） | ✅ 发 |
| 只改文档（README / CHANGELOG 笔误等） | 不变 | ❌ 只推 `main` |

## 2. 文件名

发布产物的文件名必须带上**游戏版本**和**模组加载器**：

```
hx-chat-translator-<mod_version>+mc<minecraft_version>-<loader>.jar
例：hx-chat-translator-1.0.3+mc26.2-fabric.jar
```

由 `build.gradle` 里的 `archiveFileName` 生成，改版本时只改 `gradle.properties`，不要手改文件名。

## 3. 发布流程

```bash
# 1) 改代码 -> 2) 本地构建与自检
./gradlew build
#    离线断言（不需要启动游戏）
javac -encoding UTF-8 -cp "build/classes/java/main:libs/*" -d build/verify tools/VerifyCore.java
java  -cp "build/classes/java/main:build/verify:libs/*" VerifyCore   # 必须 0 失败

# 3) 提交并推送 main（叠加新提交，不 force push、不改写历史）
git add -A && git commit -m "fix: ..."
git push origin main

# 4) 打 tag 并推送 —— CI 会自动构建并创建 Release
git tag -a v<mod_version> -m "Hypixel 聊天翻译 v<mod_version> (MC 26.2 / Fabric)"
git push origin v<mod_version>
```

`v*` 标签会触发 `.github/workflows/build.yml`：用 JDK 25 + Gradle 构建，
上传 jar 作为 artifact，并把 jar 附到同 tag 的 Release 上（已存在则覆盖上传）。

推 tag 之后，再补一份写给人看的 Release 说明：改了什么、为什么改、升级后要做什么。

## 4. 保留旧版

- **旧版本的 Release 与附件一律保留**，不删除、不覆盖、不改 tag 指向。
- 新版本走新 tag、新 Release；旧版说明里加一行指向 [最新版](https://github.com/KokoroLyase/HypixelChatTranslator/releases/latest) 即可。
- 涉及旧附件改名时，重新上传同内容的新名字附件再删旧名，**不要删掉整个 Release**。

## 5. 配置兼容

`config/hxtranslate.json` 是用户资产，升级时：

- 通过 `TranslatorConfig.applyMigrations()` 做**自动迁移**，并把 `configVersion` +1；
- 只补缺、只替换「仍是旧版默认值」的字段（用提示词标记判断，例如 `LEGACY_INCOMING_MARKERS`）；
- **绝不覆盖**用户自定义的提示词、术语表、命令名单和数值；
- 迁移逻辑必须是纯函数，并在 `tools/VerifyCore.java` 里有对应用例。

## 6. 测试

- `tools/VerifyCore.java` 是离线自检程序：语言判断、命令解析、DeepSeek 请求/响应、
  过滤器决策、配置迁移，全部不依赖 Minecraft。
- **每次修复真实 bug，都要把玩家反馈里的原始消息加进回归用例**，并在 CHANGELOG 里注明。
- 断言数只增不减；有新 bug 先补用例复现，再改代码。

## 7. 提交信息

- 首行：`fix:` / `feat:` / `docs:` / `ci:` + 一句话说明 + `(v1.0.3)`
- 正文写清**根因**（哪一行、什么条件触发）、**修法**、**兼容性**，中文书写。
