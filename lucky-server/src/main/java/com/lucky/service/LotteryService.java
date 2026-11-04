package com.lucky.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lucky.dto.JoinResultVO;
import com.lucky.dto.LotteryDTO;
import com.lucky.dto.WinnerVO;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Winner;

import java.util.List;

public interface LotteryService extends IService<LotteryRound> {

    /**
     * 创建抽奖轮次（必须绑定奖品，名额不得超过奖品库存）
     */
    LotteryRound createRound(LotteryDTO dto);

    /**
     * 开始本轮抢奖：预热库存 + 切状态 + 广播倒计时
     */
    void startRound(Long roundId);

    /**
     * 结束本轮抢奖
     */
    void endRound(Long roundId);

    /**
     * 用户抢奖
     */
    JoinResultVO join(Long roundId, Long participantId);

    /**
     * 查询某人在本轮的中奖结果
     */
    Winner getResult(Long roundId, Long participantId);

    /**
     * 剩余名额
     */
    Integer getRemaining(Long roundId);

    /**
     * 获取活动的所有轮次
     */
    List<LotteryRound> getRoundsByActivity(Long activityId);

    /**
     * 获取轮次的中奖者（含姓名与奖品名）
     */
    List<WinnerVO> getWinnersByRound(Long roundId);
}
