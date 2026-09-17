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
 * <p>文本清洗沿用同一份实现（{@link LangUtils#sanitizeOneLine}）：接口返回的错误正文、
 * 第三方中转站返回的模型名都是**不可信输入**，万一某个调用方忘了清洗，{@code §} 会变成
 * 颜色代码、换行会把一条提示拆成好几行。防线放在唯一出口最划算（v2.2.2 的结论）。
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

    private static String clean(String text) {
        return LangUtils.sanitizeOneLine(text);
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
