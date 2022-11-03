package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import javax.annotation.Resource;
import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
/*
作为缓存的工具类，提供四个方法
①，传入ttl时间和单位存入redis
②，传入逻辑过期时间和单位，新建redisData存入redis,解决缓存击穿
③，通过设置空值而解决缓存穿透的方法
④，通过
 */
@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        String jsonStr = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key,jsonStr,time,timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        String jsonStr = JSONUtil.toJsonStr(redisData);

        stringRedisTemplate.opsForValue().set(key,jsonStr);
    }

    /**
     *
     * @param keyPrefix   作为redis存储的key的前缀
     * @param id            作为数据库查询的参数
     * @param type          作为返回值的类型
     * @param dbfallback    查询数据库的方法
     * @param time           设置ttl
     * @param timeUnit         ttl单位
     * @param <R>            返回值类型
     * @param <ID>           数据库查询的参数的类型
     * @return
     */
    //此处泛型的使用必须复习，再<>中声明这些符号为泛型，R为返回值，ID为数据库查询的参数，再函数接口中分别作为输出值和输入值
    //函数接口的使用：把查询数据库的方法交给调用者来传入，实现复用
    public <R,ID> R getWithThroughPass(
             String keyPrefix, ID id, Class<R> type, Function<ID,R> dbfallback,Long time,TimeUnit timeUnit){

        //通过传进来的参数拼接
        String cacheKey=keyPrefix+id;
        String cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);

        //如果查到数据则返回
        if (!StrUtil.isBlank(cacheJson)){
            R cacheBean = JSONUtil.toBean(cacheJson, type);
            return cacheBean;
        }

        //如果为“”即它为数据库中没有的数据
        if("".equals(cacheJson)){
            return null;
        }

        //调用函数去数据库中查找
        R cacheResult = dbfallback.apply(id);

        //当在数据库中查到为null时，就把“”写到内存中
        if (cacheResult==null){
            stringRedisTemplate.opsForValue().set(cacheKey,"",time, timeUnit);
            return null;
        }
        //查到就写到redis中
        set(cacheKey,JSONUtil.toJsonStr(cacheResult),time, timeUnit);
        return cacheResult;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    //使用逻辑过期时间解决缓存击穿
    public <R,ID>  R getWithLogicExprie(
            String keyPrefix,ID id,Class<R> type,String lockPrefix,Function<ID,R> dbFallBack,
            Long time,TimeUnit timeUnit){

        String cacheKey=keyPrefix+id;
        String cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);

        //因为热点缓存都是手动添加的，所以未命中直接返回null
        if (StrUtil.isBlank(cacheJson)){
            return null;
        }
        //获取命中的redisData并提取数据
        RedisData redisData = JSONUtil.toBean(cacheJson, RedisData.class);
        R r=JSONUtil.toBean((JSONObject) redisData.getData(),type);
        //判断是否超过逻辑时间，未超过则返回数据
        if (LocalDateTime.now().isBefore(redisData.getExpireTime())){
            return r;
        }

        //如果已经超过逻辑时间，则尝试获取锁
        if (tryLock(cacheKey)) {

            //双检！！       在并发中很重要
            /*
            假设a线程拿到锁重建缓存的过程中b线程判断缓存过期,等b线程申请锁时a线程已经完成工作,所以b也会去重建缓存
             */
            cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);
            redisData = JSONUtil.toBean(cacheJson, RedisData.class);
            if (LocalDateTime.now().isBefore(redisData.getExpireTime())) {
                return r;
            }

            //在线程池中新建线程去重建缓存，并且在子线程中释放锁
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newR = dbFallBack.apply(id);
                    setWithLogicalExpire(cacheKey,newR,time,timeUnit);
                } finally {
                    unlock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //获取不到锁的都返回久数据
        return r;
    }

    private boolean tryLock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
