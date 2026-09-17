package com.isomeria.hxtranslate.util;

import java.util.Arrays;
import com.isomeria.hxtranslate.config.TranslatorConfig;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析命令，拆出「命令头」和「需要翻译的正文」。
 *
 * <p>纯字符串处理，不依赖 Minecraft，方便离线测试。
 * 注意：传入的命令串<b>不含</b>前导斜杠（原版 ChatScreen 就是这样调用 sendCommand 的）。
 *
 * <p>识别分三层，任何一层命中就返回：
 * <ol>
 *   <li><b>显式名单</b>（{@code translateCommandArgs}）：{@code /shout}、{@code /pc}、{@code /msg}
 *       这类一眼就知道正文在哪里的命令；</li>
 *   <li><b>二义性命令</b>（{@code guardedCommands}）：{@code /party invite Steve} 是管理，
 *       {@code /party chat 大家好} 是发消息，需要看第一个参数才能决定；</li>
 *   <li><b>未知命令兜底</b>：Hypixel 以后新加的聊天命令，只要正文明显是一句中文就翻译，
 *       但会排除 {@code protectedCommands}（参数是玩家名/物品名的命令）。</li>
 * </ol>
 */
public final class CommandMessage {

    /** {@code /party chat}、{@code /guild chat} 这类「子命令 + 正文」的写法。 */
    private static final List<String> CHAT_SUBCOMMANDS = Arrays.asList("chat");

    /** 兜底翻译的门槛：正文至少这么多汉字才算「明显是一句话」，避免把玩家名当消息翻译。 */
    private static final int SENTENCE_MIN_HAN = 8;
    private static final int SENTENCE_MIN_HAN_WITH_SPACE = 5;

    /**
     * 永远不翻译的命令：本模组自己的客户端命令。
     *
     * <p>硬编码而不是放进配置，是因为这些命令根本不该发到服务器。
     * 否则「未知命令兜底」会把它当成普通命令：
     * {@code /server_chat_translator test 这是一句很长的中文} 会被取消、正文被翻译、
     * 再当成服务器命令发出去 —— 命令没执行，还往服务器发了垃圾。
     */
    private static final List<String> ALWAYS_PROTECTED = Arrays.asList("translator");

    /**
     * head 例如 {@code "msg Player "}，message 例如 {@code "你好"}。
     *
     * <p>Java 8 没有 record，写成普通不可变类，访问器名字保持 {@code head()} / {@code message()}。
     */
    public static final class Split {

        private final String head;
        private final String message;

        public Split(String head, String message) {
            this.head = head;
            this.message = message;
        }

        public String head() {
            return head;
        }

        public String message() {
            return message;
        }
    }

    private CommandMessage() {
    }

    /** 总入口：返回需要翻译的正文，或 null 表示这条命令不用管。 */
    public static Split resolve(String command, TranslatorConfig config) {
        if (command == null || LangUtils.isBlank(command)) {
            return null;
        }
        if (isAlwaysProtected(command)) {
            return null;
        }

        Split split = split(command, config.translateCommandArgs);
        if (split != null) {
            return split;
        }

        split = splitGuarded(command, config.guardedCommands, config.commandManagementKeywords);
        if (split != null) {
            return split;
        }

        if (!config.translateUnknownCommands || isProtected(command, config.protectedCommands)) {
            return null;
        }
        Split tail = splitTail(command);
        if (tail != null && looksLikeSentence(tail.message())) {
            return tail;
        }
        return null;
    }

    /** 命令名是否属于「本模组自己的命令」。 */
    public static boolean isAlwaysProtected(String command) {
        if (command == null || LangUtils.isBlank(command)) {
            return false;
        }
        int firstSpace = command.indexOf(' ');
        String name = (firstSpace < 0 ? command : command.substring(0, firstSpace)).toLowerCase(Locale.ROOT);
        return ALWAYS_PROTECTED.contains(name);
    }

