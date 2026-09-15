package com.isomeria.hxtranslate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.isomeria.hxtranslate.HxTranslateClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置文件：.minecraft/config/hxtranslate.json
 *
 * <p>所有字段都是 public 的，Gson 直接读写。新增字段时只要给一个默认值，
 * 旧配置文件缺少该字段也不会出错（Gson 会保留默认值）。
 */
public final class TranslatorConfig {

    /** 配置结构版本，用来把老版本的配置自动升级到新默认值。 */
    public static final int CURRENT_CONFIG_VERSION = 6;

    /**
     * v1.1.1 及之前的默认 {@code ignorePatterns}。
     *
     * <p>用来判断用户有没有动过这个列表：一条都没删，说明还是默认值，v6 迁移才会把新的
     * 横幅规则补进去（见 {@link #applyMigrations()}）。
     */
    private static final List<String> LEGACY_DEFAULT_IGNORES = List.of(
            "^\\+\\d+ .*(XP|Coins|Tokens)",
            "^(You|A player) (joined|left)",
            "^Sending you to");

    /** 横幅分隔线（{@code ▬▬▬▬} 这类）开头的消息。 */
    private static final String BANNER_SEPARATOR_PATTERN = "^[\\u25AC\\u2500\\u2014\\u2550=\\uff1d~*_\\-]{4,}";

    /**
     * 当前默认模型名（2026-09 起 DeepSeek 的有效名字）。
     *
     * <p>做成常量是因为 {@link #normalize()} 里也要用同一个兜底值：
     * 之前那里写死了已经下线的 {@code deepseek-chat}，而迁移只在 {@code configVersion < 5}
     * 时才会纠正模型名 —— 于是「已经是 v5 的配置 + model 被清空」会让每条请求都 400。
     */
    public static final String DEFAULT_MODEL = "deepseek-flash";

    /** v1.0.5 之前默认的模型名，2026-09 起 DeepSeek 已下线该名称。 */
    private static final List<String> RETIRED_MODELS = List.of(
            "deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp",
            "deepseek-coder", "deepseek-v3", "deepseek-v3.1");

    /** 老版本提示词的识别标记：提示词里还带着这些句子，说明它是旧版默认值，可以安全替换。 */
    private static final String[] LEGACY_INCOMING_MARKERS = {
            "Keep common gaming abbreviations meaningful",  // v1.0.0
            "Use the glossary below when it is provided."   // v1.0.1 / v1.0.2
    };
    private static final String[] LEGACY_OUTGOING_MARKERS = {
            "let's go mid",                       // v1.0.0
            "never produce mixed-language text"   // v1.0.1 / v1.0.2
    };

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

    /** 使用的模型。2026-09 起 DeepSeek 只提供 deepseek-flash 与 deepseek-v4-pro，旧名 deepseek-chat 已下线。 */
    public String model = DEFAULT_MODEL;

    /**
     * 是否开启思考模式。
     *
     * <p>DeepSeek 新模型<b>默认开启思考模式且 effort=high</b>，聊天翻译完全不需要：
     * 会多出几秒延迟，还会按输出 token 计费。所以默认显式关闭。
     */
    public boolean enableThinking = false;

    /** 采样温度，翻译要稳定不要发挥。 */
    public double temperature = 0.7;

    /** 单次回复的最大 token 数。 */
    public int maxTokens = 512;

    /** 建立连接超时（秒）。 */
    public int connectTimeoutSeconds = 5;

    /** 读取响应超时（秒）。 */
    public int httpTimeoutSeconds = 15;

    /** 请求失败（429/5xx/网络错误）时是否自动重试一次。 */
    public boolean retryOnFailure = true;

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

    /**
     * 发送方向翻译失败时怎么办：
     * {@code CANCEL} = 不发送，只在聊天栏提示（默认，避免中文原样发到英文服）；
     * {@code SEND_ORIGINAL} = 按中文原文发出去。
     */
    public String failureFallback = "CANCEL";

    /** 永不翻译这些玩家的消息（写名字即可，朋友是中国人时很有用）。 */
    public List<String> blacklistedPlayers = new ArrayList<>();

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
    public int requestsPerMinute = 60;

