package net.itzq.datax.engine;

import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.core.transport.channel.memory.MemoryChannel;
import com.alibaba.datax.core.util.FrameworkErrorCode;
import com.alibaba.datax.core.util.container.CoreConstant;

import java.util.Collection;

/**
 * 可停止的内存 Channel。
 * 在 reader/writer 数据流转的关键节点检查停止标记，命中后抛出异常，
 * 由 DataX 框架自身的失败处理机制完成子线程清理与任务退出。
 */
public class StopableMemoryChannel extends MemoryChannel {

    public StopableMemoryChannel(Configuration configuration) {
        super(configuration);
    }

    private long jobId() {
        return getConfiguration().getLong(CoreConstant.DATAX_CORE_CONTAINER_JOB_ID, -1);
    }

    private void checkStop() {
        long jobId = jobId();
        if (jobId > 0 && StopFlagRegistry.isStopped(jobId)) {
            throw DataXException.asDataXException(FrameworkErrorCode.RUNTIME_ERROR,
                    "任务已被用户手动停止(jobId=" + jobId + ")");
        }
    }

    @Override
    protected void doPush(Record r) {
        checkStop();
        super.doPush(r);
    }

    @Override
    protected void doPushAll(Collection<Record> rs) {
        checkStop();
        super.doPushAll(rs);
    }

    @Override
    protected Record doPull() {
        checkStop();
        return super.doPull();
    }

    @Override
    protected void doPullAll(Collection<Record> rs) {
        checkStop();
        super.doPullAll(rs);
    }
}
