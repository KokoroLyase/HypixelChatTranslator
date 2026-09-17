package com.isomeria.hxtranslate.util;

import com.isomeria.hxtranslate.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 语言与文本小工具。刻意不依赖任何 Minecraft 类，方便离线单元测试。
 */
public final class LangUtils {

    private LangUtils() {
    }

    /**
     * 单次正则匹配的预算（毫秒）。
     *
     * <p>超时就放弃这次匹配，并给这条正则记一次「疑似卡顿」；**连续**两次才真正停用。
     * 取 500ms 的理由：正常匹配在微秒级（实测 5 条默认正则合计 19.6µs），
     * 而渲染线程会被 GC 停顿 / 区块加载 / CPU 争抢拖慢 —— 预算给得太紧（试过 150ms）
     * 会把**本来没问题**的正则误判成灾难性回溯，而误判的后果是用户的忽略规则静默失效。
     * 真正的灾难性回溯是指数级的（实测 {@code (.*a){20}$} 在 26 字符时 4.4 秒、
     * 240 字符时不可能在可接受时间内返回），所以 500ms 足够区分「卡了一下」与「真的爆了」。
     */
    public static final long REGEX_BUDGET_MS = 500L;

    /**
     * 连续超时几次才停用一条正则。
     *
     * <p>2 次：既不会因为一次 GC 停顿误伤，也不会让一条真坏的正则反复拖慢主线程
     * （第二次超时之后就再也不碰它了）。
     */
    private static final int REGEX_TIMEOUT_STRIKES = 2;

