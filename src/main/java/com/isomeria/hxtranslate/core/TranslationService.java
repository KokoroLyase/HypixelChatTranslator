package com.isomeria.hxtranslate.core;

import com.isomeria.hxtranslate.HxTranslateClient;
import com.isomeria.hxtranslate.config.TranslatorConfig;
import com.isomeria.hxtranslate.util.LangUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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

    /** 提交结果：区分「没配 Key」「被限流」「队列积压」等不同降级原因。 */
    public enum SubmitResult {
        ACCEPTED,
        NOT_READY,
        RATE_LIMITED,
        QUEUE_FULL,
        EMPTY;

        public boolean accepted() {
            return this == ACCEPTED;
        }
    }

    private final TranslatorConfig config;
    private final DeepSeekClient client;
    /** 收到消息：2 个线程，允许并发。 */
    private final ThreadPoolExecutor incomingExecutor;
    /** 发出消息：单线程 FIFO，保证「你连打两条中文」时译文按原顺序发出去。 */
    private final ThreadPoolExecutor outgoingExecutor;
    private final AtomicBoolean missingKeyWarned = new AtomicBoolean(false);

    /** LRU 翻译缓存，key = 方向 + 归一化原文。 */
    private final Map<String, String> cache;
    /** 滑动窗口限流用的时间戳。 */
    private final Deque<Long> requestWindow = new ArrayDeque<>();

    private volatile boolean shutdown;

    public TranslationService(TranslatorConfig config) {
        this.config = config;
        this.client = new DeepSeekClient(config);
        this.cache = createCache();
        this.incomingExecutor = newWorkerPool(2);
        this.outgoingExecutor = newWorkerPool(1);
    }

    private static ThreadPoolExecutor newWorkerPool(int threads) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "hxtranslate-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * LRU 上限每次都从配置里读，这样 {@code /hxtranslate reload} 改了 {@code cacheSize} 就能立刻生效。
     * （以前是在构造时把数值固化进闭包，改配置必须重启游戏才生效。）
     */
    private Map<String, String> createCache() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                // 下限用配置里的常量，别在这里写魔法数字：以前这里是 Math.max(16, ...)，
                // 于是用户把 cacheSize 设成 0/3 都不生效，和 README 的说明也对不上。
                return size() > Math.max(TranslatorConfig.MIN_CACHE_SIZE, config.cacheSize);
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
     * <p>回调<b>一定</b>在 {@code direction} 对应的工作线程里执行，包括缓存命中：
     * 命中缓存也照样排队，这样发送方向的单线程 FIFO 才真的等于「按你输入的先后发出」。
     * 因此回调里如果要碰游戏状态，必须自己切回客户端主线程。
     *
     * @return {@link SubmitResult#ACCEPTED} 表示已受理（回调一定会被调用）；
     *         其它值表示没受理，调用方按原因决定降级行为。
     */
    public SubmitResult submit(String text, Direction direction, Callback callback) {
        if (!isReady()) {
            warnMissingKeyOnce();
            return SubmitResult.NOT_READY;
        }
        if (text == null || text.isBlank()) {
            return SubmitResult.EMPTY;
        }

        String key = direction.name() + '|' + LangUtils.normalizeKey(text);

        // 背压：接口变慢时消息会堆在队列里，越堆越晚。超过阈值就先不接了，
        // 免得延迟滚雪球、内存也跟着涨。
        //
        // 这一步必须排在限流之前：被背压挡下的请求根本没有发出去，
        // 却先把每分钟的配额吃掉，等于让后面的消息替它买单。
        ThreadPoolExecutor pool = direction == Direction.OUTGOING ? outgoingExecutor : incomingExecutor;
        if (pool.getQueue().size() >= Math.max(1, config.maxPendingTranslations)) {
            if (config.debugLog) {
                HxTranslateClient.LOGGER.info("[queue-full] 丢弃 {}", text);
            }
            return SubmitResult.QUEUE_FULL;
        }

        String cached;
        synchronized (this) {
            cached = cache.get(key);
        }
        if (cached != null) {
            if (config.debugLog) {
                HxTranslateClient.LOGGER.info("[cache] {} {}", direction.label(), text);
            }
            // 缓存命中也要走同一个执行队列，绝不能在这里直接回调调用方。
            // 发送方向是单线程 FIFO，直接回调等于让「命中缓存的第二条」插队：
            // 先发的那条还在等网络，后发的这条已经排队去发了，译文就会乱序。
            // （v1.0.5 为「连打两条中文乱序」改成了单线程池，但漏了这条捷径。）
            String hit = cached;
            pool.execute(() -> callback.onResult(true, hit, null));
            return SubmitResult.ACCEPTED;
        }

        if (!tryAcquireRateLimit()) {
            if (config.debugLog) {
                HxTranslateClient.LOGGER.info("[rate-limit] 丢弃 {}", text);
            }
            return SubmitResult.RATE_LIMITED;
        }

        pool.execute(() -> {
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
        return SubmitResult.ACCEPTED;
    }

    /** 正在执行 + 排队中的翻译请求数，给状态命令用。 */
    public int pendingTranslations() {
        return incomingExecutor.getQueue().size() + incomingExecutor.getActiveCount()
                + outgoingExecutor.getQueue().size() + outgoingExecutor.getActiveCount();
    }

    /** 接口是否处于熔断状态（连续失败后暂停）。 */
    public boolean isCircuitOpen() {
        return client.isCircuitOpen();
    }

    public long circuitRemainingSeconds() {
        return client.circuitRemainingSeconds();
    }

    /** 手动复位熔断（配置重载后调用：可能刚换了 Key 或接口地址）。 */
    public void resetCircuit() {
        client.resetCircuit();
    }

    /** 查询当前账号可用的模型列表（GET /models），接口改版后可自查。 */
    public DeepSeekClient.Result listModels() {
        return client.listModels();
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
        incomingExecutor.shutdownNow();
        outgoingExecutor.shutdownNow();
    }
}
