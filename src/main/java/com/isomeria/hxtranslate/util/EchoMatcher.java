package com.isomeria.hxtranslate.util;

/**
 * 判断一条收到的消息是不是「自己刚发出去、被服务器回显回来」的。
 *
 * <p>必须整条正文一致才算，<b>不能</b>用「包含」判断。
 * v1.0.2 及之前用的是 {@code haystack.contains(sent)}：只要自己发过含 {@code u}、{@code so} 这类
 * 短片段的英文消息，之后别人任何包含该片段的喊话都会被误判成自己的回显而静默丢弃 ——
 * 这就是「有时喊话翻译失效」的原因。
 *
 * <p>v1.0.7 起再加一道<b>时间窗</b>：回显只可能紧接着发送到达（往返通常不到 1 秒），
 * 而「自己发过的消息」在此之前是整局都记着的。译文常常就是 {@code wp} / {@code ty} /
 * {@code omw} / {@code inc mid} 这种短词，一直拿它们去比对的话，
 * 别人说的同样短语会被当成自己的回显而永远不翻译。
 *
 * <p>纯字符串逻辑，不依赖 Minecraft，方便离线回归测试。
 */
public final class EchoMatcher {

    /** 前缀匹配的最低长度，低于这个长度的消息只允许「完全一致」。 */
    private static final int MIN_PREFIX_MATCH = 8;

    /**
     * 回显的认领时间窗：只有在这个时间内发出去的消息，才可能是眼前这条的回显。
     *
     * <p>取 15 秒是为了容得下网络抖动，同时远小于「一局游戏」的尺度。
     */
    public static final long ECHO_WINDOW_MS = 15_000L;

    /**
     * 一条自己发出去的消息。
     *
     * @param text     归一化后的正文（见 {@link LangUtils#normalizeKey}）
     * @param atMillis 发送时刻，见 {@link System#currentTimeMillis()}
     */
    public record Sent(String text, long atMillis) {

        /** 记下「刚刚发出」的一条消息。 */
        public static Sent at(String text, long atMillis) {
            return new Sent(LangUtils.normalizeKey(text), atMillis);
        }

        /** 记下「刚刚发出」的一条消息（用当前时刻）。 */
        public static Sent now(String text) {
            return at(text, System.currentTimeMillis());
        }
    }

    private EchoMatcher() {
    }

    /**
     * @param incoming     收到的原始消息
     * @param sentMessages 自己最近发出去的消息（带发送时刻）
     * @param nowMillis    当前时刻
     * @return 命中的那条自己发过的消息；不是自己的回显则返回 null
     */
    public static String findEcho(String incoming, Iterable<Sent> sentMessages, long nowMillis) {
        if (incoming == null || sentMessages == null) {
            return null;
        }
        String body = LangUtils.normalizeKey(LangUtils.messageBody(incoming));
        if (body.isEmpty()) {
            return null;
        }
        for (Sent sent : sentMessages) {
            if (sent == null) {
                continue;
            }
            String text = sent.text();
            if (text == null || text.isEmpty()) {
                continue;
            }
            // 时间窗：太久以前发过的话，不足以证明眼前这条是自己的回显。
            // 负的年龄（时钟回拨）也一并算过期，免得时间跳变后又去吞别人的消息。
            long age = nowMillis - sent.atMillis();
            if (age < 0 || age > ECHO_WINDOW_MS) {
                continue;
            }
            if (text.equals(body)) {
                return text;
            }
            // 服务器偶尔会截断或补全长消息，允许「前缀一致」这一种情况。
            // 两边都要求至少 8 个字符，避免短消息（"u"、"so"、"hi"）误伤别人的话。
            if (text.length() >= MIN_PREFIX_MATCH && body.length() >= MIN_PREFIX_MATCH
                    && (body.startsWith(text) || text.startsWith(body))) {
                return text;
            }
        }
        return null;
    }

    /** 便捷重载：以当前时刻判定。 */
    public static String findEcho(String incoming, Iterable<Sent> sentMessages) {
        return findEcho(incoming, sentMessages, System.currentTimeMillis());
    }
}
