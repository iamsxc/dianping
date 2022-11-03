package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;



    @Override
    public Result sendCode(String phone, HttpSession session) {
        //后端手机号校验
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("请输入正确的手机号");
        }
        //生成随机验证码
        String randomNumbers = RandomUtil.randomNumbers(6);

        //保存到session作用域
        //session.setAttribute("code",randomNumbers);

        //使用redis,key为手机号，value为验证码   记得在存入时加入指定过期时间
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, randomNumbers, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.info("验证码为：{}", randomNumbers);
        return Result.ok();
    }

    //登录的实现
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //再次校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("请输入正确的手机号");
        }
        //将输入的验证码与session中的验证码做对比
//        if (!(loginForm.getCode().equals(session.getAttribute("code")))){
//            return Result.fail("验证码出错");
//        }
        //将输入的验证码和redis中的验证码做对比             定义常量
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        if (code == null || code.equals(session.getAttribute("code"))) {
            return Result.fail("验证码出错");
        }
        //查询数据库中是否已经存在用户，如果不存在则新建用户
        QueryWrapper<User> userQueryWrapper = new QueryWrapper<User>().eq("phone", loginForm.getPhone());
        User user = baseMapper.selectOne(userQueryWrapper);
        if (user == null) {
            user = createUserByPhone(loginForm);
        }

        //生成token   使用uuid，也可以用jwt
        String token = UUID.randomUUID(true).toString();

        //将用户信息存到session中
        //session.setAttribute("user",user);

        //把user转化为map，在redis中用hash存储
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);

        //bean转map的api的使用，因为userDTO中的id为long类型，所以需要自定义转化格式
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));


        //用token为key，用户信息为value存入redis中
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);

        //给redis中登录的用户设置有效期，不过存在一个问题----》有效期到了用户会被强制踢下线，所以要在拦截器中给活跃对象续期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        //把token发送给前端
        return Result.ok(token);
    }

    //使用bitmap来存储签到的日期，一个key-value来表示一个月内的签到信息
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int dayOfMonth = now.getDayOfMonth();
        Boolean isSuccess = stringRedisTemplate.opsForValue().setBit(USER_SIGN_KEY + userId + now, dayOfMonth - 1, true);
        if (isSuccess) {
            return Result.ok();
        }
        return Result.fail("签到失败");
    }

    //统计这个月到今天
    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(USER_SIGN_KEY + userId + now
                , BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0));
        if (result==null||result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        int count=0;
        while(true){
            if ((num&1)==0){
                break;
            }else{
                count++;
            }
            num>>>=1;
        }
        return null;
    }

    private User createUserByPhone(LoginFormDTO loginForm) {
        User user;
        user = new User();
        user.setPhone(loginForm.getPhone());
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        baseMapper.insert(user);
        return user;
    }
}
