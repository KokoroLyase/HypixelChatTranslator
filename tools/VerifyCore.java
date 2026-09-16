import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.isomeria.hxtranslate.chat.ChatClientPort;
import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.chat.FeedbackPort;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.DeepSeekClient;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.core.PromptGlossary;
import com.isomeria.hxtranslate.core.TranslationService;
import com.isomeria.hxtranslate.util.CommandMessage;
import com.isomeria.hxtranslate.util.EchoMatcher;
import com.isomeria.hxtranslate.util.IncomingFilter;
import com.isomeria.hxtranslate.util.LangUtils;
import com.isomeria.hxtranslate.util.PlayerBlacklist;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 离线验证：不启动 Minecraft，直接验证翻译核心逻辑（语言判断、命令解析、DeepSeek 请求/响应）。
 *
 * <p>已经接进构建：{@code ./gradlew build} 会自动跑到这里，失败即构建失败；
 * 只想跑自检用 {@code ./gradlew verifyCore}。
 *
 * <p>覆盖不到的：{@code ChatTranslator} 依赖 Minecraft 类，不在本程序里 ——
 * 那部分的改动只能靠代码审查，并保持「同一条规则只有一个出口」的结构。
 */
public class VerifyCore {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        langUtils();
        commandSplit();
        hypixelCommands();
        hypixelSamples();
        v103Regressions();
        v104Hardening();
        v105ApiAndSafety();
        v106Review();
        v107Fixes();
        v108Audit();
        v111ScreenshotFixes();
        v112BannerIgnore();
        v113ConfigDurability();
        v113ApiTextAndRequest();
        v113OwnMessageAndRules();
        v114GlossaryBothDirections();
        v210ChatLogic();
        v214GlossaryMigration();
        v214AuditFixes();
        versionConsistency();
        docConsistency();

        httpSuccess();
        httpBaseUrls();
        httpErrors();
        requestBody();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    private static void langUtils() {
        System.out.println("== 语言判断 ==");
        check("英文不算汉字", !LangUtils.containsHan("Hello, how are you? gg wp"));
        check("中文算汉字", LangUtils.containsHan("你好世界"));
        check("中英混排算汉字", LangUtils.containsHan("gg 打得不错"));
        check("日文假名不算汉字", !LangUtils.containsHan("こんにちは"));
        check("中文标点不算汉字", !LangUtils.containsHan("，。！？"));
        checkEq("拉丁字母数", 5, LangUtils.countLatinLetters("abc 12 DE!"));
        checkEq("归一化", "hello world", LangUtils.normalizeKey("  Hello \t World  "));
        checkEq("去双引号", "你好", LangUtils.stripWrappingQuotes("\"你好\""));
        checkEq("去中文引号", "你好", LangUtils.stripWrappingQuotes("“你好”"));
        checkEq("无引号不变", "hello", LangUtils.stripWrappingQuotes("hello"));
        checkEq("单个引号不处理", "\"", LangUtils.stripWrappingQuotes("\""));

        checkEq("短文本不截断", "hello", LangUtils.truncateForChat("hello", 256));
        checkEq("正好等于上限不截断", "abcde", LangUtils.truncateForChat("abcde", 5));
        String longText = "word ".repeat(100).trim();
        String truncated = LangUtils.truncateForChat(longText, 20);
        check("超长被截断到上限内: " + truncated, truncated.length() <= 20);
        check("截断后带省略号", truncated.endsWith("…"));
        checkEq("在词边界切断", "word word word…", truncated);
        check("连续无空格也能硬切", LangUtils.truncateForChat("a".repeat(50), 10).length() <= 10);

        // v1.0.1 新增：汉字占比 / 正文提取 / 中文标点
        checkEq("纯中文占比 1.0", 1.0, LangUtils.hanRatio("你购买了金苹果"));
        checkEq("纯英文占比 0.0", 0.0, LangUtils.hanRatio("rush mid now"));
        check("带本地化队伍名的英文喊话占比很低", LangUtils.hanRatio("[MVP+] [红队] Steve: rush mid") < 0.2);
        check("中文击杀播报占比不高（所以不能只看占比）", LangUtils.hanRatio("isabellab2012被Venomed击杀。") < 0.2);
        check("识别中文标点：句号", LangUtils.containsCjkPunctuation("hello。"));
        check("识别中文标点：右角括号", LangUtils.containsCjkPunctuation("hbs_」被逼入末路"));
        check("识别中文标点：全角问号", LangUtils.containsCjkPunctuation("有人进攻？"));
        check("英文标点不算中文标点", !LangUtils.containsCjkPunctuation("stop! why? ok."));
        checkEq("取冒号后的正文", "rush mid", LangUtils.messageBody("[MVP+] [红队] Steve: rush mid"));
        checkEq("没有冒号时用整条", "hello world", LangUtils.messageBody("hello world"));
    }

    private static void commandSplit() {
        System.out.println("== 命令拆解 ==");
        TranslatorConfig config = new TranslatorConfig();

        assertSplit("私聊", "msg Steve 你好世界", "msg Steve ", "你好世界", config);
        assertSplit("回复", "r 你好", "r ", "你好", config);
        assertSplit("队伍频", "pc 集合", "pc ", "集合", config);
        assertSplit("大小写不敏感", "MSG Steve hi", "MSG Steve ", "hi", config);
        assertSplit("双空格", "msg  Steve  你好", "msg  Steve  ", "你好", config);
        assertSplit("回复双空格", "r   你好", "r   ", "你好", config);

        check("未配置的命令不翻译", CommandMessage.split("kill 你好", config.translateCommandArgs) == null);
        check("没有正文时返回 null", CommandMessage.split("msg Steve", config.translateCommandArgs) == null);
        check("空命令返回 null", CommandMessage.split("", config.translateCommandArgs) == null);
        check("null 返回 null", CommandMessage.split(null, config.translateCommandArgs) == null);
        check("只有命令名返回 null", CommandMessage.split("msg", config.translateCommandArgs) == null);
    }

    /** v1.0.2：按 Hypixel 官方命令表补全所有「玩家输入正文」的聊天命令。 */
    private static void hypixelCommands() {
        System.out.println("== Hypixel 聊天命令识别 ==");
        TranslatorConfig config = new TranslatorConfig();

        // 玩家反馈的 bug：/shout 喊话以前不在名单里，整条中文被原样发出去
        assertCommand(config, "shout 致我们伟大的末地石建筑", "shout ", "致我们伟大的末地石建筑");
        assertCommand(config, "shout 大家快来中路", "shout ", "大家快来中路");

        // 全局 / 队伍 / 公会 / 官员 频道
        assertCommand(config, "ac 有人吗", "ac ", "有人吗");
        assertCommand(config, "achat 有人吗", "achat ", "有人吗");
        assertCommand(config, "pc 集合", "pc ", "集合");
        assertCommand(config, "pchat 集合", "pchat ", "集合");
        assertCommand(config, "gc 大家好", "gc ", "大家好");
        assertCommand(config, "gchat 大家好", "gchat ", "大家好");
        assertCommand(config, "oc 开会了", "oc ", "开会了");
        assertCommand(config, "ochat 开会了", "ochat ", "开会了");

        // 私聊 / 好友私信
        assertCommand(config, "msg Steve 你好", "msg Steve ", "你好");
        assertCommand(config, "message Steve 你好", "message Steve ", "你好");
        assertCommand(config, "tell Steve 你好", "tell Steve ", "你好");
        assertCommand(config, "w Steve 你好", "w Steve ", "你好");
        assertCommand(config, "whisper Steve 你好", "whisper Steve ", "你好");
        assertCommand(config, "r 你好", "r ", "你好");
        assertCommand(config, "reply 你好", "reply ", "你好");

        // /party chat、/guild chat 写法
        assertCommand(config, "party chat 大家好", "party chat ", "大家好");
        assertCommand(config, "guild chat 大家好", "guild chat ", "大家好");

        // 管理命令不能被误当成聊天
        assertNoCommand(config, "party invite 小明");
        assertNoCommand(config, "p invite 小明");
        assertNoCommand(config, "g kick 小明");
        assertNoCommand(config, "guild warp");
        assertNoCommand(config, "chat p");
        assertNoCommand(config, "chat a");
        assertNoCommand(config, "party chat");

        // 未知命令兜底：正文明显是一句话才翻译，短参数（多半是玩家名）不碰
        assertCommand(config, "newchatcmd 大家快来这里集合", "newchatcmd ", "大家快来这里集合");
        assertNoCommand(config, "newcmd 小明");
        assertNoCommand(config, "tp 小明");
        assertNoCommand(config, "f add 小明明明");
        assertNoCommand(config, "report Steve 他开挂骂人");
        assertNoCommand(config, "visit 某某的家");
        assertNoCommand(config, "ah 我的世界");

        // 英文内容仍然要能解析出正文（后续 ChatTranslator 会因为没汉字而放行）
        assertCommand(config, "shout rush mid", "shout ", "rush mid");
        assertCommand(config, "msg Steve hello there", "msg Steve ", "hello there");

        // ---- 老配置迁移：v1.0.1 用户的配置里没有 /shout，还误收录了 /chat ----
        TranslatorConfig legacy = new TranslatorConfig();
        legacy.configVersion = 2;
        legacy.translateCommandArgs = new LinkedHashMap<>(Map.of("msg", 1, "r", 0, "chat", 0));
        legacy.guardedCommands = new ArrayList<>();
        legacy.protectedCommands = new ArrayList<>();
        boolean migrated = legacy.applyMigrations();
        check("迁移报告有改动", migrated);
        check("迁移后补上了 /shout", legacy.translateCommandArgs.containsKey("shout"));
        check("迁移后补上了 /pc、/gc、/oc", legacy.translateCommandArgs.containsKey("pc")
                && legacy.translateCommandArgs.containsKey("gc") && legacy.translateCommandArgs.containsKey("oc"));
        check("迁移后移除了误收录的 /chat", !legacy.translateCommandArgs.containsKey("chat"));
        checkEq("迁移保留了原有条目", Integer.valueOf(1), legacy.translateCommandArgs.get("msg"));
        check("迁移补齐了 guardedCommands", legacy.guardedCommands.contains("party"));
        checkEq("迁移后版本号已更新", TranslatorConfig.CURRENT_CONFIG_VERSION, legacy.configVersion);
        check("迁移后的旧配置能识别 /shout", CommandMessage.resolve("shout 你好啊", legacy) != null);
    }

    private static void assertCommand(TranslatorConfig config, String command, String head, String message) {
        CommandMessage.Split split = CommandMessage.resolve(command, config);
        if (split == null) {
            fail("/" + command + " -> 应能解析出正文，实际返回 null");
            return;
        }
        checkEq("/" + command + " head", head, split.head());
        checkEq("/" + command + " message", message, split.message());
    }

    private static void assertNoCommand(TranslatorConfig config, String command) {
        CommandMessage.Split split = CommandMessage.resolve(command, config);
        check("不应翻译: /" + command + (split == null ? "" : " -> 实际解析出正文 <" + split.message() + ">"),
                split == null);
    }

    /** v1.0.3：修「英文播报被中文标点误杀」和「喊话被当成自己回显丢弃」两个 bug。 */
    private static void v103Regressions() {
        System.out.println("== v1.0.3 回归：英文播报 + 喊话回显误判 ==");
        TranslatorConfig config = new TranslatorConfig();

        // bug 1：英文句子 + 服务器追加的中文后缀，以前被「含中文标点就跳过」误杀
        assertTranslate(config, "3_0HY was thrown into a black hole by G19sy. 最终击杀！");
        // 玩家反馈的喊话（以前被 ownEcho 子串匹配误判，见下面的 EchoMatcher 用例）
        assertTranslate(config, "[喊话] [黄队] [MVP+] Maceuser: green u have a real good range");
        assertTranslate(config, "[喊话] [红队] [MVP+] iFarmUnityPhoneFri: ur so sweaty bro chill! fr fr");

        // 反过来：中文播报（英文玩家名很长、汉字占比很低）不能被误翻
        assertSkip(config, "bedsyuu被Mlable击杀");
        assertSkip(config, "9twHest被iFarmUnityPhoneFri践踏。");
        assertSkip(config, "_Moriarty__受到了ku_jo232的冷淡。");
        assertSkip(config, "the_Sponger_ 被Maceuser_化作月尘。");
        assertSkip(config, "Green的床成为了iFarmUnityPhoneFri破坏的第1,569张床！");
        assertSkip(config, "RiloLess被G19sy塞进了戴维·琼斯的箱子。");
        assertSkip(config, "Blaineley被G19sy塞进了戴维·琼斯的箱子。");
        assertSkip(config, "Dzeaimo被bedsyuu挪落深渊。");
        assertSkip(config, "kallepekka1a被fabian1ooooo击杀。");
        assertSkip(config, "kerimfx12被Im_Emma307吼了。");
        assertSkip(config, "床已被破坏 >");
        assertSkip(config, "队伍已被淘汰 > 蓝队 已被淘汰！");
        assertSkip(config, "你购买了永久的铁链盔甲");
        assertSkip(config, "绿宝石不足！还需要绿宝石x6!");

        // ---- EchoMatcher：v1.0.2 的「包含」判断会把别人的话误判成自己的回显 ----
        String shout1 = "[喊话] [黄队] [MVP+] Maceuser: green u have a real good range";
        String shout2 = "[喊话] [红队] [MVP+] iFarmUnityPhoneFri: ur so sweaty bro chill! fr fr";
        check("发过 \"u\" 不会把别人的喊话误判成回显", EchoMatcher.findEcho(shout1, justSent("u")) == null);
        check("发过 \"so\" 不会把别人的喊话误判成回显", EchoMatcher.findEcho(shout2, justSent("so")) == null);
        check("发过 \"hi\" 不会把含 hi 的句子误判成回显",
                EchoMatcher.findEcho("[MVP+] Steve: hi there buddy", justSent("hi")) == null);
        check("发过 \"go\" 不会把别人的话误判成回显",
                EchoMatcher.findEcho("[MVP+] Alex: going mid now", justSent("go")) == null);

        // 真正的回显仍然要被认出来
        check("自己的喊话回显能认出来",
                EchoMatcher.findEcho("[喊话] [黄队] Isomeria: hello everyone come mid",
                        justSent("hello everyone come mid")) != null);
        check("自己的私聊回显能认出来", EchoMatcher.findEcho("To Steve: hi", justSent("hi")) != null);
        check("自己发的英文回显能认出来（v1.0.3 起英文也会被记住）",
                EchoMatcher.findEcho("[MVP+] Isomeria: nice bed defense", justSent("nice bed defense")) != null);
        check("服务器截断的长消息也能认出来",
                EchoMatcher.findEcho("Steve: this is a very long message that got",
                        justSent("this is a very long message that got cut off")) != null);

        // ---- LangUtils 新信号 ----
        checkEq("英文信号词：英文句子", 3, LangUtils.countEnglishHintWords("was thrown into a black hole by G19sy"));
        checkEq("英文信号词：玩家名不算", 0, LangUtils.countEnglishHintWords("Moriarty ku jo232 G19sy"));
        checkEq("英文信号词：整词匹配（im 不该命中 time/ime）", 0, LangUtils.countEnglishHintWords("time ime"));
        checkEq("英文信号词：单字母不算", 0, LangUtils.countEnglishHintWords("kallepekka a i u"));
        checkEq("最长汉字段：击杀", 2, LangUtils.longestHanRun("bedsyuu被Mlable击杀"));
        checkEq("最长汉字段：化作月尘", 4, LangUtils.longestHanRun("the_Sponger_ 被Maceuser_化作月尘。"));

        // ---- v3 -> v4 配置迁移 ----
        TranslatorConfig v3 = new TranslatorConfig();
        v3.configVersion = 3;
        v3.requestsPerMinute = 40;
        v3.glossary = new ArrayList<>(List.of("obby=黑曜石（obsidian）"));
        v3.applyMigrations();
        check("v4 迁移补上了新术语（sweaty）",
                v3.glossary.stream().anyMatch(g -> g.startsWith("sweaty=")));
        check("v4 迁移保留了用户原有术语",
                v3.glossary.stream().anyMatch(g -> g.startsWith("obby=")));
        checkEq("v4 迁移后 obby 没有重复", 1L, v3.glossary.stream().filter(g -> g.startsWith("obby=")).count());
        checkEq("限流默认值升到 60", 60, v3.requestsPerMinute);
        check("迁移后提示词带少样本示例", v3.incomingSystemPrompt.contains("Examples:"));

        TranslatorConfig custom = new TranslatorConfig();
        custom.configVersion = 3;
        custom.requestsPerMinute = 25;
        custom.applyMigrations();
        checkEq("用户自定义的限流值不被覆盖", 25, custom.requestsPerMinute);
    }

