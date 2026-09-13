package net.itzq.datax.connector.mysql;

import net.itzq.datax.connector.Capabilities;
import net.itzq.datax.connector.DataxAdapter;
import net.itzq.datax.connector.DbConnector;
import net.itzq.datax.connector.Dialect;
import net.itzq.datax.connector.Metadata;
import net.itzq.datax.connector.TypeMapping;
import org.springframework.stereotype.Component;

/**
 * MySQL 连接器：5 个协作者的装配。
 *
 * <p>本类刻意不含任何业务逻辑 —— 它的存在只是告诉 {@code ConnectorRegistry}
 * "MySQL 这套协作者是一个整体"。P1 新增 PostgreSQL 时，照抄这个结构即可。
 */
@Component
public class MysqlConnector implements DbConnector {

    private final MysqlCapabilities capabilities;
    private final MysqlDialect dialect;
    private final MysqlMetadata metadata;
    private final MysqlTypeMapping typeMapping;
    private final MysqlDataxAdapter datax;

    public MysqlConnector(MysqlCapabilities capabilities,
                          MysqlDialect dialect,
                          MysqlMetadata metadata,
                          MysqlTypeMapping typeMapping,
                          MysqlDataxAdapter datax) {
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
