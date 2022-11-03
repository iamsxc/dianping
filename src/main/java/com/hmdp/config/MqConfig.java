package com.hmdp.config;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Configuration
public class MqConfig {

    @Resource
    private RabbitTemplate rabbitTemplate;

//    @Bean
//    public MessageConverter messageConverter(){
//        return new Jackson2JsonMessageConverter();
//    }

    @PostConstruct
    public void initRebbitTemplate(){
        /**
         * correlationData
         */
        rabbitTemplate.setConfirmCallback((correlationData,ack,cause)->{
            //当消息成功被投递到broke时做的操作
            System.out.println("correlationData:"+correlationData);
        });

        rabbitTemplate.setReturnCallback((message,replyCode,replyText,exchange,routingKey)->{
            //当信息不能正确的加入队列时执行的操作
            System.out.println("错误消息码为："+replyCode+"     错误信息为："+replyText);
        });
    }


}
