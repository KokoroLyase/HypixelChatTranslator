package com.isomeria.hxtranslate;

/**
 * 全模组统一的日志出口（**加载器无关**）。
 *
 * <p>为什么是自己写的门面，而不是直接用某个日志库：两条线能用的日志库不一样 ——
 * Fabric(26.3) 侧是 slf4j，Forge(1.8.9) 侧只有 log4j。而共享层（{@code src/shared/java}）
 * 由两个构建**编译同一份文件**，所以它不能依赖其中任何一个，否则另一条线在编译期就找不到类。
 * 真正的日志实现由各加载器的装配层通过 {@link #setSink} 注入。
 *
 * <p>接口刻意抄 slf4j 的常用子集（{@code info/warn/error} + {@code {}} 占位符），
 * 这样原来那一批 {@code Log.LOGGER.info("...{}", v)} 调用处**一行都不用改**。
 *
 * <p><b>为什么单独抽一个类</b>（v2.1.0 的教训）：以前各处写入口类的
 * {@code HxTranslateClient.LOGGER}，而入口类实现了 {@code ClientModInitializer}，
 * 于是「打一行日志」会连带加载 Fabric 的加载器 API。真出过事：拿不到那个类的环境里
 * 抛 {@link NoClassDefFoundError} —— 那是 {@link Error}，不是 {@link Exception}，
 * 所有 {@code catch (RuntimeException)} 都接不住，工作线程直接死掉，
 * **回调永远不执行、消息静默消失、玩家看到「⏳ 翻译中…」再没下文**。
 *
 * <p>所以这里额外上两道保险：{@link #emit} 用 {@code catch (Throwable)} 包住整个出口，
 * 而且默认 sink 是彻底静默的（离线自检、sink 尚未注入时都不会有任何副作用）。
 * **以后不要再用入口类的 LOGGER。**
 */
public final class Log {

    /** 日志级别。 */
    public enum Level {
        INFO, WARN, ERROR
    }

    /** 各加载器装配层提供的真实日志实现；共享层只认这个接口。 */
    public interface Sink {

        /**
         * @param message 已经替换过 {@code {}} 占位符的成品文本（不再含占位符）
         * @param error   随日志一起抛出的异常；没有则为 {@code null}
         */
        void log(Level level, String message, Throwable error);
    }

    /** 默认实现：什么都不做。sink 没注入时（离线自检、单元测试）也绝不能有副作用。 */
    private static final Sink SILENT = new Sink() {
        @Override
        public void log(Level level, String message, Throwable error) {
        }
    };

    private static final Object[] NO_ARGS = new Object[0];

    private static volatile Sink sink = SILENT;

    /** 与 slf4j 用法一致的出口：{@code Log.LOGGER.info("x {}", v)}。 */
    public static final Logger LOGGER = new Logger();

    private Log() {
    }

    /**
     * 由各加载器的装配层调用：Fabric 传 slf4j 实现，Forge 1.8.9 传 log4j 实现。
     *
     * @param newSink 新的日志实现；传 {@code null} 表示恢复静默
     */
    public static void setSink(Sink newSink) {
        sink = newSink == null ? SILENT : newSink;
    }

    /** 吞掉异常时统一用它，避免各处写 {@code e.toString()} 格式不一。 */
    public static void warn(String message, Throwable error) {
        emit(Level.WARN, message, new Object[] {error});
    }

    /** slf4j 常用子集；占位符只认 {@code {}}。 */
    public static final class Logger {

        public void info(String format, Object... args) {
            emit(Level.INFO, format, args);
        }

        public void warn(String format, Object... args) {
            emit(Level.WARN, format, args);
        }

        public void error(String format, Object... args) {
            emit(Level.ERROR, format, args);
        }
    }

    private static void emit(Level level, String format, Object[] args) {
        Sink current = sink;
        // 静默 sink（离线自检里的绝大多数日志）连字符串都不用拼
        if (current == SILENT) {
            return;
        }
        try {
            Object[] values = args == null ? NO_ARGS : args;
            int slots = countSlots(format);
            Throwable error = null;
            // slf4j 的规矩：参数比占位符多、且最后一个参数是异常时，它当作异常而不是文本
            if (values.length > slots && values[values.length - 1] instanceof Throwable) {
                error = (Throwable) values[values.length - 1];
            }
            current.log(level, format(format, values, slots, error), error);
        } catch (Throwable ignored) {
            // 日志出口坏了绝不能影响翻译：v2.1.0 的「静默丢消息」就是打日志把工作线程打死的
        }
    }

    /** 数一数格式串里有几个 {@code {}} 占位符。 */
    private static int countSlots(String format) {
        if (format == null) {
            return 0;
        }
        int slots = 0;
        int from = 0;
        while (true) {
            int at = format.indexOf("{}", from);
            if (at < 0) {
                return slots;
            }
            slots++;
            from = at + 2;
        }
    }

    /**
     * 把参数填进占位符。
     *
     * <p>占位符比参数多时，多出来的 {@code {}} 原样保留（slf4j 也是这样）；
     * 参数比占位符多时，多出来的接在末尾，避免悄悄丢掉排错信息。
     */
    private static String format(String format, Object[] values, int slots, Throwable error) {
        if (format == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(format.length() + 32);
        int cursor = 0;
        int used = 0;
        while (used < slots && used < values.length) {
            int at = format.indexOf("{}", cursor);
            if (at < 0) {
                break;
            }
            out.append(format, cursor, at).append(stringify(values[used]));
            cursor = at + 2;
            used++;
        }
        out.append(format, cursor, format.length());
        for (int extra = slots; extra < values.length; extra++) {
            if (error != null && extra == values.length - 1) {
                continue;
            }
            out.append(' ').append(stringify(values[extra]));
        }
        return out.toString();
    }

    /** {@code toString()} 本身抛异常也不能把日志出口带崩。 */
    private static String stringify(Object value) {
        try {
            return String.valueOf(value);
        } catch (Throwable t) {
            return "<toString 抛异常>";
        }
    }
}
