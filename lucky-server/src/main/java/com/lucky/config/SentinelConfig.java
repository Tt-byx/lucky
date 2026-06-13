package com.lucky.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel限流 + 熔断配置
 */
@Slf4j
@Configuration
public class SentinelConfig {

    @PostConstruct
    public void initRules() {
        // 初始化限流规则
        initFlowRules();
        // 初始化熔断规则
        initDegradeRules();
    }

    /**
     * 初始化限流规则
     */
    private void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        // 1. 弹幕发送接口限流 - 每秒最多100次
        FlowRule danmakuRule = new FlowRule();
        danmakuRule.setResource("POST:/api/danmaku/send");
        danmakuRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        danmakuRule.setCount(100);
        danmakuRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_WARM_UP);
        danmakuRule.setWarmUpPeriodSec(10);
        rules.add(danmakuRule);

        // 2. 参与者注册接口限流 - 每秒最多50次
        FlowRule registerRule = new FlowRule();
        registerRule.setResource("POST:/api/participant/register");
        registerRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        registerRule.setCount(50);
        registerRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_WARM_UP);
        registerRule.setWarmUpPeriodSec(10);
        rules.add(registerRule);

        // 3. 登录接口限流 - 每秒最多10次（防暴力破解）
        FlowRule loginRule = new FlowRule();
        loginRule.setResource("POST:/api/auth/login");
        loginRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        loginRule.setCount(10);
        loginRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_WARM_UP);
        loginRule.setWarmUpPeriodSec(5);
        rules.add(loginRule);

        // 4. 抽奖接口限流 - 每秒最多5次
        FlowRule drawRule = new FlowRule();
        drawRule.setResource("POST:/api/lottery/draw/{roundId}");
        drawRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        drawRule.setCount(5);
        drawRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_WARM_UP);
        drawRule.setWarmUpPeriodSec(5);
        rules.add(drawRule);

        // 5. 全局默认限流 - 每秒最多500次
        FlowRule globalRule = new FlowRule();
        globalRule.setResource("global");
        globalRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        globalRule.setCount(500);
        globalRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_WARM_UP);
        globalRule.setWarmUpPeriodSec(30);
        rules.add(globalRule);

        FlowRuleManager.loadRules(rules);
        log.info("Sentinel限流规则加载完成，共{}条规则", rules.size());
    }

    /**
     * 初始化熔断规则
     */
    private void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // 1. 弹幕发送接口熔断 - 慢调用比例
        DegradeRule danmakuDegradeRule = new DegradeRule();
        danmakuDegradeRule.setResource("POST:/api/danmaku/send");
        danmakuDegradeRule.setGrade(RuleConstant.DEGRADE_GRADE_RT); // 慢调用比例
        danmakuDegradeRule.setCount(500); // 超过500ms为慢调用
        danmakuDegradeRule.setTimeWindow(10); // 熔断时间窗口10秒
        danmakuDegradeRule.setSlowRatioThreshold(0.5); // 慢调用比例超过50%
        danmakuDegradeRule.setMinRequestAmount(10); // 最少10个请求
        danmakuDegradeRule.setStatIntervalMs(10000); // 统计时间窗口10秒
        rules.add(danmakuDegradeRule);

        // 2. 参与者注册接口熔断 - 异常比例
        DegradeRule registerDegradeRule = new DegradeRule();
        registerDegradeRule.setResource("POST:/api/participant/register");
        registerDegradeRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO); // 异常比例
        registerDegradeRule.setCount(0.5); // 异常比例超过50%
        registerDegradeRule.setTimeWindow(10); // 熔断时间窗口10秒
        registerDegradeRule.setMinRequestAmount(10); // 最少10个请求
        registerDegradeRule.setStatIntervalMs(10000); // 统计时间窗口10秒
        rules.add(registerDegradeRule);

        // 3. 抽奖接口熔断 - 异常数
        DegradeRule drawDegradeRule = new DegradeRule();
        drawDegradeRule.setResource("POST:/api/lottery/draw/{roundId}");
        drawDegradeRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT); // 异常数
        drawDegradeRule.setCount(5); // 异常数超过5个
        drawDegradeRule.setTimeWindow(30); // 熔断时间窗口30秒
        drawDegradeRule.setMinRequestAmount(10); // 最少10个请求
        drawDegradeRule.setStatIntervalMs(10000); // 统计时间窗口10秒
        rules.add(drawDegradeRule);

        // 4. 登录接口熔断 - 异常数
        DegradeRule loginDegradeRule = new DegradeRule();
        loginDegradeRule.setResource("POST:/api/auth/login");
        loginDegradeRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT); // 异常数
        loginDegradeRule.setCount(10); // 异常数超过10个
        loginDegradeRule.setTimeWindow(60); // 熔断时间窗口60秒
        loginDegradeRule.setMinRequestAmount(20); // 最少20个请求
        loginDegradeRule.setStatIntervalMs(10000); // 统计时间窗口10秒
        rules.add(loginDegradeRule);

        DegradeRuleManager.loadRules(rules);
        log.info("Sentinel熔断规则加载完成，共{}条规则", rules.size());
    }
}
