package net.itzq.datax.engine;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务停止标记注册表（jobId -> 是否请求停止）
 * StopableMemoryChannel 在数据流转时检查该标记实现任务停止。
 */
public class StopFlagRegistry {

    private static final ConcurrentHashMap<Long, AtomicBoolean> FLAGS = new ConcurrentHashMap<>();

    public static void register(long jobId) {
        FLAGS.put(jobId, new AtomicBoolean(false));
    }

    public static void stop(long jobId) {
        AtomicBoolean f = FLAGS.get(jobId);
        if (f != null) {
            f.set(true);
        }
    }

    public static boolean isStopped(long jobId) {
        AtomicBoolean f = FLAGS.get(jobId);
        return f != null && f.get();
    }

    public static void unregister(long jobId) {
        FLAGS.remove(jobId);
    }
}
