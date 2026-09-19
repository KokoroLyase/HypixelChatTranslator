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

    /**
     * 合并显示（v3.1.0，{@code incomingDisplay=MERGE}）：把译文后缀追加到**原消息组件**后面，
     * 作为一个整体显示成一行。
     *
     * <p>为什么收的是原组件而不是拼好的字符串：原消息带着服务器给的样式、悬停提示与
     * 点击事件（Hypixel 的队伍前缀、玩家头衔都在上面），拍平成字符串重拼会把这些全丢掉。
     * 实现必须**复制原组件并在其后追加**，而不是重新渲染原文。
     *
     * <p>{@code originalComponent} 的具体类型由各装配层决定（Fabric 是 {@code Component}、
     * 1.8.9 是 {@code IChatComponent}），共享层只负责传递 —— 与
     * {@code ChatClientPort.currentConnection()} 返回 {@code Object} 是同一个先例。
     * 和其它输出一样，实现内部要切回客户端主线程。
     */
    void showMergedIncoming(Object originalComponent, String suffix);

    /**
     * 把被合并扣住的原消息原样显示出来（v3.1.0）。
     *
     * <p>MERGE 模式下原文会被取消显示、等译文回来一起合并；译文超时或失败时，
     * 必须用这个出口把原文放出去 —— **原文是玩家的原始聊天，任何情况下都不能丢**。
     * 实现同样要复制组件（别把共享层还持有的引用直接交给聊天栏再被改掉）。
     */
    void showOriginalIncoming(Object originalComponent);
}
