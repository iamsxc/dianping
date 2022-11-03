package com.hmdp.utils.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/*
这个拦截器用于通过token来获取用户信息，并且将用户信息存储到threadLocal中，并且刷新redis中的时效

作用的url为全部
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;


    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //在前置过滤器中获取session中的user，并判断是否空
        //HttpSession session = request.getSession();
        //Object user = session.getAttribute("user");

        //获取前端传来的token
        String token = request.getHeader("authorization");

        //通过前端传来的token去redis中取值，如果取的hash为空，则校验不通过
        Map<Object, Object> userMap = stringRedisTemplate.
                opsForHash().entries(LOGIN_USER_KEY + token);
        if (userMap.isEmpty()){
            return true;
        }

        //把map类型的数据转化为对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //把user存储在threadlocal中
        UserHolder.saveUser(userDTO);

        //！！！要给redis中的数据续期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

}
