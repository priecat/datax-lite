package net.itzq.datax.service;

import net.itzq.datax.common.IdGen;
import net.itzq.datax.dto.NotifyRequest;
import net.itzq.datax.engine.JobLogCollector;
import net.itzq.datax.entity.SyncTask;
import net.itzq.datax.entity.SyncTaskLog;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务完成通知：任务执行结束后，按任务配置向外部系统发送自定义 HTTP 请求。
 * 支持占位符变量（URL/Headers/Params/Body 均可使用）：
 * ${taskId} ${taskName} ${logId} ${state}(SUCCESS/FAILED/STOPPED) ${message}
 * ${triggerType}(manual/schedule) ${startTime} ${endTime} ${durationMs}
 * ${readRecords} ${writeRecords} ${errorRecords} ${readBytes} ${speedRecord} ${speedByte}
 */
@Slf4j
@Service
public class NotifyService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RestTemplate restTemplate;
    private ExecutorService pool;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        restTemplate = new RestTemplate(factory);
        pool = Executors.newFixedThreadPool(2, namedFactory("notify-"));
    }

    @PreDestroy
    public void destroy() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** 任务结束后触发通知：并发发送并等待全部完成，结果写入任务日志末尾；失败不影响任务结果 */
    public void sendTaskFinished(SyncTask task, SyncTaskLog logRecord) {
        if (task == null || logRecord == null) {
            return;
        }
        String logId = logRecord.getId();
        String json = task.getNotifyConfig();
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        List<NotifyRequest> list;
        try {
            list = MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, NotifyRequest.class));
        } catch (Exception e) {
            // 运行在 JobRunner 线程上，任务日志上下文仍绑定，此处 log.warn 会被 JobLogAppender 采集进任务日志，无需重复写入
            log.warn("通知配置解析失败, taskId={}: {}", task.getId(), e.getMessage());
            return;
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        List<NotifyRequest> valid = new ArrayList<>();
        for (NotifyRequest req : list) {
            if (req != null && req.getUrl() != null && !req.getUrl().trim().isEmpty()) {
                valid.add(req);
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        Map<String, String> vars = buildVars(task, logRecord);
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            NotifyRequest req = valid.get(i);
            int no = i + 1;
            int total = valid.size();
            jobs.add(() -> {
                doSend(req, vars, logId, no, total);
                return null;
            });
        }
        try {
            // 等待全部通知发送完成：JobRunner 在本方法返回后才 unbind 关闭任务日志，
            // 保证"通知结果"一定出现在任务日志末尾
            pool.invokeAll(jobs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void doSend(NotifyRequest req, Map<String, String> vars, String logId, int no, int total) {
        String method = req.getMethod() == null ? "POST" : req.getMethod().trim().toUpperCase();
        try {
            HttpMethod httpMethod = HttpMethod.resolve(method);
            if (httpMethod == null) {
                log.warn("通知请求方法非法: {}", method);
                appendTaskLog(logId, "WARN", "任务通知[" + no + "/" + total + "]请求方法非法: " + method);
                return;
            }
            String url = render(req.getUrl(), vars, false);

            // Params 拼接到 URL 查询串
            List<NotifyRequest.KeyValue> params = clean(req.getParams());
            if (!params.isEmpty()) {
                StringBuilder qs = new StringBuilder();
                for (NotifyRequest.KeyValue kv : params) {
                    if (qs.length() > 0) {
                        qs.append('&');
                    }
                    qs.append(URLEncoder.encode(kv.getKey().trim(), "UTF-8"))
                            .append('=')
                            .append(URLEncoder.encode(render(kv.getValue(), vars, false), "UTF-8"));
                }
                url += (url.contains("?") ? "&" : "?") + qs;
            }

            HttpHeaders headers = new HttpHeaders();
            boolean hasContentType = false;
            if (req.getHeaders() != null) {
                for (NotifyRequest.KeyValue kv : req.getHeaders()) {
                    if (kv == null || kv.getKey() == null || kv.getKey().trim().isEmpty()) {
                        continue;
                    }
                    if ("content-type".equalsIgnoreCase(kv.getKey().trim())) {
                        hasContentType = true;
                    }
                    headers.add(kv.getKey().trim(), render(kv.getValue(), vars, false));
                }
            }
            String bodyType = req.getBodyType() == null ? "none" : req.getBodyType().trim().toLowerCase();
            // form/json 类型未显式指定 Content-Type 时自动注入
            if (!hasContentType) {
                if ("json".equals(bodyType)) {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                } else if ("form".equals(bodyType)) {
                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                }
            }

            String body = null;
            boolean bodyAllowed = !"GET".equals(method) && !"HEAD".equals(method);
            if (bodyAllowed && !"none".equals(bodyType)) {
                if ("form".equals(bodyType)) {
                    StringBuilder sb = new StringBuilder();
                    for (NotifyRequest.KeyValue kv : clean(req.getFormFields())) {
                        if (sb.length() > 0) {
                            sb.append('&');
                        }
                        sb.append(URLEncoder.encode(render(kv.getKey(), vars, false), "UTF-8"))
                                .append('=')
                                .append(URLEncoder.encode(render(kv.getValue(), vars, false), "UTF-8"));
                    }
                    body = sb.toString();
                } else if ("json".equals(bodyType)) {
                    body = render(nvl(req.getRawBody()), vars, true);
                } else if ("raw".equals(bodyType)) {
                    body = render(nvl(req.getRawBody()), vars, false);
                }
            }

            ResponseEntity<String> resp =
                    restTemplate.exchange(url, httpMethod, new HttpEntity<>(body, headers), String.class);
            String ok = "任务通知[" + no + "/" + total + "]已发送: " + method + " " + url
                    + " -> HTTP " + resp.getStatusCodeValue();
            log.info(ok);
            appendTaskLog(logId, "INFO", ok);
        } catch (Exception e) {
            String err = "任务通知[" + no + "/" + total + "]发送失败: " + method + " " + req.getUrl()
                    + ": " + e.getMessage();
            log.warn(err);
            appendTaskLog(logId, "WARN", err);
        }
    }

    /** 通知结果写入任务日志（与 JobRunner 日志格式一致）；任务日志已关闭时静默跳过 */
    private void appendTaskLog(String logId, String level, String msg) {
        if (logId == null || logId.isEmpty()) {
            return;
        }
        JobLogCollector.append(logId,
                "[" + IdGen.formatTime(System.currentTimeMillis()) + "] [console] " + level + " " + msg);
    }

    /** 构建占位符变量表 */
    private Map<String, String> buildVars(SyncTask task, SyncTaskLog l) {
        Map<String, String> vars = new HashMap<>();
        vars.put("taskId", nvl(task.getId()));
        vars.put("taskName", nvl(task.getName()));
        vars.put("logId", nvl(l.getId()));
        vars.put("state", nvl(l.getState()));
        vars.put("triggerType", nvl(l.getTriggerType()));
        vars.put("message", nvl(l.getMessage()));
        vars.put("startTime", l.getStartTime() == null ? "" : IdGen.formatTime(l.getStartTime().getTime()));
        vars.put("endTime", l.getEndTime() == null ? "" : IdGen.formatTime(l.getEndTime().getTime()));
        vars.put("durationMs", str(l.getDurationMs()));
        vars.put("readRecords", str(l.getReadRecords()));
        vars.put("writeRecords", str(l.getWriteRecords()));
        vars.put("errorRecords", str(l.getErrorRecords()));
        vars.put("readBytes", str(l.getReadBytes()));
        vars.put("speedRecord", nvl(l.getSpeedRecord()));
        vars.put("speedByte", nvl(l.getSpeedByte()));
        return vars;
    }

    /**
     * 占位符替换。
     * jsonSafe=true（JSON body）时：数字/布尔原样替换（可作数字节点），
     * 其余值做 JSON 字符串内容转义（不加引号，适配 "key":"${var}" 写法，含引号等特殊字符也安全）。
     */
    private String render(String text, Map<String, String> vars, boolean jsonSafe) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String token = "${" + e.getKey() + "}";
            if (!text.contains(token)) {
                continue;
            }
            String v = e.getValue() == null ? "" : e.getValue();
            if (jsonSafe && !(v.matches("-?\\d+(\\.\\d+)?") || "true".equals(v) || "false".equals(v))) {
                v = escapeJson(v);
            }
            text = text.replace(token, v);
        }
        return text;
    }

    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private List<NotifyRequest.KeyValue> clean(List<NotifyRequest.KeyValue> list) {
        List<NotifyRequest.KeyValue> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (NotifyRequest.KeyValue kv : list) {
            if (kv != null && kv.getKey() != null && !kv.getKey().trim().isEmpty()) {
                out.add(kv);
            }
        }
        return out;
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String str(Number n) {
        return n == null ? "0" : String.valueOf(n);
    }

    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            // 通知线程池由首次提交任务的 JobRunner 线程懒创建，InheritableThreadLocal 会继承 logId，
            // 导致 log.warn 被 JobLogAppender 重复采集进任务日志；线程启动时先清除，任务日志统一由 appendTaskLog 显式写入
            Thread t = new Thread(() -> {
                JobLogCollector.clearCurrent();
                r.run();
            }, prefix + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
