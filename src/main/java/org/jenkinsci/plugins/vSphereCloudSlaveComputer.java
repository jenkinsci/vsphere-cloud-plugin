/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
 

/**
 *
 * @author Admin
 */
public class vSphereCloudSlaveComputer extends AbstractCloudComputer {
    private final vSphereCloudSlave vSlave;

    public vSphereCloudSlaveComputer(AbstractCloudSlave slave) {
        super(slave);
        vSlave = (vSphereCloudSlave)slave;
    }

    @Override
    public boolean isConnecting() {
        return (vSlave.slaveIsStarting == Boolean.TRUE) || super.isConnecting();
    }
}