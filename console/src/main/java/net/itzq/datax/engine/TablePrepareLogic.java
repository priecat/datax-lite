package net.itzq.datax.engine;

import net.itzq.datax.common.BizException;
import net.itzq.datax.connector.AddColumnRequest;
import net.itzq.datax.connector.ConnectorRegistry;
import net.itzq.datax.connector.DbConnector;
import net.itzq.datax.connector.Dialect;
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
        /** 移除目标表已有外键（立即执行：数据写入期间目标表上不能有外键） */
        public static final String DROP_FK = "DROP_FK";
        /** 外键恢复（延迟动作：数据同步完成后统一执行，写入期间目标表上不能有外键） */
        public static final String RESTORE_FK = "RESTORE_FK";
        /** 移除目标表已有触发器（立即执行：写入期间触发器逐行触发会改变数据语义） */
        public static final String DROP_TRIGGER = "DROP_TRIGGER";
        /** 触发器恢复（延迟动作：数据同步完成后统一执行） */
        public static final String RESTORE_TRIGGER = "RESTORE_TRIGGER";
        /** 自增计数器对齐（延迟动作：数据同步完成后统一执行，写入显式 id 只会向上抬升计数器，写完对齐才准确） */
        public static final String RESTORE_AUTOINC = "RESTORE_AUTOINC";

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
        /** 对应的表映射（编排层回填，供预览组装表级展示） */
        private TableMapping tableMapping;
        private TablePreparePlan plan;
        private List<String> logLines = new ArrayList<>();
        /** 规划失败原因（仅预览模式降级时非空；执行模式规划失败直接抛出） */
        private String errorMessage;
    }

    /**
     * 配置与目标库现状冲突：准备阶段直接阻断（预览与执行都不放行）——
     * 典型场景：未勾选"包含外键/触发器"，但目标表上残留外键/触发器，
     * 带着它们写数据会触发外键冲突或改变写入语义。
     */
    public static class PrepareBlockException extends BizException {
        public PrepareBlockException(String message) {
            super(message);
        }
    }

    /** 准备阶段统一产出：全部日志行 + 逐表结果（预览与执行共用同一路径生成） */
    @Data
    public static class PrepareReport {
        /** 按执行顺序排列的准备日志。预览模式末尾为"数据同步完成后统一恢复外键"段；
         *  执行模式该段在 DataX 成功后由 {@link #restoreForeignKeys} 实时输出到任务日志 */
        private List<String> logLines = new ArrayList<>();
        /** 逐表准备结果，顺序与启用表一致 */
        private List<PreparedTable> tables = new ArrayList<>();
        /** 延迟恢复的外键动作（数据同步完成后由 JobRunner 统一执行） */
        private List<PrepareAction> deferredFk = new ArrayList<>();
        /** 延迟恢复的触发器动作（数据同步完成后由 JobRunner 统一执行） */
        private List<PrepareAction> deferredTriggers = new ArrayList<>();
        /** 延迟执行的自增计数器对齐动作（数据同步完成后由 JobRunner 统一执行） */
        private List<PrepareAction> deferredAutoInc = new ArrayList<>();
        /** 统一恢复的外键条数 */
        private int restoreFkCount;
        /** 统一恢复的触发器条数 */
        private int restoreTriggerCount;
        /** 统一对齐的自增计数器条数 */
        private int restoreAutoIncCount;
    }

    /**
     * 准备阶段统一编排——预览与执行的<b>唯一路径</b>：
     * 逐表规划准备动作 → 产出日志行 → 执行 DDL → 收集延迟恢复的外键。
     *
     * <p>外键策略（与 Navicat/Workbench/pgloader 的"延迟恢复"一致）：
     * 数据写入期间目标表上不能有外键（DataX 多线程乱序写入会触发 1452），
     * 因此本阶段只保证"表存在且无外键"，恢复动作由调用方在数据同步成功后
     * 调 {@link #restoreForeignKeys} 统一执行（第三阶段）。
     *
     * <p>preview=true：只生成日志与计划，不执行任何 DDL（单表规划失败降级为警告行，继续其余表）；
     * preview=false：逐条执行 DDL，任何失败直接抛出——准备阶段报错不得进入数据同步阶段。
     * 日志行在两种模式下由同一段代码产生，保证预览与执行日志逐行一致，不存在第二套拼装。
     *
     * @param lineSink 执行模式传入日志实时回调（边执行边输出，失败时已产出的行保留在任务日志中）；
     *                 预览模式传 null，调用方从 report.getLogLines() 取
     */
    public PrepareReport runPrepare(DataSource sourceDs, String sourceDb, DataSource targetDs, String targetDb,
                                    net.itzq.datax.dto.TaskConfig config,
                                    net.itzq.datax.dto.StructureOptions structureOptions, boolean preview,
                                    java.util.function.Consumer<String> lineSink) {
        PrepareReport report = new PrepareReport();
        List<TableMapping> enabled = new ArrayList<>();
        if (config.getTables() != null) {
            for (TableMapping tm : config.getTables()) {
                if (tm.isEnabled()) {
                    enabled.add(tm);
                }
            }
        }
        for (TableMapping tm : enabled) {
            PreparedTable prepared;
            try {
                prepared = prepare(sourceDs, sourceDb, targetDs, targetDb, tm, structureOptions);
            } catch (BizException e) {
                // 配置冲突类错误（残留外键/触发器）预览也要直接报错，不能降级为"预览不完整"警告
                if (e instanceof PrepareBlockException) {
                    throw e;
                }
                if (preview) {
                    PreparedTable failed = new PreparedTable();
                    failed.setTableMapping(tm);
                    failed.setErrorMessage(e.getMessage());
                    report.getTables().add(failed);
                    addLine(report, lineSink, "表[" + tm.getSourceTable()
                            + "]读取元数据失败，预览不完整: " + e.getMessage());
                    continue;
                }
                throw e;
            }
            prepared.setTableMapping(tm);
            report.getTables().add(prepared);
            for (String line : prepared.getLogLines()) {
                addLine(report, lineSink, line);
            }
            for (PrepareAction action : prepared.getPlan().getActions()) {
                if (PrepareAction.RESTORE_FK.equals(action.getType())) {
                    report.getDeferredFk().add(action);
                    continue;
                }
                if (PrepareAction.RESTORE_TRIGGER.equals(action.getType())) {
                    report.getDeferredTriggers().add(action);
                    continue;
                }
                if (PrepareAction.RESTORE_AUTOINC.equals(action.getType())) {
                    report.getDeferredAutoInc().add(action);
                    continue;
                }
                if (!preview) {
                    // 单个动作可能含多条语句（如达梦的 ALTER ADD COLUMN + COMMENT ON COLUMN）
                    for (String ddl : action.getDdlList()) {
                        if (ddl != null) {
                            metaService.executeDdl(targetDs, targetDb, ddl);
                        }
                    }
                }
            }
        }
        validateForeignKeyParents(targetDs, targetDb, enabled, report);
        return report;
    }

    /**
     * 前置校验：恢复列表引用的父表必须在本任务范围内、或目标库中已存在，
     * 否则同步完成后恢复必然失败——在准备阶段就报错阻断，不进入数据同步阶段。
     */
    private void validateForeignKeyParents(DataSource targetDs, String targetDb, List<TableMapping> enabled,
                                           PrepareReport report) {
        if (report.getDeferredFk().isEmpty()) {
            return;
        }
        DbConnector targetConnector = registry.get(targetDs);
        Set<String> inTask = new HashSet<>();
        for (TableMapping tm : enabled) {
            inTask.add(tm.getTargetTable());
            inTask.add(targetConnector.dialect().normalizeIdentifier(tm.getTargetTable()));
        }
        for (PrepareAction a : report.getDeferredFk()) {
            for (String ddl : a.getDdlList()) {
                String parent = referencedTableOf(ddl);
                if (parent == null || parent.isEmpty()) {
                    continue;
                }
                if (inTask.contains(parent)
                        || inTask.contains(targetConnector.dialect().normalizeIdentifier(parent))) {
                    continue;
                }
                if (metaService.tableExists(targetDs, targetDb, parent)) {
                    continue;
                }
                throw new BizException("外键恢复校验失败: 语句[" + ddl + "]引用的父表[" + parent
                        + "]既不在任务范围内，目标库[" + targetDb + "]中也不存在，无法完成外键恢复");
            }
        }
    }

    /** 从恢复语句中解析 REFERENCES 引用的父表名（剥离引号与库名前缀） */
    private static String referencedTableOf(String addDdl) {
        int i = addDdl.toUpperCase().indexOf("REFERENCES");
        if (i < 0) {
            return null;
        }
        String rest = addDdl.substring(i + "REFERENCES".length()).trim().replace("`", "");
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^([A-Za-z0-9_$\\.]+)").matcher(rest);
        if (!m.find()) {
            return null;
        }
        String token = m.group(1);
        int dot = token.lastIndexOf('.');
        return dot >= 0 ? token.substring(dot + 1) : token;
    }

    /**
     * 第三阶段：数据同步成功后统一恢复外键（预览与执行共用同一段标题与语句生成）。
     */
    public void restoreForeignKeys(PrepareReport report, boolean execute, DataSource targetDs, String targetDb,
                                   java.util.function.Consumer<String> lineSink) {
        report.setRestoreFkCount(totalDdl(report.getDeferredFk()));
        runDeferred(report, report.getDeferredFk(), execute, targetDs, targetDb, lineSink,
                "── 数据同步完成后统一恢复外键（%d 条）──",
                "恢复外键失败: %s",
                "数据已同步完成，但恢复外键失败 %d 条，任务标记为失败: ");
    }

    /**
     * 第三阶段：数据同步成功后统一恢复触发器（与外键同一套延迟执行机制）。
     * 触发器必须在外键恢复之后再重建，确保恢复阶段表处于最终结构。
     */
    public void restoreTriggers(PrepareReport report, boolean execute, DataSource targetDs, String targetDb,
                                java.util.function.Consumer<String> lineSink) {
        report.setRestoreTriggerCount(totalDdl(report.getDeferredTriggers()));
        runDeferred(report, report.getDeferredTriggers(), execute, targetDs, targetDb, lineSink,
                "── 数据同步完成后统一恢复触发器（%d 条）──",
                "恢复触发器失败: %s",
                "数据已同步完成，但恢复触发器失败 %d 条，任务标记为失败: ");
    }

    /**
     * 第三阶段：数据同步成功后统一对齐自增计数器（与外键/触发器同一套延迟执行机制）。
     * 放在恢复流程最后：此时数据与表结构均已就绪，计数器一次性对齐为源表快照值。
     */
    public void restoreAutoIncrement(PrepareReport report, boolean execute, DataSource targetDs, String targetDb,
                                     java.util.function.Consumer<String> lineSink) {
        report.setRestoreAutoIncCount(totalDdl(report.getDeferredAutoInc()));
        runDeferred(report, report.getDeferredAutoInc(), execute, targetDs, targetDb, lineSink,
                "── 数据同步完成后对齐自增计数器（%d 条）──",
                "对齐自增计数器失败: %s",
                "数据已同步完成，但对齐自增计数器失败 %d 条，任务标记为失败: ");
    }

    private static int totalDdl(List<PrepareAction> actions) {
        int n = 0;
        for (PrepareAction a : actions) {
            n += a.getDdlList().size();
        }
        return n;
    }

    /**
     * 延迟动作统一执行器（预览与执行共用同一段日志与语句生成）。
     *
     * <p>execute=false（预览）：只把标题与语句行追加进 report.getLogLines()；
     * execute=true（执行）：逐条执行——先入日志再执行，失败时用户能看到中断发生在哪条语句；
     * 单条失败记录日志后继续尝试其余语句，最后统一抛出。此时数据已同步完成，
     * 任务仍标记为失败，日志会明确"数据已同步"避免误判数据丢失。
     */
    private void runDeferred(PrepareReport report, List<PrepareAction> actions, boolean execute,
                             DataSource targetDs, String targetDb, java.util.function.Consumer<String> lineSink,
                             String headerFmt, String itemFailFmt, String throwFmt) {
        if (actions.isEmpty()) {
            return;
        }
        addLine(report, lineSink, String.format(headerFmt, totalDdl(actions)));
        int failed = 0;
        String firstError = null;
        for (PrepareAction action : actions) {
            for (String ddl : action.getDdlList()) {
                addLine(report, lineSink, ddl);
                if (execute) {
                    try {
                        metaService.executeDdl(targetDs, targetDb, ddl);
                    } catch (RuntimeException e) {
                        failed++;
                        if (firstError == null) {
                            firstError = e.getMessage();
                        }
                        addLine(report, lineSink, String.format(itemFailFmt, e.getMessage()));
                    }
                }
            }
        }
        if (failed > 0) {
            throw new BizException(String.format(throwFmt, failed) + firstError);
        }
    }

    /** 日志行：写入 report，执行模式下同步回调实时输出 */
    private void addLine(PrepareReport report, java.util.function.Consumer<String> sink, String line) {
        report.getLogLines().add(line);
        if (sink != null) {
            sink.accept(line);
        }
    }

    /**
     * 单表准备过程（只读，不执行 DDL）：字段物化 -> 切分键默认值 -> 规划准备动作，
     * 并按执行日志相同的文案与顺序生成日志行。JobRunner 打印与预览展示共用此方法，
     * 保证"预览的准备过程"与"执行开始时的日志"逐行一致。
     */
    public PreparedTable prepare(DataSource sourceDs, String sourceDb,
                                 DataSource targetDs, String targetDb, TableMapping tm,
                                 net.itzq.datax.dto.StructureOptions structureOptions) {
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
        TablePreparePlan plan = planPrepareActions(sourceDs, sourceDb, targetDs, targetDb, tm, structureOptions);
        for (String w : plan.getWarnings()) {
            result.getLogLines().add(w);
        }
        for (String info : plan.getInfos()) {
            result.getLogLines().add(info);
        }
        for (PrepareAction action : plan.getActions()) {
            // 外键/触发器恢复是延迟动作：数据同步完成后才执行，日志/预览中由调用方统一追加到计划末尾，
            // 不跟随本表的准备段（否则会被误读为"建完这张表就立刻执行"）
            if (PrepareAction.RESTORE_FK.equals(action.getType())
                    || PrepareAction.RESTORE_TRIGGER.equals(action.getType())
                    || PrepareAction.RESTORE_AUTOINC.equals(action.getType())) {
                continue;
            }
            result.getLogLines().add(action.getSummary());
        }
        result.setPlan(plan);
        return result;
    }

    /**
     * 只读规划单表准备动作（不执行 DDL）。
     * 决策：勾选重建表 → DROP(若存在)+CREATE；目标不存在 → 自动建表或警告；
     * 目标存在 → 按"期望映射列 vs 实际列"补齐缺失字段（ALTER ADD COLUMN）。
     * 末尾统一追加自增计数器对齐规划（{@link #appendAutoIncAction}）。
     */
    public TablePreparePlan planPrepareActions(DataSource sourceDs, String sourceDb,
                                               DataSource targetDs, String targetDb, TableMapping tm,
                                               net.itzq.datax.dto.StructureOptions structureOptions) {
        TablePreparePlan plan = planPrepareActionsInner(sourceDs, sourceDb, targetDs, targetDb, tm, structureOptions);
        appendAutoIncAction(sourceDs, sourceDb, targetDs, targetDb, tm, plan, structureOptions, registry.get(targetDs));
        return plan;
    }

    private TablePreparePlan planPrepareActionsInner(DataSource sourceDs, String sourceDb,
                                                     DataSource targetDs, String targetDb, TableMapping tm,
                                                     net.itzq.datax.dto.StructureOptions structureOptions) {
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
            DdlGenerator.BuildResult built = ddlGenerator.buildCreateTableStatements(
                    sourceDs, sourceDb, targetDs, targetDb, tm, structureOptions);
            plan.setCreateDdl(built.getStatements().isEmpty() ? null : built.getStatements().get(0));
            for (String ddl : built.getStatements()) {
                plan.getActions().add(new PrepareAction(PrepareAction.CREATE, ddl,
                        "目标表[" + tm.getTargetTable() + "]重建: " + ddl));
            }
            plan.getWarnings().addAll(built.getTypeWarnings());
            appendRestoreFkActions(plan, built, tm);
            // 源触发器在第三阶段恢复；重建路径下目标旧表连触发器随 DROP TABLE 一并消失，无需显式移除
            appendTriggerActions(sourceDs, sourceDb, targetDs, targetDb, tm, plan, structureOptions, target, false);
            return plan;
        }

        if (!exists) {
            if (tm.isAutoCreateTable()) {
                DdlGenerator.BuildResult built = ddlGenerator.buildCreateTableStatements(
                        sourceDs, sourceDb, targetDs, targetDb, tm, structureOptions);
                plan.setCreateDdl(built.getStatements().isEmpty() ? null : built.getStatements().get(0));
                for (String ddl : built.getStatements()) {
                    plan.getActions().add(new PrepareAction(PrepareAction.CREATE, ddl,
                            "目标表[" + tm.getTargetTable() + "]不存在，自动建表: " + ddl));
                }
                plan.getWarnings().addAll(built.getTypeWarnings());
                appendRestoreFkActions(plan, built, tm);
                appendTriggerActions(sourceDs, sourceDb, targetDs, targetDb, tm, plan, structureOptions, target, false);
            } else {
                plan.getWarnings().add("目标表[" + tm.getTargetTable() + "]不存在，未启用自动建表，执行时写入将失败");
            }
            return plan;
        }

        // 目标表存在：先处理外键（放在字段补齐之前，避免被下方读取失败的提前 return 跳过），
        // 再按"期望映射列 vs 实际列"补齐缺失字段（目标多出的字段不影响按列写入，不做删除）
        // 目标表上可能残留外键（上次同步恢复的、或外部创建的），DataX 乱序写入会触发 1452——
        // 开启外键选项：先移除，数据同步完成后统一恢复；
        // 未开启：检查残留，有则直接报错阻断（预览与执行都拦），不带隐患进入数据同步
        net.itzq.datax.dto.StructureOptions so = net.itzq.datax.dto.StructureOptions.safe(structureOptions);
        if (target.capabilities().nativeShowCreate()) {
            String raw = metaService.showCreateTable(targetDs, targetDb, tm.getTargetTable());
            String body = target.dialect().extractTableBody(raw);
            java.util.LinkedHashMap<String, String> fks = target.dialect().parseForeignKeyDefs(body);
            if (so.isForeignKey()) {
                for (java.util.Map.Entry<String, String> e : fks.entrySet()) {
                    String dropDdl = target.dialect().buildDropForeignKey(targetDb, tm.getTargetTable(), e.getKey());
                    plan.getActions().add(new PrepareAction(PrepareAction.DROP_FK, dropDdl,
                            "目标表[" + tm.getTargetTable() + "]存在外键约束[" + e.getKey()
                                    + "]，先移除（数据同步完成后统一恢复）: " + dropDdl));
                    String addDdl = target.dialect().buildAddForeignKey(targetDb, tm.getTargetTable(), e.getValue());
                    plan.getActions().add(new PrepareAction(PrepareAction.RESTORE_FK, addDdl,
                            "表[" + tm.getTargetTable() + "]数据同步完成后恢复外键: " + addDdl));
                }
            } else if (!fks.isEmpty()) {
                throw new PrepareBlockException("目标表[" + tm.getTargetTable() + "]存在外键约束["
                        + String.join("、", fks.keySet())
                        + "]：任务未勾选\"包含外键约束\"，DataX 写入期间会被外键校验拒绝（多线程乱序写入）。"
                        + "请勾选\"包含外键约束\"（同步期间先移除、完成后恢复），或改用\"重建表\"，或先手动处理目标表外键");
            }
        }
        // 目标表存在 → 目标已有触发器需要显式移除（写入期间逐行触发会改变数据语义）
        appendTriggerActions(sourceDs, sourceDb, targetDs, targetDb, tm, plan, structureOptions, target, true);
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

    /** 外键恢复动作：建表语句中剥离的外键，数据同步完成后由执行器统一执行（延迟动作） */
    private void appendRestoreFkActions(TablePreparePlan plan, DdlGenerator.BuildResult built, TableMapping tm) {
        for (String fk : built.getPostStatements()) {
            plan.getActions().add(new PrepareAction(PrepareAction.RESTORE_FK, fk,
                    "表[" + tm.getTargetTable() + "]数据同步完成后恢复外键: " + fk));
        }
    }

    /**
     * 自增计数器对齐规划（第三阶段延迟动作）：autoIncrement 开关开启且目标方言支持时，
     * 读取源表计数器快照 n（下一个将分配的自增 id），同步完成后 ALTER 目标表 AUTO_INCREMENT=n。
     *
     * <p>为什么放第三阶段而不是建表语句：TRUNCATE 会把计数器重置回初始值，写入显式 id 后
     * 计数器只到 max(id)+1——源表删过尾部数据（n &gt; max(id)+1）时目标计数器会低于源，
     * 后续两边自增 id 分叉。同步完成后统一 ALTER 对齐（MySQL 只向上调整，幂等安全），
     * 对新建表/已有表/truncate 所有路径生效；identity 建表语句也一律不再携带 AUTO_INCREMENT=n
     * （结构 vs 状态分离）。源表无自增列（n=null 或 &lt;=1）或方言不支持时无动作。
     */
    private void appendAutoIncAction(DataSource sourceDs, String sourceDb,
                                     DataSource targetDs, String targetDb, TableMapping tm,
                                     TablePreparePlan plan, net.itzq.datax.dto.StructureOptions structureOptions,
                                     DbConnector target) {
        net.itzq.datax.dto.StructureOptions so = net.itzq.datax.dto.StructureOptions.safe(structureOptions);
        if (!so.isAutoIncrement()) {
            return;
        }
        Long n;
        try {
            n = metaService.readAutoIncrement(sourceDs, sourceDb, tm.getSourceTable());
        } catch (BizException e) {
            plan.getWarnings().add("读取源表[" + tm.getSourceTable() + "]自增计数器失败，跳过对齐: " + e.getMessage());
            return;
        }
        if (n == null || n <= 1) {
            return;
        }
        String ddl = target.dialect().buildSetAutoIncrement(targetDb, tm.getTargetTable(), n);
        if (ddl == null) {
            return;
        }
        plan.getActions().add(new PrepareAction(PrepareAction.RESTORE_AUTOINC, ddl,
                "表[" + tm.getTargetTable() + "]数据同步完成后对齐自增计数器（源=" + n + "）: " + ddl));
    }

    /**
     * 触发器动作规划（两个独立开关）：
     * <ul>
     *   <li>移除触发器（dropTrigger，默认勾选）：目标已有触发器在写入期间每行都会触发、改变写入语义
     *       （审计表膨胀、字段被改写等），准备阶段先 DROP。未勾选时不做任何移除，
     *       但目标表残留触发器会直接报错阻断（预览与执行都拦，不带隐患写入）。</li>
     *   <li>重建触发器（recreateTrigger，默认不勾选）：数据同步完成后（第三阶段）按源库权威定义
     *       在目标表重建，表名重定向到目标表；目标独有触发器（源库无同名定义）按原定义恢复，避免静默丢失。
     *       仅移除不重建时，目标触发器在本次同步后保持移除状态，需要时手动重建或下次勾选本项。</li>
     * </ul>
     */
    private void appendTriggerActions(DataSource sourceDs, String sourceDb,
                                      DataSource targetDs, String targetDb, TableMapping tm,
                                      TablePreparePlan plan, net.itzq.datax.dto.StructureOptions structureOptions,
                                      DbConnector target, boolean dropExisting) {
        net.itzq.datax.dto.StructureOptions so = net.itzq.datax.dto.StructureOptions.safe(structureOptions);
        if (!target.capabilities().supportsTriggers()) {
            return;
        }
        List<Dialect.TriggerDef> existing = dropExisting
                ? metaService.listTriggers(targetDs, targetDb, tm.getTargetTable())
                : java.util.Collections.<Dialect.TriggerDef>emptyList();
        if (dropExisting && !existing.isEmpty() && !so.isDropTrigger()) {
            // 移除触发器未勾选：残留触发器会在写入期间逐行触发、改变数据语义，直接报错阻断
            List<String> names = new ArrayList<String>();
            for (Dialect.TriggerDef t : existing) {
                names.add(t.getName());
            }
            throw new PrepareBlockException("目标表[" + tm.getTargetTable() + "]存在触发器["
                    + String.join("、", names)
                    + "]：任务未勾选\"移除触发器\"，DataX 写入期间触发器会逐行触发、改变数据语义。"
                    + "请勾选\"移除触发器\"，或改用\"重建表\"，或先手动处理目标表触发器");
        }
        if (so.isRecreateTrigger()) {
            DbConnector source = registry.get(sourceDs);
            if (source.capabilities().supportsTriggers()) {
                // 源触发器：权威定义，同名覆盖目标（第三阶段重建）
                Set<String> srcNames = new HashSet<String>();
                for (Dialect.TriggerDef t : metaService.listTriggers(sourceDs, sourceDb, tm.getSourceTable())) {
                    srcNames.add(t.getName());
                    String ddl = target.dialect().retargetTrigger(t.getCreateSql(), targetDb, tm.getTargetTable());
                    plan.getActions().add(new PrepareAction(PrepareAction.RESTORE_TRIGGER, ddl,
                            "表[" + tm.getTargetTable() + "]数据同步完成后恢复触发器: " + ddl));
                }
                // 目标独有触发器：源库没有同名定义，按原定义恢复，避免静默丢失
                for (Dialect.TriggerDef t : existing) {
                    if (!srcNames.contains(t.getName())) {
                        String ddl = target.dialect().retargetTrigger(t.getCreateSql(), targetDb, tm.getTargetTable());
                        plan.getActions().add(new PrepareAction(PrepareAction.RESTORE_TRIGGER, ddl,
                                "表[" + tm.getTargetTable() + "]数据同步完成后恢复目标原有触发器: " + ddl));
                    }
                }
            }
        }
        if (so.isDropTrigger()) {
            for (Dialect.TriggerDef t : existing) {
                String dropDdl = target.dialect().buildDropTrigger(targetDb, t.getName());
                String note = so.isRecreateTrigger() ? "（数据同步完成后统一恢复）" : "（任务未勾选重建触发器，同步后不会恢复）";
                plan.getActions().add(new PrepareAction(PrepareAction.DROP_TRIGGER, dropDdl,
                        "目标表[" + tm.getTargetTable() + "]存在触发器[" + t.getName()
                                + "]，写入期间先移除" + note + ": " + dropDdl));
            }
        }
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
