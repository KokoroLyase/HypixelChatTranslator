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

    /** 统计文本里汉字的个数。 */
    public static int countHan(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isHan(cp)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 汉字在「汉字 + 拉丁字母」里占的比例，用来判断这条消息本身是不是中文。
     *
     * <p>为什么不能简单地「含汉字就跳过」：Hypixel 会按客户端语言把队伍名本地化成
     * {@code [红队]}，于是英文喊话会变成 {@code [MVP+] [红队] Steve: rush mid} —— 里面确实有汉字，
     * 但它显然是一条英文消息，必须翻译。真正的中文消息汉字占比会很高。
     *
     * @return 0.0 ~ 1.0；没有汉字时返回 0
     */
    public static double hanRatio(String text) {
        int han = countHan(text);
        if (han == 0) {
            return 0.0;
        }
        int total = han + countLatinLetters(text);
        return total == 0 ? 0.0 : (double) han / total;
    }

    /**
     * 是否包含中文/全角标点（。！？；，、」等）。
     *
     * <p>这是判断「本来就是中文消息」的强信号：Hypixel 本地化过的中文播报几乎必然带这些标点
     * （例如 {@code isabellab2012被Venomed击杀。}），而英文消息几乎不会用全角标点。
     * 光看汉字占比不够——上面这条消息里英文玩家名很长，汉字占比只有 0.15。
     */
    public static boolean containsCjkPunctuation(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjkPunctuation(cp)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjkPunctuation(int cp) {
        return (cp >= 0x3000 && cp <= 0x303F)      // 、。〈〉《》「」【】〜 等
                || (cp >= 0xFF01 && cp <= 0xFF0F)  // 全角 ！＂＃＄％＆＇（）＊＋，－．／
                || (cp >= 0xFF1A && cp <= 0xFF20)  // 全角 ：；＜＝＞？＠
                || (cp >= 0xFF3B && cp <= 0xFF40)
                || (cp >= 0xFF5B && cp <= 0xFF65)
                || cp == 0x2026;                   // …
    }

    /**
     * 取聊天正文：玩家喊话通常是 {@code 前缀 玩家名: 正文}，而前缀里可能带本地化的队伍名
     * （{@code [红队]}）。判断语言时只看正文更准。
     */
    public static String messageBody(String text) {
        if (text == null) {
            return "";
        }
        int idx = text.indexOf(": ");
        if (idx >= 0 && idx + 2 < text.length()) {
            return text.substring(idx + 2);
        }
        return text;
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