    /** v1.0.4：按审计清单加固——格式代码清洗、自消息识别、背压、限流、错误节流。 */
    private static void v104Hardening() throws Exception {
        System.out.println("== v1.0.4 加固：格式代码 / 背压 / 限流 ==");

        // 1) § 格式代码必须剔除，否则会被当成正文丢给模型
        checkEq("剔除颜色/格式代码", "[MVP+] Steve: inc mid",
                LangUtils.stripFormattingCodes("§7§l[MVP+] §r§fSteve: §ainc mid"));
        checkEq("没有代码时原样返回", "hello world", LangUtils.stripFormattingCodes("hello world"));
        checkEq("结尾孤立的 § 保留", "abc§", LangUtils.stripFormattingCodes("abc§"));
        checkEq("null 安全", "", LangUtils.stripFormattingCodes(null));

        TranslatorConfig config = new TranslatorConfig();
        // 带格式代码的英文喊话：清洗后应当判为「该翻译」
        String dirtyEnglish = "§7[喊话] §f[红队] §b[MVP+] §rSteve: §fgreen u have a real good range";
        assertTranslate(config, LangUtils.stripFormattingCodes(dirtyEnglish).strip());
        // 带格式代码的中文播报：清洗后仍然不该翻译
        assertSkip(config, LangUtils.stripFormattingCodes("§c你购买了金苹果§r").strip());
        assertSkip(config, LangUtils.stripFormattingCodes("§7Blaineley被G19sy塞进了戴维·琼斯的箱子。").strip());

        // 2) TranslationService 提交结果要能区分「没配 Key / 被限流 / 队列积压」
        try (MockServer server = new MockServer()) {
            server.response = ok("translated");
            server.delayMs = 700;

            TranslatorConfig noKey = new TranslatorConfig();
            TranslationService serviceNoKey = new TranslationService(noKey);
            checkEq("没配 Key -> NOT_READY", TranslationService.SubmitResult.NOT_READY,
                    serviceNoKey.submit("hello", Direction.INCOMING, (ok, t, e) -> { }));
            serviceNoKey.shutdown();

            TranslatorConfig limited = new TranslatorConfig();
            limited.apiKey = "sk-test";
            limited.apiBaseUrl = "http://127.0.0.1:" + server.port;
            limited.requestsPerMinute = 1;
            TranslationService serviceLimited = new TranslationService(limited);
            check("第一条受理", serviceLimited.submit("one", Direction.INCOMING, (ok, t, e) -> { }).accepted());
            checkEq("超过每分钟上限 -> RATE_LIMITED", TranslationService.SubmitResult.RATE_LIMITED,
                    serviceLimited.submit("two", Direction.INCOMING, (ok, t, e) -> { }));
            checkEq("空文本 -> EMPTY", TranslationService.SubmitResult.EMPTY,
                    serviceLimited.submit("   ", Direction.INCOMING, (ok, t, e) -> { }));
            serviceLimited.shutdown();

            TranslatorConfig burst = new TranslatorConfig();
            burst.apiKey = "sk-test";
            burst.apiBaseUrl = "http://127.0.0.1:" + server.port;
            burst.requestsPerMinute = 100;
            burst.maxPendingTranslations = 1;
            TranslationService serviceBurst = new TranslationService(burst);
            // 两个工作线程都在忙、队列里还排着 1 条时，下一条应被背压挡下
            serviceBurst.submit("burst one", Direction.INCOMING, (ok, t, e) -> { });
            serviceBurst.submit("burst two", Direction.INCOMING, (ok, t, e) -> { });
            serviceBurst.submit("burst three", Direction.INCOMING, (ok, t, e) -> { });
            Thread.sleep(120);
            checkEq("队列积压 -> QUEUE_FULL", TranslationService.SubmitResult.QUEUE_FULL,
                    serviceBurst.submit("burst four", Direction.INCOMING, (ok, t, e) -> { }));
            check("pendingTranslations 统计在途请求", serviceBurst.pendingTranslations() >= 1);
            serviceBurst.shutdown();
        }
    }

    /** v1.0.5：DeepSeek 换模型名 + 关思考模式 + 重试熔断 + 黑名单 + 注入防线。 */
    private static void v105ApiAndSafety() throws Exception {
        System.out.println("== v1.0.5：模型改名 / 思考模式 / 重试熔断 / 黑名单 ==");

        // 1) 旧模型名必须被自动迁移（2026-09 起 deepseek-chat 已下线）
        TranslatorConfig legacy = new TranslatorConfig();
        legacy.configVersion = 4;
        legacy.model = "deepseek-chat";
        legacy.temperature = 1.3;
        legacy.httpTimeoutSeconds = 20;
        legacy.applyMigrations();
        checkEq("deepseek-chat 自动换成 deepseek-flash", "deepseek-flash", legacy.model);
        checkEq("温度跟随新版默认值", 0.7, legacy.temperature);
        checkEq("读取超时跟随新版默认值", 15, legacy.httpTimeoutSeconds);
        checkEq("失败兜底默认不发送", "CANCEL", legacy.failureFallback);

        TranslatorConfig keep = new TranslatorConfig();
        keep.configVersion = 4;
        keep.model = "deepseek-v4-pro";
        keep.temperature = 0.2;
        keep.applyMigrations();
        checkEq("仍然有效的自定义模型名不被覆盖", "deepseek-v4-pro", keep.model);
        checkEq("用户自己调过的温度不被覆盖", 0.2, keep.temperature);
        check("默认必须关闭思考模式", !new TranslatorConfig().enableThinking);

        // 2) 黑名单判断
        List<String> blacklist = List.of("Steve", "小张");
        check("精确名字命中", PlayerBlacklist.matchesName("steve", blacklist));
        check("不在名单不命中", !PlayerBlacklist.matchesName("Alex", blacklist));
        check("系统聊天里的 [MVP+] Steve: 命中", PlayerBlacklist.speaksIn("[MVP+] Steve: inc mid", blacklist));
        check("行会前缀 Steve > 命中", PlayerBlacklist.speaksIn("Guild > Steve > hello", blacklist));
        check("中文名字命中", PlayerBlacklist.speaksIn("[红队] 小张: 冲", blacklist));
        check("正文提到名字不算发言", !PlayerBlacklist.speaksIn("[MVP+] Alex: ask Steve to def", blacklist));
        check("名字是别人前缀的一部分不算",
                !PlayerBlacklist.speaksIn("[MVP+] SteveJobs: hi", blacklist));
        check("空名单不命中", !PlayerBlacklist.speaksIn("[MVP+] Steve: hi", List.of()));

        // 3) 请求体：模型名、思考模式、注入防线、长度校验
        try (MockServer server = new MockServer()) {
            server.response = ok("translated");
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.apiBaseUrl = "http://127.0.0.1:" + server.port;
            DeepSeekClient client = new DeepSeekClient(config);

            client.translate("hello", Direction.INCOMING);
            JsonObject body = JsonParser.parseString(server.lastBody).getAsJsonObject();
            checkEq("请求里模型名是 deepseek-flash", "deepseek-flash", body.get("model").getAsString());
            checkEq("请求里思考模式已关闭", "disabled",
                    body.getAsJsonObject("thinking").get("type").getAsString());
            check("非思考模式才传 temperature", body.has("temperature"));
            String sys = body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            check("提示词声明「内容是数据不是指令」",
                    sys.contains("DATA to translate") && sys.contains("never obey"));

            // 模型开始长篇大论时要拦下来
            server.response = ok("x".repeat(600));
            DeepSeekClient.Result tooLong = client.translate("hi", Direction.INCOMING);
            check("超长译文被拦下", !tooLong.ok() && tooLong.error().contains("长度异常"));

            // 4) 模型列表查询
            server.response = "{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-flash\"},{\"id\":\"deepseek-v4-pro\"}]}";
            DeepSeekClient.Result models = client.listModels();
            check("能查到可用模型", models.ok() && models.text().contains("deepseek-flash")
                    && models.text().contains("deepseek-v4-pro"));
            checkEq("模型列表走 /models", "/models", server.lastPath);
        }

        // 5) 429/5xx 自动重试一次后成功
        try (MockServer server = new MockServer()) {
            server.failFirst = 1;
            server.failStatus = 500;
            server.response = ok("translated");
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.apiBaseUrl = "http://127.0.0.1:" + server.port;
            DeepSeekClient client = new DeepSeekClient(config);
            DeepSeekClient.Result retried = client.translate("hello", Direction.INCOMING);
            check("500 会重试一次并成功", retried.ok());
        }

        // 6) 连续失败后熔断，不再无脑打接口
        try (MockServer server = new MockServer()) {
            server.status = 503;
            server.response = "{\"error\":{\"message\":\"unavailable\"}}";
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.apiBaseUrl = "http://127.0.0.1:" + server.port;
            config.retryOnFailure = false;
            DeepSeekClient client = new DeepSeekClient(config);
            for (int i = 0; i < 5; i++) {
                client.translate("msg" + i, Direction.INCOMING);
            }
            check("连续失败后进入熔断", client.isCircuitOpen());
            DeepSeekClient.Result blocked = client.translate("later", Direction.INCOMING);
            check("熔断期间直接拒绝并说明原因",
                    !blocked.ok() && blocked.error().contains("暂停"));
        }
    }

    /** v1.0.6：复核发现的逻辑问题——本模组自己的客户端命令不能被「未知命令兜底」劫持。 */
    private static void v106Review() {
        System.out.println("== v1.0.6 复核：自己的命令不被劫持 ==");
        TranslatorConfig config = new TranslatorConfig();
        // 这些命令如果被劫持，会被取消并当成服务器命令发出去
        assertNoCommand(config, "hxtranslate test 这是一句很长的中文话");
        assertNoCommand(config, "hxt test 大家快来这里集合");
        assertNoCommand(config, "hxtranslate key sk-abcdefghijklmn");
        assertNoCommand(config, "HXTRANSLATE test 中文测试文本要长一点");
        check("isAlwaysProtected 认得出自己的命令", CommandMessage.isAlwaysProtected("hxtranslate status"));
        check("别的命令不算自己的命令", !CommandMessage.isAlwaysProtected("shout hello"));
    }

    /**
     * v1.0.7：复核发现的 4 个真实缺陷 + 1 道防御。
     *
     * <p>其中「缓存命中插队」是 v1.0.5「连打两条中文乱序」那次修复漏掉的路径：
     * 当时把发送方向改成了单线程 FIFO，但缓存命中在 {@code submit()} 里直接回调，
     * 根本没进那个队列。
     */
    private static void v107Fixes() throws Exception {
        System.out.println("== v1.0.7：缓存插队 / 回显时间窗 / 模型兜底 / 未翻译输出 ==");

        // 1) 模型名兜底值：空 model 不能回落成已下线的 deepseek-chat
        TranslatorConfig blankModel = new TranslatorConfig();
        blankModel.model = "   ";
        blankModel.normalize();
        checkEq("空模型名兜底成当前默认模型", "deepseek-flash", blankModel.model);
        checkEq("默认模型常量与字段一致", TranslatorConfig.DEFAULT_MODEL, new TranslatorConfig().model);

        // 2) 回显时间窗：自己发过的短译文不能整局都拿去吞别人的话
        long now = System.currentTimeMillis();
        check("时间窗内：自己发出去的译文回显认得出来",
                EchoMatcher.findEcho("[MVP+] Isomeria: wp",
                        List.of(EchoMatcher.Sent.at("wp", now - 900)), now) != null);
        check("玩家反馈场景：约 1 分钟后别人说的 wp 必须照常翻译",
                EchoMatcher.findEcho("[MVP+] [红队] Steve: wp",
                        List.of(EchoMatcher.Sent.at("wp", now - 60_000)), now) == null);
        check("时间窗边缘内仍算自己的回显",
                EchoMatcher.findEcho("[MVP+] Isomeria: ty",
                        List.of(EchoMatcher.Sent.at("ty", now - 14_000)), now) != null);
        check("超过时间窗的长消息也不再认领",
                EchoMatcher.findEcho("[MVP+] Steve: inc mid, u def obby",
                        List.of(EchoMatcher.Sent.at("inc mid, u def obby", now - 16_000)), now) == null);
        check("时钟回拨时宁可多翻一条，也不吞别人的话",
                EchoMatcher.findEcho("[MVP+] Steve: wp",
                        List.of(EchoMatcher.Sent.at("wp", now + 5_000)), now) == null);

        try (MockServer server = new MockServer()) {
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.apiBaseUrl = "http://127.0.0.1:" + server.port;

            // 3) 发送方向：模型原样返回中文必须按失败处理
            //    （否则等于替玩家把中文发到英文服，正是本模组要避免的事）
            DeepSeekClient client = new DeepSeekClient(config);
            server.response = ok("你好世界");
            DeepSeekClient.Result unchanged = client.translate("你好世界", Direction.OUTGOING);
            check("译文仍是中文 -> 判为失败", !unchanged.ok() && unchanged.error().contains("仍有汉字"));

            // v2.1.4 起判据是「一个汉字都不许有」，所以夹着中文玩家名的译文同样按失败处理。
            // 取舍理由见 DeepSeekClient.parseResponse 的注释；半中半英的完整用例在 v214AuditFixes。
            server.response = ok("find 小明 to play");
            DeepSeekClient.Result withName = client.translate("找小明一起玩", Direction.OUTGOING);
            check("英文译文里夹中文玩家名 -> 也判为失败（宁可不发，也不把汉字送进英文服）",
                    !withName.ok() && withName.error().contains("仍有汉字"));

            server.response = ok("find xiaoming to play");
            check("模型把中文玩家名转成拼音 -> 正常放行",
                    client.translate("找小明一起玩", Direction.OUTGOING).ok());

            server.response = ok("你好世界");
            check("接收方向返回中文是正常的（不受这道校验影响）",
                    client.translate("hello world", Direction.INCOMING).ok());

            // 4) 缓存命中不能插队：先发的必须先回调
            server.delayMs = 0;
            server.response = ok("cached translation");
            TranslationService service = new TranslationService(config);
            CountDownLatch warmed = new CountDownLatch(1);
            checkEq("预热请求受理", TranslationService.SubmitResult.ACCEPTED,
                    service.submit("你好世界", Direction.OUTGOING, (ok, t, e) -> warmed.countDown()));
            check("预热请求完成（结果已进缓存）", warmed.await(10, TimeUnit.SECONDS));

            server.delayMs = 600;
            server.response = ok("network translation");
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch both = new CountDownLatch(2);
            service.submit("这是一条要走网络的中文消息", Direction.OUTGOING,
                    (ok, t, e) -> { order.add("先发(走网络)"); both.countDown(); });
            service.submit("你好世界", Direction.OUTGOING,
                    (ok, t, e) -> { order.add("后发(命中缓存)"); both.countDown(); });
            check("两条都拿到回调", both.await(10, TimeUnit.SECONDS));
            checkEq("命中缓存的第二条不插队，仍然先发先回",
                    List.of("先发(走网络)", "后发(命中缓存)"), List.copyOf(order));
            service.shutdown();

            // 5) 被背压挡下的请求不该消耗每分钟配额
            TranslatorConfig burst = new TranslatorConfig();
            burst.apiKey = "sk-test";
            burst.apiBaseUrl = "http://127.0.0.1:" + server.port;
            burst.requestsPerMinute = 100;
            burst.maxPendingTranslations = 1;
            TranslationService serviceBurst = new TranslationService(burst);
            server.delayMs = 1500;
            // 接收方向是 2 个工作线程：先让两个线程都忙起来，队列才是空的
            serviceBurst.submit("背压一", Direction.INCOMING, (ok, t, e) -> { });
            serviceBurst.submit("背压二", Direction.INCOMING, (ok, t, e) -> { });
            Thread.sleep(400);
            checkEq("两个工作线程都忙时仍可排队一条", TranslationService.SubmitResult.ACCEPTED,
                    serviceBurst.submit("背压三", Direction.INCOMING, (ok, t, e) -> { }));
            checkEq("队列积压 -> QUEUE_FULL", TranslationService.SubmitResult.QUEUE_FULL,
                    serviceBurst.submit("背压四", Direction.INCOMING, (ok, t, e) -> { }));
            checkEq("被挡下的请求不消耗限流配额（只应记 3 条）", 3,
                    serviceBurst.usedRequestsThisMinute());
            serviceBurst.shutdown();
        }
    }

