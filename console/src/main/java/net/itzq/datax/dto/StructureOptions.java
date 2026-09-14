package net.itzq.datax.dto;

import lombok.Data;

/**
 * 表结构复制选项（任务级，随 TaskOptions 存于 sync_task.config）。
 *
 * <p>工具定位为<b>数据拷贝</b>：会改变写入语义的对象（外键、触发器重建等）默认不同步——
 * 对表结构有要求的场景建议提前手动建立目标表，本工具只负责数据。
 * 外键与触发器按"写入期间移除 → 数据同步完成后统一恢复"的三阶段策略处理，
 * 移除与恢复为两个独立开关，可只清场不回填。
 *
 * <p>作用于建表的 identity 快路径（源表 SHOW CREATE TABLE 原样拷贝）：
 * 各开关按需从拷贝的表体中剥离对应定义。逐列生成路径（改名/裁剪字段/跨库）仅含列与主键，
 * 表体相关开关不参与。表注释（COMMENT='...'）始终保留。
 */
@Data
public class StructureOptions {

    /** 包含索引：KEY / UNIQUE KEY / FULLTEXT / SPATIAL KEY（主键始终保留，不做开关）。默认开（不影响写入正确性） */
    private boolean index = true;
    /** 包含外键约束：写入期间移除，数据同步完成后统一 ALTER 恢复。默认关（写入期约束，DataX 不推荐） */
    private boolean foreignKey = false;
    /** 包含检查约束（MySQL 8.0+ 的 CONSTRAINT ... CHECK）。默认开（源数据本满足源约束） */
    private boolean check = true;
    /** 包含引擎（ENGINE=xxx）。默认开（纯描述性） */
    private boolean engine = true;
    /** 包含字符集（DEFAULT CHARSET=xxx 与 COLLATE=xxx）。默认开（纯描述性） */
    private boolean charset = true;
    /** 对齐自增计数器：数据同步完成后 ALTER 目标表 AUTO_INCREMENT=源计数器快照值（覆盖 truncate 重置等场景）。默认开（幂等无害） */
    private boolean autoIncrement = true;
    /** 移除触发器：目标表已有触发器在写入期间每行都会触发、改变数据语义，准备阶段先 DROP。默认开。
     *  未勾选时不做任何移除，但目标表残留触发器会直接报错阻断（不带隐患写入） */
    private boolean dropTrigger = true;
    /** 重建触发器：数据同步完成后（第三阶段）按源库权威定义在目标表重建，目标独有触发器原样恢复。默认关（数据拷贝定位） */
    private boolean recreateTrigger = false;

    /** null 安全取值（旧 config 无该节点 / Jackson 显式 null） */
    public static StructureOptions safe(StructureOptions o) {
        return o == null ? new StructureOptions() : o;
    }
}
