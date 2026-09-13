package net.itzq.datax.mapper;

import net.itzq.datax.entity.SyncTaskLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SyncTaskLogMapper {

    int insert(SyncTaskLog l);

    int updateFinish(SyncTaskLog l);

    int updateLogFile(@Param("id") String id, @Param("logFile") String logFile);

    /** 任务真正开始执行：QUEUED → RUNNING（提交时写的是 QUEUED） */
    int markRunning(@Param("id") String id);

    int markAllRunningFailed(@Param("message") String message);

    SyncTaskLog findById(@Param("id") String id);

    List<SyncTaskLog> listByTask(@Param("taskId") String taskId, @Param("limit") int limit);

    List<SyncTaskLog> listBySchedule(@Param("scheduleId") String scheduleId, @Param("limit") int limit);

    List<SyncTaskLog> listPage(@Param("state") String state, @Param("keyword") String keyword,
                               @Param("limit") int limit, @Param("offset") int offset);

    long countPage(@Param("state") String state, @Param("keyword") String keyword);
}
