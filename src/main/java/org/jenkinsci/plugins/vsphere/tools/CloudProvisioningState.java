package org.jenkinsci.plugins.vsphere.tools;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;

/**
 * Utility class that works out what agents we should start up in response to
 * Jenkins asking us to start things.
 * <p>
 * We do this by keeping a record of every agent we start, and every agent we
 * have active. That way, we can avoid over-provisioning.
 * </p>
 * <p>
 * The idea is that we are told what agents that the cloud is going to create,
 * when the cloud has created them (or failed to) and when those agents have
 * died. This way we can keep track of everything, in order to allow the cloud
 * to make accurate decisions regarding what to create next.
 * </p>
 * Note: This is not thread-safe. Callers must do their own synchronization.
 */
public class CloudProvisioningState {
    private static final Logger LOGGER = Logger.getLogger(CloudProvisioningState.class.getName());
    /**
     * Record of agents we've told Jenkins to start up, which have yet to start.
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
     * To be called when we've decided to create a new node. Callers MUST ensure
     * that {@link #provisionedSlaveNowActive(CloudProvisioningRecord, String)}
     * or {@link #provisioningEndedInError(CloudProvisioningRecord, String)}
     * gets called later.
     * 
     * @param provisionable
     *            Our record for the template for the named node.
     * @param nodeName
     *            The name of the VM.
     */
    public void provisioningStarted(CloudProvisioningRecord provisionable, String nodeName) {
        final boolean wasPreviouslyUnknownToPlanning = provisionable.addCurrentlyPlanned(nodeName);
        final boolean wasAlreadyActive = provisionable.removeCurrentlyActive(nodeName);
        final boolean wasPreviouslyUnwanted = provisionable.removeCurrentlyUnwanted(nodeName);
        logStateChange(Level.FINE, "Intending to create {0}",
                "wasPreviouslyUnknownToPlanning", wasPreviouslyUnknownToPlanning, true,
                "wasAlreadyActive", wasAlreadyActive, false,
                "wasPreviouslyUnwanted", wasPreviouslyUnwanted, false,
                nodeName);
    }

    /**
     * To be called when a newly created node (previously promised to
     * {@link #provisioningStarted(CloudProvisioningRecord, String)}) comes up.
     * Callers MUST ensure that
     * {@link #provisionedSlaveNowUnwanted(String, boolean)} gets called later
     * when we do not want it anymore.
     * 
     * @param provisionable
     *            Our record for the template for the named node.
     * @param nodeName
     *            The name of the VM.
     */
    public void provisionedSlaveNowActive(CloudProvisioningRecord provisionable, String nodeName) {
        final boolean wasNotPreviouslyActive = provisionable.addCurrentlyActive(nodeName);
        final boolean wasPreviouslyPlanned = provisionable.removeCurrentlyPlanned(nodeName);
        final boolean wasPreviouslyUnwanted = provisionable.removeCurrentlyUnwanted(nodeName);
        logStateChange(Level.FINE, "Marking {0} as active",
                "wasNotPreviouslyActive", wasNotPreviouslyActive, true,
                "wasPreviouslyPlanned", wasPreviouslyPlanned, true,
                "wasPreviouslyUnwanted", wasPreviouslyUnwanted, false,
                nodeName);
    }

