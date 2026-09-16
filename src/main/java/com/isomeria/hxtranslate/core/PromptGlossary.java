package com.isomeria.hxtranslate.core;

import com.isomeria.hxtranslate.Log;
import com.isomeria.hxtranslate.util.LangUtils;

import java.util.List;

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
        int pairs = 0;
        for (String entry : glossary) {
            if (entry == null) {
                continue;
            }
            // 一个条目里可能有多组对照：def=防守（defend）；"u def"=你来防守
            for (String part : entry.split(ENTRY_SEPARATOR)) {
                if (pairs >= MAX_OUTGOING_PAIRS) {
                    break;
                }
                String pair = renderPair(part);
                if (pair == null) {
                    continue;
                }
                if (!table.isEmpty()) {
                    table.append('\n');
                }
                table.append(pair);
                pairs++;
            }
        }
        if (table.isEmpty()) {
            return null;
        }
        return "Minecraft / Hypixel / Bed Wars terminology reference "
                + "(Chinese phrasing -> the English wording players actually type):\n"
                + table + "\n"
                + "When translating into English, use the listed English wording when it fits. "
                + "Otherwise translate normally with the most natural gaming English; "
                + "do not force an entry that does not fit.";
    }

    /** 把一组 {@code 英文=中文} 渲染成 {@code 中文 -> 英文}；无法解析时返回 null。 */
    private static String renderPair(String part) {
        if (part == null) {
            return null;
        }
        int separator = part.indexOf(KEY_SEPARATOR);
        if (separator <= 0) {
            return null;
        }
        String english = part.substring(0, separator).trim();
        String chinese = chineseGloss(part.substring(separator + 1));
        if (english.isEmpty() || chinese.isEmpty()) {
            return null;
        }
        return chinese + " -> " + english;
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
