package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.EXPIRE_SECOND_SHOP;

@RunWith(SpringRunner.class)
@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl iShopService;


    @Resource
    IVoucherService voucherService;

    @Resource
    private RedisWorker redisWorker;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Test
    public void addRedisData() {
        iShopService.saveData2Redis(1l, EXPIRE_SECOND_SHOP);
    }

    @Test
    public void testId() {
        System.out.println("测试的id" + redisWorker.nextId("testId"));
    }


    @Test
    public void voucherTest() {
        System.out.println(voucherService.queryVoucherOfShop(1l));
    }

    @Test
    public void sendMessage() {

    }
}
