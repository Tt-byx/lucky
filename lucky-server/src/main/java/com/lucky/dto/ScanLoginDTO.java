package com.lucky.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 扫码登录请求 DTO
 * 用户扫码后提交身份信息，获取场次专属临时令牌
 */
@Data
public class ScanLoginDTO {

    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    @NotBlank(message = "学号不能为空")
    @Size(max = 30, message = "学号长度不能超过30个字符")
    private String studentId;

    /**
     * 姓名
     * 首次注册必填；已注册用户再次扫码可以不传（用库里已有记录）。
     * 这条规则由 Service 层判断，不放在注解上，否则老用户快速进入会被拦掉。
     */
    @Size(max = 50, message = "姓名长度不能超过50个字符")
    private String name;

    @Size(max = 20, message = "手机号长度不能超过20个字符")
    private String phone;
}
