package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.pro.audit.AuditEvent;
import com.wontlost.agui.pro.audit.AuditTrail;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AuditBinder 在 UI 线程同步写审计；数据库不可用时异常会冒到界面。此包装只记 WARN 吞掉写失败，
 * 查询失败返回空，保证审计故障不影响对话（ADR 0049 §2.7）。
 */
public final class ResilientAuditTrail implements AuditTrail {
    private static final Logger LOG = LoggerFactory.getLogger(ResilientAuditTrail.class);

    private final AuditTrail delegate;

    public ResilientAuditTrail(AuditTrail delegate) {
        this.delegate = delegate;
    }

    @Override
    public void record(AuditEvent event) {
        try {
            delegate.record(event);
        } catch (RuntimeException e) {
            LOG.warn("审计写入失败（{} / {}）：{}", event.kind(), event.threadId(), e.toString());
        }
    }

    @Override
    public List<AuditEvent> query(Query query) {
        try {
            return delegate.query(query);
        } catch (RuntimeException e) {
            LOG.warn("审计查询失败，按空结果处理：{}", e.toString());
            return List.of();
        }
    }

    @Override
    public int purge(Purge purge) {
        return delegate.purge(purge);
    }
}
