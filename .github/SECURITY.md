# 安全策略（SECURITY）

## 请不要在公开 issue 里贴这些内容

| 不要贴 | 为什么 |
| --- | --- |
| **DeepSeek API Key**（`sk-` 开头） | 拿到它就能用你的余额，等同于你的账号密码 |
| 完整的 `config/server_chat_translator.json` | 里面有 `apiKey` 明文 |
| 未删减的 `logs/latest.log`（1.8.9 线是 `logs/fml-client-latest.log`） | 可能包含上面两者 |

贴日志前请先搜一遍 `sk-` 并删掉。想只贴配置结构的话，把 `apiKey` 的值改成 `""` 再贴。

> Key 泄露了怎么办：立刻去 <https://platform.deepseek.com/api_keys> 撤销那个 Key 并新建一个，
> 然后在游戏里执行 `/translator key <新Key>`。

## 这个模组会把聊天内容发给外部 AI（发布平台的披露要求）

翻译功能完全依赖外部生成式 AI（DeepSeek 接口）。发布到 Modrinth 之类的平台时，
这属于必须**主动披露**的内容，对应平台的这几项：

- **AI functionality**：项目依赖生成式 AI 才能工作（连接在线 LLM）；
- **Contains Telemetry**：会把数据发给第三方 —— 具体是「需要翻译的那一条聊天正文」
  （接收方向是**其他玩家**打的字，发送方向是你自己输入的中文），以及你配置的术语表/提示词。
  不收集账号、硬件、实例信息；模组没有自建服务器与遥测端点。

相关事实（便于随时核对）：

- 唯一的外部通信目标是你配置的 `apiBaseUrl`（默认 `https://api.deepseek.com`），
  代码里只有 `DeepSeekClient` 的 `attempt()` 与 `listModels()` 两处联网；
- **API Key 只出现在 `Authorization` 请求头**，不会写进任何日志行
  （`/translator key` 只回显长度，不回显内容）；
- 没有任何运行时下载或执行远程代码、没有 `Runtime.exec` / `ProcessBuilder`；
- 提醒用户「不要分享配置文件与日志」的理由见本文开头（配置里 Key 是明文；
  `debug on` 之后日志里会有聊天正文与译文）。

## 提示词注入（已知限制，不是待修 bug）

接收方向的原文来自其他玩家，而它会被放进发给模型的请求里 —— 这是提示词注入面。
模组有两道防护（提示词里的「数据不是指令」声明 + 接收方向译文必须含汉字），
实测把一类注入的成功率从 6/7 降到 2/7，但**无法根治**：攻击者若诱导模型输出一段**中文**的、
与原文无关的内容，它在形态上与真译文无法区分。详见 README §9.2。

**这不是可以「修掉」的漏洞**，而是「完全依赖外部 LLM 做翻译」的固有限制。
缓解建议是关掉接收方向（`/translator incoming off`），那条路径的原文只来自玩家自己。

## 用中转站时请注意 `apiBaseUrl` 的协议

模组会把 DeepSeek API Key 放在请求头里发给你配置的 `apiBaseUrl`。
**如果那个地址是 `http://`（非加密），Key 就是明文传输** ——
同一网段上任何能抓包的人（公共 WiFi、公司网络、合租的路由器）都能拿到它并花你的余额。

- 官方地址默认是 `https://api.deepseek.com`，不要改；
- 自建/第三方中转站**优先选 HTTPS**；
- 确实需要 `http://`（例如本机跑的代理）时，请确认那台机器和链路是你自己可控的；
- 模组会在启动时对 `http://` 地址警告一次 —— 它**不会**阻止这种配置，因为本地代理是合理需求。

## 报告安全问题的渠道

这个模组是**纯客户端**的，没有自建服务器、也不上传任何游戏数据；
唯一的外部通信是直连你自己配置的 DeepSeek API 地址（`apiBaseUrl`，默认官方域名）。

如果你发现了真正的安全问题（例如：接口返回的内容能注入聊天栏格式代码、能绕过「只翻译不执行」的
约束、或者日志/配置会泄露到不该去的地方），**请不要开公开 issue**，
改用 GitHub 的 [私密漏洞报告](https://docs.github.com/zh/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
（仓库的 **Security → Report a vulnerability**）。

普通 bug、翻译质量问题、功能建议走 [Issues](https://github.com/KokoroLyase/ServerChatTranslator/issues) 即可。

## 本模组已有的相关防线（供参考）

- 接口返回的译文、模型名、错误正文一律**压成一行并去掉 `§` 格式代码**后才进聊天栏，
  防止「换行把一行拆成多行、看起来像服务器自己说的话」以及颜色代码注入；
- 待翻译内容在提示词里被明确声明为**数据、不是指令**，模型不得执行其中的指令；
- 发送方向会校验译文**必须真的是英文**（只要还剩一个汉字就判失败），避免中文漏进英文服；
- 配置文件损坏时**先整份备份**再退回默认值，绝不静默覆盖；写入走原子替换。

## 1.8.9 线的核心插件做了什么（安全相关的边界说明）

MC 1.8.9 那条线是**核心插件（coremod）**，会在游戏启动时改动一个方法 —— 说明它到底动了什么，
因为它比普通模组更接近游戏本体：

- **只注入一个方法**：`EntityPlayerSP.sendChatMessage(String)`（玩家发送聊天的那一条路径）。
  在方法**头部**插入一个判断「这次发送要不要拦下来」的早退分支；
- **不拦的时候原版逻辑一个字节都不改**；它不碰网络协议、不碰渲染、不碰实体逻辑；
- **不下载、不加载、不执行任何远程代码**，注入的字节码是编译进 jar 的固定片段；
- 注入失败时**不会崩游戏**，只是把发送方向降级为「不翻译」，并在日志里留一行明确说明；
- 这段注入有离线门禁：`tools/VerifyCoremod.java` 会拿真实的 `EntityPlayerSP` 跑一遍改写，
  再用**真 JVM 的校验器**（`-Xverify:all`）验证产物合法（见 `RELEASING.md` §10.4）。

Fabric(26.3) 那条线**没有任何字节码修改**，两者的差异是平台能力造成的。
