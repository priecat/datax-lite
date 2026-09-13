package net.itzq.datax.engine;

import net.itzq.datax.common.BizException;
import net.itzq.datax.connector.AddColumnRequest;
import net.itzq.datax.connector.ConnectorRegistry;
import net.itzq.datax.connector.DbConnector;
import net.itzq.datax.connector.ResolvedColumn;
import net.itzq.datax.connector.TypeDecision;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.ColumnMeta;
import net.itzq.datax.dto.TableMapping;
import net.itzq.datax.entity.DataSource;
import net.itzq.datax.service.MetaService;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 表级同步准备逻辑：字段物化、切分键默认值、建表/补字段动作规划。
 * 执行（JobRunner）与预览（TaskService.previewPlan）共用同一份决策代码，保证预览结果与执行一致：
 * 规划只读元数据并产出待执行动作，由调用方决定是否真正执行 DDL。
 *
 * <p>跨库改造后本类不再拼任何 SQL：
 * <ul>
 *   <li>表名限定、删表/建表/补列语句一律由 {@code Dialect} 产出；</li>
 *   <li>列名比较一律走 {@code Dialect.normalizeIdentifier}（禁止裸 {@code toLowerCase()}，
 *       否则达梦/Oracle 下 {@code T_USER} 与 {@code t_user} 会被误判为不同列而重复 ALTER）；</li>
 *   <li>目标列类型由目标库的 {@code TypeMapping.decide} 决定。</li>
 * </ul>
 */
@Component
public class TablePrepareLogic {

    private final MetaService metaService;
    private final DdlGenerator ddlGenerator;
    private final ConnectorRegistry registry;

    public TablePrepareLogic(MetaService metaService, DdlGenerator ddlGenerator, ConnectorRegistry registry) {
        this.metaService = metaService;
        this.ddlGenerator = ddlGenerator;
        this.registry = registry;
    }

    /** 单个准备动作 */
    public static class PrepareAction {
        public static final String DROP = "DROP";
        public static final String CREATE = "CREATE";
        public static final String ALTER_ADD_COLUMN = "ALTER_ADD_COLUMN";

        private final String type;

        /**
         * 待执行语句列表。
         *
         * <p>之所以是列表：部分方言（达梦 DM）的 {@code ALTER ADD COLUMN} 不支持内联 COMMENT，
         * 必须追加独立的 {@code COMMENT ON COLUMN} 语句。MySQL 下恒为 1 条。
         */
        private final List<String> ddlList;

        /** 日志/预览展示文本 */
        private final String summary;

        public PrepareAction(String type, List<String> ddlList, String summary) {
            this.type = type;
            this.ddlList = ddlList == null ? Collections.<String>emptyList() : ddlList;
            this.summary = summary;
        }

        public PrepareAction(String type, String ddl, String summary) {
            this(type, ddl == null ? Collections.<String>emptyList()
                    : Collections.singletonList(ddl), summary);
        }

        public String getType() {
            return type;
        }

        public List<String> getDdlList() {
            return ddlList;
        }

        public String getSummary() {
            return summary;
        }
    }

    /** 单表准备计划（只读产出） */
    @Data
    public static class TablePreparePlan {
        private boolean targetExists;
        private List<PrepareAction> actions = new ArrayList<>();
        /** 需要提醒的风险项 */
        private List<String> warnings = new ArrayList<>();
        /** 普通说明信息 */
        private List<String> infos = new ArrayList<>();
        /** 目标表缺失、将被自动补齐的映射列 */
        private List<ColumnMapping> missingColumns = new ArrayList<>();
        /** 目标表多出的字段（不会被写入，也不会被删除） */
        private List<String> extraTargetColumns = new ArrayList<>();
        /** 将执行的建表 DDL（重建/自动建表时；多条语句时取首条，完整列表见 actions） */
        private String createDdl;
    }

