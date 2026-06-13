package com.lucky.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.entity.OperationLog;
import com.lucky.mapper.OperationLogMapper;
import com.lucky.service.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 操作日志服务实现
 */
@Slf4j
@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog>
        implements OperationLogService {

    @Override
    @Async
    public void log(Long operatorId, String operatorName, String operationType,
                    String targetType, Long targetId, String detail, String ip) {
        try {
            OperationLog operationLog = new OperationLog();
            operationLog.setOperatorId(operatorId);
            operationLog.setOperatorName(operatorName);
            operationLog.setOperationType(operationType);
            operationLog.setTargetType(targetType);
            operationLog.setTargetId(targetId);
            operationLog.setDetail(detail);
            operationLog.setIp(ip);
            save(operationLog);

            log.info("操作日志已记录: operator={}, type={}, target={}", operatorName, operationType, targetType);
        } catch (Exception e) {
            log.error("记录操作日志失败: {}", e.getMessage(), e);
        }
    }
}
