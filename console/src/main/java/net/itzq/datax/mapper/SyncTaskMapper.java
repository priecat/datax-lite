package net.itzq.datax.mapper;

import net.itzq.datax.entity.SyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SyncTaskMapper {

    int insert(SyncTask t);

    int update(SyncTask t);

    List<SyncTask> findAll();

    SyncTask findById(@Param("id") String id);

    int delete(@Param("id") String id, @Param("now") long now);
}
