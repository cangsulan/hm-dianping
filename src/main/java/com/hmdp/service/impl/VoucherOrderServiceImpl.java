package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    // 代理对象
    private IVoucherOrderService proxy;

    // 阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new LinkedBlockingQueue<VoucherOrder>(1024*1024);

    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 当前类初始化完毕后，执行：
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 线程任务
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while(true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    // 处理订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        // 使用 Redisson 的方式
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 使用 Redisson 的方式
        boolean isLock = lock.tryLock();

        if(!isLock){
            // 获取锁失败，
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象（事务）
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }


    /**
     * @description: 秒杀优化，使用异步的方式来分解业务
     * @param: voucherId
     * @return: com.hmdp.dto.Result
     * @author 30241
     * @date: 2025/2/28 下午7:26
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 2.判断结果是否为0
        int r=result.intValue();
        if(r != 0){
            // 2.1 不为0，没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        // 2.2 为0，有购买资格，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");

        // 保存到 阻塞队列
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 订单id
        voucherOrder.setId(orderId);

        orderTasks.add(voucherOrder);

        // 获取代理对象
        proxy= (IVoucherOrderService) AopContext.currentProxy();

        // 3.返回订单id
        return Result.ok(orderId);
    }


    /**
     * @description: 使用 简单 redis分布式锁 来解决，可应对 集群环境
     * @param: voucherId
     * @return: com.hmdp.dto.Result
     * @author 30241
     * @date: 2025/2/26 下午4:43
     */
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock()<1) {
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        // 使用 Redisson 的方式
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 获取锁
        // boolean isLock = lock.tryLock(100);

        // 使用 Redisson 的方式
        boolean isLock = lock.tryLock();

        if(!isLock){
            // 获取锁失败，返回错误或重试
            return Result.fail("一个人只允许下一单");
        }
        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
    }*/


    /**
     * @description: 使用synchronized来加锁，只能应对单进程，无法对付集群
     * @param: voucherId
     * @author 30241
     * @date: 2025/2/26 下午4:42
     */ 
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock()<1) {
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()){
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }*/


    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        // 5.一人一单
        Long userId = voucherOrder.getUserId();
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2 判断是否存在
        if(count>0){
            // 用户已经购买过了
            log.error("用户已经购买过一次了！");
            return;
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)
                .update();
        if(!success){
            //扣减失败
            log.error("库存不足！");
            return;
        }
        // 7.创建订单

        save(voucherOrder);
    }
}
