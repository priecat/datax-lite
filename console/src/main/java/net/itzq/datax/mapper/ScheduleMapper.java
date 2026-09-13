package net.itzq.datax.mapper;

import net.itzq.datax.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ScheduleMapper {

    int insert(Schedule s);

    int update(Schedule s);

    List<Schedule> findAll();

    Schedule findById(@Param("id") String id);

    long countByTaskId(@Param("taskId") String taskId);

    int delete(@Param("id") String id, @Param("now") long now);
}
