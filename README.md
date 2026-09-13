# Hypixel 聊天翻译 (hx-chat-translator)

面向 **Minecraft Java 版 26.2 + Fabric** 的纯客户端聊天翻译模组。调用 **DeepSeek API** 做 AI 翻译：

- **别人打的英文 → 自动翻成中文**，作为一条附带消息显示在原消息下面；
- **你打的中文 → 自动翻成英文再发出去**，服务器里的外国人看到的是正常英文；
- **你打英文 → 完全不干预**，原样发送（不消耗任何 API 请求）。

> 专门针对 Hypixel 这类英文服务器设计，客户端安装即用，服务器无需安装任何东西。

> 仓库：<https://github.com/KokoroLyase/HypixelChatTranslator>
> 下载：见 [Releases](https://github.com/KokoroLyase/HypixelChatTranslator/releases)（也可以点 [Actions](https://github.com/KokoroLyase/HypixelChatTranslator/actions) 里任意一次成功构建，在 Artifacts 里下载）。
> 更新记录：[CHANGELOG.md](CHANGELOG.md)

---

## 1. 环境要求

| 项目 | 版本 |
| --- | --- |
| Minecraft | **26.2**（2026-06-16 发布的正式版） |
| Fabric Loader | **≥ 0.19.3** |
| Fabric API | **0.160.0+26.2**（必须安装，模组依赖它的聊天事件） |
| Java | **25**（26.2 强制要求，启动器会自动带上） |
| 系统 | Windows / macOS / Linux 均可 |

## 2. 安装

1. 安装 **Fabric Loader ≥ 0.19.3**（[官方安装器](https://fabricmc.net/use/installer/)）。
2. 把 **Fabric API** 放进 `mods` 文件夹：
   `fabric-api-0.160.0+26.2.jar`（[下载](https://modrinth.com/mod/fabric-api/versions?g=26.2)）。
3. 把本模组 **`hx-chat-translator-<版本>.jar`**（最新版见 [Releases](https://github.com/KokoroLyase/HypixelChatTranslator/releases/latest)）放进同一个 `mods` 文件夹：
   - Windows：`%appdata%\.minecraft\mods`
   - macOS：`~/Library/Application Support/minecraft/mods`
   - Linux：`~/.minecraft/mods`
4. 启动游戏，进入 Hypixel。

## 3. 配置 DeepSeek API Key（必须做一次）

1. 到 <https://platform.deepseek.com/api_keys> 注册并创建一个 API Key（形如 `sk-xxxxxxxx`），账户里需要有一点余额。
2. 进游戏后，在聊天栏输入：

   ```
   /hxtranslate key sk-你的Key
   ```

   提示「已保存」即生效（该命令只在本地执行，**不会**发到 Hypixel，Key 也不会回显在聊天栏）。

   也可以直接编辑配置文件 `config/hxtranslate.json` 里的 `apiKey` 字段，保存后 `/hxtranslate reload` 热重载。

> 配置文件首次启动时自动生成在 `.minecraft/config/hxtranslate.json`，Key 以**明文**保存，请注意不要把这个文件分享给别人。

## 4. 使用

| 操作 | 说明 |
| --- | --- |
| 直接打字 | 含中文 → 自动翻译成英文发送；纯英文 → 原样发送 |
| 收到英文 | 自动在下面追加一行 `[译] 中文` |
| `F6` | 一键开关翻译（可在「选项 → 控制 → 按键绑定 → 多人游戏」里改键） |
| `/hxtranslate` 或 `/hxt` | 查看状态 |

### 游戏内命令

```
/hxtranslate                 查看当前状态（含消息统计）
/hxtranslate on|off          开关总闸
/hxtranslate incoming on|off 只控制「收消息翻译」
/hxtranslate outgoing on|off 只控制「发消息翻译」
/hxtranslate key <Key>       设置 DeepSeek API Key
/hxtranslate test <文本>      测试翻译一段文本（结果会打印在聊天栏）
/hxtranslate debug on|off    排错模式：打印每条消息是「翻译」还是「跳过（原因）」
/hxtranslate reload          重新读取配置文件并清空缓存
```

### 快捷指令里的中文也会被翻译

命令名、玩家名、频道前缀都会原样保留，只翻译正文：

```
/shout 大家快来中路      →  /shout everyone come mid      （局内喊话）
/msg Steve 你好          →  /msg Steve hello              （私聊 / 好友私信）
/message、/tell、/w、/whisper 同上
/r 你好                  →  /r hello                     （回复上一条私聊）
/ac 有人吗               →  /ac anyone there              （全局聊天）
/pc 集合                 →  /pc regroup                   （队伍聊天）
/gc 大家好               →  /gc hi everyone               （公会聊天）
/oc 开会了               →  /oc meeting time              （公会官员聊天）
/party chat 大家好       →  /party chat hi everyone       （带子命令的写法）
/guild chat 大家好       →  /guild chat hi everyone
```

识别分三层，已按 [Hypixel 官方命令表](https://hypixel.fandom.com/wiki/Commands) 全覆盖：

1. **显式名单**（`translateCommandArgs`）：`shout`、`ac`/`achat`、`pc`/`pchat`、`gc`/`gchat`、`oc`/`ochat`、`msg`/`message`/`tell`/`w`/`whisper`、`r`/`reply`；
2. **管理/聊天二义性命令**（`guardedCommands`，默认 `p`/`party`/`g`/`guild`）：第一个词是 `chat` 就当聊天，是 `invite`/`kick`/`warp` 这类管理子命令就不动；
3. **未知命令兜底**：Hypixel 以后新加的聊天命令，只要正文明显是一句中文（较长或带中文标点）就翻译；`tp`、`f add`、`report`、`visit` 这些参数是玩家名的命令由 `protectedCommands` 排除在外。

想增删命令，改配置里的 `translateCommandArgs`：命令名（小写、不含斜杠）→ 正文前面还有几个参数。
例如 `/msg <玩家> <正文>` 是 `1`，`/shout <正文>` 是 `0`。

## 5. 主要配置项（`config/hxtranslate.json`）

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `apiKey` | `""` | DeepSeek API Key |
| `apiBaseUrl` | `https://api.deepseek.com` | 接口地址，用中转站时改这里 |
| `model` | `deepseek-chat` | 模型；`deepseek-chat` 快且便宜 |
| `temperature` | `1.3` | DeepSeek 官方建议的翻译温度 |
| `enabled` | `true` | 总开关 |
| `translateIncoming` | `true` | 翻译收到的英文 |
| `translateOutgoing` | `true` | 翻译自己发的中文 |
| `translateCommandMessages` | `true` | 是否翻译 `/msg` 之类命令的正文 |
| `translateCommandArgs` | 16 条 | 命令名 → 正文前有几个参数；按 Hypixel 官方命令表预置了所有聊天命令，可自行增删 |
| `guardedCommands` | `p`/`party`/`g`/`guild` | 既是管理又是聊天的命令，看第一个参数决定（`chat` → 聊天，`invite` 等 → 管理） |
| `commandManagementKeywords` | 40 条 | 上面那些命令的管理子命令关键字 |
| `translateUnknownCommands` | `true` | 名单外的命令，正文明显是一句中文时也翻译（应对 Hypixel 新增命令） |
| `protectedCommands` | 50+ 条 | 兜底翻译时排除的命令（`tp`、`f`、`report`、`visit` 等，参数是玩家名） |
| `incomingPrefix` | `§8[§b译§8] §f` | 译文前缀（支持 `§` 颜色代码） |
| `outgoingPrefix` | `§8[§a→EN§8] §f` | 自己发出去后的英文回显前缀 |
| `includeOriginalInIncoming` | `false` | 译文里是否再带上原文 |
| `minLatinLetters` | `2` | 至少几个拉丁字母才认为“像英文” |
| `chineseRatioThreshold` | `0.4` | 正文里汉字占比达到多少就认为「本来就是中文」而跳过（见下方「为什么需要这个阈值」） |
| `glossary` | 约 50 条 | Hypixel / Bed Wars 术语表，`缩写=含义`；会追加到提示词里，要求模型按含义翻译而不是保留 `obby`/`dia`/`u def` 这类英文缩写。清空即可关闭 |
| `maxIncomingChars` | `240` | 超过这个长度不翻译 |
| `maxOutgoingChars` | `256` | 译文最大长度（原版聊天框上限 256，超长会被服务器拒绝），超出会按词边界截断并加省略号 |
| `requestsPerMinute` | `40` | 每分钟最多请求次数（防刷屏烧钱） |
| `cacheSize` | `500` | 重复消息走缓存，不再花钱 |
| `httpTimeoutSeconds` | `20` | 请求超时 |
| `ignorePatterns` | 若干正则 | 命中的消息不翻译（服务器提示音效等） |
| `skipOwnEcho` | `true` | 自己发出的消息被服务器回显时不再翻回中文 |
| `showErrorsInChat` | `true` | 出错时在聊天栏提示 |
| `debugLog` | `false` | 调试模式：把每条消息的处理结果写进 `logs/latest.log` 并同步打印到聊天栏（`/hxtranslate debug on`） |
| `incomingSystemPrompt` / `outgoingSystemPrompt` | 见文件 | 两个方向的提示词，可自行微调语气 |
| `configVersion` | 当前版本号 | 配置结构版本，请勿手改；升级模组时会自动把老版提示词/术语表升级到新默认值，你自定义过的内容不会被覆盖 |

### 为什么需要 `chineseRatioThreshold`

你的客户端是中文时，Hypixel 会把队伍名**本地化**后塞进聊天内容，于是英文喊话长这样：

```
[MVP+] [红队] Mguappe: rush        ← 里面有汉字，但这是英文消息，必须翻译
```

而服务器自己发的中文消息长这样：

```
isabellab2012被Venomed击杀。         ← 汉字占比只有 0.15（玩家名很长），但它是中文
```

所以判断逻辑用了三条信号，缺一不可（实现在 `IncomingFilter`，有专门的离线回归测试）：

1. **含中文/全角标点**（。！？；，、」等）→ 判为中文，跳过；
2. **只看冒号后面的正文**的汉字占比 ≥ `chineseRatioThreshold` → 判为中文，跳过；
3. 正文里拉丁字母太少 → 不是英文，跳过。

如果遇到误判，可以调这个阈值（调低 = 更容易判成英文去翻译，调高 = 更保守）。

## 6. 工作原理

```
收到消息:  ChatListener/系统消息 → Fabric ClientReceiveMessageEvents
           → IncomingFilter 过滤（中文标点? / 正文汉字占比? / 有英文吗? / 忽略规则? / 自己的回显?）
           → 线程池 POST https://api.deepseek.com/chat/completions（带上术语表）
           → 回到客户端主线程 → Hud.getChat().addClientSystemMessage("[译] …")

发送消息:  回车 → Fabric ClientSendMessageEvents.ALLOW_CHAT
           → 不含汉字？直接放行（英文原样发出）
           → 含汉字？取消本次发送 → 异步翻译 → 主线程用 ClientPacketListener.sendChat(英文) 发出去
```

几个实现上的选择：

- **为什么译文另起一行，而不是把原消息替换掉？** 翻译是异步的（几百毫秒到几秒），而聊天栏消息一旦显示就无法就地修改文字；另起一行最稳，也不会破坏服务器原来的颜色/点击事件。
- **为什么不用 Mixin？** 26.2 的 Fabric API 已经提供了收发聊天的全部事件，模组**零 Mixin**，对游戏版本更新更耐受，也几乎不可能与其它模组冲突。
- **HTTP 只用 `HttpURLConnection`**（`java.base` 模块），不依赖 `java.net.http`，避免 Mojang 精简版运行时缺少模块导致崩溃。

## 7. 费用 / 限流

- 只有**真正需要翻译**的消息才会请求 API：中文消息、像英文的服务器消息；纯中文的收到的消息、你打的英文、重复消息（命中缓存）都不花钱。
- 默认每分钟最多 40 次请求，超出直接跳过（不会排队堆积）。
- `deepseek-chat` 的聊天翻译开销极小，正常游玩一天通常只是几分钱量级，具体价格以 <https://api-docs.deepseek.com/quick_start/pricing> 为准。

## 8. 常见问题

**聊天栏提示「未配置 DeepSeek API Key」**
执行 `/hxtranslate key sk-xxx`，或编辑 `config/hxtranslate.json` 后 `/hxtranslate reload`。

**提示 401 / 402 / 429**
401 = Key 无效；402 = DeepSeek 账户余额不足；429 = 请求太频繁（调小 `requestsPerMinute`）。

**我发中文后要等一秒才发出去**
正常现象：模组先取消原发送，等翻译结果回来再发，期间聊天栏会显示「翻译中…」。

**翻译失败会怎样？**
**不会吞掉你的消息**：会自动按中文原文发出去，并在聊天栏红字提示失败原因。

**服务器里出现的消息太多，翻译刷屏 / 太费钱**
把“收到的消息翻译”关掉：`/hxtranslate incoming off`，或调低 `requestsPerMinute`、往 `ignorePatterns` 里加正则。

**玩家喊话没被翻译 / 有些消息没有译文**
先 `/hxtranslate debug on`，模组会逐条打印是「正在翻译」还是「跳过（原因）」。常见原因：

- 提示「未配置 API Key」→ 去配置 Key；
- 提示「超出每分钟限流」→ 调大 `requestsPerMinute`；
- 提示「含中文标点 / 已经是中文」→ 这条本来就是中文（服务器按你的客户端语言本地化过）；
- 提示「命中 ignorePatterns」→ 你的忽略正则把它挡了。

> v1.0.0 有个已知 bug：中文客户端收到的英文喊话带着本地化的 `[红队]` 前缀，被误判成中文而整条跳过。**v1.0.1 已修复**，请升级。

**`obby` / `dia` / `u def` / `inc` 这类缩写没有被翻译**
v1.0.1 起内置了约 50 条 Bed Wars 术语表并要求模型按含义翻译。如果还有不认识的缩写，
直接往配置的 `glossary` 里加一条（例如 `"gapple=金苹果"`），然后 `/hxtranslate reload` 即可生效。

**在 Hypixel 用会被封号吗？**
本模组只做「读取聊天 + 代替你发送你亲手输入的文本」，不会自动操作游戏、不会自动刷屏，属于常见的聊天辅助类客户端模组。但 Hypixel 的模组政策由服务器单方面解释，请自行阅读其 *Allowed Modifications* 并自行承担风险。

**换了别的服务器 / 单机还能用吗？**
能。它只监听客户端聊天事件，和具体服务器无关。

## 9. 从源码构建

需要 **JDK 25**：

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew build
# 产物: build/libs/hx-chat-translator-<版本>.jar
```

只用到了 Fabric API（`fabric-message-api-v1` / `fabric-key-mapping-api-v1` / `fabric-command-api-v2` / `fabric-lifecycle-events-v1`），无需额外依赖。

### 离线自检（不需要启动游戏）

`tools/VerifyCore.java` 会用本地 mock HTTP 服务验证语言判断、命令拆解、DeepSeek 请求体与各种错误分支：

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build
LIBS=/path/to/gson.jar:/path/to/slf4j-api.jar:/path/to/fabric-loader.jar
javac -encoding UTF-8 -cp "build/classes/java/main:$LIBS" -d build/verify tools/VerifyCore.java
java -cp "build/classes/java/main:build/verify:$LIBS" VerifyCore
```

## 10. 许可

MIT。
