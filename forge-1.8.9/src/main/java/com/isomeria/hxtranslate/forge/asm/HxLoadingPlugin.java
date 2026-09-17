package com.isomeria.hxtranslate.forge.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Forge 1.8.9 的加载插件（核心插件入口）。
 *
 * <p><b>为什么 1.8.9 线必须有这个东西</b>：这个模组的核心功能之一是「拦下自己发的中文、
 * 翻译成英文再发出去」。Fabric 线用的是 {@code ClientSendMessageEvents.ALLOW_CHAT/ALLOW_COMMAND}，
 * 而 <b>Forge 1.8.9 根本没有对应的事件</b> —— {@code ClientChatEvent} 是 1.11 才加入的
 * （1.9 / 1.9.4 / 1.10.x / 1.10.2 都没有），服务端的 {@code ServerChatEvent} 在 Hypixel
 * 这种远程服务器上永远不会触发。所以 1.8.9 上唯一可行的办法是注入字节码。
 *
 * <p>注入点只有一个：{@code EntityPlayerSP.sendChatMessage(String)}。所有发送都经过它 ——
 * 玩家在聊天框敲回车（{@code GuiChat} → {@code GuiScreen.sendChatMessage} → 这里）、
 * {@code /shout} 这类命令（走客户端命令处理器，没被认领就原样落到这里），以及其它模组
 * 程序化调用。所以只注入这一个方法就能覆盖全部发送路径。
 *
 * <p>注入的语义与 Fabric 线的 {@code ALLOW_CHAT} 完全一致：在方法头问一句
 * 「这次要不要拦下来」，要拦就直接 {@code RETURN}（原版一行都不会执行），
 * 翻译完成后由模组自己再调一次同方法重发，此时闸门放行、走原版逻辑。
 */
@IFMLLoadingPlugin.Name("server_chat_translator")
@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.TransformerExclusions({"com.isomeria.hxtranslate"})
public final class HxLoadingPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {"com.isomeria.hxtranslate.forge.asm.HxTransformer"};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // 不需要 FML 的加载数据
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
