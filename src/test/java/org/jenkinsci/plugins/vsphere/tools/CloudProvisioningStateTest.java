package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.any;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.collection.IsArrayContainingInOrder.arrayContaining;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.Assert.assertThat;
import hudson.model.Node.Mode;
import hudson.slaves.NodeProperty;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.collection.IsIterableWithSize;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.VSphereGuestInfoProperty;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class CloudProvisioningStateTest {
    private static List<vSphereCloudSlaveTemplate> stubVSphereCloudTemplates;
    private static vSphereCloud stubVSphereCloud;
    private int recordNumber;
    private int nodeNumber;
    private Logger testLogger;
    private List<LogRecord> loggedMessages;

    @BeforeClass
    public static void setupClass() {
        stubVSphereCloudTemplates = new ArrayList<vSphereCloudSlaveTemplate>();
        final VSphereConnectionConfig vsConnectionConfig = new VSphereConnectionConfig("vsHost", false, "credentialsId");
        stubVSphereCloud = new vSphereCloud(vsConnectionConfig, "vsDescription", 0, 0, false, stubVSphereCloudTemplates);
    }

    @Before
    public void setup() {
        stubVSphereCloudTemplates.clear();
        recordNumber = 0;
        nodeNumber = 0;
        loggedMessages = new ArrayList<LogRecord>();
        Logger logger = Logger.getLogger("CloudProvisioningStateTest");
        logger.setLevel(Level.ALL);
        final Handler[] handlers = logger.getHandlers();
        for (final Handler handler : handlers) {
            logger.removeHandler(handler);
        }
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
    public void constructorGivenCalledThenLogsConstructions() {
        // Given
        final Object[] expectedArgs = { stubVSphereCloud.toString() };

        // When
        createInstance();

        // Then
        assertThat(loggedMessages, contains(logMessage(Level.FINE, expectedArgs)));
    }

    @Test
    public void provisioningStartedGivenNoPreviousStateThenLogs() {
        // Given
        final String nodeName = createNodeName();
        final Object[] expectedArgs = { nodeName };
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord provisionable = createRecord(instance);
        wipeLog();

        // When
        instance.provisioningStarted(provisionable, nodeName);

        // Then
        assertThat(loggedMessages, contains(logMessage(Level.FINE, expectedArgs)));
    }

    @Test
    public void provisioningStartedGivenPreviouslyStartedThenWarns() {
        // Given
        final String nodeName = createNodeName();
        final Object[] expectedArgs = { nodeName };
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord provisionable = createRecord(instance);
        instance.provisioningStarted(provisionable, nodeName);
        wipeLog();

        // When
        instance.provisioningStarted(provisionable, nodeName);

        // Then
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));
    }

    @Test
    public void normalLifecycleGivenNoErrorsThenLogs() {
        // Given
        final String nodeName = createNodeName();
        final Object[] expectedArgs = { nodeName };
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord provisionable = createRecord(instance);
        wipeLog();

        // When
        instance.provisioningStarted(provisionable, nodeName);
        instance.provisionedSlaveNowActive(provisionable, nodeName);
        instance.provisionedSlaveNowUnwanted(nodeName, true);
        instance.unwantedSlaveNowDeleted(nodeName);

        // Then
        assertThat(loggedMessages, everyItem(logMessage(Level.FINE, expectedArgs)));
        assertThat(loggedMessages, IsIterableWithSize.<LogRecord> iterableWithSize(4));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void failedToProvisionGivenNothingOutOfSequenceThenLogs() {
        // Given
        final String nodeName = createNodeName();
        final Object[] expectedArgs = { nodeName };
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord provisionable = createRecord(instance);
        wipeLog();

        // When
        instance.provisioningStarted(provisionable, nodeName);
        instance.provisioningEndedInError(provisionable, nodeName);

        // Then
        assertThat(
                loggedMessages,
                contains(logMessage(Level.FINE, expectedArgs),
                        logMessage(containsString("failed"), Level.INFO, expectedArgs)));
        assertThat(loggedMessages, IsIterableWithSize.<LogRecord> iterableWithSize(2));
    }

    @Test
    public void provisionGivenOutOfOrderSequenceThenComplains() {
        // Given
        final String nodeName = createNodeName();
        final Object[] expectedArgs = { nodeName };
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord provisionable = createRecord(instance);
        wipeLog();

        // When/Then
        wipeLog();
        instance.unwantedSlaveNowDeleted(nodeName);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));

        wipeLog();
        instance.provisionedSlaveNowUnwanted(nodeName, false);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));

        wipeLog();
        instance.provisioningStarted(provisionable, nodeName);
        assertThat(loggedMessages, contains(logMessage(Level.FINE, expectedArgs)));

        wipeLog();
        instance.provisioningStarted(provisionable, nodeName);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));

        wipeLog();
        instance.provisioningEndedInError(provisionable, nodeName);
        assertThat(loggedMessages, contains(logMessage(Level.INFO, expectedArgs)));

        wipeLog();
        instance.provisioningEndedInError(provisionable, nodeName);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));

        wipeLog();
        instance.provisionedSlaveNowActive(provisionable, nodeName);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));

        wipeLog();
        instance.provisionedSlaveNowActive(provisionable, nodeName);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));

        wipeLog();
        instance.provisioningStarted(provisionable, nodeName);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));

        wipeLog();
        instance.provisioningStarted(provisionable, nodeName);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));

        wipeLog();
        instance.provisionedSlaveNowUnwanted(nodeName, false);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));

        wipeLog();
        instance.provisionedSlaveNowUnwanted(nodeName, true);
        assertThat(loggedMessages, contains(logMessage(Level.WARNING, expectedArgs)));
    }

    @Test
    public void pruneUnwantedRecordsGivenUnknownTemplatesThenRemovesRecordsForEmptyDeletedTemplates() {
        // Given
        final String deletedAndInactiveNodeName = createNodeName();
        final String deletedButActiveNodeName = createNodeName();
        final String livedAndDiedNodeName = createNodeName();
        final CloudProvisioningState instance = createInstance();
        // A template which the user deleted but still has an active slave
        final CloudProvisioningRecord deletedButActiveRecord = createRecord(instance);
        instance.provisioningStarted(deletedButActiveRecord, deletedButActiveNodeName);
        instance.provisionedSlaveNowActive(deletedButActiveRecord, deletedButActiveNodeName);
        // A template which the user deleted and is no longer needed
        final CloudProvisioningRecord deletedAndInactiveRecord = createRecord(instance);
        instance.provisioningStarted(deletedAndInactiveRecord, deletedAndInactiveNodeName);
        instance.provisionedSlaveNowActive(deletedAndInactiveRecord, deletedAndInactiveNodeName);
        final vSphereCloudSlaveTemplate deletedAndInactiveTemplate = deletedAndInactiveRecord.getTemplate();
        instance.provisionedSlaveNowUnwanted(deletedAndInactiveNodeName, true);
        instance.unwantedSlaveNowDeleted(deletedAndInactiveNodeName);
        // A template which is current but has no active slaves right now
        final CloudProvisioningRecord activeRecord = createRecord(instance);
        instance.provisioningStarted(activeRecord, livedAndDiedNodeName);
        instance.provisionedSlaveNowActive(activeRecord, livedAndDiedNodeName);
        instance.provisionedSlaveNowUnwanted(livedAndDiedNodeName, true);
        instance.unwantedSlaveNowDeleted(livedAndDiedNodeName);

        // When
        userHasDeletedSlaveTemplate(deletedButActiveRecord);
        userHasDeletedSlaveTemplate(deletedAndInactiveRecord);
        wipeLog();
        instance.pruneUnwantedRecords();

        // Then
        assertThat(
                loggedMessages,
                contains(logMessage(containsString("Disposing"), Level.FINE, deletedAndInactiveTemplate.getCloneNamePrefix(), deletedAndInactiveTemplate.toString())));
    }

    @Test
    public void countNodesGivenNoTemplatesOrSlavesThenReturnsZero() {
        // Given
        final CloudProvisioningState instance = createInstance();

        // When
        final int actual = instance.countNodes();

        // Then
        assertThat(actual, equalTo(0));
    }

    @Test
    public void countNodesGivenNoSlavesInAnyTemplatesThenReturnsZero() {
        // Given
        final CloudProvisioningState instance = createInstance();
        createRecord(instance);
        final String node = createNodeName();
        final CloudProvisioningRecord previouslyActiveRecord = createRecord(instance);
        previouslyActiveRecord.addCurrentlyActive(node);
        previouslyActiveRecord.removeCurrentlyActive(node);
        createRecord(instance);

        // When
        final int actual = instance.countNodes();

        // Then
        assertThat(actual, equalTo(0));
    }

    @Test
    public void countNodesGiven2ActiveSlavesThenReturns2() {
        // Given
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord activeRecord = createRecord(instance);
        activeRecord.addCurrentlyActive(createNodeName());
        activeRecord.addCurrentlyActive(createNodeName());

        // When
        final int actual = instance.countNodes();

        // Then
        assertThat(actual, equalTo(2));
    }

    @Test
    public void countNodesGiven2UnwantedSlavesThenReturns2() {
        // Given
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord activeRecord = createRecord(instance);
        activeRecord.setCurrentlyUnwanted(createNodeName(), false);
        activeRecord.setCurrentlyUnwanted(createNodeName(), false);

        // When
        final int actual = instance.countNodes();

        // Then
        assertThat(actual, equalTo(2));
    }

    @Test
    public void countNodesGiven3ActiveAnd4PendingSlavesThenReturns7() {
        // Given
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord recordWith2Active = createRecord(instance);
        recordWith2Active.addCurrentlyActive(createNodeName());
        recordWith2Active.addCurrentlyActive(createNodeName());
        final CloudProvisioningRecord recordWith1Active4Planned = createRecord(instance);
        recordWith1Active4Planned.addCurrentlyActive(createNodeName());
        recordWith1Active4Planned.addCurrentlyPlanned(createNodeName());
        recordWith1Active4Planned.addCurrentlyPlanned(createNodeName());
        recordWith1Active4Planned.addCurrentlyPlanned(createNodeName());
        recordWith1Active4Planned.addCurrentlyPlanned(createNodeName());

        // When
        final int actual = instance.countNodes();

        // Then
        assertThat(actual, equalTo(7));
    }

    @Test
    public void getUnwantedVMsThatNeedDeletingGivenNothingNeedsDeletingThenReturnsNothing() {
        // Given
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord recordWith2Active = createRecord(instance);
        recordWith2Active.addCurrentlyActive(createNodeName());
        recordWith2Active.addCurrentlyActive(createNodeName());
        final CloudProvisioningRecord recordWith1Active1Planned = createRecord(instance);
        recordWith1Active1Planned.addCurrentlyActive(createNodeName());
        recordWith1Active1Planned.addCurrentlyPlanned(createNodeName());
        final List<String> expected = Arrays.asList();

        // When
        final List<String> actual = instance.getUnwantedVMsThatNeedDeleting();
        // Then
        assertThat(actual, equalTo(expected));
    }

    @Test
    public void getUnwantedVMsThatNeedDeletingGivenSeveralUnwantedThenReturnsDeletableVMsInCorrectOrder() {
        // Given
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord r1 = createRecord(instance);
        final String r1unwanted1 = createNodeName();
        final String r1unwanted2alreadyBeingDeleted = createNodeName();
        final String r1unwanted3 = createNodeName();
        r1.setCurrentlyUnwanted(r1unwanted1, false);
        r1.setCurrentlyUnwanted(r1unwanted2alreadyBeingDeleted, true);
        r1.setCurrentlyUnwanted(r1unwanted3, false);
        final CloudProvisioningRecord r2 = createRecord(instance);
        final String r2unwanted1alreadyBeingDeleted = createNodeName();
        final String r2unwanted2 = createNodeName();
        r2.setCurrentlyUnwanted(r2unwanted1alreadyBeingDeleted, true);
        r2.setCurrentlyUnwanted(r2unwanted2, false);
        // Expect to get the first not-being-deleted record from both before the
        // second not-being-deleted record. However we don't mind the order that
        // the records are processed, so there are two correct answers.
        final List<String> expectedA = Arrays.asList(r1unwanted1, r2unwanted2, r1unwanted3);
        final List<String> expectedB = Arrays.asList(r2unwanted2, r1unwanted1, r1unwanted3);

        // When
        final List<String> actual = instance.getUnwantedVMsThatNeedDeleting();
        // Then
        assertThat(actual, either(equalTo(expectedA)).or(equalTo(expectedB)));
    }

    @Test
    public void unwantedSlaveNowDeletedGivenNothingElseToDeleteThenNothingRemains() {
        // Given
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord r1 = createRecord(instance);
        final String r1unwanted1 = createNodeName();
        final vSphereCloudSlaveTemplate r1t = r1.getTemplate();
        instance.recordExistingUnwantedVM(r1t, r1unwanted1);
        final int numberOfNodesBeforeDeletion = instance.countNodes();
        assertThat(numberOfNodesBeforeDeletion, equalTo(1));
        final List<String> noNodes = Arrays.asList();

        // When
        final Boolean actualIsOkToDelete = instance.isOkToDeleteUnwantedVM(r1unwanted1);
        instance.unwantedSlaveNowDeleted(r1unwanted1);
        final int actualNodesAfterDeletion = instance.countNodes();
        final List<String> actualNodesToBeDeletedNext = instance.getUnwantedVMsThatNeedDeleting();

        // Then
        assertThat(actualIsOkToDelete, equalTo(Boolean.TRUE));
        assertThat(actualNodesAfterDeletion, equalTo(0));
        assertThat(actualNodesToBeDeletedNext, equalTo(noNodes));
    }

    @Test
    public void unwantedSlaveNotDeletedGivenNothingElseToDeleteThenFailedDeletionRemains() {
        // Given
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord r1 = createRecord(instance);
        final String r1unwanted1 = createNodeName();
        final vSphereCloudSlaveTemplate r1t = r1.getTemplate();
        instance.recordExistingUnwantedVM(r1t, r1unwanted1);
        final int numberOfNodesBeforeDeletion = instance.countNodes();
        assertThat(numberOfNodesBeforeDeletion, equalTo(1));
        final List<String> sameNodeRemains = Arrays.asList(r1unwanted1);

        // When
        final Boolean actualIsOkToDelete = instance.isOkToDeleteUnwantedVM(r1unwanted1);
        instance.unwantedSlaveNotDeleted(r1unwanted1);
        final int actualNodesAfterDeletion = instance.countNodes();
        final List<String> actualNodesToBeDeletedNext = instance.getUnwantedVMsThatNeedDeleting();

        // Then
        assertThat(actualIsOkToDelete, equalTo(Boolean.TRUE));
        assertThat(actualNodesAfterDeletion, equalTo(1));
        assertThat(actualNodesToBeDeletedNext, equalTo(sameNodeRemains));
    }

    @Test
    public void isOkToDeleteUnwantedVMGivenNobodyElseDeletingVMThenReturnsTrueOnceAndFalseThereafter() {
        // Given
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord r1 = createRecord(instance);
        final String r1unwanted1 = createNodeName();
        final vSphereCloudSlaveTemplate r1t = r1.getTemplate();
        instance.recordExistingUnwantedVM(r1t, r1unwanted1);

        // When
        final Boolean actual1 = instance.isOkToDeleteUnwantedVM(r1unwanted1);
        final Boolean actual2 = instance.isOkToDeleteUnwantedVM(r1unwanted1);
        final Boolean actual3 = instance.isOkToDeleteUnwantedVM(r1unwanted1);

        // Then
        assertThat(actual1, equalTo(Boolean.TRUE));
        assertThat(actual2, equalTo(Boolean.FALSE));
        assertThat(actual3, equalTo(Boolean.FALSE));
    }

    @Test
    public void isOkToDeleteUnwantedVMGivenDeletionInProgressVMThenReturnsFalseUntilDeletionFails() {
        // Given
        final CloudProvisioningState instance = createInstance();
        final CloudProvisioningRecord r1 = createRecord(instance);
        final String r1unwanted1 = createNodeName();
        r1.setCurrentlyUnwanted(r1unwanted1, true);

        // When
        final Boolean actualBefore = instance.isOkToDeleteUnwantedVM(r1unwanted1);
        instance.unwantedSlaveNotDeleted(r1unwanted1);
        final Boolean actualAfter = instance.isOkToDeleteUnwantedVM(r1unwanted1);

        // Then
        assertThat(actualBefore, equalTo(Boolean.FALSE));
        assertThat(actualAfter, equalTo(Boolean.TRUE));
    }

    private void wipeLog() {
        loggedMessages.clear();
    }

    private CloudProvisioningState createInstance() {
        return new CloudProvisioningState(stubVSphereCloud, testLogger);
    }

    private CloudProvisioningRecord createRecord(CloudProvisioningState instance) {
        recordNumber++;
        final String cloneNamePrefix = "prefix" + recordNumber;
        final vSphereCloudSlaveTemplate template = new vSphereCloudSlaveTemplate(cloneNamePrefix, "masterImageName",
                null, "snapshotName", false, "cluster", "resourcePool", "datastore", "folder", "customizationSpec", "templateDescription", 0, 1, "remoteFS",
                "", Mode.NORMAL, false, false, 0, 0, false, "targetResourcePool", "targetHost", null,
                new JNLPLauncher(), RetentionStrategy.NOOP, Collections.<NodeProperty<?>> emptyList(),
                Collections.<VSphereGuestInfoProperty> emptyList());
        stubVSphereCloudTemplates.add(template);
        final List<vSphereCloudSlaveTemplate> templates = new ArrayList<vSphereCloudSlaveTemplate>();
        templates.add(template);
        final List<CloudProvisioningRecord> records = instance.calculateProvisionableTemplates(templates);
        assertThat(records, IsIterableWithSize.<CloudProvisioningRecord> iterableWithSize(1));
        final CloudProvisioningRecord record = records.get(0);
        return record;
    }

    private String createNodeName() {
        nodeNumber++;
        final String nodeName = "N#" + nodeNumber;
        return nodeName;
    }

    private void userHasDeletedSlaveTemplate(CloudProvisioningRecord record) {
        stubVSphereCloudTemplates.remove(record.getTemplate());
    }

    private static Matcher<LogRecord> logMessage(final Level expectedLevel, final Object... expectedArgs) {
        final List<Matcher<? super String>> messageMatchers = new ArrayList<Matcher<? super String>>(
                expectedArgs.length);
        for (int i = 0; i < expectedArgs.length; i++) {
            final String expectedString = "{" + i + "}";
            messageMatchers.add(containsString(expectedString));
        }
        final Matcher<String> messageMatcher;
        if (messageMatchers.isEmpty()) {
            messageMatcher = any(String.class);
        } else {
            messageMatcher = allOf(messageMatchers);
        }
        return logMessage(messageMatcher, expectedLevel, expectedArgs);
    }

    private static Matcher<LogRecord> logMessage(final Matcher<String> messageMatcher, final Level expectedLevel,
            final Object... expectedArgs) {
        final Matcher<Level> levelMatcher = equalTo(expectedLevel);
        final Matcher<Object[]> parametersMatcher = arrayContaining(expectedArgs);
        final Matcher<LogRecord> itemMatcher = new TypeSafeMatcher<LogRecord>(LogRecord.class) {
            @Override
            public boolean matchesSafely(LogRecord actual) {
                final String actualMessage = actual.getMessage();
                final Level actualLevel = actual.getLevel();
                final Object[] actualParameters = actual.getParameters();
                return messageMatcher.matches(actualMessage) && levelMatcher.matches(actualLevel)
                        && parametersMatcher.matches(actualParameters);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("LogRecord(");
                description.appendText("message ").appendDescriptionOf(messageMatcher);
                description.appendText(" && level ").appendDescriptionOf(levelMatcher);
                description.appendText(" && parameters ").appendDescriptionOf(parametersMatcher);
                description.appendText(")");
            }

            @Override
            protected void describeMismatchSafely(LogRecord actual, Description description) {
                final String actualMessage = actual.getMessage();
                final Level actualLevel = actual.getLevel();
                final Object[] actualParameters = actual.getParameters();
                description.appendText("was LogRecord(");
                description.appendText("message=\"").appendValue(actualMessage);
                description.appendText("\", level ").appendValue(actualLevel);
                description.appendText(", parameters ").appendValueList("[", ",", "]", actualParameters);
                description.appendText(")");
            }
        };
        return itemMatcher;
    }
}