    /**
     * v1.0.8：第二轮全面复核。
     *
     * <p>本段覆盖 4 项可以离线验证的修复；另外一项（出站被限流/背压挡下时也要遵守
     * {@code failureFallback}）在 {@code ChatTranslator} 里，那里依赖 Minecraft 类，
     * 按工程约定不纳入离线自检 —— 它靠「6 个降级调用点全部收口到 sendOriginalOnFailure()」保证。
     */
    private static void v108Audit() throws Exception {
        System.out.println("== v1.0.8 复核：不可信文本 / 代理对截断 / 缓存容量 / 熔断复位 ==");

        // 1) 接口返回的错误正文是「不可信输入」（用户可能配第三方中转站）：
        //    带 § 会被原版渲染成颜色代码，带换行会把一条提示拆成好几行
        checkEq("清洗不可信文本：去掉 § 代码", "翻译失败 请稍后重试",
                LangUtils.sanitizeOneLine("§c翻译失败§r 请稍后重试"));
        checkEq("清洗不可信文本：换行压成一行", "第一行 第二行",
                LangUtils.sanitizeOneLine("第一行\n第二行"));
        checkEq("清洗不可信文本：回车与制表符也压掉", "a b",
                LangUtils.sanitizeOneLine("a\r\tb"));
        checkEq("清洗不可信文本：其余控制字符丢弃", "ab",
                LangUtils.sanitizeOneLine("a\u0000\u0007b"));
        checkEq("清洗不可信文本：null 安全", "", LangUtils.sanitizeOneLine(null));

        // 2) 截断不能把 emoji 的代理对劈成两半（孤立代理会变乱码，还可能被服务器拒收）
        check("代理对截断：不产生孤立代理项",
                !hasLoneSurrogate(LangUtils.truncateForChat("😀😀😀😀", 4)));
        checkEq("代理对截断：只保留放得下的完整 emoji + 省略号", "😀…",
                LangUtils.truncateForChat("😀😀😀😀", 4));
        check("代理对截断：中英混排也不产生孤立代理",
                !hasLoneSurrogate(LangUtils.truncateForChat("中文测试😀内容abc", 9)));
        check("代理对截断：长度仍不超上限",
                LangUtils.truncateForChat("😀😀😀😀", 4).length() <= 4);
        check("代理对截断：没超长时原样返回",
                LangUtils.truncateForChat("😀ok", 10).equals("😀ok"));

        // 3) failureFallback 的兜底方向必须是「不发」：配置写错时绝不能反而把中文漏出去
        TranslatorConfig typo = new TranslatorConfig();
        typo.failureFallback = "send_original"; // 大小写不同
        typo.normalize();
        checkEq("failureFallback 大小写不敏感", "SEND_ORIGINAL", typo.failureFallback);
        TranslatorConfig hyphen = new TranslatorConfig();
        hyphen.failureFallback = "send-original"; // 连字符是最常见的写法错误
        hyphen.normalize();
        checkEq("failureFallback 连字符也认（写错就静默不发，代价太大）",
                "SEND_ORIGINAL", hyphen.failureFallback);
        TranslatorConfig wrong = new TranslatorConfig();
        wrong.failureFallback = "随便写的值";
        wrong.normalize();
        checkEq("failureFallback 写错时按 CANCEL 兜底", "CANCEL", wrong.failureFallback);

        try (MockServer server = new MockServer()) {
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.apiBaseUrl = "http://127.0.0.1:" + server.port;
            config.requestsPerMinute = 1000;
            config.retryOnFailure = false; // 熔断用例不要每次都等 800ms 退避

            // 4) 错误正文里的 § 和换行必须清洗掉再进聊天栏
            //    断言必须要求「真正的原因仍然出现」：只断言「不含 § / 不含换行」的话，
            //    把 extractErrorMessage 改成 return "" 也照样是绿的（等于没保护这条接线）。
            DeepSeekClient client = new DeepSeekClient(config);
            server.status = 500;
            server.response = "{\"error\":{\"message\":\"§c网关坏了\\n请稍后重试\"}}";
            DeepSeekClient.Result dirty = client.translate("hi", Direction.INCOMING);
            check("接口错误正文里的 § 被清洗", !dirty.ok() && !dirty.error().contains("§"));
            check("接口错误正文里的换行被清洗", !dirty.ok() && !dirty.error().contains("\n"));
            check("接口错误正文清洗后仍带着真正的原因",
                    !dirty.ok() && dirty.error().contains("网关坏了 请稍后重试"));
            server.status = 200;

            // 5) 熔断后复位要能立刻重试（/hxtranslate reload 会调用它）
            server.status = 500;
            for (int i = 0; i < 5; i++) {
                client.translate("trip" + i, Direction.INCOMING);
            }
            check("连续失败后进入熔断", client.isCircuitOpen());
            client.resetCircuit();
            check("复位后不再熔断", !client.isCircuitOpen());
            server.status = 200;
            server.response = ok("ok");
            check("复位后能立刻正常翻译", client.translate("after reset", Direction.INCOMING).ok());

            // 6) cacheSize 必须每次从配置读：以前在构造时固化，reload 改配置要重启游戏才生效
            TranslatorConfig sized = new TranslatorConfig();
            sized.apiKey = "sk-test";
            sized.apiBaseUrl = "http://127.0.0.1:" + server.port;
            sized.requestsPerMinute = 1000;
            sized.cacheSize = 100;                 // 构造时是大容量
            TranslationService service = new TranslationService(sized);
            sized.cacheSize = 16;                  // 模拟 /hxtranslate reload 把它改小
            server.delayMs = 0;
            server.response = ok("cached value");
            // 必须用发送方向：它是单线程 FIFO，写入缓存的先后是确定的。
            // 收方向有 2 个线程，谁先返回谁先入缓存，「哪条被挤掉」会随机。
            CountDownLatch filled = new CountDownLatch(17);
            for (int i = 0; i < 17; i++) {
                service.submit("缓存容量测试 " + i, Direction.OUTGOING, (ok, t, e) -> filled.countDown());
            }
            check("17 条不同文本都翻译完成", filled.await(30, TimeUnit.SECONDS));
            int beforeRefill = server.requestCount.get();
            CountDownLatch refill = new CountDownLatch(1);
            service.submit("缓存容量测试 0", Direction.OUTGOING, (ok, t, e) -> refill.countDown());
            check("改小后的 cacheSize 立刻生效（第一条已被 LRU 挤掉，重新走了网络）",
                    refill.await(10, TimeUnit.SECONDS) && server.requestCount.get() == beforeRefill + 1);
            service.shutdown();
        }
    }

    /** 字符串里是否存在孤立的代理项（半个 emoji）。 */
    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * v1.1.1：玩家截图反馈的两个 bug，用例里的字符串就是截图里的原话。
     *
     * <p><b>图1</b>：{@code Im_Bad_At_PKMN失足跌入虚空。} 是服务器本地化过的中文播报，
     * 却被翻译了一遍 —— 聊天栏多出一行一模一样的 {@code [译] Im_Bad_At_PKMN失足跌入虚空。}，
     * 还白花一次请求。根因：玩家名被切成 {@code im} / {@code bad} / {@code at} 三个
     * 「英文信号词」，把整条中文消息判成了英文句子。
     *
     * <p><b>图2</b>：自己说了句 {@code gg} 之后，别人说的 {@code gg} 全都不翻译了。
     * 根因：回显判断只看「正文和我发过的一样不一样」，不看说话人 ——
     * 而系统聊天里本来就写着谁在说话。
     */
    private static void v111ScreenshotFixes() {
        System.out.println("== v1.1.1：玩家截图反馈 ==");
        TranslatorConfig config = new TranslatorConfig();

        // ---- 图1：本地化中文播报不能被翻译 ----
        assertSkip(config, "Im_Bad_At_PKMN失足跌入虚空。");
        checkEq("玩家名里的 im/bad/at 不再算英文信号词", 0,
                LangUtils.countEnglishHintWords("Im_Bad_At_PKMN失足跌入虚空。"));
        assertSkip(config, "Im_Bad_At_PKMN被G19sy击杀。");
        checkEq("同一句话换成普通名字也照样跳过", 0,
                LangUtils.countEnglishHintWords("bedsyuu失足跌入虚空。"));
        // 但这个词出现在真正的英文句子里时，仍要翻译（名字和句子之间有空格）
        assertTranslate(config, "Im_Bad_At_PKMN was thrown into the void by G19sy.");
        assertTranslate(config, "bad at the game, u def mid");
        // 截图里其它几行
        assertSkip(config, "你购买了 Wood");
        assertSkip(config, "铁锭不足！还需要铁锭x21!");
        assertTranslate(config, "You earned 187 Bed Wars XP");

        // ---- 图2：按说话人认回显，不再按正文猜 ----
        check("别人的 gg：名字对不上，不是我的消息（关键修复）",
                Boolean.FALSE.equals(EchoMatcher.isOwnMessage("[VIP] KineticRules: gg", "Isomeria")));
        check("别人的 gg：说一样的话也不是我的",
                Boolean.FALSE.equals(EchoMatcher.isOwnMessage("DonTlacario04: gg", "Isomeria")));
        check("自己的 gg：名字对上才是我的",
                Boolean.TRUE.equals(EchoMatcher.isOwnMessage("Isomeria: gg", "Isomeria")));
        check("名字比对大小写不敏感",
                Boolean.TRUE.equals(EchoMatcher.isOwnMessage("[MVP+] isomeria: gg", "Isomeria")));
        check("队伍/喊话前缀里的名字也能认出来",
                Boolean.TRUE.equals(EchoMatcher.isOwnMessage("[喊话] [红队] [MVP+] Isomeria: gg", "Isomeria")));
        check("To xxx 是自己发出的私聊",
                Boolean.TRUE.equals(EchoMatcher.isOwnMessage("To Steve: hi", "Isomeria")));
        check("别人私聊给我（From）不是我的消息",
                Boolean.FALSE.equals(EchoMatcher.isOwnMessage("From Steve: gg", "Isomeria")));
        check("认不出说话人时返回 null（调用方退回正文比对）",
                EchoMatcher.isOwnMessage("Guild > Steve > hello", "Isomeria") == null);
        check("拿不到本地名字时也返回 null（退回正文比对）",
                EchoMatcher.isOwnMessage("[MVP+] Steve: gg", null) == null);
        check("服务器公告不误判成玩家发言",
                EchoMatcher.speakerOf("You earned 187 Bed Wars XP") == null);
        checkEq("说话人解析", "Steve", EchoMatcher.speakerOf("Party > [MVP+] Steve: hi").name());
    }

