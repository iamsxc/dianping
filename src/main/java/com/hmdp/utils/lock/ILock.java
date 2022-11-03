package com.hmdp.utils.lock;

public interface ILock {

    boolean tryLock(long locksec);

    void unlock();
}
