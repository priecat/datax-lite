package net.itzq.datax.dto;

import lombok.Data;

import java.util.List;

/**
 * 任务完成通知的 HTTP 请求配置（notify_config JSON 数组元素）
 */
@Data
public class NotifyRequest {

    /** 请求方法：GET/POST/PUT/DELETE */
    private String method;

    /** 目标 URL */
    private String url;

    /** 请求头（bodyType 为 form/json 且未显式指定 Content-Type 时自动注入） */
    private List<KeyValue> headers;

    /** URL 查询参数（拼接到 URL） */
    private List<KeyValue> params;

    /** 请求体类型：none / form(x-www-form-urlencoded) / json / raw */
    private String bodyType;

    /** bodyType=form 时的表单字段 */
    private List<KeyValue> formFields;

    /** bodyType=json/raw 时的请求体文本，支持 ${taskName} 等占位符 */
    private String rawBody;

    @Data
    public static class KeyValue {

        private String key;
        private String value;
    }
}
