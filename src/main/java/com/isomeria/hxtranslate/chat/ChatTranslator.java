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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    private final TranslatorConfig config;
    private final TranslationService service;

    private final Deque<EchoMatcher.Sent> recentlySent = new ArrayDeque<>();
    private final List<Pattern> compiledPatterns = new ArrayList<>();
    private List<String> compiledFrom;

    private final AtomicInteger receivedCount = new AtomicInteger();
    private final AtomicInteger translatedCount = new AtomicInteger();
    private final AtomicInteger skippedCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();

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
        String ownEcho = config.skipOwnEcho ? findOwnEcho(text) : null;
        IncomingFilter.Decision decision = IncomingFilter.decide(text, config, isIgnored(text), ownEcho != null);
        if (!decision.translate()) {
            skipIncoming(ownEcho != null
                    ? decision.reason() + "（匹配到自己发过的 \"" + shorten(ownEcho) + "\"）"
                    : decision.reason(), text);
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
            String line = config.includeOriginalInIncoming
                    ? "§7" + text + " §8▏ " + config.incomingPrefix + translated
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
        return oneLine.length() <= DEBUG_TEXT_LIMIT
                ? oneLine
                : oneLine.substring(0, DEBUG_TEXT_LIMIT) + "…";
    }

    private boolean isIgnored(String text) {
        for (Pattern pattern : patterns()) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** 配置里的正则只在内容变化时重新编译。 */
    private List<Pattern> patterns() {
        List<String> source = config.ignorePatterns;
        if (source == compiledFrom) {
            return compiledPatterns;
        }
        compiledPatterns.clear();
        if (source != null) {
            for (String regex : source) {
                if (regex == null || regex.isBlank()) {
                    continue;
                }
                try {
                    compiledPatterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
                } catch (PatternSyntaxException e) {
                    HxTranslateClient.LOGGER.warn("忽略无效的 ignorePatterns 正则 '{}': {}", regex, e.getDescription());
                }
            }
        }
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

    /** 给 /hxtranslate status 用的统计信息。 */
    public String counters() {
        return "§7收到 §f" + receivedCount.get() + " §7条 §8| §a已翻译 §f" + translatedCount.get()
                + " §8| §e跳过 §f" + skippedCount.get() + " §8| §c失败 §f" + failedCount.get();
    }

    public void resetCounters() {
        receivedCount.set(0);
        translatedCount.set(0);
        skippedCount.set(0);
        failedCount.set(0);
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
            if (sendOriginalOnFailure()) {
                if (config.showErrorsInChat) {
                    Feedback.error("未配置 DeepSeek API Key，本条已按原文发送。"
                            + "用 §f/hxtranslate key <你的Key>§c 配置，或 §f/hxtranslate outgoing off§c 关掉发送翻译。");
                }
                return true;
            }
            if (config.showErrorsInChat) {
                Feedback.error("未配置 DeepSeek API Key，本条未发送。"
                        + "用 §f/hxtranslate key <你的Key>§c 配置，或 §f/hxtranslate outgoing off§c 直接发原文。");
            }
            return false;
        }

        // 记下发起翻译时所在的连接：翻译回来时如果已经不是同一个连接，
        // 说明中途切了服务器/退了世界，绝不能把这条消息发到别的服务器去。
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener originConnection = minecraft == null ? null : minecraft.getConnection();

        TranslationService.SubmitResult submitted = service.submit(translatable, Direction.OUTGOING, (ok, translated, error) -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                return;
            }
            client.execute(() -> {
                ClientPacketListener connection = client.getConnection();
                if (connection == null) {
                    return;
                }
                if (connection != originConnection) {
                    Feedback.error("期间切换了服务器，这条翻译已取消，没有发出去。");
                    return;
                }
                if (ok) {
                    String outgoing = LangUtils.truncateForChat(translated, config.maxOutgoingChars);
                    if (!outgoing.equals(translated)) {
                        Feedback.hint("译文超过 " + config.maxOutgoingChars + " 字符，已截断。");
                    }
                    rememberSent(outgoing);
                    sendProgrammatically(connection, outgoing, false);
                    Feedback.info(config.outgoingPrefix + outgoing);
                } else {
                    failedCount.incrementAndGet();
                    if (sendOriginalOnFailure()) {
                        // 配置成「失败就发原文」时才降级发送
                        sendProgrammatically(connection, message, false);
                        if (config.showErrorsInChat) {
                            Feedback.error("翻译失败，已发送原文: " + error);
                        }
                    } else if (config.showErrorsInChat) {
                        Feedback.error("翻译失败，本条未发送（按 ↑ 可找回刚才的内容）: " + error);
                    }
                }
            });
        });

        if (!submitted.accepted()) {
            // 被限流 / 背压挡下时同样要遵守 failureFallback。
            // 这里以前是无条件 return true（放行中文原文），等于「翻译请求一忙就把中文漏到英文服里」，
            // 和 v1.0.6 统一过的语义（没配 Key 也走 failureFallback）自相矛盾。
            failedCount.incrementAndGet();
            String reason = rejectedReason(submitted);
            if (sendOriginalOnFailure()) {
                if (config.showErrorsInChat) {
                    Feedback.hint(reason + "，本条未翻译，按原文发送。");
                }
                return true;
            }
            if (config.showErrorsInChat) {
                Feedback.error(reason + "，本条未发送（按 ↑ 可找回刚才的内容）。");
            }
            return false;
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
            if (sendOriginalOnFailure()) {
                if (config.showErrorsInChat) {
                    Feedback.error("未配置 DeepSeek API Key，本条命令已按原文发送。");
                }
                return true;
            }
            if (config.showErrorsInChat) {
                Feedback.error("未配置 DeepSeek API Key，这条命令未发送（按 ↑ 可找回）。");
            }
            return false;
        }

        Minecraft originClient = Minecraft.getInstance();
        ClientPacketListener originConnection = originClient == null ? null : originClient.getConnection();

        TranslationService.SubmitResult submitted = service.submit(message, Direction.OUTGOING, (ok, translated, error) -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                return;
            }
            client.execute(() -> {
                ClientPacketListener connection = client.getConnection();
                if (connection == null) {
                    return;
                }
                if (connection != originConnection) {
                    Feedback.error("期间切换了服务器，这条命令已取消，没有发出去。");
                    return;
                }
                if (ok) {
                    // 命令总长同样受原版 256 字符限制，命令名 + 玩家名（head）也要占额度，
                    // 否则给名字很长的玩家发长句时整条命令会超限被服务器拒绝
                    int budget = Math.max(16, config.maxOutgoingChars - head.length());
                    String outgoing = LangUtils.truncateForChat(translated, budget);
                    if (!outgoing.equals(translated)) {
                        Feedback.hint("译文超过 " + budget + " 字符（要给命令本身留位置），已截断。");
                    }
                    String payload = head + outgoing;
                    rememberSent(outgoing);
                    sendProgrammatically(connection, payload, true);
                    Feedback.info(config.outgoingPrefix + "/" + payload);
                } else {
                    failedCount.incrementAndGet();
                    if (sendOriginalOnFailure()) {
                        sendProgrammatically(connection, head + message, true);
                        if (config.showErrorsInChat) {
                            Feedback.error("命令内容翻译失败，已发送原文: " + error);
                        }
                    } else if (config.showErrorsInChat) {
                        Feedback.error("命令内容翻译失败，这条命令未发送（按 ↑ 可找回）: " + error);
                    }
                }
            });
        });

        if (!submitted.accepted()) {
            // 和 onSendChat 一样：没被受理也要看 failureFallback，不能把中文正文跟着命令发出去
            failedCount.incrementAndGet();
            String reason = rejectedReason(submitted);
            if (sendOriginalOnFailure()) {
                if (config.showErrorsInChat) {
                    Feedback.hint(reason + "，这条命令未翻译，按原文发送。");
                }
                return true;
            }
            if (config.showErrorsInChat) {
                Feedback.error(reason + "，这条命令未发送（按 ↑ 可找回刚才的内容）。");
            }
            return false;
        }

        Feedback.actionBar("§e⏳ 翻译中…");
        return false;
    }

    private void sendProgrammatically(ClientPacketListener connection, String payload, boolean asCommand) {
        programmaticSend = true;
        try {
            if (asCommand) {
                connection.sendCommand(payload.startsWith("/") ? payload.substring(1) : payload);
            } else {
                connection.sendChat(payload);
            }
        } catch (RuntimeException e) {
            HxTranslateClient.LOGGER.error("发送翻译结果失败: {}", e.toString());
            if (config.showErrorsInChat) {
                Feedback.error("发送失败: " + e.getMessage());
            }
        } finally {
            programmaticSend = false;
        }
    }
}
