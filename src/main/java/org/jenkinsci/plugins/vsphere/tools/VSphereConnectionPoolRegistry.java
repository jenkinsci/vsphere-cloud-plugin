package org.jenkinsci.plugins.vsphere.tools;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tracks every {@link VSphereConnectionPool} currently in existence, so that pools left
 * behind by a replaced/removed {@code vSphereCloud} instance (e.g. after the cloud config
 * is re-saved from the UI or JCasC) can be found and shut down.
 *
 * <p>Without this, a pool's background thread (and any vCenter session it holds open)
 * would otherwise keep running indefinitely under its old settings, invisible to anyone
 * looking at the current cloud configuration.
 */
final class VSphereConnectionPoolRegistry {

    private static final Logger LOGGER = Logger.getLogger(VSphereConnectionPoolRegistry.class.getName());

    private static final Set<VSphereConnectionPool> LIVE_POOLS = ConcurrentHashMap.newKeySet();

    private VSphereConnectionPoolRegistry() {
    }

    static void register(VSphereConnectionPool pool) {
        LIVE_POOLS.add(pool);
    }

    static void unregister(VSphereConnectionPool pool) {
        LIVE_POOLS.remove(pool);
    }

    /** Visible for testing: whether {@code pool} is still tracked (i.e. not shut down). */
    static boolean isTracked(VSphereConnectionPool pool) {
        return LIVE_POOLS.contains(pool);
    }

    /**
     * Shuts down (and unregisters) every currently-registered pool whose owning cloud is
     * no longer present in the live Jenkins configuration.
     */
    static void reapOrphans() {
        for (VSphereConnectionPool pool : LIVE_POOLS) {
            if (pool.isOrphaned()) {
                LOGGER.info("Shutting down orphaned vSphere connection pool left behind by a replaced/removed cloud");
                pool.shutdown();
            }
        }
    }
}
