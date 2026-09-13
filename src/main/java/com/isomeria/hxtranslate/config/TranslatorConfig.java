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

    /** 配置结构版本，用来把老版本的配置自动升级到新默认值。 */
    public static final int CURRENT_CONFIG_VERSION = 2;

    /** 老版本提示词的识别标记，只在迁移时使用。 */
    private static final String LEGACY_INCOMING_MARKER = "Keep common gaming abbreviations meaningful";
    private static final String LEGACY_OUTGOING_MARKER = "let's go mid";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 配置文件结构版本，请勿手动修改。 */
    public int configVersion = CURRENT_CONFIG_VERSION;

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

    /** 调试模式：把每条消息的处理结果写进 latest.log，并同步打印到聊天栏。 */
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

    /**
     * 汉字在「汉字 + 拉丁字母」中的占比达到多少，就认为这条消息本来就是中文，不再翻译。
     *
     * <p>这个阈值存在的原因：Hypixel 会按客户端语言把队伍名本地化成 {@code [红队]}，
     * 于是 {@code [MVP+] [红队] Steve: rush mid} 这种英文喊话里也带汉字（占比约 0.1），
     * 必须照样翻译；而真正的中文消息占比通常在 0.6 以上。默认 0.4 能干净地分开这两种情况。
     */
    public double chineseRatioThreshold = 0.4;

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
     * Hypixel / Bed Wars 术语与缩写对照表，格式 {@code 英文=中文含义}。
     *
     * <p>会追加到「收到消息」的提示词里，要求模型按含义翻译而不是原样保留英文缩写。
     * 清空这个列表即可关闭术语表。
     */
    public List<String> glossary = new ArrayList<>(List.of(
            "obby=黑曜石（obsidian）",
            "dia=钻石（diamond）",
            "dias=钻石",
            "def=防守（defend）；\"u def\"=你来防守",
            "inc=有人进攻（incoming）",
            "mid=中路、中间的资源点",
            "gen=资源点、刷资源机（generator）",
            "rush=速攻、直接冲家",
            "bed=床（要破坏的目标）",
            "final=终杀（final kill）",
            "void=虚空",
            "kb=击退（knockback）",
            "pot=药水（potion）",
            "invis=隐身药水",
            "jump=跳跃药水",
            "speed=速度药水",
            "pearl=末影珍珠",
            "fb=火球（fireball）",
            "gap=金苹果（golden apple）",
            "gaps=金苹果",
            "sharp=锋利附魔",
            "prot=保护附魔",
            "scaffold=搭桥（多指作弊搭桥）",
            "reach=攻击距离（多指作弊）",
            "hack=开挂",
            "hacker=外挂玩家",
            "cheater=作弊玩家",
            "noob=菜鸟、新手",
            "ez=太简单了（嘲讽）",
            "gg=打得好",
            "wp=干得漂亮",
            "afk=挂机",
            "brb=马上回来",
            "gtg=我要下了",
            "omw=在路上",
            "ty=谢谢",
            "thx=谢谢",
            "np=不客气",
            "sry=抱歉",
            "pls=请",
            "u=你",
            "ur=你的、你是",
            "r=are（例如 \"r u ok\" = 你还好吗）",
            "y=是",
            "n=不",
            "1v1=单挑",
            "team=队伍",
            "island=岛"
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
            Task: translate the received chat message into Simplified Chinese.
            Rules:
            - The text comes from the Hypixel Minecraft server. It usually starts with server-added prefixes
              such as "[MVP+]", a team tag like "[红队]" or a player name followed by ":". Keep those prefixes,
              player names, numbers and coordinates exactly as they are, and translate only the real message.
            - A Chinese team tag such as "[红队]" is added by the server because the client language is Chinese.
              It does NOT mean the message itself is Chinese: when the message body is English, translate it.
            - Expand Minecraft / Hypixel / Bed Wars slang into its Chinese meaning instead of keeping the English
              abbreviation (for example "obby" -> 黑曜石, "dia" -> 钻石, "u def" -> 你来防守, "inc" -> 有人进攻,
              "mid" -> 中路). Use the glossary below when it is provided.
            - Translate only. Do NOT answer, explain, comment on or continue the conversation.
            - Do NOT add quotes, prefixes, emojis or any extra text.
            - If the message body is already Chinese, output it unchanged.
            Output only the translated text.""";

    public String outgoingSystemPrompt = """
            You are a translation engine embedded in a Minecraft client.
            Task: translate the user's Chinese chat message into natural, casual English used on the
            Hypixel Minecraft server.
            Rules:
            - Keep player names, numbers, coordinates and Minecraft terms unchanged.
            - Use short, natural gaming English. Well-known Hypixel / Bed Wars abbreviations are welcome when
              they are unambiguous ("def", "inc", "mid", "obby", "dia", "gg"), but never produce mixed-language text.
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
            loaded.migrate();
            return loaded;
        } catch (IOException | JsonSyntaxException e) {
            HxTranslateClient.LOGGER.error("读取配置失败，将使用默认配置: {}", e.toString());
            return new TranslatorConfig();
        }
    }

    /**
     * 把老版本配置文件里的内容升级到新版默认值。
     *
     * <p>只覆盖「还是老版默认值」或空白的字段，用户自己改过的提示词不会被冲掉。
     */
    private void migrate() {
        if (configVersion >= CURRENT_CONFIG_VERSION) {
            return;
        }
        TranslatorConfig defaults = new TranslatorConfig();
        boolean changed = false;

        if (needsPromptUpgrade(incomingSystemPrompt, LEGACY_INCOMING_MARKER)) {
            incomingSystemPrompt = defaults.incomingSystemPrompt;
            changed = true;
        }
        if (needsPromptUpgrade(outgoingSystemPrompt, LEGACY_OUTGOING_MARKER)) {
            outgoingSystemPrompt = defaults.outgoingSystemPrompt;
            changed = true;
        }
        if (glossary == null || glossary.isEmpty()) {
            glossary = defaults.glossary;
            changed = true;
        }

        configVersion = CURRENT_CONFIG_VERSION;
        HxTranslateClient.LOGGER.info("配置已升级到 v{}（{}）", CURRENT_CONFIG_VERSION,
                changed ? "提示词与术语表已更新" : "保留了你的自定义内容");
        save();
    }

    private static boolean needsPromptUpgrade(String prompt, String legacyMarker) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }
        // 老版本的默认提示词：还带着识别标记，说明没被自定义过，可以安全替换
        return prompt.contains(legacyMarker);
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
        chineseRatioThreshold = Math.min(1.0, Math.max(0.05, chineseRatioThreshold));
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
        if (glossary == null) {
            glossary = new ArrayList<>();
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
        this.configVersion = o.configVersion;
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
        this.chineseRatioThreshold = o.chineseRatioThreshold;
        this.maxIncomingChars = o.maxIncomingChars;
        this.maxOutgoingChars = o.maxOutgoingChars;
        this.requestsPerMinute = o.requestsPerMinute;
        this.cacheSize = o.cacheSize;
        this.ignorePatterns = o.ignorePatterns;
        this.glossary = o.glossary;
        this.translateCommandArgs = o.translateCommandArgs;
        this.incomingSystemPrompt = o.incomingSystemPrompt;
        this.outgoingSystemPrompt = o.outgoingSystemPrompt;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
