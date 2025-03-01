package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class
FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result isFollow(Long followUserId) {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 1.查询是否关注
        Integer count = query().eq("follow_user_id", followUserId).eq("user_id", userId).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follow:"+userId;
        // 1.判断是要 关注 还是 取关
        if(isFollow){
            // 2.关注，新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{
            // 3.取关，删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if (isSuccess) {
                // 把关注用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follow:"+userId;
        String key2 = "follow:"+id;
        // 2.求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect == null || intersect.isEmpty()){
            // 无交集，返回空的集合
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOS = users.stream()
                        .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                        .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
