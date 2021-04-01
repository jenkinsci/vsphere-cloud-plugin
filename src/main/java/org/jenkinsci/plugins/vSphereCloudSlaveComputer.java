package org.jenkinsci.plugins;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import com.vmware.vim25.VirtualHardware;
import com.vmware.vim25.VirtualMachineConfigInfo;
import com.vmware.vim25.VirtualMachineGuestSummary;
import com.vmware.vim25.VirtualMachineQuickStats;
import com.vmware.vim25.VirtualMachineSummary;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.VirtualMachine;

import hudson.model.Computer;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import jenkins.model.Jenkins;

public class vSphereCloudSlaveComputer extends AbstractCloudComputer {
    private final vSphereCloudSlave vSlave;
    /**
     * Cached VM details we show the user if they look at the
     * .../computer/NodeName/ page.
     * @see The corresponding main.jelly file for how this is used.
     */
    private transient VMInformation vmInformation;

    public vSphereCloudSlaveComputer(AbstractCloudSlave slave) {
        super(slave);
        vSlave = (vSphereCloudSlave) slave;
    }

    @Override
    public boolean isConnecting() {
        return (vSlave.slaveIsStarting == Boolean.TRUE) || super.isConnecting();
    }

    public String getCloudDescription() {
        final vSphereCloud ourCloud = vSlave.findOurVsInstance();
        return ourCloud == null ? null : ourCloud.getVsDescription();
    }

    public String getVmName() {
        return vSlave.getVmName();
    }

    public String getVmFolder() {
        return getVMInformation().folder;
    }

    public int getVmMemoryMB() {
        return getVMInformation().memoryMB;
    }

    public int getVmNumCPU() {
        return getVMInformation().numCPU;
    }

    public String getVmAnnotation() {
        return getVMInformation().annotation;
    }

    public String getVmGuestName() {
        return getVMInformation().guestName;
    }

    public String getVmGuestToolsStatus() {
        return getVMInformation().guestToolsStatus;
    }

    public int getVmUptimeSeconds() {
        return getVMInformation().uptimeSeconds;
    }

    public String getVmInformationError() {
        return getVMInformation().errorEncounteredWhenDataWasRead;
    }

    /**
     * Get all vsphere computers.
     */
    @Restricted(NoExternalUse.class)
    protected static @Nonnull List<vSphereCloudSlaveComputer> getAll() {
        ArrayList<vSphereCloudSlaveComputer> out = new ArrayList<>();
        for (final Computer c : Jenkins.get().getComputers()) {
            if (!(c instanceof vSphereCloudSlaveComputer)) continue;
            out.add((vSphereCloudSlaveComputer) c);
        }
        return out;
    }

    /** 10 seconds */
    private static final long NANOSECONDS_TO_CACHE_VMINFORMATION = 10L * 1000000000L;

    /**
     * Gets the VMInformation and caches it for a short while to avoid spamming
     * vSphere.
     */
    private synchronized VMInformation getVMInformation() {
        final long systemUptimeNow = System.nanoTime();
        final boolean shouldFetchData;
        if (vmInformation == null) {
            shouldFetchData = true;
        } else {
            final long nanosecondsSinceLastFetch = systemUptimeNow - vmInformation.systemUptimeWhenDataWasRead;
            shouldFetchData = nanosecondsSinceLastFetch > NANOSECONDS_TO_CACHE_VMINFORMATION;
        }
        if (shouldFetchData) {
            try {
                final String ourVmName = vSlave.getVmName();
                final vSphereCloud ourCloud = vSlave.findOurVsInstance();
                final VSphere vSphereInstance = ourCloud.vSphereInstance();
                final VirtualMachine ourVm = vSphereInstance.getVmByName(ourVmName);
                vmInformation = new VMInformation(systemUptimeNow, ourVm);
            } catch (Throwable e) {
                vmInformation = new VMInformation(systemUptimeNow, e);
            }
        }
        return vmInformation;
    }

    /** Cacheable information about the vSphere VM */
    private static class VMInformation {
        public final long systemUptimeWhenDataWasRead;
        public final String errorEncounteredWhenDataWasRead;
        public final String annotation;
        public final String folder;
        public final String guestName;
        public final String guestToolsStatus;
        public final int memoryMB;
        public final int numCPU;
        public final int uptimeSeconds;

        /** Populates instance from given VM. */
        public VMInformation(long systemUptimeNow, VirtualMachine vm) {
            final VirtualMachineConfigInfo vmConfig = vm == null ? null : vm.getConfig();
            final VirtualHardware vmHardware = vmConfig == null ? null : vmConfig.getHardware();
            final VirtualMachineSummary vmSummary = vm == null ? null : vm.getSummary();
            final VirtualMachineGuestSummary vmGuest = vmSummary == null ? null : vmSummary.getGuest();
            final VirtualMachineToolsStatus vmToolsStatus = vmGuest == null ? null : vmGuest.getToolsStatus();
            final VirtualMachineQuickStats vmStats = vmSummary == null ? null : vmSummary.getQuickStats();
            systemUptimeWhenDataWasRead = systemUptimeNow;
            errorEncounteredWhenDataWasRead = null;
            annotation = nullIfEmpty(vmConfig == null ? null : vmConfig.getAnnotation());
            memoryMB = vmHardware == null ? 0 : vmHardware.getMemoryMB();
            numCPU = vmHardware == null ? 0 : vmHardware.getNumCPU();
            String path = "";
            final ManagedEntity parentFolder = vm == null ? null : vm.getParent();
            for (ManagedEntity me = parentFolder; me != null; me = me.getParent()) {
                path = me.getName() + '/' + path;
            }
            folder = nullIfEmpty(path);
            guestToolsStatus = nullIfEmpty(vmToolsStatus == null ? null : vmToolsStatus.toString());
            guestName = nullIfEmpty(vmGuest == null ? null : vmGuest.getGuestFullName());
            final Integer vmUptimeSeconds = vmStats == null ? null : vmStats.getUptimeSeconds();
            uptimeSeconds = vmUptimeSeconds == null ? 0 : vmUptimeSeconds.intValue();
        }

        /** Populates instance with given exception only. */
        public VMInformation(long systemUptimeNow, Throwable ex) {
            annotation = folder = guestToolsStatus = guestName = null;
            numCPU = memoryMB = uptimeSeconds = 0;
            systemUptimeWhenDataWasRead = systemUptimeNow;
            final StringWriter s = new StringWriter();
            final PrintWriter pw = new PrintWriter(s);
            ex.printStackTrace(pw);
            pw.flush();
            errorEncounteredWhenDataWasRead = s.toString();
        }

        private static String nullIfEmpty(final String s) {
            if (s == null || s.trim().isEmpty()) {
                return null;
            }
            return s;
        }
    }
}