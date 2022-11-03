package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    FollowMapper followMapper;

    @Resource
    IUserService userService;

    @Override
    public Result followUser(long userId, Boolean isFollow) {
        Long id = UserHolder.getUser().getId();
        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(userId);
            follow.setCreateTime(LocalDateTime.now());
            save(follow);
        }else{
            remove(new QueryWrapper<Follow>().eq("user_id",id).eq("follow_user_id",userId));
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long userId) {
        Long id = UserHolder.getUser().getId();
        int count = count(new QueryWrapper<Follow>().eq("user_id", id).eq("follow_user_id", userId));
        if (count>0){
            return Result.ok(true);
        }else{
            return Result.ok(false);
        }
    }

    @Override
    public Result getCommonFollow(long userId) {
        Long id = UserHolder.getUser().getId();
        List<Long> list=followMapper.getCommonFollow(userId,id);
        System.out.println(list);
        List<User> users = userService.listByIds(list);
        List<UserDTO> collect = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(collect);
    }

    @Override
    public List<Long> getFansId(Long id) {
        List<Follow> list = list(new QueryWrapper<Follow>().eq("follow_user_id", id));
        List<Long> userIds = list.stream().map(Follow::getUserId).collect(Collectors.toList());
        return userIds;
    }
}
