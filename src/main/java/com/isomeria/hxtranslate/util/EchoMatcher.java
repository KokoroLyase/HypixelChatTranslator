package com.isomeria.hxtranslate.util;

/**
 * 判断一条收到的消息是不是「自己刚发出去、被服务器回显回来」的。
 *
 * <p>必须整条正文一致才算，<b>不能</b>用「包含」判断。
 * v1.0.2 及之前用的是 {@code haystack.contains(sent)}：只要自己发过含 {@code u}、{@code so} 这类
 * 短片段的英文消息，之后别人任何包含该片段的喊话都会被误判成自己的回显而静默丢弃 ——
 * 这就是「有时喊话翻译失效」的原因。
 *
 * <p>纯字符串逻辑，不依赖 Minecraft，方便离线回归测试。
 */
public final class EchoMatcher {

    /** 前缀匹配的最低长度，低于这个长度的消息只允许「完全一致」。 */
    private static final int MIN_PREFIX_MATCH = 8;

    private EchoMatcher() {
    }

    /**
     * @param incoming     收到的原始消息
     * @param sentMessages 自己最近发出去的消息（已归一化）
     * @return 命中的那条自己发过的消息；不是自己的回显则返回 null
     */
    public static String findEcho(String incoming, Iterable<String> sentMessages) {
        if (incoming == null || sentMessages == null) {
            return null;
        }
        String body = LangUtils.normalizeKey(LangUtils.messageBody(incoming));
        if (body.isEmpty()) {
            return null;
        }
        for (String sent : sentMessages) {
            if (sent == null || sent.isEmpty()) {
                continue;
            }
            if (sent.equals(body)) {
                return sent;
            }
            // 服务器偶尔会截断或补全长消息，允许「前缀一致」这一种情况。
            // 两边都要求至少 8 个字符，避免短消息（"u"、"so"、"hi"）误伤别人的话。
            if (sent.length() >= MIN_PREFIX_MATCH && body.length() >= MIN_PREFIX_MATCH
                    && (body.startsWith(sent) || sent.startsWith(body))) {
                return sent;
            }
        }
        return null;
    }
}
