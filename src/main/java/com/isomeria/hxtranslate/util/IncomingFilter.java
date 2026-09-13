package com.isomeria.hxtranslate.util;

import com.isomeria.hxtranslate.config.TranslatorConfig;

/**
 * 判断一条收到的消息要不要翻译。
 *
 * <p>抽成不依赖 Minecraft 的纯函数，一来逻辑集中好维护，二来可以离线跑回归测试
 * （见 {@code tools/VerifyCore.java}，里面用的就是真实 Hypixel 聊天样本）。
 *
 * <p>判断顺序（任意一步命中就跳过）：
 * <ol>
 *   <li>空消息、超长消息；</li>
 *   <li>含中文/全角标点 → 本来就是中文（如 {@code xxx被yyy击杀。}）；</li>
 *   <li>正文汉字占比 ≥ 阈值 → 本来就是中文；</li>
 *   <li>正文里没有足够的拉丁字母 → 不是英文；</li>
 *   <li>命中用户的忽略正则；</li>
 *   <li>是自己刚发出去的消息被服务器回显回来。</li>
 * </ol>
 */
public final class IncomingFilter {

    /** 判断结果。{@code hanRatio} 是正文的汉字占比，调试输出会用到。 */
    public record Decision(boolean translate, String reason, double hanRatio) {

        public String describe() {
            return translate ? "翻译" : "跳过（" + reason + "）";
        }
    }

    private IncomingFilter() {
    }

    /**
     * @param text    收到的原始消息（已经 strip 过）
     * @param ignored 是否命中 {@code ignorePatterns}
     * @param ownEcho 是否是自己的消息回显
     */
    public static Decision decide(String text, TranslatorConfig config, boolean ignored, boolean ownEcho) {
        if (text == null || text.isBlank()) {
            return new Decision(false, "空消息", 0);
        }
        if (text.length() > config.maxIncomingChars) {
            return new Decision(false, "太长 " + text.length() + " 字符", LangUtils.hanRatio(text));
        }

        // 只看冒号后面的正文：Hypixel 会在前缀里塞本地化的队伍名（[红队]），
        // 那些汉字不代表消息本身是中文。
        String body = LangUtils.messageBody(text);
        double ratio = LangUtils.hanRatio(body);

        if (LangUtils.containsCjkPunctuation(text)) {
            return new Decision(false, "含中文标点", ratio);
        }
        if (ratio >= config.chineseRatioThreshold) {
            return new Decision(false, String.format("已经是中文（汉字占比 %.0f%%）", ratio * 100), ratio);
        }
        if (LangUtils.countLatinLetters(body) < config.minLatinLetters) {
            return new Decision(false, "没有英文内容", ratio);
        }
        if (ignored) {
            return new Decision(false, "命中 ignorePatterns", ratio);
        }
        if (ownEcho) {
            return new Decision(false, "自己消息的回显", ratio);
        }
        return new Decision(true, "", ratio);
    }
}
