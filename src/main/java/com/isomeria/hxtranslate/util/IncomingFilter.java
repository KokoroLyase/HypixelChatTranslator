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

        // 只看冒号后面的正文：Hypixel 会在前缀里塞本地化的队伍名（[红队]）和 [喊话]，
        // 那些汉字不代表消息本身是中文。
        String body = LangUtils.messageBody(text);
        double ratio = LangUtils.hanRatio(body);
        int hintWords = LangUtils.countEnglishHintWords(body);
        int hanRun = LangUtils.longestHanRun(body);

        // 0) 用户自己的过滤规则和自己的回显，优先于一切内容判断
        if (ignored) {
            return new Decision(false, "命中 ignorePatterns", ratio);
        }
        if (ownEcho) {
            return new Decision(false, "自己消息的回显", ratio);
        }
        // 1) 正文汉字占比够高 → 本来就是中文消息
        if (ratio >= config.chineseRatioThreshold) {
            return new Decision(false, String.format("已经是中文（汉字占比 %.0f%%）", ratio * 100), ratio);
        }
        // 2) 正文里有成句的英文（≥2 个英文信号词）→ 哪怕带中文后缀也照样翻译。
        //    例：3_0HY was thrown into a black hole by G19sy. 最终击杀！
        //    （以前这里被「含中文标点就跳过」误杀，导致英文播报漏翻）
        if (hintWords >= 2) {
            return new Decision(true, "英文句子", ratio);
        }
        // 3) 正文里有成段的汉字（连续 ≥2 个）→ 是中文播报，别翻。
        //    例：bedsyuu被Mlable击杀 / isabellab2012被Venomed击杀。
        //    （这类消息英文玩家名很长，汉字占比不到 0.4，只能靠「成段汉字」认出来）
        if (hanRun >= 2) {
            return new Decision(false, "正文含成段中文", ratio);
        }
        // 4) 剩下还带中文标点的，基本也是中文
        if (LangUtils.containsCjkPunctuation(text)) {
            return new Decision(false, "含中文标点", ratio);
        }
        if (LangUtils.countLatinLetters(body) < config.minLatinLetters) {
            return new Decision(false, "没有英文内容", ratio);
        }
        return new Decision(true, "", ratio);
    }
}
