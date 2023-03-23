package org.jenkinsci.plugins;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;
import org.jenkinsci.plugins.vSphereCloud.VSpherePlannedNode;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.tools.CloudProvisioningAlgorithm;
import org.jenkinsci.plugins.vsphere.tools.CloudProvisioningRecord;
import org.jenkinsci.plugins.vsphere.tools.CloudProvisioningState;

import hudson.model.Label;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.NodeProvisioner.PlannedNode;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class NodePreProvisionTest {
    /** Used when faking up test data */
    private static List<vSphereCloudSlaveTemplate> stubVSphereCloudTemplates;
    private static VSphereConnectionConfig vsConnectionConfig;
    private static vSphereCloud stubVSphereCloud;
    private static CloudProvisioningState stubVSphereTemplateState;
    private Logger testLogger;
    private List<LogRecord> loggedMessages;

    @BeforeClass
    public static void setupClass() {
        stubVSphereCloudTemplates = new ArrayList<vSphereCloudSlaveTemplate>();
        vsConnectionConfig = new VSphereConnectionConfig("vsHost", false, "credentialsId");
        stubVSphereCloud = new vSphereCloud(vsConnectionConfig, "vsDescription", 100, 100, stubVSphereCloudTemplates);
        stubVSphereTemplateState = new CloudProvisioningState(stubVSphereCloud);
    }

    @Before
    public void setup() {
        stubVSphereCloudTemplates.clear();
        loggedMessages = new ArrayList<LogRecord>();
        // Get vSphereCloud logger
        Logger logger = Logger.getLogger("vsphere-cloud");
        logger.setLevel(Level.ALL);
        // final Handler[] handlers = logger.getHandlers();
        // for (final Handler handler : handlers) {
        //     logger.removeHandler(handler);
        // }
        final Handler testHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                loggedMessages.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(testHandler);
        testLogger = logger;
    }

    @Test 
    public void shouldPreProvisionNodesWhenNotEnough() {
        vSphereCloudSlaveTemplate template = createTemplate(10, 2);
        provisionNode(template);
        assertThat(stubVSphereTemplateState.countNodes(), equalTo(1));

        // Here it says that there still should be provisioned 2 nodes, despite the fact there is 1 active already
        stubVSphereCloud.preProvisionNodes(template);

        // Below is a draft line
        //assertThat(loggedMessages.get(1).getMessage(), equalTo("should pre-provision 1 node"));

    }

    private vSphereCloudSlaveTemplate createTemplate(int templateCapacity, int instanceMin){
        return stubTemplate("templateCapacity" + templateCapacity + "instanceMin" + instanceMin, templateCapacity, instanceMin);
    }
    
    private void provisionNode(vSphereCloudSlaveTemplate template) {
        CloudProvisioningRecord provisionable = stubVSphereTemplateState.getOrCreateRecord(template);
        final String nodeName = CloudProvisioningAlgorithm.findUnusedName(provisionable);
        stubVSphereTemplateState.provisionedSlaveNowActive(provisionable, nodeName);
        // Below doesn't work either
        //VSpherePlannedNode.createInstance(stubVSphereTemplateState, nodeName, provisionable);
    }

    private static vSphereCloudSlaveTemplate stubTemplate(String prefix, int templateInstanceCap, int instanceMin) {
        return new vSphereCloudSlaveTemplate(prefix, "", null, null, false, null, null, null, null, null, null, templateInstanceCap, 1,
                null, null, null, false, false, 0, 0, false, null, null, instanceMin, null, new JNLPLauncher(),
                RetentionStrategy.NOOP, null, null);
    }
}