    /** 正在执行用户正则的工作线程；只在真的需要超时保护时才用（见 matchesAny）。 */
    private static final ExecutorService REGEX_WORKER = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "server_chat_translator-regex-" + seq.incrementAndGet());
            thread.setDaemon(true); // 绝不能拦住 JVM 退出
            return thread;
        }
    });

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

    /**
     * 单个码位是否为汉字（只算表意文字，不算中文标点）。
     *
     * <p><b>范围必须覆盖 Unicode 里所有 Script=Han 的码位</b>（v3.0.0 洁净度审计补齐）。
     * 原因不是「更好看」，而是 {@link #containsHan} 是「绝不把中文发到英文服」的**最后一道闸**：
     * 漏掉任何一个汉字区间，模型回一个落在缺口里的字就能带着汉字穿过闸门发到英文服。
     * 另外 {@link #hanRatio} 的汉字计数少算，会让「收到的中文消息」占比不足阈值而被送去翻译
     * （白花钱、还往聊天栏贴一条中译中的废话）。
     *
     * <p>补齐的做法是拿 JDK 自带的 {@code Character.UnicodeScript} 逐码位对照出来的：
     * 旧实现漏了 1512 个 Script=Han 的码位，主要是
     * {@code U+2E80-2EF3}（部首补充）、{@code U+2F00-2FD5}（康熙部首）、
     * {@code U+2F800-2FA1D}（兼容表意文字补充）、{@code U+2EBF0-2EE5D}（扩展 I，Unicode 15.1 新增）、
     * 以及 {@code 〇}(U+3007) 这类零散的表意符号。
     *
     * <p>刻意**不**把这些区间里的非表意码位算进来（例如 {@code 〆} U+3006、
     * {@code 〇} 之外的 U+3008 起的中文标点）：本方法只回答「是不是汉字」，
     * 标点由 {@link #isCjkPunctuation} 单独判定。
     */
    public static boolean isHan(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK 统一表意文字
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0xF900 && cp <= 0xFAFF)  // 兼容表意文字
                || (cp >= 0x20000 && cp <= 0x2A6DF)// 扩展 B
                || (cp >= 0x2A700 && cp <= 0x2EBEF)// 扩展 C-F
                || (cp >= 0x2EBF0 && cp <= 0x2EE5D)// 扩展 I（Unicode 15.1 新增，旧实现漏了）
                || (cp >= 0x30000 && cp <= 0x323AF)// 扩展 G-H
                || (cp >= 0x2E80 && cp <= 0x2EF3)  // CJK 部首补充（U+2E9A 未分配，一起包含无害）
                || (cp >= 0x2F00 && cp <= 0x2FD5)  // 康熙部首
                || (cp >= 0x2F800 && cp <= 0x2FA1D)// 兼容表意文字补充（旧实现漏了 542 个）
                || (cp >= 0x3021 && cp <= 0x3029)  // 苏州码子 〡-〩（Script=Han）
                || (cp >= 0x3038 && cp <= 0x303B)  // 〸-〻 合体字
                || cp == 0x3005                     // 々 迭代符号
                || cp == 0x3007                     // 〇 表意数字零（中文里真的会用）
                || cp == 0x16FE2                    // 古汉字钩号
                || cp == 0x16FE3                    // 古汉字迭代号
                || cp == 0x16FF0                    // 越南喃字迭代号（Script=Han）
                || cp == 0x16FF1;                   // 同上
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

    /**
     * 正文里最长的一段连续汉字有多少个字。
     *
     * <p>用来识别「中文播报里夹着英文玩家名」：{@code bedsyuu被Mlable击杀} 的汉字占比只有 0.2，
     * 但里面有 {@code 击杀} 这样成段的汉字，说明它本来就是中文，不该翻译。
     * 反过来 {@code ... by G19sy. 最终击杀！} 虽然有汉字，但先被英文词信号判成英文了。
     */
    public static int longestHanRun(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isHan(cp)) {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }

    /**
     * 强烈暗示「这是英文句子」的常见英文词 / 缩写。按整词匹配，不会把 {@code im} 命中到 {@code time} 里。
     *
     * <p>只看这些词而不是全部单词，是为了避免把英文玩家名（{@code Moriarty}、{@code G19sy}）当成英文内容。
     */
    private static final Set<String> ENGLISH_HINT_WORDS = new LinkedHashSet<>(Arrays.asList(
            // 虚词（刻意不收 a / i / u 这类单字母：它们太容易出现在玩家名或中文句子里）
            "an", "the", "is", "are", "was", "were", "be", "been", "am",
            "do", "does", "did", "have", "has", "had", "will", "would", "can", "could",
            "should", "shall", "may", "might", "must", "you", "ur", "your",
            "yours", "my", "me", "we", "our", "us", "he", "him", "his", "she",
            "her", "it", "its", "they", "them", "their", "this", "that", "these", "those",
            "to", "of", "in", "on", "at", "for", "with", "by", "from", "and",
            "or", "but", "not", "no", "yes", "so", "if", "then", "than", "as",
            "into", "out", "about", "after", "before", "because", "while", "during", "without",
            "up", "down", "off", "over", "under", "through", "between", "against", "above", "below",
            "away", "back", "around", "along", "across", "behind", "beyond", "near", "since", "until",
            "just", "very", "too", "also", "there", "here", "what", "when", "where", "who",
            "why", "how", "all", "any", "some", "more", "most", "one", "two", "other",
            "another", "such", "both", "each", "few", "many", "much", "only", "same", "own",
            "still", "even", "again", "though", "although", "however", "maybe", "perhaps",
            "get", "got", "go", "going", "gonna", "let", "dont", "cant", "wont", "im",
            "ive", "ill", "thats", "youre", "youve", "ok", "okay", "yeah", "yep", "nope",
            "pls", "plz", "thx", "ty", "please", "sorry", "thanks", "thank", "really", "actually",
            "wanna", "gotta", "kinda", "wtf", "lol", "lmao", "omg", "bruh", "dude", "guys",
            "nice", "good", "great", "bad", "stop", "wait", "help", "run", "jump", "fast",
            "kill", "killed", "dead", "died", "lose", "lost", "win", "won", "play", "player",
            "game", "pro", "skill", "aim", "lag", "laggy", "ping", "team", "base", "island",
            // 起床战争常用词
            "gg", "wp", "ez", "inc", "def", "mid", "rush", "rushin", "bed", "range",
            "reach", "sweaty", "chill", "tryhard", "noob", "hacker", "hack", "cheat", "cheater", "camp",
            "carry", "clutch", "obby", "dia", "dias", "gen", "pot", "void", "gap", "fb"
    ));

    /**
     * 正文里出现了几个「英文信号词」。≥2 个基本可以断定这是一句英文。
     *
     * <p><b>含汉字的「词」整块跳过</b>：Hypixel 的本地化播报会把玩家名直接粘在中文上，
     * 例如 {@code Im_Bad_At_PKMN失足跌入虚空。} —— 玩家名会被切成 {@code im} / {@code bad} / {@code at}
     * 三个信号词，于是整条中文播报被误判成「英文句子」而送去翻译（玩家反馈的真实 bug，
     * 见 VerifyCore 里用截图原文写的回归用例）。名字不是句子。
     *
     * <p>反过来，像 {@code 3_0HY was thrown into a black hole by G19sy. 最终击杀！} 这种
     * 玩家名与英文句子之间是有空格的，照旧按句子统计，不受影响。
     */
    public static int countEnglishHintWords(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String word : text.split("\\s+")) {
            if (word.isEmpty() || containsHan(word)) {
                continue;
            }
            count += countHintWordsInWord(word);
        }
        return count;
    }

    /** 单个「不含汉字的词」里命中的信号词数（词内还会按非字母再切，例如 {@code Bad_At} 算两个）。 */
    private static int countHintWordsInWord(String word) {
        int count = 0;
        int i = 0;
        int length = word.length();
        StringBuilder token = new StringBuilder();
        while (i <= length) {
            char c = i < length ? word.charAt(i) : ' ';
            if (Character.isLetter(c) && c < 128) {
                token.append(Character.toLowerCase(c));
            } else {
                if (token.length() > 0) {   // StringBuilder.isEmpty() 是 Java 15 的
                    if (ENGLISH_HINT_WORDS.contains(token.toString())) {
                        count++;
                    }
                    token.setLength(0);
                }
            }
            i++;
        }
        return count;
    }

    /**
     * 去掉原版颜色/格式代码（{@code §a}、{@code §r}、{@code §l} 等）。
     *
     * <p>服务器把聊天打包成 Component 时，有些消息（Hypixel 尤其多）会把 {@code §} 代码直接写在
     * 文本里，于是 {@code Component.getString()} 拿到的字符串带着一堆 {@code §x}。
     * 这些符号对翻译没有意义，还可能被模型当成正文一起"翻译"，所以在送去翻译前先剔除。
     */
    public static String stripFormattingCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7') {
                if (i + 1 < text.length()) {
                    i++; // 连同后一个格式字符一起丢掉
                }
                // 末尾孤立的 § 也丢掉（v2.2.2）：它后面没有字符，在 Minecraft 里就是个裸字符、
                // 没有任何格式含义，但留着会让「同一句话」出现两种形态 ——
                // 实测后果：模型回一个 `gg§` 时，回显名单记成 `gg§` 而服务器回显是 `gg`，
                // 于是自己的回显认不出来、被当成别人的消息再翻成中文。
                continue;
            }
            builder.append(c);
        }
        return builder.toString();
    }

    /** 缓存用的归一化 key：去掉首尾空白、压缩连续空白、统一小写。 */
    public static String normalizeKey(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * 编译配置里的正则（{@code ignorePatterns} 这类）。
     *
     * <p>放在这里是为了让「生产代码」和「离线自检」用同一份规则：自检如果自己抄一遍
     * {@code Pattern.compile(regex, CASE_INSENSITIVE)}，那么生产代码哪天把 {@link Matcher#find()}
     * 改成 {@link Matcher#matches()}、或者丢掉 {@code CASE_INSENSITIVE}，用例照样是绿的
     * —— 等于那些用例根本没在保护东西。
     *
     * @param onInvalid 遇到写错的正则时回调（传正则原文），可以为 null；调用方决定怎么记日志
     */
    public static List<Pattern> compilePatterns(List<String> regexes, Consumer<String> onInvalid) {
        if (regexes == null || regexes.isEmpty()) {
            return new ArrayList<>();
        }
        List<Pattern> compiled = new ArrayList<>(regexes.size());
        for (String regex : regexes) {
            if (regex == null || LangUtils.isBlank(regex)) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                if (onInvalid != null) {
                    onInvalid.accept(regex);
                }
            }
        }
        return compiled;
    }

    /**
     * 文本是否命中任意一条已编译的正则（子串语义，不区分大小写）。
     *
     * <p><b>带灾难性回溯（ReDoS）防护</b>：{@code ignorePatterns} 是用户在 json 里手写的正则，
     * 而这个方法是在**渲染线程**（Fabric 事件回调）上对每条收到的消息调用的。
     * 实测 {@code (.*a){20}$} 在仅 26 字符的输入下就要 4.4 秒 —— 而 {@code maxIncomingChars}
     * 允许到 240 字符，等于每来一条消息就把游戏冻住一次。默认那几条正则都是安全的
     * （只有字符类与固定次数重复），风险来自用户按网上示例抄进来的写法。
     *
     * <p>做法：逐条在守护线程上匹配，单条超过 {@link #REGEX_BUDGET_MS} 就放弃它、
     * 把这条正则按**文本**记入熔断名单（之后直接跳过，不再每条消息都白等一次），并继续试下一条。
     * 语义取舍很明确：超时 = 「这条规则不生效」= 消息照常翻译。宁可多花一次 API 请求，
     * 也绝不能冻结主线程；用户会从日志里看到是哪条正则被停用了。
     *
     * @return true 表示命中（应当忽略这条消息）；false 表示没命中**或**匹配超时被放弃
     */
    public static boolean matchesAny(String text, List<Pattern> patterns) {
        if (text == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern == null || disabledRegexes.containsKey(pattern.pattern())) {
                continue;
            }
            Boolean hit = matchWithBudget(pattern, text);
            if (hit == null) {
                // 超时：记一次「疑似」。**连续**两次才停用 —— 一次超时可能只是 GC 停顿，
                // 而误停用会让用户的忽略规则静默失效（多花 API 请求），比多等一次更糟。
                String regex = pattern.pattern();
                int strikes = timeoutStrikes.merge(regex, 1, Integer::sum);
                if (strikes >= REGEX_TIMEOUT_STRIKES) {
                    disabledRegexes.put(regex, "连续 " + strikes + " 次匹配超过 " + REGEX_BUDGET_MS + " ms");
                    Log.LOGGER.warn("ignorePatterns 里的正则「{}」连续 {} 次匹配超时，已停用。"
                                    + "多半是灾难性回溯的写法（例如 (.*a){20}$ 这类嵌套量词）；"
                                    + "改掉它并 /translator reload 即可恢复",
                            regex, strikes);
                } else {
                    Log.LOGGER.warn("ignorePatterns 里的正则「{}」本次匹配超过 {} ms（第 {} 次，"
                                    + "再超时一次就停用）。可能只是卡了一下，本次按「不命中」处理",
                            regex, REGEX_BUDGET_MS, strikes);
                }
                continue;
            }
            // 匹配成功就清掉全部「疑似」计数（v2.2.2）：一条正则在同一条消息上连着两次超时
            // 才说明它真的有问题；中间只要有**任何**一次匹配顺利完成，就说明这两次超时
            // 之间系统没有持续卡顿，更可能只是 GC/加载造成的偶发停顿，不该累积成停用。
            if (hit) {
                timeoutStrikes.clear();
                return true;
            }
        }
        return false;
    }

    /**
     * 在守护线程上跑一条正则，最多等 {@link #REGEX_BUDGET_MS}。
     *
     * @return true/false = 匹配结果；null = 超时（无法判定）
     */
    private static Boolean matchWithBudget(Pattern pattern, String text) {
        Future<Boolean> future = null;
        try {
            future = REGEX_WORKER.submit(() -> pattern.matcher(text).find());
            return future.get(REGEX_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 尽力取消；正则匹配不响应中断，那个线程会自己跑完（守护线程，不拦 JVM 退出）
            if (future != null) {
                future.cancel(true);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Boolean.FALSE;
        } catch (RuntimeException | java.util.concurrent.ExecutionException e) {
            // 执行期异常（例如栈溢出）：按「不命中」处理，绝不影响翻译主流程
            Log.LOGGER.warn("ignorePatterns 匹配出错，本条按「不命中」处理: {}", e.toString());
            return Boolean.FALSE;
        } catch (Error e) {
            // Error（例如 OOM）不能让它穿到 Fabric 事件回调上：这不是「翻译失败」，
            // 而是「这条忽略规则判不了」。
            Log.LOGGER.warn("ignorePatterns 匹配时抛出 Error，本条按「不命中」处理: {}", e.toString());
            return Boolean.FALSE;
        }
    }

    /** 本次会话里连续超时次数（达到 {@link #REGEX_TIMEOUT_STRIKES} 才移入停用名单）。 */
    private static final java.util.Map<String, Integer> timeoutStrikes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 因连续匹配超时被停用的正则（按正则文本 -> 原因）。
     *
     * <p>用文本而不是 Pattern 实例做键：{@code /translator reload} 会重新编译出**新的**
     * Pattern 对象，用实例做键的话坏正则会在 reload 后复活，又冻一次主线程。
     */
    private static final java.util.Map<String, String> disabledRegexes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 清空熔断名单，让所有正则重新参与匹配。
     *
     * <p>{@code /translator reload} 会调用它 —— 用户改了正则（或只是想让被误停用的规则复活）
     * 之后必须能恢复，否则唯一的办法就是重启游戏。
     */
    public static void resetRegexCircuit() {
        disabledRegexes.clear();
        timeoutStrikes.clear();
    }

    /** 当前被停用的正则条数（给 {@code /translator status} 与自检用）。 */
    public static int disabledRegexCount() {
        return disabledRegexes.size();
    }

    /** 被停用的正则文本（给状态命令列出原因用）。 */
    public static List<String> disabledRegexes() {
        return new ArrayList<>(disabledRegexes.keySet());
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
        cut = stripTrailing(cut);
        // 别把代理对（emoji 之类）劈成两半：留下孤立的高位代理会变成乱码，
        // 发到服务器还可能被判定为非法字符直接拒收。
        if (!cut.isEmpty() && Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
            cut = stripTrailing(cut.substring(0, cut.length() - 1));
        }
        return cut + "…";
    }

    /**
     * 把<b>不可信文本</b>压成一行并去掉格式代码，用于写进聊天栏。
     *
     * <p>典型来源是接口返回的错误正文（用户可能配了第三方中转站，内容是对方可控的）。
     * 直接拼进聊天栏会有两个后果：{@code §} 会被原版渲染成颜色代码，
     * 换行则会把一条提示拆成好几行刷屏。
     *
     * <p>注意与 {@link #stripFormattingCodes} 的区别：那个是「送去翻译前」清洗原文，
     * 这个是「显示给玩家前」兜底。
     */
    public static String sanitizeOneLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String stripped = stripFormattingCodes(text);
        StringBuilder builder = new StringBuilder(stripped.length());
        boolean pendingSpace = false;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                pendingSpace = true;
            } else if (c >= 0x20 && c != 0x7F) {
                // 不可见 / 双向格式字符必须丢掉（v3.0.0 洁净度审计补）。
                //
                // 这里处理的是**接口返回的文本**（译文、模型名、错误正文），来源可能是第三方中转站，
                // 对模组是不可信输入。旧实现只挡了 C0 控制字符，而下面这些都是「合法」字符、
                // 能原样进聊天栏 —— 其中 U+202E 会把**整行剩余部分的显示顺序反过来**：
                // 传入 "hello \u202Eworld" 时玩家看到的是被重排过的文本，可以用作视觉伪装。
                // 零宽字符则能在看起来正常的句子里藏东西（复制出来才发现不一样）。
                //
                // 刻意**保留** U+200C/U+200D（零宽不连字/连字）：它们是 emoji 组合
                // （👨‍👩‍👧）和部分文字连写所必需的，删掉会把玩家的 emoji 弄坏，
                // 而它们本身不能重排文本、也不是可滥用的伪装手段。
                if (isInvisibleFormat(c)) {
                    continue;
                }
                if (pendingSpace && builder.length() > 0) {
                    builder.append(' ');
                }
                pendingSpace = false;
                builder.append(c);
            }
            // 其余控制字符直接丢弃
        }
        return builder.toString();
    }

    /**
     * 是否是「看不见、但能影响显示」的格式字符 —— 进聊天栏前一律丢弃。
     *
     * <p>保留 {@code U+200C} / {@code U+200D}（emoji 组合需要），理由见 {@link #sanitizeOneLine}。
     */
    private static boolean isInvisibleFormat(char c) {
        return c == '\u00AD'                       // SOFT HYPHEN：看不见，复制出来却多一个字符
                || c == '\u200B'                   // ZERO WIDTH SPACE
                || c == '\u200E' || c == '\u200F'  // LRM / RLM：双向标记
                || (c >= '\u202A' && c <= '\u202E')// LRE / RLE / PDF / LRO / RLO（能重排整行）
                || (c >= '\u2066' && c <= '\u2069')// LRI / RLI / FSI / PDI：双向隔离
                || c == '\uFEFF'                   // BOM / ZERO WIDTH NO-BREAK SPACE
                || (c >= '\uE000' && c <= '\uF8FF');// 私用区：聊天字体没有字形，只会显示成方块
    }

    // ------------------------------------------------------------------
    // Java 8 语义兼容层（双版本共编共享层的必需品）
    //
    // 共享层由 Fabric(Java 25) 与 Forge 1.8.9(Java 8) 两个构建**编译同一份文件**，
    // 所以只能用 Java 8 的 API。下面这几个方法是对 Java 11+ 同名方法的**逐语义复刻**，
    // 不是「差不多就行」的替代：
    //
    // - Java 的 `isBlank` / `strip*` 用的是 `Character.isWhitespace`，
    //   而 Java 8 只有 `String.trim()`（按 `<= ' '` 裁，认不出全角空格、NBSP 之外的一些字符）。
    //   直接用 trim() 会让两条线对同一句话给出不同判断（例如 `\u3000` 全角空格）。
    // - `lines()` 按 \n / \r / \r\n 切分，且**不保留末尾空行**，与 split("\n") 不同。
    // - `repeat(n)` 在 n <= 0 时返回空串。
    //
    // 这些差异都会直接影响翻译决策（空消息判定、命令正文提取），所以必须精确对齐。
    // ------------------------------------------------------------------

    /** {@code String.isBlank()}（Java 11）的 Java 8 复刻：null 也算空白。 */
    public static boolean isBlank(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code String.strip()}（Java 11）的 Java 8 复刻。 */
    public static String strip(String text) {
        return text == null ? null : stripTrailing(stripLeading(text));
    }

    /** {@code String.stripLeading()}（Java 11）的 Java 8 复刻。 */
    public static String stripLeading(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return start == 0 ? text : text.substring(start);
    }

    /** {@code String.stripTrailing()}（Java 11）的 Java 8 复刻。 */
    public static String stripTrailing(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    /**
     * {@code String.lines()}（Java 11）的 Java 8 复刻。
     *
     * <p>按 {@code \n} / {@code \r} / {@code \r\n} 切分，且**不保留末尾空行**：
     * {@code "a\n"} 得到 {@code ["a"]}，空串得到空列表。
     */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** {@code String.repeat(int)}（Java 11）的 Java 8 复刻：n &lt;= 0 时返回空串。 */
    public static String repeat(String text, int times) {
        if (text == null || text.isEmpty() || times <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(text);
        }
        return builder.toString();
    }
}