    /**
     * To be called when a node we created (previously told to
     * {@link #provisionedSlaveNowActive(CloudProvisioningRecord, String)}) is
     * no longer wanted and should be deleted.
     * 
     * @param nodeName
     *            The name of the VM.
     * @param willAttemptImmediateDeletion
     *            If true then the caller must attempt to delete the agent and
     *            guarantee that they will call
     *            {@link #unwantedSlaveNotDeleted(String)} or
     *            {@link #unwantedSlaveNowDeleted(String)} as appropriate (just
     *            as if they'd called {@link #isOkToDeleteUnwantedVM(String)}
     *            and been told True). If false then the caller is under no such
     *            obligation.
     */
    public void provisionedSlaveNowUnwanted(String nodeName, boolean willAttemptImmediateDeletion) {
        final Map.Entry<vSphereCloudSlaveTemplate, CloudProvisioningRecord> entry = findEntryForVM(nodeName);
        if (entry != null) {
            final CloudProvisioningRecord provisionable = entry.getValue();
            final boolean wasPreviouslyPlanned = provisionable.removeCurrentlyPlanned(nodeName);
            final boolean wasPreviouslyActive = provisionable.removeCurrentlyActive(nodeName);
            final boolean wasNotPreviouslyUnwanted = provisionable.setCurrentlyUnwanted(nodeName, willAttemptImmediateDeletion)==null;
            logStateChange(Level.FINE, "Marking {0} for termination",
                    "wasPreviouslyPlanned", wasPreviouslyPlanned, false,
                    "wasPreviouslyActive", wasPreviouslyActive, true,
                    "wasNotPreviouslyUnwanted", wasNotPreviouslyUnwanted, true,
                    nodeName);
        } else {
            logger.log(Level.WARNING, "Asked to mark {0} for termination, but we have no record of it.", nodeName);
        }
    }

    /**
     * To be called before commencing the deletion of a VM.
     * 
     * @param nodeName
     *            The name of the VM being deleted.
     * @return null if the VM is not unwanted (it may have recently been
     *         deleted). false if another thread is currently trying to delete
     *         it. true if deletion should be attempted, in which case the
     *         caller MUST later call {@link #unwantedSlaveNowDeleted(String)}
     *         or {@link #unwantedSlaveNotDeleted(String)}.
     */
    public Boolean isOkToDeleteUnwantedVM(String nodeName) {
        final Map.Entry<vSphereCloudSlaveTemplate, CloudProvisioningRecord> entry = findEntryForVM(nodeName);
        if (entry == null) {
            return null;
        }
        final CloudProvisioningRecord record = entry.getValue();
        final Boolean thisNode = record.isCurrentlyUnwanted(nodeName);
        if (thisNode == null) {
            return null;
        }
        boolean someoneElseIsDeletingThis = thisNode.booleanValue();
        boolean isOkForUsToDeleteIt = !someoneElseIsDeletingThis;
        if (isOkForUsToDeleteIt) {
            record.setCurrentlyUnwanted(nodeName, true);
        }
        return Boolean.valueOf(isOkForUsToDeleteIt);
    }

    /**
     * MUST be called when a node previously declared to be unwanted (previously
     * told to {@link #provisionedSlaveNowUnwanted(String, boolean)}) and that
     * we were given clearance to delete
     * ({@link #isOkToDeleteUnwantedVM(String)} returned true) has been
     * successfully removed.
     * 
     * @param nodeName
     *            The name of the VM that was successfully deleted.
     */
    public void unwantedSlaveNowDeleted(String nodeName) {
        final Map.Entry<vSphereCloudSlaveTemplate, CloudProvisioningRecord> entry = findEntryForVM(nodeName);
        if (entry != null) {
            final CloudProvisioningRecord provisionable = entry.getValue();
            final boolean wasPreviouslyPlanned = provisionable.removeCurrentlyPlanned(nodeName);
            final boolean wasPreviouslyActive = provisionable.removeCurrentlyActive(nodeName);
            final boolean wasPreviouslyUnwanted = provisionable.removeCurrentlyUnwanted(nodeName);
            if (recordIsPrunable(provisionable)) {
                removeExistingRecord(provisionable);
            }
            logStateChange(Level.FINE, "Marking {0} as successfully terminated",
                    "wasPreviouslyPlanned", wasPreviouslyPlanned, false,
                    "wasPreviouslyActive", wasPreviouslyActive, false,
                    "wasPreviouslyUnwanted", wasPreviouslyUnwanted, true,
                    nodeName);
        } else {
            logger.log(Level.WARNING, "Asked to mark {0} as terminated, but we had no record of it.", nodeName);
        }
    }

