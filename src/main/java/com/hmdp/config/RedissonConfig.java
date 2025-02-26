package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author 30241
 * @version 1.0
 * @description: TODO
 * @date 2025/2/26 下午6:06
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.64.201:6397")
                .setPassword("123456");
        //创建
        return Redisson.create(config);
    }

}
