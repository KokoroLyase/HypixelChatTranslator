package com.isomeria.hxtranslate;

import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.chat.Feedback;
import com.isomeria.hxtranslate.command.TranslateCommand;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.TranslationService;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hypixel 聊天翻译模组入口（纯客户端）。
 */
public final class HxTranslateClient implements ClientModInitializer {

    public static final String MOD_ID = "hxtranslate";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * 默认按 F6 切换翻译开关，可在“按键设置 → 多人游戏”里修改。
     *
     * <p>两处都是 26.3 的适配点，改之前请先看 26.2 的写法：
     * <ul>
     *   <li>键码常量用 {@link InputConstants#KEY_F6}，不要用
     *       {@code org.lwjgl.glfw.GLFW.GLFW_KEY_F6} —— 26.3 起 Loom 的编译类路径不再直接暴露
     *       LWJGL 的 glfw 模块，直接引它是 {@code package org.lwjgl.glfw does not exist}。
     *       Minecraft 自己已经把键码导出在 {@link InputConstants} 里，不需要额外依赖；</li>
     *   <li>输入类型用 {@link InputConstants.Type#KEYBOARD}，不是 26.2 的
     *       {@code InputConstants.Type.KEYSYM} —— 26.3 把 {@code KEYSYM} / {@code SCANCODE}
     *       合并成了 {@code KEYBOARD}（{@code Type} 现在只剩 {@code KEYBOARD} 与 {@code MOUSE}）。</li>
     * </ul>
     */
    private static final int DEFAULT_TOGGLE_KEY = InputConstants.KEY_F6;

    private TranslatorConfig config;
    private TranslationService service;
    private ChatTranslator translator;

    private boolean startupNoticeShown;

    @Override
    public void onInitializeClient() {
        config = TranslatorConfig.load();
        service = new TranslationService(config);
        translator = new ChatTranslator(config, service);
        translator.register();

        KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.hxtranslate.toggle",
                InputConstants.Type.KEYBOARD,
                DEFAULT_TOGGLE_KEY,
                KeyMapping.Category.MULTIPLAYER));

        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (toggleKey.consumeClick()) {
                config.enabled = !config.enabled;
                config.save();
                Feedback.info(config.enabled ? "§a聊天翻译已开启" : "§c聊天翻译已关闭");
            }
            if (!startupNoticeShown && minecraft.player != null) {
                startupNoticeShown = true;
                showStartupNotice();
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> service.shutdown());

        TranslateCommand.register(config, service, translator);

        if (!config.hasApiKey()) {
            LOGGER.warn("尚未配置 DeepSeek API Key，翻译功能不可用。配置文件: {}", TranslatorConfig.configPath());
        }
        LOGGER.info("Hypixel 聊天翻译已加载 (MC 26.2 / DeepSeek {})", config.model);
    }

    private void showStartupNotice() {
        Feedback.info("§8[§bhx§8] §7Hypixel 聊天翻译已就绪 §8(" + (config.enabled ? "§a开" : "§c关") + "§8)");
        // 配置读不出来时必须说清楚：否则玩家看到「未配置 API Key」会以为模组坏了，
        // 甚至重新输入一遍 Key 把原件覆盖掉（原文件已经备份过，但先说清楚能省掉这一步）。
        if (config.loadWarning() != null) {
            Feedback.error(config.loadWarning());
        }
        if (!config.hasApiKey()) {
            Feedback.error("未配置 DeepSeek API Key！请执行 §f/hxtranslate key <你的Key> §c或编辑配置文件。");
            Feedback.hint("配置文件: " + TranslatorConfig.configPath());
        } else {
            Feedback.hint("F6 开关翻译，/hxtranslate status 查看状态，/hxtranslate debug on 排错");
        }
    }
}