    /**
     * MUST be called when a node previously declared to be unwanted (previously
     * told to {@link #provisionedSlaveNowUnwanted(String, boolean)}) and that
     * we were given clearance to delete
     * ({@link #isOkToDeleteUnwantedVM(String)} returned true) failed to be
     * removed.
     * 
     * @param nodeName
     *            The name of the VM that failed to delete
     */
    public void unwantedSlaveNotDeleted(String nodeName) {
        final Map.Entry<vSphereCloudSlaveTemplate, CloudProvisioningRecord> entry = findEntryForVM(nodeName);
        if (entry != null) {
            final CloudProvisioningRecord provisionable = entry.getValue();
            final boolean isPlanned = provisionable.getCurrentlyPlanned().contains(nodeName);
            final boolean isActive = provisionable.getCurrentlyProvisioned().contains(nodeName);
            final boolean isUnwanted = provisionable.setCurrentlyUnwanted(nodeName, false) != null;
            logStateChange(Level.INFO, "Marking {0} as unsuccessfully terminated - we'll have to try again later",
                    "isPlanned", isPlanned, false,
                    "isActive", isActive, false,
                    "isUnwanted", isUnwanted, true,
                    nodeName);
        } else {
            logger.log(Level.WARNING, "Asked to mark {0} as unsuccessfully terminated, but we had no record of it.",
                    nodeName);
        }
    }

    /**
     * To be called if we become aware that there is a VM that exist in vSphere
     * (that we created) which we don't want anymore.
     * 
     * @param template
     *            The template to which the node belonged.
     * @param nodeName
     *            The name of the node that exists (despite our wishes).
     */
    public void recordExistingUnwantedVM(final vSphereCloudSlaveTemplate template, String nodeName) {
        final CloudProvisioningRecord record = getOrCreateRecord(template);
        final boolean wasPreviouslyPlanned = record.removeCurrentlyPlanned(nodeName);
        final boolean wasPreviouslyActive = record.removeCurrentlyActive(nodeName);
        final boolean wasAlreadyUnwanted = record.setCurrentlyUnwanted(nodeName, false) != null;
        logStateChange(Level.INFO, "Marking {0} as found in vSphere but unwanted",
                "wasPreviouslyPlanned", wasPreviouslyPlanned, false,
                "wasPreviouslyActive", wasPreviouslyActive, false,
                "wasAlreadyUnwanted", wasAlreadyUnwanted, false,
                nodeName);
    }

