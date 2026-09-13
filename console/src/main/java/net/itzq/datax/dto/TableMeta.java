package net.itzq.datax.dto;

import lombok.Data;

@Data
public class TableMeta {

    private String name;
    private String engine;
    private Long rows;
    private String comment;
}
