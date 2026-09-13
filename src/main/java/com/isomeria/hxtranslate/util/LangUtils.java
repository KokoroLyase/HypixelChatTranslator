package com.isomeria.hxtranslate.util;

import java.util.Locale;

/**
 * 语言与文本小工具。刻意不依赖任何 Minecraft 类，方便离线单元测试。
 */
public final class LangUtils {

    private LangUtils() {
    }

    /** 文本中是否包含汉字。 */
    public static boolean containsHan(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isHan(cp)) {
                return true;
            }
        }
        return false;
    }

    /** 单个码位是否为汉字（只算表意文字，不算中文标点）。 */
    public static boolean isHan(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK 统一表意文字
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0xF900 && cp <= 0xFAFF)  // 兼容表意文字
                || (cp >= 0x20000 && cp <= 0x2A6DF)// 扩展 B
                || (cp >= 0x2A700 && cp <= 0x2EBEF)// 扩展 C-F
                || (cp >= 0x30000 && cp <= 0x323AF);// 扩展 G-H
    }

    /** 统计 ASCII 拉丁字母数量，用来判断“这看起来像英文”。 */
    public static int countLatinLetters(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                count++;
            }
        }
        return count;
    }

    /** 缓存用的归一化 key：去掉首尾空白、压缩连续空白、统一小写。 */
    public static String normalizeKey(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 去掉模型有时会自作主张加上的包裹引号。 */
    public static String stripWrappingQuotes(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            boolean paired = (first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”')
                    || (first == '「' && last == '」');
            if (paired) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    /**
     * 把译文裁剪到聊天框允许的长度。
     *
     * <p>原版聊天输入上限是 256 个字符；中文译成英文会变长，超长消息直接被服务器拒绝
     * 甚至有被踢的风险，所以宁可截断。优先在词边界切，并在结尾加省略号。
     */
    public static String truncateForChat(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        int limit = Math.max(1, max - 1);
        String cut = text.substring(0, Math.min(text.length(), limit));
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace >= limit / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.stripTrailing() + "…";
    }
}