    /**
     * 未做过字段映射的表默认同步全部字段：以源表实际列填充映射（全部选中、目标同名），
     * 使建表/补字段/生成 job 行为一致。
     * 打开过映射对话框但全部取消勾选的表不在此列，仍按"未选择任何同步字段"报错。
     */
    public List<ColumnMapping> materializeColumns(DataSource sourceDs, String sourceDb, TableMapping tm) {
        if (tm.getColumns() != null && !tm.getColumns().isEmpty()) {
            return tm.getColumns();
        }
        List<ColumnMeta> cols;
        try {
            cols = metaService.listColumns(sourceDs, sourceDb, tm.getSourceTable());
        } catch (BizException e) {
            throw new BizException("表[" + tm.getSourceTable() + "]读取字段列表失败: " + e.getMessage());
        }
        if (cols == null || cols.isEmpty()) {
            throw new BizException("表[" + tm.getSourceTable() + "]没有可同步的字段");
        }
        List<ColumnMapping> all = new ArrayList<>();
        for (ColumnMeta m : cols) {
            ColumnMapping c = new ColumnMapping();
            c.setSource(m.getName());
            c.setTarget(m.getName());
            c.setSourceType(m.getType());
            c.setComment(m.getComment());
            c.setSelected(true);
            c.setPk(m.isPk());
            all.add(c);
        }
        tm.setColumns(all);
        return all;
    }

    /** splitPk 默认取源表主键（读取失败时抛出，由调用方决定是否忽略） */
    public void applySplitPkDefault(DataSource sourceDs, String sourceDb, TableMapping tm) {
        if (tm.getSplitPk() != null && !tm.getSplitPk().trim().isEmpty()) {
            return;
        }
        List<ColumnMeta> cols = metaService.listColumns(sourceDs, sourceDb, tm.getSourceTable());
        if (cols != null) {
            for (ColumnMeta c : cols) {
                if (c.isPk()) {
                    tm.setSplitPk(c.getName());
                    break;
                }
            }
        }
    }

    /** 单表准备结果：规划 + 与执行日志逐行一致的准备过程日志 */
    @Data
    public static class PreparedTable {
        private TablePreparePlan plan;
        private List<String> logLines = new ArrayList<>();
    }

    /**
     * 单表准备过程（只读，不执行 DDL）：字段物化 -> 切分键默认值 -> 规划准备动作，
     * 并按执行日志相同的文案与顺序生成日志行。JobRunner 打印与预览展示共用此方法，
     * 保证"预览的准备过程"与"执行开始时的日志"逐行一致。
     */
    public PreparedTable prepare(DataSource sourceDs, String sourceDb,
                                 DataSource targetDs, String targetDb, TableMapping tm) {
        PreparedTable result = new PreparedTable();
        // 未配置字段映射的表默认同步全部字段
        boolean noMapping = tm.getColumns() == null || tm.getColumns().isEmpty();
        List<ColumnMapping> effective = materializeColumns(sourceDs, sourceDb, tm);
        if (noMapping) {
            result.getLogLines().add("表[" + tm.getSourceTable()
                    + "]未配置字段映射，默认同步全部 " + effective.size() + " 个字段");
        }
        // splitPk 默认取主键（读取失败不阻断执行）
        try {
            applySplitPkDefault(sourceDs, sourceDb, tm);
        } catch (BizException e) {
            result.getLogLines().add("读取源表主键失败: " + e.getMessage());
        }
        TablePreparePlan plan = planPrepareActions(sourceDs, sourceDb, targetDs, targetDb, tm);
        for (String w : plan.getWarnings()) {
            result.getLogLines().add(w);
        }
        for (String info : plan.getInfos()) {
            result.getLogLines().add(info);
        }
        for (PrepareAction action : plan.getActions()) {
            result.getLogLines().add(action.getSummary());
        }
        result.setPlan(plan);
        return result;
    }

