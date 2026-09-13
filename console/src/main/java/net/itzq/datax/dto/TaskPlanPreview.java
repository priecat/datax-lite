package net.itzq.datax.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务执行计划预览（编辑器第四步展示）：
 * 只读元数据生成，不执行任何 DDL / 写入。
 */
@Data
public class TaskPlanPreview {

    /** 与执行时完全一致的 DataX job.json */
    private String jobJson;
    /** 全局提醒 */
    private List<String> warnings = new ArrayList<>();
    /** 任务开始时准备过程日志预览（与执行开始时的日志逐行一致，含 DDL 全文） */
    private List<String> prepareLogs = new ArrayList<>();
    private List<TablePlan> tables = new ArrayList<>();
    /** 任务完成 HTTP 通知 */
    private List<NotifyItem> notifies = new ArrayList<>();
    /** 源数据源连接信息（顶部方向展示，不含账号密码） */
    private DsInfo source;
    /** 目标数据源连接信息 */
    private DsInfo target;

    @Data
    public static class NotifyItem {
        private String method;
        private String url;
        private String bodyType;
    }

    @Data
    public static class DsInfo {
        private String name;
        private String type;
        private String host;
        private Integer port;
        private String database;
    }

    @Data
    public static class TablePlan {
        private String sourceTable;
        private String targetTable;
        private boolean autoCreateTable;
        private boolean recreateTable;
        private boolean truncateBefore;
        /** 读取过滤条件（不含 where 关键字） */
        private String where;
        /** 实际生效的切分键 */
        private String splitPk;
        /** splitPk 是否为默认主键 */
        private boolean splitPkDefault;
        /** 需要醒目提醒的差异/风险项 */
        private List<String> warnings = new ArrayList<>();
        /** 将执行的建表 DDL（重建/自动建表时；多条语句方言取首条） */
        private String createDdl;
        private List<ColumnPlan> columns = new ArrayList<>();
        private int selectedColumnCount;
    }

    @Data
    public static class ColumnPlan {
        private String source;
        private String sourceType;
        private String target;
        private boolean pk;
        private boolean selected;
        /** 源字段与目标字段名不同 */
        private boolean renamed;
        /** 目标表已存在但缺少该列（执行时自动补齐 ALTER ADD COLUMN） */
        private boolean missingInTarget;
    }
}
