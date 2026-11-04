package com.lucky.service;

import com.lucky.dto.ScanLoginDTO;
import com.lucky.dto.ScanLoginVO;

/**
 * 扫码登录服务
 * 高并发接入优化：Redis 临时令牌 + 限流拦截 + 异步入库
 */
public interface ScanLoginService {

    /**
     * 扫码登录：校验身份 → 生成场次专属临时令牌 → 异步入库
     *
     * @param dto 登录请求
     * @return 登录响应（含临时令牌）
     */
    ScanLoginVO login(ScanLoginDTO dto);

    /**
     * 校验临时令牌有效性
     *
     * @param token 临时令牌
     * @return 参与者 ID，无效返回 null
     */
    Long validateToken(String token);

    /**
     * 登出：销毁临时令牌
     *
     * @param token 临时令牌
     */
    void logout(String token);
}
