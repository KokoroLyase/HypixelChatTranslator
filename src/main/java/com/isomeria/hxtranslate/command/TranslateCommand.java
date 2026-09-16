package com.isomeria.hxtranslate.command;

import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.chat.FeedbackPort;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.DeepSeekClient;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.core.TranslationService;
import com.isomeria.hxtranslate.util.LangUtils;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * 客户端命令 /hxtranslate（别名 /hxt）。
 * 这些命令只在本地执行，不会发到 Hypixel 服务器。
 */
public final class TranslateCommand {

    private TranslateCommand() {
    }

    public static void register(TranslatorConfig config, TranslationService service,
                                ChatTranslator translator, FeedbackPort feedback) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(build("hxtranslate", config, service, translator, feedback));
            dispatcher.register(build("hxt", config, service, translator, feedback));
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
                    }, "hxtranslate-models");
                    thread.setDaemon(true);
                    thread.start();
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

    /** 测试翻译要发网络请求，必须放到后台线程，否则会卡住游戏。 */
    private static void runTest(TranslationService service, FeedbackPort feedback, String text) {
        // 方向按内容自动判断，规则和实际收发时一致：含汉字 = 你想发出去的中文（中→英），
        // 否则当作收到的英文（英→中）。
        // 以前这里固定用「英→中」，于是 `/hxtranslate test 你好` 会得到「你好」原样返回，
        // 看着像模组坏了，其实是根本没测到发送方向 —— 而发送方向才是会影响服务器里别人的那个。
        Direction direction = LangUtils.containsHan(text) ? Direction.OUTGOING : Direction.INCOMING;
        Thread thread = new Thread(() -> {
            DeepSeekClient.Result result = service.translateBlocking(text, direction);
            if (result.ok()) {
                feedback.success("测试译文（" + direction.label() + "）: §f" + result.text());
            } else {
                feedback.error("测试失败: " + result.error());
            }
        }, "hxtranslate-test");
        thread.setDaemon(true);
        thread.start();
    }

    private static void status(FabricClientCommandSource source, TranslatorConfig config,
                               TranslationService service, ChatTranslator translator) {
        source.sendFeedback(Component.literal("§8===== §bHypixel 聊天翻译 §8====="));
        source.sendFeedback(Component.literal("§7总开关: " + onOff(config.enabled)
                + " §8| §7收到翻译: " + onOff(config.translateIncoming)
                + " §8| §7发送翻译: " + onOff(config.translateOutgoing)
                + " §8| §7调试: " + onOff(config.debugLog)));
        source.sendFeedback(Component.literal("§7模型: §f" + config.model
                + " §8| §7思考模式: " + onOff(config.enableThinking)
                + " §8| §7API Key: " + (config.hasApiKey() ? "§a已配置" : "§c未配置")
                + " §8| §7术语表: §f" + (config.glossary == null ? 0 : config.glossary.size()) + " §7条"));
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
                    + "改掉它并 §f/hxtranslate reload §e即可恢复。"));
            for (String regex : LangUtils.disabledRegexes()) {
                source.sendFeedback(Component.literal("§8  - §7" + LangUtils.sanitizeOneLine(regex)));
            }
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
