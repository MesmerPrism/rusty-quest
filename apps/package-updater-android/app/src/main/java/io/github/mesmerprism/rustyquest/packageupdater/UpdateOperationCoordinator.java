package io.github.mesmerprism.rustyquest.packageupdater;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** One process-owned preparation slot shared by the UI and e2e pipeline. */
final class UpdateOperationCoordinator {
    private static final AtomicReference<Object> ACTIVE = new AtomicReference<>();

    private UpdateOperationCoordinator() {}

    static <T> T run(Callable<T> operation) throws Exception {
        Object owner = new Object();
        if (!ACTIVE.compareAndSet(null, owner)) {
            throw new IllegalStateException("update_operation_already_active");
        }
        try {
            return operation.call();
        } finally {
            ACTIVE.compareAndSet(owner, null);
        }
    }
}
