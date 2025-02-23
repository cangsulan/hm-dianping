package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {

        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    public <R,ID> R queryWithPassThrough
            (String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback , Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if("".equals(json)){
            // 返回一个错误信息
            return null;
        }
        // 4.不存在，根据id查询数据库
        R r= dbFallback.apply(id);
        // 5.不存在，返回错误
        if(r ==null){
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis
        this.set(key,r,time,unit);
        // 7.返回
        return r;
    }



    /**
     * 缓存重建线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 根据id查询热点数据（使用逻辑过期解决缓存击穿）
     *
     * @param cacheKeyPrefix    缓存key前缀
     * @param id                查询id，与缓存key前缀拼接
     * @param type              查询数据的Class类型
     * @param lockKeyPrefix     缓存数据锁前缀，与查询id拼接
     * @param dbFallback        根据id查询数据的函数式接口
     * @param expireTime        逻辑过期时间
     * @param unit              时间单位
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R handleCacheBreakdownByLogicalExpire(String cacheKeyPrefix, ID id, Class<R> type, String lockKeyPrefix, Function<ID, R> dbFallback, long expireTime, TimeUnit unit) {
        // 从缓存中获取热点数据
        String cacheKey = cacheKeyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        // 判断缓存是否命中（由于是热点数据，提前进行缓存预热，默认缓存一定会命中）
        if (StrUtil.isBlank(json)) {
            // 缓存未命中，说明查到的不是热点key，直接返回空
            return null;
        }
        // 缓存命中，先把json反序列化为逻辑过期对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 将Object对象转成JSONObject再反序列化为目标对象
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 判断是否逻辑过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期，直接返回正确数据
            return r;
        }
        // 已过期，先尝试获取互斥锁，再判断是否需要缓存重建
        String lockKey = lockKeyPrefix + id;
        // 判断是否获取锁成功
        if (tryLock(lockKey)) {
            // 在线程1重建缓存期间，线程2进行过期判断，假设此时key是过期状态，线程1重建完成并释放锁，线程2立刻获取锁，并启动异步线程执行重建，那此时的重建就与线程1的重建重复了
            // 因此需要在线程2获取锁成功后，在这里再次检测redis中缓存是否过期（DoubleCheck），如果未过期则无需重建缓存，防止数据过期之后，刚释放锁就有线程拿到锁的情况，重复访问数据库进行重建
            json = stringRedisTemplate.opsForValue().get(cacheKey);
            // 缓存命中，先把json反序列化为逻辑过期对象
            redisData = JSONUtil.toBean(json, RedisData.class);
            // 将Object对象转成JSONObject再反序列化为目标对象
            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            // 判断是否逻辑过期
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                // 命中且未过期，直接返回新数据
                return r;
            }
            // 获取锁成功，开启一个独立子线程去重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R result = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(cacheKey, result, expireTime, unit);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }
        // 获取锁失败，直接返回过期的旧数据
        return r;
    }

    /**
     * 尝试获取锁，判断是否获取锁成功
     * setIfAbsent()：如果缺失不存在这个key，则可以set，返回true；存在key不能set，返回false。相当于setnx命令
     * @param lockKey 互斥锁的key
     * @return 是否获取到锁
     */
    private boolean tryLock(String lockKey) {
        // 原子命令：set lock value ex 10 nx
        Boolean isGetLock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10L, TimeUnit.SECONDS);
        // 为了避免Boolean直接返回自动拆箱未null，用工具包将null和false都返回为false
        return BooleanUtil.isTrue(isGetLock);
    }

    /**
     * 释放互斥锁
     * @param lockKey 互斥锁的key
     */
    private void unLock(String lockKey) {
        if (BooleanUtil.isTrue(stringRedisTemplate.hasKey(lockKey))) {
            stringRedisTemplate.delete(lockKey);
        }
    }
}
