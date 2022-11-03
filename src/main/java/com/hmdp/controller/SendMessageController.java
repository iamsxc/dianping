package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.MQ_SECKILL_ORDER_DIRECT_EXCHANGE;
import static com.hmdp.utils.RedisConstants.MQ_VOUCHER_ORDERTASK_QUEUE;

@RestController
public class SendMessageController {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @GetMapping("/send")
    public String sendMessage(@RequestParam("num") long num){
        for (int i = 0; i < num; i++) {
            rabbitTemplate.convertAndSend(MQ_SECKILL_ORDER_DIRECT_EXCHANGE
                    , MQ_VOUCHER_ORDERTASK_QUEUE, "helloworld" + i
            );
        }
        return "ok";
    }
}
