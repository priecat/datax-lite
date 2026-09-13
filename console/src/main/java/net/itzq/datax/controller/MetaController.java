package net.itzq.datax.controller;

import net.itzq.datax.common.BizException;
import net.itzq.datax.common.R;
import net.itzq.datax.connector.Capabilities;
import net.itzq.datax.connector.ConnectorRegistry;
import net.itzq.datax.connector.DbConnector;
import net.itzq.datax.connector.FieldSpec;
import net.itzq.datax.connector.TypeDecision;
import net.itzq.datax.dto.ColumnMeta;
import net.itzq.datax.dto.DbTypeInfo;
import net.itzq.datax.dto.TypeResolveQuery;
import net.itzq.datax.entity.DataSource;
import net.itzq.datax.jdbc.JdbcDriverHolder;
import net.itzq.datax.jdbc.JdbcDriverInfo;
import net.itzq.datax.service.DataSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源类型元信息：前端动态表单的唯一数据来源。
 *
 * <p>数据全部来自已注册的 {@link DbConnector}，因此<b>新增一种数据库不需要改本类</b>。
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final ConnectorRegistry registry;
    private final JdbcDriverHolder driverHolder;
    private final DataSourceService dataSourceService;

    public MetaController(ConnectorRegistry registry, JdbcDriverHolder driverHolder,
                          DataSourceService dataSourceService) {
        this.registry = registry;
        this.driverHolder = driverHolder;
        this.dataSourceService = dataSourceService;
    }

    @GetMapping("/db-types")
    public R<List<DbTypeInfo>> dbTypes() {
        List<DbTypeInfo> list = new ArrayList<DbTypeInfo>();
        for (DbConnector connector : registry.all()) {
            list.add(toInfo(connector));
        }
        return R.ok(list);
    }

    /**
     * 「系统默认」类型裁决的批量查询：对每个源类型走「类型字典 -> 恒等回退」分支
     * （与执行时 decide 一致，但不含用户显式 targetType）。
     *
     * <p>供字段映射对话框「目标类型(最终)」列展示：用户未指定类型时的最终落库类型预判。
     */
    @PostMapping("/resolve-types")
    public R<List<TypeResolveQuery.Item>> resolveTypes(@RequestBody TypeResolveQuery query) {
        if (query.getSourceDatasourceId() == null || query.getTargetDatasourceId() == null) {
            throw new BizException("缺少源/目标数据源");
        }
        DataSource sourceDs = dataSourceService.get(query.getSourceDatasourceId());
        DataSource targetDs = dataSourceService.get(query.getTargetDatasourceId());
        DbConnector target = registry.get(targetDs);
        String sourceBrand = ConnectorRegistry.effectiveBrand(sourceDs);
        String targetBrand = ConnectorRegistry.effectiveBrand(targetDs);

        List<TypeResolveQuery.Item> result = new ArrayList<TypeResolveQuery.Item>();
        for (String raw : query.getSourceTypes()) {
            TypeResolveQuery.Item item = new TypeResolveQuery.Item();
            item.setSourceType(raw);
            if (raw == null || raw.trim().isEmpty()) {
                item.setTargetType(raw);
                result.add(item);
                continue;
            }
            // column 传 null：跳过显式分支，纯粹预演「字典 -> 恒等」裁决
            ColumnMeta fake = new ColumnMeta();
            fake.setType(raw);
            TypeDecision d = target.typeMapping().decide(null, fake, sourceBrand, targetBrand);
            item.setTargetType(d.getTargetType());
            item.setSameAsSource(raw.equals(d.getTargetType()));
            if (!d.getIssues().isEmpty()) {
                List<String> msgs = new ArrayList<String>();
                for (net.itzq.datax.connector.TypeIssue issue : d.getIssues()) {
                    msgs.add(issue.getMessage());
                }
                item.setIssues(msgs);
            }
            result.add(item);
        }
        return R.ok(result);
    }

    private DbTypeInfo toInfo(DbConnector connector) {
        Capabilities caps = connector.capabilities();

        DbTypeInfo info = new DbTypeInfo();
        // P0 的 brand 与 type 同名（mysql）。P1 引入 profile 后，type 与 brand 可以不同
        // （例如 type=oceanbase + props.compatMode=mysql → brand=oceanbase-mysql）。
        info.setType(caps.brand());
        info.setBrand(caps.brand());
        info.setFamily(caps.family());
        info.setDisplayName(caps.displayName());
        info.setDefaultPort(caps.defaultPort());
        info.setUrlPreview(caps.jdbcUrlTemplate());
        info.setNamespaceKind(caps.namespaceKind().name());
        info.setRequiresAnchor(caps.requiresAnchor());
        info.setFields(withDriverOptions(caps));
        info.setExtraParamsPlaceholder(caps.extraParamsPlaceholder());

        DbTypeInfo.Capabilities c = new DbTypeInfo.Capabilities();
        c.setSupportsSchema(caps.supportsSchema());
        c.setSupportsSplitPk(caps.supportsSplitPk());
        c.setSupportsUpsert(caps.supportsUpsert());
        c.setSupportsDdl(caps.supportsDdl());
        c.setSupportsComment(caps.supportsComment());
        c.setSupportsCharset(caps.supportsCharset());
        c.setNativeShowCreate(caps.nativeShowCreate());
        c.setWriteModes(caps.writeModes());
        info.setCapabilities(c);
        return info;
    }

    /**
     * 给 fields 里的 driverClass 下拉注入 jdbc/ 目录已加载的驱动选项。
     *
     * <p>首选项为「品牌默认」；其余按扫描结果列出（不同版本可互换试用，
     * 与 IDEA 数据源的驱动选择体验一致）。
     */
    private List<FieldSpec> withDriverOptions(Capabilities caps) {
        List<FieldSpec> fields = caps.fields();
        for (FieldSpec f : fields) {
            if ("driverClass".equals(f.getKey())) {
                List<FieldSpec.Option> options = new ArrayList<FieldSpec.Option>();
                options.add(new FieldSpec.Option(caps.driverClass(),
                        caps.displayName() + " 默认驱动"));
                for (JdbcDriverInfo jar : driverHolder.list()) {
                    for (String cls : jar.getDriverClasses()) {
                        if (cls.equals(caps.driverClass())) {
                            continue; // 默认驱动已作为首选项
                        }
                        String simple = cls.substring(cls.lastIndexOf('.') + 1);
                        options.add(new FieldSpec.Option(cls, jar.getFileName() + " [" + simple + "]"));
                    }
                }
                f.setOptions(options);
            }
        }
        return fields;
    }
}
