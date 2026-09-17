package com.isomeria.hxtranslate.forge;

import com.isomeria.hxtranslate.Log;
import com.isomeria.hxtranslate.chat.ChatClientPort;
import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.forge.asm.HxHooks;
import com.isomeria.hxtranslate.util.LangUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.UUID;

/**
 * {@link ChatClientPort} 的 Forge 1.8.9 实现，同时负责把 {@link ChatTranslator}
 * 挂到 Forge 事件上。
 *
 * <p>与 Fabric 线的差异（都是 1.8.9 的平台能力所限，不是偷懒）：
 * <ul>
 *   <li><b>接收只有一条事件</b>：{@code ClientChatReceivedEvent} 覆盖服务器发来的**所有**聊天
 *       （玩家聊天、{@code /say}、系统公告、以及 type=2 的物品栏上方提示）。1.8.9 不分
 *       「签名聊天」与「系统消息」，所以 Fabric 线那两条链路在这里合成一条。</li>
 *   <li><b>拿不到发送者</b>：{@code S02PacketChat} 只带 {@code IChatComponent} + {@code type}，
 *       没有 UUID 也没有名字 —— 1.8.9 的聊天是不签名的，发送者是服务端渲染进文本里的。
 *       所以 {@code isLocalPlayer} 恒为 false、发送者信息一律传 null。
 *       这不是缺陷：Hypixel 是代理服，在 Fabric 线上走的**也正是**这条「没有发送者」的路径，
 *       判断「是不是自己」本来就靠正文比对与说话人名字（EchoMatcher）。</li>
 *   <li><b>发送靠字节码注入</b>：见 {@code asm/} 包。这里只实现闸门。</li>
 * </ul>
 */
public final class ForgeClient implements ChatClientPort {

    private ChatTranslator translator;

    /** 补上翻译器引用（和 Fabric 线一样，用来打破「翻译器 ↔ 端口」的构造环）。 */
    public void bind(ChatTranslator newTranslator) {
        this.translator = newTranslator;
    }

    /**
     * 注册事件与发送闸门。
     *
     * <p>契约：接收方向**必须**接 {@code ClientChatReceivedEvent}（唯一一条链路）；
     * 发送方向必须把闸门交给 {@code HxHooks}（字节码注入点的唯一入口）。
     */
    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        HxHooks.setGate(new HxHooks.SendGate() {
            @Override
            public boolean onSendChatMessage(String message) {
                return interceptSend(message);
            }
        });
    }

    /**
     * 注入点回调：决定要不要拦下这次原版发送。
     *
     * <p>1.8.9 里聊天与命令走的是同一个方法，所以命令要靠开头的 {@code /} 自己分流 ——
     * Fabric 线是两条事件，这里是一条。{@code onSendChat} 收到的是不带斜杠的正文，
     * {@code onSendCommand} 收到的也是不带斜杠的命令（两边都靠 {@code CommandMessage} 拆解）。
     */
    private boolean interceptSend(String message) {
        ChatTranslator current = translator;
        if (current == null || message == null) {
            return false;
        }
        boolean allow;
        try {
            if (message.startsWith("/")) {
                allow = current.onSendCommand(message.substring(1));
            } else {
                allow = current.onSendChat(message);
            }
        } catch (RuntimeException e) {
            Log.LOGGER.error("发送拦截出错，本条原样放行: {}", e.toString());
            return false;
        }
        // 注入点在原版发送之前：返回 true = 取消原版发送（翻译完由模组重发）
        return !allow;
    }

    /** 服务器发来的所有聊天都从这里进（type 2 是物品栏上方的状态提示）。 */
    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        ChatTranslator current = translator;
        if (current == null || event == null || event.message == null) {
            return;
        }
        IChatComponent component = event.message;
        // 1.8.9 的 getUnformattedText() 会把 § 格式代码一并带出来（现代版本的
        // Component.getString() 不会），而 Fabric 线送进过滤器的正是没有 § 的纯文本。
        // 为了两条线的判定完全一致，这里统一剥掉格式代码。
        String text = LangUtils.stripFormattingCodes(component.getUnformattedText());
        boolean overlay = event.type == 2;
        // senderId / senderName 一律为 null：1.8.9 拿不到发送者（见类注释）
        current.onIncoming(text, overlay, false, null, null);
    }

    // ------------------------------------------------------------------
    // ChatClientPort
    // ------------------------------------------------------------------

    @Override
    public String localPlayerName() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.thePlayer == null) {
            return null;
        }
        return minecraft.thePlayer.getName();
    }

    @Override
    public boolean isLocalPlayer(UUID senderId) {
        // 1.8.9 的聊天事件不携带发送者，永远走不到这里；保留实现是为了接口完整，
        // 并明确「这里不能拿来做身份判断」。
        return false;
    }

    @Override
    public void execute(Runnable task) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }
        minecraft.addScheduledTask(task);
    }

    @Override
    public Object currentConnection() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft == null ? null : minecraft.getNetHandler();
    }

    @Override
    public boolean isSameConnection(Object connection) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return false;
        }
        NetHandlerPlayClient current = minecraft.getNetHandler();
        return current != null && current == connection;
    }

    @Override
    public boolean sendChat(String payload) {
        EntityPlayerSP player = player();
        if (player == null) {
            Log.LOGGER.warn("跳过发送：当前没有连接");
            return false;
        }
        return send(player, payload, "聊天");
    }

    @Override
    public boolean sendCommand(String command) {
        EntityPlayerSP player = player();
        if (player == null) {
            Log.LOGGER.warn("跳过发送：当前没有连接");
            return false;
        }
        String payload = command.startsWith("/") ? command : "/" + command;
        return send(player, payload, "命令");
    }

    /**
     * 统一发送出口：置「本次是模组自己发的」标志，发送，再复位。
     *
     * <p>必须走原版 {@code sendChatMessage}，这样才能经过服务器要求的正常发送路径；
     * 也因此必须让注入点放行，否则「模组发送 → 又被闸门拦下 → 再翻译」会无限递归。
     */
    private boolean send(EntityPlayerSP player, final String payload, String kind) {
        HxHooks.setProgrammatic(true);
        try {
            player.sendChatMessage(payload);
            return true;
        } catch (RuntimeException e) {
            Log.LOGGER.error("发送翻译结果失败({}): {}", kind, e.toString());
            return false;
        } finally {
            HxHooks.setProgrammatic(false);
        }
    }

    private EntityPlayerSP player() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft == null ? null : minecraft.thePlayer;
    }
}
