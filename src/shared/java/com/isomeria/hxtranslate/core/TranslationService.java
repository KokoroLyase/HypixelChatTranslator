package com.isomeria.hxtranslate.core;

import com.isomeria.hxtranslate.Log;
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
            Thread thread = new Thread(runnable, "server_chat_translator-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * LRU 上限每次都从配置里读，这样 {@code /translator reload} 改了 {@code cacheSize} 就能立刻生效。
     * （以前是在构造时把数值固化进闭包，改配置必须重启游戏才生效。）
     */
    private Map<String, String> createCache() {
        // Java 8 不允许「菱形 + 匿名类」同时用（那是 Java 9 的改进），显式写类型参数
        return new LinkedHashMap<String, String>(16, 0.75f, true) {
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
        if (text == null || LangUtils.isBlank(text)) {
            return SubmitResult.EMPTY;
        }

        String key = direction.name() + '|' + LangUtils.normalizeKey(text);

        // 缓存查找必须排在背压**之前**（v2.2.3 调整顺序）。
        //
        // 原因：缓存命中是唯一「零网络、可立即完成」的出路 —— 它不需要接口，也不占限流配额。
        // 以前先查背压，于是接口变慢、队列积压时，连「这句我刚才已经翻过」的免费消息
        // 也会被当成「接口变慢」拒绝（默认 failureFallback=CANCEL 下直接不发出去）。
        // 顺序调换只影响「缓存命中 + 队列满」这一种组合，发送顺序语义不变：
        // 命中缓存仍然走下面同一个执行队列（见那里的注释）。
        String cached;
        synchronized (this) {
            cached = cache.get(key);
        }
        ThreadPoolExecutor pool = direction == Direction.OUTGOING ? outgoingExecutor : incomingExecutor;
        if (cached != null) {
            if (config.debugLog) {
                Log.LOGGER.info("[cache] {} {}", direction.label(), text);
            }
            // 缓存命中也要走同一个执行队列，绝不能在这里直接回调调用方。
            // 发送方向是单线程 FIFO，直接回调等于让「命中缓存的第二条」插队：
            // 先发的那条还在等网络，后发的这条已经排队去发了，译文就会乱序。
            // （v1.0.5 为「连打两条中文乱序」改成了单线程池，但漏了这条捷径。）
            String hit = cached;
            pool.execute(() -> {
                try {
                    callback.onResult(true, hit, null);
                } catch (Throwable t) {
                    // 同上：回调必被调用，且它自己抛错也不能弄死工作线程
                    reportFailure(text, direction, t);
                }
            });
            return SubmitResult.ACCEPTED;
        }

        // 背压：接口变慢时消息会堆在队列里，越堆越晚。超过阈值就先不接了，
        // 免得延迟滚雪球、内存也跟着涨。
        //
        // 这一步必须排在限流之前：被背压挡下的请求根本没有发出去，
        // 却先把每分钟的配额吃掉，等于让后面的消息替它买单。
        if (pool.getQueue().size() >= Math.max(1, config.maxPendingTranslations)) {
            if (config.debugLog) {
                Log.LOGGER.info("[queue-full] 丢弃 {}", text);
            }
            return SubmitResult.QUEUE_FULL;
        }

        if (!tryAcquireRateLimit()) {
            if (config.debugLog) {
                Log.LOGGER.info("[rate-limit] 丢弃 {}", text);
            }
            return SubmitResult.RATE_LIMITED;
        }

        pool.execute(() -> {
            try {
                DeepSeekClient.Result result = client.translate(text, direction);
                if (result.ok()) {
                    synchronized (this) {
                        cache.put(key, result.text());
                    }
                    if (config.debugLog) {
                        Log.LOGGER.info("[translate] {} {} -> {}", direction.label(), text, result.text());
                    }
                } else if (config.debugLog) {
                    Log.LOGGER.info("[translate-fail] {} {}: {}", direction.label(), text, result.error());
                }
                callback.onResult(result.ok(), result.text(), result.error());
            } catch (Throwable t) {
                // 兜底：**回调必须被调用**（submit 的契约），否则这条消息会永远停在「⏳ 翻译中…」，
                // 在发送方向更是「玩家打了中文，然后什么都没发生」。
                //
                // 这里刻意捕获 Throwable 而不是 Exception：真出过事 —— debug 分支引用了实现
                // ClientModInitializer 的入口类，加载失败抛的是 NoClassDefFoundError（Error），
                // 现有的 catch (IOException|RuntimeException) 全都接不住，工作线程直接死、
                // 回调不执行、消息静默消失。任何从这个任务里穿出去的 Throwable 都必须在
                // 这里转成「翻译失败」，而不是让线程死掉。
                reportFailure(text, direction, t);
                try {
                    callback.onResult(false, null, describeFailure(t));
                } catch (Throwable fromCallback) {
                    // 连调用方都抛了：仍然不能让线程死掉（否则后续排队的请求全部丢失）
                    reportFailure(text, direction, fromCallback);
                }
            }
        });
        return SubmitResult.ACCEPTED;
    }

    /** 把「任务里逃出来的 Throwable」写进日志；连日志都写不了时也不能再抛。 */
    private static void reportFailure(String text, Direction direction, Throwable t) {
        try {
            Log.LOGGER.error("翻译任务异常（{} {}）: {}", direction.label(), text, t.toString(), t);
        } catch (Throwable ignored) {
            // 日志出口本身都坏了：这里必须保持沉默，抛出去就又回到「线程死、回调丢」的老问题
        }
    }

    /**
     * 给玩家看的失败原因。
     *
     * <p>v2.2.2 改：以前返回的是 {@code 异常类名: 消息}（例如 {@code NoClassDefFoundError: ...}），
     * 而这条文本会经 {@code ChatTranslator} 直接进聊天栏 —— 对玩家零信息量，
     * 与 v2.2.1 统一网络错误文案的初衷也不一致。异常类型仍然完整写进日志（见上面的
     * {@code Log.LOGGER.error(..., t)}），排错信息一点没少。
     */
    private static String describeFailure(Throwable t) {
        String message = t.getMessage() == null ? "" : t.getMessage();
        // 消息本身是给开发者看的（可能带类名/堆栈片段），所以只留可读的那部分，并压成一行
        return "翻译线程内部错误（详情见日志）" + (LangUtils.isBlank(message) ? "" : "：" + message);
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

    /** 同步翻译，仅供游戏内 /translator test 这类需要立刻拿结果的场景使用。 */
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
            Log.LOGGER.warn("未配置 DeepSeek API Key，翻译功能不可用。请编辑 {}", TranslatorConfig.configPath());
        }
    }

    public void shutdown() {
        shutdown = true;
        incomingExecutor.shutdownNow();
        outgoingExecutor.shutdownNow();
    }
}
