# 安全策略（SECURITY）

## 请不要在公开 issue 里贴这些内容

| 不要贴 | 为什么 |
| --- | --- |
| **DeepSeek API Key**（`sk-` 开头） | 拿到它就能用你的余额，等同于你的账号密码 |
| 完整的 `config/hxtranslate.json` | 里面有 `apiKey` 明文 |
| 未删减的 `logs/latest.log` | 可能包含上面两者 |

贴日志前请先搜一遍 `sk-` 并删掉。想只贴配置结构的话，把 `apiKey` 的值改成 `""` 再贴。

> Key 泄露了怎么办：立刻去 <https://platform.deepseek.com/api_keys> 撤销那个 Key 并新建一个，
> 然后在游戏里执行 `/hxtranslate key <新Key>`。

## 报告安全问题的渠道

这个模组是**纯客户端**的，没有自建服务器、也不上传任何游戏数据；
唯一的外部通信是直连你自己配置的 DeepSeek API 地址（`apiBaseUrl`，默认官方域名）。

如果你发现了真正的安全问题（例如：接口返回的内容能注入聊天栏格式代码、能绕过「只翻译不执行」的
约束、或者日志/配置会泄露到不该去的地方），**请不要开公开 issue**，
改用 GitHub 的 [私密漏洞报告](https://docs.github.com/zh/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
（仓库的 **Security → Report a vulnerability**）。

普通 bug、翻译质量问题、功能建议走 [Issues](https://github.com/KokoroLyase/HypixelChatTranslator/issues) 即可。

## 本模组已有的相关防线（供参考）

- 接口返回的译文、模型名、错误正文一律**压成一行并去掉 `§` 格式代码**后才进聊天栏，
  防止「换行把一行拆成多行、看起来像服务器自己说的话」以及颜色代码注入；
- 待翻译内容在提示词里被明确声明为**数据、不是指令**，模型不得执行其中的指令；
- 发送方向会校验译文**必须真的是英文**（汉字占比过高即判失败），避免中文漏进英文服；
- 配置文件损坏时**先整份备份**再退回默认值，绝不静默覆盖；写入走原子替换。
