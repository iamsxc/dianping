package com.hmdp.config;

import com.hmdp.utils.interceptor.LoginInterceptor;
import com.hmdp.utils.interceptor.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

//配置拦截器
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //注册拦截器并排除那些不需要被拦截的url
        registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
                "/shop/**",
                "/voucher/**",
                "/shop-type/**",
                "/upload/**",
                "/blog/hot",
                "/user/code",
                "/user/login",
                "/send"
        ).order(1);
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
}
