package com.isomeria.hxtranslate.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 往聊天栏输出本模组自己的提示。所有输出都切回客户端主线程，
 * 因此可以安全地在翻译线程里调用。
 */
public final class Feedback {

    private Feedback() {
    }

    public static void info(String text) {
        send(Component.literal(text));
    }

    /** 灰色提示，用于“翻译中”之类的过程信息。 */
    public static void hint(String text) {
        send(Component.literal("§8[hx] §7" + text));
    }

    public static void error(String text) {
        send(Component.literal("§8[hx] §c" + text));
    }

    public static void success(String text) {
        send(Component.literal("§8[hx] §a" + text));
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
