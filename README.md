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
3. 把本模组 **`hx-chat-translator-<版本>+mc26.2-fabric.jar`**（最新版见 [Releases](https://github.com/KokoroLyase/HypixelChatTranslator/releases/latest)）放进同一个 `mods` 文件夹：
   - Windows：`%appdata%\.minecraft\mods`
   - macOS：`~/Library/Application Support/minecraft/mods`
   - Linux：`~/.minecraft/mods`

   文件名里的 `mc26.2` 是游戏版本、`fabric` 是模组加载器，和大多数模组一样。下载后**不需要改名**，直接丢进 `mods` 即可。
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
/hxtranslate                 查看当前状态（含收发两个方向的消息统计）
/hxtranslate on|off          开关总闸
/hxtranslate incoming on|off 只控制「收消息翻译」
/hxtranslate outgoing on|off 只控制「发消息翻译」
/hxtranslate key <Key>       设置 DeepSeek API Key
/hxtranslate test <文本>      测试翻译一段文本（方向按内容判断：含中文＝中→英，结果打印在聊天栏）
/hxtranslate models          查询 DeepSeek 当前可用的模型名（接口改版时自查）
/hxtranslate debug on|off    排错模式：打印每条消息是「翻译」还是「跳过（原因）」
/hxtranslate reload          重新读取配置文件，并清空缓存、复位限流与熔断
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
| `model` | `deepseek-flash` | 模型。**2026-09 起 DeepSeek 只提供 `deepseek-flash` 与 `deepseek-v4-pro`**，旧的 `deepseek-chat` 已下线（升级时会自动改过来） |
| `enableThinking` | `false` | 是否开启思考模式。新模型**默认开启**，聊天翻译既慢又贵，所以默认显式关闭 |
| `temperature` | `0.7` | 采样温度；翻译要稳定，别调太高（思考模式下该参数不生效） |
| `retryOnFailure` | `true` | 429/5xx/网络抖动时自动重试一次；连续失败 5 次后熔断 60 秒 |
| `enabled` | `true` | 总开关 |
| `translateIncoming` | `true` | 翻译收到的英文 |
| `translateOutgoing` | `true` | 翻译自己发的中文（直接打出来的聊天；`/shout` 这类命令正文由 `translateCommandMessages` 控制） |
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
| `maxOutgoingChars` | `256` | 译文最大长度（原版聊天框上限 256，超长会被服务器拒绝），超出会按词边界截断并加省略号。**只能调小**：调大也不会真的发出去，反而可能被服务器踢（配置会被自动夹到 256） |
| `requestsPerMinute` | `60` | 每分钟最多请求次数（防刷屏烧钱）；超限时会在聊天栏提醒一次 |
| `maxPendingTranslations` | `20` | 排队中的翻译请求上限（背压）：接口变慢时超过这个数就先跳过新消息，避免延迟越滚越大 |
| `cacheSize` | `500` | 重复消息走缓存，不再花钱（**最小 16**，写更小的值会被夹到 16） |
| `httpTimeoutSeconds` | `15` | 读取响应超时（建立连接超时是 `connectTimeoutSeconds`，默认 5） |
| `ignorePatterns` | 若干正则 | 命中的消息不翻译：服务器提示音效、经验/代币刷屏、**横幅分隔线与游戏名**（`▬▬▬▬`、`Bed Wars` 这类，翻了没用还多花请求）。可自行增删 |
| `skipOwnEcho` | `true` | 自己发出（含直接打英文）的消息被服务器回显时不再翻回中文 |
| `failureFallback` | `CANCEL` | 发送方向翻译失败时：`CANCEL` = 不发送、只在聊天栏提示（默认）；`SEND_ORIGINAL` = 按中文原文发出去 |
| `blacklistedPlayers` | `[]` | 永不翻译这些玩家的消息（写游戏名即可），朋友是中国人时很有用 |
| `connectTimeoutSeconds` | `5` | 建立连接超时 |
| `showErrorsInChat` | `true` | 出错时在聊天栏提示 |
| `debugLog` | `false` | 调试模式：把每条消息的处理结果写进 `logs/latest.log` 并同步打印到聊天栏（`/hxtranslate debug on`） |
| `incomingSystemPrompt` / `outgoingSystemPrompt` | 见文件 | 两个方向的提示词（内含少样本示例），可自行微调语气 |
| `configVersion` | 当前版本号 | 配置结构版本，请勿手改；升级模组时会自动把老版提示词/术语表升级到新默认值，你自定义过的内容不会被覆盖 |

### 收到消息要不要翻译，是怎么判断的

Hypixel 的聊天内容很"脏"：同一个句子里可能既有中文又有英文。判断逻辑在 `IncomingFilter`，
按下面的顺序走（每一步都有取自真实截图的离线回归测试）：

| 顺序 | 信号 | 例子 |
| --- | --- | --- |
| 0 | 命中 `ignorePatterns` 或是自己的回显 → 跳过 | 经验/代币刷屏、自己刚发的消息 |
| 1 | 只看冒号后正文的**汉字占比** ≥ `chineseRatioThreshold` → 跳过 | `你购买了金苹果`（100%） |
| 2 | 正文有 **≥2 个英文信号词** → **翻译** | `3_0HY was thrown into a black hole by G19sy. 最终击杀！` |
| 3 | 正文有**连续 ≥2 个汉字** → 跳过 | `bedsyuu被Mlable击杀`（占比仅 0.2，但确属中文） |
| 4 | 还带中文/全角标点 → 跳过 | `_Moriarty__受到了ku_jo232的冷淡。` |
| 5 | 正文拉丁字母太少 → 跳过 | `？？？` |
| 6 | 其余 → **翻译** | `[喊话] [红队] Maceuser: rush mid` |

两个容易踩的坑（都已修，且写成了回归测试）：

- **不能"含汉字就跳过"**：中文客户端收到的英文喊话带本地化前缀 `[红队]`；
- **不能"含中文标点就跳过"**：`某某 was killed by 某某。最终击杀！` 这类英文播报带中文后缀。

### 关于"自己消息的回显"

判断回显用的是**正文完全一致**（`EchoMatcher`），不是"包含"。
早期版本用"包含"判断，只要你发过含 `u`、`so` 这种短片段的英文，之后别人任何包含该片段的喊话
都会被误判成"自己的回显"而静默丢掉 —— 这就是"有时喊话不翻译"的原因。
另外你**直接打英文**的消息也会被记住，所以服务器回显时不会再被翻成中文。

v1.0.7 起再加了一道 **15 秒时间窗**：只有 15 秒内自己发过的内容才用来认领回显。
（回显是紧接着发送到达的，往返通常不到 1 秒，所以该认的一条都不会漏。）

但**光看正文终究不够**：你自己说了句 `gg`，终局时别人也在同时打 `gg`，
时间窗挡不住，别人的 `gg` 就会被当成"你的回显"而全都不翻译。所以 **v1.1.1 起改为先认说话人名字**——
系统聊天里本来就写着谁在说话（`[VIP] KineticRules: gg`），名字和你的游戏名对得上才算你的消息；
对不上就照常翻译。只有在认不出格式时（例如 `Guild > Steve > hello`）才退回正文比对。
`To xxx:` 这种自己发出的私聊仍然识别为自己。

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
- **为什么进来的消息要同时接 `CHAT` 和 `GAME` 两条事件？** 别删掉任何一条。正常服务器的玩家聊天走签名聊天（`CHAT`，能拿到发送者，判断「是不是自己」最可靠）；而 Hypixel 是代理服，玩家聊天是以**系统消息**（`GAME`）下发的，那条链路拿不到发送者，只能靠内容与回显比对来过滤。只接一条就会有一半场景失效。
- **发送方向为什么要「取消原发送 → 异步翻译 → 自己重发」？** Fabric 的发送事件是同步回调，而网络请求要几百毫秒，不能在主线程里等；重发时必须走原版 `ClientPacketListener.sendChat`，让客户端自己重新签名。也因此必须有个 `programmaticSend` 开关把「自己发的」和「玩家发的」区分开，否则会无限递归。

## 7. 费用 / 限流

- 只有**真正需要翻译**的消息才会请求 API：中文消息、像英文的服务器消息；纯中文的收到的消息、你打的英文、重复消息（命中缓存）都不花钱。
- 默认每分钟最多 60 次请求，超出直接跳过；另有 `maxPendingTranslations` 背压，接口变慢时不会无限堆积。
- `deepseek-flash` 的聊天翻译开销极小，正常游玩一天通常只是几分钱量级，具体价格以 <https://api-docs.deepseek.com/quick_start/pricing> 为准。

## 8. 常见问题

**聊天栏提示「未配置 DeepSeek API Key」**
执行 `/hxtranslate key sk-xxx`，或编辑 `config/hxtranslate.json` 后 `/hxtranslate reload`。

**我明明配过 Key，提示却说没配 / 设置全变回默认了**
多半是配置文件被改坏了（手改 json 漏个逗号、写错类型）。模组会在聊天栏告诉你原因，
并把原文件**另存**为同目录下的 `hxtranslate.json.broken-<日期-时间>`，然后按默认设置运行 ——
把备份改名回 `hxtranslate.json`、修好里面的语法，再 `/hxtranslate reload` 就能恢复。
（v1.1.3 起才有备份；更早的版本遇到坏配置会直接按默认值覆盖掉原文件。）

**提示 401 / 402 / 429**
401 = Key 无效；402 = DeepSeek 账户余额不足；429 = 请求太频繁（调小 `requestsPerMinute`；模组本身会自动重试一次，连续失败 5 次会熔断 60 秒）。

**提示 400 / 模型不可用**
DeepSeek 会更换模型名（2026-09 就把 `deepseek-chat` 换成了 `deepseek-flash`）。执行
`/hxtranslate models` 看当前可用的模型名，再把配置里的 `model` 改成列表里的名字，然后 `/hxtranslate reload`。

**我发中文后要等一秒才发出去**
正常现象：模组先取消原发送，等翻译结果回来再发，期间物品栏上方会显示「⏳ 翻译中…」。

**我打了中文、紧接着又打了英文，服务器里的顺序好像反了**
正常现象：英文不需要翻译，会立刻发出去；中文要等翻译结果回来（通常一秒内）。
模组保证的是**两条需要翻译的消息之间**按你的输入顺序发出，中间夹的英文不会等。
如果这句英文必须排在中文后面，隔一秒再打即可。

**翻译失败会怎样？**
按 `failureFallback` 处理，默认 **`CANCEL`：这条不发送**，聊天栏红字提示失败原因，
按 `↑` 可以找回刚才输入的内容（避免中文原样发到英文服）。
想改成"失败就发原文"，把配置里的 `failureFallback` 改成 `SEND_ORIGINAL`。

> 这里说的"失败"包括全部 5 种情况：没配 Key、翻译失败、译文仍是中文、**被限流**、**队列积压**。
> 早期版本里最后两种会把中文原文直接发出去，v1.0.8 起统一。

**服务器里出现的消息太多，翻译刷屏 / 太费钱**
把“收到的消息翻译”关掉：`/hxtranslate incoming off`，或调低 `requestsPerMinute`、往 `ignorePatterns` 里加正则。

**玩家喊话没被翻译 / 有些消息没有译文**
先 `/hxtranslate debug on`，模组会逐条打印是「正在翻译」还是「跳过（原因）」。常见原因：

- 提示「未配置 API Key」→ 去配置 Key；
- 提示「超出每分钟限流」→ 调大 `requestsPerMinute`（同时也会在聊天栏提醒一次）；
- 提示「已经是中文 / 正文含成段中文 / 含中文标点」→ 这条本来就是中文（服务器按你的客户端语言本地化过）；
- 提示「自己消息的回显」→ 它认为这条是你刚发过的；提示里会带上匹配到的原文，便于核对；
- 提示「命中 ignorePatterns」→ 你的忽略正则把它挡了。

> 历史 bug（均已修复，请确保用最新版）：
> - v1.0.0：带本地化 `[红队]` 前缀的英文喊话被误判成中文 → **v1.0.1 修复**；
> - v1.0.1：`/shout` 等命令不在名单里 → **v1.0.2 修复**；
> - v1.0.2：回显判断用「子串包含」，发过 `u`、`so` 这种短词后别人的喊话会被误当成自己的回显丢弃 → **v1.0.3 修复**；
> - v1.0.2：英文播报带中文后缀（`... 最终击杀！`）被误判成中文 → **v1.0.3 修复**；
> - v1.0.5：连打两条中文时译文乱序（2 线程池谁先返回谁先发）→ v1.0.5 改成单线程 FIFO，
>   但「命中缓存的第二条会插队」这条捷径漏了 → **v1.0.7 补完**；
> - v1.0.6：自己发过的短译文（`wp`/`ty`/`omw`）会一直把别人说的同一句吞掉，表现为"有些人的 wp 不翻译" → **v1.0.7 加 15 秒时间窗**；
> - v1.0.6：玩家名里含英文常用词（如 `Im_Bad_At_PKMN` = im/bad/at）时，服务器本地化的**中文**播报被误判成英文句子翻了一遍 → **v1.1.1 修复**；
> - v1.0.6：回显只按正文比对，自己打了 `gg` 之后别人同时打的 `gg` 全被跳过、不翻译 → **v1.1.1 改为按说话人名字识别**；
> - v1.0.x：横幅里的游戏名（`Bed Wars`）被翻成 `[译] 起床战争`，没用还多花一次请求 → **v1.1.2 用默认 `ignorePatterns` 挡掉**；
> - v1.0.0：配置文件里没有 `configVersion` 字段，升级时被当成「已是最新版」，模型名永远停在已下线的 `deepseek-chat`（每条请求 400）→ **v1.1.3 按 v1 迁移**；
> - v1.0.x：配置文件里有个语法错误，模组只写日志、不备份，之后任意一次保存都会把用户的 Key/术语表/规则覆盖成默认值 → **v1.1.3 先备份再退回默认值，并改成原子写入**；
> - v1.0.x：接口返回的译文/模型名没做清洗，里面的换行会把一行拆成多行（译文那行会丢掉 `[译]` 前缀）、`§` 会变成颜色代码 → **v1.1.3 统一清洗**；
> - v1.0.x：`To view your stats, type: /stats` 这类服务器提示被当成「自己发的私聊」而永不翻译 → **v1.1.3 收紧为 `To <玩家名>: `**；
> - v1.0.x：发送失败时仍会统计成「已发出」并打一条假回显 → **v1.1.3 只在真的发出去后才计数**。

**自己发的中文没发出去，提示「模型没有译成英文」**
模型偶尔会把中文原样吐回来（短句、口语尤其容易）。这种译文发出去等于替你往英文服里发中文，
所以 v1.0.7 起直接判为失败，按 `failureFallback` 处理（默认不发送，可用 ↑ 找回内容）。
换个说法重发即可。

**自己发的英文被翻回中文了**
v1.0.3 起，你直接打英文（含 `/shout`、`/msg` 正文）也会被记入回显名单，服务器回显时不再翻译。

**`obby` / `dia` / `u def` / `inc` 这类缩写没有被翻译**
v1.0.1 起内置了 Bed Wars 术语表并要求模型按含义翻译，v1.0.3 扩充到约 70 条并加了少样本示例。
如果还有不认识的缩写，直接往配置的 `glossary` 里加一条（例如 `"gapple=金苹果"`），
然后 `/hxtranslate reload` 即可生效。

**在 Hypixel 用会被封号吗？**
本模组只做「读取聊天 + 代替你发送你亲手输入的文本」，不会自动操作游戏、不会自动刷屏，属于常见的聊天辅助类客户端模组。但 Hypixel 的模组政策由服务器单方面解释，请自行阅读其 *Allowed Modifications* 并自行承担风险。

**换了别的服务器 / 单机还能用吗？**
能。它只监听客户端聊天事件，和具体服务器无关。

## 9. 隐私与合规

- **聊天内容会发到 DeepSeek 的服务器**：开启的「收到翻译」会把**其他玩家**在游戏里说的话发送给 DeepSeek API 才能翻译 —— 这是本模组的工作原理，不是可选项。介意的话用 `/hxtranslate incoming off` 关掉接收方向，只保留你自己发消息时的翻译。
- **不会上传**账号、密码、坐标、背包等游戏数据；模组只读取聊天栏文本，并且只把需要翻译的那一条发出去。
- **API Key** 只存在你本机的 `.minecraft/config/hxtranslate.json`，只用于直连 DeepSeek。本模组没有任何自建服务器，不会把 Key 或聊天内容转发到别处。
- **不要**把配置文件或日志发给别人（里面有 Key）；仓库的 `.gitignore` 已排除本地配置。
- **服务器规则**：本模组只做「读聊天 + 代替你发送你亲手输入的文本」，不会自动操作游戏、不会自动刷屏。但个别服务器把「自动代发」视为宏，请自行查阅所在服务器规则（Hypixel 见 *Allowed Modifications*）。
- **DeepSeek 服务条款**：使用即表示你同意 <https://api-docs.deepseek.com/zh-cn/> 的条款与计费方式。

## 10. 从源码构建

需要 **JDK 25**：

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew build
# 产物: build/libs/hx-chat-translator-<版本>+mc26.2-fabric.jar
```

只用到了 Fabric API（`fabric-message-api-v1` / `fabric-key-mapping-api-v1` / `fabric-command-api-v2` / `fabric-lifecycle-events-v1`），无需额外依赖。

发布新版本（版本号规则、文件命名、保留旧版、配置迁移等约定）见 [RELEASING.md](RELEASING.md)。

### 离线自检（不需要启动游戏）

`tools/VerifyCore.java` 会用本地 mock HTTP 服务验证语言判断、命令拆解、DeepSeek 请求体与各种错误分支，
**并且已经接进 Gradle 构建**：`./gradlew build` 会顺带跑完（本地和 CI 用的是同一条命令），
失败会直接让构建红掉，所以不存在「忘了跑测试」这回事。

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build      # 构建 + 自动跑自检
JAVA_HOME=/path/to/jdk-25 ./gradlew verifyCore # 只跑自检
```

自检不依赖 Minecraft 运行时（`ChatTranslator` 里依赖游戏类的部分不在其中），几秒内跑完。
新增的纯逻辑（`util/` 下的过滤器、匹配器、解析器）都应该在这里补用例。开发环境里改完代码，
把自检跑绿再提交。

## 11. 许可

MIT。
