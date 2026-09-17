<!--
  这是 Bug 反馈模板的「纯文本」版本，和 bug_report.yml（YAML 表单）同时存在。

  为什么要两份：
  - bug_report.yml 是 GitHub 现行的表单格式（必填项校验、下拉、预填代码块），
    新建 issue 页面优先使用它；
  - 这一份 Markdown 模板是兜底，同时它是**唯一**会被 GitHub「社区档案」接口
    （health_percentage 里的 issue_template 项）统计到的形式 —— 目录下的 YAML 表单
    不会被那个接口登记。两者是同一份内容，改动时请一起改。
-->

---
name: Bug 反馈
about: 模组没按预期工作时用这个模板
title: "[Bug] "
labels: bug
---

## 环境

- 我用的是哪条线（MC 26.3 + Fabric / MC 1.8.9 + Forge）：
- 模组版本（照抄文件名里的版本号，如 `2.3.0`）：
- 游戏与加载器版本（26.3 写 Fabric Loader + Fabric API；1.8.9 写 Forge 版本）：
- 客户端语言（中文 / 英文）：
- 服务器与场景（如 Hypixel Bed Wars 单排）：

> 模组版本与 MC 版本必须对得上：**v2.x 支持 MC 26.3 与 1.8.9 两条线**，26.2 请用
> [v1.1.3](https://github.com/KokoroLyase/HypixelChatTranslator/releases/tag/v1.1.3)。
> 下载地址见 [Releases](https://github.com/KokoroLyase/HypixelChatTranslator/releases)
> （Release 名带 `-mc<游戏版本>-<加载器>`，与 jar 文件名一一对应）。
>
> **1.8.9 的玩家请注意**：那一条线是核心插件（coremod）。如果「打中文没被翻译」
> 而其它功能（收消息翻译、F6、命令）都正常，多半是字节码注入没生效 ——
> `logs/latest.log` 里会有 `[server_chat_translator] EntityPlayerSP 字节码注入失败…`，请务必贴上那一行。

## 现象

<!-- 说清「你做了什么、期望什么、实际发生了什么」。有截图最好贴一张。 -->

## 游戏内诊断输出

先执行这两条命令，把输出贴上来（统计数字和跳过原因基本能定位到具体分支）：

```
/translator status
/translator debug on
```

`debug on` 之后复现一次，聊天栏会逐条打印「翻译 / 跳过（原因）」，把相关几行贴上来：

```

```

> 如果收到的东西**完全没有反应**（连「跳过」都没打印），先说一声 ——
> 那说明问题在收到消息之前，`status` 里的计数最能说明是哪种情况。

## 日志

`logs/latest.log` 里搜 `server_chat_translator` 的相关行（**贴之前请先删掉 API Key**）：

```

```
