package org.sitenetsoft.quarkus.social.engine;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.LaunchMode;

/**
 * Dev mode only: warns once when publish/update/delete is called inside an active JTA transaction. JTA is looked up
 * by name, so the check is silently off when {@code jakarta.transaction} isn't on the class path.
 */
final class TransactionCheck {

    private static final Logger LOG = Logger.getLogger(TransactionCheck.class);
    private static final String REGISTRY = "jakarta.transaction.TransactionSynchronizationRegistry";
    /** {@code jakarta.transaction.Status.STATUS_NO_TRANSACTION}. */
    private static final int NO_TRANSACTION = 6;

    private final boolean enabled = LaunchMode.current() == LaunchMode.DEVELOPMENT;
    private final AtomicBoolean warned = new AtomicBoolean();

    /** Must run on the caller's thread (JTA transactions are thread-bound). */
    void warnIfActive(String operation) {
        if (!enabled || warned.get() || !inTransaction()) {
            return;
        }
        if (warned.compareAndSet(false, true)) {
            LOG.warnf("Social.%s was called inside an active transaction. Networks are remote calls and the "
                    + "ActivityPub part runs in its own transaction: call it after your transaction commits.", operation);
        }
    }

    private static boolean inTransaction() {
        try {
            Class<?> type = Class.forName(REGISTRY, false, Thread.currentThread().getContextClassLoader());
            InjectableInstance<?> registry = Arc.container().select(type);
            if (!registry.isResolvable()) {
                return false;
            }
            Method status = type.getMethod("getTransactionStatus");
            return (int) status.invoke(registry.get()) != NO_TRANSACTION;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debugf(e, "transaction check skipped");
            return false;
        }
    }
}
