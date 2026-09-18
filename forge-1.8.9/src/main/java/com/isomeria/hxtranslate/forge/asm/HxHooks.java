package com.isomeria.hxtranslate.forge.asm;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 注入字节码与模组逻辑之间的唯一桥梁。
 *
 * <p><b>这个类会被极早加载</b>：它由被改造过的 {@code EntityPlayerSP} 直接调用，
 * 那时模组主体可能还没初始化完。所以它只依赖 {@code java.*}，静态初始化里
 * 一个 Minecraft 类都不许碰 —— 否则就是 {@code NoClassDefFoundError}，
 * 而且是 {@link Error}，调用方接不住。
 *
 * <p><b>递归闸门必须是 {@link ThreadLocal}</b>，不能改成普通字段：模组翻译完成后要
 * 再调一次 {@code sendChatMessage} 把英文发出去，那一次会再次经过注入点，必须被放行。
 * 「这次是我自己发的」这个事实的作用域精确等于**当前这一次发送的调用栈**；
 * 用共享的 volatile 字段时，两次发送并发（或嵌套）会互相擦掉对方的标志位。
 * Fabric 线的 {@code GameClient.programmaticSend} 是同一个理由（见那边的注释）。
 */
public final class HxHooks {

    /** 由装配层实现的闸门：返回 true 表示「拦下这次发送」。 */
    public interface SendGate {
        boolean onSendChatMessage(String message);
    }

    /**
     * 「这次发送是模组自己发起的」标志。
     *
     * <p>Fabric 线用的是 {@code ThreadLocal.withInitial(() -> false)}，
     * 那是 Java 8 的 API，这里手写匿名子类以保持同样的语义（并且没有额外的类加载）。
     */
    private static final ThreadLocal<Boolean> PROGRAMMATIC = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private static volatile SendGate gate;

    /**
     * 「发送闸门出错」这件事是否已经报过（v3.0.8）。
     *
     * <p>发送路径每条消息都会走一遍，反复往 {@code System.err} 打会把控制台刷满；
     * 但一次都不打又让「打中文没被翻译」变成完全无线索 —— 所以只报第一次。
     */
    private static final AtomicBoolean reportedSwallowed = new AtomicBoolean();

    /**
     * 「FML 至少把目标类交给过转换器」这个事实记录在哪个系统属性里（v3.0.8）。
     *
     * <p><b>为什么用系统属性，而不是 HxTransformer 里的一个静态字段</b>：核心插件的类
     * （{@link HxTransformer}）与模组类（{@code HxTranslateForge}）是否由同一个
     * {@code LaunchClassLoader} 加载，**离线无法验证** —— 而这个判断只用于「报警」，
     * 万一两边不是同一个类加载器，静态字段会让装配层读到「没见过目标类」，
     * 于是**每个玩家**都会看到一条「注入似乎没生效」的假警报。假警报比沉默更糟，所以
     * 这里刻意挑了一个 JVM 全局、与类加载器无关的载体。
     *
     * <p>反过来说，这条判断不会漏报：只有转换器**真的**见过目标类名才会置上它，
     * 没置上就说明 FML 从来没让我们碰过那个类（注入必然没生效）。
     */
    private static final String TARGET_SEEN_PROPERTY = "server_chat_translator.targetClassSeen";

    /** 由 {@link HxTransformer} 在类名命中时调用。 */
    public static void markTargetClassSeen() {
        System.setProperty(TARGET_SEEN_PROPERTY, "1");
    }

    /**
     * 本 JVM 里 FML 是否至少把目标类交给过转换器 —— 给装配层做一次性自检用。
     *
     * <p>装配层在「玩家实体已经建出来」的那一刻问一次：那时 {@code EntityPlayerSP}
     * 必然已经被加载过，转换器也就必然被叫过。答案是否，就说明注入静默失效了。
     */
    public static boolean sawTargetClass() {
        return "1".equals(System.getProperty(TARGET_SEEN_PROPERTY));
    }

    private HxHooks() {
    }

    /** 装配层注册闸门；传 {@code null} 表示不拦任何发送。 */
    public static void setGate(SendGate newGate) {
        gate = newGate;
    }

    /** 标记/取消「本次发送是模组自己发起的」。调用方必须用 try/finally 复位。 */
    public static void setProgrammatic(boolean value) {
        PROGRAMMATIC.set(Boolean.valueOf(value));
    }

    /**
     * 由注入的字节码调用。返回值直接决定原版方法要不要早退。
     *
     * @return true 表示拦下这次原版发送（模组翻译完再重发）
     */
    public static boolean onSendChatMessage(String message) {
        if (Boolean.TRUE.equals(PROGRAMMATIC.get())) {
            return false;
        }
        SendGate current = gate;
        if (current == null) {
            return false;
        }
        try {
            return current.onSendChatMessage(message);
        } catch (Throwable t) {
            // 翻译逻辑出任何问题都不能卡住玩家发消息：放行原版行为是最安全的降级。
            // 这里同样 catch Throwable —— 静态初始化失败抛的是 Error。
            //
            // v3.0.8：**降级不等于静默**。以前这条路一个字都不打，玩家看到的是
            // 「打中文没被翻译，而日志里什么都搜不到」—— 正好把 README 给出的排查路径堵死。
            // 只用 System.err（不碰日志框架，见类注释），且只报第一次（发送路径每条消息都会经过）。
            if (reportedSwallowed.compareAndSet(false, true)) {
                System.err.println("[server_chat_translator] 发送闸门出错，本条原样发出"
                        + "（其余功能不受影响）: " + t);
                t.printStackTrace();
            }
            return false;
        }
    }
}
