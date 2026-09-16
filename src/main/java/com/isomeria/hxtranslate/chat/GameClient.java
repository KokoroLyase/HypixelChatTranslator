package com.isomeria.hxtranslate.chat;

import com.isomeria.hxtranslate.Log;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link ChatClientPort} 的生产实现，同时负责把 {@link ChatTranslator} 挂到 Fabric 事件上。
 *
 * <p>这是模组里唯一 import Minecraft / Fabric 事件的地方（另一个是 {@link GameFeedback}）。
 * 它只做三件事：把游戏对象翻译成朴素类型、把发送动作真正做掉、把事件注册好；
 * 所有决策逻辑都在 {@link ChatTranslator} 里，因此那部分可以被离线自检完整驱动。
 *
 * <p><b>为什么值得这么切</b>：以前「注册了哪几条事件」和「发送时怎么判定连接」都埋在
 * 逻辑里，只能靠人工看代码。现在注册清单集中在 {@link #register()} 一处，
 * 而发送/连接/身份判定在自检里有假实现对照着测。
 */
public final class GameClient implements ChatClientPort {

    private ChatTranslator translator;

    /**
     * 模组自己调用 sendChat/sendCommand 时要忽略事件，否则会无限递归。
     *
     * <p>从 ChatTranslator 里的 {@code volatile boolean} 改成 {@link ThreadLocal}：
     * 「压制自身事件」这件事的作用域就是**当前这一次发送的调用栈** ——
     * 在同一个线程里紧挨着设置与消费。用共享的 volatile 字段时，两次发送并发（或嵌套）会互相
     * 擦掉对方的标志位；ThreadLocal 天然按调用线程隔离，语义更准确。
     */
    private final ThreadLocal<Boolean> programmaticSend = ThreadLocal.withInitial(() -> false);

    /**
     * 补上翻译器引用。
     *
     * <p>之所以是后填而不是构造器注入：{@code ChatTranslator} 本身需要本对象作为端口，
     * 两者构成一个环。这里刻意用一次性的外部装配（{@code HxTranslateClient} 里建好翻译器后
     * 立刻调用）而不是公开的 setter —— 环里那个「先有鸡还是先有蛋」的问题只应该在一个地方解决。
     *
     * <p>自检里注册事件是可选的：用假的事件记录器时不需要翻译器，所以调用顺序不受限制。
     */
    public void bind(ChatTranslator translator) {
        this.translator = translator;
    }

    /** 事件回调里的兜底：还没绑定翻译器时（理论上不会发生）放行，绝不因为装配顺序崩游戏。 */
    private ChatTranslator translator() {
        return translator;
    }

    /**
     * 把翻译逻辑挂到 Fabric 的收发事件上。
     *
     * <p>契约（改动这里时必须逐条核对，离线自检盯着这个清单）：
     * <ul>
     *   <li>{@code ClientSendMessageEvents.ALLOW_CHAT} / {@code ALLOW_COMMAND}：
     *       发送方向的闸门，返回 false 表示「取消原发送，我来翻译后重发」；</li>
     *   <li>{@code ClientReceiveMessageEvents.GAME}：服务器下发的系统消息
     *       （Hypixel 的玩家聊天走这条，拿不到发送者）；</li>
     *   <li>{@code ClientReceiveMessageEvents.CHAT}：签名聊天（能给出发送者 UUID，
     *       判断「是不是自己」最可靠）。两条都接，少一条就有一半场景失效。</li>
     * </ul>
     *
     * <p>开关按键（F6）的装配在 {@code HxTranslateClient}，不在这里。
     */
    public void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(this::allowSendChat);
        ClientSendMessageEvents.ALLOW_COMMAND.register(this::allowSendCommand);
        ClientReceiveMessageEvents.GAME.register(this::onGameMessage);
        ClientReceiveMessageEvents.CHAT.register(this::onChatMessage);
    }

    private boolean allowSendChat(String message) {
        if (programmaticSend.get() || translator() == null) {
            return true;
        }
        return translator().onSendChat(message);
    }

    private boolean allowSendCommand(String command) {
        if (programmaticSend.get() || translator() == null) {
            return true;
        }
        return translator().onSendCommand(command);
    }

    private void onGameMessage(Component message, boolean overlay) {
        if (translator() == null) {
            return;
        }
        // 代理服（Hypixel）的玩家聊天是系统消息：没有发送者信息，传 null 让内容比对兜底
        translator().onIncoming(message.getString(), overlay, false, null, null);
    }

    private void onChatMessage(Component message, PlayerChatMessage signedMessage,
                               GameProfile sender, ChatType.Bound bound, Instant receivedAt) {
        if (translator() == null) {
            return;
        }
        UUID senderId = sender == null ? null : sender.id();
        String senderName = sender == null ? null : sender.name();
        translator().onIncoming(message.getString(), false, true, senderId, senderName);
    }

    // ------------------------------------------------------------------
    // ChatClientPort
    // ------------------------------------------------------------------

    @Override
    public String localPlayerName() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return null;
        }
        GameProfile profile = minecraft.player.getGameProfile();
        return profile == null ? null : profile.name();
    }

    @Override
    public boolean isLocalPlayer(UUID senderId) {
        if (senderId == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.player != null
                && minecraft.player.getUUID().equals(senderId);
    }

    @Override
    public void execute(Runnable task) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(task);
    }

    @Override
    public Object currentConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getConnection();
    }

    @Override
    public boolean isSameConnection(Object connection) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        ClientPacketListener current = minecraft.getConnection();
        return current != null && current == connection;
    }

    @Override
    public boolean sendChat(String payload) {
        ClientPacketListener connection = connection();
        if (connection == null) {
            Log.LOGGER.warn("跳过发送：当前没有连接");
            return false;
        }
        return send(() -> connection.sendChat(payload), "聊天");
    }

    @Override
    public boolean sendCommand(String command) {
        ClientPacketListener connection = connection();
        if (connection == null) {
            Log.LOGGER.warn("跳过发送：当前没有连接");
            return false;
        }
        String payload = command.startsWith("/") ? command.substring(1) : command;
        return send(() -> connection.sendCommand(payload), "命令");
    }

    /**
     * 统一发送出口：设置「本次是模组自己发的」标志，发送，再复位。
     *
     * <p>必须走原版 {@code ClientPacketListener.sendChat/sendCommand}，让客户端自己重新签名；
     * 也因此必须屏蔽自身事件，否则「模组发送 → 又触发发送事件 → 又要翻译」会无限递归。
     */
    private boolean send(Runnable action, String kind) {
        programmaticSend.set(true);
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            Log.LOGGER.error("发送翻译结果失败({}): {}", kind, e.toString());
            return false;
        } finally {
            programmaticSend.set(false);
        }
    }

    private ClientPacketListener connection() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getConnection();
    }
}
