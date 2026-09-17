package com.isomeria.hxtranslate.core;

import java.util.Collections;
import com.isomeria.hxtranslate.Log;
import com.isomeria.hxtranslate.util.LangUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把配置里的术语表拼成系统提示词里的那一段。
 *
 * <p><b>两个方向都要用，而且用法不同</b>：配置里的条目写成 {@code 英文写法=中文含义}
 * （见 {@link com.isomeria.hxtranslate.config.TranslatorConfig#glossary}），
 * 它天然是「英→中」的查表方向，v1.1.4 之前也只拼给接收方向。于是发送方向（中→英）
 * 完全没有术语表可用：玩家打「我们有黑曜石，直接冲他家」，模型不知道 {@code obby} / {@code rush}
 * 这些英文服里真正在用的说法，只能给出 {@code black obsidian} / {@code charge their base}
 * 这类「语法正确但没人这么说」的英文。
 *
 * <p>所以这里按方向渲染同一个列表：
 * <ul>
 *   <li>{@link Direction#INCOMING}（英→中）：原样列出条目，要求按含义翻成中文，不要保留英文缩写；</li>
 *   <li>{@link Direction#OUTGOING}（中→英）：把条目反查一遍，列出「中文说法 → 英文写法」，
 *       要求优先用列表里的英文说法；没命中就用最自然的英文，别硬套。</li>
 * </ul>
 *
 * <p>渲染是不依赖 Minecraft 的纯函数，所以生产代码和离线自检调用的是同一份实现
 * （见 {@code tools/VerifyCore.java}）—— 免得用例测的是自检自己抄的一份。
 */
public final class PromptGlossary {

    /** 配置条目里分隔「英文写法」和「中文含义」的字符。 */
    private static final char KEY_SEPARATOR = '=';

    /** 一个条目里可以写多组对照，用中文分号或英文分号隔开。 */
    private static final String ENTRY_SEPARATOR = "[；;]";

    /**
     * 反查时最多列出多少组对照。
     *
     * <p>默认术语表约 100 组，用户还会自己加，所以上限要留出余量：**上限一旦小于实际条数，
     * 排在末尾的词就会被静默丢掉**（第一版设成 80，结果新补的 low hp / side rush / fall back
     * 全在 80 名之外，等于白补）。它只是「用户往术语表里塞了几百条」时的兜底。
     *
     * <p>公开出来是给离线自检断言的：这个数字和「默认术语表有多少组」是耦合的，
     * 自检必须能读同一个值，而不是抄一份字面量。
     */
    public static final int MAX_OUTGOING_PAIRS = 120;

    private PromptGlossary() {
    }

    /**
     * 渲染术语表段落。
     *
     * @param glossary  配置里的条目，形如 {@code "obby=黑曜石（obsidian）"}；为空则返回 {@code null}
     * @param direction 翻译方向，决定渲染成「英→中」还是「中→英」的对照表
     * @return 可以直接追加到系统提示词后面的段落；没有可用术语时返回 {@code null}
     */
    public static String render(List<String> glossary, Direction direction) {
        if (glossary == null || glossary.isEmpty()) {
            return null;
        }
        if (direction == Direction.OUTGOING) {
            return renderChineseToEnglish(glossary);
        }
        return "Minecraft / Hypixel / Bed Wars 术语与缩写对照表（必须按含义翻译成中文，"
                + "不要保留英文原样；同一缩写有多种含义时按上下文选择最合适的一个）：\n"
                + String.join("；", glossary);
    }

    /**
     * 渲染「中→英」方向的对照表。
     *
     * @return 对照表段落；一个可反查的条目都没有时返回 {@code null}
     *         （例如用户把术语表改成了没有 {@code =} 的自由格式：宁可不注入，
     *         也不要把半截指令塞进提示词）
     */
    private static String renderChineseToEnglish(List<String> glossary) {
        StringBuilder table = new StringBuilder();
        // 同一中文说法只保留第一个英文写法（v2.1.4）。
        //
        // 之前是「来一条写一条」，于是默认表里 钻石 -> dia 与 钻石 -> dias 会同时出现在
        // 对照表里（金苹果更夸张：gap / gaps / gapple 三条），而提示词并没有优先级规则，
        // 模型只能自己挑 —— 真实 API 实测同一句话重复 6 次会给出 3 种不同译文。
        // 用户自己写的条目也一样：先写的优先，符合「配置从上往下读」的直觉。
        Set<String> seen = new HashSet<>();
        int pairs = 0;
        for (String entry : glossary) {
            if (entry == null) {
                continue;
            }
            // 一个条目里可能有多组对照：def=防守（defend）；"u def"=你来防守
            for (String part : splitParts(entry)) {
                if (pairs >= MAX_OUTGOING_PAIRS) {
                    break;
                }
                String pair = renderPair(part);
                if (pair == null) {
                    continue;
                }
                if (!seen.add(chineseKeyOf(pair))) {
                    continue;
                }
                if (table.length() > 0) {   // StringBuilder.isEmpty() 是 Java 15 的
                    table.append('\n');
                }
                table.append(pair);
                pairs++;
            }
        }
        if (table.length() == 0) {
            return null;
        }
        return "Minecraft / Hypixel / Bed Wars terminology reference "
                + "(Chinese phrasing -> the English wording players actually type):\n"
                + table + "\n"
                + "When translating into English, use the listed English wording when it fits. "
                + "Otherwise translate normally with the most natural gaming English; "
                + "do not force an entry that does not fit.";
    }

    /**
     * 把一条配置按分号拆成若干组对照（{@code def=防守；you def=你来防守}）。
     *
     * <p>和渲染共用同一套规则：术语表体检（{@link GlossaryAudit}）也用这个拆法，
     * 否则「体检说没问题、渲染却把这条丢了」这种分叉会永远查不出来。
     *
     * @param entry 一条配置；为 {@code null} 时返回空列表
     */
    static List<String> splitParts(String entry) {
        if (entry == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(entry.split(ENTRY_SEPARATOR));
    }

    /** 一组对照解析失败的原因，供术语表体检给出「哪里错、怎么改」的提示。 */
    enum Problem {
        /** 解析成功。 */
        NONE,
        /** 没有等号（也包含全角等号这种写法）。 */
        NO_SEPARATOR,
        /** 等号左边是空的，缺英文写法。 */
        EMPTY_ENGLISH,
        /** 等号右边是空的，或缺中文含义（括号内容会被当注释去掉，只剩括号也算空）。 */
        EMPTY_CHINESE
    }

    /**
     * 一组对照的解析结果。
     *
     * <p>{@code english} 已去掉包裹引号，{@code chinese} 已去掉括号说明并压成一行 ——
     * 也就是**真正会被渲染进提示词的那两个值**。
     *
     * <p>Java 8 没有 record（1.8.9 那条线编译不过），写成普通不可变类，
     * 访问器名字保持 {@code english()} / {@code chinese()} / {@code problem()} 不变。
     */
    static final class Parsed {

        private final String english;
        private final String chinese;
        private final Problem problem;

        Parsed(String english, String chinese, Problem problem) {
            this.english = english;
            this.chinese = chinese;
            this.problem = problem;
        }

        String english() {
            return english;
        }

        String chinese() {
            return chinese;
        }

        Problem problem() {
            return problem;
        }

        /** 这一组是否可用（不可用的会被渲染直接跳过）。 */
        boolean ok() {
            return problem == Problem.NONE;
        }
    }

    /**
     * 解析一组对照（形如 {@code obby=黑曜石（obsidian）}）。
     *
     * <p>这是渲染与术语表体检**共用的唯一解析入口**：渲染用它决定「这一组能不能进提示词」，
     * 体检用它判断「为什么进不去」。两边分家的话，体检报告就会和实际行为不一致。
     */
    static Parsed parse(String part) {
        if (part == null) {
            return new Parsed("", "", Problem.NO_SEPARATOR);
        }
        int separator = part.indexOf(KEY_SEPARATOR);
        if (separator < 0) {
            return new Parsed("", "", Problem.NO_SEPARATOR);
        }
        // 英文侧可能被用户加上引号（v2.1.4 前的默认表里就有一条 "u def"）。
        // 引号在这里没有任何意义，却会原样进提示词，和「不要加引号」的规则打架，所以剥掉。
        String english = stripQuotes(part.substring(0, separator).trim());
        if (english.isEmpty()) {
            return new Parsed("", "", Problem.EMPTY_ENGLISH);
        }
        String chinese = chineseGloss(part.substring(separator + 1));
        if (chinese.isEmpty()) {
            return new Parsed(english, "", Problem.EMPTY_CHINESE);
        }
        return new Parsed(english, chinese, Problem.NONE);
    }

    /** 把一组 {@code 英文=中文} 渲染成 {@code 中文 -> 英文}；无法解析时返回 null。 */
    private static String renderPair(String part) {
        Parsed parsed = parse(part);
        if (!parsed.ok()) {
            return null;
        }
        return parsed.chinese() + " -> " + parsed.english();
    }

    /** 去掉包裹英文写法的成对引号（直引号、弯引号、书名号都算）。 */
    private static String stripQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            boolean paired = (first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”')
                    || (first == '「' && last == '」');
            if (paired) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    /** 取对照行里的中文说法（{@code " -> "} 左侧），用于去重。 */
    private static String chineseKeyOf(String pair) {
        // 用 lastIndexOf（v2.2.2）：中文说法**自身**可能含 " -> "（用户写 `aaa=a -> b`），
        // 取第一个箭头会把键退化成半个说法，导致两条不同的说法被判成同一条而静默合并 ——
        // 后写的那条（通常正是用户刚加的）会凭空消失。
        int arrow = pair.lastIndexOf(" -> ");
        return arrow <= 0 ? pair : pair.substring(0, arrow);
    }

    /**
     * 反查表里实际有多少组对照（已去重、已被 {@link #MAX_OUTGOING_PAIRS} 截断）。
     *
     * <p>公开给离线自检用：v2.1.4 起同一中文说法只保留一个英文写法，所以
     * 「组数」不再等于「术语表条目数」（默认表 95 条 → 90 组）。用例要断言
     * 「默认表整份装得下、没被上限静默截掉」，就必须读这个真实数字，
     * 而不是自己假设「一组条目等于一组对照」。
     *
     * @return 组数；没有可用条目时返回 0
     */
    public static int outgoingPairCount(List<String> glossary) {
        String table = render(glossary, Direction.OUTGOING);
        if (table == null) {
            return 0;
        }
        int count = 0;
        for (String line : table.split("\n")) {
            if (line.contains(" -> ")) {
                count++;
            }
        }
        // 表头也含 " -> "（"Chinese phrasing -> the English wording..."），减掉它
        return Math.max(0, count - 1);
    }

    /**
     * 从「=」右侧取出中文说法，并清洗成单行。
     *
     * <p>清洗和译文走同一条规则（去掉 {@code §} 格式代码、压成一行）：术语表是用户在 json 里
     * 手写的，而它被拼进的是**发给接口的请求体**，一个换行就能让整段格式乱掉。
     */
    private static String chineseGloss(String raw) {
        String cleaned = LangUtils.sanitizeOneLine(raw);
        // 中文说法取第一个括号之前的内容；括号里通常是给模型看的补充说明
        // （黑曜石（obsidian）、防守（defend）），反查时用不上
        int bracket = firstBracket(cleaned);
        if (bracket >= 0) {
            cleaned = cleaned.substring(0, bracket);
        }
        return cleaned.trim();
    }

    /** 第一个「（」或「(」的下标；没有则返回 -1。 */
    private static int firstBracket(String text) {
        int zh = text.indexOf('（');
        int en = text.indexOf('(');
        if (zh < 0) {
            return en;
        }
        if (en < 0) {
            return zh;
        }
        return Math.min(zh, en);
    }

    /**
     * {@link #render} 的防炸版本：渲染出问题时只记一行日志，返回 {@code null}。
     *
     * <p>提示词组装在 {@code DeepSeekClient} 的请求路径上，而术语表内容完全由用户掌握，
     * 所以这里兜一层：术语表坏掉的后果应该是「这条提示词少一段」，而不是「所有翻译都失败」。
     */
    public static String renderSafely(List<String> glossary, Direction direction) {
        try {
            return render(glossary, direction);
        } catch (RuntimeException e) {
            Log.LOGGER.warn("拼装术语表失败（本条提示词不带术语表）: {}", e.toString());
            return null;
        }
    }
}
