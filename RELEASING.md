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

### Release 说明的约定

CI 建的 Release 只有一句自动生成的 `**Full Changelog**` 占位（v1.0.6 就是这个状态），
**推完 tag 之后必须手动补写**，三段固定内容：

1. **这一版改了什么** —— 只写玩家能感知的变化；纯重构/整洁度版本就明说「行为没变」；
2. **升级后你要做什么** —— 环境要求有没有变、配置要不要改、不支持哪些旧版本；
3. **验证** —— 自检跑了多少项、CI 结论、以及**哪些部分没有自动化覆盖**
   （见 §6 末尾：`GameClient` / 提示词效果 / 换 MC 版本的人工核对）。

另外记得在正文里点明「不兼容变更」：例如换 MC 版本时要直接写出
「本版不再支持 26.2，旧版用 vX.Y.Z」，不要让玩家自己去对比表格。

## 4. 保留旧版

- **旧版本的 Release 与附件一律保留**，不删除、不覆盖、不改 tag 指向。
- 新版本走新 tag、新 Release；旧版说明里加一行指向 [Releases](https://github.com/KokoroLyase/HypixelChatTranslator/releases) 即可
  —— 注意**不要**再写 `releases/latest`：两条线并行之后，「最新」只代表最近发布的那条线，
  不一定是读者要的那条。
- 涉及旧附件改名时，重新上传同内容的新名字附件再删旧名，**不要删掉整个 Release**。
- 唯一的例外是**纠错**，不是常规操作，至今只发生过两次：
  - `v1.0.10` 违反 §1 的进位规则、发布仅数分钟且无人下载，被撤回并更正为 `v1.1.0`（见 §1 结尾）；
  - **v2.3.0 两个 Release 的定名**（2026-09-17，同一天改了两次）：双版本并行后要给 Release
    名加后缀，先定成 `v2.3.0-fabric` / `v2.3.0-forge`；随后发现只看后缀分不出游戏版本，
    最终定为与产物文件名对齐的 `v2.3.0-mc26.3-fabric` / `v2.3.0-mc1.8.9-forge`（§10.2）。
    两次都严格遵守同一个顺序：**先**在原提交上建新 tag 与新 Release，核对新附件
    （Fabric 比整包 sha256 逐字节相同；Forge 按 §10.3 比内容）证明只改名没改内容，
    **再**删旧 tag 与旧 Release —— 顺序反了就会出现「这段时间没有版本可下」的空窗。
    中途用过的名字已全部删除，仓库里不留悬挂引用。
    ≤ v2.2.3 的历史 Release 一律**保持旧名不动**。
  名字要改，请尽量在**打 tag 之前**想清楚 —— 推上去之后再动 tag 与 Release，
  代价和风险都比改一个字符串大得多。这次连着改两遍就是反面教材：
  定名时应该一开始就把「游戏版本」和「加载器」都放进名字里。

## 5. 配置兼容

`config/hxtranslate.json` 是用户资产，升级时：

- 通过 `TranslatorConfig.applyMigrations()` 做**自动迁移**，并把 `configVersion` +1；
- 只补缺、只替换「仍是旧版默认值」的字段（用提示词标记判断，例如 `LEGACY_INCOMING_MARKERS`）；
- **绝不覆盖**用户自定义的提示词、术语表、命令名单和数值；
- 迁移逻辑要做成「少改」而不是「多改」：判据只认老版默认值的识别标记，**不要**用
  「里面没有某个新段落就当成老版」这类会误伤手写内容的规则（v1.1.3 修的就是这条）；
- 配置**读不出来**时（JSON 语法错误、类型写错）必须先把原文件整份另存为
  `hxtranslate.json.broken-<时间戳>`，再退回默认值，并在游戏内告知原因与备份文件名 ——
  只写日志的话，玩家看到的只是「设置全没了」，而之后任意一次保存都会把原件覆盖掉；
- 写盘一律走 `TranslatorConfig.writeAtomically()`（先写 `.tmp` 再改名），
  并且保存时要保留文件里模组「不认识的字段」；
- 迁移逻辑必须是纯函数，并在 `tools/VerifyCore.java` 里有对应用例；
  磁盘读写路径的用例用临时目录跑真的 `load()` / `save()`（见 `v113ConfigDurability`）。

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
- 用例**不要自己抄一份被测逻辑**：判定规则要抽成不依赖 Minecraft 的纯函数
  （例如 `LangUtils.compilePatterns` / `matchesAny`），生产代码和自检调用同一个。
  自检里重抄一遍 `Pattern.compile(...)` 的写法，在生产代码换掉 `find()` 语义时照样是绿的。
- 用例要**抗自己的失败**：一条断言报红不能变成异常（例如「备份文件没生成」时不要直接
  `list.get(0)`），否则后面的用例全都跑不到，反向验证也看不出全貌。
- 需要多线程协作的断言要**确定性**：先等第一步完成再发第二步，
  否则「同时提交两条」这类写法会因为时序而恒绿（v1.1.3 的缓存方向用例就是改成这样的）。
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
- **日志**：一律用 `com.isomeria.hxtranslate.Log.LOGGER`，**不要**用入口类的
  `HxTranslateClient.LOGGER`。入口类实现 `ClientModInitializer`，引它会连带加载 Fabric
  加载器 API：万一那个类不可用（或以后挪了包名），打日志就会抛 `NoClassDefFoundError`，
  而它是 `Error`，`catch (RuntimeException)` 接不住，工作线程直接死、翻译回调不执行、
  消息静默消失（v2.1.0 修的就是这个，见 CHANGELOG）。自检的类路径已经剔除了游戏库，
  违反这条会在 `./gradlew build` 里立刻报错。
- **自检的类路径**：`build.gradle` 里 `sourceSets.verify` 刻意**只放行 gson 与 slf4j**，
  Minecraft / Fabric / LWJGL / authlib 全部剔除。所以 `tools/VerifyCore.java` 能碰到的
  只有纯逻辑类；若要在自检里用新库，必须在那个过滤器里显式加白名单，别把
  `configurations.compileClasspath` 整个塞回去 —— 那会让「纯逻辑类误引用游戏 API」
  重新变成能悄悄通过的事。
- **提交**：一个改动一个提交，提交信息写清根因与修法；纯文档改动不占版本号（见 §1）。
- **issue 模板要放两份**：`.github/ISSUE_TEMPLATE/` 下的 **YAML 表单**（`bug_report.yml`，
  GitHub 现行格式，新建 issue 页面优先用它）与**同名 Markdown 模板**（`bug_report.md`）。
  两份内容是同一份东西，改动时一起改。原因是 2026-09 实测出来的一个坑：
  GitHub 的**社区档案接口只统计旧式路径**（`.github/ISSUE_TEMPLATE.md` 或目录下的
  **Markdown** 模板），目录下的 YAML 表单不会被它登记 —— 只放 YAML 时
  `health_percentage` 会一直显示 `issue_template: 缺失`。GraphQL 的
  `repository.issueTemplates` 同样只返回旧式模板，因此**这两个接口都不能用来判断
  YAML 表单是否生效**，要确认只能打开一次「New issue」页面看选择器。

## 9. 跨 Minecraft 版本升级（换 `minecraft_version` 时照做）

换 MC 版本属于**不兼容变更**（§1），版本号按首位进位。下面这张清单来自 26.2 → 26.3
那次升级（v2.0.0），每一步都对应过一个真实的坑：

1. **先查工具链**，不要凭记忆填版本：
   - Fabric 游戏版本与 loader：`https://meta.fabricmc.net/v2/versions/game`、
     `.../versions/loader`；
   - Fabric API 对应版本：Modrinth 的 `game_versions` 过滤；
   - **Java 要求**：Mojang 版本清单里该版本的 `javaVersion.majorVersion`
     （26.3 仍是 25，不要想当然跟着年份涨）。
2. **同步三个文件的版本号**：`gradle.properties`（`minecraft_version` / `loader_version` /
   `fabric_api_version`）、`src/main/resources/fabric.mod.json` 的 `depends`、README 的环境
   要求表与产物文件名。**这三处现在由自检的 `versionConsistency()` 盯着**，漏一处构建就红。
3. **`./gradlew clean build`，逐个修编译错误**，并把每一处 API 变更**记进 CHANGELOG**
   （写清「26.2 怎么写 / 26.3 怎么改 / 报错原文」）。26.2 → 26.3 的两处是：
   `org.lwjgl.glfw` 不再直接可见（改用 `InputConstants.KEY_F6`）、
   `InputConstants.Type.KEYSYM` 合并为 `KEYBOARD`。
4. **人工核对注册面**（编译不会报错的部分）：`GameClient.register()` 里四条事件
   （`ALLOW_CHAT` / `ALLOW_COMMAND` / `GAME` / `CHAT`）与 `HxTranslateClient` 里的
   开关按键 + 生命周期回调，一条都不能少。接收方向的两条链路尤其不能删：签名聊天能给
   发送者，Hypixel 的系统消息给不了，少一条就有一半场景失效。
5. **确认自检仍然全绿**，特别是 `v210ChatLogic()` 那一组 —— 它覆盖的正是编译期看不出来的
   发送/接收行为（降级五条路径、切服保护、单线程顺序、缓存命中、告警节流）。
6. 打 tag 前确认产物文件名里的 `mc<版本>` 已变（`archiveFileName` 用的是
   `project.minecraft_version`，所以只要第 2 步改对就会对）。

## 10. 双版本并行（v2.3.0 起）

从 v2.3.0 起仓库同时维护两条线，**共用同一份纯逻辑源码**：

| 线 | 构建目录 | MC / 加载器 | 工具链 | 产物名 |
| --- | --- | --- | --- | --- |
| Fabric | 仓库根目录 | 26.3 / Fabric | Gradle 9 + Loom + JDK 25 | `hx-chat-translator-<版本>+mc26.3-fabric.jar` |
| Forge | `forge-1.8.9/` | 1.8.9 / Forge 11.15.1.2318 | Gradle 2.14.1 + ForgeGradle 2.1 + **JDK 8** | `hx-chat-translator-<版本>+mc1.8.9-forge.jar` |

### 10.1 共享层是硬约束

`src/shared/java` 由**两个构建编译同一份文件**，因此锁定在 **Java 8**：

- **不许**出现 Java 9+ 的语法与 API：`record`、文本块、`List.of` / `Map.of` / `Set.of`、
  switch 表达式、`String.isBlank` / `strip*`、`Files.readString/writeString`、
  `StringBuilder.isEmpty`、`InputStream.readAllBytes`、`Optional.isEmpty`、
  菱形 + 匿名类、`ByteArrayOutputStream.toString(Charset)`。
- 需要 Java 11 语义的地方用 `LangUtils` 的**语义精确复刻**（`isBlank` / `strip` /
  `stripLeading` / `stripTrailing` / `lines` / `repeat`）。**不要用 `trim()` 顶替**：
  `isBlank`/`strip` 按 `Character.isWhitespace` 判定，`trim()` 只认 `<= ' '`，
  两者对全角空格等输入结论不同，会让两条线对同一句话给出不同判断。
- 第三方库只有 **gson**，而且只能用 **1.8.9 自带的 2.2.4** 也有的 API：
  `JsonParser.parseString` 是 Java 11 的静态方法（2.2.4 没有），要用 `new JsonParser().parse(...)`；
  `JsonArray.add(String)` 重载 2.2.4 也没有，要显式包 `JsonPrimitive`。
- 日志一律走 `Log`（自己实现的加载器无关门面，接口是 slf4j 的常用子集）。
  **不许**在共享层直接 import slf4j 或 log4j：Fabric 有 slf4j，1.8.9 只有 log4j。
- 配置目录由装配层通过 `TranslatorConfig.setConfigDir` 注入（共享层不许 import 加载器 API）。

两道门禁合起来才成立，改动共享层后**两个构建都要跑**：

1. `sharedLayerPurity()`：共享层不许出现任何游戏/加载器 import；
2. Forge 构建用 JDK 8 编译共享层 —— 所有 Java 9+ 语法与 API 会在这里直接编译失败。

### 10.2 版本号与标签

- `mod_version` 只有**一个来源**：仓库根目录的 `gradle.properties`；
  `forge-1.8.9/build.gradle` 从那里读，两条线永远同号。
- **tag 与 Release 名的格式是 `v<版本>-mc<游戏版本>-<加载器>`**（v2.3.0 起）：

  | 线 | tag / Release 名 | 例 |
  | --- | --- | --- |
  | Fabric | `v<版本>-mc<游戏版本>-fabric` | `v2.3.0-mc26.3-fabric` |
  | Forge | `v<版本>-mc<游戏版本>-forge` | `v2.3.0-mc1.8.9-forge` |

  刻意与**产物文件名**对齐，只差一个字符（产物用 `+`、tag 用 `-`）：
  产物是 `hx-chat-translator-2.3.0+mc26.3-fabric.jar`，tag 就是 `v2.3.0-mc26.3-fabric`。
  这样「看到 jar 的名字就知道该找哪个 Release」，反过来也一样。
  只写 `-fabric` / `-forge` 不够 —— 光看 tag 分不出是给哪个 Minecraft 版本的，
  而两条线将来各自都可能再支持别的游戏版本。
- **历史遗留**：≤ v2.2.3 的 Release 用的是加后缀之前的旧名（`v2.2.3`、`v1.1.3` …），
  一律**保持原样不动**（§4）。v2.3.0 这两个 Release 定名前改过两次，经过见 §4。
- 两个 workflow 各盯自己的后缀：`build.yml` 只在 `v*-mc*-fabric` 上发 Release，
  `build-forge.yml` 只在 `v*-mc*-forge` 上发。**改标签约定时两处都要改**，
  否则要么同一个标签被两条线各建一次 Release，要么某条线压根不发。
  写成 `v*-mc*-<加载器>` 而不是 `v*-<加载器>`，是为了让「漏了 mc 段」的名字
  **不会**自动发版 —— 宁可什么都不发、让人工发现名字写错。
- 两条线的 Release 说明都必须写三段（§3）；Forge 线还要写明它是 coremod。

### 10.3 产物可复现性与核对方式（两条线不一样，别用错判据）

| 线 | 整包 sha256 可复现？ | 核对方式 |
| --- | --- | --- |
| Fabric（Gradle 9） | **可以**，本地与 CI 逐字节相同 | 直接比 `sha256sum` |
| Forge（Gradle 2.14） | **不可以**：2.14 没有 `preserveFileTimestamps` / `reproducibleFileOrder`（Gradle 3.2+ 才有），jar 里带构建时间戳 | **比内容**：解开两个 jar，逐条对比「条目名 → 内容 sha256」 |

所以 Forge 线的 Release 核对**不能**用整包 sha256 判「构建是否一致」——
本次实测：同一份源码、本地与 CI 的整包 sha256 不同，但 69 个条目**逐条内容完全一致**。
写成一句话就是：**Forge 线看内容，Fabric 线看整包哈希。**

### 10.4 给 1.8.9 线加东西时

- 决策逻辑仍然只能写在共享层；Forge 侧只放「把游戏对象翻译成朴素类型」的装配代码。
- 1.8.9 的三个平台事实（改之前先看一眼，别再重新踩）：
  - **没有 Brigadier**（1.13 才有）→ 命令写 `ICommand` + `ClientCommandHandler`；
  - **没有签名聊天**（`S02PacketChat` 只带 `IChatComponent` + `type`）→ 拿不到发送者，
    `isLocalPlayer` 恒 false、发送者传 null；
  - **没有 `ClientChatEvent`**（1.11 才加入；`ServerChatEvent` 只在集成服务端触发）→
    拦「自己发的聊天」只能靠 `forge-1.8.9/.../asm/` 里的核心插件。
- 核心插件的每次改动**必须**通过 `verifyCoremod`（`forge-1.8.9` 的 `check` 已自动带上）：
  它拿真实 deobf `EntityPlayerSP` 跑一遍转换器，再用**真 JVM 校验器**（`-Xverify:all`）
  验字节码，并做三项反向验证（非目标类原样返回 / SRG 名命中 / 混淆名命中）。
  这道门禁抓到过一个真实缺陷：`IClassTransformer.transform` 传进来的类名是**点号分隔**的，
  按斜杠内部名去比会导致 MCP 名永远匹配不上 —— 游戏里的表现是「发送方向完全不翻译」，
  而编译、构建、702 项自检全是绿的。
- 注入失败**绝不能静默**：`HxTransformer` 的 catch 会往 `System.err` 打一行明确的
  失败说明（那条路径执行得极早，碰不得日志框架）。
- 1.8.9 的 `IChatComponent.getUnformattedText()` 会带出 `§` 代码（现代 `getString()` 不会），
  所以装配层统一先过一遍 `LangUtils.stripFormattingCodes`，保证两条线判定一致。
