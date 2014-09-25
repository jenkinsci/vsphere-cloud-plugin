/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import hudson.slaves.SlaveComputer;
import hudson.model.Slave;
import java.util.concurrent.Future;

/**
 *
 * @author Admin
 */
public class vSphereCloudSlaveComputer extends SlaveComputer {
    private transient vSphereCloudSlave vSlave;

    public vSphereCloudSlaveComputer(Slave slave) {
        super(slave);
        vSlave = (vSphereCloudSlave)slave;
    }

    @Override
    protected Future<?> _connect(boolean forceReconnect) {
        return super._connect(forceReconnect);
    }

    @Override
    public boolean isAcceptingTasks() {
        return super.isAcceptingTasks();
    }

    @Override
    public boolean isConnecting() {
        return (vSlave.slaveIsStarting == Boolean.TRUE) || super.isConnecting();
    }
}
