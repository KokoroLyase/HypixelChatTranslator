package com.isomeria.hxtranslate.forge;

import com.isomeria.hxtranslate.Log;
import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.chat.FeedbackPort;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.GlossaryAudit;
import com.isomeria.hxtranslate.core.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Server Chat Translator的 MC 1.8.9 + Forge 入口（纯客户端）。
 *
 * <p>这里只做「装配」，与 Fabric 线的 {@code HxTranslateClient} 一一对应：
 * 注入日志出口、注入配置目录、造出翻译逻辑需要的几个实现、把事件挂上、把命令与按键接好。
 * 决策逻辑在共享层的 {@code ChatTranslator} 里，两条线编译的是同一份文件。
 *
 * <p>三条平台差异（都是 1.8.9 的既成事实，不是取舍）：
 * <ul>
 *   <li>事件总线：{@code MinecraftForge.EVENT_BUS.register(this)}，注解是
 *       {@code net.minecraftforge.fml.common.eventhandler.SubscribeEvent}
 *       （1.8.9 里 FML 总线与 Forge 总线是同一个对象）；</li>
 *   <li>开关按键：用 {@code InputEvent.KeyInputEvent} + {@code KeyBinding.isPressed()}
 *       （Forge 自己的文档就说 isPressed 该用在按键事件里、isKeyDown 用在 tick 里）；</li>
 *   <li>命令：1.8.9 没有 Brigadier，注册 {@code ICommand} 到 {@code ClientCommandHandler}。</li>
 * </ul>
 *
 * <p>翻译线程都是守护线程（见 {@code TranslationService} 的线程工厂），所以即使没有
 * 「客户端退出」回调也不会拦住 JVM 退出；1.8.9 也确实没有可用的客户端停止事件。
 */
@Mod(modid = HxTranslateForge.MODID,
        name = "Server Chat Translator",
        version = HxVersion.VERSION,
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.8.9]")
public final class HxTranslateForge {

    public static final String MODID = "server_chat_translator";

    /** 默认按 F6 切换翻译开关，可在「按键设置 → 多人游戏」里改。 */
    private static final int DEFAULT_TOGGLE_KEY = Keyboard.KEY_F6;

    private static final Logger LOGGER = LogManager.getLogger(MODID);

    static {
        // 把共享层的日志出口接到 log4j 上。Fabric 线接的是 slf4j；
        // 1.8.9 只有 log4j（2.0-beta9），所以共享层不能直接依赖任何一个。
        Log.setSink(new Log.Sink() {
            @Override
            public void log(Log.Level level, String message, Throwable error) {
                String text = error == null ? message : message + " | " + error;
                switch (level) {
                    case INFO:
                        LOGGER.info(text);
                        break;
                    case WARN:
                        LOGGER.warn(text);
                        break;
                    default:
                        LOGGER.error(text);
                        break;
                }
            }
        });
    }

    private TranslatorConfig config;
    private TranslationService service;
    private ChatTranslator translator;
    private FeedbackPort feedback;
    private KeyBinding toggleKey;

    private boolean startupNoticeShown;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // 共享层不知道配置目录在哪（它不能 import 任何加载器 API），由这里注入。
        // 必须赶在 load() 之前：否则会退回到相对路径，玩家的配置会被写到别的地方。
        // 1.8.9 的 getConfigDir() 返回 File，两条线的实际目录都是 .minecraft/config，
        // 所以 Fabric 与 Forge 共用同一个配置文件（玩家换版本不用重配）。
        TranslatorConfig.setConfigDir(Loader.instance().getConfigDir().toPath());
        config = TranslatorConfig.load();
        service = new TranslationService(config);
        feedback = new ForgeFeedback();

        ForgeClient client = new ForgeClient();
        translator = new ChatTranslator(config, service, client, feedback);
        client.bind(translator);
        client.register();

        // 本类自己也要挂到总线上：开关按键与启动提示都在这里
        MinecraftForge.EVENT_BUS.register(this);

        toggleKey = new KeyBinding("key.server_chat_translator.toggle", DEFAULT_TOGGLE_KEY,
                "key.categories.multiplayer");
        ClientRegistry.registerKeyBinding(toggleKey);

        ClientCommandHandler.instance.registerCommand(
                new ForgeChatCommand(config, service, translator, feedback));

        if (!config.hasApiKey()) {
            LOGGER.warn("尚未配置 DeepSeek API Key，翻译功能不可用。配置文件: {}",
                    TranslatorConfig.configPath());
        }
        LOGGER.info("Server Chat Translator已加载 (MC 1.8.9 / Forge / DeepSeek {})", config.model);
    }

    /** 开关按键。 */
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (toggleKey == null || config == null || !toggleKey.isPressed()) {
            return;
        }
        config.enabled = !config.enabled;
        config.save();
        feedback.info(config.enabled ? "§a聊天翻译已开启" : "§c聊天翻译已关闭");
    }

    /** 进世界之后报一次启动提示（和 Fabric 线一样，等玩家真进世界了再说）。 */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || startupNoticeShown) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.thePlayer == null) {
            return;
        }
        startupNoticeShown = true;
        showStartupNotice();
    }

    private void showStartupNotice() {
        feedback.info("§8[§bsct§8] §7Server Chat Translator已就绪 §8(" + (config.enabled ? "§a开" : "§c关") + "§8)");
        // 配置读不出来时必须说清楚：否则玩家看到「未配置 API Key」会以为模组坏了
        if (config.loadWarning() != null) {
            feedback.error(config.loadWarning());
        }
        if (!config.hasApiKey()) {
            feedback.error("未配置 DeepSeek API Key！请执行 §f/server_chat_translator key <你的Key> §c或编辑配置文件。");
            feedback.hint("配置文件: " + TranslatorConfig.configPath());
        } else {
            feedback.hint("F6 开关翻译，/server_chat_translator status 查看状态，/server_chat_translator debug on 排错");
        }
        reportGlossaryFindings();
    }

    /** 启动时报一次术语表体检；判定逻辑全在共享层的 GlossaryAudit 里。 */
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
