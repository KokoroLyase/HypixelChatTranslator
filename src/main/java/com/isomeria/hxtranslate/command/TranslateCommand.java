package com.isomeria.hxtranslate.command;

import com.isomeria.hxtranslate.chat.Feedback;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.DeepSeekClient;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.core.TranslationService;
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

    public static void register(TranslatorConfig config, TranslationService service) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(build("hxtranslate", config, service));
            dispatcher.register(build("hxt", config, service));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(
            String name, TranslatorConfig config, TranslationService service) {

        return ClientCommands.literal(name)
                .executes(context -> {
                    status(context.getSource(), config, service);
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
                    status(context.getSource(), config, service);
                    return 1;
                }))
                .then(ClientCommands.literal("reload").executes(context -> {
                    config.reload();
                    service.invalidateCache();
                    service.resetRateLimit();
                    context.getSource().sendFeedback(Component.literal("§a配置已重新加载，缓存已清空"));
                    return 1;
                }))
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
                                    context.getSource().sendFeedback(Component.literal(
                                            "§a已保存 DeepSeek API Key（长度 " + key.length() + "，出于安全不回显内容）"));
                                    return 1;
                                })))
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
        Thread thread = new Thread(() -> {
            DeepSeekClient.Result result = service.translateBlocking(text, Direction.INCOMING);
            if (result.ok()) {
                Feedback.success("测试译文: §f" + result.text());
            } else {
                Feedback.error("测试失败: " + result.error());
            }
        }, "hxtranslate-test");
        thread.setDaemon(true);
        thread.start();
    }

    private static void status(FabricClientCommandSource source, TranslatorConfig config, TranslationService service) {
        source.sendFeedback(Component.literal("§8===== §bHypixel 聊天翻译 §8====="));
        source.sendFeedback(Component.literal("§7总开关: " + onOff(config.enabled)
                + " §8| §7收到翻译: " + onOff(config.translateIncoming)
                + " §8| §7发送翻译: " + onOff(config.translateOutgoing)));
        source.sendFeedback(Component.literal("§7模型: §f" + config.model
                + " §8| §7API Key: " + (config.hasApiKey() ? "§a已配置" : "§c未配置")));
        source.sendFeedback(Component.literal("§7本分钟请求: §f" + service.usedRequestsThisMinute()
                + "§7/§f" + config.requestsPerMinute
                + " §8| §7配置文件: §f" + TranslatorConfig.configPath().getFileName()));
    }

    private static String onOff(boolean value) {
        return value ? "§a开" : "§c关";
    }
}