    /**
     * 只读规划单表准备动作（不执行 DDL）。
     * 决策：勾选重建表 → DROP(若存在)+CREATE；目标不存在 → 自动建表或警告；
     * 目标存在 → 按"期望映射列 vs 实际列"补齐缺失字段（ALTER ADD COLUMN）。
     */
    public TablePreparePlan planPrepareActions(DataSource sourceDs, String sourceDb,
                                               DataSource targetDs, String targetDb, TableMapping tm) {
        TablePreparePlan plan = new TablePreparePlan();
        DbConnector target = registry.get(targetDs);
        boolean exists = metaService.tableExists(targetDs, targetDb, tm.getTargetTable());
        plan.setTargetExists(exists);

        if (tm.isRecreateTable()) {
            if (exists) {
                for (String dropDdl : target.dialect().buildDropTable(targetDb, tm.getTargetTable())) {
                    plan.getActions().add(new PrepareAction(PrepareAction.DROP, dropDdl,
                            "已勾选重建表，执行: " + dropDdl));
                }
            }
            DdlGenerator.BuildResult built = ddlGenerator.buildCreateTableStatements(sourceDs, sourceDb, targetDs, targetDb, tm);
            plan.setCreateDdl(built.getStatements().isEmpty() ? null : built.getStatements().get(0));
            for (String ddl : built.getStatements()) {
                plan.getActions().add(new PrepareAction(PrepareAction.CREATE, ddl,
                        "目标表[" + tm.getTargetTable() + "]重建: " + ddl));
            }
            plan.getWarnings().addAll(built.getTypeWarnings());
            return plan;
        }

        if (!exists) {
            if (tm.isAutoCreateTable()) {
                DdlGenerator.BuildResult built = ddlGenerator.buildCreateTableStatements(sourceDs, sourceDb, targetDs, targetDb, tm);
                plan.setCreateDdl(built.getStatements().isEmpty() ? null : built.getStatements().get(0));
                for (String ddl : built.getStatements()) {
                    plan.getActions().add(new PrepareAction(PrepareAction.CREATE, ddl,
                            "目标表[" + tm.getTargetTable() + "]不存在，自动建表: " + ddl));
                }
                plan.getWarnings().addAll(built.getTypeWarnings());
            } else {
                plan.getWarnings().add("目标表[" + tm.getTargetTable() + "]不存在，未启用自动建表，执行时写入将失败");
            }
            return plan;
        }

        // 目标表存在：按"期望映射列 vs 实际列"补齐缺失字段（目标多出的字段不影响按列写入，不做删除）
        List<ColumnMapping> expected;
        try {
            expected = expectedColumns(sourceDs, sourceDb, tm);
        } catch (BizException e) {
            plan.getWarnings().add("读取源表[" + tm.getSourceTable() + "]字段失败: " + e.getMessage() + "，跳过字段补齐");
            return plan;
        }
        List<ColumnMeta> actualCols;
        try {
            actualCols = metaService.listColumns(targetDs, targetDb, tm.getTargetTable());
        } catch (BizException e) {
            plan.getWarnings().add("读取目标表[" + tm.getTargetTable() + "]字段失败: " + e.getMessage() + "，跳过字段补齐");
            return plan;
        }

        // 实际列名按【目标库】的折叠规则归一化
        Set<String> actual = new HashSet<>();
        for (ColumnMeta c : actualCols) {
            actual.add(target.dialect().normalizeIdentifier(c.getName()));
        }
        List<ColumnMapping> missing = new ArrayList<>();
        for (ColumnMapping c : expected) {
            if (!actual.contains(target.dialect().normalizeIdentifier(c.getTarget()))) {
                missing.add(c);
            }
        }
        plan.setMissingColumns(missing);
        if (missing.isEmpty()) {
            plan.getInfos().add("目标表[" + tm.getTargetTable() + "]字段齐全(" + actualCols.size() + " 列)，无需调整");
        } else {
            for (ColumnMapping c : missing) {
                AddColumnResult r = buildAddColumnStatements(sourceDs, targetDs, target, targetDb, tm.getTargetTable(), c);
                for (String ddl : r.ddlList) {
                    plan.getActions().add(new PrepareAction(PrepareAction.ALTER_ADD_COLUMN, ddl,
                            "目标表[" + tm.getTargetTable() + "]缺少字段[" + c.getTarget() + "]，自动补齐: " + ddl));
                }
                plan.getWarnings().addAll(r.warnings);
            }
        }

        // 期望列名按【目标库】的折叠规则归一化
        Set<String> expectedNames = new HashSet<>();
        for (ColumnMapping c : expected) {
            expectedNames.add(target.dialect().normalizeIdentifier(c.getTarget()));
        }
        for (ColumnMeta c : actualCols) {
            if (!expectedNames.contains(target.dialect().normalizeIdentifier(c.getName()))) {
                plan.getExtraTargetColumns().add(c.getName());
            }
        }
        return plan;
    }

