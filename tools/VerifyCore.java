import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.DeepSeekClient;
import com.isomeria.hxtranslate.core.Direction;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 离线验证：不启动 Minecraft，直接验证翻译核心逻辑（语言判断、命令解析、DeepSeek 请求/响应）。
 *
 * <pre>
 * javac -encoding UTF-8 -cp build/classes/java/main:libs/* -d build/verify tools/VerifyCore.java
 * java  -cp build/classes/java/main:build/verify:libs/* VerifyCore
 * </pre>
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
            check("译文仍是中文 -> 判为失败", !unchanged.ok() && unchanged.error().contains("仍是中文"));

            server.response = ok("find 小明 to play");
            check("英文译文里夹中文玩家名不算「没翻译」",
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
            check("发送方向不注入中文术语表", !outgoingPrompt.contains("obby=黑曜石"));
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
                if (failFirst > 0 && requestCount.incrementAndGet() <= failFirst) {
                    effectiveStatus = failStatus;
                }
                byte[] payload = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(effectiveStatus, payload.length);
                exchange.getResponseBody().write(payload);
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

    private static void check(String label, boolean condition) {
        if (condition) {
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
