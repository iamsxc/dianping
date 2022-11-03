package com.hmdp.utils.lock;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class Lock implements ILock{

    private StringRedisTemplate stringRedisTemplate;

    private String name;

    public Lock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private final static String KEY_PREFIX="key:";

    private final static String ID_PREFIX= UUID.randomUUID().toString(true)+"-";

    //为了维护，从外部读取lua脚本
    //tips:当程序需要io时，将io的操作设置为静态的(只io一次，并且将io操作放在代码快中),属性的初始化要多行操作
    private final static DefaultRedisScript<Long> UNLOCK_SCRIPT;

    //io操作并初始化属性
    static{
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long locksec) {
        //以uuid和线程号破解做为value
        String threadId = Thread.currentThread().getId()+ID_PREFIX;
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId
                , locksec, TimeUnit.SECONDS);
        //涉及到包装类自动拆箱的情况都要注意一下会不会有可能空指针异常
        return Boolean.TRUE.equals(isLock);
    }

    @Override
    public void unlock() {
        //使用lua脚本来使得查询判断删除操作的原子性
        //读取RedisSCRIPT并设置变量
        stringRedisTemplate.execute(UNLOCK_SCRIPT
                , Collections.singletonList("KEY_PREFIX + name")
                , Thread.currentThread().getId()+ID_PREFIX);
//        //在开锁前判断一下是否为同一把锁，防止前面线程因锁过期而开了别人的锁
//        //问题：此时的查询锁和删除锁不是原子的，所以可能导致在判断锁之后锁已经被别人删除
//        //又产生锁的误删
//        String threadId = Thread.currentThread().getId()+ID_PREFIX;
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(id)){
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }


    }
}
