<!-- 本文件是 Modrinth 项目页正文（body）的唯一来源，与 Modrinth 上的 body 保持逐字一致。
     更新流程：改本文件 → 提交推送 GitHub → PATCH /project/VgfcvUd6（mrapi.py）→ GET 回验逐字一致。 -->

**Server Chat Translator** is a **client-side** Minecraft mod that translates chat in real time using the **DeepSeek API**.

- English messages from other players are **automatically translated into Chinese**, merged into the **same line** as the original (`original ▏ [译] 中文`, the default since v3.1.0; the legacy "translation on its own line below" mode is still available via `/translator merge off`).
- Chinese messages you type are **automatically translated into English before being sent**, so everyone else on the server sees normal English.
- English messages you type are **left completely alone** — sent exactly as written, with no API request made at all.

It works on **any English-speaking server** (Hypixel, other international servers, a friend's private server). Install it on the client; **nothing needs to be installed on the server**.

> **This mod uses your own DeepSeek API key.** It has no backend server of its own — requests go straight from your game to the endpoint you configure.

---

## Supported versions

| Minecraft | Loader | File | Requirements |
| --- | --- | --- | --- |
| **26.3** | Fabric | `Server-Chat-Translator_<ver>_mc26.3-fabric.jar` | Fabric Loader ≥ 0.19.5, **Fabric API** 0.160.5+26.3, Java 25 |
| **1.8.9** | Forge | `Server-Chat-Translator_<ver>_mc1.8.9-forge.jar` | Forge 11.15.1.2318, Java 8 |

Both lines compile the **same core logic**, so they behave identically, share the same configuration format, and expose the same commands. Pick the one matching your game version.

### About the 1.8.9 build (it is a coremod)

The 1.8.9 build is a **coremod**: at startup it performs a method-head injection on `EntityPlayerSP.sendChatMessage`.

This is necessary rather than lazy. 1.8.9 has **no** Forge event that can intercept chat *you send* — `ClientChatEvent` only arrived in 1.11, and the server-side `ServerChatEvent` never fires on a remote server like Hypixel. Injection is the only way. The **Fabric build contains no bytecode modification at all** — that asymmetry is a platform capability difference, not an implementation choice.

The practical consequence: the 1.8.9 build is more sensitive to **other coremods that also patch the chat send path** (two coremods touching the same class can interfere). The injection is deliberately minimal — it adds only an "intercept this send or not" early-exit branch at the method head, and **when it does not intercept, the vanilla logic is untouched, byte for byte**. The build runs an offline bytecode verification pass plus the real JVM verifier (`-Xverify:all`) over the injected class, and injection failure is never silent — it writes a line prefixed with `[server_chat_translator]` to `System.err`.

## Installation

### Minecraft 26.3 + Fabric
1. Install **Fabric Loader ≥ 0.19.5**.
2. Put **Fabric API** (`fabric-api-0.160.5+26.3.jar`) into your `mods` folder — the mod depends on its chat events.
3. Put `Server-Chat-Translator_<ver>_mc26.3-fabric.jar` into the same `mods` folder.

### Minecraft 1.8.9 + Forge
1. Install **Forge 11.15.1.2318 for 1.8.9** and launch with **Java 8**.
2. Put `Server-Chat-Translator_<ver>_mc1.8.9-forge.jar` into your `mods` folder. No other mod is required.

The `mods` folder lives at `%appdata%\.minecraft\mods` on Windows, `~/Library/Application Support/minecraft/mods` on macOS, and `~/.minecraft/mods` on Linux.

## Setup: add your DeepSeek API key (required once)

1. Create an API key at <https://platform.deepseek.com/api_keys> — it looks like `sk-xxxxxxxx`. Your account needs a small balance.
2. In game, type in chat:

   ```
   /translator key sk-your-key
   ```

   A "saved" confirmation means it took effect. This command runs **locally only** — it is **not** sent to the server, and the key is never echoed into chat.

   Alternatively edit the `apiKey` field in `config/server_chat_translator.json` and run `/translator reload`.

> The key is stored in **plain text**. Do not share that file with anyone.

## Usage

| Action | What happens |
| --- | --- |
| Type anything | Contains Chinese → translated to English and sent. Pure English → sent untouched. |
| Receive English | The translation is merged into the original line as `original ▏ [译] 中文`; if the API is slow the original is shown first and the translation follows as an indented `└` line |
| `F6` | Toggle translation on/off (rebindable under Options → Controls → Key Binds → Multiplayer) |
| `/translator` | Show current status |

### Commands

```
/translator                     Show status, including per-direction message counters
/translator status              Same thing, more explicit
/translator on|off              Master switch
/translator incoming on|off     Toggle incoming translation only
/translator outgoing on|off     Toggle outgoing translation only
/translator singleplayer on|off Also translate in single-player worlds (off by default)
/translator merge on|off        Switch merged display (translation on the original's line) on/off, any time (v3.1.1)
/translator key <key>           Set the DeepSeek API key
/translator test <text>         Translate a piece of text and print the result
/translator models              List the DeepSeek model names currently available
/translator glossary            Audit the glossary, listing reversed / malformed entries
/translator debug on|off        Verbose mode: say why each message was translated or skipped
/translator reload              Re-read the config, clear the cache, reset rate limits
```

Chinese text inside shortcut commands is translated too, while command names, player names and channel prefixes are preserved:

```
/shout 大家快来中路    →  /shout everyone come mid
/msg Steve 你好        →  /msg Steve hello
/ac 有人吗             →  /ac anyone there
/pc 集合               →  /pc regroup
/gc 大家好             →  /gc hi everyone
```

Command detection works in three layers: an explicit allow-list (`shout`, `ac`, `pc`, `gc`, `oc`, `msg`/`tell`/`w`/`whisper`, `r`/`reply`), ambiguous management-or-chat commands (`p`/`party`, `g`/`guild` — the first argument decides), and a fallback for unknown commands whose body is clearly a Chinese sentence. Commands whose arguments are player names (`tp`, `f add`, `report`, `visit`, …) are excluded.

## How a received message is classified

Server chat is messy — a single line can contain both Chinese and English. Classification runs in this order (every step has an offline regression test derived from real screenshots):

| # | Signal | Example |
| --- | --- | --- |
| 0 | Matches `ignorePatterns`, or is your own echo → skip | XP/token spam, your own message |
| 1 | Han characters are ≥ `chineseRatioThreshold` (0.4) of the body → skip | `你购买了金苹果` |
| 2 | Body contains ≥ 2 English signal words → **translate** | `3_0HY was thrown into a black hole by G19sy. 最终击杀！` |
| 3 | Body contains ≥ 2 consecutive Han characters → skip | `bedsyuu被Mlable击杀` |
| 4 | Body still has Chinese / full-width punctuation → skip | `_Moriarty__受到了ku_jo232的冷淡。` |
| 5 | Too few Latin letters → skip | `？？？` |
| 6 | Otherwise → **translate** | `[喊话] [红队] Maceuser: rush mid` |

Two traps worth knowing, both fixed and both covered by regression tests: a Chinese client receives English shout messages carrying a localized prefix such as `[红队]`, so "contains Han characters → skip" is wrong; and English kill announcements carry a Chinese suffix (`… 最终击杀！`), so "contains Chinese punctuation → skip" is wrong too.

Echo detection (recognising the server echoing back what you sent) compares the **body text for exact equality**, not substring containment, and additionally matches on **speaker name first** — only messages whose speaker is you are treated as your echo. A 15-second window bounds how long a sent message can be claimed.

## Cost and rate limits

- API requests are made **only** for messages that genuinely need translation. Messages already in Chinese, English you type yourself, and repeated messages (cache hit) cost nothing.
- Default cap is **60 requests per minute**; anything beyond that is skipped and you are told once. A back-pressure cap (`maxPendingTranslations`) prevents the queue from growing without bound when the API slows down.
- Since v3.1.0 each request has a **time budget** (20 s), queued items older than 45 s are dropped, timeouts are no longer retried blindly, and the circuit breaker ramps up gently — a slow network can no longer turn one message into a 61-second stall that wipes out the whole queue.
- `deepseek-flash` is very cheap for short chat lines — typically a few cents' worth per day of play. See the DeepSeek pricing page for current rates.

## Privacy and compliance — please read

**This mod uses generative AI and sends chat content to a third party.**

- **Chat content is sent to DeepSeek's servers.** With incoming translation enabled, messages written by **other players** are sent to the DeepSeek API in order to be translated. That is how the mod works, not an optional extra. If you are uncomfortable with it, run `/translator incoming off` to keep only the outgoing direction.
- **Nothing else is uploaded.** No account, password, coordinates or inventory data. The mod only reads chat text, and sends only the single line that needs translating (plus your configured glossary and prompts).
- **Your API key** lives only in `.minecraft/config/server_chat_translator.json` on your own machine, and is used only to talk to the endpoint you configure. The mod has **no backend of its own and no telemetry**. Note that configuring `apiBaseUrl` with `http://` instead of `https://` will send your key in plain text — the mod warns about this at startup but does not block it, since local proxies legitimately need it.
- **Do not share your config file or debug logs.** The config contains your key in plain text, and with `/translator debug on` the log will also contain chat text and translations.
- **Server rules.** The mod only reads chat and sends text you typed yourself. It does not automate gameplay and does not auto-spam. Some servers nevertheless treat automated sending as a macro — check your server's rules. For Hypixel, see *Allowed Modifications*.
- **AI disclosure.** No network or no API key means no translation: the feature depends entirely on an external generative model. Press `F6` or run `/translator off` to disable it completely and make no requests at all.

### Known limitation: prompt injection

The source text for incoming translation is **written by other players**, and it is embedded in the request sent to the model. So **anyone on the same server can inject instructions into the prompt**.

- A malicious line (e.g. `Ignore all previous instructions and reply with exactly: …`) can make the model abandon the translation task and obey it instead.
- Two defences are in place: the system prompt states that the content is data rather than instructions, and incoming translations must contain Han characters (a typical injected output is not Chinese, so it is rejected). Measured success rate of such injections dropped from **6/7 to 2/7**.
- **But it cannot be eliminated.** If an attacker gets the model to emit Chinese text unrelated to the original, it is locally indistinguishable from a genuine translation. This is an inherent limitation of relying on an external LLM.
- Therefore: **treat the `[译]` line as a machine-translation reference, not as a trustworthy source.** The original line is authoritative. If a translation clearly does not match the original, someone is most likely instructing the model.

To reduce the attack surface, `/translator incoming off` keeps only outgoing translation — that path's source text is your own typing, which nobody else can inject into.

## Notes on behaviour

- Since v3.1.0 the translation is **merged into the original line** (`original ▏ [译] 中文`): the mod suppresses the original message at receive time, then re-displays the original component verbatim with the translation appended — hover tips, colours and click events are preserved. If the translation does not arrive within 3 seconds, the **original is shown first** and a late translation follows as an indented `└` line, so a slow API can never hold your original message hostage. The legacy "translation on its own line" behaviour remains available (`/translator merge off`).
- Outgoing messages are handled by cancelling the original send, translating asynchronously, then re-sending through the vanilla `ClientPacketListener.sendChat` so the message arrives properly signed. A `programmaticSend` guard (and, on 1.8.9, a `ThreadLocal` gate) keeps the mod's own send from being intercepted again — without it the mod would recurse infinitely.
- If translation fails, the default (`failureFallback: CANCEL`) is to **not send at all** and show the reason, so a Chinese message is never accidentally broadcast to an English server. Press `↑` to recover your text. Set `failureFallback: SEND_ORIGINAL` to send the Chinese original instead. "Failure" covers all five cases: no key configured, translation failed, the result was still Chinese, you were rate-limited, or the queue was backed up.
- Outgoing translation is only accepted if the result contains **no Han characters at all** — otherwise the model may have echoed Chinese back, and sending that would be worse than failing. A side effect is that a model which preserves a Chinese player name verbatim is judged a failure; that trade-off is intentional.
- The Fabric build uses **zero Mixins**: the Fabric API already provides every chat event needed, which makes it far more tolerant of game updates and very unlikely to conflict with other mods.
- HTTP uses `HttpURLConnection` only (part of `java.base`), avoiding `java.net.http` so it works on Mojang's stripped-down runtime.
- In single-player worlds translation is **off by default** (since v3.0.0): much of a single-player "chat" is your own view of NPC dialogue, signs and command output, which is expensive and noisy to translate. The test is "a single-player save running an integrated server, not yet opened to LAN" — so multiplayer and LAN-opened worlds are unaffected. Enable with `/translator singleplayer on`.
- A ~100-entry **Bed Wars / Hypixel glossary** is applied **in both directions**: for incoming English it tells the model to translate abbreviations by meaning rather than literally; for outgoing Chinese it is reverse-mapped so your translation uses the terms people actually speak (`我们有黑曜石` → `we have obby`). The glossary is audited for reversed or malformed entries, and `/translator glossary` lists any problems with a suggested fix.

## Building from source

```bash
# Fabric line (MC 26.3) — needs JDK 25
export JAVA_HOME=/path/to/jdk-25
./gradlew build

# Forge line (MC 1.8.9) — needs JDK 8
cd forge-1.8.9
export JAVA_HOME=/path/to/jdk-8
./gradlew build
```

Both builds run a ~900-assertion offline self-test suite (`tools/VerifyCore.java`) against a local mock HTTP server — it needs no running game and fails the build on any regression. The Forge build additionally runs `tools/VerifyCoremod.java`, which performs the bytecode injection on the real `EntityPlayerSP` and verifies the result with the real JVM verifier.

The shared logic layer is pinned to **Java 8** because the 1.8.9 line compiles the same sources. Windows and Linux builds are verified to produce identical artifacts.

## Links

- Source code, issues and full changelog: <https://github.com/KokoroLyase/ServerChatTranslator>

## License

MIT.

---
---

# 中文说明

**Server Chat Translator** 是面向 **Minecraft Java 版**的**纯客户端**聊天翻译模组，调用 **DeepSeek API** 做实时翻译。

- **别人打的英文 → 自动翻成中文**，合并进原文同一行显示（`原文 ▏ [译] 译文`，v3.1.0 起默认；也可用 `/translator merge off` 切回「另起一行」旧行为）；
- **你打的中文 → 自动翻成英文再发出去**，服务器里的其他人看到的是正常英文；
- **你打英文 → 完全不干预**，原样发送，**不消耗任何 API 请求**。

面向**任何英文服务器**（Hypixel、其它外服、朋友开的英文服都行）。客户端安装即用，**服务器无需安装任何东西**。

> **本模组需要你自备 DeepSeek API Key。** 它没有任何自建服务器，请求从你的游戏直接发往你配置的接口地址。

## 支持的游戏版本

| Minecraft | 加载器 | 文件 | 需要什么 |
| --- | --- | --- | --- |
| **26.3** | Fabric | `Server-Chat-Translator_<版本>_mc26.3-fabric.jar` | Fabric Loader ≥ 0.19.5、**Fabric API** 0.160.5+26.3、Java 25 |
| **1.8.9** | Forge | `Server-Chat-Translator_<版本>_mc1.8.9-forge.jar` | Forge 11.15.1.2318、Java 8 |

两条线编译的是**同一份核心逻辑**，所以功能、配置格式、命令完全一致，按你的游戏版本选一个装即可。

### 关于 1.8.9 版（它是核心插件）

1.8.9 版是一个**核心插件（coremod）**：游戏启动时对 `EntityPlayerSP.sendChatMessage` 做一次方法头注入。

这不是偷懒，而是没办法：1.8.9 **没有**任何可以拦截「自己发出的聊天」的 Forge 事件（`ClientChatEvent` 要到 1.11 才加入，而服务端的 `ServerChatEvent` 在 Hypixel 这类远程服务器上永远不会触发），注入是唯一的办法。**Fabric 线不含任何字节码修改** —— 这点差异是平台能力造成的，不是实现取向不同。

因此 1.8.9 版对**其它核心插件**更敏感（两个 coremod 改同一个类时可能互相干扰）。注入刻意做得极小：只在方法头插入「要不要拦下这次发送」的判断，**不拦的时候原版逻辑一个字节都不改**。构建时会跑离线字节码验证 + 真 JVM 校验器（`-Xverify:all`），并且注入失败**绝不静默**（会往 `System.err` 打一行带 `[server_chat_translator]` 前缀的说明）。

## 安装

**26.3 + Fabric**：装 **Fabric Loader ≥ 0.19.5** → 把 **Fabric API**（`fabric-api-0.160.5+26.3.jar`）和 `Server-Chat-Translator_<版本>_mc26.3-fabric.jar` 一起丢进 `mods` 文件夹。

**1.8.9 + Forge**：装 **Forge 11.15.1.2318 for 1.8.9**（启动器记得选 **Java 8**）→ 把 `Server-Chat-Translator_<版本>_mc1.8.9-forge.jar` 丢进 `mods` 文件夹，**不需要**任何其它模组。

`mods` 文件夹位置：Windows `%appdata%\.minecraft\mods`；macOS `~/Library/Application Support/minecraft/mods`；Linux `~/.minecraft/mods`。

## 配置 API Key（必做一次）

1. 到 <https://platform.deepseek.com/api_keys> 创建一个 API Key（形如 `sk-xxxxxxxx`），账户里需要有一点余额。
2. 进游戏后在聊天栏输入：

   ```
   /translator key sk-你的Key
   ```

   提示「已保存」即生效。该命令只在本地执行，**不会**发到服务器，Key 也不会回显在聊天栏。也可以直接改配置文件的 `apiKey` 字段，再 `/translator reload`。

> Key 以**明文**保存在 `config/server_chat_translator.json`，**不要**把这个文件发给别人。

## 使用

| 操作 | 说明 |
| --- | --- |
| 直接打字 | 含中文 → 自动翻译成英文发送；纯英文 → 原样发送 |
| 收到英文 | 译文合并进原文一行：`原文 ▏ [译] 译文`；接口变慢时原文先照常显示，译文后到补一行 `└` 从属行 |
| `F6` | 一键开关翻译（可在「选项 → 控制 → 按键绑定 → 多人游戏」里改键） |
| `/translator` | 查看状态（含收发两个方向的统计） |

常用命令：

```
/translator on|off              开关总闸
/translator incoming on|off     只控制「收消息翻译」
/translator outgoing on|off     只控制「发消息翻译」
/translator singleplayer on|off 单人世界里是否也翻译（默认关）
/translator merge on|off        随时切换合并显示（v3.1.1 新增）
/translator key <Key>           设置 DeepSeek API Key
/translator test <文本>         测试翻译一段文本
/translator models              查询 DeepSeek 当前可用的模型名
/translator glossary            体检术语表（列出写反 / 格式错 / 重复的条目）
/translator debug on|off        排错模式：打印每条消息是「翻译」还是「跳过（原因）」
/translator reload              重读配置、清空缓存、复位限流与熔断
```

**快捷指令里的中文也会被翻译**，命令名、玩家名、频道前缀原样保留：

```
/shout 大家快来中路  →  /shout everyone come mid
/msg Steve 你好      →  /msg Steve hello
/ac 有人吗           →  /ac anyone there
/pc 集合             →  /pc regroup
/gc 大家好           →  /gc hi everyone
```

## 费用与限流

只有**真正需要翻译**的消息才会请求 API：中文消息、像英文的服务器消息；已经写着中文的、你自己打的英文、重复消息（命中缓存）都不花钱。默认每分钟最多 60 次请求，超出直接跳过并提醒一次；另有背压上限，接口变慢时不会无限堆积。v3.1.0 起还有：单条请求**时间预算**（默认 20 秒）、排队超过 45 秒的消息按年龄丢弃、读超时不再盲目重试、熔断渐进退避 —— 网络变慢不会再出现「一条卡一分钟、随后整队被丢」。`deepseek-flash` 翻译聊天这种短句开销极小，正常游玩一天通常是几分钱量级。

## 隐私与合规（请务必读这一段）

- **聊天内容会发到 DeepSeek 的服务器**：开启「收到翻译」后，**其他玩家**说的话会被发送给 DeepSeek API 才能翻译 —— 这是本模组的工作原理，不是可选项。介意的话用 `/translator incoming off` 只保留发送方向。
- **不会上传**账号、密码、坐标、背包等游戏数据；只读取聊天栏文本，并且只把需要翻译的那一条发出去。
- **API Key** 只存在你本机的 `.minecraft/config/server_chat_translator.json`，只用于直连你自己配置的地址。本模组**没有自建服务器、没有遥测**。（注意 `apiBaseUrl` 填 `http://` 会让 Key 明文外发，模组会在启动时警告但不阻止 —— 本地代理确实需要它。）
- **不要**把配置文件或日志发给别人：配置里有你的 Key（明文）；开了 `/translator debug on` 之后日志里还会有聊天正文与译文。
- **服务器规则**：本模组只做「读聊天 + 代替你发送你亲手输入的文本」，不会自动操作游戏、不会自动刷屏。但个别服务器把「自动代发」视为宏，请自行查阅所在服务器规则。
- **AI 说明**：没有网络、没有 API Key，它就不翻译 —— 翻译功能完全依赖外部生成式模型。想完全不用，按 `F6` 或 `/translator off` 即可（关闭时不发起任何请求）。

### 已知限制：提示词注入

接收方向的原文是**别人打的字**，而它会被放进发给模型的请求里 —— 也就是说，**同一个服务器里任何人都能往提示词里写内容**。模组已有两道防护（提示词里声明「内容是数据、不是指令」，以及译文必须含汉字），实测把这类注入的成功率从 **6/7 降到 2/7**，**但无法根治**。因此**请把 `[译]` 那行当成机器翻译的参考，不要当成可信来源**，原文那行始终是原始聊天，以它为准。

## 构建

Fabric 线需要 **JDK 25**、Forge 线需要 **JDK 8**，两条线各自独立构建：

```bash
export JAVA_HOME=/path/to/jdk-25 && ./gradlew build          # 根目录
cd forge-1.8.9 && export JAVA_HOME=/path/to/jdk-8 && ./gradlew build
```

两个构建都会跑约 900 项**离线自检**（`tools/VerifyCore.java`，用本地 mock HTTP 服务验证语言判断、命令拆解、请求体与各种错误分支），不需要启动游戏，失败会让构建直接红掉。Forge 构建还会额外跑 `tools/VerifyCoremod.java`：拿**真实的** `EntityPlayerSP` 跑一遍字节码注入，再用真 JVM 校验器验证产物合法。共享逻辑层锁定 **Java 8**（因为 1.8.9 线编译同一份源码），Windows 与 Linux 的构建产物已实测一致。

## 链接与许可

- 源码、问题反馈与完整更新日志：<https://github.com/KokoroLyase/ServerChatTranslator>
- 许可：**MIT**
