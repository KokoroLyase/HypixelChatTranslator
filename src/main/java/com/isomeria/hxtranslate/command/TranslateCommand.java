package com.isomeria.hxtranslate.command;

import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.chat.FeedbackPort;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.DeepSeekClient;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.core.GlossaryAudit;
import com.isomeria.hxtranslate.core.TranslationService;
import com.isomeria.hxtranslate.util.LangUtils;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 客户端命令 /translator（v3.0.0 起不再注册旧别名 /hxtranslate 与 /hxt）。
 * 这些命令只在本地执行，不会发到 Hypixel 服务器。
 */
public final class TranslateCommand {

    /** 术语表体检在一次命令里最多列几条明细（再多就把聊天记录顶掉了）。 */
    private static final int GLOSSARY_DETAIL_LIMIT = 12;

    private TranslateCommand() {
    }

    public static void register(TranslatorConfig config, TranslationService service,
                                ChatTranslator translator, FeedbackPort feedback) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(build("translator", config, service, translator, feedback));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(
            String name, TranslatorConfig config, TranslationService service,
            ChatTranslator translator, FeedbackPort feedback) {

        return ClientCommands.literal(name)
                .executes(context -> {
                    status(context.getSource(), config, service, translator);
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(context -> {
                    config.enabled = true;
                    config.save();
                    context.getSource().sendFeedback(Component.literal("§a聊天翻译已开启"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(context -> {
                    config.enabled = false;
                    config.save();
                    context.getSource().sendFeedback(Component.literal("§c聊天翻译已关闭"));
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(context -> {
                    status(context.getSource(), config, service, translator);
                    return 1;
                }))
                .then(ClientCommands.literal("reload").executes(context -> {
                    config.reload();
                    service.invalidateCache();
                    service.resetRateLimit();
                    // 重载往往是因为「刚换了 Key / 接口地址」，这时不该让之前攒下的熔断继续挡着
                    service.resetCircuit();
                    // 正则熔断也要复位（v2.2.2）：被停用的 ignorePatterns 条目只能靠这里恢复，
                    // 否则玩家唯一的办法是重启游戏 —— 而忽略规则失效在游戏内完全看不见。
                    int revived = LangUtils.disabledRegexCount();
                    LangUtils.resetRegexCircuit();
                    // 文件改坏了要当场说：否则玩家会以为「reload 没生效」而反复重试
                    String warning = config.loadWarning();
                    String message = warning == null
                            ? "§a配置已重新加载：缓存已清空、限流与熔断已复位"
                            : "§c配置重新加载有问题：" + warning;
                    if (revived > 0) {
                        message = message + "§a（另有 " + revived + " 条被停用的忽略正则已恢复）";
                    }
                    // 重载往往就是「刚改完术语表」：顺手体检一次，写反 / 漏等号的条目当场说清楚
                    String suspicious = GlossaryAudit.countsText(GlossaryAudit.audit(config.glossary));
                    if (suspicious != null) {
                        message = message + "§e（术语表体检：" + suspicious + "，输入 /translator glossary 查看）";
                    }
                    context.getSource().sendFeedback(Component.literal(message));
                    return 1;
                }))
                .then(ClientCommands.literal("debug")
                        .then(ClientCommands.literal("on").executes(context -> {
                            config.debugLog = true;
                            config.save();
                            translator.resetCounters();
                            context.getSource().sendFeedback(Component.literal(
                                    "§a调试模式已开启：会在聊天栏打印每条消息是「翻译」还是「跳过（原因）」"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(context -> {
                            config.debugLog = false;
                            config.save();
                            context.getSource().sendFeedback(Component.literal("§c调试模式已关闭"));
                            return 1;
                        })))
                .then(ClientCommands.literal("incoming").then(ClientCommands.literal("on").executes(context -> {
                    config.translateIncoming = true;
                    config.save();
                    context.getSource().sendFeedback(Component.literal("§a已开启：收到的英文消息自动翻译"));
                    return 1;
                })).then(ClientCommands.literal("off").executes(context -> {
                    config.translateIncoming = false;
                    config.save();
                    context.getSource().sendFeedback(Component.literal("§c已关闭：收到的消息不再翻译"));
                    return 1;
                })))
                .then(ClientCommands.literal("outgoing").then(ClientCommands.literal("on").executes(context -> {
                    config.translateOutgoing = true;
                    config.save();
                    context.getSource().sendFeedback(Component.literal("§a已开启：中文自动翻译成英文发送"));
                    return 1;
                })).then(ClientCommands.literal("off").executes(context -> {
                    config.translateOutgoing = false;
                    config.save();
                    // 这条只管「直接打出来的聊天」：命令正文由 translateCommandMessages 单独控制，
                    // 以前这里的提示是「中文不再翻译，直接发送」，和 /shout 仍会被翻译的实际行为不符。
                    context.getSource().sendFeedback(Component.literal(
                            "§c已关闭：直接打出的中文不再翻译（命令正文见 §ftranslateCommandMessages§c）"));
                    return 1;
                })))
                // 单人（单机）世界里要不要翻译（v3.0.0）。默认关：单机里的聊天多半是自己看的，
                // 而且 NPC 对话/告示牌/命令输出逐条送去翻译既费钱又刷屏。
                .then(ClientCommands.literal("singleplayer").then(ClientCommands.literal("on").executes(context -> {
                    config.translateInSingleplayer = true;
                    config.save();
                    context.getSource().sendFeedback(Component.literal("§a已开启：单人世界的消息也会翻译"));
                    return 1;
                })).then(ClientCommands.literal("off").executes(context -> {
                    config.translateInSingleplayer = false;
                    config.save();
                    context.getSource().sendFeedback(Component.literal("§c已关闭：单人世界不翻译（多人服不受影响）"));
                    return 1;
                })))
                .then(ClientCommands.literal("key")
                        .then(ClientCommands.argument("value", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String key = StringArgumentType.getString(context, "value").trim();
                                    config.apiKey = key;
                                    config.save();
                                    // 刚换了 Key，之前因为网络/限流攒下的熔断不该继续挡着
                                    service.resetCircuit();
                                    context.getSource().sendFeedback(Component.literal(
                                            "§a已保存 DeepSeek API Key（长度 " + key.length() + "，出于安全不回显内容）"));
                                    return 1;
                                })))
                .then(ClientCommands.literal("models").executes(context -> {
                    context.getSource().sendFeedback(Component.literal("§7正在查询 DeepSeek 可用模型…"));
                    Thread thread = new Thread(() -> {
                        DeepSeekClient.Result result = service.listModels();
                        if (result.ok()) {
                            // GameFeedback 会把 § 和高亮一起剥掉（那里的入口按不可信文本处理，
                            // 见它的 javadoc），所以这里只拼纯文本。
                            feedback.success("可用模型: " + result.text() + " ｜ 当前使用: " + config.model);
                        } else {
                            feedback.error("查询失败: " + result.error());
                        }
                    }, "server_chat_translator-models");
                    thread.setDaemon(true);
                    thread.start();
                    return 1;
                }))
                .then(ClientCommands.literal("glossary").executes(context -> {
                    reportGlossary(context.getSource(), config);
                    return 1;
                }))
                .then(ClientCommands.literal("test")
                        .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String text = StringArgumentType.getString(context, "text");
                                    context.getSource().sendFeedback(Component.literal("§7正在翻译: §f" + text));
                                    runTest(service, feedback, text);
                                    return 1;
                                })));
    }

    /**
     * 术语表体检报告（{@code /translator glossary}）。
     *
     * <p>术语表是玩家长期维护的资产，而「写反了」「漏了等号」这类错误是**完全静默**的：
     * 前者让两个方向的含义都反过来，后者会被渲染直接丢掉（加了词却一个字都没进提示词）。
     * 判定逻辑全在 {@link GlossaryAudit} 里（纯逻辑、离线自检覆盖），这里只负责打印。
     *
     * <p>命令反馈走的是 {@code source.sendFeedback}，**不经过** {@code GameFeedback} 的统一清洗，
     * 所以明细文本由 {@code GlossaryAudit.detailLines} 负责去 {@code §}、压成一行并限长。
     */
    private static void reportGlossary(FabricClientCommandSource source, TranslatorConfig config) {
        List<GlossaryAudit.Finding> findings = GlossaryAudit.audit(config.glossary);
        int total = config.glossary == null ? 0 : config.glossary.size();
        source.sendFeedback(Component.literal("§8===== §b术语表体检 §8====="));
        String counts = GlossaryAudit.countsText(findings);
        if (counts == null) {
            source.sendFeedback(Component.literal("§a未发现可疑条目§7（共 " + total + " 条）"));
            return;
        }
        source.sendFeedback(Component.literal("§e发现 " + counts));
        for (String line : GlossaryAudit.detailLines(findings, GLOSSARY_DETAIL_LIMIT)) {
            source.sendFeedback(Component.literal("§7  - " + line));
        }
        source.sendFeedback(Component.literal(
                "§7体检只做提示，不会自动改你的配置；改完术语表后 §f/translator reload §7即可生效"));
    }

    /** 测试翻译要发网络请求，必须放到后台线程，否则会卡住游戏。 */
    private static void runTest(TranslationService service, FeedbackPort feedback, String text) {
        // 方向按内容自动判断，规则和实际收发时一致：含汉字 = 你想发出去的中文（中→英），
        // 否则当作收到的英文（英→中）。
        // 以前这里固定用「英→中」，于是 `/translator test 你好` 会得到「你好」原样返回，
        // 看着像模组坏了，其实是根本没测到发送方向 —— 而发送方向才是会影响服务器里别人的那个。
        Direction direction = LangUtils.containsHan(text) ? Direction.OUTGOING : Direction.INCOMING;
        Thread thread = new Thread(() -> {
            DeepSeekClient.Result result = service.translateBlocking(text, direction);
            if (result.ok()) {
                feedback.success("测试译文（" + direction.label() + "）: §f" + result.text());
            } else {
                feedback.error("测试失败: " + result.error());
            }
        }, "server_chat_translator-test");
        thread.setDaemon(true);
        thread.start();
    }

    private static void status(FabricClientCommandSource source, TranslatorConfig config,
                               TranslationService service, ChatTranslator translator) {
        source.sendFeedback(Component.literal("§8===== §bServer Chat Translator §8====="));
        source.sendFeedback(Component.literal("§7总开关: " + onOff(config.enabled)
                + " §8| §7收到翻译: " + onOff(config.translateIncoming)
                + " §8| §7发送翻译: " + onOff(config.translateOutgoing)
                + " §8| §7调试: " + onOff(config.debugLog)));
        source.sendFeedback(Component.literal("§7模型: §f" + config.model
                + " §8| §7思考模式: " + onOff(config.enableThinking)
                + " §8| §7API Key: " + (config.hasApiKey() ? "§a已配置" : "§c未配置")
                + " §8| §7术语表: §f" + (config.glossary == null ? 0 : config.glossary.size()) + " §7条"));
        // 单人闸门的状态（v3.0.0）：玩家在单机里发现「怎么不翻译」时，唯一能告诉他原因的地方。
        // 文案由共享层生成，两条线的口径不会漂移（见 ChatTranslator#singleplayerStatusLine）。
        source.sendFeedback(Component.literal(translator.singleplayerStatusLine()));
        if (service.isCircuitOpen()) {
            source.sendFeedback(Component.literal("§c翻译服务连续失败，熔断中，还需 §f"
                    + service.circuitRemainingSeconds() + " §c秒"));
        }
        // 被停用的忽略正则必须可见（v2.2.2）：忽略规则失效是「静默少省钱、多翻译」，
        // 玩家不主动看根本发现不了；这里告诉他怎么恢复（reload 就能复活）。
        int disabledRegexes = LangUtils.disabledRegexCount();
        if (disabledRegexes > 0) {
            source.sendFeedback(Component.literal("§e有 §f" + disabledRegexes
                    + " §e条 ignorePatterns 正则因匹配超时被停用（多半写了灾难性回溯的写法）。"
                    + "改掉它并 §f/translator reload §e即可恢复。"));
            for (String regex : LangUtils.disabledRegexes()) {
                source.sendFeedback(Component.literal("§8  - §7" + LangUtils.sanitizeOneLine(regex)));
            }
        }
        // 术语表体检（v2.3.0）：有问题才说话，和上面的正则熔断一样，避免把状态刷成一片。
        // 写反 / 漏等号的条目完全静默（加了词却进不了提示词），不主动报玩家永远发现不了。
        String glossaryCounts = GlossaryAudit.countsText(GlossaryAudit.audit(config.glossary));
        if (glossaryCounts != null) {
            source.sendFeedback(Component.literal("§e术语表体检发现 " + glossaryCounts
                    + "，输入 §f/translator glossary §e查看并修改"));
        }
        source.sendFeedback(Component.literal("§7输入长度上限: §f" + config.maxIncomingChars
                + "§7字符 §8| §7本分钟请求: §f" + service.usedRequestsThisMinute()
                + "§7/§f" + config.requestsPerMinute
                + " §8| §7进行中: §f" + service.pendingTranslations()
                + " §8| §7中文判定阈值: §f" + config.chineseRatioThreshold));
        source.sendFeedback(Component.literal(translator.counters()));
        source.sendFeedback(Component.literal(translator.sendCounters()));
    }

    private static String onOff(boolean value) {
        return value ? "§a开" : "§c关";
    }
}
