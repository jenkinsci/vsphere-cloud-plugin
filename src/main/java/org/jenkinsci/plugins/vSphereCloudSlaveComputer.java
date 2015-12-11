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
    private final vSphereCloudSlave vSlave;

    public vSphereCloudSlaveComputer(Slave slave) {
        super(slave);
        vSlave = (vSphereCloudSlave)slave;
    }

    @Override
    public boolean isConnecting() {
        return (vSlave.slaveIsStarting == Boolean.TRUE) || super.isConnecting();
    }
}