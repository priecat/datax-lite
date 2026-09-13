package net.itzq.datax.dto;

import lombok.Data;

@Data
public class ColumnMeta {

    private String name;
    private String type;
    private boolean nullable;
    private String defaultValue;
    private String comment;
    private boolean pk;
    private int ordinal;
}
