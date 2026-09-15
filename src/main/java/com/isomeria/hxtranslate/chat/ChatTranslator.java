package com.isomeria.hxtranslate.chat;

import com.isomeria.hxtranslate.HxTranslateClient;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.core.TranslationService;
import com.isomeria.hxtranslate.util.CommandMessage;
import com.isomeria.hxtranslate.util.EchoMatcher;
import com.isomeria.hxtranslate.util.IncomingFilter;
import com.isomeria.hxtranslate.util.LangUtils;
import com.isomeria.hxtranslate.util.PlayerBlacklist;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 */
public final class ChatTranslator {

    /** 记住最近发出的英文，避免服务器回显时又被翻译回中文（带时间窗，见 EchoMatcher）。 */
    private static final int RECENT_SENT_LIMIT = 8;

    /** 同一条聊天栏告警的最小间隔，避免接口异常时刷屏。 */
    private static final long WARN_INTERVAL_MS = 30_000L;

    /** 调试输出里原文的截断长度。 */
    private static final int DEBUG_TEXT_LIMIT = 60;

    /** 「没配 Key」的统一提示。 */
    private static final String NO_KEY_HINT =
            "未配置 DeepSeek API Key（用 §f/hxtranslate key <你的Key>§c 配置）";
    /** 聊天方向多给一条退路：干脆关掉发送翻译。 */
    private static final String NO_KEY_HINT_WITH_OFF =
            NO_KEY_HINT + "，或 §f/hxtranslate outgoing off§c 关掉发送翻译";

    private final TranslatorConfig config;
    private final TranslationService service;

    private final Deque<EchoMatcher.Sent> recentlySent = new ArrayDeque<>();
    private final List<Pattern> compiledPatterns = new ArrayList<>();
    private List<String> compiledFrom;

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

    /** 模组自己调用 sendChat/sendCommand 时要忽略事件，否则会无限递归。 */
    private volatile boolean programmaticSend;
    private volatile String lastWarning;
    private volatile long lastWarningAt;

    public ChatTranslator(TranslatorConfig config, TranslationService service) {
        this.config = config;
        this.service = service;
    }

