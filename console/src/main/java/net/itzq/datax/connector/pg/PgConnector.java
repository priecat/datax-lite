package net.itzq.datax.connector.pg;

import net.itzq.datax.connector.Capabilities;
import net.itzq.datax.connector.DataxAdapter;
import net.itzq.datax.connector.DbConnector;
import net.itzq.datax.connector.Dialect;
import net.itzq.datax.connector.Metadata;
import net.itzq.datax.connector.TypeMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 连接器：5 个协作者的装配。
 *
 * <p>结构照抄 {@code MysqlConnector} —— 本类刻意不含任何业务逻辑，
 * 只告诉 {@code ConnectorRegistry} "PostgreSQL 这套协作者是一个整体"。
 */
@Component
@ConditionalOnProperty(name = "connector.pg.enabled", havingValue = "true")
public class PgConnector implements DbConnector {

    private final PgCapabilities capabilities;
    private final PgDialect dialect;
    private final PgMetadata metadata;
    private final PgTypeMapping typeMapping;
    private final PgDataxAdapter datax;

    public PgConnector(PgCapabilities capabilities,
                       PgDialect dialect,
                       PgMetadata metadata,
                       PgTypeMapping typeMapping,
                       PgDataxAdapter datax) {
        this.capabilities = capabilities;
        this.dialect = dialect;
        this.metadata = metadata;
        this.typeMapping = typeMapping;
        this.datax = datax;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public Dialect dialect() {
        return dialect;
    }

    @Override
    public Metadata metadata() {
        return metadata;
    }

    @Override
    public TypeMapping typeMapping() {
        return typeMapping;
    }

    @Override
    public DataxAdapter datax() {
        return datax;
    }
}
