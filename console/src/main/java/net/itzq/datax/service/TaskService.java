package net.itzq.datax.service;

import net.itzq.datax.common.BizException;
import net.itzq.datax.common.IdGen;
import net.itzq.datax.connector.ConnectorRegistry;
import net.itzq.datax.connector.Dialect;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.ColumnMeta;
import net.itzq.datax.dto.TaskConfig;
import net.itzq.datax.dto.TaskPlanPreview;
import net.itzq.datax.dto.TableMapping;
import net.itzq.datax.engine.DataxJobBuilder;
import net.itzq.datax.engine.JobExecutor;
import net.itzq.datax.engine.TablePrepareLogic;
import net.itzq.datax.entity.DataSource;
import net.itzq.datax.entity.SyncTask;
import net.itzq.datax.mapper.DataSourceMapper;
import net.itzq.datax.mapper.SyncTaskMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TaskService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final SyncTaskMapper taskMapper;
    private final DataSourceMapper dataSourceMapper;
    private final DataxJobBuilder jobBuilder;
    private final JobExecutor jobExecutor;
    private final MetaService metaService;
    private final TablePrepareLogic tablePrepareLogic;
    private final ConnectorRegistry connectorRegistry;

    public TaskService(SyncTaskMapper taskMapper, DataSourceMapper dataSourceMapper,
                       DataxJobBuilder jobBuilder, JobExecutor jobExecutor,
                       MetaService metaService, TablePrepareLogic tablePrepareLogic,
                       ConnectorRegistry connectorRegistry) {
        this.taskMapper = taskMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.jobBuilder = jobBuilder;
        this.jobExecutor = jobExecutor;
        this.metaService = metaService;
        this.tablePrepareLogic = tablePrepareLogic;
        this.connectorRegistry = connectorRegistry;
    }

    public List<SyncTask> list() {
        return taskMapper.findAll();
    }

    public SyncTask get(String id) {
        SyncTask task = taskMapper.findById(id);
        if (task == null) {
            throw new BizException("同步任务不存在");
        }
        return task;
    }

    public SyncTask create(SyncTask task) {
        validate(task);
        task.setId(IdGen.uuid());
        task.setCreateDate(new Date());
        task.setDelFlag("0");
        task.setConfig(writeConfig(task.getConfig()));
        taskMapper.insert(task);
        return task;
    }

    public SyncTask update(String id, SyncTask task) {
        validate(task);
        SyncTask old = get(id);
        old.setName(task.getName());
        old.setSourceDatasourceId(task.getSourceDatasourceId());
        old.setSourceDatabase(task.getSourceDatabase());
        old.setTargetDatasourceId(task.getTargetDatasourceId());
        old.setTargetDatabase(task.getTargetDatabase());
        old.setConfig(writeConfig(task.getConfig()));
        old.setNotifyConfig(task.getNotifyConfig());
        old.setDescription(task.getDescription());
        old.setUpdateDate(new Date());
        taskMapper.update(old);
        return old;
    }

    public void delete(String id) {
        get(id);
        taskMapper.delete(id, System.currentTimeMillis());
    }

    /** 手动执行一次 */
    public String run(String taskId) {
        return run(taskId, "manual", null);
    }

    public String run(String taskId, String triggerType, String scheduleId) {
        SyncTask task = get(taskId);
        DataSource sourceDs = requireDatasource(task.getSourceDatasourceId());
        DataSource targetDs = requireDatasource(task.getTargetDatasourceId());
        TaskConfig config = parseConfig(task.getConfig());
        if (config == null || config.getTables() == null || config.getTables().isEmpty()) {
            throw new BizException("任务尚未配置同步对象");
        }
        // 同一任务的并发冲突由 JobExecutor 原子占位判定：冲突时写入"跳过"执行记录并立即结束，不抛异常
        return jobExecutor.submit(task, sourceDs, targetDs, config, triggerType, scheduleId);
    }

    /** 预览生成的 DataX job.json（调试用） */
    public String previewJobJson(String taskId) {
        SyncTask task = get(taskId);
        DataSource sourceDs = requireDatasource(task.getSourceDatasourceId());
        DataSource targetDs = requireDatasource(task.getTargetDatasourceId());
        TaskConfig config = parseConfig(task.getConfig());
        if (config == null) {
            throw new BizException("任务尚未配置同步对象");
        }
        return jobBuilder.build(sourceDs, task.getSourceDatabase(), targetDs, task.getTargetDatabase(), config);
    }

    /**
     * 生成任务执行计划预览（编辑器第四步）。
     * 与 JobRunner.run 完全相同的顺序：先物化字段映射/切分键（TablePrepareLogic 共用代码，
     * 会修改解析后的 config 副本），再生成 job.json；单表准备动作也来自同一决策逻辑，
     * 只读元数据、不做任何 DDL/写入，保证预览结果与执行一致。
     */
    public TaskPlanPreview previewPlan(SyncTask task) {
        if (task.getName() == null || task.getName().trim().isEmpty()) {
            throw new BizException("请填写任务名称");
        }
        if (task.getSourceDatasourceId() == null || task.getSourceDatabase() == null
                || task.getTargetDatasourceId() == null || task.getTargetDatabase() == null) {
            throw new BizException("请完整配置源与目标");
        }
        DataSource sourceDs = requireDatasource(task.getSourceDatasourceId());
        DataSource targetDs = requireDatasource(task.getTargetDatasourceId());
        TaskConfig config = parseConfig(task.getConfig());
        if (config == null || config.getTables() == null || config.getTables().isEmpty()) {
            throw new BizException("任务尚未配置同步对象");
        }

        TaskPlanPreview preview = new TaskPlanPreview();
        List<TableMapping> enabledTables = new ArrayList<>();
        for (TableMapping tm : config.getTables()) {
            if (tm.isEnabled()) {
                enabledTables.add(tm);
            }
        }

        // 1. 与执行一致：先物化字段映射（columns 为空 = 全字段）与切分键默认值
        Map<String, Boolean> splitPkDefaultFlags = new LinkedHashMap<>();
        for (TableMapping tm : enabledTables) {
            splitPkDefaultFlags.put(tm.getSourceTable(),
                    tm.getSplitPk() == null || tm.getSplitPk().trim().isEmpty());
            try {
                tablePrepareLogic.materializeColumns(sourceDs, task.getSourceDatabase(), tm);
                tablePrepareLogic.applySplitPkDefault(sourceDs, task.getSourceDatabase(), tm);
            } catch (Exception e) {
                preview.getWarnings().add("表[" + tm.getSourceTable() + "]：" + e.getMessage());
            }
        }

        // 2. 完整 job.json（此时映射已物化，与执行时 build 的输入一致）
        try {
            preview.setJobJson(jobBuilder.build(sourceDs, task.getSourceDatabase(),
                    targetDs, task.getTargetDatabase(), config));
        } catch (Exception e) {
            preview.getWarnings().add("生成 job.json 失败：" + e.getMessage());
        }

        // 3. 任务开始时准备过程（与执行日志逐行一致：首行为任务开始，随后逐表准备日志）
        preview.getPrepareLogs().add("开始执行任务: " + task.getName()
                + " [" + task.getSourceDatabase() + " -> " + task.getTargetDatabase() + "]");
        for (TableMapping tm : enabledTables) {
            try {
                preview.getTables().add(buildTablePlan(preview, sourceDs, targetDs, task, tm,
                        Boolean.TRUE.equals(splitPkDefaultFlags.get(tm.getSourceTable()))));
            } catch (Exception e) {
                TaskPlanPreview.TablePlan plan = new TaskPlanPreview.TablePlan();
                plan.setSourceTable(tm.getSourceTable());
                plan.setTargetTable(tm.getTargetTable());
                plan.getWarnings().add("读取元数据失败，预览不完整：" + e.getMessage());
                preview.getPrepareLogs().add("表[" + tm.getSourceTable() + "]读取元数据失败，预览不完整: " + e.getMessage());
                preview.getTables().add(plan);
            }
        }

        // 4. 任务完成通知
        preview.setNotifies(parseNotifies(task.getNotifyConfig()));

        // 5. 源/目标连接信息（顶部方向展示，不含账号密码）
        preview.setSource(toDsInfo(sourceDs, task.getSourceDatabase()));
        preview.setTarget(toDsInfo(targetDs, task.getTargetDatabase()));
        return preview;
    }

    private TaskPlanPreview.DsInfo toDsInfo(DataSource ds, String database) {
        TaskPlanPreview.DsInfo info = new TaskPlanPreview.DsInfo();
        info.setName(ds.getName());
        info.setType(ds.getType());
        info.setHost(ds.getHost());
        info.setPort(ds.getPort());
        info.setDatabase(database);
        return info;
    }

    /** 单表执行计划（准备动作来自与执行共用的 TablePrepareLogic，准备日志行汇总到全局 preview.prepareLogs） */
    private TaskPlanPreview.TablePlan buildTablePlan(TaskPlanPreview preview, DataSource sourceDs, DataSource targetDs,
                                                     SyncTask task, TableMapping tm, boolean splitPkDefault) {
        String targetDb = task.getTargetDatabase();
        TaskPlanPreview.TablePlan plan = new TaskPlanPreview.TablePlan();
        plan.setSourceTable(tm.getSourceTable());
        plan.setTargetTable(tm.getTargetTable());
        plan.setAutoCreateTable(tm.isAutoCreateTable());
        plan.setRecreateTable(tm.isRecreateTable());
        plan.setTruncateBefore(tm.isTruncateBefore());
        plan.setWhere(tm.getWhere());
        plan.setSplitPk(tm.getSplitPk());
        plan.setSplitPkDefault(splitPkDefault);

        // 字段明细与差异
        // 列名比较一律走方言的归一化：源列用源库规则、目标列用目标库规则（禁止裸 toLowerCase）
        Dialect sourceDialect = connectorRegistry.get(sourceDs).dialect();
        Dialect targetDialect = connectorRegistry.get(targetDs).dialect();
        Map<String, ColumnMeta> sourceMeta = sourceColumnMap(sourceDs, task.getSourceDatabase(), tm.getSourceTable());
        boolean anyDiff = false;
        Map<String, TaskPlanPreview.ColumnPlan> planByTarget = new LinkedHashMap<>();
        for (ColumnMapping c : tm.getColumns()) {
            TaskPlanPreview.ColumnPlan cp = new TaskPlanPreview.ColumnPlan();
            cp.setSource(c.getSource());
            cp.setTarget(c.getTarget());
            cp.setSelected(c.isSelected());
            cp.setPk(c.isPk());
            ColumnMeta sm = sourceMeta.get(c.getSource() == null ? "" : sourceDialect.normalizeIdentifier(c.getSource()));
            if (sm != null) {
                cp.setPk(cp.isPk() || sm.isPk());
                cp.setSourceType(c.getSourceType() == null || c.getSourceType().trim().isEmpty()
                        ? sm.getType() : c.getSourceType());
            } else {
                cp.setSourceType(c.getSourceType());
            }
            cp.setRenamed(c.isSelected() && c.getSource() != null && !c.getSource().equals(c.getTarget()));
            if (cp.isRenamed() || !c.isSelected()) {
                anyDiff = true;
            }
            plan.getColumns().add(cp);
            planByTarget.put(c.getTarget() == null ? "" : targetDialect.normalizeIdentifier(c.getTarget()), cp);
            if (c.isSelected()) {
                plan.setSelectedColumnCount(plan.getSelectedColumnCount() + 1);
            }
        }

        // 准备过程（与执行共用同一决策，日志行与执行开始时的日志逐行一致，含 DDL 全文）
        TablePrepareLogic.PreparedTable prepared = tablePrepareLogic.prepare(
                sourceDs, task.getSourceDatabase(), targetDs, targetDb, tm);
        TablePrepareLogic.TablePreparePlan prepare = prepared.getPlan();
        preview.getPrepareLogs().addAll(prepared.getLogLines());
        plan.setCreateDdl(prepare.getCreateDdl());
        plan.getWarnings().addAll(prepare.getWarnings());
        for (ColumnMapping c : prepare.getMissingColumns()) {
            TaskPlanPreview.ColumnPlan cp = planByTarget.get(
                    c.getTarget() == null ? "" : targetDialect.normalizeIdentifier(c.getTarget()));
            if (cp != null) {
                cp.setMissingInTarget(true);
            }
        }
        if (!prepare.getExtraTargetColumns().isEmpty()) {
            plan.getWarnings().add("目标表多出字段 [" + String.join(", ", prepare.getExtraTargetColumns())
                    + "] 不会被写入，也不会被删除");
        }
        // 字段改名/裁剪时的建表行为提醒
        boolean willCreate = tm.isRecreateTable() || (!prepare.isTargetExists() && tm.isAutoCreateTable());
        if (anyDiff && willCreate) {
            plan.getWarnings().add("存在字段改名/裁剪：建表将按映射列生成（仅保留主键，不含源表二级索引）");
        }
        // 清空目标通过 writer preSql 实现（执行时在写入前触发），预览中附在准备过程末尾
        if (tm.isTruncateBefore()) {
            preview.getPrepareLogs().add("写入前清空目标表（writer preSql）: "
                    + targetDialect.buildTruncateTable(targetDb, tm.getTargetTable()));
            if (tm.isRecreateTable()) {
                plan.getWarnings().add("已勾选重建表（每次执行前 DROP+CREATE），清空目标可省略");
            }
        }
        return plan;
    }

    private List<TaskPlanPreview.NotifyItem> parseNotifies(String notifyConfig) {
        List<TaskPlanPreview.NotifyItem> list = new ArrayList<>();
        if (notifyConfig == null || notifyConfig.trim().isEmpty()) {
            return list;
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(notifyConfig,
                    MAPPER.getTypeFactory().constructParametricType(List.class, Map.class));
            for (Map<String, Object> m : raw) {
                Object url = m.get("url");
                if (url == null || url.toString().trim().isEmpty()) {
                    continue;
                }
                TaskPlanPreview.NotifyItem item = new TaskPlanPreview.NotifyItem();
                item.setMethod(m.get("method") == null ? null : m.get("method").toString());
                item.setUrl(url.toString());
                item.setBodyType(m.get("bodyType") == null ? null : m.get("bodyType").toString());
                list.add(item);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private Map<String, ColumnMeta> sourceColumnMap(DataSource sourceDs, String sourceDb, String sourceTable) {
        Map<String, ColumnMeta> map = new LinkedHashMap<>();
        Dialect sourceDialect = connectorRegistry.get(sourceDs).dialect();
        for (ColumnMeta m : metaService.listColumns(sourceDs, sourceDb, sourceTable)) {
            map.put(sourceDialect.normalizeIdentifier(m.getName()), m);
        }
        return map;
    }

    public TaskConfig parseConfig(String json) {
        return JobExecutor.parseConfig(json);
    }

    private String writeConfig(String raw) {
        if (raw == null) {
            throw new BizException("任务配置不能为空");
        }
        // 校验并规范化 JSON（去除未知字段）
        try {
            return MAPPER.writeValueAsString(MAPPER.readValue(raw, TaskConfig.class));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("任务配置JSON格式错误: " + e.getMessage(), e);
        }
    }

    private DataSource requireDatasource(String id) {
        DataSource ds = dataSourceMapper.findById(id);
        if (ds == null) {
            throw new BizException("数据源不存在: " + id);
        }
        return ds;
    }

    private void validate(SyncTask task) {
        if (task.getName() == null || task.getName().trim().isEmpty()) {
            throw new BizException("任务名称不能为空");
        }
        if (task.getSourceDatasourceId() == null || task.getSourceDatabase() == null
                || task.getTargetDatasourceId() == null || task.getTargetDatabase() == null) {
            throw new BizException("请完整配置源与目标");
        }
    }
}
