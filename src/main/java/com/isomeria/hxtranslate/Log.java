package com.isomeria.hxtranslate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全模组统一的日志出口。
 *
 * <p>单独抽出来是为了**斩断对游戏/加载器 API 的加载依赖**：以前各处写
 * {@code HxTranslateClient.LOGGER}，而 {@code HxTranslateClient} 实现了
 * {@code net.fabricmc.api.ClientModInitializer}，于是「打一行日志」这个动作会连带加载
 * Fabric 的加载器 API。真出过事：{@code TranslationService} 的 debug 分支引用它，
 * 在拿不到 Fabric 类的环境（离线自检）里抛 {@link NoClassDefFoundError} ——
 * 那是 {@link Error}，不是 {@link Exception}，所有 {@code catch (RuntimeException)} 都接不住，
 * 工作线程直接死掉，**回调永远不执行、消息静默消失、玩家看到「⏳ 翻译中…」再没下文**。
 *
 * <p>换成这个类之后，日志只依赖 slf4j（生产环境里由游戏提供实现），
 * 与游戏类、Fabric API 再无加载关系。**以后不要再用入口类的 LOGGER**。
 */
public final class Log {

    public static final Logger LOGGER = LoggerFactory.getLogger("hxtranslate");

    private Log() {
    }

    /** 吞掉异常时统一用它，避免各处写 {@code e.toString()} 格式不一。 */
    public static void warn(String message, Throwable error) {
        LOGGER.warn(message, error);
    }
}
