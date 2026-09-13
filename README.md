# Hypixel 聊天翻译 (hx-chat-translator)

面向 **Minecraft Java 版 26.2 + Fabric** 的纯客户端聊天翻译模组。调用 **DeepSeek API** 做 AI 翻译：

- **别人打的英文 → 自动翻成中文**，作为一条附带消息显示在原消息下面；
- **你打的中文 → 自动翻成英文再发出去**，服务器里的外国人看到的是正常英文；
- **你打英文 → 完全不干预**，原样发送（不消耗任何 API 请求）。

> 专门针对 Hypixel 这类英文服务器设计，客户端安装即用，服务器无需安装任何东西。

> 仓库：<https://github.com/KokoroLyase/HypixelChatTranslator>
> 下载：见 [Releases](https://github.com/KokoroLyase/HypixelChatTranslator/releases)（也可以点 [Actions](https://github.com/KokoroLyase/HypixelChatTranslator/actions) 里任意一次成功构建，在 Artifacts 里下载）。

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
3. 把本模组 **`hx-chat-translator-1.0.0.jar`** 放进同一个 `mods` 文件夹：
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
/hxtranslate                 查看当前状态
/hxtranslate on|off          开关总闸
/hxtranslate incoming on|off 只控制「收消息翻译」
/hxtranslate outgoing on|off 只控制「发消息翻译」
/hxtranslate key <Key>       设置 DeepSeek API Key
/hxtranslate test <文本>      测试翻译一段文本（结果会打印在聊天栏）
/hxtranslate reload          重新读取配置文件并清空缓存
```

### 快捷指令里的中文也会被翻译

默认支持这些命令的**正文部分**翻译（命令名和玩家名保持原样）：

```
/msg 玩家 你好        →  /msg Player hello
/r 你好               →  /r hello
/pc 你好              →  /pc hello
/gc 你好              →  /gc hello
/ac 你好              →  /ac hello
```

想增删命令，改配置里的 `translateCommandArgs`：命令名（小写、不含斜杠）→ 正文前面还有几个参数。
例如 `/msg <玩家> <正文>` 是 `1`，`/r <正文>` 是 `0`。

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
| `incomingPrefix` | `§8[§b译§8] §f` | 译文前缀（支持 `§` 颜色代码） |
| `outgoingPrefix` | `§8[§a→EN§8] §f` | 自己发出去后的英文回显前缀 |
| `includeOriginalInIncoming` | `false` | 译文里是否再带上原文 |
| `minLatinLetters` | `2` | 至少几个拉丁字母才认为“像英文” |
| `maxIncomingChars` | `240` | 超过这个长度不翻译 |
| `maxOutgoingChars` | `256` | 译文最大长度（原版聊天框上限 256，超长会被服务器拒绝），超出会按词边界截断并加省略号 |
| `requestsPerMinute` | `40` | 每分钟最多请求次数（防刷屏烧钱） |
| `cacheSize` | `500` | 重复消息走缓存，不再花钱 |
| `httpTimeoutSeconds` | `20` | 请求超时 |
| `ignorePatterns` | 若干正则 | 命中的消息不翻译（服务器提示音效等） |
| `skipOwnEcho` | `true` | 自己发出的消息被服务器回显时不再翻回中文 |
| `showErrorsInChat` | `true` | 出错时在聊天栏提示 |
| `debugLog` | `false` | 往 `logs/latest.log` 写详细日志 |
| `incomingSystemPrompt` / `outgoingSystemPrompt` | 见文件 | 两个方向的提示词，可自行微调语气 |

## 6. 工作原理

```
收到消息:  ChatListener/系统消息 → Fabric ClientReceiveMessageEvents
           → 过滤（是否已是中文 / 是否像英文 / 是否命中忽略规则 / 是否自己的回显）
           → 线程池 POST https://api.deepseek.com/chat/completions
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

**在 Hypixel 用会被封号吗？**
本模组只做「读取聊天 + 代替你发送你亲手输入的文本」，不会自动操作游戏、不会自动刷屏，属于常见的聊天辅助类客户端模组。但 Hypixel 的模组政策由服务器单方面解释，请自行阅读其 *Allowed Modifications* 并自行承担风险。

**换了别的服务器 / 单机还能用吗？**
能。它只监听客户端聊天事件，和具体服务器无关。

## 9. 从源码构建

需要 **JDK 25**：

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew build
# 产物: build/libs/hx-chat-translator-1.0.0.jar
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
