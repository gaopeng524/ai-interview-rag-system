package com.example.demo.modules.interviewschedule.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.interviewschedule.mapper.InterviewScheduleMapper;
import com.example.demo.modules.interviewschedule.model.dto.CreateInterviewRequest;
import com.example.demo.modules.interviewschedule.model.dto.InterviewScheduleDTO;
import com.example.demo.modules.interviewschedule.model.entity.InterviewScheduleEntity;
import com.example.demo.modules.interviewschedule.model.enums.InterviewStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程管理业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleMapper scheduleMapper;

    /**
     * 创建面试日程
     */
    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request, Long userId) {
        InterviewScheduleEntity entity = InterviewScheduleEntity.builder()
                .userId(userId)
                .companyName(request.getCompanyName())
                .position(request.getPosition())
                .interviewTime(request.getInterviewTime())
                .interviewType(request.getInterviewType() != null ? request.getInterviewType() : "VIDEO")
                .meetingLink(request.getMeetingLink())
                .roundNumber(request.getRoundNumber() != null ? request.getRoundNumber() : 1)
                .interviewer(request.getInterviewer())
                .notes(request.getNotes())
                .status(InterviewStatus.PENDING)
                .build();

        scheduleMapper.insertSchedule(entity);
        log.info("✅ 创建面试日程成功: id={}, company={}, time={}", entity.getId(), entity.getCompanyName(), entity.getInterviewTime());
        return toDTO(entity);
    }

    /**
     * 更新面试日程
     */
    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request, Long userId) {
        InterviewScheduleEntity entity = getByIdOrThrow(id, userId);

        entity.setCompanyName(request.getCompanyName());
        entity.setPosition(request.getPosition());
        entity.setInterviewTime(request.getInterviewTime());
        entity.setInterviewType(request.getInterviewType() != null ? request.getInterviewType() : "VIDEO");
        entity.setMeetingLink(request.getMeetingLink());
        entity.setRoundNumber(request.getRoundNumber() != null ? request.getRoundNumber() : 1);
        entity.setInterviewer(request.getInterviewer());
        entity.setNotes(request.getNotes());

        scheduleMapper.updateSchedule(entity);
        log.info("✅ 更新面试日程成功: id={}", id);
        return toDTO(entity);
    }

    /**
     * 删除面试日程
     */
    @Transactional
    public void delete(Long id, Long userId) {
        getByIdOrThrow(id, userId);
        scheduleMapper.deleteScheduleById(id, userId);
        log.info("🗑️ 删除面试日程成功: id={}", id);
    }

    /**
     * 更新面试状态
     */
    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status, Long userId) {
        InterviewScheduleEntity entity = getByIdOrThrow(id, userId);
        entity.setStatus(status);
        scheduleMapper.updateStatus(id, status.name(), userId);
        log.info("🔄 更新面试日程状态成功: id={}, newStatus={}", id, status);
        return toDTO(entity);
    }

    /**
     * 获取单个日程详情
     */
    public InterviewScheduleDTO getById(Long id, Long userId) {
        return toDTO(getByIdOrThrow(id, userId));
    }

    /**
     * 多维条件检索日程列表（支持状态与时间范围）
     */
    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end, Long userId) {
        List<InterviewScheduleEntity> list = scheduleMapper.findSchedules(userId, status, start, end);
        return list.stream().map(this::toDTO).toList();
    }

    // ==================== 私有辅助 ====================

    private InterviewScheduleEntity getByIdOrThrow(Long id, Long userId) {
        InterviewScheduleEntity entity = scheduleMapper.findScheduleById(id, userId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "面试日程不存在或无权访问: id=" + id);
        }
        return entity;
    }

    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        return InterviewScheduleDTO.builder()
                .id(entity.getId())
                .companyName(entity.getCompanyName())
                .position(entity.getPosition())
                .interviewTime(entity.getInterviewTime())
                .interviewType(entity.getInterviewType())
                .meetingLink(entity.getMeetingLink())
                .roundNumber(entity.getRoundNumber())
                .interviewer(entity.getInterviewer())
                .notes(entity.getNotes())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
