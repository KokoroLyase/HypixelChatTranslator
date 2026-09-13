package com.isomeria.hxtranslate.chat;

import com.isomeria.hxtranslate.HxTranslateClient;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.core.TranslationService;
import com.isomeria.hxtranslate.util.CommandMessage;
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

    private final TranslatorConfig config;
    private final TranslationService service;

    private final Deque<String> recentlySent = new ArrayDeque<>();
    private final List<Pattern> compiledPatterns = new ArrayList<>();
    private List<String> compiledFrom;

    /** 模组自己调用 sendChat/sendCommand 时要忽略事件，否则会无限递归。 */
    private volatile boolean programmaticSend;

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
        if (text.isEmpty() || text.length() > config.maxIncomingChars) {
            return;
        }
        // 已经是中文、或者不像英文，就不浪费 token
        if (LangUtils.containsHan(text)) {
            return;
        }
        if (LangUtils.countLatinLetters(text) < config.minLatinLetters) {
            return;
        }
        if (isIgnored(text)) {
            return;
        }
        if (config.skipOwnEcho && isOwnEcho(text)) {
            return;
        }

        service.submit(text, Direction.INCOMING, (ok, translated, error) -> {
            if (!ok) {
                if (config.showErrorsInChat && config.enabled) {
                    Feedback.error("翻译失败: " + error);
                }
                return;
            }
            if (!config.enabled) {
                return;
            }
            String line = config.includeOriginalInIncoming
                    ? "§7" + text + " §8▏ " + config.incomingPrefix + translated
                    : config.incomingPrefix + translated;
            Feedback.info(line);
        });
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
                } else if (config.showErrorsInChat) {
                    Feedback.error("命令内容翻译失败，已发送原文: " + error);
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
