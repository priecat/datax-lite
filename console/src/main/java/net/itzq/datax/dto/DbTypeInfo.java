package net.itzq.datax.dto;

import lombok.Data;
import net.itzq.datax.connector.FieldSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源类型描述（{@code GET /api/meta/db-types} 的响应元素）。
 *
 * <p>前端按 {@link #fields} 动态渲染表单，从而避免 {@code v-if} 链随库类型增长而爆炸。
 *
 * <p>注意：<b>返回的是 {@code type}（前端提交值），不是 brand</b>。
 * brand 由 type + {@code props.compatMode} 在服务端解析（见 {@code ConnectorRegistry.get}），
 * 前端不需要也不应该知道这层组合规则。
 */
@Data
public class DbTypeInfo {

    /** 前端提交的类型值，如 mysql / postgresql / oceanbase / dm */
    private String type;

    /** 展示名 */
    private String displayName;

    /** 服务端解析出的品牌（诊断用，前端一般不展示） */
    private String brand;

    /** 协议族（诊断/回退用） */
    private String family;

    /** 默认端口 */
    private int defaultPort;

    /** JDBC URL 预览模板 */
    private String urlPreview;

    /** 命名空间语义：DATABASE | SCHEMA */
    private String namespaceKind;

    /** 建连是否需要连接锚点库（PG/达梦为 true → 前端把 defaultDb 设为必填） */
    private boolean requiresAnchor;

    /** 能力声明（前端据此显隐选项，如 writeModes） */
    private Capabilities capabilities;

    /** 动态表单字段 schema */
    private List<FieldSpec> fields = new ArrayList<FieldSpec>();

    /** 「额外参数」占位提示 */
    private String extraParamsPlaceholder;

    @Data
    public static class Capabilities {
        private boolean supportsSchema;
        private boolean supportsSplitPk;
        private boolean supportsUpsert;
        private boolean supportsDdl;
        private boolean supportsComment;
        private boolean supportsCharset;
        /** 是否支持 SHOW CREATE TABLE 身份拷贝快路径 */
        private boolean nativeShowCreate;
        private List<String> writeModes = new ArrayList<String>();
    }
}
