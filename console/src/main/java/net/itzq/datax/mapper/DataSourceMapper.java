package net.itzq.datax.mapper;

import net.itzq.datax.entity.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DataSourceMapper {

    int insert(DataSource d);

    int update(DataSource d);

    List<DataSource> findAll();

    DataSource findById(@Param("id") String id);

    int delete(@Param("id") String id, @Param("now") long now);
}
