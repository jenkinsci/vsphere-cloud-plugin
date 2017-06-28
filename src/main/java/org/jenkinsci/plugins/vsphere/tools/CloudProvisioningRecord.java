package org.jenkinsci.plugins.vsphere.tools;

import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;

/**
 * There's a delay between when we give a bunch of slave nodes to Jenkins (when
 * it asks us to provision some) and when those nodes appear in vSphere and in
 * Jenkins, so we need to keep a record of what's in progress so we don't
 * over-commit.
 */
public final class CloudProvisioningRecord {
    private final vSphereCloudSlaveTemplate template;
    private Set<String> currentlyProvisioned;
    private Set<String> currentlyPlanned;

    CloudProvisioningRecord(vSphereCloudSlaveTemplate template) {
        this.template = template;
        this.currentlyProvisioned = new TreeSet<String>();
        this.currentlyPlanned = new TreeSet<String>();
    }

    public vSphereCloudSlaveTemplate getTemplate() {
        return template;
    }

    @Override
    public String toString() {
        return String.format("Template[prefix=%s, provisioned=%s, planned=%s, max=%d, fullness=%.3f%%]", getTemplate()
                .getCloneNamePrefix(), getCurrentlyProvisioned(), getCurrentlyPlanned(), calcMaxToProvision(),
                calcFullness() * 100.0);
    }

    Set<String> getCurrentlyProvisioned() {
        return currentlyProvisioned;
    }

    boolean addCurrentlyActive(String nodeName) {
        return currentlyProvisioned.add(nodeName);
    }

    boolean removeCurrentlyActive(String nodeName) {
        return currentlyProvisioned.remove(nodeName);
    }

    Set<String> getCurrentlyPlanned() {
        return currentlyPlanned;
    }

    boolean addCurrentlyPlanned(String nodeName) {
        return currentlyPlanned.add(nodeName);
    }

    boolean removeCurrentlyPlanned(String nodeName) {
        return currentlyPlanned.remove(nodeName);
    }

    private int calcMaxToProvision() {
        final int templateInstanceCap = template.getTemplateInstanceCap();
        final int maxToProvision = templateInstanceCap == 0 ? Integer.MAX_VALUE : templateInstanceCap;
        return maxToProvision;
    }

    private boolean hasFiniteCapacity() {
        final int templateInstanceCap = template.getTemplateInstanceCap();
        final int maxToProvision = templateInstanceCap == 0 ? Integer.MAX_VALUE : templateInstanceCap;
        return maxToProvision != Integer.MAX_VALUE;
    }

    private double calcFullness() {
        final int maxToProvision = calcMaxToProvision();
        return ((double) calcCurrentCommitment()) / (double) maxToProvision;
    }

    boolean hasCapacityForMore() {
        final int totalCommitment = calcCurrentCommitment();
        final int maxToProvision = calcMaxToProvision();
        return maxToProvision > totalCommitment;
    }

    private int calcCurrentCommitment() {
        return currentlyProvisioned.size() + currentlyPlanned.size();
    }

    /**
     * Sorts {@link CloudProvisioningRecord}s, putting the ones with most free
     * capacity first.
     * <p/>
     * When comparing two records with finite capacity then their usage:limit
     * ratios are compared, otherwise current usage levels are compared.
     */
    static final Comparator<CloudProvisioningRecord> leastUsedFirst = new Comparator<CloudProvisioningRecord>() {
        private static final int theyAreTheSame = 0;
        private static final int bShouldComeLast = -1;
        private static final int aShouldComeLast = 1;

        @Override
        public int compare(CloudProvisioningRecord a, CloudProvisioningRecord b) {
            if (b == a) {
                return theyAreTheSame;
            }
            final int compareByCapacity;
            if (a.hasFiniteCapacity() && b.hasFiniteCapacity()) {
                compareByCapacity = compareByUsageRatio(a, b);
            } else {
                compareByCapacity = compareByUsage(a, b);
            }
            if (compareByCapacity != theyAreTheSame) {
                return compareByCapacity;
            }
            final int compareByMaxCapacity = compareByMaxCapacity(a, b);
            if (compareByMaxCapacity != theyAreTheSame) {
                return compareByMaxCapacity;
            }
            return tieBreak(a, b);
        }

        /** if both have instance caps, we rank by utilization:capacity ratio */
        private int compareByUsageRatio(CloudProvisioningRecord a, CloudProvisioningRecord b) {
            // sort by utilization:capacity ratio - lowest usage comes first
            final double aFullness = a.calcFullness();
            final double bFullness = b.calcFullness();
            if (aFullness > bFullness) {
                return aShouldComeLast;
            }
            if (aFullness < bFullness) {
                return bShouldComeLast;
            }
            return theyAreTheSame;
        }

        /**
         * if either has no instance cap, we rank by least usage UNLESS one of
         * them is full
         */
        private int compareByUsage(CloudProvisioningRecord a, CloudProvisioningRecord b) {
            // sort by "is full" - ones that are full come last
            final boolean aFull = !a.hasCapacityForMore();
            final boolean bFull = !b.hasCapacityForMore();
            if (aFull != bFull) {
                if (aFull) {
                    return aShouldComeLast;
                } else {
                    return bShouldComeLast;
                }
            }
            // sort by utilization - lowest usage comes first
            final double aUsage = a.calcCurrentCommitment();
            final double bUsage = b.calcCurrentCommitment();
            if (aUsage > bUsage) {
                return aShouldComeLast;
            }
            if (aUsage < bUsage) {
                return bShouldComeLast;
            }
            return theyAreTheSame;
        }

        /** Try rank by capacity */
        private int compareByMaxCapacity(CloudProvisioningRecord a, CloudProvisioningRecord b) {
            // by absolute capacity - highest comes first
            final int aCapacity = a.calcMaxToProvision();
            final int bCapacity = b.calcMaxToProvision();
            if (bCapacity > aCapacity) {
                return aShouldComeLast;
            }
            if (bCapacity < aCapacity) {
                return bShouldComeLast;
            }
            return theyAreTheSame;
        }

        /**
         * if all else is equal we prefer the one with fewer VMs being started
         * up
         */
        private int tieBreak(CloudProvisioningRecord a, CloudProvisioningRecord b) {
            // then by number of VMs being started - lowest comes first
            final int aCurrentlyPlanned = a.currentlyPlanned.size();
            final int bCurrentlyPlanned = b.currentlyPlanned.size();
            if (aCurrentlyPlanned > bCurrentlyPlanned) {
                return aShouldComeLast;
            }
            if (aCurrentlyPlanned < bCurrentlyPlanned) {
                return bShouldComeLast;
            }
            return theyAreTheSame;
        }
    };
}