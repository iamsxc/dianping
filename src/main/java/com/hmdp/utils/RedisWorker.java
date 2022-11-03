package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {

    //将2022-1-1-0:00作为时间戳的起始时间
    private static final long BEGIN_TIME= 1640995200L;

    //时间戳左移的位数
    private static final int BITCOUNT=32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //用于获取自增主键id，将时间戳左移32位并或运算拼接上自增数
    public long nextId(String keyPrefix){

        LocalDateTime now = LocalDateTime.now();

        //添加时间戳
        long nowStamp = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowStamp-BEGIN_TIME;

        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        //获取redis中的自增value，key为前缀拼接上日期时间
        Long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix+":"+date);

        //位运算
        return timeStamp<<BITCOUNT|increment;
    }


}
