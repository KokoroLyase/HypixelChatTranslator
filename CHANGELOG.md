# 更新日志

## v1.0.2 — 2026-09-13

修复玩家反馈的「我喊话的内容不会被翻译」，并系统性排查了 Hypixel 所有聊天场景。

### 修复：`/shout` 喊话的中文不被翻译

**现象**：用 `/shout 致我们伟大的末地石建筑` 喊话，服务器里显示的仍是中文，外国人看不懂。

**根因**：模组只翻译「命令名单」里列出的命令正文，而 v1.0.1 的名单只写了
`msg` / `tell` / `w` / `whisper` / `r` / `reply` / `pc` / `gc` / `ac` / `achat` / `chat`，
**漏掉了 `/shout`**（以及 `/message`、`/pchat`、`/gchat`、`/ochat`），
所以喊话整条被原样放行。

**修复**：按 [Hypixel 官方命令表](https://hypixel.fandom.com/wiki/Commands) 重新整理了完整的聊天命令名单：

| 场景 | 命令 |
| --- | --- |
| 局内喊话 | `/shout` |
| 全局聊天 | `/ac`、`/achat` |
| 队伍聊天 | `/pc`、`/pchat`、`/party chat` |
| 公会聊天 | `/gc`、`/gchat`、`/guild chat` |
| 公会官员聊天 | `/oc`、`/ochat` |
| 私聊 / 好友私信 | `/msg`、`/message`、`/tell`、`/w`、`/whisper` |
| 回复私聊 | `/r`、`/reply` |

同时把命令识别做成三层，避免以后再漏：

1. **显式名单** —— 一眼能看出正文在哪里的命令；
2. **管理/聊天二义性命令** —— `/party invite Steve` 是管理不动它，`/party chat 大家好` 是发消息要翻译；
3. **未知命令兜底** —— 名单外的命令（Hypixel 以后新增的），只要正文明显是一句中文就翻译；
   `tp`、`f add`、`report`、`visit` 这类参数是玩家名的命令由 `protectedCommands` 排除。

### 修正：`/chat` 不该在名单里

`/chat` 是切换聊天频道的命令（`/chat a|p|g|o`），不是发消息。v1.0.1 误收录了它，现已移除。

### 排查过的其它场景（确认无问题）

- 普通玩家消息 `[MVP+] Steve: hi`、带本地化队伍名的 `[MVP+] [红队] Steve: hi`；
- 喊话回显 `[喊话] [黄队] Isomeria: ...`（自己的消息不会被重复翻回中文）；
- 队伍/公会/官员频道消息 `Party > Steve: hi`、`Guild > Steve: hi`；
- 好友私聊 `From Steve: hi`、自己的 `To Steve: hi`；
- 服务器本地化的中文播报（击杀、购买、经验）不会被误翻；
- 签名玩家聊天（`ClientboundPlayerChatPacket`）与代理系统聊天（`ClientboundSystemChatPacket`）
  两条链路都接了 Fabric 的 `CHAT` / `GAME` 事件。

### 兼容性

- 旧配置自动升级到 `configVersion` 3：**补齐**缺失的命令条目（所以老配置也能翻译 `/shout` 了），
  移除误收录的 `/chat`，补上新的判断名单；**不会覆盖**你自己调过的参数个数和提示词。
- 环境要求不变：MC 26.2 / Fabric Loader ≥ 0.19.3 / Fabric API 0.160.0+26.2 / Java 25。

### 测试

离线断言由 94 项增加到 **159 项**，新增 Hypixel 全部聊天命令的识别用例与老配置迁移用例：

```
/shout 致我们伟大的末地石建筑  ->  head="shout ", message="致我们伟大的末地石建筑"
/party chat 大家好            ->  head="party chat ", message="大家好"
/party invite 小明            ->  不翻译（管理命令）
/chat p                       ->  不翻译（切换频道）
/tp 小明                      ->  不翻译（玩家名参数）
/newchatcmd 大家快来这里集合   ->  翻译（未知命令兜底）
迁移后补上了 /shout、移除了 /chat、保留了原有条目
```

---

## v1.0.1 — 2026-09-13

修复玩家反馈的两个 bug。

### 修复：服务器玩家的英文喊话完全不翻译

**现象**：Hypixel 里玩家打的英文（`rush`、`yellow stop cheating!` 等）一条都不翻译，而英文的服务器消息（如 `+15 Bed Wars XP`）有时也不翻译。

**根因**：客户端语言是中文时，Hypixel 会把队伍名**本地化**后写进聊天内容里，英文喊话实际长这样：

```
[MVP+] [红队] Mguappe: rush
```

v1.0.0 的过滤器写的是「消息里含任何汉字就认为它已经是中文，直接跳过」，于是**所有带 `[红队]`/`[蓝队]` 的英文喊话都被丢掉**。（另外 `+15 Bed Wars XP` 不翻译是默认 `ignorePatterns` 有意过滤的经验刷屏，属正常行为。）

**修复**：把判断逻辑抽成独立的 `IncomingFilter`，改用三条信号，缺一不可：

1. 含中文/全角标点（。！？；，、」等）→ 判为中文；
2. **只看冒号后面的正文**的汉字占比 ≥ `chineseRatioThreshold`（默认 0.4）→ 判为中文；
3. 正文里拉丁字母太少 → 不是英文。

这样既能翻译 `[MVP+] [红队] Mguappe: rush`（汉字占比 0.09），也不会误翻 `isabellab2012被Venomed击杀。`（汉字占比只有 0.15，但含中文句号、且是服务器本地化消息）这类是真中文的消息。

### 修复：`U def` / `obby` / `dia` 等缩写不被翻译

**根因**：v1.0.0 的提示词里写的是「保留常见缩写」，而且没有任何术语表，模型就原样留下了这些词。

**修复**：

- 新增约 50 条 Bed Wars / Hypixel 术语表（`glossary` 配置项，`缩写=含义`），自动追加到「收到消息」方向的提示词里，并要求模型**按含义翻译成中文**，不要保留英文；
- 提示词明确要求保留 `[MVP+]`、`[红队]`、玩家名等服务器前缀，只翻译正文；
- 新增 `chineseRatioThreshold` 配置项，误判时可自行调整。

### 新增：排错能力

- `/hxtranslate debug on|off`：逐条打印每条消息是「正在翻译（汉字占比 x%）」还是「跳过（原因）」；
- `/hxtranslate status` 增加消息统计：收到 / 已翻译 / 跳过 / 失败 条数；
- 未配置 API Key 时不再静默失败：会以每分钟最多一次的频率在聊天栏红字提醒（以前是完全没反应，很难判断模组到底有没有工作）；
- 翻译失败、限流、命中忽略规则等分支都会记录跳过原因。

### 兼容性

- 旧版配置文件会被**自动升级**（`configVersion` 1 → 2）：只替换仍是老版默认值的提示词，并补上术语表；你自己改过的提示词不会被覆盖。
- 服务器端、Fabric API 版本要求不变（MC 26.2 / Loader ≥ 0.19.3 / Fabric API 0.160.0+26.2 / Java 25）。

### 测试

`tools/VerifyCore.java` 的离线断言从 63 项增加到 **94 项**，并新增了**直接取自玩家反馈截图的真实聊天样本回归**：

```
应翻译: [MVP+] [红队] Mguappe: rush
应翻译: [MVP+] [红队] Enimoria2013: blue u will delete by yellow so stop kil us
应跳过: isabellab2012被Venomed击杀。  (含中文标点)
应跳过: 团队 > Mguappe: 有人进攻！     (含中文标点)
应跳过: 你购买了金苹果                 (汉字占比 100%)
```

---

## v1.0.0 — 2026-09-13

首个版本。

- 收到服务器英文聊天 → 自动翻译成中文，追加显示在原消息下方；
- 输入含中文时自动翻译成英文再发送，输入纯英文则完全放行（不消耗 API）；
- 支持 `/msg`、`/r`、`/pc`、`/gc`、`/ac` 等命令正文翻译；
- LRU 翻译缓存、每分钟限流、译文超长截断、失败自动回退原文；
- 零 Mixin；`F6` 开关 + `/hxtranslate` 客户端命令；
- DeepSeek 客户端只依赖 `HttpURLConnection` + Gson。
