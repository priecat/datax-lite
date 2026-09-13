package net.itzq.datax.common;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用工具
 */
public class IdGen {
    public static String[] chars = new String[] { "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "0", "1", "2", "3",
            "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z" };
    public static String uuidShort() {
        StringBuffer shortBuffer = new StringBuffer();
        String uuid = uuid();
        for (int i = 0; i < 8; i++) {
            String str = uuid.substring(i * 4, i * 4 + 4);
            int x = Integer.parseInt(str, 16);
            shortBuffer.append(chars[x % 0x3E]);
        }
        return shortBuffer.toString();

    }
    private static final AtomicLong JOB_ID = new AtomicLong(
            System.currentTimeMillis() % 100000000L * 10L);

    /** DataX 数字 jobId（线程名/日志展示用，同一次运行内唯一） */
    public static long nextDataxJobId() {
        return JOB_ID.incrementAndGet();
    }

    /** 业务主键（UUID 去横线） */
    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String formatTime(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(millis));
    }

    /** 文件名用紧凑时间戳（yyyyMMddHHmmss），便于 work 目录排序与关联 */
    public static String compactTime(long millis) {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date(millis));
    }
}
