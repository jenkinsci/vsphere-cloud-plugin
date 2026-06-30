package org.jenkinsci.plugins.vsphere.tools;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maintains a single long-lived vSphere session for one {@code vSphereCloud} instance,
 * eliminating the per-operation login/logout overhead that occurs when every API call
 * creates and destroys its own session.
 *
 * <h3>Caller contract</h3>
 * <ol>
 *   <li>Obtain the shared connection via {@link #acquire()}.</li>
 *   <li>Use it normally — all public {@link VSphere} methods work as usual.</li>
 *   <li>Call {@link VSphere#disconnect()} when done.  For pooled connections this is a
 *       no-op; the pool controls the real session lifecycle.</li>
 * </ol>
 *
 * <h3>Background maintenance</h3>
 * <ul>
 *   <li><b>Health check</b> — if {@code healthCheckIntervalSecs > 0}, a daemon thread
 *       periodically issues a lightweight {@code getCurrentTime()} call and reconnects
 *       automatically on failure.</li>
 *   <li><b>Session age limit</b> — if {@code sessionMaxAgeSecs > 0}, the session is
 *       proactively restarted once it has been alive for that many seconds, both in the
 *       background and on the next {@link #acquire()} call.</li>
 *   <li><b>Use-count limit</b> — if {@code sessionMaxUses > 0}, the session is restarted
 *       on the next {@link #acquire()} once that many acquisitions have been made.</li>
 *   <li><b>Idle timeout</b> — if {@code idleTimeoutSecs > 0}, the session is disconnected
 *       (and lazily reconnected on the next {@link #acquire()}) after the connection has
 *       not been acquired for that many seconds.</li>
 * </ul>
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
            LOGGER.info("vSphere connection pool [" + config.getVsHost() + "]: restarting session — "
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
                    + "]: health check failed — reconnecting");
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

    // Called from the background scheduler thread
    private void scheduledMaintenance() {
        synchronized (this) {
            if (connection == null) return;

            // Proactive age-based restart (before callers run into an expired session)
            if (sessionMaxAgeSecs > 0) {
                long ageMs = System.currentTimeMillis() - connectionCreatedAtMs;
                if (ageMs > (long) sessionMaxAgeSecs * 1000L) {
                    LOGGER.info("vSphere connection pool [" + config.getVsHost()
                            + "]: proactive reconnect — max session age reached");
                    disconnectQuietly();
                    try {
                        connect();
                    } catch (VSphereException e) {
                        LOGGER.log(Level.SEVERE,
                                "vSphere connection pool [" + config.getVsHost()
                                        + "]: proactive reconnect failed", e);
                    }
                    return;
                }
            }

            // Idle disconnect
            if (idleTimeoutSecs > 0 && lastAcquiredAtMs > 0) {
                long idleMs = System.currentTimeMillis() - lastAcquiredAtMs;
                if (idleMs > (long) idleTimeoutSecs * 1000L) {
                    LOGGER.info(String.format(
                            "vSphere connection pool [%s]: disconnecting — idle for %ds (limit %ds)",
                            config.getVsHost(), idleMs / 1000, idleTimeoutSecs));
                    disconnectQuietly();
                }
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

        if (idleTimeoutSecs > 0 || sessionMaxAgeSecs > 0) {
            // Pick a maintenance interval that fires frequently enough to be useful
            // but not so often that it wastes resources.
            int maintenanceSecs = 30;
            if (idleTimeoutSecs > 0) {
                maintenanceSecs = Math.min(maintenanceSecs, Math.max(5, idleTimeoutSecs / 2));
            }
            if (sessionMaxAgeSecs > 0) {
                maintenanceSecs = Math.min(maintenanceSecs, Math.max(5, sessionMaxAgeSecs / 4));
            }
            scheduler.scheduleAtFixedRate(
                    this::scheduledMaintenance,
                    maintenanceSecs, maintenanceSecs, TimeUnit.SECONDS);
        }
    }

    private void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
