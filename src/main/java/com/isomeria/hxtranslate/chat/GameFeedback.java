package com.isomeria.hxtranslate.chat;

import com.isomeria.hxtranslate.util.LangUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * {@link FeedbackPort} 的生产实现：往聊天栏 / 物品栏上方输出本模组自己的提示。
 *
 * <p>所有输出都切回客户端主线程（{@code Minecraft.execute}），因此可以在翻译线程里调用。
 * 这是模组里**唯一**接触 Minecraft 聊天渲染的地方；翻译逻辑只用 {@link FeedbackPort}。
 *
 * <p>v2.2.2 起所有文本在这里**再清一遍**（去掉 {@code §} 格式代码、压成一行）：
 * 以前这条防线完全靠调用方自觉 —— 例如接口返回的错误正文、第三方中转站返回的模型名，
 * 都是**不可信输入**，一旦某个新调用方忘了清洗，{@code §} 就会变成颜色代码、
 * 换行会把一条提示拆成好几行（看起来像服务器自己说的话）。防线放在唯一出口最划算。
 */
public final class GameFeedback implements FeedbackPort {

    @Override
    public void info(String text) {
        send(Component.literal(clean(text)));
    }

    @Override
    public void hint(String text) {
        send(Component.literal("§8[hx] §7" + clean(text)));
    }

    @Override
    public void error(String text) {
        send(Component.literal("§8[hx] §c" + clean(text)));
    }

    @Override
    public void success(String text) {
        send(Component.literal("§8[hx] §a" + clean(text)));
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
     * 清洗不可信文本：去掉格式代码、压成一行。
     *
     * <p>2026-09-16 的审计结论：这里**不能**去猜「哪些 {@code §} 是自己拼的高亮、哪些来自接口」——
     * 调用方会把接口返回值拼进自己的高亮里（例如 {@code "可用模型: §f" + result.text()}），
     * 从字符串上无法区分来源。所以统一按不可信处理：全部剥掉。
     * 需要高亮的调用方应该只给「自己那一段」加色，接口文本先经
     * {@code DeepSeekClient} 的清洗与限量（见 {@code listModels()}）。
     */
    private static String clean(String text) {
        return LangUtils.sanitizeOneLine(text);
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