    public void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(this::onSendChat);
        ClientSendMessageEvents.ALLOW_COMMAND.register(this::onSendCommand);
        ClientReceiveMessageEvents.GAME.register(this::onGameMessage);
        ClientReceiveMessageEvents.CHAT.register(this::onChatMessage);
    }

    // ------------------------------------------------------------------
    // 收到消息
    // ------------------------------------------------------------------

    private void onGameMessage(Component message, boolean overlay) {
        if (overlay) {
            return;
        }
        handleIncoming(message.getString());
    }

    private void onChatMessage(Component message, PlayerChatMessage signedMessage,
                               GameProfile sender, ChatType.Bound bound, Instant receivedAt) {
        // 签名玩家聊天这条链路能拿到发送者，是本人就直接跳过（比字符串匹配更可靠）。
        // Hypixel 等代理服走的是系统聊天，没有发送者信息，那边靠 EchoMatcher 兜底。
        if (isLocalPlayer(sender)) {
            return;
        }
        if (sender != null && isBlacklisted(sender.name())) {
            return;
        }
        handleIncoming(message.getString());
    }

    private boolean isLocalPlayer(GameProfile sender) {
        if (sender == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.player != null
                && minecraft.player.getUUID().equals(sender.id());
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

    public void handleIncoming(String plain) {
        if (!config.enabled || !config.translateIncoming) {
            return;
        }
        if (plain == null) {
            return;
        }
        // 先剔除 §a、§r 这类原版格式代码：它们对翻译没有意义，还可能被模型当成正文
        String text = LangUtils.stripFormattingCodes(plain).strip();
        if (text.isEmpty()) {
            return;
        }

        receivedCount.incrementAndGet();

        // 黑名单玩家（系统聊天拿不到发送者，只能按「名字:」模式识别）
        if (isBlacklistedSpeaker(text)) {
            skipIncoming("黑名单玩家", text);
            return;
        }

        // 关键：不能「含汉字就跳过」。Hypixel 按客户端语言把队伍名本地化成 [红队]，
        // 于是英文喊话 "[MVP+] [红队] Steve: rush mid" 里也有汉字。
        // 具体判断规则见 IncomingFilter（那里有完整的说明和离线回归测试）。
        String ownEcho = ownEchoReason(text);
        IncomingFilter.Decision decision = IncomingFilter.decide(text, config, isIgnored(text), ownEcho != null);
        if (!decision.translate()) {
            skipIncoming(ownEcho == null ? decision.reason() : decision.reason() + "（" + ownEcho + "）", text);
            return;
        }

        TranslationService.SubmitResult submitted = service.submit(text, Direction.INCOMING, (ok, translated, error) -> {
            if (!ok) {
                failedCount.incrementAndGet();
                if (config.debugLog) {
                    debug("翻译失败: " + error + " §8| " + shorten(text));
                }
                // 出错提示做去重节流：接口挂了的时候不能每条消息刷一行红字
                if (config.showErrorsInChat && config.enabled) {
                    warnThrottled("翻译失败: " + error);
                }
                return;
            }
            if (!config.enabled) {
                return;
            }
            translatedCount.incrementAndGet();
            // 拼进聊天栏的原文同样只能是一行：服务器可以下发多行消息，
            // 换行会被原版拆成多条聊天行，把「[译] …」那行挤掉前缀、看起来像服务器说的话。
            String line = config.includeOriginalInIncoming
                    ? "§7" + LangUtils.sanitizeOneLine(text) + " §8▏ " + config.incomingPrefix + translated
                    : config.incomingPrefix + translated;
            Feedback.info(line);
        });

        switch (submitted) {
            case ACCEPTED -> debug(String.format("正在翻译（正文汉字占比 %.0f%%）: %s",
                    decision.hanRatio() * 100, shorten(text)));
            case NOT_READY -> {
                skipIncoming("未配置 API Key", text);
                warnThrottled("未配置 DeepSeek API Key，收到的消息无法翻译。用 §f/hxtranslate key <你的Key> §c配置。");
            }
            case RATE_LIMITED -> {
                skipIncoming("超出每分钟限流", text);
                warnThrottled("翻译请求达到每分钟上限（" + config.requestsPerMinute
                        + " 次），部分消息没有翻译。可调大配置里的 §frequestsPerMinute§c。");
            }
            case QUEUE_FULL -> {
                skipIncoming("翻译队列积压", text);
                warnThrottled("翻译请求积压超过 " + config.maxPendingTranslations
                        + " 条（接口变慢了），已跳过部分消息。");
            }
            case EMPTY -> skipIncoming("空消息", text);
        }
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
     */
    private void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (message.equals(lastWarning) && now - lastWarningAt < WARN_INTERVAL_MS) {
            return;
        }
        lastWarning = message;
        lastWarningAt = now;
        Feedback.error(message);
    }

    private void debug(String message) {
        if (!config.debugLog) {
            return;
        }
        HxTranslateClient.LOGGER.info("[debug] {}", message);
        Feedback.hint(message);
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

    private boolean isIgnored(String text) {
        return LangUtils.matchesAny(text, patterns());
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
                regex -> HxTranslateClient.LOGGER.warn("忽略无效的 ignorePatterns 正则: {}", regex)));
        compiledFrom = source;
        return compiledPatterns;
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
        Boolean own = EchoMatcher.isOwnMessage(text, localPlayerName());
        if (own != null) {
            return own ? "自己发的消息" : null;
        }
        // 认不出说话人（格式没见过）：退回正文比对，那一步带 15 秒时间窗
        String matched = findOwnEcho(text);
        return matched == null ? null : "匹配到自己发过的 \"" + shorten(matched) + "\"";
    }

    /** 本地玩家的名字；还没进入世界时返回 null（那时也不会有聊天可处理）。 */
    private String localPlayerName() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return null;
        }
        GameProfile profile = minecraft.player.getGameProfile();
        return profile == null ? null : profile.name();
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

    /** 给 /hxtranslate status 用的统计信息（收到方向）。 */
    public String counters() {
        return "§7收到 §f" + receivedCount.get() + " §7条 §8| §a译 §f" + translatedCount.get()
                + " §8| §e跳过 §f" + skippedCount.get() + " §8| §c失败 §f" + failedCount.get();
    }

    /** 给 /hxtranslate status 用的统计信息（发出方向）。 */
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
    private boolean onSendChat(String message) {
        if (programmaticSend || !config.enabled || !config.translateOutgoing) {
            return true;
        }
        if (message == null || message.isBlank() || message.startsWith("/")) {
            return true;
        }
        // 去掉 § 格式代码后再判断/翻译；但真要原样放行时发的还是原始字符串
        String translatable = LangUtils.stripFormattingCodes(message);
        if (translatable.isBlank()) {
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
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener originConnection = minecraft == null ? null : minecraft.getConnection();

        TranslationService.SubmitResult submitted = service.submit(translatable, Direction.OUTGOING,
                (ok, translated, error) -> runOnClientThread(client -> {
                    ClientPacketListener connection = connectionOrWarn(client, originConnection, "这条翻译");
                    if (connection == null) {
                        return;
                    }
                    if (!ok) {
                        // 配置成「失败就发原文」时才降级发送
                        if (fallbackToOriginal("翻译失败: " + error, "本条")) {
                            sendProgrammatically(connection, message, false);
                        }
                        return;
                    }
                    String outgoing = truncateTranslated(translated, config.maxOutgoingChars, "");
                    rememberSent(outgoing);
                    if (!sendProgrammatically(connection, outgoing, false)) {
                        // 发送本身失败：sendProgrammatically 已经在聊天栏报错，这里不再谎报成功
                        return;
                    }
                    sentCount.incrementAndGet();
                    Feedback.info(config.outgoingPrefix + outgoing);
                }));

        if (!submitted.accepted()) {
            return fallbackToOriginal(rejectedReason(submitted), "本条");
        }

        Feedback.actionBar("§e⏳ 翻译中…");
        return false;
    }

    /** 翻译请求没被受理的原因，写进聊天栏提示。 */
    private String rejectedReason(TranslationService.SubmitResult result) {
        return switch (result) {
            case RATE_LIMITED -> "本分钟翻译请求已达上限（" + config.requestsPerMinute + " 次）";
            case QUEUE_FULL -> "翻译请求积压超过 " + config.maxPendingTranslations + " 条（接口变慢了）";
            default -> "翻译请求未被受理";
        };
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
        // 两种降级都用红字：即使按原文发出去了，也意味着「中文可能已经出现在英文服里」，
        // 这是玩家最该注意到的情况，不能只给一条灰色提示。
        if (sendOriginalOnFailure()) {
            if (config.showErrorsInChat) {
                Feedback.error(reason + "，" + subject + "未翻译，仍按原文发送。");
            }
            return true;
        }
        if (config.showErrorsInChat) {
            Feedback.error(reason + "，" + subject + "未发送（按 ↑ 可找回刚才的内容）。");
        }
        return false;
    }

    /**
     * 回调来自工作线程：切回客户端主线程再碰游戏状态（工程约定，见 {@link Feedback}）。
     *
     * <p>抽出来是因为每个翻译回调都要先做这件事，散在各处迟早会漏。
     */
    private void runOnClientThread(Consumer<Minecraft> action) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> action.accept(client));
    }

    /**
     * 回到主线程后重新取连接。
     *
     * <p>发起翻译时记下了当时的连接，回来时必须还是同一个：中途切了服务器/退了世界，
     * 就绝不能把这条消息发到别的服务器去；连接已经断开则静默放弃（玩家已经在主菜单了）。
     *
     * @return 可以安全发送的连接；不一致或已断开时返回 null（不一致会给出提示）
     */
    private ClientPacketListener connectionOrWarn(Minecraft client, ClientPacketListener origin, String subject) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return null;
        }
        if (connection != origin) {
            Feedback.error("期间切换了服务器，" + subject + "已取消，没有发出去。");
            return null;
        }
        return connection;
    }

    /** 按预算截断译文；真截断了就在聊天栏说明原因（预算的来源两个方向不同）。 */
    private String truncateTranslated(String translated, int budget, String note) {
        String outgoing = LangUtils.truncateForChat(translated, budget);
        if (!outgoing.equals(translated) && config.showErrorsInChat) {
            Feedback.hint("译文超过 " + budget + " 字符" + note + "，已截断。");
        }
        return outgoing;
    }

    /** 命令字符串没有前导斜杠，这是原版 ChatScreen 的行为。 */
    private boolean onSendCommand(String command) {
        if (programmaticSend || !config.enabled || !config.translateCommandMessages) {
            return true;
        }
        if (command == null || command.isBlank()) {
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
        if (message.isBlank()) {
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

        Minecraft originClient = Minecraft.getInstance();
        ClientPacketListener originConnection = originClient == null ? null : originClient.getConnection();

        TranslationService.SubmitResult submitted = service.submit(message, Direction.OUTGOING,
                (ok, translated, error) -> runOnClientThread(client -> {
                    ClientPacketListener connection = connectionOrWarn(client, originConnection, "这条命令");
                    if (connection == null) {
                        return;
                    }
                    if (!ok) {
                        if (fallbackToOriginal("命令内容翻译失败: " + error, "这条命令")) {
                            sendProgrammatically(connection, head + message, true);
                        }
                        return;
                    }
                    // 命令总长同样受原版 256 字符限制，命令名 + 玩家名（head）也要占额度，
                    // 否则给名字很长的玩家发长句时整条命令会超限被服务器拒绝
                    String outgoing = truncateTranslated(translated,
                            Math.max(16, config.maxOutgoingChars - head.length()),
                            "（要给命令本身留位置）");
                    String payload = head + outgoing;
                    rememberSent(outgoing);
                    if (!sendProgrammatically(connection, payload, true)) {
                        // 同上：没发出去就不算发出
                        return;
                    }
                    sentCount.incrementAndGet();
                    Feedback.info(config.outgoingPrefix + "/" + payload);
                }));

        if (!submitted.accepted()) {
            return fallbackToOriginal(rejectedReason(submitted), "这条命令");
        }

        Feedback.actionBar("§e⏳ 翻译中…");
        return false;
    }

    /**
     * 真正把内容发出去。
     *
     * <p>返回 {@code false} 表示这次发送失败了：调用方**不能**再记一条「发出译文」，
     * 也不能打一行「[→EN] …」的回显 —— 否则聊天栏和统计都会声称一条根本没发出去的消息已经发出。
     */
    private boolean sendProgrammatically(ClientPacketListener connection, String payload, boolean asCommand) {
        programmaticSend = true;
        try {
            if (asCommand) {
                connection.sendCommand(payload.startsWith("/") ? payload.substring(1) : payload);
            } else {
                connection.sendChat(payload);
            }
            return true;
        } catch (RuntimeException e) {
            HxTranslateClient.LOGGER.error("发送翻译结果失败: {}", e.toString());
            if (config.showErrorsInChat) {
                Feedback.error("发送失败: " + e.getMessage());
            }
            return false;
        } finally {
            programmaticSend = false;
        }
    }
}
