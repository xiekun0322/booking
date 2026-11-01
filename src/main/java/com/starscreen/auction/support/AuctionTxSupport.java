package com.starscreen.auction.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务回调工具。
 *
 * <p><b>只用于"提交后做一次、失败也不影响业务"的动作</b>：异步存证、发件箱快速投递。
 * 涉及数据一致性的写（写发件箱、写审计）必须放在事务<b>内</b>——
 * 放进 afterCommit 等于放弃了原子性，进程在提交后崩溃就永久丢失。
 *
 * <p><b>无事务时立即执行</b>：调用点可能在测试或非事务路径上，
 * 此时静默不执行会让"存证没做"变成难以复现的问题。
 */
public final class AuctionTxSupport {

    private static final Logger log = LoggerFactory.getLogger(AuctionTxSupport.class);

    private AuctionTxSupport() {
    }

    /**
     * 在事务提交后执行；当前没有事务时立即执行。
     *
     * <p>回调内部异常被吞掉并记日志：此时事务已经提交，抛出去只会让调用方
     * 收到一个"业务成功但接口报错"的矛盾结果，而真正该做的是让补偿机制（定时任务）接手。
     */
    public static void afterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(action);
            }
        });
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("提交后回调执行失败（不影响已提交的业务）", e);
        }
    }
}
