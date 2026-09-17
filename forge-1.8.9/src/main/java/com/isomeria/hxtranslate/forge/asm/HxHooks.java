package com.isomeria.hxtranslate.forge.asm;

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
            return false;
        }
    }
}
