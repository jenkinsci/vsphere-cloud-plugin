package org.jenkinsci.plugins.vsphere.tools;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * How we decide what template to create the next agent on.
 */
public final class CloudProvisioningAlgorithm {
    private CloudProvisioningAlgorithm() {
    }

    /**
     * Given a bunch of templates to choose from, works out which one we should
     * use next.
     * 
     * @param provisionables
     *            Template records to decide between.
     * @return The record with the most free capacity, or null if there are none
     *         with any capacity.
     */
    public static CloudProvisioningRecord findTemplateWithMostFreeCapacity(
            Collection<? extends CloudProvisioningRecord> provisionables) {
        final SortedSet<CloudProvisioningRecord> sortedSet = new TreeSet<CloudProvisioningRecord>(
                CloudProvisioningRecord.leastUsedFirst);
        sortedSet.addAll(provisionables);
        final Iterator<CloudProvisioningRecord> iterator = sortedSet.iterator();
        if (iterator.hasNext()) {
            final CloudProvisioningRecord bestOption = iterator.next();
            if (bestOption.hasCapacityForMore()) {
                return bestOption;
            }
        }
        return null;
    }

    /**
     * Chooses a name for a new node. The name will start with the
     * {@link vSphereCloudSlaveTemplate#getCloneNamePrefix()}.
     * <ul>
     * <li>If the template has a limited number of instances available then the
     * name will be of the form "prefix<i>number</i>" where "<i>number</i>" is a
     * number that should be between 1 and the number of permitted instances.
     * </li>
     * <li>If the template has an unlimited number of instances available then
     * the name will be of the form "prefix<i>random</i>" where "<i>random</i>"
     * is a random UUID's 32-byte (128 bit) number (rendered using a high radix
     * to keep the string short).</li>
     * </ul>
     * 
     * @param record
     *            Our record regarding the template the agent will be created
     *            from.
     * @return A name for the new node. This will start with the
     *         {@link vSphereCloudSlaveTemplate#getCloneNamePrefix()}.
     */
    public static String findUnusedName(CloudProvisioningRecord record) {
        final vSphereCloudSlaveTemplate template = record.getTemplate();
        final String cloneNamePrefix = template.getCloneNamePrefix();
        final Set<String> existingNames = record.getCurrentNames();
        final int templateInstanceCap = template.getTemplateInstanceCap();
        final boolean hasCap = templateInstanceCap > 0 && templateInstanceCap < Integer.MAX_VALUE;
        final int maxAttempts = hasCap ? (templateInstanceCap) : 100;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            final String suffix = hasCap ? calcSequentialSuffix(attempt) : calcRandomSuffix(attempt);
            final String nodeName = cloneNamePrefix + suffix;
            if (!existingNames.contains(nodeName)) {
                return nodeName;
            }
        }
        throw new IllegalStateException("Unable to find unused name for slave for record " + record.toString()
                + ", even after " + maxAttempts + " attempts.");
    }

    /**
     * Compares sum of provisioned and planned nodes for the template.
     *
     * If the sum is less than instanceMin template value we should provision more nodes,
     * otherwise the value is satisfied and we should not add any more nodes yet.
     *
     * @param record
     *            Our record regarding the template the agent will be created
     *            from.
     * @return A number of nodes to be provisioned.
     */
    public static int shouldPreProvisionNodes(CloudProvisioningRecord record) {
        int provisionedNodes = record.getCurrentlyProvisioned().size() + record.getCurrentlyPlanned().size();
        int requiredPreProvisionedNodes = record.getTemplate().getInstancesMin();
        return requiredPreProvisionedNodes - provisionedNodes;
    }

    private static String calcSequentialSuffix(final int attempt) {
        final int slaveNumber = attempt + 1;
        final String suffix = Integer.toString(slaveNumber);
        return suffix;
    }

    private static String calcRandomSuffix(int attempt) {
        // get "unique" UUID
        final UUID uuid = UUID.randomUUID();
        // put both "long"s into a BigInteger.
        final long lsb = uuid.getLeastSignificantBits();
        final long msb = uuid.getMostSignificantBits();
        final BigInteger bigNumber = toBigInteger(msb, lsb);
        // turn into a string
        final String suffix = bigNumber.toString(Character.MAX_RADIX);
        return suffix;
    }

    /**
     * Turns two 64-bit long numbers into a positive 128-bit {@link BigInteger}
     * that's in the range 0 to 2<sup>128</sup>-1.
     * <p>
     * <b>Note:</b> This is only package-level access for unit-testing.
     * </p>
     * 
     * @param msb
     *            The most-significant 64 bits.
     * @param lsb
     *            The least-significant 64 bits.
     * @return A {@link BigInteger}.
     */
    @Restricted(NoExternalUse.class)
    static BigInteger toBigInteger(final long msb, final long lsb) {
        final byte[] bytes = new byte[17];
        int b = 0;
        bytes[b++] = (byte) 0; // ensure we're all positive
        bytes[b++] = (byte) (msb >> 56);
        bytes[b++] = (byte) (msb >> 48);
        bytes[b++] = (byte) (msb >> 40);
        bytes[b++] = (byte) (msb >> 32);
        bytes[b++] = (byte) (msb >> 24);
        bytes[b++] = (byte) (msb >> 16);
        bytes[b++] = (byte) (msb >> 8);
        bytes[b++] = (byte) (msb);
        bytes[b++] = (byte) (lsb >> 56);
        bytes[b++] = (byte) (lsb >> 48);
        bytes[b++] = (byte) (lsb >> 40);
        bytes[b++] = (byte) (lsb >> 32);
        bytes[b++] = (byte) (lsb >> 24);
        bytes[b++] = (byte) (lsb >> 16);
        bytes[b++] = (byte) (lsb >> 8);
        bytes[b++] = (byte) (lsb);
        final BigInteger bigNumber = new BigInteger(bytes);
        return bigNumber;
    }
}