    /**
     * 排队中的翻译请求上限（背压）。
     *
     * <p>接口变慢时消息会堆在队列里，越堆越晚。积压超过这个数就先跳过新消息，
     * 避免延迟滚雪球、内存也跟着涨。正常网络下这个值不会碰到。
     */
    public int maxPendingTranslations = 20;

    /** 翻译缓存条数。 */
    public int cacheSize = 500;

    /**
     * 命中这些正则（不区分大小写）的消息不翻译，例如服务器提示音效。
     *
     * <p>最后两条是给「服务器横幅」留的：{@code ▬▬▬▬} 分隔线、以及横幅里的游戏名本身
     * （{@code Bed Wars}）。它们不是给人读的句子，翻了只会多出一行没用的译文、还多花一次请求。
     */
    public List<String> ignorePatterns = new ArrayList<>(List.of(
            "^\\+\\d+ .*(XP|Coins|Tokens)",
            "^(You|A player) (joined|left)",
            "^Sending you to",
            BANNER_SEPARATOR_PATTERN,
            "^Bed\\s*Wars$"
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
            "sweaty=太拼了、满头大汗（形容打得很用力）",
            "tryhard=太拼了、卷王",
            "chill=冷静点、别激动",
            "fr fr=说真的、真的（fr 同理）",
            "bro=兄弟",
            "camp=龟缩、蹲点",
            "carry=带飞、carry 全场",
            "clutch=极限翻盘",
            "trap=陷阱",
            "gapple=金苹果",
            "punch bow=击退弓",
            "kb stick=击退棒",
            "mining fatigue=挖掘疲劳（陷阱效果）",
            "base=家、基地",
            "pop=床被打掉（bed pop）",
            "lag=卡、延迟",
            "laggy=很卡",
            "teamwipe=团灭对面",
            "defend=防守（同 def）",
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
     * 例如 /msg &lt;玩家&gt; &lt;内容&gt; 是 1，/shout &lt;内容&gt; 是 0。
     *
     * <p>名单依据 Hypixel 官方命令表整理，覆盖所有「玩家自己输入正文」的聊天命令。
     * v1.0.2 之前这里漏了 /shout（喊话）、/message、/pchat、/gchat、/ochat 等，导致喊话不翻译。
     */
    public Map<String, Integer> translateCommandArgs = new LinkedHashMap<>(Map.ofEntries(
            // 私聊 / 好友私信
            Map.entry("msg", 1),
            Map.entry("message", 1),
            Map.entry("tell", 1),
            Map.entry("w", 1),
            Map.entry("whisper", 1),
            Map.entry("r", 0),
            Map.entry("reply", 0),
            // 频道聊天
            Map.entry("ac", 0),      // 全局聊天
            Map.entry("achat", 0),
            Map.entry("pc", 0),      // 队伍聊天
            Map.entry("pchat", 0),
            Map.entry("gc", 0),      // 公会聊天
            Map.entry("gchat", 0),
            Map.entry("oc", 0),      // 公会官员聊天
            Map.entry("ochat", 0),
            // 局内喊话（起床战争等）
            Map.entry("shout", 0)
    ));

    /**
     * 既是「管理命令」又可能是「聊天」的命令，需要额外判断。
     *
     * <p>例如 {@code /party invite Steve} 是邀请，而 {@code /party chat 大家好} 是发消息。
     * 规则：第一个词是 {@code chat} → 后面是正文；第一个词是管理子命令
     * （见 {@link #commandManagementKeywords}）→ 不动；其它情况按 {@code /party <正文>} 处理。
     */
    public List<String> guardedCommands = new ArrayList<>(List.of("p", "party", "g", "guild"));

    /** 上面那些命令的管理子命令，出现这些词就说明不是聊天内容。 */
    public List<String> commandManagementKeywords = new ArrayList<>(List.of(
            "invite", "uninvite", "kick", "promote", "demote", "transfer", "warp", "list", "disband",
            "leave", "accept", "deny", "mute", "unmute", "poll", "settings", "setting", "open", "close",
            "stream", "rename", "join", "create", "remove", "add", "help", "info", "stats", "top",
            "quest", "quests", "tag", "color", "setrank", "online", "history", "log", "slow", "fast"
    ));

    /** 不在任何名单里的命令：如果正文明显是一句中文，也翻译（应对 Hypixel 新增命令）。 */
    public boolean translateUnknownCommands = true;

    /**
     * 兜底翻译时要排除的命令：它们的参数是玩家名 / 物品名 / 设置项，翻译了会出事。
     * 只在「未知命令兜底」里生效，不影响上面的显式名单。
     */
    public List<String> protectedCommands = new ArrayList<>(List.of(
            "tp", "tpa", "tpahere", "tpaccept", "tpdeny", "tpall",
            "f", "friend", "friends", "fl", "ignore", "unignore", "block",
            "report", "wdr", "watchdogreport", "chatreport", "cr", "helpop",
            "duel", "trade", "ah", "auction", "bazaar", "visit", "housing",
            "play", "lobby", "hub", "skyblock", "sb", "profile", "coop", "island",
            "pet", "wardrobe", "skills", "collection", "collections", "minion", "minions",
            "booster", "mystery", "rank", "ping", "stats", "api", "link", "settings",
            "options", "toggle", "language", "lang", "nick", "p", "party", "g", "guild"
    ));

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    public String incomingSystemPrompt = """
            You are a translation engine embedded in a Minecraft client. You translate Hypixel chat
            messages into Simplified Chinese for a Chinese-speaking player.

            Input format:
            - Messages usually start with server-added prefixes such as "[MVP+]", "[VIP]", a team tag like
              "[红队]", or a channel tag like "[喊话]" (shout), followed by "PlayerName:".
            - Keep every prefix, player name, number and coordinate exactly as it is; translate only the
              message itself.
            - A Chinese team tag such as "[红队]" is added by the server because the client language is
              Chinese. It does NOT mean the message is Chinese: when the body is English, translate it.
            - Some server messages are English sentences with a Chinese suffix, e.g.
              "3_0HY was thrown into a black hole by G19sy. 最终击杀！". Translate the English part and keep
              the suffix as it is.

            Style:
            - Output natural, casual Chinese that a Chinese Minecraft player would actually type in chat.
            - Expand Minecraft / Hypixel / Bed Wars slang into its Chinese meaning instead of keeping the
              English abbreviation: obby -> 黑曜石, dia -> 钻石, u def -> 你来防守, inc -> 有人进攻,
              mid -> 中路, sweaty -> 太拼了, chill -> 冷静点, fr fr -> 说真的, gg -> 打得不错.
            - Keep it about as short as the original. Do not turn a short taunt into a long sentence.

            Examples:
            [喊话] [红队] [MVP+] Alex: ur so sweaty bro chill! fr fr
            -> [喊话] [红队] [MVP+] Alex: 你也太拼了吧兄弟，冷静点！说真的
            [MVP+] Steve: inc mid, u def
            -> [MVP+] Steve: 有人从中路进攻，你来防守
            green u have a real good range
            -> 绿队，你这攻击距离也太远了吧

            Rules:
            - Translate only. Do NOT answer, explain, comment on or continue the conversation.
            - Do NOT add quotes, prefixes, emojis or any extra text.
            - If the message body is already Chinese, output it unchanged.
            Output only the translated text.""";

    public String outgoingSystemPrompt = """
            You are a translation engine embedded in a Minecraft client. You translate the Chinese messages
            a player types into natural English for the Hypixel Minecraft server.

            Style:
            - Short, casual gaming English — exactly what people type in Bed Wars chat.
            - Common Hypixel / Bed Wars abbreviations are welcome when they are unambiguous
              ("def", "inc", "mid", "obby", "dia", "gg", "wp", "omw", "ty"), but never mix Chinese
              characters into the English output.
            - Keep player names, numbers and coordinates unchanged.
            - Do not add greetings, emojis, explanations or punctuation noise.

            Examples:
            你来防守 -> u def
            中路有人进攻 -> inc mid
            我们床没了，先撤 -> we lost our bed, fall back
            干得漂亮 -> wp
            等我一下，马上到 -> wait for me, omw

            Rules:
            - Translate only. Do NOT answer, explain, comment on or continue the conversation.
            - Do NOT add quotes, prefixes, emojis or any extra text.
            - If the text is already English, output it unchanged.
            Output only the English translation.""";

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /**
     * 配置文件路径：{@code .minecraft/config/hxtranslate.json}。
     *
     * <p>取不到 Fabric 环境（例如离线自检程序）时退回到相对路径，
     * 保证这个辅助方法本身永远不会把调用方炸掉 —— 它只被日志和读写用，
     * 不该因为环境缺失影响到翻译主流程。
     */
    public static Path configPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("hxtranslate.json");
        } catch (Throwable ignored) {
            return Path.of("config", "hxtranslate.json");
        }
    }

