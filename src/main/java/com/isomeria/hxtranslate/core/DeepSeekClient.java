package com.isomeria.hxtranslate.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.isomeria.hxtranslate.HxTranslateClient;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.util.LangUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 极简 DeepSeek Chat Completions 客户端。
 *
 * <p>只用 {@link HttpURLConnection}（java.base 模块，任何 JRE 里都有），
 * 避免依赖 java.net.http 或第三方 HTTP 库。
 */
public final class DeepSeekClient {

    /** 翻译结果：ok 为 false 时 error 里是给用户看的失败原因。 */
    public record Result(boolean ok, String text, String error) {
        public static Result success(String text) {
            return new Result(true, text, null);
        }

        public static Result failure(String error) {
            return new Result(false, null, error);
        }
    }

    private final TranslatorConfig config;

    public DeepSeekClient(TranslatorConfig config) {
        this.config = config;
    }

    public Result translate(String text, Direction direction) {
        if (!config.hasApiKey()) {
            return Result.failure("未配置 API Key");
        }

        String endpoint = buildEndpoint(config.apiBaseUrl);
        String body = buildRequestBody(text, direction);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(config.httpTimeoutSeconds * 1000);
            connection.setReadTimeout(config.httpTimeoutSeconds * 1000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey.trim());

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int status = connection.getResponseCode();
            String response = readBody(connection, status);

            if (status < 200 || status >= 300) {
                return Result.failure(describeHttpError(status, response));
            }
            return parseResponse(response);
        } catch (IOException e) {
            HxTranslateClient.LOGGER.warn("翻译请求失败: {}", e.toString());
            return Result.failure("网络错误: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            HxTranslateClient.LOGGER.warn("翻译请求异常: {}", e.toString());
            return Result.failure("请求异常: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildEndpoint(String baseUrl) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private String buildRequestBody(String text, Direction direction) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.model);
        root.addProperty("stream", false);
        root.addProperty("temperature", config.temperature);
        root.addProperty("max_tokens", config.maxTokens);

        JsonArray messages = new JsonArray();
        messages.add(message("system", buildSystemPrompt(direction)));
        messages.add(message("user", text));
        root.add("messages", messages);

        return root.toString();
    }

    /**
     * 组装系统提示词。
     *
     * <p>翻译方向是「英→中」时，把配置里的术语表追加进去，要求模型把 obby / dia / u def 这类
     * Hypixel 缩写按含义翻成中文，而不是原样保留英文。
     */
    private String buildSystemPrompt(Direction direction) {
        String base = direction.toChinese() ? config.incomingSystemPrompt : config.outgoingSystemPrompt;
        if (!direction.toChinese() || config.glossary == null || config.glossary.isEmpty()) {
            return base;
        }
        return base + "\n\n"
                + "Minecraft / Hypixel / Bed Wars 术语与缩写对照表（必须按含义翻译成中文，"
                + "不要保留英文原样；同一缩写有多种含义时按上下文选择最合适的一个）：\n"
                + String.join("；", config.glossary);
    }

    private static JsonObject message(String role, String content) {
        JsonObject json = new JsonObject();
        json.addProperty("role", role);
        json.addProperty("content", content);
        return json;
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = (status >= 200 && status < 300)
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Result parseResponse(String response) {
        try {
            JsonElement parsed = JsonParser.parseString(response);
            if (!parsed.isJsonObject()) {
                return Result.failure("返回内容不是 JSON");
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return Result.failure("返回内容为空");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                return Result.failure("返回内容缺少 message.content");
            }
            String content = LangUtils.stripWrappingQuotes(message.get("content").getAsString());
            if (content.isEmpty()) {
                return Result.failure("模型返回了空翻译");
            }
            return Result.success(content);
        } catch (RuntimeException e) {
            return Result.failure("解析返回内容失败: " + e.getClass().getSimpleName());
        }
    }

    private String describeHttpError(int status, String response) {
        String detail = "";
        try {
            JsonElement parsed = JsonParser.parseString(response);
            if (parsed.isJsonObject()) {
                JsonObject error = parsed.getAsJsonObject().getAsJsonObject("error");
                if (error != null && error.has("message")) {
                    detail = error.get("message").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // 不是 JSON 就按原文截断显示
        }
        if (detail.isEmpty() && !response.isBlank()) {
            detail = response.length() > 160 ? response.substring(0, 160) + "..." : response;
        }

        return switch (status) {
            case 401 -> "API Key 无效或已过期 (401) " + detail;
            case 402 -> "DeepSeek 账户余额不足 (402) " + detail;
            case 429 -> "请求过于频繁，被限流 (429) " + detail;
            case 500, 502, 503 -> "DeepSeek 服务暂时不可用 (" + status + ") " + detail;
            default -> "HTTP " + status + " " + detail;
        };
    }
}
