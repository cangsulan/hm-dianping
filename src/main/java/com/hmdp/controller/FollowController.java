package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * @description: 根据id，关注id所在的用户，或 取关 该用户
     * @param: followUserId
     * @param: isFollow
     * @return: com.hmdp.dto.Result
     * @author 30241
     * @date: 2025/3/1 下午12:39
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId,isFollow);
    }

    /**
     * @description: 查询当前用户是否关注了id所在的用户
     * @param: followUserId
     * @return: com.hmdp.dto.Result
     * @author 30241
     * @date: 2025/3/1 下午12:40
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }
}
