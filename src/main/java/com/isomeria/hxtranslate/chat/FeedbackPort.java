package com.isomeria.hxtranslate.chat;

/**
 * 模组自己往聊天栏/物品栏上方输出的那一条条提示。
 *
 * <p>抽成接口有两个目的：一是让 {@link ChatTranslator} 不再直接依赖 Minecraft；
 * 二是**让离线自检能断言「玩家看到了什么」**—— 例如「没配 Key 时到底有没有给出提示」、
 * 「发送失败时那条假的「[→EN] …」回显有没有被误打出来」（v1.1.3 修的就是后者）。
 *
 * <p>生产实现是 {@link GameFeedback}：所有输出都切回客户端主线程，因此可以在翻译线程里调用。
 */
public interface FeedbackPort {

    /** 普通信息（翻译结果显示用）。 */
    void info(String text);

    /** 灰色提示，用于「翻译中」这类过程信息。 */
    void hint(String text);

    /** 红色错误提示。 */
    void error(String text);

    /** 绿色成功提示。 */
    void success(String text);

    /**
     * 在物品栏上方显示一行提示（action bar），不占用聊天栏。
     *
     * <p>「⏳ 翻译中…」用它，避免把聊天内容顶上去。
     */
    void actionBar(String text);
}
