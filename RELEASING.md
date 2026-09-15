# 发布约定（RELEASING）

本仓库的版本、命名与发布规则。改动这个仓库时按这里的约定执行。

## 1. 版本号

跟随 `gradle.properties` 里的 `mod_version`，格式固定为 `a.b.c` 三段数字：

| 改动类型 | 版本号 | 是否发 Release |
| --- | --- | --- |
| 修 bug、改提示词、调默认值、重构 | 末位 +1（`1.1.3` → `1.1.4`） | ✅ 发 |
| 新增功能、新增配置项 | 中位 +1，末位归零（`1.1.x` → `1.2.0`） | ✅ 发 |
| 不兼容变更（改配置结构且不做迁移、换 MC 版本等） | 首位 +1，中位与末位归零（`1.x` → `2.0.0`） | ✅ 发 |
| 只改文档（README / CHANGELOG / RELEASING 笔误等） | 不变 | ❌ 只推 `main` |

### 进位规则（末位到 9 就进位）

**末位到 `9` 之后不进位成 `a.b.10`，而是当作一次中位升级：`a.b.9` → `a.(b+1).0`。**
中位到 `9` 同理进位到首位：`a.9.9` → `(a+1).0.0`。

也就是说**每一段只占一位数字**，版本号永远是 `1.1.4` 这种紧凑写法，不会出现 `1.0.10`。

```
1.0.7 → 1.0.8 → 1.0.9 → 1.1.0 → 1.1.1 → … → 1.1.9 → 1.2.0 → … → 1.9.9 → 2.0.0
                          ↑ 不是 1.0.10
```

> 这条规则是 2026-09-15 定的：v1.0.9 之后确实误发过一次 `1.0.10`，随后按此规则更正为 `1.1.0`
> （那次改号的经过记在 CHANGELOG 的 v1.1.0 条目里）。已对外发布过的版本一律保留，见 §4。

## 2. 文件名

发布产物的文件名必须带上**游戏版本**和**模组加载器**：

```
hx-chat-translator-<mod_version>+mc<minecraft_version>-<loader>.jar
例：hx-chat-translator-1.0.3+mc26.2-fabric.jar
```

由 `build.gradle` 里的 `archiveFileName` 生成，改版本时只改 `gradle.properties`，不要手改文件名。

## 3. 发布流程

```bash
# 1) 改代码 -> 2) 本地构建与自检（build 会自动跑离线断言，失败即构建失败）
./gradlew build

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
- 它在 `build.gradle` 里是独立的 `verify` 源集，并挂在 `check` 上：
  **`./gradlew build`（CI 用的也是这条命令）会自动跑，失败即构建失败**；单独跑用 `./gradlew verifyCore`。
  所以 CI 本身就是质量门禁，不需要额外配步骤。
- **每次修复真实 bug，都要把玩家反馈里的原始消息加进回归用例**，并在 CHANGELOG 里注明。
- 断言数只增不减；有新 bug 先补用例复现，再改代码。
- 修完一轮建议做一次**反向验证**：把修复中和掉再跑一遍，确认对应用例真的会红 ——
  用例如果「怎么改都绿」，那它就没有在保护任何东西。
- `ChatTranslator` 依赖 Minecraft 类，不在这套离线自检里。改动它时只能靠代码审查 +
  保持「同一规则只有一个出口」的结构（例如出站降级全部走 `fallbackToOriginal`），
  并在 CHANGELOG 里说明「无自动化覆盖」。

## 7. 提交信息

- 首行：`fix:` / `feat:` / `docs:` / `ci:` + 一句话说明 + `(v1.0.3)`
- 正文写清**根因**（哪一行、什么条件触发）、**修法**、**兼容性**，中文书写。

## 8. 工程洁净度

这些约定是为了让后续改动不产生额外噪音，改东西时请遵守：

- **格式**：`.editorconfig` 固定了各文件类型的缩进与换行（Java 4 空格、Gradle/JSON 制表符、
  YAML 2 空格、无行尾空格、文件末尾空行）。编辑器支持 EditorConfig 时会自动生效。
- **行尾**：`.gitattributes` 把文本统一成 LF、把 `*.jar` 等标记为二进制。
  Windows 上 clone 也不会产生「只改了行尾」的假 diff。
- **不要提交生成物**：`build/`、`.gradle/`、`run/`、`logs/`、`config/hxtranslate.json`
  （含 API Key）都已在 `.gitignore` 里。跑完自检会在根目录生成 `logs/`，那是运行期产物。
- **依赖**：保持零第三方依赖（只用 Fabric API + JDK 自带的 `HttpURLConnection`）。
  引入新依赖前先想清楚是否值得 —— 目前整包不到 70 KB。
- **提交**：一个改动一个提交，提交信息写清根因与修法；纯文档改动不占版本号（见 §1）。
