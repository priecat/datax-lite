package net.itzq.datax.engine;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务日志采集与读取。
 * 运行中：内存 ring + 同步落盘；结束后：直接读文件。
 */
@Slf4j
public class JobLogCollector {

    private static final InheritableThreadLocal<String> CURRENT = new InheritableThreadLocal<>();
    private static final Map<String, Buffer> BUFFERS = new ConcurrentHashMap<>();
    private static final int RING_SIZE = 5000;

    public static void bind(String logId, Path logFile) {
        CURRENT.set(logId);
        BUFFERS.putIfAbsent(logId, new Buffer(logFile));
    }

    public static void unbind(String logId) {
        CURRENT.remove();
        Buffer b = BUFFERS.remove(logId);
        if (b != null) {
            b.close();
        }
    }

    public static String currentLogId() {
        return CURRENT.get();
    }

    /** 清除当前线程的任务日志上下文：供不归属任何任务的线程池在启动时调用，避免继承的 logId 导致日志误写入任务日志 */
    public static void clearCurrent() {
        CURRENT.remove();
    }

    public static void append(String logId, String line) {
        Buffer b = BUFFERS.get(logId);
        if (b != null) {
            b.append(line);
        }
    }

    public static boolean isRunning(String logId) {
        return BUFFERS.containsKey(logId);
    }

    /**
     * 读取日志片段。运行中从内存 ring 读取（较早内容可能已被挤出，请读文件）；
     * 结束后从日志文件读取。
     *
     * @param start 起始行号（0 开始）
     */
    public static LogSegment read(String logId, Path logFile, long start, int max) {
        Buffer b = BUFFERS.get(logId);
        if (b != null) {
            return b.read(start, max);
        }
        return readFile(logFile, start, max);
    }

    public static long totalLines(String logId, Path logFile) {
        Buffer b = BUFFERS.get(logId);
        if (b != null) {
            return b.total();
        }
        return readFile(logFile, 0, 0).total;
    }

    public static class LogSegment {
        public long total;
        public long start;
        public List<String> lines = new ArrayList<>();
    }

    private static LogSegment readFile(Path logFile, long start, int max) {
        LogSegment seg = new LogSegment();
        if (logFile == null || !Files.exists(logFile)) {
            return seg;
        }
        try {
            List<String> all = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            seg.total = all.size();
            if (start < 0) {
                start = 0;
            }
            if (max > 0 && start < all.size()) {
                int end = (int) Math.min(all.size(), start + max);
                seg.start = start;
                seg.lines = new ArrayList<>(all.subList((int) start, end));
            }
        } catch (IOException e) {
            log.warn("读取任务日志文件失败: {}", logFile, e);
        }
        return seg;
    }

    private static class Buffer {
        private final Path logFile;
        private final ArrayDeque<String> ring = new ArrayDeque<>();
        private BufferedWriter fileWriter;
        private long total = 0;

        Buffer(Path logFile) {
            this.logFile = logFile;
            if (logFile != null) {
                try {
                    if (logFile.getParent() != null) {
                        Files.createDirectories(logFile.getParent());
                    }
                    this.fileWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("创建任务日志文件失败: {}", logFile, e);
                }
            }
        }

        synchronized void append(String line) {
            total++;
            if (ring.size() >= RING_SIZE) {
                ring.pollFirst();
            }
            ring.addLast(line);
            if (fileWriter != null) {
                try {
                    fileWriter.write(line);
                    fileWriter.newLine();
                    fileWriter.flush();
                } catch (IOException e) {
                    log.warn("写入任务日志文件失败: {}", logFile);
                }
            }
        }

        synchronized long total() {
            return total;
        }

        synchronized LogSegment read(long start, int max) {
            LogSegment seg = new LogSegment();
            seg.total = total;
            long ringStart = total - ring.size();
            if (start < ringStart) {
                start = ringStart;
            }
            if (start >= total) {
                return seg;
            }
            int skip = (int) (start - ringStart);
            Iterator<String> it = ring.iterator();
            while (skip-- > 0 && it.hasNext()) {
                it.next();
            }
            while (it.hasNext() && seg.lines.size() < max) {
                seg.lines.add(it.next());
            }
            seg.start = start;
            return seg;
        }

        synchronized void close() {
            if (fileWriter != null) {
                try {
                    fileWriter.close();
                } catch (IOException ignored) {
                }
                fileWriter = null;
            }
        }
    }
}
