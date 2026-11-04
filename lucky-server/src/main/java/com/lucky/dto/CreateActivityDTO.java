package com.lucky.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建活动请求DTO
 */
@Data
public class CreateActivityDTO {

    @NotBlank(message = "活动名称不能为空")
    @Size(max = 100, message = "活动名称长度不能超过100个字符")
    private String name;

    /**
     * 开始时间（可选）
     *
     * 必须指定格式：LocalDateTime 的 Jackson 默认反序列化只认 ISO-8601（2030-01-01T10:00:00），
     * 传 "2030-01-01 10:00:00" 会直接解析失败，抛出来的还是「服务器内部错误」，
     * 根本走不到业务里的「开始时间不能晚于结束时间」校验。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
