package dev.saicoremake.headhunting.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InventoryTransactionLock {
    private final ConcurrentMap<UUID, UUID> owners = new ConcurrentHashMap<>();

    public Lease tryAcquire(UUID playerId) {
        UUID token = UUID.randomUUID();
        return owners.putIfAbsent(playerId, token) == null ? new Lease(this, playerId, token) : null;
    }

    public boolean isLocked(UUID playerId) {
        return owners.containsKey(playerId);
    }

    private void release(UUID playerId, UUID token) {
        owners.remove(playerId, token);
    }

    public static final class Lease implements AutoCloseable {
        private final InventoryTransactionLock owner;
        private final UUID playerId;
        private final UUID token;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(InventoryTransactionLock owner, UUID playerId, UUID token) {
            this.owner = owner;
            this.playerId = playerId;
            this.token = token;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(playerId, token);
            }
        }
    }
}
