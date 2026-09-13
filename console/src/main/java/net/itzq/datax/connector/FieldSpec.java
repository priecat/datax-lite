package net.itzq.datax.connector;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 前端数据源动态表单的字段描述。
 *
 * <p>由 brand profile 声明、经 {@code GET /api/meta/db-types} 下发，
 * 前端按 {@code type} 渲染控件，避免 {@code v-if} 链随库类型增长而爆炸。
 */
@Data
public class FieldSpec {

    /** 提交时的字段名（普通字段直接进 DataSource；类型特有字段进 props） */
    private String key;

    /** 展示标签 */
    private String label;

    /** 控件类型：text | number | password | select */
    private String type = "text";

    /** 是否必填（PG/DM 的 default_db 为 true） */
    private boolean required;

    /** 默认值 */
    private Object defaultValue;

    /** 占位提示 */
    private String placeholder;

    /** select 的候选项 */
    private List<Option> options;

    /** number 控件的下界（可为 null） */
    private Integer min;

    /** number 控件的上界（可为 null） */
    private Integer max;

    /**
     * 是否落进 props（false 表示 DataSource 上的具名属性，如 host/port/username/extraParams）。
     * 只有类型特有字段（如 OceanBase 的 compatMode）才为 true。
     */
    private boolean inProps;

    @Data
    public static class Option {
        private String value;
        private String label;

        public Option() {
        }

        public Option(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    // ── 工厂方法（让 profile 声明读起来像配置，而不是一堆 setter） ──

    public static FieldSpec text(String key, String label) {
        return base(key, label, "text");
    }

    public static FieldSpec text(String key, String label, boolean required) {
        FieldSpec f = base(key, label, "text");
        f.required = required;
        return f;
    }

    public static FieldSpec text(String key, String label, boolean required, String placeholder) {
        FieldSpec f = text(key, label, required);
        f.placeholder = placeholder;
        return f;
    }

    public static FieldSpec password(String key, String label) {
        return base(key, label, "password");
    }

    public static FieldSpec number(String key, String label, int defaultValue) {
        FieldSpec f = base(key, label, "number");
        f.defaultValue = defaultValue;
        return f;
    }

    public static FieldSpec port(String key, String label, int defaultPort) {
        FieldSpec f = number(key, label, defaultPort);
        f.min = 1;
        f.max = 65535;
        return f;
    }

    public static FieldSpec select(String key, String label, String defaultValue, Option... options) {
        FieldSpec f = base(key, label, "select");
        f.defaultValue = defaultValue;
        f.options = new ArrayList<>();
        for (Option o : options) {
            f.options.add(o);
        }
        return f;
    }

    /** 标记为"类型特有字段"，提交时进 props JSON */
    public FieldSpec props() {
        this.inProps = true;
        return this;
    }

    private static FieldSpec base(String key, String label, String type) {
        FieldSpec f = new FieldSpec();
        f.key = key;
        f.label = label;
        f.type = type;
        return f;
    }
}
