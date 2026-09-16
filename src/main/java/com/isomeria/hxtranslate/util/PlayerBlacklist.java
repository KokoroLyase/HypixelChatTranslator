package com.isomeria.hxtranslate.util;

import java.util.List;
import java.util.Locale;

/**
 * 「永不翻译」黑名单判断。
 *
 * <p>抽成不依赖 Minecraft 的纯函数，便于离线回归测试。
 * 两个入口：
 * <ul>
 *   <li>{@link #matchesName} —— 签名玩家聊天能直接拿到发送者名字，精确比对；</li>
 *   <li>{@link #speaksIn} —— 代理服的系统聊天没有发送者信息，只能在文本里找
 *       {@code 名字:} / {@code 名字 >} 这样的模式。</li>
 * </ul>
 */
public final class PlayerBlacklist {

    private PlayerBlacklist() {
    }

    public static boolean matchesName(String name, List<String> blacklist) {
        if (name == null || blacklist == null || blacklist.isEmpty()) {
            return false;
        }
        for (String entry : blacklist) {
            if (entry != null && !entry.isBlank() && entry.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 文本是否以黑名单玩家「发言」的形式出现。
     *
     * <p>要求名字处于**说话人位置**：行首，或紧跟在 {@code ]} / {@code >} 这类标签结束符之后
     * （Hypixel 的 {@code [MVP+] Steve: hi}、{@code Guild > Steve > hi}）。
     * 后面紧跟 {@code :} 或 {@code >} 才算是发言。
     *
     * <p>v2.2.2 收紧：以前只检查「名字前后是词边界 + 后面有冒号」，于是
     * {@code [MVP+] Bob: I saw Steve: he left} 这种**正文里提到**黑名单玩家的消息
     * 会被整条跳过（Bob 的话白丢，debug 还会显示「黑名单玩家」，让人以为是 Steve 说的）。
     * 现在只有说话人位置才算 —— 这正是这份名单的本意（「永不翻译这些玩家的消息」）。
     */
    public static boolean speaksIn(String text, List<String> blacklist) {
        if (text == null || blacklist == null || blacklist.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String entry : blacklist) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String name = entry.trim().toLowerCase(Locale.ROOT);
            int index = lower.indexOf(name);
            while (index >= 0) {
                if (isSpeakerPosition(lower, index, name.length())) {
                    return true;
                }
                index = lower.indexOf(name, index + 1);
            }
        }
        return false;
    }

    /**
     * 名字是否处在说话人位置。
     *
     * @param lower 已经转小写的整条文本
     * @param index 名字的起始下标
     * @param length 名字长度
     */
    private static boolean isSpeakerPosition(String lower, int index, int length) {
        // 左边：行首，或者只能隔着空白跟在标签结束符之后（[MVP+] Steve / Guild > Steve）
        int before = index - 1;
        while (before >= 0 && lower.charAt(before) == ' ') {
            before--;
        }
        boolean leftOk = before < 0 || lower.charAt(before) == ']' || lower.charAt(before) == '>';
        if (!leftOk) {
            return false;
        }
        // 右边：允许空格后跟分隔符（`Steve: hi` 与 `Steve > hi` 都算发言）
        int after = index + length;
        while (after < lower.length() && lower.charAt(after) == ' ') {
            after++;
        }
        return after < lower.length() && (lower.charAt(after) == ':' || lower.charAt(after) == '>');
    }
}
