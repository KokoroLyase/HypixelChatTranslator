package com.isomeria.hxtranslate.util;

import java.util.Locale;
import java.util.Map;

/**
 * 解析 /msg、/r 这类命令，拆出「命令头」和「需要翻译的正文」。
 *
 * <p>纯字符串处理，不依赖 Minecraft，方便离线测试。
 * 注意：传入的命令串<b>不含</b>前导斜杠（原版 ChatScreen 就是这样调用 sendCommand 的）。
 */
public final class CommandMessage {

    /** head 例如 {@code "msg Player "}，message 例如 {@code "你好"}。 */
    public record Split(String head, String message) {
    }

    private CommandMessage() {
    }

    /**
     * @param command              不含斜杠的命令，例如 {@code "msg Player 你好"}
     * @param translateCommandArgs 命令名（小写）-> 正文之前还有几个参数
     * @return 拆解结果；该命令不需要翻译、或没有正文时返回 null
     */
    public static Split split(String command, Map<String, Integer> translateCommandArgs) {
        if (command == null || command.isBlank() || translateCommandArgs == null) {
            return null;
        }

        int firstSpace = command.indexOf(' ');
        if (firstSpace <= 0) {
            return null;
        }

        String name = command.substring(0, firstSpace).toLowerCase(Locale.ROOT);
        Integer extraArgs = translateCommandArgs.get(name);
        if (extraArgs == null || extraArgs < 0) {
            return null;
        }

        // 跳过 1 个命令名 + extraArgs 个参数，定位正文起点。
        // 按 token 边界扫描，这样连续多个空格也不会把玩家名算进正文。
        int length = command.length();
        int tokensToSkip = 1 + extraArgs;
        int position = 0;
        for (int skipped = 0; skipped < tokensToSkip; skipped++) {
            while (position < length && command.charAt(position) != ' ') {
                position++;
            }
            if (position >= length) {
                // 参数不够，说明这条命令后面没有正文
                return null;
            }
            while (position < length && command.charAt(position) == ' ') {
                position++;
            }
        }

        if (position >= length) {
            return null;
        }

        String head = command.substring(0, position);
        String message = command.substring(position).strip();
        if (message.isEmpty()) {
            return null;
        }
        return new Split(head, message);
    }
}
