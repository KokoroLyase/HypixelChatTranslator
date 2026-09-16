package com.isomeria.hxtranslate;

import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.chat.FeedbackPort;
import com.isomeria.hxtranslate.chat.GameClient;
import com.isomeria.hxtranslate.chat.GameFeedback;
import com.isomeria.hxtranslate.command.TranslateCommand;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.GlossaryAudit;
import com.isomeria.hxtranslate.core.TranslationService;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Hypixel 聊天翻译模组入口（纯客户端）。
 *
 * <p>这里只做「装配」：造出翻译逻辑需要的几个实现（{@code GameClient} / {@code GameFeedback}
 * / {@code TranslationService}），把事件挂上，把开关按键接好。
 * 决策逻辑在 {@code ChatTranslator} 里，那部分不依赖 Minecraft，由离线自检覆盖
 * （见 {@code tools/VerifyCore.java}）。改动本类时请留意：**它不在自检覆盖范围内**，
 * 所以尽量只往这里加装配代码，不要加判断逻辑。
 *
 * <p>{@code GameClient} 需要 {@code ChatTranslator}，而 {@code ChatTranslator} 又需要
 * {@code GameClient}（作为端口）。解法是先建 client、再建 translator、最后调
 * {@link GameClient#bind(ChatTranslator)} 补上引用 —— 避免用 setter 暴露一个
 * 「随时可以被改」的字段。
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
    private FeedbackPort feedback;

    private boolean startupNoticeShown;

    @Override
    public void onInitializeClient() {
        config = TranslatorConfig.load();
        service = new TranslationService(config);
        feedback = new GameFeedback();

        GameClient client = new GameClient();
        translator = new ChatTranslator(config, service, client, feedback);
        client.bind(translator);

        // 事件注册与开关按键都在 client 里，入口只负责把 KeyMapping 做出来交给它
        KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.hxtranslate.toggle",
                InputConstants.Type.KEYBOARD,
                DEFAULT_TOGGLE_KEY,
                KeyMapping.Category.MULTIPLAYER));
        // 事件注册在这里；开关按键的 press 轮询在 registerToggleTick 里
        client.register();
        registerToggleTick(toggleKey);

        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> service.shutdown());

        TranslateCommand.register(config, service, translator, feedback);

        if (!config.hasApiKey()) {
            LOGGER.warn("尚未配置 DeepSeek API Key，翻译功能不可用。配置文件: {}", TranslatorConfig.configPath());
        }
        LOGGER.info("Hypixel 聊天翻译已加载 (MC 26.3 / DeepSeek {})", config.model);
    }

    /** 开关按键的轮询与启动提示。 */
    private void registerToggleTick(KeyMapping toggleKey) {
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (toggleKey.consumeClick()) {
                config.enabled = !config.enabled;
                config.save();
                feedback.info(config.enabled ? "§a聊天翻译已开启" : "§c聊天翻译已关闭");
            }
            if (!startupNoticeShown && minecraft.player != null) {
                startupNoticeShown = true;
                showStartupNotice();
            }
        });
    }

    private void showStartupNotice() {
        feedback.info("§8[§bhx§8] §7Hypixel 聊天翻译已就绪 §8(" + (config.enabled ? "§a开" : "§c关") + "§8)");
        // 配置读不出来时必须说清楚：否则玩家看到「未配置 API Key」会以为模组坏了，
        // 甚至重新输入一遍 Key 把原件覆盖掉（原文件已经备份过，但先说清楚能省掉这一步）。
        if (config.loadWarning() != null) {
            feedback.error(config.loadWarning());
        }
        if (!config.hasApiKey()) {
            feedback.error("未配置 DeepSeek API Key！请执行 §f/hxtranslate key <你的Key> §c或编辑配置文件。");
            feedback.hint("配置文件: " + TranslatorConfig.configPath());
        } else {
            feedback.hint("F6 开关翻译，/hxtranslate status 查看状态，/hxtranslate debug on 排错");
        }
        reportGlossaryFindings();
    }

    /**
     * 启动时报一次术语表体检（v2.3.0）。
     *
     * <p>术语表是玩家长期往里加词的**用户资产**，而两类错误是完全静默的：写反了
     * （{@code 黑曜石=obby}，两个方向含义都反）、格式错（漏等号 / 全角等号，整条被渲染丢掉）。
     * 玩家只会觉得「加了词没用」，所以这里主动说一次。
     *
     * <p>判定逻辑全在 {@link GlossaryAudit} 里（纯逻辑、离线自检覆盖），本类只负责打印 ——
     * 装配类不在自检覆盖范围内（见 RELEASING §6），所以别在这里加判断。
     */
    private void reportGlossaryFindings() {
        List<GlossaryAudit.Finding> findings = GlossaryAudit.audit(config.glossary);
        String summary = GlossaryAudit.summarize(findings);
        if (summary == null) {
            return;
        }
        feedback.hint(summary);
        for (String line : GlossaryAudit.detailLines(findings, GlossaryAudit.STARTUP_DETAIL_LIMIT)) {
            feedback.hint(line);
        }
    }
}
