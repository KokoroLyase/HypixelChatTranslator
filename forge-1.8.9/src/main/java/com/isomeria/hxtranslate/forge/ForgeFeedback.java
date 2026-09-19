package com.isomeria.hxtranslate.forge;

import com.isomeria.hxtranslate.chat.ChatTranslator;
import com.isomeria.hxtranslate.chat.FeedbackPort;
import com.isomeria.hxtranslate.util.LangUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

/**
 * {@link FeedbackPort} 的 Forge 1.8.9 实现：往聊天栏 / 物品栏上方输出本模组自己的提示。
 *
 * <p>所有输出都切回客户端主线程（{@code Minecraft.addScheduledTask}），因此可以在翻译线程里调用。
 * 这是 1.8.9 线里**唯一**接触聊天渲染的地方。
 *
 * <p>与 Fabric 线逐条对应：
 * <ul>
 *   <li>{@code Hud.getChat().addClientSystemMessage(...)} → {@code ingameGUI.getChatGUI().printChatMessage(...)}；</li>
 *   <li>{@code Hud.setOverlayMessage(...)}（原版 60 tick 自动消失）→
 *       {@code ingameGUI.setRecordPlaying(text, false)} —— 同样是 60 tick（3 秒）后自动消失，
 *       1.8.9 没有可以改这个时长的公开 API，所以「⏳ 翻译中…」的存活时间与 Fabric 线一致。</li>
 * </ul>
 *
 * <p>文本版式沿用同一份实现（{@link LangUtils#singleLineLayout}）：换行会把一条提示拆成好几行。
 * <b>v3.0.4 起不再剥 {@code §}</b>（与 Fabric 线的 {@code GameFeedback} 一致）：
 * 不可信内容（接口返回的错误正文、第三方中转站返回的模型名）在 {@code DeepSeekClient}
 * 里已经清洗过，这里再对整行剥一次只会把调用方自己拼的颜色也剥掉。
 */
public final class ForgeFeedback implements FeedbackPort {

    @Override
    public void info(String text) {
        send(new ChatComponentText(clean(text)));
    }

    @Override
    public void hint(String text) {
        send(new ChatComponentText(ChatTranslator.CHAT_PREFIX + " §7" + clean(text)));
    }

    @Override
    public void error(String text) {
        send(new ChatComponentText(ChatTranslator.CHAT_PREFIX + " §c" + clean(text)));
    }

    @Override
    public void success(String text) {
        send(new ChatComponentText(ChatTranslator.CHAT_PREFIX + " §a" + clean(text)));
    }

    @Override
    public void actionBar(String text) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }
        final String message = clean(text);
        minecraft.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                GuiIngame ingame = minecraft.ingameGUI;
                if (ingame == null) {
                    return;
                }
                // 第二个参数是 isPlaying：false = 当普通的一行文字显示，不是「正在播放唱片」的样式
                ingame.setRecordPlaying(message, false);
            }
        });
    }

    /**
     * 合并显示（v3.1.0）：复制原消息组件、把译文后缀追加成 sibling，作为一个整体显示。
     *
     * <p>与 Fabric 线的 {@code GameFeedback#showMergedIncoming} 逐条对应：
     * 必须**复制再追加**而不是拍平重拼 —— 原组件带着服务器的样式（1.8.9 的 § 已由
     * Forge 渲染进 ChatStyle），重拼会把它们全丢掉。
     * {@code createCopy()} / {@code appendSibling} 都是 1.8.9 {@code IChatComponent} 的原生 API。
     */
    @Override
    public void showMergedIncoming(Object originalComponent, String suffix) {
        if (!(originalComponent instanceof IChatComponent)) {
            // 不是本线的组件类型（理论上不会发生）：退回纯后缀，绝不静默吞掉译文
            info(suffix);
            return;
        }
        send(merge((IChatComponent) originalComponent, suffix));
    }

    /** 1.8.9 的合并实现：原组件复制后追加一行译文后缀。 */
    private static IChatComponent merge(IChatComponent original, String suffix) {
        return original.createCopy().appendSibling(new ChatComponentText(clean(suffix)));
    }

    /** 把被 MERGE 扣住的原消息原样放行（超时 / 失败 / 无可译内容时调用）。 */
    @Override
    public void showOriginalIncoming(Object originalComponent) {
        if (!(originalComponent instanceof IChatComponent)) {
            return;
        }
        // 复制一份：共享层还持有这个引用，聊天栏不该共享同一实例
        send(((IChatComponent) originalComponent).createCopy());
    }

    /**
     * 兜底版式：压成一行，但**保留** {@code §} 格式代码。
     *
     * <p>与 Fabric 线的 {@code GameFeedback#clean} 逐条对应，理由见那边与
     * {@link LangUtils#singleLineLayout} 的 javadoc：整行清洗会把调用方自己拼的颜色
     * （前缀、{@code §f}/{@code §c} 高亮）一起剥掉，而不可信内容在
     * {@code DeepSeekClient} 里已经清过了。
     */
    private static String clean(String text) {
        return LangUtils.singleLineLayout(text);
    }

    private static void send(final IChatComponent component) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }
        minecraft.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                GuiIngame ingame = minecraft.ingameGUI;
                if (ingame == null || ingame.getChatGUI() == null) {
                    return;
                }
                ingame.getChatGUI().printChatMessage(component);
            }
        });
    }
}
