package com.lucky.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lucky.dto.ParticipantDTO;
import com.lucky.entity.Participant;

import java.util.List;

public interface ParticipantService extends IService<Participant> {

    Participant register(ParticipantDTO dto);

    List<Participant> getByActivity(Long activityId);

    int getCountByActivity(Long activityId);
}