    /** 补列产出：DDL 语句 + 类型裁决 WARN 文本 */
    private static class AddColumnResult {
        private final List<String> ddlList = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
    }

    /** 生成补列语句（目标类型由目标库的类型映射决定，走「显式指定 > 类型字典 > 恒等回退」裁决链） */
    private AddColumnResult buildAddColumnStatements(DataSource sourceDs, DataSource targetDs, DbConnector target,
                                                     String targetDb, String targetTable, ColumnMapping c) {
        AddColumnResult result = new AddColumnResult();
        AddColumnRequest req = new AddColumnRequest();
        req.setTargetDb(targetDb);
        req.setTargetTable(targetTable);
        ResolvedColumn rc = new ResolvedColumn();
        rc.setMapping(c);
        rc.setSourceMeta(null);
        // 补列路径没有源列元数据：类型直接取映射上的 sourceType（与原实现一致）
        TypeDecision decision = target.typeMapping().decide(c, null,
                ConnectorRegistry.effectiveBrand(sourceDs), ConnectorRegistry.effectiveBrand(targetDs));
        rc.setTargetType(decision.getTargetType());
        rc.setPrimaryKey(c.isPk());
        req.setColumn(rc);
        result.ddlList.addAll(target.dialect().buildAddColumn(req));
        for (net.itzq.datax.connector.TypeIssue issue : decision.getIssues()) {
            result.warnings.add("表[" + targetTable + "] 列[" + issue.getColumn() + "] " + issue.getMessage());
        }
        return result;
    }

    /** 映射期望写入的目标列（含类型/注释信息）：取 selected 列；无映射信息时退化为源表全部列 */
    public List<ColumnMapping> expectedColumns(DataSource sourceDs, String sourceDb, TableMapping tm) {
        List<ColumnMapping> exp = new ArrayList<>();
        if (tm.getColumns() != null) {
            for (ColumnMapping c : tm.getColumns()) {
                if (c.isSelected()) {
                    exp.add(c);
                }
            }
        }
        boolean noType = exp.stream().anyMatch(c -> c.getSourceType() == null || c.getSourceType().trim().isEmpty());
        if (!exp.isEmpty() && !noType) {
            return exp;
        }
        // 用源表元数据补全类型与注释（target 不同名时以映射为准，元数据按 source 列匹配）
        java.util.Map<String, ColumnMeta> sourceMeta = new java.util.LinkedHashMap<>();
        // 源列名按【源库】的折叠规则归一化
        final DbConnector sourceConnector = registry.get(sourceDs);
        for (ColumnMeta m : metaService.listColumns(sourceDs, sourceDb, tm.getSourceTable())) {
            sourceMeta.put(sourceConnector.dialect().normalizeIdentifier(m.getName()), m);
        }
        if (exp.isEmpty()) {
            for (ColumnMeta m : sourceMeta.values()) {
                ColumnMapping c = new ColumnMapping();
                c.setSource(m.getName());
                c.setTarget(m.getName());
                c.setSourceType(m.getType());
                c.setComment(m.getComment());
                c.setSelected(true);
                exp.add(c);
            }
            return exp;
        }
        for (ColumnMapping c : exp) {
            ColumnMeta m = sourceMeta.get(c.getSource() == null ? "" : sourceConnector.dialect().normalizeIdentifier(c.getSource()));
            if (m != null) {
                if (c.getSourceType() == null || c.getSourceType().trim().isEmpty()) {
                    c.setSourceType(m.getType());
                }
                if (c.getComment() == null || c.getComment().trim().isEmpty()) {
                    c.setComment(m.getComment());
                }
            }
        }
        return exp;
    }
}
