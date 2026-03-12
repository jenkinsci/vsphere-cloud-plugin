package org.jenkinsci.plugins.vsphere.tools;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.CoreMatchers.*;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudProvisioningAlgorithmTest {

    /** Used when faking up test data */
    private int instanceNumber;

    @BeforeEach
    void setup() {
        instanceNumber = 0;
    }

    @Test
    void findTemplateWithMostFreeCapacityGivenNoOptionsThenReturnsNull() {
        // Given
        final List<CloudProvisioningRecord> emptyList = Collections.emptyList();

        // When
        final CloudProvisioningRecord actual = CloudProvisioningAlgorithm.findTemplateWithMostFreeCapacity(emptyList);

        // Then
        assertThat(actual, nullValue());
    }

    @Test
    void findTemplateWithMostFreeCapacityGivenSomeActiveAndOneUnusedThenPrefersUnusedTemplate() {
        // Given
        final CloudProvisioningRecord zeroOfTwo = createInstance(2, 0, 0);
        final CloudProvisioningRecord onePlannedOfTwo = createInstance(2, 0, 1);
        final CloudProvisioningRecord oneExistsOfTwo = createInstance(2, 1, 0);
        final List<CloudProvisioningRecord> forwards = Arrays.asList(zeroOfTwo, onePlannedOfTwo, oneExistsOfTwo);
        final List<CloudProvisioningRecord> reverse = Arrays.asList(oneExistsOfTwo, onePlannedOfTwo, zeroOfTwo);

        // When
        final CloudProvisioningRecord actual1 = CloudProvisioningAlgorithm.findTemplateWithMostFreeCapacity(forwards);
        final CloudProvisioningRecord actual2 = CloudProvisioningAlgorithm.findTemplateWithMostFreeCapacity(reverse);

        // Then
        assertThat(actual1, sameInstance(zeroOfTwo));
        assertThat(actual2, sameInstance(zeroOfTwo));
    }

    @Test
    void findTemplateWithMostFreeCapacityGivenEqualCapsThenDistributesTheLoadEvenly() {
        // Given
        final CloudProvisioningRecord a = createInstance(2, 0, 0);
        final CloudProvisioningRecord b = createInstance(2, 0, 0);
        final CloudProvisioningRecord c = createInstance(2, 0, 0);
        final List<CloudProvisioningRecord> records = Arrays.asList(a, b, c);

        // When/Then
        testScenario(records, a, b, c, a, b, c, null);
    }

    @Test
    void findTemplateWithMostFreeCapacityGivenEqualCapsButExistingUsageThenDistributesTheLoadEvenly() {
        // Given
        final CloudProvisioningRecord a = createInstance(2, 2, 0);
        final CloudProvisioningRecord b = createInstance(2, 1, 0);
        final CloudProvisioningRecord c = createInstance(2, 0, 0);
        final List<CloudProvisioningRecord> records = Arrays.asList(a, b, c);

        // When/Then
        testScenario(records, c, b, c, null);
    }

    @Test
    void findTemplateWithMostFreeCapacityGivenNoCapsThenDistributesTheLoadEvenly() {
        // Given
        final CloudProvisioningRecord a = createInstance(0, 0, 0);
        final CloudProvisioningRecord b = createInstance(0, 0, 0);
        final CloudProvisioningRecord c = createInstance(0, 0, 0);
        final List<CloudProvisioningRecord> records = Arrays.asList(a, b, c);

        // When/Then
        testScenario(records, a, b, c, a, b, c, a, b, c, a);
    }

    @Test
    void findTemplateWithMostFreeCapacityGivenUnequalCapsThenDistributesTheLoadFairly() {
        findTemplateWithMostFreeCapacityGivenUnequalCapsThenDistributesTheLoadFairly(true);
    }

    @Test
    void findTemplateWithMostFreeCapacityGivenUnequalCapsThenDistributesTheLoadFairly2() {
        findTemplateWithMostFreeCapacityGivenUnequalCapsThenDistributesTheLoadFairly(false);
    }

    private void findTemplateWithMostFreeCapacityGivenUnequalCapsThenDistributesTheLoadFairly(boolean forwards) {
        // Given
        final CloudProvisioningRecord capOf2 = createInstance(2, 0, 0);
        final CloudProvisioningRecord capOf5 = createInstance(5, 0, 0);
        final List<CloudProvisioningRecord> records = forwards ? Arrays.asList(capOf2, capOf5) : Arrays.asList(capOf5,
                capOf2);

        // When/Then
        testScenario(records, capOf5, capOf2, capOf5, capOf5, capOf2, capOf5, capOf5, null);
    }

    @Test
    void findTemplateWithMostFreeCapacityGivenOneCappedAndOneUncappedThenDistributesTheLoadEvenlyUntilCapReached() {
        findTemplateWithMostFreeCapacityGivenDifferentCapnessThenDistributesTheLoadEvenlyUntilCapReached(true);
    }

    @Test
    void findTemplateWithMostFreeCapacityGivenOneUncappedAndOneCappedThenDistributesTheLoadEvenlyUntilCapReached() {
        findTemplateWithMostFreeCapacityGivenDifferentCapnessThenDistributesTheLoadEvenlyUntilCapReached(false);
    }

    private void findTemplateWithMostFreeCapacityGivenDifferentCapnessThenDistributesTheLoadEvenlyUntilCapReached(
            boolean forwards) {
        // Given
        final CloudProvisioningRecord capOf2 = createInstance(2, 0, 0);
        final CloudProvisioningRecord uncapped = createInstance(0, 0, 0);
        final List<CloudProvisioningRecord> records = forwards ? Arrays.asList(capOf2, uncapped) : Arrays.asList(
                uncapped, capOf2);

        // When/Then
        testScenario(records, uncapped, capOf2, uncapped, capOf2, uncapped, uncapped, uncapped);
    }

    private static void testScenario(List<CloudProvisioningRecord> records, CloudProvisioningRecord... expectedRecords) {
        // Given records and expected return values
        int i = 0;
        for (final CloudProvisioningRecord expected : expectedRecords) {
            final CloudProvisioningRecord actual = CloudProvisioningAlgorithm.findTemplateWithMostFreeCapacity(records);
            i++;
            assertThat("findTemplateWithMostFreeCapacity(" + records + ")#" + i, actual, sameInstance(expected));
            final String nodeName = "PlannedInStep" + i;
            if (actual != null) {
                actual.addCurrentlyPlanned(nodeName);
            }
        }
    }

    @Test
    void toBigIntegerGivenTwoPow128MinusOneThenReturnsTwoPow128MinusOne() {
        testToBigInteger(-1, -1, "340282366920938463463374607431768211455");
    }

    @Test
    void toBigIntegerGivenTwoPow64PlusOneThenReturnsTwoPow64PlusOne() {
        testToBigInteger(1, 1, "18446744073709551617");
    }

    @Test
    void toBigIntegerGivenZeroThenReturnsZero() {
        testToBigInteger(0, 0, "0");
    }

    @Test
    void toBigIntegerGivenPowersOfTwoThenReturnsPowersOfTwo() {
        long lsb = 1;
        long msb = 0;
        BigInteger big = new BigInteger("1");
        for (int bit = 0; bit < 64; bit++) {
            testToBigInteger(msb, lsb, big.toString());
            lsb <<= 1;
            big = big.add(big);
        }
        msb = 1;
        for (int bit = 0; bit < 64; bit++) {
            testToBigInteger(msb, lsb, big.toString());
            msb <<= 1;
            big = big.add(big);
        }
    }

    private static void testToBigInteger(long msb, long lsb, String expected) {
        // Given
        final BigInteger expectedValue = new BigInteger(expected);
        final byte[] expectedBytes = expectedValue.toByteArray();
        // When
        final BigInteger actual = CloudProvisioningAlgorithm.toBigInteger(msb, lsb);
        final byte[] actualBytes = actual.toByteArray();
        final String scenario = "toBigInteger(" + msb + "," + lsb + ") == " + expected + "\ni.e. "
                + toHexString(actualBytes) + " ~= " + toHexString(expectedBytes);
        // Then
        assertThat(scenario, actual, equalTo(expectedValue));
    }

    @Test
    void findUnusedNameGivenZeroOfTwoExistsThenReturnsOneThenTwo() {
        // Given
        final CloudProvisioningRecord record = createInstance(2, 0, 0);
        final String prefix = record.getTemplate().getCloneNamePrefix();
        final String expected1 = prefix + "1";
        final String expected2 = prefix + "2";

        // When
        final String actual1 = CloudProvisioningAlgorithm.findUnusedName(record);
        record.addCurrentlyActive(actual1);
        final String actual2 = CloudProvisioningAlgorithm.findUnusedName(record);
        record.addCurrentlyActive(actual2);

        // Then
        assertThat(actual1, equalTo(expected1));
        assertThat(actual2, equalTo(expected2));
    }

    @Test
    void findUnusedNameGivenMiddleOfThreeStillExistsThenReturnsOneThenThree() {
        // Given
        final CloudProvisioningRecord record = createInstance(3, 0, 0);
        final String prefix = record.getTemplate().getCloneNamePrefix();
        final String expected1 = prefix + "1";
        final String unwanted = prefix + "2";
        record.setCurrentlyUnwanted(unwanted, false);
        final String expected2 = prefix + "3";

        // When
        final String actual1 = CloudProvisioningAlgorithm.findUnusedName(record);
        record.addCurrentlyActive(actual1);
        final String actual2 = CloudProvisioningAlgorithm.findUnusedName(record);
        record.addCurrentlyActive(actual2);

        // Then
        assertThat(actual1, equalTo(expected1));
        assertThat(actual2, equalTo(expected2));
    }

    @Test
    void findUnusedNameGivenNoSpaceThenThrowsIllegalStateException() {
        // Given
        final CloudProvisioningRecord record = createInstance(3, 0, 0);
        final String prefix = record.getTemplate().getCloneNamePrefix();
        final String unwanted = prefix + "1";
        final String active = prefix + "2";
        final String planned = prefix + "3";
        record.setCurrentlyUnwanted(unwanted, false);
        record.addCurrentlyActive(active);
        record.addCurrentlyPlanned(planned);
        final List<CloudProvisioningRecord> records = List.of(record);
        final CloudProvisioningRecord shouldBeNull = CloudProvisioningAlgorithm.findTemplateWithMostFreeCapacity(records);
        assertThat(shouldBeNull, nullValue());

        // When
        assertThrows(IllegalStateException.class, () -> CloudProvisioningAlgorithm.findUnusedName(record));
    }

    @Test
    void findUnusedNameGivenOneOfTwoHasEndedThenReturnsOne() {
        // Given
        final CloudProvisioningRecord record = createInstance(2, 0, 0);
        final String prefix = record.getTemplate().getCloneNamePrefix();
        final String expected = prefix + "1";

        // When
        final String actual1 = CloudProvisioningAlgorithm.findUnusedName(record);
        record.addCurrentlyActive(actual1);
        final String actual2 = CloudProvisioningAlgorithm.findUnusedName(record);
        record.addCurrentlyActive(actual2);
        record.removeCurrentlyActive(actual1);
        final String actual = CloudProvisioningAlgorithm.findUnusedName(record);
        record.addCurrentlyActive(actual);

        // Then
        assertThat(actual, equalTo(expected));
    }

    @Test
    void findUnusedNameGivenUncappedInstancesThenReturnsUniqueNames() {
        // Given
        final CloudProvisioningRecord record = createInstance(0, 5, 6);
        final String prefix = record.getTemplate().getCloneNamePrefix();
        final List<String> actuals = new ArrayList<>();

        // When
        for (int i = 0; i < 100; i++) {
            final String actual = CloudProvisioningAlgorithm.findUnusedName(record);
            record.addCurrentlyActive(actual);
            actuals.add(actual);
        }

        // Then
        final List<String> uniques = new ArrayList<>(new LinkedHashSet<>(actuals));
        assertThat(actuals, equalTo(uniques));
        assertThat(actuals, everyItem(startsWith(prefix)));
    }

    private CloudProvisioningRecord createInstance(int capacity, int provisioned, int planned) {
        final int iNum = ++instanceNumber;
        final vSphereCloudSlaveTemplate template = stubTemplate(iNum + "cap" + capacity, capacity);
        final CloudProvisioningRecord instance = new CloudProvisioningRecord(template);
        for (int i = 0; i < provisioned; i++) {
            final String nodeName = iNum + "provisioned#" + i;
            instance.addCurrentlyActive(nodeName);
        }
        for (int i = 0; i < planned; i++) {
            final String nodeName = iNum + "planned#" + i;
            instance.addCurrentlyPlanned(nodeName);
        }
        return instance;
    }

    private static vSphereCloudSlaveTemplate stubTemplate(String prefix, int templateInstanceCap) {
        return new vSphereCloudSlaveTemplate(prefix, "", null, null, false, null, null, null, null, null, null, templateInstanceCap, 1,
                null, null, null, false, false, 0, 0, false, null, null, null, new JNLPLauncher(),
                RetentionStrategy.NOOP, null, null);
    }

    private static String toHexString(byte[] bytes) {
        final StringBuilder s = new StringBuilder("0x");
        for (final byte b : bytes) {
            final int highDigit = (b >> 8) & 15;
            final int lowDigit = b & 15;
            s.append(Integer.toString(highDigit, 16));
            s.append(Integer.toString(lowDigit, 16));
        }
        return s.toString();
    }
}
