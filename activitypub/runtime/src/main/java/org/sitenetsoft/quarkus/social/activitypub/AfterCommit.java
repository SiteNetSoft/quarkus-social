package org.sitenetsoft.quarkus.social.activitypub;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;

/** JTA side of {@link InboundInteractions}; only loaded when {@code jakarta.transaction} is on the class path. */
final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * Runs {@code action} after the current transaction commits (never on rollback).
     *
     * @return false when no transaction is active: nothing was registered and the caller should run the action
     */
    static boolean register(Runnable action) {
        InstanceHandle<TransactionSynchronizationRegistry> handle = Arc.container()
                .instance(TransactionSynchronizationRegistry.class);
        if (!handle.isAvailable()) {
            return false;
        }
        TransactionSynchronizationRegistry registry = handle.get();
        if (registry.getTransactionStatus() == Status.STATUS_NO_TRANSACTION) {
            return false;
        }
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                    action.run();
                }
            }
        });
        return true;
    }
}
