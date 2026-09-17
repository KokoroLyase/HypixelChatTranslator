package com.isomeria.hxtranslate.forge;

import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.chat.FeedbackPort;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.core.DeepSeekClient;
import com.isomeria.hxtranslate.core.Direction;
import com.isomeria.hxtranslate.core.GlossaryAudit;
import com.isomeria.hxtranslate.core.TranslationService;
import com.isomeria.hxtranslate.util.LangUtils;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 客户端命令 {@code /server_chat_translator}（别名 {@code /hxt}）的 1.8.9 实现。
 *
 * <p>1.8.9 <b>没有 Brigadier</b>（那是 1.13 才进的），所以命令要写成
 * {@link ICommand} 并注册到 {@code ClientCommandHandler}。这里把 Fabric 线
 * {@code TranslateCommand} 的全部子命令按同一套语义重写了一遍，参数靠手工解析。
 *
 * <p>注册成**客户端**命令有两个好处：命令不会发到 Hypixel 服务器；而且客户端命令
 * 优先于服务端同名命令（Forge 在 {@code GuiScreen.sendChatMessage} 里先问客户端命令表，
 * 认领了就直接返回）。
 */
public final class ForgeChatCommand extends CommandBase {

    /** 一次命令里最多列几条术语表明细（再多就把聊天记录顶掉了）。 */
    private static final int GLOSSARY_DETAIL_LIMIT = 12;

    private final TranslatorConfig config;
    private final TranslationService service;
    private final ChatTranslator translator;
    private final FeedbackPort feedback;

    public ForgeChatCommand(TranslatorConfig config, TranslationService service,
                            ChatTranslator translator, FeedbackPort feedback) {
        this.config = config;
        this.service = service;
        this.translator = translator;
        this.feedback = feedback;
    }

    @Override
    public String getCommandName() {
        return "translator";
    }

    @Override
    public List<String> getCommandAliases() {
        // v3.0.0 起只保留 /translator：旧的 /server_chat_translator 与 /hxt 不再注册（用户已确认「只换不留」）。
        return new ArrayList<String>();
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/server_chat_translator [status|on|off|incoming|outgoing|key|models|glossary|debug|reload|test]";
    }

