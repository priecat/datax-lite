package net.itzq.datax.engine;

import net.itzq.datax.connector.ConnectorRegistry;
import net.itzq.datax.connector.DbConnector;
import net.itzq.datax.connector.Dialect;
import net.itzq.datax.connector.DdlRequest;
import net.itzq.datax.connector.ResolvedColumn;
import net.itzq.datax.connector.TypeDecision;
import net.itzq.datax.connector.TypeIssue;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.ColumnMeta;
import net.itzq.datax.dto.TableMapping;
import net.itzq.datax.entity.DataSource;
import net.itzq.datax.service.MetaService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 目标表 DDL 生成：
 * 1. 列名一一对应、全字段同步且类型裁决未发生任何改写时，使用源表建表语句（保留索引等完整定义）；
 * 2. 其余情况按源表结构 + 映射关系逐列生成建表语句。
 *
 * <p>本类只做装配：
 * <ul>
 *   <li>目标列类型由目标库的 {@code TypeMapping.decide} 决定（单一裁决出口）；</li>
 *   <li>DDL 文本由目标库的 {@code Dialect.buildCreateTable} 渲染；</li>
 *   <li>源表建表语句由源库的 {@code Dialect} 读取与解析。</li>
 * </ul>
 *
 * <p><b>identity 快路径必须以裁决结果为准</b>：只要任一列的目标类型被改写
 * （用户显式指定或字典规则命中），就走逐列生成，绝不能用源表 DDL 整体拷贝，
 * 否则用户意图会被静默忽略。同库恒等（P0 场景）裁决结果 == 源类型原文，仍走快路径。
 */
@Component
public class DdlGenerator {

    private final MetaService metaService;
    private final ConnectorRegistry registry;

    public DdlGenerator(MetaService metaService, ConnectorRegistry registry) {
        this.metaService = metaService;
        this.registry = registry;
    }

    /** 建表语句 + 类型裁决 WARN 汇总 + 建表后需延迟执行的外键恢复语句（供预览/执行日志展示） */
    public static class BuildResult {
        private final List<String> statements;
        private final List<String> typeWarnings;
        /** 外键恢复 ALTER 语句（identity 快路径且启用"包含外键"时非空；全部表建完后统一执行） */
        private final List<String> postStatements;

        public BuildResult(List<String> statements, List<String> typeWarnings, List<String> postStatements) {
            this.statements = statements;
            this.typeWarnings = typeWarnings;
            this.postStatements = postStatements == null
                    ? java.util.Collections.<String>emptyList() : postStatements;
        }

        public List<String> getStatements() {
            return statements;
        }

        public List<String> getTypeWarnings() {
            return typeWarnings;
        }

        public List<String> getPostStatements() {
            return postStatements;
        }
    }

    public String generate(DataSource sourceDs, String sourceDb, DataSource targetDs, String targetDb, TableMapping tm) {
        BuildResult result = buildCreateTableStatements(sourceDs, sourceDb, targetDs, targetDb, tm, null);
        if (result.getStatements().isEmpty()) {
            throw new IllegalStateException("目标表[" + tm.getTargetTable() + "]未生成任何建表语句");
        }
        return result.getStatements().get(0);
    }

    /**
     * 生成建表语句列表（跨库时的正确入口：部分方言建表需要多条语句）。
     * 同时返回类型裁决产生的 WARN 文本（如跨库未配置映射规则的直传提示）。
     *
     * @param structureOptions 表结构复制选项；null 时按默认（全保留 + 外键剥离待恢复）处理
     */
    public BuildResult buildCreateTableStatements(DataSource sourceDs, String sourceDb,
                                                  DataSource targetDs, String targetDb, TableMapping tm,
                                                  net.itzq.datax.dto.StructureOptions structureOptions) {
        List<ColumnMapping> selected = new ArrayList<ColumnMapping>();
        for (ColumnMapping c : tm.getColumns()) {
            if (c.isSelected()) {
                selected.add(c);
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("表[" + tm.getSourceTable() + "]未选择任何同步字段");
        }

        DbConnector source = registry.get(sourceDs);
        DbConnector target = registry.get(targetDs);

        List<ColumnMeta> sourceColumns = metaService.listColumns(sourceDs, sourceDb, tm.getSourceTable());

        // 源列元数据按原样列名索引（不做大小写折叠，与原实现一致）
        Map<String, ColumnMeta> byName = new HashMap<String, ColumnMeta>();
        for (ColumnMeta c : sourceColumns) {
            byName.put(c.getName(), c);
        }

        // 目标类型由目标库的类型映射决定 —— 单一来源，方言层不再自行推断；
        // 传入源/目标品牌后走「显式指定 > 类型字典 > 恒等回退」的完整裁决链
        String sourceBrand = ConnectorRegistry.effectiveBrand(sourceDs);
        String targetBrand = ConnectorRegistry.effectiveBrand(targetDs);
        List<ResolvedColumn> resolved = new ArrayList<ResolvedColumn>(selected.size());
        List<String> typeWarnings = new ArrayList<String>();
        boolean typeIdentity = true;
        for (ColumnMapping m : selected) {
            ColumnMeta src = byName.get(m.getSource());
            TypeDecision decision = target.typeMapping().decide(m, src, sourceBrand, targetBrand);
            for (TypeIssue issue : decision.getIssues()) {
                String col = issue.getColumn() == null ? "" : "列[" + issue.getColumn() + "] ";
                typeWarnings.add("表[" + tm.getSourceTable() + "] " + col + issue.getMessage());
            }
            ResolvedColumn rc = new ResolvedColumn();
            rc.setMapping(m);
            rc.setSourceMeta(src);
            rc.setTargetType(decision.getTargetType());
            rc.setPrimaryKey(src != null ? src.isPk() : m.isPk());
            resolved.add(rc);
            // 裁决结果与源类型原文不一致 = 发生改写（显式指定或规则命中），不可走身份拷贝
            String raw = src != null && src.getType() != null ? src.getType() : m.getSourceType();
            if (!Objects.equals(decision.getTargetType(), raw == null ? null : raw.trim())) {
                typeIdentity = false;
            }
        }

        boolean identity = tm.getSourceTable().equals(tm.getTargetTable())
                && sourceColumns.size() == selected.size()
                && allIdentity(selected)
                && typeIdentity;

        DdlRequest req = new DdlRequest();
        req.setTargetDb(targetDb);
        req.setTargetTable(tm.getTargetTable());
        req.setSelected(resolved);
        req.setIdentity(identity);

        // 身份拷贝快路径：仅当目标方言声明支持且裁决未改写任何类型时，才读取源库建表语句。
        // 表体按结构复制选项剥离（外键抽出为延迟恢复语句，其余按开关剥离）。
        List<String> postStatements = java.util.Collections.<String>emptyList();
        if (identity && target.capabilities().nativeShowCreate()) {
            String raw = metaService.showCreateTable(sourceDs, sourceDb, tm.getSourceTable());
            String body = source.dialect().extractTableBody(raw);
            Dialect.IdentityBody processed = target.dialect().processIdentityBody(
                    targetDb, tm.getTargetTable(), body, structureOptions);
            req.setSourceCreateTable(processed.getBody());
            postStatements = processed.getForeignKeyAlters();
        }

        return new BuildResult(target.dialect().buildCreateTable(req), typeWarnings, postStatements);
    }

    private boolean allIdentity(List<ColumnMapping> selected) {
        for (ColumnMapping c : selected) {
            if (!c.getSource().equals(c.getTarget())) {
                return false;
            }
        }
        return true;
    }
}
