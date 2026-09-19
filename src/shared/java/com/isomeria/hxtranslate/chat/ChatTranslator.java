package com.isomeria.hxtranslate.chat;

import com.isomeria.hxtranslate.Log;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.core.TranslationService;
import com.isomeria.hxtranslate.util.CommandMessage;
import com.isomeria.hxtranslate.util.EchoMatcher;
import com.isomeria.hxtranslate.util.IncomingFilter;
import com.isomeria.hxtranslate.util.LangUtils;
import com.isomeria.hxtranslate.util.PlayerBlacklist;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 翻译的核心逻辑：
 *
 * <ul>
 *   <li>收到服务器消息（英文）→ 翻译成中文，作为一条客户端提示追加在下面；</li>
 *   <li>自己发送中文 → 取消原发送，翻译成英文后由模组发出去；</li>
 *   <li>自己发送英文 → 完全不干预。</li>
 * </ul>
 *
 * <p>“取消原发送 + 异步翻译 + 自己发一遍”是必须的：Fabric 的发送事件是同步回调，
 * 而网络请求要几百毫秒，不能在主线程里等。
 *
 * <p><b>这个类刻意不 import 任何 Minecraft 类</b>：与游戏打交道的部分收在
 * {@link ChatClientPort} 与 {@link FeedbackPort} 两个接口后面（生产实现分别是
 * {@link GameClient}、{@link GameFeedback}）。所以离线自检可以拿假实现驱动整条链路，
 * 断言「该翻的翻了 / 不该翻的没翻 / 翻译失败时中文没有漏到英文服 / 统计没有谎报」——
 * 这些正是历史上反复出 bug 的地方，见 {@link ChatClientPort} 的说明。
 */
public final class ChatTranslator {

    /** 记住最近发出的英文，避免服务器回显时又被翻译回中文（带时间窗，见 EchoMatcher）。 */
    private static final int RECENT_SENT_LIMIT = 8;

    /** 同一条聊天栏告警的最小间隔，避免接口异常时刷屏。 */
    private static final long WARN_INTERVAL_MS = 30_000L;

    /**
     * 最多同时跟踪多少条不同文案的「被节流条数」。
     *
     * <p>告警文案是有限几种（失败原因 / 限流 / 队列积压 / 未配 Key），8 已经远大于实际；
     * 设上限只是为了防「接口每次返回不同错误正文」这种情况把 Map 撑大。
     */
    private static final int MAX_TRACKED_WARNINGS = 8;

    /** 调试输出里原文的截断长度。 */
    private static final int DEBUG_TEXT_LIMIT = 60;

    /**
     * 忽略正则最多看多少字符（v2.2.2）。
     *
     * <p>比 {@code maxIncomingChars} 的默认值（240）宽松得多，所以正常消息一字不少；
     * 作用只是别把程序化注入的超长文本整条喂给用户写的正则（慢正则会因此跑很久，
     * 而它在渲染线程上执行）。
     */
    private static final int MAX_REGEX_INPUT_CHARS = 1024;

    /**
     * 聊天栏提示的行首品牌前缀（{@code §8[§bsct§8]}）。
     *
     * <p>做成常量是因为它曾经**在两个类里各写了一份**：更名时只改了装配层的「启动提示」，
     * 而 {@code GameFeedback}/{@code ForgeFeedback} 里 hint/error/success 用的还是旧品牌 {@code [hx]}，
     * 于是启动那一行显示新名字、此后的每条提示都显示旧名字（v3.0.1 审计发现）。
     * 现在只有一个来源，两线共用。
     */
    public static final String CHAT_PREFIX = "§8[§bsct§8]";

    /**
     * MERGE 模式（v3.1.0）里原文与译文之间的分隔符。
     *
     * <p>沿用 {@code includeOriginalInIncoming} 时代就有的 {@code ▏}：玩家已经认识它，
     * 语义也一样——「右边是同一条消息的译文」。译文自身仍带 {@code incomingPrefix}（[译]），
     * 双重锚点保证折行之后从属关系仍然看得出来。
     */
    public static final String MERGE_SEPARATOR = " §8▏ ";

    /**
     * MERGE 模式超时降级时，后到的译文行的行首（v3.1.0）。
     *
     * <p>译文超过 {@code mergeDeadlineSeconds} 才回来时，原文已经先放行了，
     * 这时译文用 {@code └} 缩进成「上一行的从属行」—— 视觉上仍然挂在原文下面，
     * 不再是「不知道谁说的」的独立一行。
     */
    public static final String MERGE_LATE_PREFIX = "§8└ ";

    /** MERGE 模式的等待状态（原文被扣住，等译文）。 */
    private static final int MERGE_PENDING = 0;
    /** 译文在期限内到达：原文 + 译文合并成一行。 */
    private static final int MERGE_MERGED = 1;
    /** 期限先到（或失败）：原文已先放行，后到的译文走 └ 从属行。 */
    private static final int MERGE_ORIGINAL_SHOWN = 2;

    /**
     * 「没配 Key」的统一提示。
     *
     * <p><b>不能在这里拼 {@link #CHAT_PREFIX}</b>（v3.0.8 修）：这两条文案只经由
     * {@code fallbackToOriginal → notifyFallback → feedback.error/hint} 出去，
     * 而两个装配层的 {@code error()}/{@code hint()} **自己就会加上前缀** ——
     * 在这里再拼一遍的结果是聊天栏显示两个 {@code [sct]}：
     * {@code §8[§bsct§8] §c§8[§bsct§8] §c未配置 DeepSeek API Key…}。
     * 前缀只有一个来源（装配层），与 {@code warnThrottled} 的文案保持同一种形状。
     */
    private static final String NO_KEY_HINT =
            "未配置 DeepSeek API Key（用 §f/translator key <你的Key>§c 配置）";
    /** 聊天方向多给一条退路：干脆关掉发送翻译。 */
    private static final String NO_KEY_HINT_WITH_OFF =
            NO_KEY_HINT + "，或 §f/translator outgoing off§c 关掉发送翻译";

    /**
     * 单人闸门在 debug 模式下给出的原因（收发两个方向共用同一句）。
     *
     * <p>必须同时说清「为什么」与「怎么打开」—— 否则玩家在单机里打了一大段中文却什么都没发生，
     * 只能以为是模组坏了。配置项名与打开方式都写全，因为这句提示是玩家唯一的线索。
     */
    private static final String SINGLEPLAYER_SKIP_REASON =
            "单人世界，且 translateInSingleplayer=false（用 /translator singleplayer on 可打开）";