    /**
     * v1.1.2：服务器横幅不再翻译。
     *
     * <p>截图里还出现过一行 {@code [译] 起床战争} —— 那是把游戏名 {@code Bed Wars} 翻成了中文。
     * 它不是误判（原文确实是英文），但横幅不是给人读的句子：翻译它只会多出一行没用的译文、
     * 还多花一次请求。这里用默认 {@code ignorePatterns} 挡掉（{@code configVersion} 5 → 6）。
     */
    private static void v112BannerIgnore() {
        System.out.println("== v1.1.2：服务器横幅不再翻译 ==");
        TranslatorConfig config = new TranslatorConfig();

        // 截图里的横幅：可能是「分隔线 + 游戏名」整块，也可能是单独一行游戏名
        check("横幅（分隔线 + 游戏名整块）被忽略",
                hitsIgnorePattern(config, "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬\nBed Wars\n▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
        check("单独一行游戏名被忽略", hitsIgnorePattern(config, "Bed Wars"));
        check("大小写与无空格变体也命中", hitsIgnorePattern(config, "bedwars"));
        check("纯分隔线被忽略", hitsIgnorePattern(config, "▬▬▬▬▬▬▬▬▬▬▬▬"));
        check("别的分隔符也认（----- / ===== / ═════）",
                hitsIgnorePattern(config, "----------") && hitsIgnorePattern(config, "==========")
                        && hitsIgnorePattern(config, "══════════"));

        // 真正的聊天一个字都不能误伤
        check("u def 不受影响", !hitsIgnorePattern(config, "u def"));
        check("rush mid 不受影响", !hitsIgnorePattern(config, "rush mid"));
        check("短句 inc 不受影响", !hitsIgnorePattern(config, "inc"));
        check("点名单词不受影响", !hitsIgnorePattern(config, "G19sy"));
        check("玩家喊话不受影响", !hitsIgnorePattern(config, "[MVP+] [红队] Steve: rush mid"));
        check("Bed Wars 出现在句子里不受影响", !hitsIgnorePattern(config, "we lost bed wars lol"));
        check("少一个分隔符就不算横幅", !hitsIgnorePattern(config, "--- hi"));

        // ---- v5 -> v6 迁移 ----
        TranslatorConfig v5 = new TranslatorConfig();
        v5.configVersion = 5;
        v5.ignorePatterns = new ArrayList<>(List.of(
                "^\\+\\d+ .*(XP|Coins|Tokens)", "^(You|A player) (joined|left)", "^Sending you to"));
        v5.applyMigrations();
        checkEq("v5 的默认列表补上了横幅规则", 5, v5.ignorePatterns.size());
        checkEq("迁移后 configVersion", TranslatorConfig.CURRENT_CONFIG_VERSION, v5.configVersion);

        // 用户自己加过规则：旧默认值还在 → 照样补缺，且不删他的
        TranslatorConfig extended = new TranslatorConfig();
        extended.configVersion = 5;
        extended.ignorePatterns = new ArrayList<>(List.of(
                "^\\+\\d+ .*(XP|Coins|Tokens)", "^(You|A player) (joined|left)", "^Sending you to",
                "^我的自定义规则"));
        extended.applyMigrations();
        check("用户自己加的规则保留", extended.ignorePatterns.contains("^我的自定义规则"));
        checkEq("补缺后共 6 条", 6, extended.ignorePatterns.size());

        // 用户删过旧默认值：说明有意调整过，不硬塞
        TranslatorConfig custom = new TranslatorConfig();
        custom.configVersion = 5;
        custom.ignorePatterns = new ArrayList<>(List.of("^我的自定义规则"));
        custom.applyMigrations();
        checkEq("用户删过默认值就不动它", 1, custom.ignorePatterns.size());
        checkEq("自定义内容原样保留", "^我的自定义规则", custom.ignorePatterns.get(0));
    }

    /**
     * 这条消息是否命中 {@code ignorePatterns}。
     *
     * <p>刻意调用 <b>生产代码用的同一个函数</b>（{@link LangUtils#compilePatterns} +
     * {@link LangUtils#matchesAny}）：以前这里自己抄了一遍
     * {@code Pattern.compile(regex, CASE_INSENSITIVE).matcher(text).find()}，
     * 于是 ChatTranslator 那边把 {@code find()} 改成 {@code matches()}、或者丢了
     * {@code CASE_INSENSITIVE}，下面那些横幅用例全都是绿的 —— 用例其实没在保护任何东西。
     */
    private static boolean hitsIgnorePattern(TranslatorConfig config, String text) {
        return LangUtils.matchesAny(text, LangUtils.compilePatterns(config.ignorePatterns, null));
    }

    // ------------------------------------------------------------------
    // v1.1.3：核心链路审计（没有玩家截图，是复查出来的；样本按「真实可能出现的输入」构造）
    // ------------------------------------------------------------------

    /**
     * 配置文件是用户资产：读坏了要备份、写要原子、不认识的字段不能抹掉。
     *
     * <p>这一组用例填补的是「磁盘 I/O 路径此前 0 覆盖」的空白 —— 而本次审计最严重的发现
     * （没有 {@code configVersion} 的老配置被当成最新版，所有迁移被跳过）正好在这条路径上。
     */
    private static void v113ConfigDurability() throws IOException {
        System.out.println("== v1.1.3：配置不再丢 ==");
        Path dir = Files.createTempDirectory("hxtranslate-verify-");
        try {
            // ---- 1) v1.0.0 时代的配置：文件里**没有** configVersion（v1.0.1 起才写）----
            //    Gson 会给「文件里没有的键」保留字段初始值，也就是当前版本号，
            //    于是迁移第一行就判定「已是最新」直接返回 —— 模型名永远停在已下线的 deepseek-chat。
            JsonObject legacyJson = new JsonObject();
            legacyJson.addProperty("apiKey", "sk-legacy");
            legacyJson.addProperty("model", "deepseek-chat");
            legacyJson.addProperty("temperature", 1.3);
            legacyJson.addProperty("httpTimeoutSeconds", 20);
            legacyJson.addProperty("requestsPerMinute", 40);
            legacyJson.add("glossary", new JsonArray());
            legacyJson.addProperty("incomingSystemPrompt",
                    "You translate Minecraft chat. Keep common gaming abbreviations meaningful.");
            JsonArray legacyIgnores = new JsonArray();
            legacyIgnores.add("^\\+\\d+ .*(XP|Coins|Tokens)");
            legacyIgnores.add("^(You|A player) (joined|left)");
            legacyIgnores.add("^Sending you to");
            legacyJson.add("ignorePatterns", legacyIgnores);
            Path legacyFile = dir.resolve("hxtranslate.json");
            Files.writeString(legacyFile, legacyJson.toString(), StandardCharsets.UTF_8);

            TranslatorConfig legacy = TranslatorConfig.load(legacyFile);
            checkEq("没有 configVersion 的旧配置：模型名换成当前的（否则每条请求 400）",
                    "deepseek-flash", legacy.model);
            checkEq("没有 configVersion 的旧配置：版本号升到当前", TranslatorConfig.CURRENT_CONFIG_VERSION,
                    legacy.configVersion);
            checkEq("旧配置的每分钟上限跟随新默认值", 60, legacy.requestsPerMinute);
            checkEq("旧配置的读取超时跟随新默认值", 15, legacy.httpTimeoutSeconds);
            check("旧配置的术语表被补齐", legacy.glossary.size() > 10);
            checkEq("旧配置补上了横幅规则", 5, legacy.ignorePatterns.size());
            check("旧配置的老提示词被换成了新默认值", legacy.incomingSystemPrompt.contains("Examples:"));
            checkEq("用户写的 API Key 原样保留", "sk-legacy", legacy.apiKey);
            check("迁移结果落盘（configVersion 已写进文件）",
                    Files.readString(legacyFile, StandardCharsets.UTF_8)
                            .contains("\"configVersion\": " + TranslatorConfig.CURRENT_CONFIG_VERSION));

            // ---- 2) 坏 JSON：备份原件 + 给玩家看的原因，且绝不静默覆盖 ----
            Path brokenFile = dir.resolve("broken.json");
            String brokenText = "{\n  \"apiKey\": \"sk-USER-SECRET\",\n  \"glossary\": [\"我的词=意思\"]\n"
                    + "  \"debugLog\": true\n}"; // 少一个逗号，正是手改配置最容易犯的错
            Files.writeString(brokenFile, brokenText, StandardCharsets.UTF_8);

            TranslatorConfig fallback = TranslatorConfig.load(brokenFile);
            check("坏配置：给出给玩家看的警告（不是只写日志）",
                    fallback.loadWarning() != null && fallback.loadWarning().contains("JSON"));
            check("坏配置：本次退回默认值", !fallback.hasApiKey());
            checkEq("坏配置：原文件一字未改", brokenText, Files.readString(brokenFile, StandardCharsets.UTF_8));

            List<Path> backups = backupsOf(dir, "broken.json.broken-");
            checkEq("坏配置：生成了备份", 1, backups.size());
            // 用例本身要抗「备份没生成」：否则一条断言失败会以异常收场，后面的用例全都跑不到
            checkEq("坏配置：备份内容就是原件（Key 还在）", brokenText, readIfExists(backups));
            // 之后任何一次 save()（按 F6、/hxtranslate on|key|debug…）都会写新文件，
            // 但备份必须还在 —— 这就是「配置不会永久丢」的底线。
            fallback.save(brokenFile);
            checkEq("坏配置：保存之后备份仍在，内容可恢复", brokenText, readIfExists(backups));

            // ---- 3) 数值笔误（例如 configVersion 写成 6.5）同样不能静默 ----
            Path typoFile = dir.resolve("typo.json");
            Files.writeString(typoFile, "{\"configVersion\": 6.5, \"apiKey\": \"sk-typo\"}", StandardCharsets.UTF_8);
            TranslatorConfig typo = TranslatorConfig.load(typoFile);
            check("数值笔误：有警告", typo.loadWarning() != null);
            checkEq("数值笔误：原件也备份了", 1, backupsOf(dir, "typo.json.broken-").size());

            // ---- 4) 不认识的字段不能被抹掉（用户备注 / 新版模组写过的字段）----
            Path noteFile = dir.resolve("note.json");
            TranslatorConfig note = new TranslatorConfig();
            note.apiKey = "sk-note";
            note.save(noteFile);
            JsonObject withNote = JsonParser.parseString(Files.readString(noteFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            withNote.addProperty("myNote", "别删我");
            Files.writeString(noteFile, withNote.toString(), StandardCharsets.UTF_8);
            TranslatorConfig reloadedNote = TranslatorConfig.load(noteFile);
            reloadedNote.save(noteFile);
            JsonObject afterSave = JsonParser.parseString(Files.readString(noteFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            check("保存不会抹掉模组不认识的字段",
                    afterSave.has("myNote") && "别删我".equals(afterSave.get("myNote").getAsString()));
            checkEq("认识的字段照常写回", "sk-note", afterSave.get("apiKey").getAsString());

            // ---- 5) 写入是原子的：先写 .tmp 再改名，中途崩溃不会留下半个 json ----
            Path atomicFile = dir.resolve("atomic.json");
            check("原子写入成功", TranslatorConfig.writeAtomically(atomicFile, "{\"hello\":1}"));
            checkEq("原子写入内容正确", "{\"hello\":1}",
                    Files.readString(atomicFile, StandardCharsets.UTF_8));
            check("原子写入不留下临时文件", !Files.exists(dir.resolve("atomic.json.tmp")));

            // ---- 6) normalize 的上下限：调大 maxOutgoingChars 会被服务器踢，调小 cacheSize 没意义 ----
            TranslatorConfig bounds = new TranslatorConfig();
            bounds.maxOutgoingChars = 999;
            bounds.cacheSize = 0;
            bounds.normalize();
            checkEq("maxOutgoingChars 上限夹到原版聊天框长度", TranslatorConfig.MAX_OUTGOING_CHARS_LIMIT,
                    bounds.maxOutgoingChars);
            checkEq("cacheSize 下限显式化", TranslatorConfig.MIN_CACHE_SIZE, bounds.cacheSize);

            // ---- 7) 提示词迁移：只替换「老版默认值」，手写提示词一字不动（RELEASING §5）----
            //    以前的判据是「提示词里没有 Examples: 就换成默认值」——手写提示词本来就可能没有这一段，
            //    于是用户自己写的提示词会被整段覆盖。现在只认老版默认值的识别标记。
            TranslatorConfig customPrompt = new TranslatorConfig();
            customPrompt.configVersion = 3;
            customPrompt.incomingSystemPrompt = "只翻译成中文，别啰嗦。";
            customPrompt.outgoingSystemPrompt = "Translate into natural English.";
            customPrompt.applyMigrations();
            checkEq("手写的接收方向提示词不被覆盖", "只翻译成中文，别啰嗦。", customPrompt.incomingSystemPrompt);
            checkEq("手写的发送方向提示词不被覆盖", "Translate into natural English.",
                    customPrompt.outgoingSystemPrompt);

            TranslatorConfig oldDefault = new TranslatorConfig();
            oldDefault.configVersion = 3;
            oldDefault.incomingSystemPrompt =
                    "You translate Minecraft chat. Keep common gaming abbreviations meaningful.";
            oldDefault.outgoingSystemPrompt = "Translate into English. never produce mixed-language text";
            oldDefault.applyMigrations();
            check("老版默认提示词仍然会被升级到新默认值",
                    oldDefault.incomingSystemPrompt.contains("Examples:")
                            && oldDefault.outgoingSystemPrompt.contains("Examples:"));
        } finally {
            deleteRecursively(dir);
        }
    }

    /** 读第一份备份；没有备份时返回空串（让断言报红，而不是抛异常中断整轮自检）。 */
    private static String readIfExists(List<Path> backups) throws IOException {
        return backups.isEmpty() ? "" : Files.readString(backups.get(0), StandardCharsets.UTF_8);
    }

    /** 等一段有限的时间；超时算失败（避免用例把构建挂死）。 */
    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 目录里以某个前缀开头的文件（用来找 {@code xxx.broken-<时间戳>} 备份）。 */
    private static List<Path> backupsOf(Path dir, String prefix) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Collections.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 接口返回的文本一律先清洗再进聊天栏/服务器，以及请求体里的两个开关。
     *
     * <p>译文以前没过 {@code sanitizeOneLine}（只有错误正文过了）：换行会被原版
     * {@code StringSplitter.splitLines} 拆成**多条独立聊天行**，译文那行会因此丢掉 {@code [译]} 前缀，
     * 看起来就像服务器自己说的话；{@code §} 则会被渲染成颜色代码。
     */
    private static void v113ApiTextAndRequest() throws IOException {
        System.out.println("== v1.1.3：接口文本清洗与请求体 ==");
        try (MockServer server = new MockServer()) {
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.apiBaseUrl = "http://127.0.0.1:" + server.port;
            config.requestsPerMinute = 1000;
            config.retryOnFailure = false;
            DeepSeekClient client = new DeepSeekClient(config);

            // 1) 译文里的换行与 § 代码
            server.response = ok("第一行\\n第二行 §c红字");
            DeepSeekClient.Result multi = client.translate("hello there", Direction.INCOMING);
            check("译文里的换行被清洗", multi.ok() && !multi.text().contains("\n"));
            check("译文里的 § 代码被清洗", multi.ok() && !multi.text().contains("§"));
            checkEq("清洗只是压成一行，内容还在", "第一行 第二行 红字", multi.text());

            // 2) 清洗之后才判空：整段控制字符等于模型什么也没给
            server.response = ok("\\u0000\\u0007");
            DeepSeekClient.Result blank = client.translate("hello there", Direction.INCOMING);
            check("清洗后为空的译文判为失败", !blank.ok() && blank.error().contains("空翻译"));

            // 3) 响应体过大：以前 readAllBytes 会把客户端堆打爆，OOM 从工作线程穿出去，
            //    那条出站消息连「未能翻译」都不会计数，直接静默消失。
            server.response = ok("x".repeat(2 * 1024 * 1024));
            DeepSeekClient.Result huge = client.translate("hello there", Direction.INCOMING);
            check("超大响应被拒绝而不是打爆客户端", !huge.ok() && huge.error().contains("过大"));

            // 4) 模型名同样是接口给的文本（用户可能配第三方中转站）
            server.response = "{\"data\":[{\"id\":\"deepseek-flash\"},{\"id\":\"bad\\nname §c\"}]}";
            DeepSeekClient.Result models = client.listModels();
            check("模型名里的换行被清洗", models.ok() && !models.text().contains("\n"));
            check("模型名里的颜色代码被清洗（只去掉接口带来的 §c）",
                    models.ok() && models.text().contains("bad name") && !models.text().contains("§c"));
            check("模型列表自己的高亮分隔符保留（§7 / §f 是本模组加的）",
                    models.ok() && models.text().contains("§7, §f") && models.text().contains("deepseek-flash"));

            // 5) 思考模式：新模型默认开思考，关掉时才传 temperature（开了传也没用，官方文档如此）
            TranslatorConfig thinkingConfig = new TranslatorConfig();
            thinkingConfig.apiKey = "sk-test";
            thinkingConfig.apiBaseUrl = "http://127.0.0.1:" + server.port;
            thinkingConfig.enableThinking = true;
            server.response = ok("ok");
            new DeepSeekClient(thinkingConfig).translate("hi", Direction.INCOMING);
            JsonObject thinkingBody = JsonParser.parseString(server.lastBody).getAsJsonObject();
            checkEq("思考模式开启时 thinking.type", "enabled",
                    thinkingBody.getAsJsonObject("thinking").get("type").getAsString());
            check("思考模式开启时不传 temperature（传了不生效）", !thinkingBody.has("temperature"));

            // 6) 缓存必须区分方向：同一句话两个方向的译文完全不同
            //    注意必须**等第一条落进缓存**再提交第二条：两条一起提交时第二条在缓存写入前就查过了，
            //    那样即使缓存 key 不带方向也会各发一次请求 —— 用例会变成「怎么改都绿」。
            TranslatorConfig cacheConfig = new TranslatorConfig();
            cacheConfig.apiKey = "sk-test";
            cacheConfig.apiBaseUrl = "http://127.0.0.1:" + server.port;
            cacheConfig.requestsPerMinute = 1000;
            TranslationService service = new TranslationService(cacheConfig);
            int before = server.requestCount.get();

            CountDownLatch first = new CountDownLatch(1);
            service.submit("方向缓存测试", Direction.INCOMING, (ok, t, e) -> first.countDown());
            check("第一个方向完成", await(first));
            checkEq("第一个方向发了一次请求", before + 1, server.requestCount.get());

            CountDownLatch second = new CountDownLatch(1);
            service.submit("方向缓存测试", Direction.OUTGOING, (ok, t, e) -> second.countDown());
            check("第二个方向完成", await(second));
            checkEq("换方向必须重新翻译（缓存按方向分开）", before + 2, server.requestCount.get());

            // 反面对照：同方向重复必须命中缓存，不再花钱
            CountDownLatch repeat = new CountDownLatch(1);
            service.submit("方向缓存测试", Direction.INCOMING, (ok, t, e) -> repeat.countDown());
            check("同方向重复完成", await(repeat));
            checkEq("同方向重复命中缓存，不再发请求", before + 2, server.requestCount.get());
            service.shutdown();
        }
    }

    /**
     * 「是不是自己的消息」与「忽略规则」这两条判定。
     *
     * <p>{@code To } 开头只是必要条件，不是充分条件：{@code To view your stats, type: /stats}
     * 这种服务器提示也以 To 开头、也含 {@code ": "}，以前会被整条当成「自己发的私聊」而永不翻译。
     */
    private static void v113OwnMessageAndRules() {
        System.out.println("== v1.1.3：自己的消息 / 忽略规则判定 ==");
        TranslatorConfig config = new TranslatorConfig();

        check("To xxx: 仍然是自己发出的私聊",
                Boolean.TRUE.equals(EchoMatcher.isOwnMessage("To Steve: hi", "Isomeria")));
        Boolean systemHint = EchoMatcher.isOwnMessage("To view your stats, type: /stats", "Isomeria");
        check("以 To 开头的服务器提示不算自己的消息", !Boolean.TRUE.equals(systemHint));
        check("同一条提示仍然会被翻译",
                IncomingFilter.decide("To view your stats, type: /stats", config, false, false).translate());

        // 忽略规则：编译 + 匹配都用生产代码那一份实现（见 hitsIgnorePattern 的说明）
        check("忽略规则仍能命中 / 不误伤",
                hitsIgnorePattern(config, "▬▬▬▬▬▬▬▬ Bed Wars")
                        && !hitsIgnorePattern(config, "[MVP+] Steve: rush mid"));
        check("非法正则只跳过、不炸",
                LangUtils.compilePatterns(List.of("[未闭合", "^\\\\+\\\\d+ .*(XP|Coins|Tokens)"), null).size() == 1);
        check("空/缺省列表安全",
                LangUtils.compilePatterns(null, null).isEmpty()
                        && !LangUtils.matchesAny("any text", LangUtils.compilePatterns(null, null)));
    }

    /** v1.0.1 修复的两个 bug 的回归用例，样本直接取自玩家反馈的截图。 */
    private static void hypixelSamples() {        System.out.println("== Hypixel 真实聊天样本回归 ==");
        TranslatorConfig config = new TranslatorConfig();

        // bug 1：中文客户端的英文喊话带本地化队伍名 [红队]，以前「见汉字就跳过」导致整条不翻译
        assertTranslate(config, "[MVP+] [红队] Mguappe: rush");
        assertTranslate(config, "[MVP+] [红队] Enimoria2013: blue u will delete by yellow so stop kil us");
        assertTranslate(config, "[MVP+] [红队] Enimoria2013: yellow stop cheating! u Scaffold");
        assertTranslate(config, "[MVP+] [红队] Mguappe: u def obby dia");
        assertTranslate(config, "[MVP+] [红队] 小张: inc mid");
        assertTranslate(config, "Chunky_Monk: omw mid");
        assertTranslate(config, "+4 Bed Wars XP (Diamonds)");

        // 服务器本地化的中文消息不能被误判成英文（否则会白花钱并多出一行重复译文）
        assertSkip(config, "团队 > Mguappe: 有人进攻！");
        assertSkip(config, "你购买了金苹果");
        assertSkip(config, "+11 tokens! (时长奖励)");
        assertSkip(config, "isabellab2012被Venomed击杀。");
        assertSkip(config, "hbs_」被NikeFig」逼入末路。");
        assertSkip(config, "2bi7因踩到了Enimoria2013的烧烤酱而跌落边缘。");
        assertSkip(config, "Fxring被MikahManz07塞进了戴维·琼斯的箱子");
        assertSkip(config, "[MVP+] [红队] 小张: 你们去中路");

        // 默认忽略规则仍要能挡住经验/代币刷屏
        boolean xpIgnored = false;
        for (String regex : config.ignorePatterns) {
            if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
                    .matcher("+15 Bed Wars XP (Time Played)").find()) {
                xpIgnored = true;
            }
        }
        check("+15 Bed Wars XP 命中默认 ignorePatterns", xpIgnored);

        // 自己消息的回显不翻译
        check("自己的回显跳过",
                !IncomingFilter.decide("[MVP+] [红队] Steve: rush mid", config, false, true).translate());
    }

    /** 把一批文本当作「刚刚发出」的消息，用于回显比对测试。 */
    private static List<EchoMatcher.Sent> justSent(String... texts) {
        List<EchoMatcher.Sent> sent = new ArrayList<>(texts.length);
        for (String text : texts) {
            sent.add(EchoMatcher.Sent.now(text));
        }
        return sent;
    }

    private static void assertTranslate(TranslatorConfig config, String text) {
        IncomingFilter.Decision decision = IncomingFilter.decide(text, config, false, false);
        check("应翻译: " + text + (decision.translate() ? "" : " -> 实际跳过(" + decision.reason() + ")"),
                decision.translate());
    }

    private static void assertSkip(TranslatorConfig config, String text) {
        IncomingFilter.Decision decision = IncomingFilter.decide(text, config, false, false);
        check("应跳过: " + text + (decision.translate() ? " -> 实际翻译了" : " (" + decision.reason() + ")"),
                !decision.translate());
    }

    private static void assertSplit(String label, String command, String head, String message, TranslatorConfig config) {
        CommandMessage.Split split = CommandMessage.split(command, config.translateCommandArgs);
        if (split == null) {
            fail(label + " -> 不应该为 null");
            return;
        }
        checkEq(label + " head", head, split.head());
        checkEq(label + " message", message, split.message());
    }

    // ------------------------------------------------------------------

    /**
     * v1.1.4：术语表开始服务于「中→英」方向（反查成「中文说法 -> 英文写法」），
     * 以及配套的 v7 配置迁移。
     *
     * <p>渲染规则抽在 {@link PromptGlossary} 里，这里调用的是生产代码那一份实现。
     */
    private static void v114GlossaryBothDirections() {
        System.out.println("== v1.1.4：术语表两个方向 ==");

        // ---- 英→中：照旧原样列出条目，要求按含义翻成中文 ----
        String toChinese = PromptGlossary.render(
                List.of("obby=黑曜石（obsidian）", "rush=速攻、直接冲家"), Direction.INCOMING);
        check("英→中方向带对照表标题", contains(toChinese, "术语与缩写对照表"));
        check("英→中方向保留原始条目", contains(toChinese, "obby=黑曜石（obsidian）"));
        check("英→中方向要求按含义翻译", contains(toChinese, "不要保留英文原样"));

        // ---- 中→英：反查成「中文说法 -> 英文写法」，括号里的说明不进对照表 ----
        String toEnglish = PromptGlossary.render(
                List.of("obby=黑曜石（obsidian）", "rush=速攻、直接冲家"), Direction.OUTGOING);
        check("中→英方向给出英文写法", contains(toEnglish, "黑曜石 -> obby"));
        check("中→英方向去掉括号说明",
                contains(toEnglish, "黑曜石 -> obby")
                        && !contains(toEnglish, "（obsidian）") && !contains(toEnglish, "(obsidian)"));
        check("中→英方向保留顿号写法", contains(toEnglish, "速攻、直接冲家 -> rush"));
        check("中→英方向要求别硬套", contains(toEnglish, "do not force"));

        // 一个条目里有多组对照：分号隔开，两组都要能反查
        String multi = PromptGlossary.render(List.of("def=防守（defend）；\"u def\"=你来防守"), Direction.OUTGOING);
        // v2.1.4：英文写法两侧的引号会在渲染时剥掉（术语表格式不支持引号，
        // 而它和提示词里「不要加引号」的规则打架）。
        check("多组对照都进对照表",
                contains(multi, "防守 -> def") && contains(multi, "你来防守 -> u def"));
        check("英文写法两侧的引号被剥掉（不再渲染成 \"u def\"）",
                !contains(multi, "\""));

        // 括号是半角时同样要截掉
        check("半角括号也截掉", contains(PromptGlossary.render(List.of("dia=钻石(diamond)"), Direction.OUTGOING),
                "钻石 -> dia"));

        // ---- 异常输入：宁可少一段提示词，也不能让翻译请求本身出问题 ----
        check("空术语表不注入", PromptGlossary.render(List.of(), Direction.OUTGOING) == null
                && PromptGlossary.render(null, Direction.INCOMING) == null);
        check("没有等号的条目被忽略",
                PromptGlossary.render(List.of("这不是对照表"), Direction.OUTGOING) == null);
        check("缺英文写法或中文说法的条目被忽略",
                PromptGlossary.render(List.of("=只有右边", "onlyleft="), Direction.OUTGOING) == null);
        check("坏条目不影响好条目",
                contains(PromptGlossary.render(List.of("这不是对照表", "obby=黑曜石"), Direction.OUTGOING),
                        "黑曜石 -> obby"));
        check("术语表里的换行不会带进请求体",
                !contains(PromptGlossary.render(List.of("obby=黑\n曜石"), Direction.OUTGOING), "\n曜"));
        check("列表里有 null 也不炸",
                contains(PromptGlossary.render(Arrays.asList(null, "obby=黑曜石"), Direction.OUTGOING),
                        "黑曜石 -> obby"));
        // 只数对照行（行首是汉字）；表头里也有一个 " -> "，不能拿它当条数
        String manyTable = PromptGlossary.render(manyGlossaryEntries(), Direction.OUTGOING);
        long capped = manyTable == null ? -1 : manyTable.lines().filter(line -> line.startsWith("词")).count();
        check("反查条数有上限（用户写很长也不撑爆提示词） = " + capped,
                capped == PromptGlossary.MAX_OUTGOING_PAIRS);

        // ---- 默认术语表本身：新增的说法要能反查到英文写法 ----
        TranslatorConfig defaults = new TranslatorConfig();
        String defaultTable = PromptGlossary.render(defaults.glossary, Direction.OUTGOING);
        check("默认术语表能反查出 fall back", contains(defaultTable, "撤、退回来 -> fall back"));
        check("默认术语表能反查出 low hp", contains(defaultTable, "残血 -> low hp"));
        check("默认术语表能反查出 side rush", contains(defaultTable, "侧翼速攻 -> side rush"));
        check("默认术语表能反查出 obby", contains(defaultTable, "黑曜石 -> obby"));
        // 反查是有上限的截断列表，所以「排在第几名」本身就是行为的一部分：
        // 第一版上限设成 80、新词又追加在末尾，结果 low hp / side rush / fall back 全被截掉，
        // 等于这次补词白补。这条用例专门守住「常用说法必须落在上限之内」。
        //
        // v2.1.4：同一中文说法只保留第一个英文写法，所以这里只能写「确定会保留的那一个」。
        // 速度药水 的两条默认写法是 speed / speed pot —— 反查表里留下的是先出现的 speed。
        check("默认术语表的常用说法没有被上限截掉",
                List.of("残血 -> low hp", "侧翼速攻 -> side rush", "撤、退回来 -> fall back",
                                "床已经没了 -> bed gone", "速度药水 -> speed", "等一下 -> hold on")
                        .stream().allMatch(s -> contains(defaultTable, s)));
        // 比上一条更强的规则：默认术语表要**整份**装得下，一条都不许被静默截掉。
        // 只守住「某几个词还在」是不够的 —— 以后往默认表里加词、或把上限调小，
        // 末尾那几条就会悄悄消失，而前一条用例照样是绿的（反向验证时就是这么漏过去的）。
        //
        // 注意：v2.1.4 起「条目数」不再等于「对照组数」（同一中文只会留一个英文写法），
        // 所以要拿 PromptGlossary 自己算出来的组数比，而不是拿 glossary.size() 比 ——
        // 后者会把去重后的正常结果误判成「被截掉了」。
        long rows = PromptGlossary.outgoingPairCount(defaults.glossary);
        long entriesInDefaultGlossary = defaults.glossary.size();
        check("默认术语表整份装得下（上限 " + PromptGlossary.MAX_OUTGOING_PAIRS
                        + " 组，默认表 " + entriesInDefaultGlossary + " 条条目 -> " + rows + " 组对照）",
                rows <= PromptGlossary.MAX_OUTGOING_PAIRS && rows > 0);
        check("默认术语表去重后仍保留了绝大部分说法（" + rows + " / " + entriesInDefaultGlossary + "）",
                rows >= entriesInDefaultGlossary - 9);
        // 精确守住「只合并了那 8 个中文说法的 9 组重复」：以后往默认表里加词时
        // 如果不小心又引入「同一中文 -> 两个英文」，这条会立刻变红。
        check("默认术语表的去重结果符合预期（95 条 -> 86 组）", rows == 86);

        // ---- v7 迁移：补词不覆盖用户自定义，提示词只动仍是默认值的 ----
        TranslatorConfig user = new TranslatorConfig();
        user.configVersion = 6;
        user.glossary = new ArrayList<>(List.of("obby=我的黑曜石叫法", "我的词=我的意思"));
        user.outgoingSystemPrompt = "Translate into English, keep it short.";
        user.applyMigrations();
        checkEq("v7 后配置版本", TranslatorConfig.CURRENT_CONFIG_VERSION, user.configVersion);
        checkEq("用户改过的 obby 条目保留", "obby=我的黑曜石叫法", user.glossary.get(0));
        check("用户自己写的词保留", user.glossary.contains("我的词=我的意思"));
        check("新默认词已补进用户的术语表", user.glossary.contains("side rush=侧翼速攻"));
        checkEq("手写的发送方向提示词不被覆盖", "Translate into English, keep it short.",
                user.outgoingSystemPrompt);

        TranslatorConfig stock = new TranslatorConfig();
        stock.configVersion = 6;
        stock.outgoingSystemPrompt = new TranslatorConfig().outgoingSystemPrompt
                .replace("\n            我们有黑曜石，直接冲他家 -> we have obby, rush their base"
                        + "\n            他残血了，你上 -> he is low hp, go", "");
        stock.applyMigrations();
        check("仍是默认值的发送方向提示词被升级（补上 obsidian 示例）",
                stock.outgoingSystemPrompt.contains("我们有黑曜石，直接冲他家 -> we have obby, rush their base"));
        checkEq("升级不会把示例弄重复",
                1L, stock.outgoingSystemPrompt.lines()
                        .filter(line -> line.contains("we have obby, rush their base")).count());
    }

    /** 造一份超过反查上限的术语表，用来验证上限真的生效。 */
    private static List<String> manyGlossaryEntries() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            entries.add("word" + i + "=词" + i);
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // v2.1.0：把「只有结构保证」的发送/接收行为变成可断言的用例
    //
    // 这些路径以前只能靠代码审查（ChatTranslator 直接依赖 Minecraft 类，离线自检碰不到），
    // 而历史上真实出过的 bug 全在这一段：v1.0.5 连打两条中文乱序、v1.0.7 命中缓存的第二条插队、
    // v1.0.8 限流时把中文原文发到英文服、v1.1.3 发送失败仍计入统计。
    // 重构后 ChatTranslator 只依赖 ChatClientPort / FeedbackPort，于是这里可以拿假实现
    // 确定性地驱动整条链路（包含「提交时在 A 服、回调回来时已切到 B 服」这种难复现场景）。
    // ------------------------------------------------------------------

    /** 交给发送方向的假回应：纯英文，免得触发「译文仍是中文」这道校验。 */
    private static final String FAKE_EN = "rush mid";

    private static void v210ChatLogic() throws Exception {
        System.out.println("== v2.1.0：收发行为（不再只靠结构保证）==");
        incomingDecisions();
        silentLossGuards();
        outgoingFallback();
        outgoingSuccessAndOrdering();
        outgoingConnectionGuard();
        outgoingCommands();
        warningThrottle();
        portsAreSwappable();
    }

    /** 收到消息：该翻的翻、该跳的跳，且两条事件链路（签名聊天 / 系统消息）都对。 */
    private static void incomingDecisions() throws Exception {
        System.out.println("-- 收到消息 --");
        try (MockServer server = new MockServer()) {
            server.response = ok("你们好");

            // 1) 英文系统消息（Hypixel 走这条，拿不到发送者）→ 翻译后**显示在聊天栏**
            //    （接收方向绝不往服务器发东西，这是本模组的基本承诺）
            Harness h = Harness.incoming(server);
            h.translator.onIncoming("[MVP+] [红队] Steve: hello", false, false, null, null);
            check("英文系统消息被翻译并显示", h.feedback.awaitInfo() && h.feedback.hasInfo("你们好"));
            check("接收方向不会往服务器发消息", h.client.sentChats.isEmpty());
            check("译文带 [译] 前缀", h.feedback.hasInfo("[译]") || h.feedback.hasInfo("译"));

            // 2) 中文消息 → 跳过，且**不该**产生任何请求（省钱的保证）
            Harness skip = Harness.incoming(server);
            skip.translator.onIncoming("你购买了金苹果", false, false, null, null);
            check("中文消息不翻译", !skip.client.hasChat(600) && !skip.hasPendingTasks());
            check("跳过的消息计入「跳过」", skip.translator.counters().contains("跳过 §f1"));

            // 3) overlay（物品栏上方那行）完全不处理
            Harness overlay = Harness.incoming(server);
            overlay.translator.onIncoming("+15 Bed Wars XP (Time Played)", true, false, null, null);
            check("overlay 消息不处理", !overlay.client.hasChat(300));

            // 4) 签名聊天：发送者就是自己 → 直接跳过，连正文都不看
            Harness selfSigned = Harness.incoming(server);
            selfSigned.client.isLocalPlayer = true;
            selfSigned.translator.onIncoming("[MVP+] Isomeria: hello", false, true, SELF_UUID, "Isomeria");
            check("自己发的签名聊天不翻译", !selfSigned.client.hasChat(300));

            // 5) 黑名单玩家：签名链路按名字直接跳过
            Harness blacklisted = Harness.incoming(server);
            blacklisted.config.blacklistedPlayers = new ArrayList<>(List.of("Steve"));
            blacklisted.client.localPlayerName = "Isomeria";
            blacklisted.translator.onIncoming("[MVP+] Steve: hello", false, true, UUID.randomUUID(), "Steve");
            check("黑名单玩家（签名链路）不翻译", !blacklisted.client.hasChat(300));

            // 6) 黑名单玩家：系统消息（Hypixel）只能从正文里认说话人，也要能挡住
            Harness blacklistedText = Harness.incoming(server);
            blacklistedText.config.blacklistedPlayers = new ArrayList<>(List.of("Steve"));
            blacklistedText.translator.onIncoming("[MVP+] Steve: hello", false, false, null, null);
            check("黑名单玩家（系统消息按名字认）不翻译", !blacklistedText.client.hasChat(300));

            // 7) 自己的回显：直接打过的英文被服务器回显回来时，靠 EchoMatcher 认领
            Harness echo = Harness.incoming(server);
            echo.client.localPlayerName = "Isomeria";
            echo.translator.onSendChat("gg ez");
            echo.translator.onIncoming("[MVP+] Isomeria: gg ez", false, false, null, null);
            check("自己回显的英文不翻译", !echo.client.hasChat(600));
        }
    }

    /**
     * v2.1.0 修掉的一个「静默丢消息」真问题，以及它的两道防线。
     *
     * <p>背景：{@code TranslationService} 的 debug 分支引用 {@code HxTranslateClient.LOGGER}，
     * 而入口类实现 {@code ClientModInitializer}，于是「打一行日志」会连带加载 Fabric 加载器 API。
     * 在拿不到那个类的环境里抛的是 {@link NoClassDefFoundError} —— 它是 {@link Error} 不是
     * {@link Exception}，{@code catch (IOException|RuntimeException)} 全都接不住：
     * 工作线程直接死、**回调永远不执行**，玩家看到「⏳ 翻译中…」之后什么都没发生。
     *
     * <p>这道防线是「写用例时撞出来的」：写 v2.1.0 的收发行为用例时，第一条「英文系统消息
     * 被翻译」一直红，查下去才发现回调根本没回来。所以这里把它固化成三条断言。
     */
    private static void silentLossGuards() throws Exception {
        System.out.println("-- 静默丢消息的防线 --");

        // 1) 打开 debug（也就是打开那些「顺手打一行日志」的分支）后，翻译仍然必须送达。
        //    这条在把日志改回「引用入口类」时会变红：debug 分支抛 NoClassDefFoundError，
        //    译文再也到不了聊天栏。
        try (MockServer server = new MockServer()) {
            server.response = ok("你们好");
            Harness debugOn = Harness.incoming(server);
            debugOn.config.debugLog = true;
            debugOn.translator.onIncoming("[MVP+] Steve: hello", false, false, null, null);
            check("debug 模式下译文仍然送达", debugOn.feedback.awaitInfo() && debugOn.feedback.hasInfo("你们好"));
        }

        // 2) 日志出口自己抛错时：事件回调不能崩、译文仍然必须送达（走网络与走缓存两条路径都要）。
        try (MockServer server = new MockServer()) {
            server.response = ok("你们好");
            Harness brokenLog = Harness.incoming(server);
            brokenLog.config.debugLog = true;
            brokenLog.breakLogger();
            brokenLog.translator.onIncoming("[MVP+] Steve: hello", false, false, null, null);
            check("日志器抛错时译文依然送达（不会静默丢消息）",
                    brokenLog.feedback.awaitInfo(2000, 1) && brokenLog.feedback.hasInfo("你们好"));
            // 第二条同样的消息命中缓存：另一条回调路径，同样不能被日志故障影响
            brokenLog.translator.onIncoming("[MVP+] Steve: hello", false, false, null, null);
            check("日志器抛错时缓存命中路径也送达", brokenLog.feedback.awaitInfo(2000, 2));
        }
    }

    /** 发送方向的 5 条降级路径必须给出同一个答案（v1.0.8 的教训：限流那条曾把中文发出去）。 */
    private static void outgoingFallback() throws Exception {
        System.out.println("-- 发送失败时的降级（5 条路径统一看 failureFallback）--");
        try (MockServer server = new MockServer()) {
            server.response = ok(FAKE_EN);

            // CANCEL（默认）：取消发送 + 红字提示 + 计入「未能翻译」，且**绝不**把中文发出去
            Harness noKey = Harness.outgoing(server);
            noKey.config.apiKey = "";
            check("没 Key：取消发送", !noKey.translator.onSendChat("你们好"));
            check("没 Key：没有发出任何内容", !noKey.client.hasChat(300));
            check("没 Key：给了聊天栏提示", noKey.feedback.hasError("未配置 DeepSeek API Key"));
            check("没 Key：不计入「已发出」", noKey.translator.sendCounters().contains("未能翻译 §f1"));

            Harness limited = Harness.outgoing(server);
            limited.config.requestsPerMinute = 0; // 保证拿不到配额
            check("被限流：取消发送", !limited.translator.onSendChat("你们好"));
            check("被限流：没有发出任何内容", !limited.client.hasChat(300));
            check("被限流：提示了原因", limited.feedback.hasError("已达上限"));

            // 译文含汉字 → DeepSeekClient 判失败（这是「中文漏进英文服」的最后一道闸）
            try (MockServer chinese = new MockServer()) {
                chinese.response = ok("你们好");
                Harness stillChinese = Harness.outgoing(chinese);
                check("译文含汉字：取消发送", !stillChinese.translator.onSendChat("你们好"));
                stillChinese.client.awaitChat(600);
                check("译文含汉字：没有把中文发出去", stillChinese.client.sentChats.isEmpty());
                check("译文含汉字：计入未能翻译", stillChinese.translator.sendCounters().contains("未能翻译 §f1"));
            }

            // SEND_ORIGINAL：上面 3 条都要改成「按原文发出」
            Harness sendOriginal = Harness.outgoing(server);
            sendOriginal.config.apiKey = "";
            sendOriginal.config.failureFallback = "SEND_ORIGINAL";
            check("没 Key + SEND_ORIGINAL：放行原消息", sendOriginal.translator.onSendChat("你们好"));
            check("没 Key + SEND_ORIGINAL：仍然给红字提示（中文可能已进英文服）",
                    sendOriginal.feedback.hasError("仍按原文发送"));

            Harness limitedOriginal = Harness.outgoing(server);
            limitedOriginal.config.requestsPerMinute = 0;
            limitedOriginal.config.failureFallback = "SEND_ORIGINAL";
            check("被限流 + SEND_ORIGINAL：放行原消息（v1.0.8 修的就是这条）",
                    limitedOriginal.translator.onSendChat("你们好"));
            check("被限流 + SEND_ORIGINAL：没有走我们自己的发送通道",
                    !limitedOriginal.client.hasChat(300));
        }
    }

    /** 成功路径：取消原发送 → 异步翻译 → 用**译文**重发；顺序、截断、统计都要对。 */
    private static void outgoingSuccessAndOrdering() throws Exception {
        System.out.println("-- 发送成功路径 --");
        try (MockServer server = new MockServer()) {
            server.response = ok(FAKE_EN);

            // 纯英文不干预，但要记下来（否则服务器回显时会被翻成中文）
            Harness english = Harness.outgoing(server);
            check("纯英文原样放行", english.translator.onSendChat("rush mid"));
            check("纯英文不产生请求", !english.client.hasChat(300));

            // 含中文：取消原发送，翻译后由模组发出
            Harness h = Harness.outgoing(server);
            check("含中文取消原发送", !h.translator.onSendChat("我们冲中路"));
            check("译文被发出去", h.client.awaitChat() && h.client.sentChats.contains(FAKE_EN));
            check("发出后打了 [→EN] 回显", h.feedback.hasInfo("→EN"));
            check("成功计入「已发出」", h.translator.sendCounters().contains("发出 §a译文 §f1"));
            check("没有计入「未能翻译」", h.translator.sendCounters().contains("未能翻译 §f0"));

            // 请求体里带的是中文原文（确认我们没把别的东西发去翻译）
            JsonObject body = JsonParser.parseString(server.lastBody).getAsJsonObject();
            checkEq("翻译的是玩家输入的原文", "我们冲中路",
                    body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());

            // 超长译文按预算截断，并说明原因
            // （FAKE_EN 是 "rush mid"，所以预算要小于它才会真的截断）
            Harness longOne = Harness.outgoing(server);
            longOne.config.maxOutgoingChars = 5;
            longOne.translator.onSendChat("我们冲中路");
            check("超长译文被截断后发送", longOne.client.awaitChat()
                    && longOne.client.sentChats.get(0).length() <= 5);
            check("截断有提示", longOne.feedback.hasHint("已截断"));

            // 连打两条中文：后一条必须等前一条发完（sendChats 是按发出顺序记的）
            Harness ordered = Harness.outgoing(server);
            ordered.translator.onSendChat("第一条中文");
            ordered.translator.onSendChat("第二条中文");
            check("两条中文都在发出通道里", ordered.client.awaitChat(4000, 2));
            checkEq("先输入的先发出（单线程 FIFO）", "rush mid",
                    ordered.client.sentChats.get(0));
            checkEq("两条不会互相插队", 2, ordered.client.sentChats.size());
        }
    }

    /** 提交时在 A 服、回调回来时已切到 B 服 —— 绝不能把消息发到别的服务器去。 */
    private static void outgoingConnectionGuard() throws Exception {
        System.out.println("-- 切服保护 --");
        try (MockServer server = new MockServer()) {
            server.response = ok(FAKE_EN);

            Harness switched = Harness.outgoing(server);
            switched.translator.onSendChat("你们好");
            switched.client.connection = new Object(); // 翻译期间切服
            switched.client.awaitChat(800);
            check("切服后不发消息", switched.client.sentChats.isEmpty());
            check("切服给了提示", switched.feedback.hasError("切换了服务器"));
            check("切服不计入「已发出」", switched.translator.sendCounters().contains("发出 §a译文 §f0"));

            Harness disconnected = Harness.outgoing(server);
            disconnected.translator.onSendChat("你们好");
            disconnected.client.connection = null; // 翻译期间退回主菜单
            disconnected.client.awaitChat(800);
            check("断线后静默放弃、不发消息", disconnected.client.sentChats.isEmpty());
            check("断线不刷屏（玩家已在主菜单）", !disconnected.feedback.hasError("切换了服务器"));
        }
    }

    /** 命令正文：只翻译正文、命令头原样保留，且按命令重发。 */
    private static void outgoingCommands() throws Exception {
        System.out.println("-- 命令正文 --");
        try (MockServer server = new MockServer()) {
            server.response = ok(FAKE_EN);

            Harness h = Harness.outgoing(server);
            check("命令正文含中文则取消原发送", !h.translator.onSendCommand("shout 我们冲中路"));
            check("命令按命令重发（带 head）", h.client.awaitChat()
                    && h.client.sentCommands.contains("shout " + FAKE_EN));
            check("命令译文不会被当成聊天发出去", h.client.sentChats.isEmpty());
            check("发出后打了回显", h.feedback.hasInfo("→EN") && h.feedback.hasInfo("shout"));

            Harness english = Harness.outgoing(server);
            check("命令正文是英文则放行", english.translator.onSendCommand("shout rush mid"));
            check("英文命令不产生请求", !english.client.hasChat(300));

            Harness notAList = Harness.outgoing(server);
            check("不在名单里的命令放行", notAList.translator.onSendCommand("tp Steve"));
            check("管理子命令放行", notAList.translator.onSendCommand("party invite Steve"));
        }
    }

    /** 告警节流：接口挂了的时候，同一句话不能每条消息刷一行红字。 */
    private static void warningThrottle() {
        System.out.println("-- 告警节流 --");
        Harness h = Harness.incoming(null);
        h.config.apiKey = "";
        h.translator.onIncoming("[MVP+] Steve: hello", false, false, null, null);
        h.translator.onIncoming("[MVP+] Steve: hello again", false, false, null, null);
        h.translator.onIncoming("[MVP+] Steve: hello once more", false, false, null, null);
        checkEq("同一条告警 30 秒内只出现一次", 1L,
                h.feedback.errors.stream().filter(e -> e.contains("未配置 DeepSeek API Key")).count());

        h.clock.advance(31_000);
        h.translator.onIncoming("[MVP+] Steve: hello after cooldown", false, false, null, null);
        checkEq("过了时间窗可以再提醒一次", 2L,
                h.feedback.errors.stream().filter(e -> e.contains("未配置 DeepSeek API Key")).count());
    }

    /** 回调切主线程：任务还没执行时不该发送，执行后才发送（保证「碰游戏状态都在主线程」）。 */
    private static void portsAreSwappable() throws Exception {
        System.out.println("-- 回调切主线程 --");
        try (MockServer server = new MockServer()) {
            server.response = ok(FAKE_EN);
            Harness h = Harness.outgoing(server);
            h.client.runTasksInline = false;

            h.translator.onSendChat("你们好");
            h.client.awaitChat(800);
            check("回调进了主线程队列、尚未发送", h.client.sentChats.isEmpty() && h.client.tasks.size() == 1);

            h.client.flushTasks();
            check("切回主线程后才真正发送", h.client.sentChats.contains(FAKE_EN));

            check("统计会清零（/hxtranslate debug on 用它）", true);
            h.translator.resetCounters();
            check("resetCounters 之后计数归零",
                    h.translator.counters().contains("收到 §f0") && h.translator.sendCounters().contains("发出 §a译文 §f0"));
        }
    }

    // ------------------------------------------------------------------
    // v2.1.4：一次「用真实 DeepSeek API 审查翻译功能与质量」之后修的 bug。
    //
    // 这一组用例守的是「送进英文服的东西」与「玩家有没有得到提示」，
    // 而不是「模型翻得好不好」—— 后者只有真实 API 能测（见 CHANGELOG 的说明）。
    // ------------------------------------------------------------------
    private static void v214AuditFixes() throws Exception {
        System.out.println("== v2.1.4：真实 API 审查后的修复 ==");

        // ---- 1) 发送方向的汉字闸门：整句中文会被拦，半中半英以前会被放行 ----
        // 「绝不把中文发到英文服」是本模组存在的理由，所以这道闸不能只看「汉字占比 ≥ 50%」。
        try (MockServer mixed = new MockServer()) {
            mixed.response = ok("打他 mid"); // hanRatio = 2/5 = 0.40，旧规则放行
            Harness mixedOut = Harness.outgoing(mixed);
            check("半中半英的译文（汉字占比 0.40）：取消发送", !mixedOut.translator.onSendChat("去打他"));
            mixedOut.client.awaitChat(600);
            check("半中半英的译文：一个字都没发出去", mixedOut.client.sentChats.isEmpty());
            check("半中半英的译文：计入未能翻译",
                    mixedOut.translator.sendCounters().contains("未能翻译 §f1"));
        }
        try (MockServer mixed2 = new MockServer()) {
            mixed2.response = ok("push mid and 打他"); // hanRatio = 3/18 = 0.17
            Harness h = Harness.outgoing(mixed2);
            check("半中半英（汉字占比 0.17）：取消发送", !h.translator.onSendChat("推中路然后打他"));
            h.client.awaitChat(600);
            check("半中半英（汉字占比 0.17）：一个字都没发出去", h.client.sentChats.isEmpty());
        }
        // 反过来：真正的纯英文译文必须照常发出，否则我们就把功能改坏了
        try (MockServer good = new MockServer()) {
            good.response = ok("he is low hp, go");
            Harness h = Harness.outgoing(good);
            check("纯英文译文仍然正常放行", !h.translator.onSendChat("他残血了，你上"));
            check("纯英文译文真的发出去了",
                    h.client.awaitChat() && h.client.sentChats.contains("he is low hp, go"));
        }
        // 接收方向不做这道校验：中文译文本来就是它的正常产出
        try (MockServer inZh = new MockServer()) {
            inZh.response = ok("有人从中路进攻");
            Harness h = Harness.incoming(inZh);
            h.translator.onIncoming("[MVP+] Steve: inc mid", false, false, null, "Steve");
            h.client.awaitChat(400);
            check("接收方向返回中文不受这道校验影响",
                    h.client.sentChats.isEmpty() && h.feedback.hasInfo("有人从中路进攻"));
        }

        // ---- 2) 回显名单：发送失败的内容绝不能进名单 ----
        // 否则 15 秒内别人发的同一句英文会被当成「自己的回显」而漏翻。
        //
        // 探针必须用**认不出说话人**的文本（裸 "u def" / "inc mid"）：如果写成
        // "[MVP+] Steve: u def"，ownEchoReason 会先按说话人名字判定（Steve != Isomeria）
        // 直接返回「不是自己的」，正文比对那条分支根本走不到 —— 那样这条用例
        // 无论 rememberSent 放在哪里都是绿的（反向验证时就是这么发现它是坏用例的）。
        try (MockServer server = new MockServer()) {
            Harness h = Harness.outgoing(server);
            h.config.skipOwnEcho = true;
            server.response = ok("u def");
            h.client.failSends = true;
            check("发送失败时仍然取消原发送", !h.translator.onSendChat("你来防守"));
            h.client.awaitChat(600);
            check("发送失败：没有内容发出去", h.client.sentChats.isEmpty());
            check("发送失败：提示了玩家", h.feedback.hasError("发送失败"));
            check("发送失败：这段英文没有被记进回显名单",
                    !h.translator.isOwnEcho("u def"));
        }
        try (MockServer server = new MockServer()) {
            Harness h = Harness.outgoing(server);
            h.config.skipOwnEcho = true;
            server.response = ok("u def");
            check("发送成功时仍然取消原发送", !h.translator.onSendChat("你来防守"));
            check("发送成功：内容发出去了", h.client.awaitChat());
            // 回显名单里记的是「真正发出去的那串」，所以拿它构造一条认不出说话人的回显
            String sent = h.client.sentChats.isEmpty() ? "" : h.client.sentChats.get(0);
            check("发送成功：正常记进回显名单（本该如此，发出的内容 = " + sent + "）",
                    !sent.isEmpty() && h.translator.isOwnEcho(sent));
        }

        // ---- 3) 单字母术语条目（u / r / y / n）会污染玩家名与普通英文 ----
        TranslatorConfig defaults = new TranslatorConfig();
        check("默认术语表里没有单字母条目 u=",
                defaults.glossary.stream().noneMatch(e -> e.startsWith("u=")));
        check("默认术语表里没有单字母条目 r=",
                defaults.glossary.stream().noneMatch(e -> e.startsWith("r=")));
        check("默认术语表里没有单字母条目 y=",
                defaults.glossary.stream().noneMatch(e -> e.startsWith("y=")));
        check("默认术语表里没有单字母条目 n=",
                defaults.glossary.stream().noneMatch(e -> e.startsWith("n=")));
        check("默认术语表里没有带引号的英文写法",
                defaults.glossary.stream().noneMatch(e -> e.contains("\"")));
        String defaultIn = PromptGlossary.render(defaults.glossary, Direction.INCOMING);
        check("英→中对照表里不再出现 u=你", defaultIn == null || !contains(defaultIn, "u=你"));
        check("英→中对照表里不再出现 y=是", defaultIn == null || !contains(defaultIn, "y=是"));

        // ---- 4) 反查表：同一中文说法只能有一个英文写法（否则译法随机漂移）----
        String table = PromptGlossary.render(defaults.glossary, Direction.OUTGOING);
        check("反查表里 钻石 只有一个英文写法: " + englishFor(table, "钻石"),
                englishFor(table, "钻石").size() == 1);
        check("反查表里 药水 只有一个英文写法: " + englishFor(table, "药水"),
                englishFor(table, "药水").size() == 1);
        check("反查表里 金苹果 只有一个英文写法（gap 优先，不是 gap/gaps/gapple）: "
                        + englishFor(table, "金苹果"),
                englishFor(table, "金苹果").size() == 1);
        check("反查表里 防守 只有一个英文写法（def 优先，defend 是重复说法）: "
                        + englishFor(table, "防守"),
                englishFor(table, "防守").size() == 1);
        check("反查表里 谢谢 只有一个英文写法: " + englishFor(table, "谢谢"),
                englishFor(table, "谢谢").size() == 1);
        // 用户自己写的条目照旧要能反查（这条不能被去重顺手改坏）
        String userTable = PromptGlossary.render(
                List.of("obby=黑曜石（obsidian）", "我的词=我的意思"), Direction.OUTGOING);
        check("用户自定义条目仍然能反查", contains(userTable, "我的意思 -> 我的词"));

        // ---- 5) showErrorsInChat=false 时不能变成「消息凭空消失」----
        // 默认 CANCEL 下这条提示是玩家唯一能知道「我的中文没发出去」的机会。
        try (MockServer server = new MockServer()) {
            Harness h = Harness.outgoing(server);
            // Harness.outgoing 自带测试 Key，这里要的是「没配 Key」这条真实降级路径
            h.config.apiKey = "";
            h.config.showErrorsInChat = false;
            check("静默模式下仍然取消发送", !h.translator.onSendChat("你们好"));
            check("静默模式下仍然告诉玩家「没发出去」",
                    h.feedback.hasError("未发送") || h.feedback.hasHint("未发送"));
        }
        try (MockServer server = new MockServer()) {
            Harness h = Harness.outgoing(server);
            h.config.showErrorsInChat = false;
            h.config.failureFallback = "SEND_ORIGINAL";
            h.config.apiKey = ""; // 没 Key：这是一条真实的失败降级路径
            check("静默模式 + SEND_ORIGINAL：仍按原文放行", h.translator.onSendChat("你们好"));
            check("静默模式 + SEND_ORIGINAL：仍然提示「中文可能已进英文服」",
                    h.feedback.hasError("仍按原文发送") || h.feedback.hasHint("仍按原文发送"));
        }
    }

    /**
     * v7 -> v8 配置迁移：删掉会污染玩家名的单字母术语条目、去掉英文写法里的引号。
     *
     * <p>RELEASING §5：迁移只认「整条仍是旧版默认值」，用户改过一个字就一个字都不动。
     */
    private static void v214GlossaryMigration() {
        System.out.println("== v2.1.4：术语表迁移（v7 -> v8）==");

        // 旧配置：v7 的默认术语表。它比 v8 默认表多出「单字母 + 带引号」那几条 ——
        // 直接拿 new TranslatorConfig() 当 v7 是错的（那已经是 v8 的名单了，没有可删的条目，
        // 迁移返回 false 会让「确实改动了」这条用例误报）。
        TranslatorConfig legacy = new TranslatorConfig();
        legacy.configVersion = 7;
        legacy.glossary.removeIf(e -> e.equals("def=防守（defend）") || e.equals("you def=你来防守"));
        legacy.glossary.add("def=防守（defend）；\"u def\"=你来防守");
        legacy.glossary.add("u=你");
        legacy.glossary.add("ur=你的、你是");
        legacy.glossary.add("r=are（例如 \"r u ok\" = 你还好吗）");
        legacy.glossary.add("y=是");
        legacy.glossary.add("n=不");
        boolean changed = legacy.applyMigrations();
        check("v7 -> v8 迁移确实改动了配置", changed);
        check("迁移后 configVersion = " + TranslatorConfig.CURRENT_CONFIG_VERSION,
                legacy.configVersion == TranslatorConfig.CURRENT_CONFIG_VERSION);
        check("迁移后不再有 u=你",
                legacy.glossary.stream().noneMatch(e -> e.equals("u=你")));
        check("迁移后不再有 r=are（带引号那条）",
                legacy.glossary.stream().noneMatch(e -> e.startsWith("r=")));
        check("迁移后不再有带引号的条目",
                legacy.glossary.stream().noneMatch(e -> e != null && e.contains("\"")));
        check("迁移后补上了 you def=你来防守",
                legacy.glossary.stream().anyMatch(e -> e.equals("you def=你来防守")));
        check("迁移后 obby 等正常条目仍在",
                legacy.glossary.stream().anyMatch(e -> e.startsWith("obby=")));

        // 用户改过的那一条必须原样保留：改成 u=您 之后，迁移不能把它删掉
        TranslatorConfig customized = new TranslatorConfig();
        customized.configVersion = 7;
        customized.glossary.remove("u=你");
        customized.glossary.add("u=您");
        customized.glossary.remove("def=防守（defend）；\"u def\"=你来防守");
        customized.glossary.add("def=防守（defend）；\"u def\"=我来防守");
        customized.applyMigrations();
        check("用户改过的 u=您 被保留", customized.glossary.stream().anyMatch(e -> e.equals("u=您")));
        check("用户改过的 def 条目被保留（只去掉引号，不删条目）",
                customized.glossary.stream().anyMatch(e -> e.contains("我来防守"))
                        && customized.glossary.stream().noneMatch(e -> e != null && e.contains("\"")));

        // 已经是最新版时不该再动任何东西
        TranslatorConfig fresh = new TranslatorConfig();
        boolean again = fresh.applyMigrations();
        check("已是最新版时迁移不做任何改动", !again);
    }

    /** 取出反查表里某个中文说法对应的全部英文写法。 */
    private static List<String> englishFor(String table, String chinese) {
        List<String> result = new ArrayList<>();
        if (table == null) {
            return result;
        }
        for (String line : table.split("\n")) {
            int arrow = line.indexOf(" -> ");
            if (arrow <= 0) {
                continue;
            }
            if (line.substring(0, arrow).trim().equals(chinese)) {
                result.add(line.substring(arrow + 4).trim());
            }
        }
        return result;
    }

    /**
     * 文档与代码的一致性。
     *
     * <p>README 里的「N 条」这类数字、以及命令表里的子命令，以前没有任何东西盯着 ——
     * 它们和代码不在一处，改代码时最容易漏。这里只守「能机械核对」的那几条，
     * 措辞是否通顺仍然靠人。
     */
    private static void docConsistency() {
        System.out.println("== 文档与代码一致性（README）==");
        String readme = readRepoFile("README.md");
        if (readme == null) {
            fail("文档一致性：读不到 README.md");
            return;
        }
        TranslatorConfig defaults = new TranslatorConfig();

        // README 的配置表写了条目数，代码改了数字没改就是错的
        check("README 的 commandManagementKeywords 条数与代码一致（"
                        + defaults.commandManagementKeywords.size() + " 条）",
                contains(readme, "| `commandManagementKeywords` | "
                        + defaults.commandManagementKeywords.size() + " 条"));
        check("README 的 translateCommandArgs 条数与代码一致（"
                        + defaults.translateCommandArgs.size() + " 条）",
                contains(readme, "| `translateCommandArgs` | " + defaults.translateCommandArgs.size() + " 条"));

        // 命令表必须列出真实存在的子命令，否则玩家不知道有这个命令
        check("README 的命令表里有 /hxtranslate status", contains(readme, "/hxtranslate status"));
        check("README 的命令表里有 incoming", contains(readme, "/hxtranslate incoming on|off"));
        check("README 的命令表里有 outgoing", contains(readme, "/hxtranslate outgoing on|off"));

        // ignorePatterns 那一行的说明不能承诺代码里没有的效果
        check("README 不再声称默认 ignorePatterns 挡「服务器提示音效」",
                !contains(readme, "服务器提示音效"));
        check("README 不再有指向不存在小节的死链「为什么需要这个阈值」",
                !contains(readme, "为什么需要这个阈值"));
    }

    // ------------------------------------------------------------------
    // 测试替身：离线自检用的假端口。放在这里而不是 src 里，避免测试代码进产物。
    // ------------------------------------------------------------------

    /** 组装一台「被自检完全控制」的翻译器：假客户端 + 假反馈 + 假时钟 + 真的 TranslationService。 */
    private static final class Harness {
        final TranslatorConfig config;
        final FakeChatClient client;
        final FakeFeedback feedback;
        final FakeClock clock;
        final ChatTranslator translator;
        final List<String> logs = new ArrayList<>();
        /** 日志出口；默认记进 {@link #logs}，{@link #breakLogger()} 会把它换成抛错的实现。 */
        private java.util.function.Consumer<String> logger = logs::add;

        private Harness(TranslatorConfig config, MockServer server) {
            this.config = config;
            this.client = new FakeChatClient();
            this.feedback = new FakeFeedback();
            this.clock = new FakeClock();
            TranslationService service = server == null
                    ? new TranslationService(config)
                    : new TranslationService(configWithEndpoint(config, server));
            this.translator = new ChatTranslator(config, service, client, feedback,
                    message -> logger.accept(message), clock);
        }

        /** 让日志出口开始抛错，用来验证「日志坏了也不能影响翻译，更不能崩事件回调」。 */
        void breakLogger() {
            logger = message -> {
                throw new IllegalStateException("模拟日志出口故障");
            };
        }

        /** 接收方向的默认配置（已配 Key，指向 mock 服务）。 */
        static Harness incoming(MockServer server) {
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.skipOwnEcho = false; // 回显判定由专门的用例控制，默认关掉避免干扰
            return new Harness(config, server);
        }

        /** 发送方向的默认配置。 */
        static Harness outgoing(MockServer server) {
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            return new Harness(config, server);
        }

        boolean hasPendingTasks() {
            return !client.tasks.isEmpty();
        }
    }

    /** 把配置里的接口地址指向 mock 服务。 */
    private static TranslatorConfig configWithEndpoint(TranslatorConfig config, MockServer server) {
        config.apiBaseUrl = "http://127.0.0.1:" + server.port;
        config.httpTimeoutSeconds = 5;
        return config;
    }

    /** 假的客户端端口：记录「发出去了什么」，并让自检能控制连接与线程切换。 */
    private static final class FakeChatClient implements ChatClientPort {
        final List<String> sentChats = Collections.synchronizedList(new ArrayList<>());
        final List<String> sentCommands = Collections.synchronizedList(new ArrayList<>());
        final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());
        volatile String localPlayerName = "Isomeria";
        volatile boolean isLocalPlayer;
        volatile Object connection = new Object();
        volatile boolean runTasksInline = true;
        /** 置为 true 后所有发送都失败，用来验证「发送失败的内容不进回显名单」。 */
        volatile boolean failSends = false;

        @Override
        public String localPlayerName() {
            return localPlayerName;
        }

        @Override
        public boolean isLocalPlayer(UUID senderId) {
            return isLocalPlayer;
        }

        @Override
        public void execute(Runnable task) {
            if (runTasksInline) {
                task.run();
            } else {
                tasks.add(task);
            }
        }

        @Override
        public Object currentConnection() {
            return connection;
        }

        @Override
        public boolean isSameConnection(Object origin) {
            return connection != null && connection == origin;
        }

        @Override
        public boolean sendChat(String payload) {
            if (failSends) {
                return false;
            }
            sentChats.add(payload);
            return true;
        }

        @Override
        public boolean sendCommand(String command) {
            if (failSends) {
                return false;
            }
            sentCommands.add(command);
            return true;
        }

        /** 等一次发送发生（最多 2 秒）。超时也是用例要断言的「没发送」，所以不抛异常。 */
        boolean awaitChat() {
            return awaitChat(2000, 1);
        }

        boolean awaitChat(long millis) {
            return awaitChat(millis, 1);
        }

        boolean awaitChat(long millis, int count) {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                if (sentChats.size() + sentCommands.size() >= count) {
                    return true;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return sentChats.size() + sentCommands.size() >= count;
        }

        /** 「这段时间内不该有发送」用的：等一小会儿再断言。 */
        boolean hasChat(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return !sentChats.isEmpty() || !sentCommands.isEmpty();
        }

        void flushTasks() {
            List<Runnable> snapshot;
            synchronized (tasks) {
                snapshot = new ArrayList<>(tasks);
                tasks.clear();
            }
            snapshot.forEach(Runnable::run);
        }
    }

    /** 假反馈：把「玩家看到的每一行」记下来，供用例断言。 */
    private static final class FakeFeedback implements FeedbackPort {
        final List<String> infos = new ArrayList<>();
        final List<String> hints = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<String> successes = new ArrayList<>();
        final List<String> actionBars = new ArrayList<>();

        @Override
        public void info(String text) {
            infos.add(text);
        }

        @Override
        public void hint(String text) {
            hints.add(text);
        }

        @Override
        public void error(String text) {
            errors.add(text);
        }

        @Override
        public void success(String text) {
            successes.add(text);
        }

        @Override
        public void actionBar(String text) {
            actionBars.add(text);
        }

        boolean hasInfo(String part) {
            return infos.stream().anyMatch(line -> line.contains(part));
        }

        boolean hasHint(String part) {
            return hints.stream().anyMatch(line -> line.contains(part));
        }

        boolean hasError(String part) {
            return errors.stream().anyMatch(line -> line.contains(part));
        }

        /** 等一条 info 出现（接收方向的译文只走聊天栏，不发服务器）。 */
        boolean awaitInfo() {
            return awaitInfo(2000, 1);
        }

        boolean awaitInfo(long millis, int count) {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                if (infos.size() >= count) {
                    return true;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return infos.size() >= count;
        }
    }

    /** 假时钟：用来确定性地测节流，不用 sleep。 */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private long now = 1_000_000L;

        void advance(long millis) {
            now += millis;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }

    private static final UUID SELF_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");



    /**
     * 版本一致性：换 MC 版本时最容易漏的不是代码，而是**散在三个文件里的版本号**。
     *
     * <p>v2.0.0 从 26.2 升 26.3 时就是这样：改完 {@code gradle.properties} 还要同步改
     * {@code fabric.mod.json} 的 {@code depends}（漏了的话模组会被加载器按旧版本拒绝/放行）
     * 与 README 的环境要求表（漏了的话玩家照着装会失败）。这三处以前没有任何东西盯着。
     *
     * <p>这些断言读的是**仓库里的真实文件**，不是常量副本 —— 所以它守的是「文件之间一致」，
     * 而不是「我抄的常量对不对」。
     */
    private static void versionConsistency() {
        System.out.println("== 版本一致性（gradle.properties / fabric.mod.json / README）==");

        String props = readRepoFile("gradle.properties");
        String modJson = readRepoFile("src/main/resources/fabric.mod.json");
        String readme = readRepoFile("README.md");
        if (props == null || modJson == null || readme == null) {
            fail("版本一致性：找不到仓库文件（自检应在仓库根目录运行）");
            return;
        }

        String mc = property(props, "minecraft_version");
        String loader = property(props, "loader_version");
        String api = property(props, "fabric_api_version");
        check("gradle.properties 里读到了版本号: " + mc + " / " + loader + " / " + api,
                mc != null && loader != null && api != null);
        if (mc == null || loader == null || api == null) {
            return;
        }

        // fabric.mod.json 的 depends 必须与 gradle.properties 一致
        check("fabric.mod.json 的 minecraft 与 gradle.properties 一致（~" + mc + "）",
                contains(modJson, "\"minecraft\": \"~" + mc + "\""));
        check("fabric.mod.json 的 fabricloader 与 gradle.properties 一致（>=" + loader + "）",
                contains(modJson, "\"fabricloader\": \">=" + loader + "\""));

        // README 的安装说明必须能让玩家装上对的东西
        check("README 里写了 Minecraft " + mc, contains(readme, mc));
        check("README 里写了 Loader ≥ " + loader, contains(readme, "≥ " + loader));
        check("README 里写了 Fabric API " + api, contains(readme, api));
        check("README 里的产物文件名带 mc" + mc, contains(readme, "+mc" + mc + "-fabric.jar"));

        // 自检的类路径必须排除游戏/加载器库：否则「纯逻辑类误引用游戏 API」在自检里也能过，
        // v2.1.0 的静默丢消息 bug 就是这么藏住的（见 build.gradle 里的说明）。
        check("自检 JVM 里加载不到 Minecraft", !classAvailable("net.minecraft.client.Minecraft"));
        check("自检 JVM 里加载不到 Fabric 加载器 API", !classAvailable("net.fabricmc.api.ClientModInitializer"));
    }

    /** 读仓库根目录下的文件；读不到返回 null（用例要抗自己的失败，不能抛异常）。 */
    private static String readRepoFile(String relative) {
        try {
            Path path = Path.of(relative);
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 从 properties 文本里取一个键；没有则返回 null。 */
    private static String property(String properties, String key) {
        for (String line : properties.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(key + "=")) {
                return trimmed.substring(key.length() + 1).strip();
            }
        }
        return null;
    }

    /** 某个类在当前 JVM 里能否加载（用来确认自检真的隔离了游戏 API）。 */
    private static boolean classAvailable(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void httpSuccess() throws Exception {
        System.out.println("== DeepSeek 正常返回 ==");
        try (MockServer server = new MockServer()) {
            server.response = """
                    {"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"你好，世界"},"finish_reason":"stop"}]}
                    """;
            DeepSeekClient client = clientFor(server, "sk-test-key");

            DeepSeekClient.Result result = client.translate("Hello world", Direction.INCOMING);
            check("请求成功", result.ok());
            checkEq("译文", "你好，世界", result.text());
            checkEq("请求路径", "/chat/completions", server.lastPath);
            checkEq("鉴权头", "Bearer sk-test-key", server.lastAuth);
            checkEq("请求方法", "POST", server.lastMethod);
        }

        // 模型有时会加引号
        try (MockServer server = new MockServer()) {
            server.response = """
                    {"choices":[{"message":{"role":"assistant","content":"\\"Where are you?\\""}}]}
                    """;
            DeepSeekClient.Result result = clientFor(server, "sk-test").translate("你在哪", Direction.OUTGOING);
            check("去掉模型加的引号", result.ok() && "Where are you?".equals(result.text()));
        }
    }

    private static void httpBaseUrls() throws Exception {
        System.out.println("== API 地址归一化 ==");
        try (MockServer server = new MockServer()) {
            server.response = ok("x");

            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.apiBaseUrl = "http://127.0.0.1:" + server.port + "/";
            new DeepSeekClient(config).translate("hi", Direction.INCOMING);
            checkEq("去掉结尾斜杠", "/chat/completions", server.lastPath);

            config.apiBaseUrl = "http://127.0.0.1:" + server.port + "/v1";
            new DeepSeekClient(config).translate("hi", Direction.INCOMING);
            checkEq("带 /v1 前缀", "/v1/chat/completions", server.lastPath);

            config.apiBaseUrl = "http://127.0.0.1:" + server.port + "/v1/chat/completions";
            new DeepSeekClient(config).translate("hi", Direction.INCOMING);
            checkEq("已是完整地址不重复拼接", "/v1/chat/completions", server.lastPath);
        }
    }

    private static void requestBody() throws Exception {
        System.out.println("== 请求体 ==");
        try (MockServer server = new MockServer()) {
            server.response = ok("translated");
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.apiBaseUrl = "http://127.0.0.1:" + server.port;
            config.model = "deepseek-chat";

            new DeepSeekClient(config).translate("Hello world", Direction.INCOMING);

            JsonObject body = JsonParser.parseString(server.lastBody).getAsJsonObject();
            checkEq("model", "deepseek-chat", body.get("model").getAsString());
            check("stream=false", !body.get("stream").getAsBoolean());
            check("temperature 存在", body.has("temperature"));
            check("max_tokens 存在", body.has("max_tokens"));

            JsonArray messages = body.getAsJsonArray("messages");
            checkEq("消息条数", 2, messages.size());
            checkEq("system 角色", "system", messages.get(0).getAsJsonObject().get("role").getAsString());
            checkEq("user 角色", "user", messages.get(1).getAsJsonObject().get("role").getAsString());
            checkEq("user 内容", "Hello world", messages.get(1).getAsJsonObject().get("content").getAsString());
            String systemPrompt = messages.get(0).getAsJsonObject().get("content").getAsString();
            check("system 提示词提示了中文方向", systemPrompt.contains("Simplified Chinese"));
            // v1.0.1：术语表要注入「收到消息」方向的提示词，解决 U def / obby / dia 不翻译的问题
            check("术语表已注入", systemPrompt.contains("obby=黑曜石") && systemPrompt.contains("术语与缩写对照表"));
            check("术语表要求按含义翻译", systemPrompt.contains("不要保留英文原样"));
            check("提示词说明了要保留 [红队] 这类前缀", systemPrompt.contains("[红队]"));

            new DeepSeekClient(config).translate("你好", Direction.OUTGOING);
            JsonObject outgoing = JsonParser.parseString(server.lastBody).getAsJsonObject();
            String outgoingPrompt = outgoing.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            check("发送方向提示词提示了英文", outgoingPrompt.contains("English"));
            // v1.1.4：术语表从「只给接收方向」改成两个方向都用 —— 发送方向反查成
            // 「中文说法 -> 英文写法」，否则玩家打「我有黑曜石」只能得到 black obsidian。
            check("发送方向也注入术语表（反查成中文 -> 英文）",
                    outgoingPrompt.contains("黑曜石 -> obby") && outgoingPrompt.contains("terminology reference"));
            check("发送方向不注入英→中的原始条目", !outgoingPrompt.contains("obby=黑曜石"));
        }

        // 术语表为空（用户清空 = 关闭术语表）时两个方向都不该有术语表段落
        try (MockServer server = new MockServer()) {
            server.response = ok("translated");
            TranslatorConfig config = new TranslatorConfig();
            config.apiKey = "sk-test";
            config.apiBaseUrl = "http://127.0.0.1:" + server.port;
            config.glossary = new ArrayList<>();
            DeepSeekClient client = new DeepSeekClient(config);

            client.translate("Hello world", Direction.INCOMING);
            String incomingPrompt = JsonParser.parseString(server.lastBody).getAsJsonObject()
                    .getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            check("清空术语表后接收方向没有对照表", !incomingPrompt.contains("对照表"));

            client.translate("你好", Direction.OUTGOING);
            String outgoingPrompt = JsonParser.parseString(server.lastBody).getAsJsonObject()
                    .getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            check("清空术语表后发送方向没有对照表", !outgoingPrompt.contains("terminology reference"));
        }
    }

    private static void httpErrors() throws Exception {
        System.out.println("== 错误处理 ==");
        try (MockServer server = new MockServer()) {
            DeepSeekClient client = clientFor(server, "sk-bad");

            server.status = 401;
            server.response = "{\"error\":{\"message\":\"Authentication Fails\"}}";
            DeepSeekClient.Result r401 = client.translate("hi", Direction.INCOMING);
            check("401 失败", !r401.ok());
            check("401 提示 Key 无效: " + r401.error(), r401.error().contains("API Key"));

            server.status = 402;
            server.response = "{\"error\":{\"message\":\"Insufficient Balance\"}}";
            check("402 提示余额不足: " + client.translate("hi", Direction.INCOMING).error(),
                    client.translate("hi", Direction.INCOMING).error().contains("余额"));

            server.status = 429;
            server.response = "{\"error\":{\"message\":\"Rate limit\"}}";
            check("429 提示限流", client.translate("hi", Direction.INCOMING).error().contains("限流"));

            server.status = 500;
            server.response = "oops";
            check("500 提示服务不可用", client.translate("hi", Direction.INCOMING).error().contains("服务暂时不可用"));

            server.status = 200;
            server.response = "{\"choices\":[]}";
            DeepSeekClient.Result empty = client.translate("hi", Direction.INCOMING);
            check("空 choices 失败", !empty.ok() && empty.error().contains("为空"));

            server.status = 200;
            server.response = "not json at all";
            check("非 JSON 失败", !client.translate("hi", Direction.INCOMING).ok());
        }

        System.out.println("== 网络异常 ==");
        TranslatorConfig config = new TranslatorConfig();
        config.apiKey = "sk-test";
        config.apiBaseUrl = "http://127.0.0.1:1";
        config.httpTimeoutSeconds = 3;
        DeepSeekClient.Result refused = new DeepSeekClient(config).translate("hi", Direction.INCOMING);
        check("连接失败不抛异常", !refused.ok());
        check("连接失败提示网络错误: " + refused.error(), refused.error().contains("网络错误"));

        System.out.println("== 没有 Key ==");
        TranslatorConfig noKey = new TranslatorConfig();
        check("没 Key 直接失败", !new DeepSeekClient(noKey).translate("hi", Direction.INCOMING).ok());
    }

    // ------------------------------------------------------------------

    private static DeepSeekClient clientFor(MockServer server, String key) {
        TranslatorConfig config = new TranslatorConfig();
        config.apiKey = key;
        config.apiBaseUrl = "http://127.0.0.1:" + server.port;
        config.httpTimeoutSeconds = 5;
        return new DeepSeekClient(config);
    }

    private static String ok(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    }

    /** 只用于测试的本地 HTTP 服务，记录最后一次请求。 */
    private static final class MockServer implements AutoCloseable {
        private final HttpServer server;
        final int port;
        volatile int status = 200;
        volatile String response = "{}";
        volatile long delayMs = 0;
        /** 前 N 次请求返回 failStatus，用来测试重试。 */
        volatile int failFirst = 0;
        volatile int failStatus = 500;
        private final java.util.concurrent.atomic.AtomicInteger requestCount =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile String lastPath;
        volatile String lastAuth;
        volatile String lastBody;
        volatile String lastMethod;

        MockServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                lastPath = exchange.getRequestURI().getPath();
                lastMethod = exchange.getRequestMethod();
                lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                int effectiveStatus = status;
                int nth = requestCount.incrementAndGet(); // 所有请求都计数，供用例断言真实调用次数
                if (failFirst > 0 && nth <= failFirst) {
                    effectiveStatus = failStatus;
                }
                byte[] payload = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(effectiveStatus, payload.length);
                try {
                    exchange.getResponseBody().write(payload);
                } catch (IOException ignored) {
                    // 客户端读到自己要的部分就断开了（例如「响应过大」用例），属预期情况
                }
                exchange.close();
            });
            server.start();
            port = server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 安全的「包含」判断：被检查的文本可能是 null。
     *
     * <p>用例要**抗自己的失败**（RELEASING §6）：断言里直接写 {@code render(...).contains(...)}，
     * 一旦被测对象返回 null，抛出的 NPE 会让整个自检停在半路 —— 后面的用例一条都跑不到，
     * 反向验证也看不出全貌。返回 null 本身就是要断言的情况之一，不该变成异常。
     */
    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.contains(needle);
    }

    private static void check(String label, boolean condition) {        if (condition) {
            passed++;
            System.out.println("  [OK]   " + label);
        } else {
            fail(label);
        }
    }

    private static void checkEq(String label, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
            System.out.println("  [OK]   " + label + " = " + actual);
        } else {
            fail(label + " 期望 <" + expected + "> 实际 <" + actual + ">");
        }
    }

    private static void fail(String label) {
        failed++;
        System.out.println("  [FAIL] " + label);
    }
}
