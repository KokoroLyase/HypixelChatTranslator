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
     * <p>要求名字前后都是词边界、且后面紧跟 {@code :} 或 {@code >}，
     * 这样「正文里提到这个名字」不会导致整条消息被跳过。
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
                boolean leftOk = index == 0 || !isNameChar(lower.charAt(index - 1));
                // 名字后面允许有空格再跟分隔符：`Steve: hi` 和 `Steve > hi` 都算发言
                int after = index + name.length();
                while (after < lower.length() && lower.charAt(after) == ' ') {
                    after++;
                }
                boolean rightOk = after < lower.length()
                        && (lower.charAt(after) == ':' || lower.charAt(after) == '>');
                if (leftOk && rightOk) {
                    return true;
                }
                index = lower.indexOf(name, index + 1);
            }
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