    private final TranslatorConfig config;
    private final TranslationService service;
    private final ChatClientPort client;
    private final FeedbackPort feedback;
    /** 日志出口：离线自检里换成记录器，不再依赖 slf4j 的静态 LOGGER。 */
    private final Consumer<String> logger;
    /** 单调时钟（毫秒），给告警节流用；测试里可以自己控制。 */
    private final java.util.function.LongSupplier clock;

    private final Deque<EchoMatcher.Sent> recentlySent = new ArrayDeque<>();
    private final List<Pattern> compiledPatterns = new ArrayList<>();
    private List<String> compiledFrom;

    // ------------------------------------------------------------------
    // MERGE 模式的在途合并（v3.1.0）
    // ------------------------------------------------------------------

    /** 一条正在等译文的原文：原文显示已被取消，等译文回来合并或超时放行。 */
    private static final class PendingMerge {
        final long id;
        final Object originalComponent;
        /** 超时时刻（毫秒，来自 clock）；到点原文先放行。 */
        final long deadline;
        int state;

        PendingMerge(long id, Object originalComponent, long deadline) {
            this.id = id;
            this.originalComponent = originalComponent;
            this.deadline = deadline;
        }
    }

    /** 全部在途合并。操作都在 {@code synchronized(this)} 里做（回调线程 / 调度线程 / 主线程三方交汇）。 */
    private final Map<Long, PendingMerge> pendingMerges = new LinkedHashMap<>();
    private long mergeSeq;
    /**
     * 超时清扫线程：懒创建、单条守护线程，每 200ms 扫一次在途合并。
     * 离线自检不依赖它（用例直接调 {@link #expireMergeDeadlines(long)}），
     * 所以创建失败也不影响主流程。
     */
    private volatile java.util.concurrent.ScheduledExecutorService mergeSweeper;

    // 统计分「收到」「发出」两组，各自独立。
    // 以前只有一组：translatedCount 仅收到方向自增，而 failedCount 两个方向都自增，
    // 于是「只发中文、从不翻译收到的消息」的玩家会看到「已翻译 0 | 失败 1」，像是模组坏了。
    private final AtomicInteger receivedCount = new AtomicInteger();
    private final AtomicInteger translatedCount = new AtomicInteger();
    private final AtomicInteger skippedCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    /** 发出方向：译文成功发出去的条数。 */
    private final AtomicInteger sentCount = new AtomicInteger();
    /** 发出方向：没能翻译成的条数（不论最后是取消还是按原文发出）。 */
    private final AtomicInteger sendFailedCount = new AtomicInteger();

    private volatile String lastWarning;
    private volatile long lastWarningAt;
    /**
     * 被节流窗口省掉的告警条数，**按文案分桶**（v3.0.8 修）。
     *
     * <p>v3.0.7 用的是一个全局计数器，于是「刚才被省掉的 3 条超时提示」会被挂到
     * 下一条**完全不同**的告警后面，而且文案写着「同类」—— 报出来的数字是错的，
     * 比不报更误导。现在按文案各自计数，只报自己那一条被省掉几条。
     *
     * <p>桶数上限 {@link #MAX_TRACKED_WARNINGS}：告警文案本来就只有固定几种
     * （失败原因 / 限流 / 队列积压 / 未配 Key），上限只是防「接口每次返回不同错误正文」
     * 这种极端情况把 Map 撑大。
     */
    private final Map<String, Integer> suppressedWarnings = new LinkedHashMap<>();

    /** 生产环境用的构造器：日志走 slf4j，时钟走系统时间。 */
    public ChatTranslator(TranslatorConfig config, TranslationService service,
                          ChatClientPort client, FeedbackPort feedback) {
        this(config, service, client, feedback,
                message -> com.isomeria.hxtranslate.Log.LOGGER.info("[debug] {}", message),
                System::currentTimeMillis);
    }

