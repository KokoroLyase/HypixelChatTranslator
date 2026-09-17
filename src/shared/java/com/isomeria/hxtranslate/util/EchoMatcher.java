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
    public static final class Sent {

        private final String text;
        private final long atMillis;

        public Sent(String text, long atMillis) {
            this.text = text;
            this.atMillis = atMillis;
        }

        public String text() {
            return text;
        }

        public long atMillis() {
            return atMillis;
        }

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

    /**
     * 系统聊天里的说话人。
     *
     * @param name     玩家名
     * @param outgoing true 表示这是<b>自己发出去</b>的消息（{@code To xxx:} 这种私聊回显）
     */
    public static final class Speaker {

        private final String name;
        private final boolean outgoing;

        public Speaker(String name, boolean outgoing) {
            this.name = name;
            this.outgoing = outgoing;
        }

        public String name() {
            return name;
        }

        public boolean outgoing() {
            return outgoing;
        }
    }

    /**
     * 从一条系统聊天里认出发言人，例如：
     *
     * <pre>
     * [MVP+] Steve: inc mid              → Steve
     * [喊话] [红队] [MVP+] Alex: gg       → Alex
     * Party &gt; [MVP+] Steve: hi           → Steve
     * To Steve: hi                       → Steve（outgoing，是我发的私聊）
     * </pre>
     *
     * <p>为什么要认名字：光靠「正文和我说过的一样」猜回显是不可靠的 ——
     * 你自己说了句 {@code gg}，接下来 15 秒里别人说的每个 {@code gg} 都会被当成你的回显而跳过，
     * 这就是玩家反馈的「别人的 gg 不翻译」（见 VerifyCore 回归用例）。
     * 系统聊天里本来就带着名字，认名字比猜正文可靠得多。
     *
     * @return 说话人；格式认不出来（没有 {@code ": "}、名字不像玩家名）时返回 null
     */
    public static Speaker speakerOf(String text) {
        if (text == null) {
            return null;
        }
        int idx = text.indexOf(": ");
        if (idx <= 0) {
            return null;
        }
        // 「To Steve: ...」是自己发出去的私聊回显。
        // 必须要求「To 」后面**紧跟**名字和冒号：服务器提示也可能以 To 开头、也含 ": "，
        // 例如 "To view your stats, type: /stats" —— 以前这种整条会被当成「自己发的消息」而永不翻译。
        boolean outgoing = isOutgoingPrivateMessage(text, idx);
        // 名字是 ": " 前面最后一个词（Hypixel 的 [MVP+]、[红队]、队伍名这些前缀里都不含空格）
        String head = LangUtils.strip(text.substring(0, idx));
        int space = head.lastIndexOf(' ');
        String name = space < 0 ? head : head.substring(space + 1);
        // 「Guild > Steve」这种用 > 分隔的写法
        int gt = name.lastIndexOf('>');
        if (gt >= 0) {
            name = name.substring(gt + 1);
        }
        name = LangUtils.strip(name);
        return isPlayerName(name) ? new Speaker(name, outgoing) : null;
    }

    /** {@code To <玩家名>: } 才是自己发出的私聊；其它的「以 To 开头的服务器提示」不算。 */
    private static boolean isOutgoingPrivateMessage(String text, int colonSpaceIndex) {
        if (!text.startsWith("To ")) {
            return false;
        }
        // "To " 之后到 ": " 之前必须正好是一个玩家名（不能带空格、逗号之类的其它词）
        return isPlayerName(LangUtils.strip(text.substring(3, colonSpaceIndex)));
    }

    /** 名字是否像 Minecraft 玩家名：字母/数字/下划线，1~20 位（正版是 3~16 位）。 */
    private static boolean isPlayerName(String name) {
        if (name.isEmpty() || name.length() > 20) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c < 128 && Character.isLetterOrDigit(c)) || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * 这条系统聊天是不是本地玩家自己发的。
     *
     * @param localName 本地玩家名；还没进世界时传 null
     * @return {@code TRUE} / {@code FALSE}；<b>{@code null} 表示认不出说话人</b>，
     *         这种情况调用方应退回「正文比对」（见 {@link #findEcho}）
     */
    public static Boolean isOwnMessage(String text, String localName) {
        Speaker speaker = speakerOf(text);
        if (speaker == null) {
            return null;
        }
        if (speaker.outgoing()) {
            return Boolean.TRUE;
        }
        return localName == null ? null : speaker.name().equalsIgnoreCase(localName);
    }
}
