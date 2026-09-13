package com.isomeria.hxtranslate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.isomeria.hxtranslate.HxTranslateClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置文件：.minecraft/config/hxtranslate.json
 *
 * <p>所有字段都是 public 的，Gson 直接读写。新增字段时只要给一个默认值，
 * 旧配置文件缺少该字段也不会出错（Gson 会保留默认值）。
 */
public final class TranslatorConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // ------------------------------------------------------------------
    // DeepSeek
    // ------------------------------------------------------------------

    /** DeepSeek API Key，在 https://platform.deepseek.com/api_keys 申请。 */
    public String apiKey = "";

    /** API 地址，一般不用改。 */
    public String apiBaseUrl = "https://api.deepseek.com";

    /** 使用的模型：deepseek-chat 快且便宜，deepseek-reasoner 更贵更慢。 */
    public String model = "deepseek-chat";

    /** 采样温度，DeepSeek 官方建议翻译任务用 1.3。 */
    public double temperature = 1.3;

    /** 单次回复的最大 token 数。 */
    public int maxTokens = 512;

    /** HTTP 超时（秒）。 */
    public int httpTimeoutSeconds = 20;

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /** 总开关，可用游戏内按键或 /hxtranslate on|off 切换。 */
    public boolean enabled = true;

    /** 是否翻译收到的消息（英文 → 中文）。 */
    public boolean translateIncoming = true;

    /** 是否翻译自己发送的中文（中文 → 英文）。 */
    public boolean translateOutgoing = true;

    /** 是否翻译 /msg、/r、/pc 等命令里的聊天内容。 */
    public boolean translateCommandMessages = true;

    /** 不翻译自己刚发出的英文被服务器回显出来的那条消息。 */
    public boolean skipOwnEcho = true;

    /** 出错时在聊天栏提示。 */
    public boolean showErrorsInChat = true;

    /** 输出调试日志到 latest.log。 */
    public boolean debugLog = false;

    // ------------------------------------------------------------------
    // 显示
    // ------------------------------------------------------------------

    /** 收到消息的译文前缀，支持 § 颜色代码。 */
    public String incomingPrefix = "§8[§b译§8] §f";

    /** 自己发出消息的英文回显前缀。 */
    public String outgoingPrefix = "§8[§a→EN§8] §f";

    /** 是否在译文前面再显示一次原文。 */
    public boolean includeOriginalInIncoming = false;

    // ------------------------------------------------------------------
    // 过滤 / 限流
    // ------------------------------------------------------------------

    /** 一条消息至少包含多少个拉丁字母才认为“像英文”。 */
    public int minLatinLetters = 2;

    /** 超过这个长度的消息不翻译（防止刷屏/超长文本）。 */
    public int maxIncomingChars = 240;

    /** 发出去的译文最大长度。原版聊天框上限 256，超长会被服务器拒绝，所以默认截断。 */
    public int maxOutgoingChars = 256;

    /** 每分钟最多请求多少次 API。 */
    public int requestsPerMinute = 40;

    /** 翻译缓存条数。 */
    public int cacheSize = 500;

    /** 命中这些正则（不区分大小写）的消息不翻译，例如服务器提示音效。 */
    public List<String> ignorePatterns = new ArrayList<>(List.of(
            "^\\+\\d+ .*(XP|Coins|Tokens)",
            "^(You|A player) (joined|left)",
            "^Sending you to"
    ));

    /**
     * 需要翻译内容部分的命令：命令名（小写，不含斜杠）-> 消息之前还有几个参数。
     * 例如 /msg <玩家> <内容> 是 1，/r <内容> 是 0。
     */
    public Map<String, Integer> translateCommandArgs = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("msg", 1),
            Map.entry("tell", 1),
            Map.entry("w", 1),
            Map.entry("whisper", 1),
            Map.entry("r", 0),
            Map.entry("reply", 0),
            Map.entry("pc", 0),
            Map.entry("gc", 0),
            Map.entry("ac", 0),
            Map.entry("achat", 0),
            Map.entry("chat", 0)
    ));

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    public String incomingSystemPrompt = """
            You are a translation engine embedded in a Minecraft client.
            Task: translate the user's chat message into Simplified Chinese.
            Rules:
            - The text comes from the Hypixel Minecraft server, so keep player names, ranks ([MVP+], [VIP]),
              item names, numbers, coordinates and server slang unchanged when they are already clear.
            - Keep common gaming abbreviations meaningful (e.g. "gg", "wp", "afk", "brb", "1v1") and add a short
              Chinese explanation only when the meaning is not obvious.
            - Translate only. Do NOT answer, explain, comment on or continue the conversation.
            - Do NOT add quotes, prefixes, emojis or any extra text.
            - If the text is already Chinese, output it unchanged.
            Output only the translated text.""";

    public String outgoingSystemPrompt = """
            You are a translation engine embedded in a Minecraft client.
            Task: translate the user's Chinese chat message into natural, casual English used on the
            Hypixel Minecraft server.
            Rules:
            - Keep player names, numbers, coordinates and Minecraft terms unchanged.
            - Use short, natural gaming English (e.g. "gg", "nice", "let's go mid").
            - Translate only. Do NOT answer, explain, comment on or continue the conversation.
            - Do NOT add quotes, prefixes, emojis, greetings or any extra text.
            - If the text is already English, output it unchanged.
            Output only the English translation.""";

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("hxtranslate.json");
    }

    /** 从磁盘读取配置，文件不存在则写入一份带默认值的模板。 */
    public static TranslatorConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            TranslatorConfig defaults = new TranslatorConfig();
            defaults.save();
            HxTranslateClient.LOGGER.info("已生成默认配置文件: {}", path);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            TranslatorConfig loaded = GSON.fromJson(reader, TranslatorConfig.class);
            if (loaded == null) {
                throw new JsonSyntaxException("配置文件为空");
            }
            loaded.normalize();
            return loaded;
        } catch (IOException | JsonSyntaxException e) {
            HxTranslateClient.LOGGER.error("读取配置失败，将使用默认配置: {}", e.toString());
            return new TranslatorConfig();
        }
    }

    /** 热重载：把磁盘内容覆盖到当前实例（保持其它地方的引用仍然有效）。 */
    public void reload() {
        TranslatorConfig fresh = load();
        copyFrom(fresh);
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            HxTranslateClient.LOGGER.error("保存配置失败: {}", e.toString());
        }
    }

    /** 修正明显不合理的值，避免用户手改配置后崩溃。 */
    private void normalize() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.deepseek.com";
        }
        if (model == null || model.isBlank()) {
            model = "deepseek-chat";
        }
        minLatinLetters = Math.max(1, minLatinLetters);
        maxIncomingChars = Math.max(16, maxIncomingChars);
        maxOutgoingChars = Math.max(16, maxOutgoingChars);
        requestsPerMinute = Math.max(1, requestsPerMinute);
        cacheSize = Math.max(0, cacheSize);
        httpTimeoutSeconds = Math.max(3, httpTimeoutSeconds);
        maxTokens = Math.max(32, maxTokens);
        temperature = Math.min(2.0, Math.max(0.0, temperature));
        if (ignorePatterns == null) {
            ignorePatterns = new ArrayList<>();
        }
        if (translateCommandArgs == null) {
            translateCommandArgs = new LinkedHashMap<>();
        }
        if (incomingPrefix == null) {
            incomingPrefix = "";
        }
        if (outgoingPrefix == null) {
            outgoingPrefix = "";
        }
    }

    private void copyFrom(TranslatorConfig o) {
        this.apiKey = o.apiKey;
        this.apiBaseUrl = o.apiBaseUrl;
        this.model = o.model;
        this.temperature = o.temperature;
        this.maxTokens = o.maxTokens;
        this.httpTimeoutSeconds = o.httpTimeoutSeconds;
        this.enabled = o.enabled;
        this.translateIncoming = o.translateIncoming;
        this.translateOutgoing = o.translateOutgoing;
        this.translateCommandMessages = o.translateCommandMessages;
        this.skipOwnEcho = o.skipOwnEcho;
        this.showErrorsInChat = o.showErrorsInChat;
        this.debugLog = o.debugLog;
        this.incomingPrefix = o.incomingPrefix;
        this.outgoingPrefix = o.outgoingPrefix;
        this.includeOriginalInIncoming = o.includeOriginalInIncoming;
        this.minLatinLetters = o.minLatinLetters;
        this.maxIncomingChars = o.maxIncomingChars;
        this.maxOutgoingChars = o.maxOutgoingChars;
        this.requestsPerMinute = o.requestsPerMinute;
        this.cacheSize = o.cacheSize;
        this.ignorePatterns = o.ignorePatterns;
        this.translateCommandArgs = o.translateCommandArgs;
        this.incomingSystemPrompt = o.incomingSystemPrompt;
        this.outgoingSystemPrompt = o.outgoingSystemPrompt;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