    /**
     * 第一层：显式名单。
     *
     * @param table 命令名（小写）-> 正文之前还有几个参数
     */
    public static Split split(String command, Map<String, Integer> table) {
        if (command == null || LangUtils.isBlank(command) || table == null || table.isEmpty()) {
            return null;
        }

        int firstSpace = command.indexOf(' ');
        if (firstSpace <= 0) {
            return null;
        }

        String name = command.substring(0, firstSpace).toLowerCase(Locale.ROOT);
        Integer extraArgs = table.get(name);
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
        String message = LangUtils.strip(command.substring(position));
        if (message.isEmpty()) {
            return null;
        }
        return new Split(head, message);
    }

    /**
     * 第二层：管理/聊天二义性命令（{@code /p}、{@code /party}、{@code /g}、{@code /guild}）。
     *
     * <ul>
     *   <li>第一个参数是 {@code chat} → 后面是正文，例如 {@code /party chat 大家好}；</li>
     *   <li>第一个参数是管理子命令（invite/kick/…）→ 不是聊天，返回 null；</li>
     *   <li>其它情况 → 按 {@code /party <正文>} 处理（Hypixel 允许这种简写时也能翻译）。</li>
     * </ul>
     */
    public static Split splitGuarded(String command, List<String> guardedCommands, List<String> managementKeywords) {
        if (command == null || LangUtils.isBlank(command) || guardedCommands == null || guardedCommands.isEmpty()) {
            return null;
        }
        int firstSpace = command.indexOf(' ');
        if (firstSpace <= 0) {
            return null;
        }
        String name = command.substring(0, firstSpace).toLowerCase(Locale.ROOT);
        if (!guardedCommands.contains(name)) {
            return null;
        }

        String afterName = command.substring(firstSpace + 1);
        String tail = LangUtils.stripLeading(afterName);
        if (tail.isEmpty()) {
            return null;
        }
        int leadingSpaces = afterName.length() - tail.length();

        int tokenEnd = tail.indexOf(' ');
        String firstArg = (tokenEnd < 0 ? tail : tail.substring(0, tokenEnd)).toLowerCase(Locale.ROOT);

        if (CHAT_SUBCOMMANDS.contains(firstArg)) {
            if (tokenEnd < 0) {
                return null; // 只有 "/party chat"，没有正文
            }
            String message = LangUtils.strip(tail.substring(tokenEnd + 1));
            if (message.isEmpty()) {
                return null;
            }
            int headEnd = firstSpace + 1 + leadingSpaces + tokenEnd + 1;
            return new Split(command.substring(0, headEnd), message);
        }

        if (managementKeywords != null && managementKeywords.contains(firstArg)) {
            return null;
        }
        return new Split(command.substring(0, firstSpace + 1), tail);
    }

    /** 第三层：未知命令，先把命令名和后面的内容拆开。 */
    public static Split splitTail(String command) {
        if (command == null || LangUtils.isBlank(command)) {
            return null;
        }
        int firstSpace = command.indexOf(' ');
        if (firstSpace <= 0 || firstSpace + 1 >= command.length()) {
            return null;
        }
        String message = LangUtils.strip(command.substring(firstSpace + 1));
        if (message.isEmpty()) {
            return null;
        }
        return new Split(command.substring(0, firstSpace + 1), message);
    }

    public static boolean isProtected(String command, List<String> protectedCommands) {
        if (command == null || protectedCommands == null || protectedCommands.isEmpty()) {
            return true;
        }
        int firstSpace = command.indexOf(' ');
        String name = (firstSpace < 0 ? command : command.substring(0, firstSpace)).toLowerCase(Locale.ROOT);
        return protectedCommands.contains(name);
    }

    /**
     * 这句「正文」看起来是不是一句真正的话，而不是玩家名 / 物品名之类的参数。
     *
     * <p>刻意保守：短的单个词（例如 {@code /tp 小明}）不会被当成消息。
     */
    public static boolean looksLikeSentence(String tail) {
        if (tail == null || LangUtils.isBlank(tail)) {
            return false;
        }
        if (!LangUtils.containsHan(tail)) {
            return false;
        }
        if (LangUtils.containsCjkPunctuation(tail)) {
            return true;
        }
        int han = LangUtils.countHan(tail);
        if (han >= SENTENCE_MIN_HAN) {
            return true;
        }
        return han >= SENTENCE_MIN_HAN_WITH_SPACE && tail.indexOf(' ') >= 0;
    }
}
