package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import com.rabbitmq.client.AMQP;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;


import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    RedisWorker redisWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    //注入rabbitmq
    @Resource
    private RabbitTemplate rabbitTemplate;

    //加载lua脚本并初始化
    private final static DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<VoucherOrder>(1024 * 1024);
    //单线程线程池
    private final static ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //如果使用静态内部类,那里面所有的方法都要是静态的，包括注入的bean，所以不使用静态内部了

    //这个注解是由Java提供的，它用来修饰一个非静态的void方法。它会在服务器加载Servlet的时候运行，并且只运行一次。
    //在构造器执行之后执行
    @PostConstruct
    public void init() {
        //阻塞队列为空时，线程会一致阻塞，
        //所以这里线程一直在等待任务加入阻塞队列
        SECKILL_ORDER_EXECUTOR.submit(() -> {
            while (true) {
                try {
                    //取出任务并处理
                    VoucherOrder order = orderTask.take();
                    voucherOrderHandle(order);
                } catch (Exception e) {
                    log.error("发生错误:{}", e);
                }
            }
        });
    }


    private void voucherOrderHandle(VoucherOrder voucher) {
        Long userId = voucher.getUserId();
        Long voucherId = voucher.getVoucherId();
        String lock_name = ORDER_SECKILL_VOUCHER + voucherId + ":" + userId;
        RLock lock = redissonClient.getLock(lock_name);
        //第一个参数为阻塞时间，第二个为ttl
        boolean isLock = lock.tryLock();
        if (!isLock) {//根据业务在一个用户多次抢之后直接返回
            log.error("单个用户只能购买一次");
        }
        try {
            //创建订单
            proxy.createVoucherOrder(voucher);
        } finally {
            lock.unlock();
        }
    }

    //代理对象的定义，因为代理对象的获取涉及到threadLocal，但上面操作已经是别的线程,所以需要在外面定义
    private IVoucherOrderService proxy;

    @Override
    public Result addOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //redis返回权限，0为可以购买
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList()
                , voucherId.toString(), userId.toString());

        int r = result.intValue();
        if (r != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }

        //生成订单id
        long orderId = redisWorker.nextId(SECKILL_VOUCHER_ORDER_ID);

        //新建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        //初始化代理对象，为主线程的
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //加到阻塞队列中
        //orderTask.add(voucherOrder);

        rabbitTemplate.convertAndSend(MQ_SECKILL_ORDER_DIRECT_EXCHANGE
                ,MQ_VOUCHER_ORDERTASK_QUEUE,voucherOrder);

        //异步放回结果给前端
        return Result.ok(orderId);
    }



    @RabbitListener(queues = {MQ_VOUCHER_ORDERTASK_QUEUE})
    public void createVoucherOrderByMQ(VoucherOrder voucherOrder){

        System.out.println(voucherOrder);
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("每个用户只能抢一张券");
        }

        //此处gt判断stock大于0才执行减库存的sql体现了乐观锁机制，解决了超卖问题
        boolean isSuccess = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();

        if (!isSuccess) {
            log.error("库存不足");
            return;
        }

        save(voucherOrder);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("每个用户只能抢一张券");
        }

        //此处gt判断stock大于0才执行减库存的sql体现了乐观锁机制，解决了超卖问题
        boolean isSuccess = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();

        if (!isSuccess) {
            log.error("库存不足");
            return;
        }

        save(voucherOrder);
    }

    /*
    @Transactional
    public Result createVoucherOrder(Long voucherId) {

        Long userId = UserHolder.getUser().getId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("每个用户只能抢一张券");
        }

        //此处gt判断stock大于0才执行减库存的sql体现了乐观锁机制，解决了超卖问题
        boolean isSuccess = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        //

        if (!isSuccess) {
            return Result.fail("扣减库存失败");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisWorker.nextId(SECKILL_VOUCHER_ORDER_ID);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        return Result.ok(voucherOrder);
    }*/

    //    @Override
//    public Result addOrder(Long voucherId) {
//
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//
//        if (seckillVoucher == null) {
//            return Result.fail("未知的优惠券");
//        }
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀还没开始");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//
//        if (seckillVoucher.getStock() <= 0) {
//            return Result.fail("没抢到券");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //当要做一个用户只能秒杀一张券的情况下，如果不加锁，操作不原子，有并发问题
//        /*
//            一，锁对象的选择
//                因为一个用户有多个线程进来，锁对象必须为多个线程的共有一个对象，因为都为同一个用户，所以用用户id来作为对象
//                将id转化为String，但因为toString方法每次都会新建一个对象,所以使用.itern()指向字符串常量词中那个字符串
//                对象！！
//
//            二，锁的范围
//                因为引入了@Transactional来控制事务，如果在事务还未提交时就把锁让出去，会有并发问题，所以锁的访问
//                要比事务的访问大，要把业务封装在方法内，对方法加事务，然后在调用方法中对方法加锁
//
//            三,事务失效的情况：自我调用
//                因为默认使用createVoucherOrder是使用this来调用，而spring底层是是用代理对象来调用才能开启事务
//                所以在这种情况下会导致事务失效
//         */
//
////        单机锁的解决方案
////        synchronized(userId.toString().intern()){
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//
////        //使用setnx+lua分布式锁来解决分布式情况下的问题
////        Lock lock=new Lock(stringRedisTemplate,"order:seckill:voucher:"+voucherId+":"+userId);
////        boolean isLock = lock.tryLock(1200l);
////        if (!isLock){//根据业务在一个用户多次抢之后直接返回
////                return Result.fail("一个用户只能下单一次");
////         }
////        try {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        } finally {
////            lock.unlock();
////        }
//
//        String lock_name = ORDER_SECKILL_VOUCHER + voucherId + ":" + userId;
//        RLock lock = redissonClient.getLock(lock_name);
//        //第一个参数为阻塞时间，第二个为ttl
//        boolean isLock = lock.tryLock();
//        if (!isLock) {//根据业务在一个用户多次抢之后直接返回
//            return Result.fail("一个用户只能下单一次");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//    }

//    @RabbitListener(queues = {"hello-java-queue"})
//    public void reciveTest(Object content, Message message, AMQP.Channel channel){
//        System.out.println(content);
//    }
}
