package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.vo.BlogUserVo;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    BlogUserVo getBlogInfo(long id);

    void like(Long id);

    Result getLikes(Long id);

    Result getUserBlog(long userId, int current);

    Result saveBlog(Blog blog);

    Result getFollowBlog(long max, int offset);
}
