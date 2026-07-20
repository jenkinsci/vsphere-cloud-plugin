package org.jenkinsci.plugins.vsphere.tools;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maintains a single long-lived vSphere session for one {@code vSphereCloud} instance,
 * eliminating the per-operation login/logout overhead that occurs when every API call
 * creates and destroys its own session.
 *
 * <p><b>Caller contract:</b></p>
 * <ol>
 *   <li>Obtain the shared connection via {@link #acquire()}.</li>
 *   <li>Use it normally - all public {@link VSphere} methods work as usual.</li>
 *   <li>Call {@link VSphere#disconnect()} when done.  For pooled connections this is a
 *       no-op; the pool controls the real session lifecycle.</li>
 * </ol>
 *
 * <p><b>Background maintenance:</b></p>
 * <ul>
 *   <li><b>Health check</b> - if {@code healthCheckIntervalSecs > 0}, a daemon thread
 *       periodically issues a lightweight {@code currentTime()} call and reconnects
 *       automatically on failure.</li>
 *   <li><b>Session age limit</b> - if {@code sessionMaxAgeSecs > 0}, a one-shot timer is
 *       armed for the exact moment the session reaches that age, at which point it is
 *       proactively restarted (also re-checked defensively on the next
 *       {@link #acquire()} call).</li>
 *   <li><b>Use-count limit</b> - if {@code sessionMaxUses > 0}, the session is restarted
 *       on the next {@link #acquire()} once that many acquisitions have been made.</li>
 *   <li><b>Idle timeout</b> - if {@code idleTimeoutSecs > 0}, a one-shot timer is armed
 *       for the exact moment the connection has gone unused for that many seconds, at
 *       which point it is disconnected (and lazily reconnected on the next
 *       {@link #acquire()}). The timer is re-armed on every {@link #acquire()}.</li>
 * </ul>
 *
 * <p>Session age and idle timeout are enforced by dedicated one-shot alarms rather than
 * periodic polling, so they fire at (approximately) the exact configured deadline instead
 * of some time after it.
 *
 * <p>A value of {@code 0} disables the corresponding feature.
 *
 * <p>Thread-safe.  All mutable state is guarded by {@code this}.
 */
public class VSphereConnectionPool {

    private static final Logger LOGGER = Logger.getLogger(VSphereConnectionPool.class.getName());

    private final VSphereConnectionConfig config;

    private final int healthCheckIntervalSecs;
    private final int sessionMaxAgeSecs;
    private final int sessionMaxUses;
    private final int idleTimeoutSecs;

    /* Guarded by this */
    private VSphere connection;
    private long connectionCreatedAtMs;
    private long lastAcquiredAtMs;
    private long useCount;
    private ScheduledFuture<?> ageExpiryFuture;
    private ScheduledFuture<?> idleExpiryFuture;

    private ScheduledExecutorService scheduler;

    public VSphereConnectionPool(
            @NonNull VSphereConnectionConfig config,
            int healthCheckIntervalSecs,
            int sessionMaxAgeSecs,
            int sessionMaxUses,
            int idleTimeoutSecs) {
        this.config = config;
        this.healthCheckIntervalSecs = Math.max(0, healthCheckIntervalSecs);
        this.sessionMaxAgeSecs       = Math.max(0, sessionMaxAgeSecs);
        this.sessionMaxUses          = Math.max(0, sessionMaxUses);
        this.idleTimeoutSecs         = Math.max(0, idleTimeoutSecs);
        startScheduler();
    }

    /**
     * Returns the pooled {@link VSphere} connection, creating or restarting it if
     * necessary.  The returned instance is marked as pooled so that callers'
     * {@link VSphere#disconnect()} calls are no-ops.
     *
     * @throws VSphereException if establishing the session fails.
     */
    public synchronized VSphere acquire() throws VSphereException {
        ensureConnected();
        lastAcquiredAtMs = System.currentTimeMillis();
        useCount++;
        connection.markAsPooled();
        scheduleIdleExpiry();
        return connection;
    }

    /**
     * Disconnects the current session (if any) and shuts down all background threads.
     * After this call the pool must not be used.
     */
    public synchronized void shutdown() {
        stopScheduler();
        disconnectQuietly();
    }

    // -------------------------------------------------------------------------
    // Internal helpers (all called with this lock held unless noted)
    // -------------------------------------------------------------------------

    private void ensureConnected() throws VSphereException {
        if (connection == null) {
            connect();
            return;
        }
        boolean ageExpired = sessionMaxAgeSecs > 0
                && (System.currentTimeMillis() - connectionCreatedAtMs) > (long) sessionMaxAgeSecs * 1000L;
        boolean usesExhausted = sessionMaxUses > 0 && useCount >= sessionMaxUses;
        if (ageExpired || usesExhausted) {
            LOGGER.info("vSphere connection pool [" + config.getVsHost() + "]: restarting session - "
                    + (ageExpired ? "max age reached" : "max uses reached"));
            disconnectQuietly();
            connect();
        }
    }

    private void connect() throws VSphereException {
        connection = VSphere.connect(config);
        connection.markAsPooled();
        connectionCreatedAtMs = System.currentTimeMillis();
        lastAcquiredAtMs      = connectionCreatedAtMs;
        useCount              = 0;
        LOGGER.info("vSphere connection pool [" + config.getVsHost() + "]: session established");
        scheduleAgeExpiry();
        scheduleIdleExpiry();
    }

    // Arms (or re-arms) a one-shot timer for the exact moment the current session
    // reaches sessionMaxAgeSecs. Called with this locked.
    private void scheduleAgeExpiry() {
        if (ageExpiryFuture != null) {
            ageExpiryFuture.cancel(false);
            ageExpiryFuture = null;
        }
        if (sessionMaxAgeSecs <= 0 || scheduler == null) {
            return;
        }
        ageExpiryFuture = scheduler.schedule(this::onAgeExpired, sessionMaxAgeSecs, TimeUnit.SECONDS);
    }

    // Arms (or re-arms) a one-shot timer for the exact moment the connection will have
    // been idle (unacquired) for idleTimeoutSecs. Called with this locked.
    private void scheduleIdleExpiry() {
        if (idleExpiryFuture != null) {
            idleExpiryFuture.cancel(false);
            idleExpiryFuture = null;
        }
        if (idleTimeoutSecs <= 0 || scheduler == null) {
            return;
        }
        idleExpiryFuture = scheduler.schedule(this::onIdleExpired, idleTimeoutSecs, TimeUnit.SECONDS);
    }

    // Called from the background scheduler thread, at the exact session-age deadline
    private void onAgeExpired() {
        synchronized (this) {
            if (connection == null) return;
            LOGGER.info("vSphere connection pool [" + config.getVsHost()
                    + "]: proactive reconnect - max session age reached");
            disconnectQuietly();
            try {
                connect();
            } catch (VSphereException e) {
                LOGGER.log(Level.SEVERE,
                        "vSphere connection pool [" + config.getVsHost()
                                + "]: proactive reconnect failed", e);
                // connection remains null; next acquire() will retry
            }
        }
    }

    // Called from the background scheduler thread, at the exact idle-timeout deadline
    private void onIdleExpired() {
        synchronized (this) {
            if (connection == null) return;
            long idleMs = System.currentTimeMillis() - lastAcquiredAtMs;
            LOGGER.info(String.format(
                    "vSphere connection pool [%s]: disconnecting - idle for %ds (limit %ds)",
                    config.getVsHost(), idleMs / 1000, idleTimeoutSecs));
            disconnectQuietly();
            if (ageExpiryFuture != null) {
                ageExpiryFuture.cancel(false);
                ageExpiryFuture = null;
            }
        }
    }

    private void disconnectQuietly() {
        if (connection != null) {
            connection.forceDisconnect();
            connection = null;
        }
    }

    // Called from the background scheduler thread
    private void scheduledHealthCheck() {
        synchronized (this) {
            if (connection == null) return;
            if (connection.isSessionAlive()) {
                LOGGER.fine("vSphere connection pool [" + config.getVsHost() + "]: health check OK");
                return;
            }
            LOGGER.warning("vSphere connection pool [" + config.getVsHost()
                    + "]: health check failed - reconnecting");
            disconnectQuietly();
            try {
                connect();
            } catch (VSphereException e) {
                LOGGER.log(Level.SEVERE,
                        "vSphere connection pool [" + config.getVsHost()
                                + "]: reconnect after health-check failure failed", e);
                // connection remains null; next acquire() will retry
            }
        }
    }

    private void startScheduler() {
        boolean needsScheduler = healthCheckIntervalSecs > 0
                || idleTimeoutSecs > 0
                || sessionMaxAgeSecs > 0;
        if (!needsScheduler) return;

        final String host = config.getVsHost();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vsphere-pool-" + host);
            t.setDaemon(true);
            return t;
        });

        if (healthCheckIntervalSecs > 0) {
            scheduler.scheduleAtFixedRate(
                    this::scheduledHealthCheck,
                    healthCheckIntervalSecs, healthCheckIntervalSecs, TimeUnit.SECONDS);
        }

        // Age-expiry and idle-expiry are armed as exact one-shot alarms from connect()
        // and acquire() respectively, once a connection actually exists.
    }

    private void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
