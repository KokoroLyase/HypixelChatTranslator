package com.isomeria.hxtranslate.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * {@link FeedbackPort} 的生产实现：往聊天栏 / 物品栏上方输出本模组自己的提示。
 *
 * <p>所有输出都切回客户端主线程（{@code Minecraft.execute}），因此可以在翻译线程里调用。
 * 这是模组里**唯一**接触 Minecraft 聊天渲染的地方；翻译逻辑只用 {@link FeedbackPort}。
 */
public final class GameFeedback implements FeedbackPort {

    @Override
    public void info(String text) {
        send(Component.literal(text));
    }

    @Override
    public void hint(String text) {
        send(Component.literal("§8[hx] §7" + text));
    }

    @Override
    public void error(String text) {
        send(Component.literal("§8[hx] §c" + text));
    }

    @Override
    public void success(String text) {
        send(Component.literal("§8[hx] §a" + text));
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
            minecraft.gui.hud.setOverlayMessage(Component.literal(text), false);
        });
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
