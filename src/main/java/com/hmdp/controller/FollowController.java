package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    IFollowService followService;

    @PutMapping("/{userId}/{isFollow}")
    public Result followUser(@PathVariable("userId") long userId,@PathVariable("isFollow") Boolean isFollow){
        return followService.followUser(userId,isFollow);
    }

    @GetMapping("/or/not/{userId}")
    public Result isFollow(@PathVariable("userId") Long userId){
        return followService.isFollow(userId);
    }

    @GetMapping("/common/{userId}")
    public Result commonFollow(@PathVariable("userId")long userId){
        return followService.getCommonFollow(userId);
    }
}
