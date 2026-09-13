package net.itzq.datax.engine;

import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.core.job.JobContainer;
import com.alibaba.datax.core.statistics.communication.Communication;
import com.alibaba.datax.core.statistics.container.communicator.AbstractContainerCommunicator;

/**
 * 暴露 JobContainer 内部的 communicator，用于运行中快照与最终指标获取。
 */
public class ConsoleJobContainer extends JobContainer {

    public ConsoleJobContainer(Configuration configuration) {
        super(configuration);
    }

    /** 汇总各 taskGroup 的实时统计 */
    public Communication snapshot() {
        AbstractContainerCommunicator c = super.getContainerCommunicator();
        return c == null ? null : c.collect();
    }
}
