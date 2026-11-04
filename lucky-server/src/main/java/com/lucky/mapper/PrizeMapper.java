package com.lucky.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lucky.entity.Prize;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 奖品Mapper
 *
 * 库存扣减与回补都用带条件的 UPDATE 完成，把「判断库存足够」这件事交给数据库做，
 * 这样即使 Redis 预扣环节出了问题，DB 这一层也不会出现负库存。
 */
@Mapper
public interface PrizeMapper extends BaseMapper<Prize> {

    /**
     * 原子扣减库存
     *
     * @return 1 扣减成功；0 库存不足（说明 Redis 与 DB 已经不一致，需要告警）
     */
    @Update("UPDATE prize SET stock = stock - 1 WHERE id = #{prizeId} AND stock > 0")
    int decreaseStock(@Param("prizeId") Long prizeId);

    /**
     * 回补库存（落库失败补偿）
     *
     * @return 1 成功；0 已回补到总库存上限
     */
    @Update("UPDATE prize SET stock = stock + 1 WHERE id = #{prizeId} AND stock < total_stock")
    int increaseStock(@Param("prizeId") Long prizeId);
}
