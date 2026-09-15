---
name: Bug 反馈
about: 模组没按预期工作时用这个模板
title: "[Bug] "
labels: bug
---

## 环境

- 模组版本（如 `v1.0.10`）：
- Minecraft / Fabric Loader / Fabric API 版本：
- 客户端语言（中文 / 英文）：
- 服务器与场景（如 Hypixel Bed Wars 单排）：

## 现象

<!-- 说清「你做了什么、期望什么、实际发生了什么」。有截图最好贴一张。 -->

## 游戏内诊断输出

先执行这两条命令，把输出贴上来（统计数字和跳过原因基本能定位到具体分支）：

```
/hxtranslate status
/hxtranslate debug on
```

`debug on` 之后复现一次，聊天栏会逐条打印「翻译 / 跳过（原因）」，把相关几行贴上来：

```

```

## 日志

`logs/latest.log` 里搜 `hxtranslate` 的相关行（**贴之前请先删掉 API Key**）：
