package com.lucky.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lucky.entity.OperationLog;

/**
 * 操作日志服务接口
 */
public interface OperationLogService extends IService<OperationLog> {

    /**
     * 记录操作日志
     */
    void log(Long operatorId, String operatorName, String operationType,
             String targetType, Long targetId, String detail, String ip);
}
