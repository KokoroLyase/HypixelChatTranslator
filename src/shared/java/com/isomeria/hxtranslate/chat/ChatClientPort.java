package com.isomeria.hxtranslate.chat;

import java.util.UUID;

/**
 * 客户端（Minecraft / Fabric）与翻译逻辑之间的唯一接缝。
 *
 * <p>存在的理由：{@link ChatTranslator} 里真正容易出错的不是「怎么跟游戏打交道」，
 * 而是「收到这条消息该不该翻、翻完发不发、发不出去算不算失败、两条中文谁先发」这类
 * **决策与顺序**。这些逻辑以前和 Minecraft 直接耦合在一起，于是离线自检碰不到它们 ——
 * 历史上 v1.0.5（连打两条中文乱序）、v1.0.7（命中缓存的第二条插队）、
 * v1.0.8（限流时把中文原文发到英文服）、v1.1.3（发送失败仍计入统计）全都是这一类 bug，
 * 而它们一条自动化用例都没有。
 *
 * <p>把这些能力收成这个接口后：
 * <ul>
 *   <li>生产实现是 {@link GameClient}（薄适配层，唯一 import Minecraft 的地方）；</li>
 *   <li>离线自检用假的实现，就能确定性地驱动整条发送/接收链路，
 *       包括「提交时在 A 服务器、回调回来时已在 B 服务器」这种真实但难复现的场景。</li>
 * </ul>
 *
 * <p><b>约定</b>：实现里的 {@link #execute(Runnable)} 必须把任务放到客户端主线程执行
 * （回调来自翻译线程，碰游戏状态必须切线程）。因此 {@link #sendChat} 等发送方法
 * <b>只能在主线程调用</b>；{@link #localPlayerName()} / {@link #isLocalPlayer} 不限制线程。
 */
public interface ChatClientPort {

    /**
     * 本地玩家的游戏名；还没进入世界时为 {@code null}。
     *
     * <p>用于「这条消息是不是我自己发的」判断（比正文比对可靠，见 {@code EchoMatcher}）。
     */
    String localPlayerName();

    /**
     * 这条签名聊天是不是本地玩家自己发的。
     *
     * <p>能给出发送者 UUID 的链路（签名聊天）用它，比字符串比对可靠；
     * 代理服（Hypixel）走系统聊天拿不到发送者，那边靠 {@code EchoMatcher} 兜底。
     */
    boolean isLocalPlayer(UUID senderId);

    /** 把任务切到客户端主线程执行；客户端已经关闭时静默忽略。 */
    void execute(Runnable task);

    /**
     * 当前是不是在**单人（单机）世界**里。
     *
     * <p>为什么这个判断必须由装配层给出：{@code ChatTranslator} 不许 import 任何游戏类
     * （两个构建编译同一份，且离线自检要能完整驱动它的决策），所以「怎么问游戏」留在各线的
     * 装配层，而「拿到这个事实之后要不要翻译」的决策留在共享层 —— 这样单人闸门才能被离线
     * 自检直接测到（自检的假实现随便设这个值）。
     *
     * <p>两条线的实现不同，但语义必须一致：**只有「本地开着集成服务端的单人世界、
     * 且还没开放到局域网」才算 true**。
     * <ul>
     *   <li>Fabric 26.3：{@code Minecraft.hasSingleplayerServer()}
     *       <b>且</b> {@code !getSingleplayerServer().isPublished()}；</li>
     *   <li>Forge 1.8.9：{@code Minecraft.isSingleplayer()}
     *       <b>且</b> {@code !getIntegratedServer().getPublic()}。</li>
     * </ul>
     *
     * <p><b>为什么必须排除「对局域网开放」</b>（2026-09-17 审计修正）：两条线的
     * {@code hasSingleplayerServer()} / {@code isSingleplayer()} 都**不看**开放标志
     * （已用 {@code javap} 逐条核对字节码），于是 LAN 存档会被判成单人，配合默认
     * {@code translateInSingleplayer=false} 就是整条链路全拦 —— 而 README §5 / §8
     * 明写「对局域网开放的存档仍然翻译」。那种场景有别的玩家在说话，属于多人，必须翻译。
     *
     * <p>还没进入世界时返回 {@code false}（没进世界时本来就不会有消息要翻）。
     */
    boolean isSingleplayer();

    /**
     * 这条消息是不是仍然连着「发起翻译时的那条连接」。
     *
     * <p>发送方向必须检查：翻译要几百毫秒，期间玩家可能切服/退世界，
     * 那样绝不能把结果发到别的服务器去。连接已断开也返回 false（玩家已经在主菜单了）。
     */
    boolean isSameConnection(Object connection);

    /** 当前连接的身份标记；还没进入世界时为 {@code null}。只用于传给 {@link #isSameConnection}。 */
    Object currentConnection();

    /**
     * 真正把内容发到服务器。
     *
     * <p>必须屏蔽自身重入：模组自己调用 sendChat/sendCommand 时，游戏会再次触发
     * 「玩家发送消息」事件，不屏蔽就会无限递归。这件事由实现负责
     * （生产实现用 {@code ClientPacketListener.sendChat}，那条链路会再次触发事件）。
     *
     * @return {@code false} 表示没发出去（例如底层抛异常）。调用方**不能**再记一条
     *         「发出译文」，也不能打一行「[→EN] …」的回显 —— 否则聊天栏和统计都会
     *         声称一条根本没发出去的消息已经发出。
     */
    boolean sendChat(String payload);

    /** 同 {@link #sendChat}，但作为命令发送（payload 不含前导斜杠）。 */
    boolean sendCommand(String command);
}
