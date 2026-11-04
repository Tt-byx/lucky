package com.lucky.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lucky.entity.Danmaku;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DanmakuMapper extends BaseMapper<Danmaku> {

    /**
     * 真正的批量插入：一条 INSERT 带多个 VALUES
     *
     * 初版是 for 循环逐条 insert，注释写着「批量」但实际是 N 次网络往返。
     * 弹幕高峰期每秒几百条，这个差别很直观。
     */
    @Insert("<script>" +
            "INSERT INTO danmaku (activity_id, participant_id, content, status, created_at) VALUES " +
            "<foreach collection='list' item='d' separator=','>" +
            "(#{d.activityId}, #{d.participantId}, #{d.content}, #{d.status}, #{d.createdAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<Danmaku> list);
}
