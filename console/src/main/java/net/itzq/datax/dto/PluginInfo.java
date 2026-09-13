package net.itzq.datax.dto;

import lombok.Data;

import java.util.List;

/**
 * DataX 插件信息
 */
@Data
public class PluginInfo {

    private String name;
    /** reader / writer */
    private String type;
    private String path;
    private String description;
    private String developer;
    private String version;
    private List<String> jars;
    private long modified;
    /**
     * 是否与其它插件目录重名。
     * 重名会让 DataX 在加载插件阶段直接抛错，导致所有作业失败，需要人工处理。
     */
    private boolean duplicatedName;
}