    /**
     * To be called when a node that we previously promised to create (by
     * calling {@link #provisioningStarted(CloudProvisioningRecord, String)})
     * failed to start.
     * 
     * @param provisionable
     *            Our record for the template for the named node.
     * @param nodeName
     *            The name of the VM.
     */
    public void provisioningEndedInError(CloudProvisioningRecord provisionable, String nodeName) {
        final boolean wasPreviouslyPlanned = provisionable.removeCurrentlyPlanned(nodeName);
        final boolean wasPreviouslyActive = provisionable.removeCurrentlyActive(nodeName);
        final boolean wasPreviouslyUnwanted = provisionable.removeCurrentlyUnwanted(nodeName);
        if (recordIsPrunable(provisionable)) {
            removeExistingRecord(provisionable);
        }
        logStateChange(Level.INFO, "Marking {0} as failed",
                "wasPreviouslyPlanned", wasPreviouslyPlanned, true,
                "wasPreviouslyActive", wasPreviouslyActive, false,
                "wasPreviouslyUnwanted", wasPreviouslyUnwanted, false,
                nodeName);
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

    /**
     * Counts all the known nodes, active, in-progress and being-deleted, across
     * all templates.
     * 
     * @return The number of nodes that exist (or will do).
     */
    public int countNodes() {
        int result = 0;
        for (final CloudProvisioningRecord record : records.values()) {
            result += record.size();
        }
        return result;
    }

    /**
     * Gets the record for the given template. If we didn't have one before, we
     * create one.
     * 
     * @param template
     *            The template in question.
     * @return The one-and-only record for this template.
     */
    public CloudProvisioningRecord getOrCreateRecord(final vSphereCloudSlaveTemplate template) {
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

    /**
     * Calculates the current list of "existing but unwanted" VMs, in priority
     * order. Note: The returned data is not "live", it's a copy, so callers are
     * free to edit the {@link List} they are given.
     * 
     * @return A copy of the list of VMs that we know exist but no longer want,
     *         and which aren't in the process of being deleted by anyone.
     */
    public List<String> getUnwantedVMsThatNeedDeleting() {
        // find out who needs what deleted
        int count = 0;
        final Map<CloudProvisioningRecord, Iterator<String>> allUnwantedVmsByRecord = new LinkedHashMap<>();
        for (final CloudProvisioningRecord record : records.values()) {
            final Map<String, Boolean> currentlyUnwanted = record.getCurrentlyUnwanted();
            final List<String> vmsInNeedOfDeletionForThisRecord = new ArrayList<String>(currentlyUnwanted.size());
            for (Map.Entry<String, Boolean> entry : currentlyUnwanted.entrySet()) {
                if (entry.getValue() == Boolean.FALSE) {
                    vmsInNeedOfDeletionForThisRecord.add(entry.getKey());
                }
            }
            count += vmsInNeedOfDeletionForThisRecord.size();
            allUnwantedVmsByRecord.put(record, vmsInNeedOfDeletionForThisRecord.iterator());
        }
        // arrange the list in a round-robin order, taking the first from each
        // template, followed by the second from each etc.
        final List<String> vmsInNeedOfDeletion = new ArrayList<String>(count);
        while (vmsInNeedOfDeletion.size() < count) {
            for (Iterator<String> i : allUnwantedVmsByRecord.values()) {
                if (i.hasNext()) {
                    final String nodeName = i.next();
                    vmsInNeedOfDeletion.add(nodeName);
                }
            }
        }
        return vmsInNeedOfDeletion;
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
        final boolean isEmpty = record.isEmpty();
        if (!isEmpty) {
            return false;
        }
        final vSphereCloudSlaveTemplate template = record.getTemplate();
        final List<? extends vSphereCloudSlaveTemplate> knownTemplates = parent.getTemplates();
        final boolean isKnownToParent = knownTemplates.contains(template);
        return !isKnownToParent;
    }

    private Map.Entry<vSphereCloudSlaveTemplate, CloudProvisioningRecord> findEntryForVM(String nodeName) {
        for (final Map.Entry<vSphereCloudSlaveTemplate, CloudProvisioningRecord> entry : records.entrySet()) {
            final CloudProvisioningRecord record = entry.getValue();
            if (record.contains(nodeName)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Logs a state change. If the state change isn't valid, it's logged as a
     * warning.
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
     * @param thirdArgName
     *            What actualThirdArgValue represents - used when complaining
     *            about its value.
     * @param actualThirdArgValue
     *            A state-change variable.
     * @param expectedThirdArgValue
     *            The expected value of actualThirdArgValue. If that's not the
     *            case, we'll complain.
     * @param args
     *            The arguments for logMsg. Used if logMsg contains {0}, {1}
     *            etc.
     */
    private void logStateChange(Level logLevel, String logMsg,
            String firstArgName, boolean actualFirstArgValue, boolean expectedFirstArgValue,
            String secondArgName, boolean actualSecondArgValue, boolean expectedSecondArgValue,
            String thirdArgName, boolean actualThirdArgValue, boolean expectedThirdArgValue,
            Object... args) {
        final boolean firstValid = actualFirstArgValue == expectedFirstArgValue;
        final boolean secondValid = actualSecondArgValue == expectedSecondArgValue;
        final boolean thirdValid = actualThirdArgValue == expectedThirdArgValue;
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
        if (!thirdValid) {
            actualMsg += " : " + thirdArgName + "!=" + expectedThirdArgValue;
            actualLevel = Level.WARNING;
        }
        final Logger loggerToUse = logger != null ? logger : LOGGER;
        loggerToUse.log(actualLevel, actualMsg, args);
    }
}
