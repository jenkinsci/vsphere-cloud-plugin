package org.jenkinsci.plugins.vsphere.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;

/**
 * Utility class that works out what slaves we should start up in response to
 * Jenkins asking us to start things.
 * <p/>
 * We do this by keeping a record of every slave we start, and every slave we
 * have active. That way, we can avoid over-provisioning.<br/>
 * The idea is that we're told what slaves we're going to create, when we've
 * created them (or failed to) and when they've died.
 * <p/>
 * Note: This is not thread-safe. Callers must do their own synchronization.
 */
public class CloudProvisioningState {
    private static final Logger LOGGER = Logger.getLogger(CloudProvisioningState.class.getName());
    /**
     * Record of slaves we've told Jenkins to start up, which have yet to start.
     */
    private final Map<vSphereCloudSlaveTemplate, CloudProvisioningRecord> records = new IdentityHashMap<vSphereCloudSlaveTemplate, CloudProvisioningRecord>();
    /**
     * Our parent, so we can check what templates still exist (as the user may
     * have added/removed some).
     */
    private final vSphereCloud parent;
    /**
     * Where we log to. This is only instance-based for test-purposes, and
     * transient to stop serialization problems.
     */
    private transient final Logger logger;

    public CloudProvisioningState(vSphereCloud parent) {
        this(parent, LOGGER);
    }

    CloudProvisioningState(vSphereCloud parent, Logger logger) {
        this.parent = parent;
        this.logger = logger;
        this.logger.log(Level.FINE, "Created for parent {0}", parent.toString());
    }

    /**
     * To be called when we've decided to create a new node.<br/>
     * Callers MUST ensure that
     * {@link #provisionedSlaveNowActive(CloudProvisioningRecord, String)} or
     * {@link #provisioningEndedInError(CloudProvisioningRecord, String)} gets
     * called later.
     */
    public void provisioningStarted(CloudProvisioningRecord provisionable, String nodeName) {
        final boolean wasPreviouslyUnknownToPlanning = provisionable.addCurrentlyPlanned(nodeName);
        final boolean wasAlreadyActive = provisionable.removeCurrentlyActive(nodeName);
        logStateChange(Level.FINE, "Intending to create {0}", "wasPreviouslyUnknownToPlanning",
                wasPreviouslyUnknownToPlanning, true, "wasAlreadyActive", wasAlreadyActive, false, nodeName);
    }

    /**
     * To be called when a newly created node (previously promised to
     * {@link #provisioningStarted(CloudProvisioningRecord, String)}) comes up.<br/>
     * Callers MUST ensure that
     * {@link #provisionedSlaveNowTerminated(vSphereCloudSlaveTemplate, String)}
     * gets called later.
     */
    public void provisionedSlaveNowActive(CloudProvisioningRecord provisionable, String nodeName) {
        final boolean wasNotPreviouslyActive = provisionable.addCurrentlyActive(nodeName);
        final boolean wasPreviouslyPlanned = provisionable.removeCurrentlyPlanned(nodeName);
        logStateChange(Level.FINE, "Marking {0} as active", "wasNotPreviouslyActive", wasNotPreviouslyActive, true,
                "wasPreviouslyPlanned", wasPreviouslyPlanned, true, nodeName);
    }

    /**
     * To be called when a node we created (previously told to
     * {@link #provisionedSlaveNowActive(CloudProvisioningRecord, String)}) has
     * died.
     */
    public void provisionedSlaveNowTerminated(vSphereCloudSlaveTemplate template, String nodeName) {
        final CloudProvisioningRecord provisionable = getExistingRecord(template);
        if (provisionable != null) {
            final boolean wasPreviouslyPlanned = provisionable.removeCurrentlyPlanned(nodeName);
            final boolean wasPreviouslyActive = provisionable.removeCurrentlyActive(nodeName);
            if (recordIsPrunable(provisionable)) {
                removeExistingRecord(provisionable);
            }
            logStateChange(Level.FINE, "Marking {0} as terminated", "wasPreviouslyPlanned", wasPreviouslyPlanned,
                    false, "wasPreviouslyActive", wasPreviouslyActive, true, nodeName);
        } else {
            logger.log(Level.WARNING, "Asked to mark {0} as terminated, but we have no record of it.", nodeName);
        }
    }

    /**
     * To be called when a node that we previously promised to create (by
     * calling {@link #provisioningStarted(CloudProvisioningRecord, String)})
     * failed to start.
     */
    public void provisioningEndedInError(CloudProvisioningRecord provisionable, String nodeName) {
        final boolean wasPreviouslyPlanned = provisionable.removeCurrentlyPlanned(nodeName);
        final boolean wasPreviouslyActive = provisionable.removeCurrentlyActive(nodeName);
        if (recordIsPrunable(provisionable)) {
            removeExistingRecord(provisionable);
        }
        logStateChange(Level.INFO, "Marking {0} as failed", "wasPreviouslyPlanned", wasPreviouslyPlanned, true,
                "wasPreviouslyActive", wasPreviouslyActive, false, nodeName);
    }

