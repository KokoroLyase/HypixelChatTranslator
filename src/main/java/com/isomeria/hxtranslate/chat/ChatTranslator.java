package com.isomeria.hxtranslate.chat;

import com.isomeria.hxtranslate.HxTranslateClient;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.core.TranslationService;
import com.isomeria.hxtranslate.util.CommandMessage;
import com.isomeria.hxtranslate.util.IncomingFilter;
import com.isomeria.hxtranslate.util.LangUtils;
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

    /** 记住最近发出的英文，避免服务器回显时又被翻译回中文。 */
    private static final int RECENT_SENT_LIMIT = 8;

    /** 没配 API Key 时的提示间隔，避免每条消息都刷屏。 */
    private static final long NO_KEY_WARN_INTERVAL_MS = 60_000L;

    /** 调试输出里原文的截断长度。 */
    private static final int DEBUG_TEXT_LIMIT = 60;

    private final TranslatorConfig config;
    private final TranslationService service;

    private final Deque<String> recentlySent = new ArrayDeque<>();
    private final List<Pattern> compiledPatterns = new ArrayList<>();
    private List<String> compiledFrom;

    private final AtomicInteger receivedCount = new AtomicInteger();
    private final AtomicInteger translatedCount = new AtomicInteger();
    private final AtomicInteger skippedCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();

    /** 模组自己调用 sendChat/sendCommand 时要忽略事件，否则会无限递归。 */
    private volatile boolean programmaticSend;
    private volatile long lastMissingKeyWarning;

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
        handleIncoming(message.getString());
    }

    public void handleIncoming(String plain) {
        if (!config.enabled || !config.translateIncoming) {
            return;
        }
        if (plain == null) {
            return;
        }
        String text = plain.strip();
        if (text.isEmpty()) {
            return;
        }

        receivedCount.incrementAndGet();

        // 关键：不能「含汉字就跳过」。Hypixel 按客户端语言把队伍名本地化成 [红队]，
        // 于是英文喊话 "[MVP+] [红队] Steve: rush mid" 里也有汉字。
        // 具体判断规则见 IncomingFilter（那里有完整的说明和离线回归测试）。
        IncomingFilter.Decision decision = IncomingFilter.decide(
                text, config, isIgnored(text), config.skipOwnEcho && isOwnEcho(text));
        if (!decision.translate()) {
            skipIncoming(decision.reason(), text);
            return;
        }
        if (!service.isReady()) {
            skipIncoming("未配置 API Key", text);
            warnMissingKeyThrottled();
            return;
        }

        boolean accepted = service.submit(text, Direction.INCOMING, (ok, translated, error) -> {
            if (!ok) {
                failedCount.incrementAndGet();
                if (config.debugLog) {
                    debug("翻译失败: " + error + " §8| " + shorten(text));
                }
                if (config.showErrorsInChat && config.enabled) {
                    Feedback.error("翻译失败: " + error);
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

        if (!accepted) {
            skipIncoming("超出每分钟限流", text);
            return;
        }
        debug(String.format("正在翻译（正文汉字占比 %.0f%%）: %s", decision.hanRatio() * 100, shorten(text)));
    }

    private void skipIncoming(String reason, String text) {
        skippedCount.incrementAndGet();
        debug("跳过（" + reason + "）: " + shorten(text));
    }

    /** 没配 Key 时给一次可见的提示，否则用户只会觉得“模组没反应”。 */
    private void warnMissingKeyThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastMissingKeyWarning < NO_KEY_WARN_INTERVAL_MS) {
            return;
        }
        lastMissingKeyWarning = now;
        Feedback.error("未配置 DeepSeek API Key，收到的消息无法翻译。用 §f/hxtranslate key <你的Key> §c配置。");
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

    private synchronized boolean isOwnEcho(String plain) {
        if (recentlySent.isEmpty()) {
            return false;
        }
        String haystack = LangUtils.normalizeKey(plain);
        for (String sent : recentlySent) {
            if (haystack.contains(sent)) {
                return true;
            }
        }
        return false;
    }

    private synchronized void rememberSent(String english) {
        String normalized = LangUtils.normalizeKey(english);
        if (normalized.isEmpty()) {
            return;
        }
        recentlySent.addLast(normalized);
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
        // 没有中文就原样发送：这是“我输入英文则无视”的实现
        if (!LangUtils.containsHan(message)) {
            return true;
        }
        if (!service.isReady()) {
            if (config.showErrorsInChat) {
                Feedback.error("未配置 DeepSeek API Key，本条已按原文发送。用 /hxtranslate key <你的Key> 配置。");
            }
            return true;
        }

        boolean accepted = service.submit(message, Direction.OUTGOING, (ok, translated, error) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                ClientPacketListener connection = minecraft.getConnection();
                if (connection == null) {
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
                    sendProgrammatically(connection, message, false);
                    if (config.showErrorsInChat) {
                        Feedback.error("翻译失败，已发送原文: " + error);
                    }
                }
            });
        });

        if (!accepted) {
            if (config.showErrorsInChat) {
                Feedback.hint("请求过快（已达每分钟上限），本条未翻译，按原文发送。");
            }
            return true;
        }

        Feedback.hint("翻译中… §8" + message);
        return false;
    }

    /** 命令字符串没有前导斜杠，这是原版 ChatScreen 的行为。 */
    private boolean onSendCommand(String command) {
        if (programmaticSend || !config.enabled || !config.translateCommandMessages) {
            return true;
        }
        if (command == null || command.isBlank()) {
            return true;
        }

        // 拆出「命令头（命令名 + 玩家名等参数）」和「待翻译正文」
        CommandMessage.Split split = CommandMessage.split(command, config.translateCommandArgs);
        if (split == null) {
            return true;
        }
        String head = split.head();
        String message = split.message();
        if (!LangUtils.containsHan(message)) {
            return true;
        }
        if (!service.isReady()) {
            if (config.showErrorsInChat) {
                Feedback.error("未配置 DeepSeek API Key，本条命令已按原文发送。");
            }
            return true;
        }

        boolean accepted = service.submit(message, Direction.OUTGOING, (ok, translated, error) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                ClientPacketListener connection = minecraft.getConnection();
                if (connection == null) {
                    return;
                }
                String outgoing = LangUtils.truncateForChat(ok ? translated : message, config.maxOutgoingChars);
                String payload = head + outgoing;
                sendProgrammatically(connection, payload, true);
                if (ok) {
                    if (!outgoing.equals(translated)) {
                        Feedback.hint("译文超过 " + config.maxOutgoingChars + " 字符，已截断。");
                    }
                    rememberSent(outgoing);
                    Feedback.info(config.outgoingPrefix + "/" + payload);
                } else {
                    failedCount.incrementAndGet();
                    if (config.showErrorsInChat) {
                        Feedback.error("命令内容翻译失败，已发送原文: " + error);
                    }
                }
            });
        });

        if (!accepted) {
            if (config.showErrorsInChat) {
                Feedback.hint("请求过快（已达每分钟上限），本条命令未翻译。");
            }
            return true;
        }

        Feedback.hint("翻译中… §8/" + command);
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