    /**
     * 从磁盘读取配置，文件不存在则写入一份带默认值的模板。
     *
     * <p>整个升级过程是自动的，用户不需要手动改文件：
     * <ol>
     *   <li>{@link #migrate()} 按版本号把老默认值换成新默认值（不覆盖用户自定义内容）；</li>
     *   <li>{@link #fillMissingFields} 把本次新增、文件里还没有的字段补写进去，
     *       这样用户能直接在 json 里看到并调整新选项。</li>
     * </ol>
     */
    public static TranslatorConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            TranslatorConfig defaults = new TranslatorConfig();
            defaults.save();
            HxTranslateClient.LOGGER.info("已生成默认配置文件: {}", path);
            return defaults;
        }

        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            TranslatorConfig loaded = GSON.fromJson(json, TranslatorConfig.class);
            if (loaded == null) {
                throw new JsonSyntaxException("配置文件为空");
            }
            loaded.normalize();
            loaded.migrate();
            loaded.fillMissingFields(json, path);
            return loaded;
        } catch (IOException | JsonSyntaxException e) {
            HxTranslateClient.LOGGER.error("读取配置失败，将使用默认配置: {}", e.toString());
            return new TranslatorConfig();
        }
    }

    /**
     * 把「当前版本有、但配置文件里没有」的顶层字段补写进文件。
     *
     * <p>补字段和换默认值是两件事：版本迁移负责改值，这里负责让新选项出现在文件里。
     * 只会在确实缺字段时写盘，不会每次启动都重写。
     */
    private void fillMissingFields(String diskJson, Path path) {
        try {
            JsonObject onDisk = JsonParser.parseString(diskJson).getAsJsonObject();
            JsonObject current = GSON.toJsonTree(this).getAsJsonObject();
            List<String> missing = new ArrayList<>();
            for (String key : current.keySet()) {
                if (!onDisk.has(key)) {
                    missing.add(key);
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            save();
            HxTranslateClient.LOGGER.info("配置文件已自动补全新字段 {}（原有设置未改动）: {}", missing, path);
        } catch (RuntimeException e) {
            HxTranslateClient.LOGGER.warn("补全配置字段失败（不影响使用）: {}", e.toString());
        }
    }

    /**
     * 把老版本配置文件里的内容升级到新版默认值。
     *
     * <p>只覆盖「还是老版默认值」或空白的字段，用户自己改过的内容不会被冲掉。
     */
    private void migrate() {
        int before = configVersion;
        boolean changed = applyMigrations();
        if (configVersion != before) {
            HxTranslateClient.LOGGER.info("配置已从 v{} 升级到 v{}（{}）", before, configVersion,
                    changed ? "新增默认值已补齐，自定义内容保留" : "无需改动");
            save();
        }
    }

    /**
     * 迁移的纯逻辑部分：不读写磁盘、不依赖 FabricLoader，方便离线测试。
     *
     * @return 是否真的改动了字段
     */
    public boolean applyMigrations() {
        if (configVersion >= CURRENT_CONFIG_VERSION) {
            return false;
        }
        int from = configVersion;
        TranslatorConfig defaults = new TranslatorConfig();
        boolean changed = false;

        // ---- v1 -> v2：提示词、术语表 ----
        if (from < 2) {
            if (needsPromptUpgrade(incomingSystemPrompt, LEGACY_INCOMING_MARKERS)) {
                incomingSystemPrompt = defaults.incomingSystemPrompt;
                changed = true;
            }
            if (needsPromptUpgrade(outgoingSystemPrompt, LEGACY_OUTGOING_MARKERS)) {
                outgoingSystemPrompt = defaults.outgoingSystemPrompt;
                changed = true;
            }
            if (glossary == null || glossary.isEmpty()) {
                glossary = defaults.glossary;
                changed = true;
            }
        }

        // ---- v2 -> v3：补齐聊天命令名单（/shout 等漏掉的命令）----
        if (from < 3) {
            if (translateCommandArgs == null) {
                translateCommandArgs = new LinkedHashMap<>();
            }
            // /chat 是切换聊天频道的命令（/chat a|p|g|o），不是发消息，v1.0.1 及之前误收录了
            if (Integer.valueOf(0).equals(translateCommandArgs.get("chat"))) {
                translateCommandArgs.remove("chat");
                changed = true;
            }
            // 只补缺，不覆盖用户自己调过的参数个数
            for (Map.Entry<String, Integer> entry : defaults.translateCommandArgs.entrySet()) {
                if (!translateCommandArgs.containsKey(entry.getKey())) {
                    translateCommandArgs.put(entry.getKey(), entry.getValue());
                    changed = true;
                }
            }
            if (guardedCommands == null || guardedCommands.isEmpty()) {
                guardedCommands = defaults.guardedCommands;
                changed = true;
            }
            if (commandManagementKeywords == null || commandManagementKeywords.isEmpty()) {
                commandManagementKeywords = defaults.commandManagementKeywords;
                changed = true;
            }
            if (protectedCommands == null || protectedCommands.isEmpty()) {
                protectedCommands = defaults.protectedCommands;
                changed = true;
            }
        }

        // ---- v3 -> v4：提示词换成带少样本示例的版本、术语表补词、限流默认值 40 -> 60 ----
        if (from < 4) {
            if (needsPromptUpgrade(incomingSystemPrompt, LEGACY_INCOMING_MARKERS)
                    || !incomingSystemPrompt.contains("Examples:")) {
                incomingSystemPrompt = defaults.incomingSystemPrompt;
                changed = true;
            }
            if (needsPromptUpgrade(outgoingSystemPrompt, LEGACY_OUTGOING_MARKERS)
                    || !outgoingSystemPrompt.contains("Examples:")) {
                outgoingSystemPrompt = defaults.outgoingSystemPrompt;
                changed = true;
            }
            if (glossary == null) {
                glossary = new ArrayList<>();
            }
            // 术语表只补缺，用户自己加的词不会被删
            for (String entry : defaults.glossary) {
                String key = glossaryKey(entry);
                boolean exists = false;
                for (String existing : glossary) {
                    if (glossaryKey(existing).equals(key)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    glossary.add(entry);
                    changed = true;
                }
            }
            // 只有还是老默认值时才调整，用户自己改过的数字不动
            if (requestsPerMinute == 40) {
                requestsPerMinute = defaults.requestsPerMinute;
                changed = true;
            }
        }

        // ---- v4 -> v5：DeepSeek 2026-09 起下线 deepseek-chat 等旧模型名 ----
        if (from < 5) {
            if (model == null || model.isBlank() || RETIRED_MODELS.contains(model.trim().toLowerCase(Locale.ROOT))) {
                HxTranslateClient.LOGGER.info("模型名 {} 已下线，自动切换为 {}",
                        model, defaults.model);
                model = defaults.model;
                changed = true;
            }
            // 思考模式默认关掉：新模型默认开启，聊天翻译既慢又贵
            if (enableThinking) {
                enableThinking = false;
                changed = true;
            }
            if (temperature == 1.3) { // 旧默认值，跟随新版调低
                temperature = defaults.temperature;
                changed = true;
            }
            if (httpTimeoutSeconds == 20) { // 旧默认值
                httpTimeoutSeconds = defaults.httpTimeoutSeconds;
                changed = true;
            }
            if (failureFallback == null || failureFallback.isBlank()) {
                failureFallback = defaults.failureFallback;
                changed = true;
            }
        }

        // ---- v5 -> v6：服务器横幅（分隔线 / 游戏名本身）不再翻译 ----
        if (from < 6) {
            if (ignorePatterns == null) {
                ignorePatterns = new ArrayList<>();
            }
            // 只补缺：旧默认值一条都没删过（说明用户没动过这个列表）才补新的两条。
            // 删过就是有意调整过，硬塞回去等于覆盖他的意图。
            if (ignorePatterns.containsAll(LEGACY_DEFAULT_IGNORES)) {
                for (String pattern : defaults.ignorePatterns) {
                    if (!ignorePatterns.contains(pattern)) {
                        ignorePatterns.add(pattern);
                        changed = true;
                    }
                }
            }
        }

        configVersion = CURRENT_CONFIG_VERSION;
        return changed;
    }

    private static String glossaryKey(String entry) {
        if (entry == null) {
            return "";
        }
        int idx = entry.indexOf('=');
        String key = idx <= 0 ? entry : entry.substring(0, idx);
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean needsPromptUpgrade(String prompt, String[] legacyMarkers) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }
        // 老版本的默认提示词：还带着识别标记，说明没被自定义过，可以安全替换
        for (String marker : legacyMarkers) {
            if (prompt.contains(marker)) {
                return true;
            }
        }
        return false;
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

    /** 修正明显不合理的值，避免用户手改配置后崩溃或一直失败。 */
    public void normalize() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.deepseek.com";
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        minLatinLetters = Math.max(1, minLatinLetters);
        chineseRatioThreshold = Math.min(1.0, Math.max(0.05, chineseRatioThreshold));
        maxIncomingChars = Math.max(16, maxIncomingChars);
        maxOutgoingChars = Math.max(16, maxOutgoingChars);
        requestsPerMinute = Math.max(1, requestsPerMinute);
        maxPendingTranslations = Math.max(1, maxPendingTranslations);
        cacheSize = Math.max(0, cacheSize);
        connectTimeoutSeconds = Math.max(1, connectTimeoutSeconds);
        httpTimeoutSeconds = Math.max(3, httpTimeoutSeconds);
        maxTokens = Math.max(32, maxTokens);
        temperature = Math.min(2.0, Math.max(0.0, temperature));
        if (failureFallback == null || failureFallback.isBlank()) {
            failureFallback = "CANCEL";
        }
        // 连字符也认：写成 send-original 的不少，而认错的代价是「消息静默不发出去」，
        // 这个方向的容错比严格更划算。
        failureFallback = failureFallback.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!failureFallback.equals("SEND_ORIGINAL")) {
            failureFallback = "CANCEL";
        }
        if (blacklistedPlayers == null) {
            blacklistedPlayers = new ArrayList<>();
        }
        if (ignorePatterns == null) {
            ignorePatterns = new ArrayList<>();
        }
        if (glossary == null) {
            glossary = new ArrayList<>();
        }
        if (translateCommandArgs == null) {
            translateCommandArgs = new LinkedHashMap<>();
        }
        if (guardedCommands == null) {
            guardedCommands = new ArrayList<>();
        }
        if (commandManagementKeywords == null) {
            commandManagementKeywords = new ArrayList<>();
        }
        if (protectedCommands == null) {
            protectedCommands = new ArrayList<>();
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
        this.enableThinking = o.enableThinking;
        this.temperature = o.temperature;
        this.maxTokens = o.maxTokens;
        this.connectTimeoutSeconds = o.connectTimeoutSeconds;
        this.httpTimeoutSeconds = o.httpTimeoutSeconds;
        this.retryOnFailure = o.retryOnFailure;
        this.enabled = o.enabled;
        this.translateIncoming = o.translateIncoming;
        this.translateOutgoing = o.translateOutgoing;
        this.translateCommandMessages = o.translateCommandMessages;
        this.skipOwnEcho = o.skipOwnEcho;
        this.failureFallback = o.failureFallback;
        this.blacklistedPlayers = o.blacklistedPlayers;
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
        this.maxPendingTranslations = o.maxPendingTranslations;
        this.cacheSize = o.cacheSize;
        this.ignorePatterns = o.ignorePatterns;
        this.glossary = o.glossary;
        this.translateCommandArgs = o.translateCommandArgs;
        this.guardedCommands = o.guardedCommands;
        this.commandManagementKeywords = o.commandManagementKeywords;
        this.translateUnknownCommands = o.translateUnknownCommands;
        this.protectedCommands = o.protectedCommands;
        this.incomingSystemPrompt = o.incomingSystemPrompt;
        this.outgoingSystemPrompt = o.outgoingSystemPrompt;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