    /** 客户端命令不需要权限等级（也避免被当成 op 命令而拒绝执行）。 */
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length != 1) {
            return null;
        }
        return getListOfStringsMatchingLastWord(args,
                "status", "on", "off", "incoming", "outgoing", "singleplayer", "key",
                "models", "glossary", "debug", "reload", "test");
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();

        if (args.length == 0 || "status".equals(sub)) {
            status(sender);
        } else if ("on".equals(sub)) {
            config.enabled = true;
            config.save();
            reply(sender, "§a聊天翻译已开启");
        } else if ("off".equals(sub)) {
            config.enabled = false;
            config.save();
            reply(sender, "§c聊天翻译已关闭");
        } else if ("incoming".equals(sub)) {
            setToggle(sender, args, true);
        } else if ("outgoing".equals(sub)) {
            setToggle(sender, args, false);
        } else if ("singleplayer".equals(sub)) {
            setSingleplayer(sender, args);
        } else if ("debug".equals(sub)) {
            setDebug(sender, args);
        } else if ("key".equals(sub)) {
            setKey(sender, args);
        } else if ("models".equals(sub)) {
            listModels(sender);
        } else if ("glossary".equals(sub)) {
            reportGlossary(sender);
        } else if ("reload".equals(sub)) {
            reload(sender);
        } else if ("test".equals(sub)) {
            runTest(sender, args);
        } else {
            reply(sender, "§c未知子命令。用法: " + getCommandUsage(sender));
        }
    }

    // ------------------------------------------------------------------

    private void setToggle(ICommandSender sender, String[] args, boolean incoming) {
        if (args.length < 2 || (!"on".equalsIgnoreCase(args[1]) && !"off".equalsIgnoreCase(args[1]))) {
            reply(sender, "§c用法: /server_chat_translator " + (incoming ? "incoming" : "outgoing") + " on|off");
            return;
        }
        boolean on = "on".equalsIgnoreCase(args[1]);
        if (incoming) {
            config.translateIncoming = on;
            config.save();
            reply(sender, on ? "§a已开启：收到的英文消息自动翻译" : "§c已关闭：收到的消息不再翻译");
        } else {
            config.translateOutgoing = on;
            config.save();
            // 这条只管「直接打出来的聊天」：命令正文由 translateCommandMessages 单独控制
            reply(sender, on
                    ? "§a已开启：中文自动翻译成英文发送"
                    : "§c已关闭：直接打出的中文不再翻译（命令正文见 §ftranslateCommandMessages§c）");
        }
    }

    /**
     * 单人（单机）世界里要不要翻译（v3.0.0）。
     *
     * <p>默认关。玩家在单机里发现「怎么不翻译」时，{@code status} 与 debug 输出都会指向这条命令，
     * 所以它的用法提示必须写全（含「多人服不受影响」，否则玩家会担心自己把联机也关掉了）。
     */
    private void setSingleplayer(ICommandSender sender, String[] args) {
        if (args.length < 2 || (!"on".equalsIgnoreCase(args[1]) && !"off".equalsIgnoreCase(args[1]))) {
            reply(sender, "§c用法: /server_chat_translator singleplayer on|off");
            return;
        }
        boolean on = "on".equalsIgnoreCase(args[1]);
        config.translateInSingleplayer = on;
        config.save();
        reply(sender, on
                ? "§a已开启：单人世界的消息也会翻译"
                : "§c已关闭：单人世界不翻译（多人服不受影响）");
    }

    private void setDebug(ICommandSender sender, String[] args) {
        if (args.length < 2 || (!"on".equalsIgnoreCase(args[1]) && !"off".equalsIgnoreCase(args[1]))) {
            reply(sender, "§c用法: /server_chat_translator debug on|off");
            return;
        }
        boolean on = "on".equalsIgnoreCase(args[1]);
        config.debugLog = on;
        config.save();
        if (on) {
            translator.resetCounters();
            reply(sender, "§a调试模式已开启：会在聊天栏打印每条消息是「翻译」还是「跳过（原因）」");
        } else {
            reply(sender, "§c调试模式已关闭");
        }
    }

    private void setKey(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, "§c用法: /server_chat_translator key <你的Key>");
            return;
        }
        StringBuilder value = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) {
                value.append(' ');
            }
            value.append(args[i]);
        }
        String key = value.toString().trim();
        config.apiKey = key;
        config.save();
        // 刚换了 Key，之前因为网络/限流攒下的熔断不该继续挡着
        service.resetCircuit();
        reply(sender, "§a已保存 DeepSeek API Key（长度 " + key.length() + "，出于安全不回显内容）");
    }

    /** 查询模型列表要发网络请求，必须放到后台线程，否则会卡住游戏。 */
    private void listModels(ICommandSender sender) {
        reply(sender, "§7正在查询 DeepSeek 可用模型…");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                DeepSeekClient.Result result = service.listModels();
                if (result.ok()) {
                    // Forge 线没有统一的「剥 §」出口（命令直接 addChatMessage），
                    // 所以这里只拼纯文本，和 Fabric 线的取舍一致。
                    feedback.success("可用模型: " + result.text() + " ｜ 当前使用: " + config.model);
                } else {
                    feedback.error("查询失败: " + result.error());
                }
            }
        }, "server_chat_translator-models");
        thread.setDaemon(true);
        thread.start();
    }

    /** 测试翻译要发网络请求，同样放后台线程。 */
    private void runTest(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, "§c用法: /server_chat_translator test <文本>");
            return;
        }
        StringBuilder value = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) {
                value.append(' ');
            }
            value.append(args[i]);
        }
        final String text = value.toString();
        reply(sender, "§7正在翻译: §f" + text);
        // 方向按内容判断，规则和实际收发时一致：含汉字 = 中→英，否则当作收到的英文
        final Direction direction = LangUtils.containsHan(text) ? Direction.OUTGOING : Direction.INCOMING;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                DeepSeekClient.Result result = service.translateBlocking(text, direction);
                if (result.ok()) {
                    feedback.success("测试译文（" + direction.label() + "）: §f" + result.text());
                } else {
                    feedback.error("测试失败: " + result.error());
                }
            }
        }, "server_chat_translator-test");
        thread.setDaemon(true);
        thread.start();
    }

    private void reload(ICommandSender sender) {
        config.reload();
        service.invalidateCache();
        service.resetRateLimit();
        // 重载往往是因为「刚换了 Key / 接口地址」，不该让之前攒下的熔断继续挡着
        service.resetCircuit();
        // 被停用的忽略正则只能靠这里恢复，否则玩家唯一的办法是重启游戏
        int revived = LangUtils.disabledRegexCount();
        LangUtils.resetRegexCircuit();
        String warning = config.loadWarning();
        String message = warning == null
                ? "§a配置已重新加载：缓存已清空、限流与熔断已复位"
                : "§c配置重新加载有问题：" + warning;
        if (revived > 0) {
            message = message + "§a（另有 " + revived + " 条被停用的忽略正则已恢复）";
        }
        String suspicious = GlossaryAudit.countsText(GlossaryAudit.audit(config.glossary));
        if (suspicious != null) {
            message = message + "§e（术语表体检：" + suspicious + "，输入 /server_chat_translator glossary 查看）";
        }
        reply(sender, message);
    }

    /** 术语表体检报告；判定逻辑全在共享层的 GlossaryAudit 里。 */
    private void reportGlossary(ICommandSender sender) {
        List<GlossaryAudit.Finding> findings = GlossaryAudit.audit(config.glossary);
        int total = config.glossary == null ? 0 : config.glossary.size();
        reply(sender, "§8===== §b术语表体检 §8=====");
        String counts = GlossaryAudit.countsText(findings);
        if (counts == null) {
            reply(sender, "§a未发现可疑条目§7（共 " + total + " 条）");
            return;
        }
        reply(sender, "§e发现 " + counts);
        for (String line : GlossaryAudit.detailLines(findings, GLOSSARY_DETAIL_LIMIT)) {
            reply(sender, "§7  - " + line);
        }
        reply(sender, "§7体检只做提示，不会自动改你的配置；改完术语表后 §f/server_chat_translator reload §7即可生效");
    }

    private void status(ICommandSender sender) {
        reply(sender, "§8===== §bServer Chat Translator §8=====");
        reply(sender, "§7总开关: " + onOff(config.enabled)
                + " §8| §7收到翻译: " + onOff(config.translateIncoming)
                + " §8| §7发送翻译: " + onOff(config.translateOutgoing)
                + " §8| §7调试: " + onOff(config.debugLog));
        reply(sender, "§7模型: §f" + config.model
                + " §8| §7思考模式: " + onOff(config.enableThinking)
                + " §8| §7API Key: " + (config.hasApiKey() ? "§a已配置" : "§c未配置")
                + " §8| §7术语表: §f" + (config.glossary == null ? 0 : config.glossary.size()) + " §7条");
        // 单人闸门的状态（v3.0.0）：文案由共享层生成，两条线的口径不会漂移。
        reply(sender, translator.singleplayerStatusLine());
        if (service.isCircuitOpen()) {
            reply(sender, "§c翻译服务连续失败，熔断中，还需 §f"
                    + service.circuitRemainingSeconds() + " §c秒");
        }
        int disabledRegexes = LangUtils.disabledRegexCount();
        if (disabledRegexes > 0) {
            reply(sender, "§e有 §f" + disabledRegexes
                    + " §e条 ignorePatterns 正则因匹配超时被停用（多半写了灾难性回溯的写法）。"
                    + "改掉它并 §f/server_chat_translator reload §e即可恢复。");
            for (String regex : LangUtils.disabledRegexes()) {
                reply(sender, "§8  - §7" + LangUtils.sanitizeOneLine(regex));
            }
        }
        String glossaryCounts = GlossaryAudit.countsText(GlossaryAudit.audit(config.glossary));
        if (glossaryCounts != null) {
            reply(sender, "§e术语表体检发现 " + glossaryCounts + "，输入 §f/server_chat_translator glossary §e查看并修改");
        }
        reply(sender, "§7输入长度上限: §f" + config.maxIncomingChars
                + "§7字符 §8| §7本分钟请求: §f" + service.usedRequestsThisMinute()
                + "§7/§f" + config.requestsPerMinute
                + " §8| §7进行中: §f" + service.pendingTranslations()
                + " §8| §7中文判定阈值: §f" + config.chineseRatioThreshold);
        reply(sender, translator.counters());
        reply(sender, translator.sendCounters());
    }

    private void reply(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }

    private static String onOff(boolean value) {
        return value ? "§a开" : "§c关";
    }
}
