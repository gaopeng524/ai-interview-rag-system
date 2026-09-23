package com.example.demo.modules.interviewschedule.mapper;

import com.example.demo.modules.interviewschedule.model.entity.InterviewScheduleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface InterviewScheduleMapper {

    int insertSchedule(InterviewScheduleEntity entity);

    int updateSchedule(InterviewScheduleEntity entity);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("userId") Long userId);

    int deleteScheduleById(@Param("id") Long id,
                           @Param("userId") Long userId);

    InterviewScheduleEntity findScheduleById(@Param("id") Long id,
                                             @Param("userId") Long userId);

    List<InterviewScheduleEntity> findSchedules(@Param("userId") Long userId,
                                                @Param("status") String status,
                                                @Param("start") LocalDateTime start,
                                                @Param("end") LocalDateTime end);

    int updateExpiredStatus(@Param("newStatus") String newStatus,
                            @Param("targetStatus") String targetStatus,
                            @Param("now") LocalDateTime now);
}
