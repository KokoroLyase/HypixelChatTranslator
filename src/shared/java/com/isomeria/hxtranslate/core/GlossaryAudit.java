package com.isomeria.hxtranslate.core;

import com.isomeria.hxtranslate.util.LangUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 术语表体检：找出「不会生效」或「会污染提示词」的条目，并给出怎么改。
 *
 * <p>为什么需要它：术语表是<b>用户资产</b>（见 RELEASING §5），玩家会长期往里加词，
 * 而里面有两类错误是**完全静默**的：
 * <ul>
 *   <li><b>写反了</b>（{@code 黑曜石=obby}）—— 玩家按中文习惯把中文写在左边，
 *       而入参方向的提示词会原样列出 {@code 黑曜石=obby}，中→英方向更会被反查成
 *       {@code obby -> 黑曜石}：两个方向的含义都反了，玩家却只看到「翻了但不对」；</li>
 *   <li><b>格式错</b>（漏了 {@code =}、右半边是空的、全角 {@code ＝}）—— 这条会被渲染
 *       静默丢掉，既不报错也不生效，玩家以为加了词，其实一个字都没进提示词。</li>
 * </ul>
 *
 * <p>还有一类是「能用但有风险」：单字母英文写法（{@code u=你}，会和玩家名、普通英文撞车）、
 * 英文写法重复（模型会同时看到两种中文说法）、整条重复。默认术语表在 v2.1.4 已经刻意清掉了
 * 单字母条目，所以这些检查在默认表上**一条都不该报**（自检里有对应断言守着）。
 *
 * <p><b>只读诊断，不改变任何翻译行为</b>：它不修配置、不改提示词、不删条目 ——
 * 是否调整由玩家自己决定。解析用的是渲染那一条路径（{@link PromptGlossary#parse}），
 * 所以「体检说能生效」与「渲染真的会用它」永远一致。
 *
 * <p>纯函数、不依赖 Minecraft，生产代码与离线自检调用的是同一份实现。
 */
public final class GlossaryAudit {

    /** 发现的问题严重程度。 */
    public enum Severity {
        /** 条目写错了：要么不会生效，要么会让某个方向的含义反过来。 */
        ERROR,
        /** 条目能用，但有已知的撞车 / 歧义风险。 */
        WARNING
    }

    /** 问题的种类。文案按种类在 {@link Finding#describe()} 里生成。 */
    public enum Kind {
        /**
         * 格式不成立（缺 {@code =}、某一侧为空、全角等号）：中→英方向整条不生效。
         *
         * <p><b>注意它并非「两个方向都不生效」</b>（2026-09-17 审计更正）：接收方向
         * （英→中）是**原样列出**术语表，所以这条会带着 {@code ＝} 一起被塞进系统提示词 ——
         * 既没起到对照作用，又占着提示词。文案必须说清这一点，否则玩家会以为「反正它没生效，
         * 不改也行」，而实际上它在污染请求体。
         */
        MALFORMED(Severity.ERROR),
        /** 左侧（应是英文写法）含汉字：多半是写反了。 */
        REVERSED(Severity.ERROR),
        /** 右侧（应是中文含义）没有汉字：中→英方向会把它当成一个奇怪的说法。 */
        NO_CHINESE(Severity.ERROR),
        /** 英文写法只有一个字母：容易和玩家名、普通英文撞车。 */
        SINGLE_LETTER(Severity.WARNING),
        /** 同一个英文写法被写了两种中文说法：模型会同时看到两条。 */
        DUPLICATE_ENGLISH(Severity.WARNING),
        /** 整条一模一样地写了两遍。 */
        DUPLICATE_ENTRY(Severity.WARNING);

        private final Severity severity;

        Kind(Severity severity) {
            this.severity = severity;
        }

        /** 这条问题的严重程度。 */
        public Severity severity() {
            return severity;
        }
    }

    /**
     * 一条体检结论。
     *
     * @param kind       问题种类
     * @param entryIndex 在 {@code glossary} 列表里的下标（0 起）；展示时 +1 变成「第 N 条」
     * @param entry      原始条目文本（供玩家在 json 里定位）
     * @param detail     一句话说明「哪里错、怎么改」
     */
    public static final class Finding {

        private final Kind kind;
        private final int entryIndex;
        private final String entry;
        private final String detail;

        public Finding(Kind kind, int entryIndex, String entry, String detail) {
            this.kind = kind;
            this.entryIndex = entryIndex;
            this.entry = entry;
            this.detail = detail;
        }

        public Kind kind() {
            return kind;
        }

        public int entryIndex() {
            return entryIndex;
        }

        public String entry() {
            return entry;
        }

        public String detail() {
            return detail;
        }

        /** 面向玩家的一行描述。 */
        public String describe() {
            return "第 " + (entryIndex + 1) + " 条「" + entry + "」" + detail;
        }

        /**
         * 这条结论属于哪一条配置条目（用于「涉及 N 条条目」这类去重统计）。
         *
         * <p>用「下标 + 条目文本」而不是只用下标：{@code countsText} 拿到的是任意一批结论，
         * 不能假设它们来自同一次 {@code audit}；带上文本后，不同来源的结论也不会有歧义。
         * 包内可见即可（这是给 {@link #countsText} 用的实现细节）。
         */
        String entryKey() {
            return entryIndex + "\u0000" + entry;
        }
    }

    /**
     * 「某个英文写法第一次出现时」记下的东西：中文含义 + 它在列表里的下标。
     *
     * <p>只需记第一次：判据是「这个英文写法后面又配了别的中文说法」，
     * 提示里还要能指回先出现的那一条（自检断言里钉了这一点）。
     */
    private static final class ChineseAt {

        final String text;
        final int entryIndex;

        ChineseAt(String text, int entryIndex) {
            this.text = text;
            this.entryIndex = entryIndex;
        }
    }

    /** 启动时最多列几条明细：再多就刷屏了，剩下的让玩家用命令自己看。 */
    public static final int STARTUP_DETAIL_LIMIT = 3;

    /**
     * 单条明细里原文最多显示多少字符。
     *
     * <p>术语表条目由用户掌握，写错时可能顺手粘进一整段文字；聊天栏一行放不下那么长。
     */
    private static final int MAX_ENTRY_CHARS = 60;

    private GlossaryAudit() {
    }

    /**
     * 体检整份术语表。
     *
     * @param glossary 配置里的条目；{@code null} 或空表示没有术语表
     * @return 按条目顺序排列的结论；没有问题（含空表）时返回空列表，**不返回 null**
     */
    public static List<Finding> audit(List<String> glossary) {
        List<Finding> findings = new ArrayList<>();
        if (glossary == null || glossary.isEmpty()) {
            return findings;
        }
        // 英文写法（小写）-> 第一次见到的中文含义与它所在的条目（用来发现「同一英文两种说法」）
        Map<String, ChineseAt> firstChinese = new HashMap<>();
        // 整条（小写去空白）-> 第一次出现的条目下标
        Map<String, Integer> firstWhole = new HashMap<>();

        for (int i = 0; i < glossary.size(); i++) {
            String entry = glossary.get(i);
            if (entry == null || LangUtils.isBlank(entry)) {
                findings.add(new Finding(Kind.MALFORMED, i, String.valueOf(entry),
                        "是空条目，不会生效（格式：英文=中文）"));
                continue;
            }
            String whole = LangUtils.strip(entry).toLowerCase(Locale.ROOT);
            Integer wholeSeen = firstWhole.putIfAbsent(whole, i);
            if (wholeSeen != null) {
                // 整条重复时不再逐组解析：否则同一个问题会被报两次
                findings.add(new Finding(Kind.DUPLICATE_ENTRY, i, entry,
                        "与第 " + (wholeSeen + 1) + " 条重复（比较时会忽略大小写与首尾空白），"
                                + "重复的条目没有意义"));
                continue;
            }
            // 一条配置里可以写多组对照（分号隔开），逐组体检
            for (String part : PromptGlossary.splitParts(entry)) {
                auditPart(findings, i, entry, part, firstChinese);
            }
        }
        return findings;
    }

    /** 体检一组对照，把结论追加到 {@code findings}。 */
    private static void auditPart(List<Finding> findings, int entryIndex, String entry, String part,
                                  Map<String, ChineseAt> firstChinese) {
        // 纯分隔符（`;` `；` `;;`）会 split 出 0 个非空组：以前这条**完全静默**，
        // 而它正是「玩家以为加了词、其实一个字都没进提示词」的典型形态（2026-09-17 审计发现）。
        if (LangUtils.isBlank(part)) {
            findings.add(new Finding(Kind.MALFORMED, entryIndex, entry,
                    "含空白的一组（多半是多打了一个分号），这一组不会生效"));
            return;
        }
        PromptGlossary.Parsed parsed = PromptGlossary.parse(part);
        if (!parsed.ok()) {
            findings.add(new Finding(Kind.MALFORMED, entryIndex, entry,
                    malformedDetail(part, parsed.problem())));
            return;
        }
        if (LangUtils.containsHan(parsed.english())) {
            // 写着「黑曜石=obby」这种：给出交换后的正确写法，玩家复制一下就能改好
            if (parsed.chinese().equalsIgnoreCase(parsed.english())) {
                // 两边一模一样时「正确的写法是 X=X」是句废话（2026-09-17 审计发现）
                findings.add(new Finding(Kind.REVERSED, entryIndex, entry,
                        "疑似写反了：左右两边写的是同一个词。左边应当是英文写法、右边才是中文含义，"
                                + "请把中文含义补上"));
            } else {
                findings.add(new Finding(Kind.REVERSED, entryIndex, entry,
                        "疑似写反了：左边应当是英文写法、右边才是中文含义，现在左边是汉字。"
                                + "正确的写法是 " + parsed.chinese() + "=" + parsed.english()));
            }
            return;
        }
        if (!LangUtils.containsHan(parsed.chinese())) {
            findings.add(new Finding(Kind.NO_CHINESE, entryIndex, entry,
                    "右边没有中文含义（格式是 英文=中文）。中→英方向仍会把它当成一个说法，"
                            + "而英→中方向拿不到中文解释"));
            return;
        }
        if (isSingleLetter(parsed.english())) {
            findings.add(new Finding(Kind.SINGLE_LETTER, entryIndex, entry,
                    "英文写法只有一个字母，很容易和玩家名、普通英文撞车"
                            + "（默认术语表已刻意移除这类条目，想留着可以忽略这条提示）"));
            return;
        }
        String key = LangUtils.strip(parsed.english()).toLowerCase(Locale.ROOT);
        // 判据：**同一个英文写法**配了不止一种中文说法 → 反查表会把两条都列出来让模型自己挑，
        // 所以要提醒玩家。反过来「同一中文、不同英文」（`dia=钻石` / `dias=钻石`）是默认表的
        // 有意设计 —— 那种情况英文写法不同，查的是不同的 key，永远走不到这里。
        //
        // 2026-09-17 审计修正：旧实现是 `english -> entryIndex` + 中文->下标 的内层表，
        // 再配 `previous != entryIndex` 想「同一条目内不重复报」，实际把**同一条目内**的
        // 变体（`mid=中路；mid=中间`）也一起跳过了 —— 而那两种说法会真的同时进对照表。
        // 现在按「这个英文写法第一次出现的中文含义」记，只要后面出现的中文不同就报，
        // 跨条目与同条目内一视同仁。
        ChineseAt seen = firstChinese.get(key);
        if (seen == null) {
            firstChinese.put(key, new ChineseAt(parsed.chinese(), entryIndex));
            return;
        }
        if (!seen.text.equals(parsed.chinese())) {
            findings.add(new Finding(Kind.DUPLICATE_ENGLISH, entryIndex, entry,
                    "的英文写法 " + parsed.english() + " 与第 " + (seen.entryIndex + 1)
                            + " 条配的中文说法不同（那里是「" + seen.text
                            + "」），模型会同时看到两种中文说法"));
        }
    }

    /**
     * 把解析失败的原因写成人话；全角等号这种高频手滑单独给一句。
     *
     * <p>每条都要说清「中→英方向不会生效」，同时**不能**让玩家以为「两个方向都不用管」
     * （2026-09-17 审计更正）：接收方向是原样列出术语表，这条会带着错字一起进请求体。
     * 所以统一补一句「英→中方向仍会原样带进提示词」。
     */
    private static String malformedDetail(String part, PromptGlossary.Problem problem) {
        String raw = part == null ? "" : part;
        if (raw.indexOf('＝') >= 0) {
            // 中文输入法下打出来的全角等号：看着和 = 一样，但程序不认
            return "用了全角等号「＝」，模组只认半角 =，中→英方向整条不会生效"
                    + "（英→中方向仍会原样带进提示词）";
        }
        int separator = raw.indexOf('=');
        String right = separator < 0 ? "" : LangUtils.strip(raw.substring(separator + 1));
        // Java 8 没有 switch 表达式，改成经典 switch（1.8.9 那条线要用）
        switch (problem) {
            case NO_SEPARATOR:
                return "缺少等号「=」（格式：英文=中文），中→英方向整条不会生效"
                        + "（英→中方向仍会原样带进提示词）";
            case EMPTY_ENGLISH:
                return "等号左边是空的，缺英文写法，中→英方向整条不会生效"
                        + "（英→中方向仍会原样带进提示词）";
            case EMPTY_CHINESE:
                if (right.isEmpty()) {
                    return "等号右边是空的，缺中文含义，中→英方向整条不会生效"
                            + "（英→中方向仍会原样带进提示词）";
                }
                return "等号右边只有空白或括号里的说明（括号内容会被当成注释去掉），"
                        + "中→英方向整条不会生效（英→中方向仍会原样带进提示词）";
            case NONE:
            default:
                return "格式有问题，中→英方向整条不会生效（英→中方向仍会原样带进提示词）";
        }
    }

    /** 英文写法是不是「单个字母」（{@code u} / {@code r} 这种）。 */
    private static boolean isSingleLetter(String english) {
        String text = english == null ? "" : english.trim();
        if (text.length() != 1) {
            return false;
        }
        char c = text.charAt(0);
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 一行摘要，给启动提示与 {@code /translator status} 用。
     *
     * @return 摘要文本；没有问题（或没有术语表）时返回 {@code null}，调用方据此决定不打扰玩家
     */
    public static String summarize(List<Finding> findings) {
        String counts = countsText(findings);
        return counts == null ? null : "术语表体检：" + counts + "（输入 /translator glossary 查看详情）";
    }

    /**
     * 只统计「几条写错、几条有风险」，不带指路后缀。
     *
     * <p>给已经处在 {@code /translator glossary} 里的场景用 —— 那时再提示「输入该命令」很滑稽。
     *
     * <p><b>2026-09-17 审计修正</b>：这里数的是「问题**处**数」而不是「条目数」，一条配置里
     * 有多组对照时两者会差很多（单条 {@code "没有等号;也没有等号;还是没有"} 会得出
     * 「3 条」，而玩家只有 1 条条目）。而且同一处问题可能在多条条目里出现（例如整条重复），
     * 所以「受影响的条目数」也不能拿 {@code errors + warnings} 当分母。
     * 现在两个数字都报：一处问题都不少，条目数用去重后的真实值。
     *
     * @return 形如 {@code "2 处写错或不会生效（涉及 2 条条目）、1 处有风险"}；没有问题时返回 {@code null}
     */
    public static String countsText(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return null;
        }
        int errors = 0;
        // 用 entryKey() 去重：只数「条目」的个数，不依赖调用方传进来的 list 是否按条目连续排列。
        Map<String, Boolean> affectedEntries = new java.util.LinkedHashMap<>();
        for (Finding finding : findings) {
            if (finding.kind().severity() == Severity.ERROR) {
                errors++;
            }
            affectedEntries.put(finding.entryKey(), Boolean.TRUE);
        }
        int warnings = findings.size() - errors;
        StringBuilder text = new StringBuilder();
        if (errors > 0) {
            text.append(errors).append(" 处写错或不会生效");
        }
        if (warnings > 0) {
            if (text.length() > 0) {   // StringBuilder.isEmpty() 是 Java 15 的
                text.append('、');
            }
            text.append(warnings).append(" 处有风险");
        }
        // 条目数与问题处数不是一回事：一条配置里可以有多组对照，同一个问题也可能在
        // 多条条目里出现。只在两者不同时才补一句，免得正常情况（1 条条目 1 处问题）变啰嗦。
        if (affectedEntries.size() != findings.size()) {
            text.append("（涉及 ").append(affectedEntries.size()).append(" 条条目）");
        }
        return text.toString();
    }

    /**
     * 逐条明细，最多列 {@code maxLines} 条。
     *
     * <p>返回值已经过 {@link LangUtils#sanitizeOneLine} 与长度截断：条目文本是用户内容，
     * 里面可能有 {@code §} 或换行，直接进聊天栏会变成颜色代码、把一行拆成好几行
     * （{@code /translator glossary} 走的是命令反馈，不经过 {@code GameFeedback} 的统一清洗）。
     *
     * @param maxLines 最多几条明细；不足时按实际数量返回
     * @return 可直接显示的行；没有问题或 {@code maxLines <= 0} 时返回空列表
     */
    public static List<String> detailLines(List<Finding> findings, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (findings == null || findings.isEmpty() || maxLines <= 0) {
            return lines;
        }
        int shown = Math.min(maxLines, findings.size());
        for (int i = 0; i < shown; i++) {
            lines.add(oneLine(findings.get(i).describe()));
        }
        if (findings.size() > shown) {
            lines.add("…另有 " + (findings.size() - shown) + " 条，输入 /translator glossary 查看全部");
        }
        return lines;
    }

    /** 清洗成单行并截断，避免一条坏条目把聊天栏顶掉。 */
    private static String oneLine(String text) {
        String cleaned = LangUtils.sanitizeOneLine(text);
        return cleaned.length() <= MAX_ENTRY_CHARS
                ? cleaned
                : LangUtils.truncateForChat(cleaned, MAX_ENTRY_CHARS);
    }
}
