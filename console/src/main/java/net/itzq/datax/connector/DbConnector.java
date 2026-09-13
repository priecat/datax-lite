package net.itzq.datax.connector;

/**
 * 一种数据库在系统中的完整画像：5 个协作者的聚合根。
 *
 * <p>拆成 5 个协作者的理由是<b>它们的变化频率完全不同</b> —— 类型映射改得最频繁、
 * 方言次之、能力声明几乎不变。合在一个大类里会导致每次调类型映射都要动一个巨型类。
 *
 * <p>实现类应当只做装配，把实际逻辑委派给各协作者。
 */
public interface DbConnector {

    Capabilities capabilities();

    Dialect dialect();

    Metadata metadata();

    TypeMapping typeMapping();

    DataxAdapter datax();
}
