/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import hudson.slaves.SlaveComputer;
import hudson.model.Slave;

/**
 *
 * @author Admin
 */
public class vSphereCloudSlaveComputer extends SlaveComputer {
    public vSphereCloudSlaveComputer(Slave slave) {
        super(slave);
    }
}
