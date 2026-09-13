package net.itzq.datax.engine;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 按任务采集 DataX 运行日志。
 * 依赖 JobLogCollector 的 InheritableThreadLocal 上下文贯穿 DataX 内部线程树。
 */
public class JobLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final int MAX_STACK_FRAMES = 30;

    @Override
    protected void append(ILoggingEvent event) {
        String logId = JobLogCollector.currentLogId();
        if (logId == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append('[').append(TS.format(Instant.ofEpochMilli(event.getTimeStamp()))).append("] [")
                .append(event.getThreadName()).append("] ")
                .append(event.getLevel()).append(" ")
                .append(event.getFormattedMessage());

        IThrowableProxy tp = event.getThrowableProxy();
        if (tp != null) {
            appendThrowable(sb, tp, 0);
        }
        JobLogCollector.append(logId, sb.toString());
    }

    private void appendThrowable(StringBuilder sb, IThrowableProxy tp, int depth) {
        if (depth == 0) {
            sb.append('\n').append(tp.getClassName());
            if (tp.getMessage() != null) {
                sb.append(": ").append(tp.getMessage());
            }
        } else {
            sb.append("\nCaused by: ").append(tp.getClassName());
            if (tp.getMessage() != null) {
                sb.append(": ").append(tp.getMessage());
            }
        }
        StackTraceElementProxy[] steps = tp.getStackTraceElementProxyArray();
        if (steps != null) {
            int n = Math.min(steps.length, MAX_STACK_FRAMES);
            for (int i = 0; i < n; i++) {
                sb.append("\n\tat ").append(steps[i].getStackTraceElement());
            }
        }
        if (tp.getCause() != null && depth < 3) {
            appendThrowable(sb, tp.getCause(), depth + 1);
        }
    }
}
