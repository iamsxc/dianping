package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.BlogUserVo;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public BlogUserVo getBlogInfo(long id) {
        //获取vo
        BlogUserVo blogUserVo = new BlogUserVo();
        //获取blog
        Blog blog = getById(id);
        //赋值vo
        BeanUtil.copyProperties(blog,blogUserVo);
        //获取user
        Long userId = blog.getUserId();
        User user = userService.getOne(new QueryWrapper<User>().eq("id", userId));
        //赋值vo
        blogUserVo.setName(user.getNickName());
        blogUserVo.setIcon(user.getIcon());

        blogUserVo.setIsLike(isLike(id));
        return blogUserVo;
    }

    //在vo中加入字段isLike，表示当前用户是否点过赞
    private Boolean isLike(long id) {
        Long userId = UserHolder.getUser().getId();
        Double isLike = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId+"");
        return BooleanUtil.isTrue(isLike!=null);
    }

    //在redis中用set存储某博客点赞的用户,当用户每点一次就去redis查是否点过，点过就移除，没点过就加入并刷新数据库
    @Override
    public void like(Long id) {
        Long userId = UserHolder.getUser().getId();
        Double isLike = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId+"");
        if (isLike==null){
            update().setSql("liked = liked + 1").eq("id", id).update();
            stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id,userId+"",System.currentTimeMillis());
        }else{
            update().setSql("liked = liked - 1").eq("id", id).update();
            stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id,userId+"");
        }
    }

    @Override
    public Result getLikes(Long id) {
        Set<String> userSet = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (userSet==null||userSet.isEmpty()){
            return Result.ok();
        }
        List<Long> userIds = userSet.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDtoList = userService.listByIds(userIds).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDtoList);
    }

    @Override
    public Result getUserBlog(long userId, int current) {
        Page<Blog> page=query().eq("user_id",userId)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    //当发布博客时向他粉丝的redis中存一份
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (isSuccess){
            List<Long> fansId=followService.getFansId(user.getId());
            System.out.println(fansId);
            for (Long aLong : fansId) {
                stringRedisTemplate.opsForZSet().add(FEED_KEY+aLong
                        ,blog.getId().toString(),System.currentTimeMillis());
            }
            return Result.ok(blog.getId());
        }
        return Result.fail("发布失败");
    }

    //滚动分页，解决当列表变化时角标变化导致分页产生重复或少
    @Override
    public Result getFollowBlog(long max, int offset) {
        Long userId = UserHolder.getUser().getId();
        System.out.println(userId);
        //set按分数并且分页获取结果
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(FEED_KEY + userId
                        , 0, max, offset, 3);

        long minTime=0;
        int os=1;
        List<Long> ids=new ArrayList<>();
        if (typedTuples==null||typedTuples.isEmpty()){
            System.out.println("!2321");
            return Result.ok();
        }
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            long score = typedTuple.getScore().longValue();
            System.out.println(typedTuple.getValue());
            ids.add(Long.valueOf(typedTuple.getValue()));
            if (minTime==score){
                os++;
            }else{
                os=1;
                minTime=score;
            }
        }
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);

        return Result.ok(scrollResult);
    }
}
