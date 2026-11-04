package com.lucky.controller;

import com.lucky.annotation.Idempotent;
import com.lucky.dto.ParticipantDTO;
import com.lucky.dto.Result;
import com.lucky.entity.Participant;
import com.lucky.service.ParticipantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 参与者Controller
 */
@RestController
@RequestMapping("/api/participant")
@RequiredArgsConstructor
public class ParticipantController {

    private final ParticipantService participantService;

    /**
     * 注册参与者（接口幂等）
     */
    @Idempotent(prefix = "register", expireSeconds = 5, message = "请勿重复提交注册请求")
    @PostMapping("/register")
    public Result<Participant> register(@Valid @RequestBody ParticipantDTO dto) {
        return Result.ok(participantService.register(dto));
    }

    /**
     * 按学号查是否注册
     *
     * 已下线：这个接口原本是公开的，任何人拿学号就能反查出姓名和手机号，
     * 等于提供了一套「按学号枚举全校学生信息」的工具。
     * 手机端的「学号快速进入」已经改走 /api/scan/login，不再依赖它。
     */
    // @GetMapping("/check") —— 已移除

    @GetMapping("/list")
    public Result<List<Participant>> list(@RequestParam Long activityId) {
        return Result.ok(participantService.getByActivity(activityId));
    }

    @GetMapping("/count")
    public Result<Integer> count(@RequestParam Long activityId) {
        return Result.ok(participantService.getCountByActivity(activityId));
    }
}
