import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.DeepSeekClient;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.util.CommandMessage;
import com.isomeria.hxtranslate.util.IncomingFilter;
import com.isomeria.hxtranslate.util.LangUtils;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** v1.0.1 修复的两个 bug 的回归用例，样本直接取自玩家反馈的截图。 */
    private static void hypixelSamples() {
        System.out.println("== Hypixel 真实聊天样本回归 ==");
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
                byte[] payload = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, payload.length);
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