    /**
     * To be called every now and again to ensure that we're not caching records
     * that will never be valid again.
     */
    public void pruneUnwantedRecords() {
        final List<CloudProvisioningRecord> toBeRemoved = new ArrayList<CloudProvisioningRecord>(records.size());
        for (final Map.Entry<vSphereCloudSlaveTemplate, CloudProvisioningRecord> entry : records.entrySet()) {
            final CloudProvisioningRecord record = entry.getValue();
            if (recordIsPrunable(record)) {
                toBeRemoved.add(record);
            }
        }
        for (final CloudProvisioningRecord record : toBeRemoved) {
            removeExistingRecord(record);
        }
    }

    /**
     * Given a set of templates, returns the equivalent records.
     * 
     * @param templates
     *            The templates we are interested in.
     * @return A list of {@link CloudProvisioningRecord}.
     */
    public List<CloudProvisioningRecord> calculateProvisionableTemplates(Iterable<vSphereCloudSlaveTemplate> templates) {
        final List<CloudProvisioningRecord> result = new ArrayList<CloudProvisioningRecord>();
        for (final vSphereCloudSlaveTemplate template : templates) {
            final CloudProvisioningRecord provisionable = getOrCreateRecord(template);
            result.add(provisionable);
        }
        return result;
    }

    private CloudProvisioningRecord getOrCreateRecord(final vSphereCloudSlaveTemplate template) {
        final CloudProvisioningRecord existingRecord = getExistingRecord(template);
        if (existingRecord != null) {
            return existingRecord;
        }
        final CloudProvisioningRecord newRecord = new CloudProvisioningRecord(template);
        logger.log(Level.FINE, "Creating new record for template {0} ({1})",
                new Object[] { template.getCloneNamePrefix(), template.toString() });
        records.put(template, newRecord);
        return newRecord;
    }

    private CloudProvisioningRecord getExistingRecord(final vSphereCloudSlaveTemplate template) {
        return records.get(template);
    }

    private void removeExistingRecord(CloudProvisioningRecord existingRecord) {
        final vSphereCloudSlaveTemplate template = existingRecord.getTemplate();
        logger.log(Level.FINE, "Disposing of record for template {0} ({1})",
                new Object[] { template.getCloneNamePrefix(), template.toString() });
        records.remove(template);
    }

    private boolean recordIsPrunable(CloudProvisioningRecord record) {
        final boolean isEmpty = record.getCurrentlyProvisioned().isEmpty() && record.getCurrentlyPlanned().isEmpty();
        if (!isEmpty) {
            return false;
        }
        final vSphereCloudSlaveTemplate template = record.getTemplate();
        final List<? extends vSphereCloudSlaveTemplate> knownTemplates = parent.getTemplates();
        final boolean isKnownToParent = knownTemplates.contains(template);
        return !isKnownToParent;
    }

    /**
     * Logs a state change.<br/>
     * If the state change isn't valid, it's logged as a warning.
     * 
     * @param logLevel
     *            The level to log the message at, if the boolean arguments are
     *            as their expected values.
     * @param logMsg
     *            The message to log.
     * @param firstArgName
     *            What actualFirstArgValue represents - used when complaining
     *            about its value.
     * @param actualFirstArgValue
     *            A state-change variable.
     * @param expectedFirstArgValue
     *            The expected value of actualFirstArgValue. If that's not the
     *            case, we'll complain.
     * @param secondArgName
     *            What actualSecondArgValue represents - used when complaining
     *            about its value.
     * @param actualSecondArgValue
     *            A state-change variable.
     * @param expectedSecondArgValue
     *            The expected value of actualSecondArgValue. If that's not the
     *            case, we'll complain.
     * @param args
     *            The arguments for logMsg. Used if logMsg contains {0}, {1}
     *            etc.
     */
    private void logStateChange(Level logLevel, String logMsg, String firstArgName, boolean actualFirstArgValue,
            boolean expectedFirstArgValue, String secondArgName, boolean actualSecondArgValue,
            boolean expectedSecondArgValue, Object... args) {
        final boolean firstValid = actualFirstArgValue == expectedFirstArgValue;
        final boolean secondValid = actualSecondArgValue == expectedSecondArgValue;
        Level actualLevel = logLevel;
        String actualMsg = logMsg;
        if (!firstValid) {
            actualMsg += " : " + firstArgName + "!=" + expectedFirstArgValue;
            actualLevel = Level.WARNING;
        }
        if (!secondValid) {
            actualMsg += " : " + secondArgName + "!=" + expectedSecondArgValue;
            actualLevel = Level.WARNING;
        }
        final Logger loggerToUse = logger != null ? logger : LOGGER;
        loggerToUse.log(actualLevel, actualMsg, args);
    }
}
