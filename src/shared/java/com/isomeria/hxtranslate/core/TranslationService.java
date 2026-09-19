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

    /**
     * 翻译完成回调。
     *
     * <p>v3.0.7 起直接回传 {@link DeepSeekClient.Result}，不再拆成
     * {@code (ok, text, error)} 三个参数：译文现在有**三个**结果状态
     * （成功 / 失败 / {@link DeepSeekClient.Result#nothingToTranslate() 无可译内容}），
     * 用布尔量表达不出来 —— 老写法里「无可译内容」只能伪装成失败，
     * 于是玩家看到一条莫名其妙的红字（这正是 v3.0.7 修的问题）。
     */
    public interface Callback {
        void onResult(DeepSeekClient.Result result);
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

    /**
     * 缓存「代际」。{@code invalidateCache()} 时 +1。
     *
     * <p><b>2026-09-17 审计修正的竞态</b>：{@code /translator reload} 会清空缓存
     * （玩家刚改完术语表 / 提示词 / 模型，盼着新配置立刻生效），但**在途**的翻译任务不会被取消 ——
     * 它拿的是旧配置生成的请求，回来之后照旧往缓存里写。于是一条用旧提示词翻出来的译文
     * 会在 reload 之后被写进刚清空的缓存，并**长期命中**（直到被 LRU 挤出去），
     * 玩家看到的是「reload 没生效」。
     *
     * <p>修法：提交任务时记下当时的代际，写完缓存前再比对一次 —— 代际变了就丢弃这次结果
     * （回调照常执行，只是不落缓存）。不用 synchronized 包住「清空 + 回写」，
     * 是因为清空与回写分别在主线程和 worker 线程上，加锁也挡不住「清空之后才开始写」这个顺序，
     * 只有代际比较能表达「这条结果属于上一代配置」。
     */
    private final java.util.concurrent.atomic.AtomicLong cacheGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    private volatile boolean shutdown;

    public TranslationService(TranslatorConfig config) {
        this.config = config;
        this.client = new DeepSeekClient(config);
        this.cache = createCache();
        this.incomingExecutor = newWorkerPool(Math.max(1, config.incomingThreads));
        this.outgoingExecutor = newWorkerPool(1);
    }

    /**
     * 带入队时刻的任务包装（v3.1.0）。
     *
     * <p>队列按**年龄**丢弃的载体：任务在队列里等超过 {@code maxQueueAgeSeconds} 就不再执行，
     * 以「排队超龄」回调调用方（回调必被调用，契约不变）。为什么不只做提交时的背压：
     * 深度上限挡住的是「队列有多长」，挡不住「队头那条已经等了多久」——
     * 20 条 × 每条 30 秒可以积压 5 分钟，那样的译文出来时早就没意义了，
     * 还一直占着线程与限流配额。
     */
    private static final class TimedTask implements Runnable {
        final long enqueuedAt;
        final Runnable delegate;
        /** 取任务时发现已超龄，就执行这个丢弃回调而不是本体。 */
        final Runnable onStale;
        /** 超龄判定（读配置的那个值由构造方闭包进来，reload 后立刻生效）。 */
        final java.util.function.BooleanSupplier staleCheck;

        TimedTask(long enqueuedAt, Runnable delegate, Runnable onStale,
                  java.util.function.BooleanSupplier staleCheck) {
            this.enqueuedAt = enqueuedAt;
            this.delegate = delegate;
            this.onStale = onStale;
            this.staleCheck = staleCheck;
        }

        boolean isStale() {
            return staleCheck.getAsBoolean();
        }

        @Override
        public void run() {
            // 执行时刻再判一次：队列积压时排在后面的任务，到 pop 出来时可能已经超龄
            if (isStale()) {
                onStale.run();
            } else {
                delegate.run();
            }
        }
    }

    /** 当前配置下的队列超龄阈值（毫秒）。 */
    private long maxQueueAgeMs() {
        return TimeUnit.SECONDS.toMillis(Math.max(1, config.maxQueueAgeSeconds));
    }

    /**
     * 把队列里已经超龄的任务请出去（v3.1.0）：在「队列满 → 拒绝新消息」之前做一次，
     * 免得队列被一堆早就没意义的旧任务占满、新消息反被背压挡掉。
     * 被请出去的任务照样走「排队超龄」回调（契约：回调必被调用）。
     *
     * @return 清掉了多少条
     */
    private int purgeStaleTasks(ThreadPoolExecutor pool) {
        java.util.List<TimedTask> candidates = new java.util.ArrayList<>();
        for (Runnable queued : pool.getQueue()) {
            if (queued instanceof TimedTask) {
                TimedTask task = (TimedTask) queued;
                if (task.isStale()) {
                    candidates.add(task);
                }
            }
        }
        int purged = 0;
        for (TimedTask task : candidates) {
            // remove 必须成功才丢弃：失败说明 worker 刚把任务取走，那边会自己判超龄，
            // 这里再执行一次 onStale 就等于回调了两次
            if (pool.getQueue().remove(task)) {
                purged++;
                task.onStale.run();
            }
        }
        return purged;
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

    /** 清空缓存（换了模型 / 改了提示词之后用）。同时推进代际，作废在途任务的回写。 */
    public synchronized void invalidateCache() {
        cache.clear();
        cacheGeneration.incrementAndGet();
    }

    /**
     * 写入缓存 —— 只在「提交时的代际 == 当前代际」时生效。
     *
     * <p>代际在 {@code invalidateCache()} 里 +1，所以配置重载之后回来的旧译文会被丢弃，
     * 不会污染刚清空的缓存（见 {@code cacheGeneration} 的说明）。
     */
    private synchronized void putCached(String key, String value, long generation) {
        if (generation != cacheGeneration.get()) {
            return;
        }
        cache.put(key, value);
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
        // 记下提交时的缓存代际：回写时若已经 reload 过（代际变了），这次结果就不进缓存。
        final long generation = cacheGeneration.get();

        // 缓存查找必须排在背压**之前**（v2.2.3 调整顺序）。
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
            long enqueuedAt = System.currentTimeMillis();
            long maxAgeMs = maxQueueAgeMs();
            // 缓存命中同样包进 TimedTask（v3.1.0）：它也要排队，也受超龄约束 ——
            // 否则它会绕过「按年龄丢弃」的语义（虽然命中缓存意味着大概率能秒回）。
            pool.execute(new TimedTask(enqueuedAt,
                    () -> {
                        try {
                            callback.onResult(DeepSeekClient.Result.success(hit));
                        } catch (Throwable t) {
                            // 同上：回调必被调用，且它自己抛错也不能弄死工作线程
                            reportFailure(text, direction, t);
                        }
                    },
                    () -> {
                        try {
                            callback.onResult(DeepSeekClient.Result.staleDropped());
                        } catch (Throwable t) {
                            reportFailure(text, direction, t);
                        }
                    },
                    () -> System.currentTimeMillis() - enqueuedAt > maxAgeMs));
            return SubmitResult.ACCEPTED;
        }

        // 背压：接口变慢时消息会堆在队列里，越堆越晚。超过阈值就先不接了，
        // 免得延迟滚雪球、内存也跟着涨。
        //
        // 这一步必须排在限流之前：被背压挡下的请求根本没有发出去，
        // 却先把每分钟的配额吃掉，等于让后面的消息替它买单。
        //
        // v3.1.0：判断「满」之前先把队列里已经超龄的任务清出去 ——
        // 深度上限管不了「队头等了多久」，不清理的话一串旧任务会把新消息挡在门外。
        purgeStaleTasks(pool);
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

        // 入队时刻（v3.1.0）：队列按年龄丢弃的计时起点
        final long enqueuedAt = System.currentTimeMillis();
        final long maxAgeMs = maxQueueAgeMs();
        Runnable task = () -> {
            try {
                DeepSeekClient.Result result = client.translate(text, direction);
                if (result.ok()) {
                    putCached(key, result.text(), generation);
                    if (config.debugLog) {
                        Log.LOGGER.info("[translate] {} {} -> {}", direction.label(), text, result.text());
                    }
                } else if (result.isNothingToTranslate()) {
                    // 「本条无可译内容」（v3.0.7）：模型按提示词要求把原文原样退回 ——
                    // 不写缓存（没有译文可缓存，而且模型下次可能给出别的结果），
                    // 但要留一行日志：玩家开 debug 排查「怎么没翻译」时，这行就是答案。
                    if (config.debugLog) {
                        Log.LOGGER.info("[nothing-to-translate] {} {}", direction.label(), text);
                    }
                } else if (config.debugLog) {
                    Log.LOGGER.info("[translate-fail] {} {}: {}", direction.label(), text, result.error());
                }
                callback.onResult(result);
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
                    callback.onResult(DeepSeekClient.Result.failure(describeFailure(t)));
                } catch (Throwable fromCallback) {
                    // 连调用方都抛了：仍然不能让线程死掉（否则后续排队的请求全部丢失）
                    reportFailure(text, direction, fromCallback);
                }
            }
        };
        // 「排队超龄」的丢弃回调：与成功/失败回调走同一个出口（回调必被调用），
        // 但请求**没有发出去**、不占限流配额，所以不受 tryAcquireRateLimit 的回滚问题困扰。
        Runnable staleDrop = () -> {
            if (config.debugLog) {
                Log.LOGGER.info("[queue-stale] 等待超 {} 秒，丢弃 {}", config.maxQueueAgeSeconds, text);
            }
            try {
                callback.onResult(DeepSeekClient.Result.staleDropped());
            } catch (Throwable t) {
                // 丢弃回调同样不能弄死工作线程
                reportFailure(text, direction, t);
            }
        };
        pool.execute(new TimedTask(enqueuedAt, task, staleDrop,
                () -> System.currentTimeMillis() - enqueuedAt > maxAgeMs));
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
        // v3.0.8：这条文案会**直接进聊天栏**（经 ChatTranslator 的失败分支），而异常消息
        // 可能夹带任意文本 —— 类名、第三方库的提示、甚至被上游拼进来的原始数据。
        // 原注释写着「只留可读的那部分，并压成一行」，但当时**根本没有调用任何清洗**，
        // 只是把 getMessage() 原样拼进去（含换行与 §）。现在按不可信文本清洗，
        // sanitizeOneLine 会压成一行、剥掉 § 与非可见格式字符，null 安全。
        String message = LangUtils.sanitizeOneLine(t == null ? null : t.getMessage());
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
        final long generation = cacheGeneration.get();
        synchronized (this) {
            String cached = cache.get(key);
            if (cached != null) {
                return DeepSeekClient.Result.success(cached);
            }
        }
        DeepSeekClient.Result result = client.translate(text, direction);
        if (result.ok()) {
            // 与 submit() 同一条规则：reload 之后回来的旧译文不写进新缓存
            putCached(key, result.text(), generation);
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
