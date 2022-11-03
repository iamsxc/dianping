package com.hmdp.mapper;

import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface FollowMapper extends BaseMapper<Follow> {

    List<Long> getCommonFollow(@Param("aId") Long userId, @Param("bId") Long id);
}
