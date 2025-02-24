package com.hmdp.utils;

public class SimpleRedisLock implements ILock{

    @Override
    public boolean tryLock(long timeoutSec) {
        return false;
    }

    @Override
    public void unlock() {

    }
}