    /**
     * 供离线自检使用的构造器：日志与时钟都可替换。
     *
     * <p>时钟可替换是为了确定性地测「告警节流」：同一分钟内该只报一次，
     * 靠 sleep 去测既慢又不稳。
     */
    public ChatTranslator(TranslatorConfig config, TranslationService service,
                          ChatClientPort client, FeedbackPort feedback,
                          Consumer<String> logger, java.util.function.LongSupplier clock) {
        this.config = config;
        this.service = service;
        this.client = client;
        this.feedback = feedback;
        this.logger = logger;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 事件入口（由 GameClient 注册到 Fabric 事件上）
    // ------------------------------------------------------------------

    /**
     * 收到服务器下发的消息（老签名，不带原消息组件 —— 离线自检与旧调用方在用）。
     *
     * @return true 表示装配层应该**取消原文的显示**（MERGE 模式接下了这条翻译，等译文一起合并）；
     *         false 表示照常显示原文
     */
    public boolean onIncoming(String rawText, boolean overlay, boolean hasSignedSender,
                              java.util.UUID senderId, String senderName) {
        return onIncoming(rawText, null, overlay, hasSignedSender, senderId, senderName);
    }

    /**
     * 收到服务器下发的消息（带原消息组件，v3.1.0 的 MERGE 模式入口）。
     *
     * <p>{@code originalComponent} 是装配层手里的原消息组件（Fabric 的 {@code Component} /
     * 1.8.9 的 {@code IChatComponent}，按 {@code Object} 传递）：MERGE 模式接下翻译时，
     * 原文显示被取消，等译文回来后经 {@code FeedbackPort.showMergedIncoming} 把它
     * 原样带着样式/悬停/点击事件重新显示成「原文 ▏ 译文」一行。
     *
     * <p>{@code senderId} 是签名聊天的发送者 UUID，代理服（Hypixel）的系统聊天给不出，
     * 传 {@code null}；{@code senderName} 是文本里能认出的说话人名字，用于黑名单与会话判断。
     *
     * <p>为什么要同时接「签名聊天」和「系统消息」两条链路：正常服务器的玩家聊天走签名聊天
     * （能拿到发送者，判断「是不是自己」最可靠）；而 Hypixel 是代理服，玩家聊天是以**系统消息**
     * 下发的，那条链路拿不到发送者，只能靠内容与回显比对来过滤。少接一条就会有一半场景失效。
     *
     * @return true 表示装配层应该**取消原文的显示**；false 表示照常显示原文
     */
    public boolean onIncoming(String rawText, Object originalComponent, boolean overlay,
                              boolean hasSignedSender, java.util.UUID senderId, String senderName) {
        if (overlay) {
            return false;
        }
        // 签名玩家聊天这条链路能拿到发送者，是本人就直接跳过（比字符串匹配更可靠）。
        if (hasSignedSender && senderId != null && client.isLocalPlayer(senderId)) {
            return false;
        }
        // 黑名单玩家：签名链路直接按 UUID 对应的名字判断最可靠；
        // 拿不到发送者时（系统聊天）退回到「文本里认说话人」，见 PlayerBlacklist。
        if (senderName != null && isBlacklisted(senderName)) {
            return false;
        }
        return handleIncoming(rawText, originalComponent);
    }

    /** 老签名（离线自检在用）：MERGE 逻辑需要组件，这里没有组件就按 APPEND 处理。 */
    public boolean handleIncoming(String plain) {
        return handleIncoming(plain, null);
    }

    private boolean handleIncoming(String plain, Object originalComponent) {
        if (!config.enabled || !config.translateIncoming) {
            return false;
        }
        // 闸门放在最前面（越早越好）：单人世界里默认整条链路都不走。
        //
        // 这里刻意不调 skipIncoming(...)：那个方法会先 shorten(text)，而闸门必须在 null 检查
        // **之前**就成立（闸门只看世界类型，与正文无关）。「单人世界跳过」这件事本身已经足够定位，
        // 不需要正文，所以直接自己加计数 + 打一行 debug。
        if (singleplayerBlocked()) {
            skippedCount.incrementAndGet();
            debug("跳过（" + SINGLEPLAYER_SKIP_REASON + "）");
            return false;
        }
        if (plain == null) {
            return false;
        }
        // 先剔除 §a、§r 这类原版格式代码：它们对翻译没有意义，还可能被模型当成正文
        String text = LangUtils.strip(LangUtils.stripFormattingCodes(plain));
        if (text.isEmpty()) {
            return false;
        }

        receivedCount.incrementAndGet();

        // 黑名单玩家（系统聊天拿不到发送者，只能按「名字:」模式识别）
        if (isBlacklistedSpeaker(text)) {
            skipIncoming("黑名单玩家", text);
            return false;
        }

        // 关键：不能「含汉字就跳过」。Hypixel 按客户端语言把队伍名本地化成 [红队]，
        // 于是英文喊话 "[MVP+] [红队] Steve: rush mid" 里也有汉字。
        // 具体判断规则见 IncomingFilter（那里有完整的说明和离线回归测试）。
        String ownEcho = ownEchoReason(text);
        IncomingFilter.Decision decision = IncomingFilter.decide(text, config, isIgnored(text), ownEcho != null);
        if (!decision.translate()) {
            skipIncoming(ownEcho == null ? decision.reason() : decision.reason() + "（" + ownEcho + "）", text);
            return false;
        }

        // MERGE 模式（v3.1.0）：原文与译文合并成一行。
        // 前提是装配层把原组件递进来了 —— 没有组件就退回 APPEND（译文另起一行），
        // 绝不为了合并去取消原文再拿纯文本重拼（那会把悬停/点击事件全丢掉）。
        final boolean mergeMode = "MERGE".equals(config.incomingDisplay) && originalComponent != null;
        //
        // 在途合并必须在 submit **之前**登记：缓存命中时回调立刻就在工作线程上跑，
        // 后登记的话回调拿到的还是 null，会错进 APPEND 路径显示两遍。
        // 提交被拒（限流/积压）就把它撤掉 —— 原文走正常显示，没有人扣着它。
        final PendingMerge pending = mergeMode
                ? registerPendingMerge(originalComponent, clock.getAsLong() + config.mergeDeadlineSeconds * 1000L)
                : null;

        TranslationService.SubmitResult submitted = service.submit(text, Direction.INCOMING, result -> {
            // 计数、告警与 debug 与 APPEND 模式**完全共享**（MERGE 只改「怎么显示」，
            // 不改「算不算翻过 / 要不要警告」—— 否则统计口径会出现两条线）。
            // 必须先判「无可译内容」（v3.0.7）：它既不是成功（没有译文）也不是失败
            // （模型没做错事），直接按失败处理会打出「翻译失败: null」。
            //
            // 典型输入就是整条消息只有一个玩家名（`hansert`、`kubo`、一串名字）：
            // 提示词本来就允许这类词原样保留，于是模型什么都翻不出来。静默跳过 ——
            // 原文那一行玩家已经看到了，再贴一遍译文没有任何意义。
            if (result.isNothingToTranslate()) {
                skippedCount.incrementAndGet();
                if (pending != null) {
                    showOriginalFor(pending);
                }
                debug("跳过（模型判定没有可译内容，原样返回）: " + shorten(text));
                return;
            }
            if (result.isStaleDropped()) {
                // 「排队超龄」（v3.1.0）：在队列里等太久，译文已经没有显示的意义。
                // 原文不受影响（APPEND 模式本来就在屏幕上；MERGE 模式此时也已按期限放行）。
                skippedCount.incrementAndGet();
                if (pending != null) {
                    showOriginalFor(pending);
                }
                debug("跳过（排队超时，接口响应太慢）: " + shorten(text));
                return;
            }
            if (!result.ok()) {
                failedCount.incrementAndGet();
                if (pending != null) {
                    showOriginalFor(pending);
                }
                if (config.debugLog) {
                    debug("翻译失败: " + result.error() + " §8| " + shorten(text));
                }
                // 出错提示做去重节流：接口挂了的时候不能每条消息刷一行红字
                if (config.showErrorsInChat && config.enabled) {
                    warnThrottled("翻译失败: " + result.error());
                }
                return;
            }
            if (!config.enabled) {
                // 玩家中途关了总闸：MERGE 模式下原文还被扣着，必须放行（绝不能凭空消失）
                if (pending != null) {
                    showOriginalFor(pending);
                }
                return;
            }
            translatedCount.incrementAndGet();
            String suffix = config.incomingPrefix + result.text();
            if (pending != null) {
                // MERGE 显示：期限内到达 → 原文 ▏ 译文 合并成一行；
                // 原文已因超时先放行 → 后到的译文补一行 └ 从属行
                PendingMerge claimed = claimForMerge(pending);
                if (claimed != null) {
                    feedback.showMergedIncoming(claimed.originalComponent, MERGE_SEPARATOR + suffix);
                } else {
                    feedback.info(MERGE_LATE_PREFIX + suffix);
                }
                return;
            }
            // 拼进聊天栏的原文同样只能是一行：服务器可以下发多行消息，
            // 换行会被原版拆成多条聊天行，把「[译] …」那行挤掉前缀、看起来像服务器说的话。
            String line = config.includeOriginalInIncoming
                    ? "§7" + LangUtils.sanitizeOneLine(text) + " §8▏ " + suffix
                    : suffix;
            feedback.info(line);
        });

        // Java 8 没有 switch 的箭头形式（1.8.9 那条线要用），改成经典 switch
        switch (submitted) {
            case ACCEPTED:
                if (pending != null) {
                    ensureMergeSweeper();
                    debug(String.format(Locale.ROOT, "正在翻译（合并显示，等译文 %.0f 秒）: %s",
                            (double) config.mergeDeadlineSeconds, shorten(text)));
                    return true;
                }
                // Locale.ROOT：不带 Locale 的 String.format 走 Locale.getDefault()，
                // 而默认区域在 Windows 与 Linux 上来源不同（区域设置 vs LANG），
                // 阿拉伯语等区域还会把数字换成非 ASCII 数字。调试输出的样子不该随机器变。
                debug(String.format(Locale.ROOT, "正在翻译（正文汉字占比 %.0f%%）: %s",
                        decision.hanRatio() * 100, shorten(text)));
                break;
            case NOT_READY:
            case RATE_LIMITED:
            case QUEUE_FULL:
            case EMPTY:
                if (pending != null) {
                    // 提交被拒：原文没有被扣住（装配层会照常显示），撤掉登记，别让清扫线程再放行一次
                    discardPendingMerge(pending.id);
                }
                // 以下与 APPEND 模式完全相同：提示原因、计「跳过」
                if (submitted == TranslationService.SubmitResult.NOT_READY) {
                    skipIncoming("未配置 API Key", text);
                    warnThrottled("未配置 DeepSeek API Key，收到的消息无法翻译。用 §f/translator key <你的Key> §c配置。");
                } else if (submitted == TranslationService.SubmitResult.RATE_LIMITED) {
                    skipIncoming("超出每分钟限流", text);
                    warnThrottled("翻译请求达到每分钟上限（" + config.requestsPerMinute
                            + " 次），部分消息没有翻译。可调大配置里的 §frequestsPerMinute§c。");
                } else if (submitted == TranslationService.SubmitResult.QUEUE_FULL) {
                    skipIncoming("翻译队列积压", text);
                    warnThrottled("接口变慢，排队中的翻译超过 " + config.maxPendingTranslations
                            + " 条，部分消息被先跳过（会自动恢复；持续出现可调大 §fmaxPendingTranslations§c）。");
                } else {
                    skipIncoming("空消息", text);
                }
                break;
            default:
                skipIncoming("空消息", text);
                break;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // MERGE 状态机（v3.1.0）
    //
    // 每条被扣住的原文有三个去向，谁先到谁生效，其余的不再操作显示：
    //   ① 译文期限内到达 → 原文 ▏ 译文 合并成一行（MERGED）；
    //   ② 超时先到（或翻译失败 / 无可译内容 / 排队超龄 / 玩家中途关总闸）
    //      → 原文原样先放行（ORIGINAL_SHOWN），后到的译文补一行 └ 从属行；
    //   ③ 提交被拒（限流 / 积压）→ 登记直接撤销，原文走正常显示路径。
    // ------------------------------------------------------------------

    /** 登记一条在途合并（调用方保证只在 MERGE 且 submit 之前调用）。 */
    private synchronized PendingMerge registerPendingMerge(Object component, long deadline) {
        mergeSeq++;
        PendingMerge entry = new PendingMerge(mergeSeq, component, deadline);
        pendingMerges.put(entry.id, entry);
        return entry;
    }

    /** 撤销一条在途登记（提交被拒时）：只移除，不做任何显示。 */
    private synchronized void discardPendingMerge(long id) {
        pendingMerges.remove(id);
    }

    /**
     * 放行原文（翻译失败 / 无可译内容 / 排队超龄 / 玩家关总闸时调用）。
     *
     * <p>认领 ORIGINAL_SHOWN 后把原组件原样交还聊天栏 —— 原文是玩家的原始聊天，
     * 任何情况下都不能因为 MERGE 扣住它而消失。
     */
    private void showOriginalFor(PendingMerge entry) {
        PendingMerge claimed = claimForOriginalShow(entry);
        if (claimed != null) {
            feedback.showOriginalIncoming(claimed.originalComponent);
        }
    }

    /** 认领并标记「原文已放行」；返回 null 表示这条登记已经不在 PENDING。 */
    private synchronized PendingMerge claimForOriginalShow(PendingMerge entry) {
        if (entry == null || entry.state != MERGE_PENDING) {
            return null;
        }
        entry.state = MERGE_ORIGINAL_SHOWN;
        pendingMerges.remove(entry.id);
        return entry;
    }

    /** 认领并标记「已合并显示」；返回 null 表示原文已经先放行（后到的译文走 └ 行）。 */
    private synchronized PendingMerge claimForMerge(PendingMerge entry) {
        if (entry == null || entry.state != MERGE_PENDING) {
            return null;
        }
        entry.state = MERGE_MERGED;
        pendingMerges.remove(entry.id);
        return entry;
    }

    /**
     * 超时清扫：把到点还没等到译文的原文放行（生产环境由 mergeSweeper 每 200ms 调一次；
     * 离线自检直接调它，用测试时钟获得确定性行为）。
     *
     * <p>公开放行的意义：MERGE 扣住原文的前提是「译文马上就到」。译文等不到时，
     * 原文是玩家自己的原始聊天，**任何情况下都不能让它消失** —— 那比译文慢更糟。
     *
     * @param nowMs 当前时刻（毫秒）
     * @return 本次放行了多少条
     */
    public int expireMergeDeadlines(long nowMs) {
        List<PendingMerge> expired = new ArrayList<>();
        synchronized (this) {
            for (java.util.Iterator<PendingMerge> it = pendingMerges.values().iterator(); it.hasNext(); ) {
                PendingMerge entry = it.next();
                if (entry.state == MERGE_PENDING && nowMs >= entry.deadline) {
                    entry.state = MERGE_ORIGINAL_SHOWN;
                    expired.add(entry);
                    it.remove();
                }
            }
        }
        for (PendingMerge entry : expired) {
            feedback.showOriginalIncoming(entry.originalComponent);
        }
        return expired.size();
    }

    /** 懒创建超时清扫线程：单条守护线程，每 200ms 扫一次。失败（极端环境拒绝建线程）不影响主流程。 */
    private void ensureMergeSweeper() {
        if (mergeSweeper != null) {
            return;
        }
        synchronized (this) {
            if (mergeSweeper != null) {
                return;
            }
            try {
                java.util.concurrent.ScheduledExecutorService sweeper =
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                            Thread thread = new Thread(runnable, "server_chat_translator-merge-sweeper");
                            thread.setDaemon(true);
                            return thread;
                        });
                sweeper.scheduleWithFixedDelay(() -> {
                    try {
                        expireMergeDeadlines(clock.getAsLong());
                    } catch (Throwable ignored) {
                        // 清扫出错绝不能弄死调度线程（否则后续超时全部失效）
                    }
                }, 200, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
                mergeSweeper = sweeper;
            } catch (Throwable t) {
                // 建不出来就退化成「没有清扫」：MERGE 的其他路径（成功合并 / 失败放行）都还在，
                // 只有「译文永远不回来」的极端情况会扣住原文 —— 与其在装配期崩，不如降级
                Log.LOGGER.warn("合并显示的超时清扫线程创建失败（译文超时将不自动放行原文）: {}", t.toString());
            }
        }
    }

    /** 停掉超时清扫线程（客户端退出时调用）。 */
    public void shutdown() {
        java.util.concurrent.ScheduledExecutorService sweeper = mergeSweeper;
        mergeSweeper = null;
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    /**
     * 单人世界里是否应该**拦下**翻译（v3.0.0）。
     *
     * <p>规则只有一条：<b>在单人世界、且 {@code translateInSingleplayer=false}（默认）时拦下</b>。
     * 玩家把那个开关打开后，单人世界的行为与多人完全一致。
     *
     * <p>为什么这个判断放在共享层而不是各线的装配层：装配层只负责回答事实
     * （{@link ChatClientPort#isSingleplayer()}），「这个事实要不要拦」是决策，
     * 必须留在共享层才能被离线自检覆盖。历史上所有「装配层自己写了一段判断逻辑」的地方
     * 都是自检够不到、只能靠人肉 review 的盲区。
     *
     * <p>收发两个方向共用一个出口，理由与 {@code fallbackToOriginal} 相同：规则只写一遍，
     * 以后新增入口也必须从这里过 —— 否则「接收方向拦了、发送方向忘了」这类分叉会以
     * 「单机里打中文还是被译成英文发出去」的形式出现在玩家面前。
     */
    private boolean singleplayerBlocked() {
        return !config.translateInSingleplayer && client.isSingleplayer();
    }

    /**
     * 当前是不是单人世界（把装配层的事实转发出来）。
     *
     * <p>给 {@code status} 显示用：玩家看到「单人世界：是，而 singleplayer 翻译是关的」
     * 才能理解「为什么单机里不翻」，否则只会以为模组坏了。
     * 状态显示必须与闸门走同一个出口（同一个 {@link ChatClientPort#isSingleplayer()}），
     * 不然显示的可能与实际行为不一致。
     */
    public boolean isSingleplayer() {
        return client.isSingleplayer();
    }

    /**
     * {@code /translator status} 里那行「单人世界 / 单人翻译」，文本由共享层生成。
     *
     * <p>为什么把文案放在共享层：两条线的 status 是各自装配的，但这句话的判据
     * （{@code isSingleplayer} 与 {@code translateInSingleplayer}）在共享层 ——
     * 文案留在这里，「显示的口径」与「闸门的口径」就永远不会漂移。两条线直接打印这行即可。
     */
    public String singleplayerStatusLine() {
        return "§7单人世界: " + (isSingleplayer() ? "§e是" : "§7否")
                + " §8| §7单人里翻译: " + (config.translateInSingleplayer ? "§a开" : "§c关")
                + (singleplayerBlocked() ? " §8(§7当前单人消息不翻译，用 §f/translator singleplayer on §7打开§8)" : "");
    }

    private void skipIncoming(String reason, String text) {
        skippedCount.incrementAndGet();
        debug("跳过（" + reason + "）: " + shorten(text));
    }

    /**
     * 聊天栏告警去重：同一条提示 {@value #WARN_INTERVAL_MS} 毫秒内只打一次。
     *
     * <p>接口挂了、Key 无效、被限流时，如果不节流就会每条消息刷一行红字，
     * 把聊天栏冲得没法看。
     *
     * <p><b>v3.0.7：被节流掉的数量不能再无声无息地丢掉。</b>玩家反馈的「有些消息没有译文」
     * 有一半来自这里 —— 两条**同样**的失败隔几秒先后发生，第二条一个字都不打，
     * 玩家只看到「有原文、没译文」，既不知道为什么，也不知道总共中招几条
     * （实测 2026-09-19：一局里 2 条失败，聊天栏只留下 1 行提示，第 2 条被这个窗口吞掉）。
     * 现在把窗口内省掉的条数攒起来，下一次真正打出来的告警后面附一句
     * 「另有 N 条同类提示已省略」：信息一条不少，也不会刷屏。
     *
     * <p><b>整个方法加 {@code synchronized}</b>（v3.0.8）：入站失败的回调在**工作线程**、
     * 出站降级在**主线程**，两边都会进来；「读 lastWarning → 比较 → 写回」以及
     * 「按文案分别累加计数」都是读-改-写，不加锁会丢计数（正是这条修复要解决的问题本身）。
     * 锁里只做 Map 操作与一次 {@code feedback.error}，而两个装配层的 error 都只是
     * 「切回主线程 / 排进任务队列」后立即返回，不存在回道回调本类的路径。
     */
    private synchronized void warnThrottled(String message) {
        long now = clock.getAsLong();
        if (message.equals(lastWarning) && now - lastWarningAt < WARN_INTERVAL_MS) {
            noteSuppressed(message);
            return;
        }
        // lastWarning 必须记**原始**文本（不带下面那句后缀），否则下一轮的相等判断永远不成立，
        // 节流会整个失效、退化成每条刷一行。
        lastWarning = message;
        lastWarningAt = now;
        // 只取**这一条文案**自己攒下的数（v3.0.8）：以前是全局一个计数器，
        // 会把另一条告警被省掉的条数算到头上，还写成「同类」。
        Integer suppressed = suppressedWarnings.remove(message);
        if (suppressed != null && suppressed > 0) {
            feedback.error(message + "（期间另有 " + suppressed + " 条同类提示已省略）");
            return;
        }
        feedback.error(message);
    }

    /** 记一次「这条文案被节流省掉了」，并保证桶数不无限增长（按插入顺序淘汰最旧的）。 */
    private void noteSuppressed(String message) {
        Integer previous = suppressedWarnings.get(message);
        suppressedWarnings.put(message, previous == null ? 1 : previous + 1);
        while (suppressedWarnings.size() > MAX_TRACKED_WARNINGS) {
            java.util.Iterator<String> oldest = suppressedWarnings.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private void debug(String message) {
        if (!config.debugLog) {
            return;
        }
        // 日志出口必须「只增信息、不加风险」：这个方法会在事件回调里（主线程）被同步调用，
        // 日志实现一旦抛错就直接穿到 Fabric 的事件链上。日志写不出来是小事，崩游戏是大事。
        try {
            logger.accept(message);
        } catch (Throwable ignored) {
            // 刻意吞掉：debug 日志永远不该成为故障源
        }
        feedback.hint(message);
    }

    private static String shorten(String text) {
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= DEBUG_TEXT_LIMIT) {
            return oneLine;
        }
        String cut = oneLine.substring(0, DEBUG_TEXT_LIMIT);
        // 别把代理对（emoji）劈成两半：孤立的高位代理在聊天栏/日志里是乱码方块。
        // 这是 LangUtils.truncateForChat 里同一条规则的漏网分支（那边加了保护，这里漏了）。
        if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    /**
     * 这条消息是否命中用户配置的忽略正则。
     *
     * <p>v2.2.2：匹配前先把文本截到 {@code maxIncomingChars} 的长度 ——
     * 忽略规则只对「本来会送去翻译的消息」有意义，而超过上限的消息在
     * {@link IncomingFilter} 里本来就会被跳过；不截断的话，用户写了一条慢正则时，
     * 一条上万字符的消息（插件/模组可以程序化注入聊天）会喂给正则跑很久。
     * 截断只影响「超长消息是否命中忽略规则」，而那种消息本来就不翻译，语义无损失。
     */
    private boolean isIgnored(String text) {
        if (text == null) {
            return false;
        }
        String bounded = text.length() > MAX_REGEX_INPUT_CHARS
                ? text.substring(0, MAX_REGEX_INPUT_CHARS)
                : text;
        return LangUtils.matchesAny(bounded, patterns());
    }

    /**
     * 配置里的正则只在内容变化时重新编译。
     *
     * <p>编译规则本身在 {@link LangUtils#compilePatterns} 里，和离线自检共用同一份 ——
     * 免得「用例测的是自检自己抄的一份实现」。
     */
    private List<Pattern> patterns() {
        List<String> source = config.ignorePatterns;
        if (source == compiledFrom) {
            return compiledPatterns;
        }
        compiledPatterns.clear();
        compiledPatterns.addAll(LangUtils.compilePatterns(source,
                regex -> logger.accept("忽略无效的 ignorePatterns 正则: " + regex)));
        compiledFrom = source;
        return compiledPatterns;
    }

    /** 名字是否在「永不翻译」黑名单里（朋友是中国人时很有用）。 */
    private boolean isBlacklisted(String name) {
        return PlayerBlacklist.matchesName(name, config.blacklistedPlayers);
    }

    /**
     * 系统聊天里拿不到发送者，只能在文本里找「名字:」/「名字 &gt;」这样的模式。
     * 判断细节见 {@link PlayerBlacklist}。
     */
    private boolean isBlacklistedSpeaker(String text) {
        return PlayerBlacklist.speaksIn(text, config.blacklistedPlayers);
    }

    /**
     * 判断这条消息是不是自己刚发出去、被服务器回显回来的。
     *
     * <p>匹配逻辑（整条一致 + 时间窗）抽在 {@link EchoMatcher} 里，那里有离线回归测试。
     *
     * @return 命中的那条自己发过的消息；不是自己的回显则返回 null
     */
    private synchronized String findOwnEcho(String plain) {
        return EchoMatcher.findEcho(plain, recentlySent);
    }

    /**
     * 这条消息是不是「自己发的」，并给出用于调试输出的说明。
     *
     * <p><b>优先认说话人名字</b>：Hypixel 的系统聊天里本来就写着谁在说话，
     * 认名字既能准确认出自己的回显，又不会把别人说的同一句话误当成回显。
     * 以前只看「正文和我发过的一样不一样」，于是你自己说了句 {@code gg} 之后，
     * 15 秒内别人说的每个 {@code gg} 都会被静默跳过 —— 玩家反馈的「别人的 gg 不翻译」。
     *
     * <p>只有认不出说话人（消息格式没见过）时才退回正文比对。
     *
     * @return null 表示不是自己的消息；否则返回给玩家/日志看的说明
     */
    private String ownEchoReason(String text) {
        if (!config.skipOwnEcho) {
            return null;
        }
        Boolean own = EchoMatcher.isOwnMessage(text, client.localPlayerName());
        if (own != null) {
            return own ? "自己发的消息" : null;
        }
        // 认不出说话人（格式没见过）：退回正文比对，那一步带 15 秒时间窗
        String matched = findOwnEcho(text);
        return matched == null ? null : "匹配到自己发过的 \"" + shorten(matched) + "\"";
    }

    /**
     * 这条收到的消息会不会被当成「自己的回显」而跳过翻译。
     *
     * <p>公开出来是给离线自检用的：发送失败时那段英文**不该**进回显名单，
     * 否则 15 秒内别人说的同一句会被误判成回显而漏翻。判据必须与生产路径同一份实现
     * （{@link #ownEchoReason}），不能在自检里另抄一遍。
     */
    public boolean isOwnEcho(String incomingText) {
        return ownEchoReason(incomingText) != null;
    }

    private synchronized void rememberSent(String english) {
        EchoMatcher.Sent sent = EchoMatcher.Sent.now(english);
        if (sent.text().isEmpty()) {
            return;
        }
        recentlySent.addLast(sent);
        while (recentlySent.size() > RECENT_SENT_LIMIT) {
            recentlySent.removeFirst();
        }
    }

    /** 给 /translator status 用的统计信息（收到方向）。 */
    public String counters() {
        return "§7收到 §f" + receivedCount.get() + " §7条 §8| §a译 §f" + translatedCount.get()
                + " §8| §e跳过 §f" + skippedCount.get() + " §8| §c失败 §f" + failedCount.get();
    }

    /** 给 /translator status 用的统计信息（发出方向）。 */
    public String sendCounters() {
        return "§7发出 §a译文 §f" + sentCount.get() + " §7条 §8| §c未能翻译 §f"
                + sendFailedCount.get() + " §7条";
    }

    public void resetCounters() {
        receivedCount.set(0);
        translatedCount.set(0);
        skippedCount.set(0);
        failedCount.set(0);
        sentCount.set(0);
        sendFailedCount.set(0);
    }

    // ------------------------------------------------------------------
    // 发送消息
    // ------------------------------------------------------------------

    /**
     * @return true 表示放行原消息；false 表示取消本次发送（我们会在翻译完成后自己发）。
     */
    public boolean onSendChat(String message) {
        if (!config.enabled || !config.translateOutgoing) {
            return true;
        }
        if (message == null) {
            return true;
        }
        // 单人闸门：返回 true = 放行原消息（原样发出去），与「不翻译」的其它路径同义。
        //
        // 位置在 null 检查之后：闸门本身与正文无关，但 debug 那行要用 shorten(message)，
        // 而 shorten 不接 null（它直接对 text 调 replace）。为了「最早」而把闸门提到 null
        // 检查之前，就会在「单人世界 + message 为 null」时抛 NPE —— 那正是这道闸门要避免的
        // 「莫名其妙崩」。
        if (singleplayerBlocked()) {
            skippedCount.incrementAndGet();
            debug("跳过（" + SINGLEPLAYER_SKIP_REASON + "）: " + shorten(message));
            return true;
        }
        if (LangUtils.isBlank(message) || message.startsWith("/")) {
            return true;
        }
        // 去掉 § 格式代码后再判断/翻译；但真要原样放行时发的还是原始字符串
        String translatable = LangUtils.stripFormattingCodes(message);
        if (LangUtils.isBlank(translatable)) {
            return true;
        }
        // 没有中文就原样发送：这是“我输入英文则无视”的实现。
        // 但要把它记下来，否则服务器把这条英文回显回来时会被翻译成中文（多此一举）。
        if (!LangUtils.containsHan(translatable)) {
            rememberSent(translatable);
            return true;
        }
        if (!service.isReady()) {
            // 没配 Key 也算「翻译不了」，和 failureFallback 保持一致
            return fallbackToOriginal(NO_KEY_HINT_WITH_OFF, "本条");
        }

        // 记下发起翻译时所在的连接：翻译回来时如果已经不是同一个连接，
        // 说明中途切了服务器/退了世界，绝不能把这条消息发到别的服务器去。
        Object originConnection = client.currentConnection();

        TranslationService.SubmitResult submitted = service.submit(translatable, Direction.OUTGOING,
                result -> client.execute(() -> {
                    if (!client.isSameConnection(originConnection)) {
                        // 连接已断开就静默放弃（玩家已经在主菜单）；换过服才提示
                        if (client.currentConnection() != null) {
                            feedback.error("期间切换了服务器，这条翻译已取消，没有发出去。");
                        }
                        return;
                    }
                    if (result.isNothingToTranslate()) {
                        // 理论上到不了这里：「无可译内容」只在「英→中」方向判定，而这条是「中→英」。
                        // 但状态是共享的，所以照样收口到同一个降级出口 ——
                        // 不能落到下面的失败分支去，那会打出「翻译失败: null」。
                        if (fallbackToOriginal("模型判定这条没有可译内容", "本条")) {
                            sendProgrammatically(message, false);
                        }
                        return;
                    }
                    if (result.isStaleDropped()) {
                        // 「排队超龄」（v3.1.0）：队列里等太久被丢弃。必须在这里收口 ——
                        // 它的 ok=false、error=null，掉进下面的失败分支会打出「翻译失败: null」。
                        // 与其它五条降级路径同一个出口：按 failureFallback 决定发不发原文。
                        if (fallbackToOriginal("翻译等待超时（接口响应太慢）", "本条")) {
                            sendProgrammatically(message, false);
                        }
                        return;
                    }
                    if (!result.ok()) {
                        // 配置成「失败就发原文」时才降级发送
                        if (fallbackToOriginal("翻译失败: " + result.error(), "本条")) {
                            sendProgrammatically(message, false);
                        }
                        return;
                    }
                    String outgoing = truncateTranslated(result.text(), config.maxOutgoingChars, "");
                    if (!sendProgrammatically(outgoing, false)) {
                        // 发送本身失败：sendProgrammatically 已经在聊天栏报错，这里不再谎报成功。
                        // 也**不能**把它记进回显名单（v2.1.4）：这段英文从未出现在服务器上，
                        // 记进去会让 15 秒内别人说的同一句被误判成「自己的回显」而漏翻。
                        return;
                    }
                    // 记进回显名单前先剥格式代码（v2.2.2）。这是实测出来的真缺口，不是理论问题：
                    // 模型偶尔会回一个末尾带 § 的译文（`sanitizeOneLine` 只管「§ + 后一个字符」，
                    // 末尾孤立的 § 会原样留下），那样入名单记的是 `gg§`，而服务器回显是 `gg`
                    // （§ 只是客户端的格式指令，不占正文）→ 正文比对永远失配，
                    // 「自己的回显不再翻回中文」恰好会在最需要它的时候失效。
                    rememberSent(LangUtils.stripFormattingCodes(outgoing));
                    sentCount.incrementAndGet();
                    feedback.info(config.outgoingPrefix + outgoing);
                }));

        if (!submitted.accepted()) {
            return fallbackToOriginal(rejectedReason(submitted), "本条");
        }

        feedback.actionBar("§e⏳ 翻译中…");
        return false;
    }

    /** 翻译请求没被受理的原因，写进聊天栏提示。 */
    private String rejectedReason(TranslationService.SubmitResult result) {
        // Java 8 没有 switch 表达式，改成经典 switch
        switch (result) {
            case RATE_LIMITED:
                return "本分钟翻译请求已达上限（" + config.requestsPerMinute + " 次）";
            case QUEUE_FULL:
                return "翻译请求积压超过 " + config.maxPendingTranslations + " 条（接口变慢了）";
            default:
                return "翻译请求未被受理";
        }
    }

    /** 翻译失败时是否按原文发出去（配置项 failureFallback）。 */
    private boolean sendOriginalOnFailure() {
        return "SEND_ORIGINAL".equalsIgnoreCase(config.failureFallback);
    }

    /**
     * 出站降级统一出口：这条内容没能译成英文时怎么办。
     *
     * <p>发送方向一共有五条路径会走到这里：没配 Key、被限流、队列积压、翻译失败、译文仍是中文。
     * 它们必须给出同一个答案（都看 {@code failureFallback}）。
     * v1.0.8 之前「被限流 / 队列积压」那两条是各写各的，就漏掉了这个判断，
     * 把中文原文直接发到了英文服 —— 所以这里刻意只留一个出口，以后新增路径也只能从这里过。
     *
     * @param reason  给玩家看的原因，例如「未配置 DeepSeek API Key」
     * @param subject 主语，{@code "本条"} 或 {@code "这条命令"}
     * @return true = 放行原消息（按原文发出去）；false = 取消本次发送
     */
    private boolean fallbackToOriginal(String reason, String subject) {
        sendFailedCount.incrementAndGet();
        // 两种降级默认都用红字：即使按原文发出去了，也意味着「中文可能已经出现在英文服里」，
        // 这是玩家最该注意到的情况，不能只给一条灰色提示。
        //
        // 但 v2.1.4 起 `showErrorsInChat=false` **不再让这里彻底静默**：玩家侧的表现原本是
        // 「看到 ⏳ 翻译中…，然后消息凭空消失」，既不知道发没发出去、也不知道内容还在不在。
        // 关掉这个开关的意图是「别刷屏」，不是「别告诉我」，所以降级为一条灰色提示。
        if (sendOriginalOnFailure()) {
            notifyFallback(reason + "，" + subject + "未翻译，仍按原文发送。");
            return true;
        }
        notifyFallback(reason + "，" + subject + "未发送（按 ↑ 可找回刚才的内容）。");
        return false;
    }

    /** 降级提示的出口：默认红字，关掉 {@code showErrorsInChat} 时降为灰色提示，但绝不静默。 */
    private void notifyFallback(String message) {
        if (config.showErrorsInChat) {
            feedback.error(message);
        } else {
            feedback.hint(message);
        }
    }

    /** 按预算截断译文；真截断了就在聊天栏说明原因（预算的来源两个方向不同）。 */
    private String truncateTranslated(String translated, int budget, String note) {
        String outgoing = LangUtils.truncateForChat(translated, budget);
        if (!outgoing.equals(translated) && config.showErrorsInChat) {
            feedback.hint("译文超过 " + budget + " 字符" + note + "，已截断。");
        }
        return outgoing;
    }

    /** 命令字符串没有前导斜杠，这是原版 ChatScreen 的行为。 */
    public boolean onSendCommand(String command) {
        if (!config.enabled || !config.translateCommandMessages) {
            return true;
        }
        if (command == null) {
            return true;
        }
        // 单人闸门（与 onSendChat 同一条规则、同一个出口）。命令也走它：
        // 否则单机里 `/shout 大家好` 仍会被翻译后当命令发出去。
        if (singleplayerBlocked()) {
            skippedCount.incrementAndGet();
            debug("跳过（" + SINGLEPLAYER_SKIP_REASON + "）: /" + shorten(command));
            return true;
        }
        if (LangUtils.isBlank(command)) {
            return true;
        }

        // 拆出「命令头（命令名 + 玩家名等参数）」和「待翻译正文」。
        // 三层识别：显式名单 -> /party chat 这类二义性命令 -> 未知命令兜底，见 CommandMessage。
        CommandMessage.Split split = CommandMessage.resolve(command, config);
        if (split == null) {
            debug("命令未翻译（不在 translateCommandArgs 名单里，或属于管理命令）: /" + shorten(command));
            return true;
        }
        String head = split.head();
        String message = LangUtils.stripFormattingCodes(split.message());
        if (LangUtils.isBlank(message)) {
            return true;
        }
        // 命令正文是英文时原样放行，但要记住，避免服务器回显时又被翻成中文
        if (!LangUtils.containsHan(message)) {
            rememberSent(message);
            return true;
        }
        if (!service.isReady()) {
            return fallbackToOriginal(NO_KEY_HINT, "这条命令");
        }

        Object originConnection = client.currentConnection();

        TranslationService.SubmitResult submitted = service.submit(message, Direction.OUTGOING,
                result -> client.execute(() -> {
                    if (!client.isSameConnection(originConnection)) {
                        if (client.currentConnection() != null) {
                            feedback.error("期间切换了服务器，这条命令已取消，没有发出去。");
                        }
                        return;
                    }
                    if (result.isNothingToTranslate()) {
                        // 同 onSendChat：发送方向理论上不会走到这里，但收口到同一个降级出口，
                        // 免得掉进失败分支打出「翻译失败: null」。
                        if (fallbackToOriginal("模型判定这条命令没有可译内容", "这条命令")) {
                            sendProgrammatically(head + message, true);
                        }
                        return;
                    }
                    if (result.isStaleDropped()) {
                        // 「排队超龄」（v3.1.0）：同 onSendChat，收口到同一个降级出口
                        if (fallbackToOriginal("翻译等待超时（接口响应太慢）", "这条命令")) {
                            sendProgrammatically(head + message, true);
                        }
                        return;
                    }
                    if (!result.ok()) {
                        if (fallbackToOriginal("命令内容翻译失败: " + result.error(), "这条命令")) {
                            sendProgrammatically(head + message, true);
                        }
                        return;
                    }
                    // 命令总长同样受原版 256 字符限制，命令名 + 玩家名（head）也要占额度，
                    // 否则给名字很长的玩家发长句时整条命令会超限被服务器拒绝
                    String outgoing = truncateTranslated(result.text(),
                            Math.max(16, config.maxOutgoingChars - head.length()),
                            "（要给命令本身留位置）");
                    String payload = head + outgoing;
                    if (!sendProgrammatically(payload, true)) {
                        // 同上：没发出去就不算发出，也不记进回显名单（v2.1.4）
                        return;
                    }
                    rememberSent(outgoing);
                    sentCount.incrementAndGet();
                    feedback.info(config.outgoingPrefix + "/" + payload);
                }));

        if (!submitted.accepted()) {
            return fallbackToOriginal(rejectedReason(submitted), "这条命令");
        }

        feedback.actionBar("§e⏳ 翻译中…");
        return false;
    }

    /**
     * 真正把内容发出去。
     *
     * <p>返回 {@code false} 表示这次发送失败了：调用方**不能**再记一条「发出译文」，
     * 也不能打一行「[→EN] …」的回显 —— 否则聊天栏和统计都会声称一条根本没发出去的消息已经发出。
     */
    private boolean sendProgrammatically(String payload, boolean asCommand) {
        boolean sent = asCommand ? client.sendCommand(payload) : client.sendChat(payload);
        if (!sent) {
            // 具体失败原因（异常类型/消息）由实现打在日志里，这里只告诉玩家「没发出去」。
            // 同 fallbackToOriginal：这条也绝不静默，否则玩家会以为已经发出去了。
            // 「按 ↑ 可找回」不能省（v2.2.3 补）：玩家看到「没发出去」的第一反应是
            // 「我打的字呢？」—— 同一个降级出口的另一条路径本来就写了这句，这里漏了。
            notifyFallback("发送失败，这条内容没有发出去（按 ↑ 可找回刚才的内容）。");
        }
        return sent;
    }
}
