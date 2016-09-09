package org.jenkinsci.plugins.vsphere.tools;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;

/**
 * How we decide what template to create the next slave on.
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
}