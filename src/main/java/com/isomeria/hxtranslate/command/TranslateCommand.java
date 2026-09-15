package com.isomeria.hxtranslate.command;

import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.chat.Feedback;
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

    public static void register(TranslatorConfig config, TranslationService service, ChatTranslator translator) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(build("hxtranslate", config, service, translator));
            dispatcher.register(build("hxt", config, service, translator));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(
            String name, TranslatorConfig config, TranslationService service, ChatTranslator translator) {

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
                    context.getSource().sendFeedback(Component.literal(
                            "§a配置已重新加载：缓存已清空、限流与熔断已复位"));
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
                    context.getSource().sendFeedback(Component.literal("§c已关闭：中文不再翻译，直接发送"));
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
                            Feedback.success("可用模型: §f" + result.text() + "§a ｜ 当前使用: §f" + config.model);
                        } else {
                            Feedback.error("查询失败: " + result.error());
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
                                    runTest(service, text);
                                    return 1;
                                })));
    }

    /** 测试翻译要发网络请求，必须放到后台线程，否则会卡住游戏。 */
    private static void runTest(TranslationService service, String text) {
        // 方向按内容自动判断，规则和实际收发时一致：含汉字 = 你想发出去的中文（中→英），
        // 否则当作收到的英文（英→中）。
        // 以前这里固定用「英→中」，于是 `/hxtranslate test 你好` 会得到「你好」原样返回，
        // 看着像模组坏了，其实是根本没测到发送方向 —— 而发送方向才是会影响服务器里别人的那个。
        Direction direction = LangUtils.containsHan(text) ? Direction.OUTGOING : Direction.INCOMING;
        Thread thread = new Thread(() -> {
            DeepSeekClient.Result result = service.translateBlocking(text, direction);
            if (result.ok()) {
                Feedback.success("测试译文（" + direction.label() + "）: §f" + result.text());
            } else {
                Feedback.error("测试失败: " + result.error());
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
        source.sendFeedback(Component.literal("§7本分钟请求: §f" + service.usedRequestsThisMinute()
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
