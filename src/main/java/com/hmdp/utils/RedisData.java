package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

//封装数据和逻辑过期时间，用于解决缓存击穿
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
