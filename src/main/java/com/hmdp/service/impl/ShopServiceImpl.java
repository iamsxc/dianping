package com.hmdp.service.impl;

import cn.hutool.core.date.DateUnit;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result getByIdWithCache(Long id) {
        //解决缓存穿透问题
        //Shop shop = getShopWithThroughPass(id);

        //使用工具类解决穿透问题
        Shop shop = cacheClient.getWithThroughPass(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //使用互斥锁解决缓存击穿
        //Shop shop=getShopWithMutex(id);、

        //使用逻辑过期时间解决缓存击穿
        //Shop shop=getShopWithLogicExprie(id);

        //Shop shop = cacheClient.getWithLogicExprie(CACHE_SHOP_KEY, id, Shop.class,LOCK_SHOP_KEY ,this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //Shop shop=getById(id);
        if (shop==null){
            return Result.fail("查询不到店铺");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    //使用逻辑过期时间解决缓存击穿
    private Shop getShopWithLogicExprie(Long id){
        String shopInfoJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);

        //因为热点缓存都是手动添加的，所以未命中直接返回null
        if (StrUtil.isBlank(shopInfoJson)){
            return null;
        }
        //获取命中的redisData并提取数据
        RedisData redisData = JSONUtil.toBean(shopInfoJson, RedisData.class);
        Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        //判断是否超过逻辑时间，未超过则返回数据
        if (LocalDateTime.now().isBefore(redisData.getExpireTime())){
            return shop;
        }

        //如果已经超过逻辑时间，则尝试获取锁
        if (tryLock(LOCK_SHOP_KEY + id)) {

            //双检！！       在并发中很重要
            /*
            假设a线程拿到锁重建缓存的过程中b线程判断缓存过期,等b线程申请锁时a线程已经完成工作,所以b也会去重建缓存
             */
            shopInfoJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            redisData = JSONUtil.toBean(shopInfoJson, RedisData.class);
            if (LocalDateTime.now().isBefore(redisData.getExpireTime())) {
                return shop;
            }

            //在线程池中新建线程去重建缓存，并且在子线程中释放锁
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveData2Redis(id, EXPIRE_SECOND_SHOP);
                } finally {
                    unlock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //获取不到锁的都返回久数据
        return shop;
    }

    //使用互斥锁解决内存击穿
    private Shop getShopWithMutex(Long id) {
        String shopInfoJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);

        if (!StrUtil.isBlank(shopInfoJson)){
            Shop shop = JSONUtil.toBean(shopInfoJson, Shop.class);
            return shop;
        }
        //为了防止内存穿透，在查到内存中存入为“”时就直接返回fail

        //此处是排除读到内存中为"",即已经查过数据库为null后
        //时刻要防止空指针异常！！
        if("".equals(shopInfoJson)){
            return null;
        }

        String lockKey=LOCK_SHOP_KEY+id;
        try {
            //获取锁失败自旋等待
            while(!tryLock(lockKey)){
                Thread.sleep(20);
            }
            //双检判断是否需要重建缓存
            shopInfoJson=stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);
            if (!StrUtil.isBlank(shopInfoJson)){
                return JSONUtil.toBean(shopInfoJson,Shop.class);
            }

            Shop shop= baseMapper.selectById(id);
            //Thread.sleep(200);
            //当在数据库中查到为null时，就把“”写到内存中
            if (shop==null){
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            //解锁
            unlock(lockKey);
        }
    }


    //解决内存穿透
    private Shop getShopWithThroughPass(Long id) {
        String shopInfoJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);

        if (!StrUtil.isBlank(shopInfoJson)){
            Shop shop = JSONUtil.toBean(shopInfoJson, Shop.class);
            return shop;
        }
        //为了防止内存穿透，在查到内存中存入为“”时就直接返回fail

        //此处是排除读到内存中为"",即已经查过数据库为null后
        //时刻要防止空指针异常！！
        if("".equals(shopInfoJson)){
            return null;
        }
        Shop shop = baseMapper.selectById(id);

        //当在数据库中查到为null时，就把“”写到内存中
        if (shop==null){
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    private boolean tryLock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    //手动将热点数据存到redis中
    //自己手动封装了redisData，其中加入了数据的逻辑过期时间
    public void saveData2Redis(Long id, Long expireSecond){
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Shop shop = getById(id);

        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId()==null){
            return Result.fail("更改失败");
        }
        this.updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

}
