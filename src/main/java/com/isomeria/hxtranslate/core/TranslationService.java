package com.isomeria.hxtranslate.core;

import com.isomeria.hxtranslate.HxTranslateClient;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.util.LangUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 翻译调度：缓存 + 限流 + 后台线程池。
 *
 * <p>回调在线程池线程里执行，调用方如果要在游戏里显示结果，
 * 需要自己切回客户端主线程。
 */
public final class TranslationService {

    /** 翻译完成回调。ok 为 false 时 error 里是失败原因。 */
    public interface Callback {
        void onResult(boolean ok, String text, String error);
    }

    private final TranslatorConfig config;
    private final DeepSeekClient client;
    private final ExecutorService executor;
    private final AtomicBoolean missingKeyWarned = new AtomicBoolean(false);

    /** LRU 翻译缓存，key = 方向 + 归一化原文。 */
    private final Map<String, String> cache;
    /** 滑动窗口限流用的时间戳。 */
    private final Deque<Long> requestWindow = new ArrayDeque<>();

    private volatile boolean shutdown;

    public TranslationService(TranslatorConfig config) {
        this.config = config;
        this.client = new DeepSeekClient(config);
        this.cache = createCache(config.cacheSize);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "hxtranslate-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static Map<String, String> createCache(int maxSize) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > Math.max(16, maxSize);
            }
        };
    }

    public boolean isReady() {
        return config.hasApiKey() && !shutdown;
    }

    /** 清空缓存（换了模型 / 改了提示词之后用）。 */
    public synchronized void invalidateCache() {
        cache.clear();
    }

    public synchronized void resetRateLimit() {
        requestWindow.clear();
    }

    /**
     * 提交一条翻译请求。
     *
     * @return true 表示已受理（回调一定会被调用，可能是同步的缓存命中）；
     *         false 表示没受理（没配 Key、已关闭、或者被限流），调用方应自行决定降级行为。
     */
    public boolean submit(String text, Direction direction, Callback callback) {
        if (!isReady()) {
            warnMissingKeyOnce();
            return false;
        }
        if (text == null || text.isBlank()) {
            return false;
        }

        String key = direction.name() + '|' + LangUtils.normalizeKey(text);
        String cached;
        synchronized (this) {
            cached = cache.get(key);
        }
        if (cached != null) {
            if (config.debugLog) {
                HxTranslateClient.LOGGER.info("[cache] {} {}", direction.label(), text);
            }
            callback.onResult(true, cached, null);
            return true;
        }

        if (!tryAcquireRateLimit()) {
            if (config.debugLog) {
                HxTranslateClient.LOGGER.info("[rate-limit] 丢弃 {}", text);
            }
            return false;
        }

        executor.execute(() -> {
            DeepSeekClient.Result result = client.translate(text, direction);
            if (result.ok()) {
                synchronized (this) {
                    cache.put(key, result.text());
                }
                if (config.debugLog) {
                    HxTranslateClient.LOGGER.info("[translate] {} {} -> {}", direction.label(), text, result.text());
                }
            } else if (config.debugLog) {
                HxTranslateClient.LOGGER.info("[translate-fail] {} {}: {}", direction.label(), text, result.error());
            }
            callback.onResult(result.ok(), result.text(), result.error());
        });
        return true;
    }

    /** 同步翻译，仅供游戏内 /hxtranslate test 这类需要立刻拿结果的场景使用。 */
    public DeepSeekClient.Result translateBlocking(String text, Direction direction) {
        if (!isReady()) {
            return DeepSeekClient.Result.failure("未配置 API Key");
        }
        String key = direction.name() + '|' + LangUtils.normalizeKey(text);
        synchronized (this) {
            String cached = cache.get(key);
            if (cached != null) {
                return DeepSeekClient.Result.success(cached);
            }
        }
        DeepSeekClient.Result result = client.translate(text, direction);
        if (result.ok()) {
            synchronized (this) {
                cache.put(key, result.text());
            }
        }
        return result;
    }

    private synchronized boolean tryAcquireRateLimit() {
        long now = System.currentTimeMillis();
        long windowStart = now - TimeUnit.MINUTES.toMillis(1);
        while (!requestWindow.isEmpty() && requestWindow.peekFirst() < windowStart) {
            requestWindow.pollFirst();
        }
        if (requestWindow.size() >= config.requestsPerMinute) {
            return false;
        }
        requestWindow.addLast(now);
        return true;
    }

    public synchronized int usedRequestsThisMinute() {
        long windowStart = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1);
        while (!requestWindow.isEmpty() && requestWindow.peekFirst() < windowStart) {
            requestWindow.pollFirst();
        }
        return requestWindow.size();
    }

    private void warnMissingKeyOnce() {
        if (missingKeyWarned.compareAndSet(false, true)) {
            HxTranslateClient.LOGGER.warn("未配置 DeepSeek API Key，翻译功能不可用。请编辑 {}", TranslatorConfig.configPath());
        }
    }

    public void shutdown() {
        shutdown = true;
        executor.shutdownNow();
    }
}
