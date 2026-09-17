package com.isomeria.hxtranslate.chat;

import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.util.LangUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * {@link FeedbackPort} 的生产实现：往聊天栏 / 物品栏上方输出本模组自己的提示。
 *
 * <p>所有输出都切回客户端主线程（{@code Minecraft.execute}），因此可以在翻译线程里调用。
 * 这是模组里**唯一**接触 Minecraft 聊天渲染的地方；翻译逻辑只用 {@link FeedbackPort}。
 *
 * <p>v2.2.2 起所有文本在这里**压成一行**（换行会把一条提示拆成好几行，看起来像服务器自己说的话）。
 * <b>v3.0.4 起不再剥 {@code §} 格式代码</b>：那道清洗会把前缀与调用方高亮的颜色一并剥掉，
 * 而不可信内容在 {@code DeepSeekClient} 里已经清过（详见 {@link #clean}）。
 */
public final class GameFeedback implements FeedbackPort {

    @Override
    public void info(String text) {
        send(Component.literal(clean(text)));
    }

    @Override
    public void hint(String text) {
        send(Component.literal(ChatTranslator.CHAT_PREFIX + " §7" + clean(text)));
    }

    @Override
    public void error(String text) {
        send(Component.literal(ChatTranslator.CHAT_PREFIX + " §c" + clean(text)));
    }

    @Override
    public void success(String text) {
        send(Component.literal(ChatTranslator.CHAT_PREFIX + " §a" + clean(text)));
    }

    /**
     * 在物品栏上方显示一行提示（action bar），不占用聊天栏。
     *
     * <p>「翻译中…」这类过程提示用它，避免把聊天内容顶上去。
     */
    @Override
    public void actionBar(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> {
            if (minecraft.gui == null || minecraft.gui.hud == null) {
                return;
            }
            minecraft.gui.hud.setOverlayMessage(Component.literal(clean(text)), false);
        });
    }

    /**
     * 兜底版式：把整行压成一行（换行会被原版 {@code StringSplitter.splitLines} 拆成
     * **多条独立聊天行**，译文那行会因此丢掉 {@code [译]} 前缀，看起来就像服务器自己说的话）。
     *
     * <p><b>这里刻意不再剥 {@code §} 格式代码</b>（2026-09-17 审计修正）。原来的实现对整行调
     * {@link LangUtils#sanitizeOneLine}，把调用方自己拼的颜色也一起剥掉了 ——
     * {@code GameFeedback} 拿到的字符串里，前缀（{@code config.incomingPrefix} /
     * {@code outgoingPrefix} / {@code CHAT_PREFIX}）和调用方的高亮都是**有意的**，
     * 而不可信内容（译文、模型名、错误正文）在 {@code DeepSeekClient} 里**已经**被
     * {@code cleanApiText} 清洗过。实际后果是聊天栏里每条译文都变成没有颜色的
     * {@code [译] …}，README 写着的「前缀支持 § 颜色代码」等于失效。
     *
     * <p>这道防线该守的仍然守着：{@link LangUtils#singleLineLayout} 丢掉 {@code \n} / {@code \r}
     * 与其它控制字符，所以「恶意内容把一行拆成两行、伪造没有前缀的服务器消息」依旧不可能。
     * 分工写在 {@code LangUtils#singleLineLayout} 的 javadoc 里。
     */
    private static String clean(String text) {
        return LangUtils.singleLineLayout(text);
    }

    private static void send(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> {
            if (minecraft.gui == null || minecraft.gui.hud == null) {
                return;
            }
            minecraft.gui.hud.getChat().addClientSystemMessage(component);
        });
    }
}
