package org.jenkinsci.plugins.vsphere.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostFolderPathTest {

    private static final List<String> PATH = Arrays.asList("DC", "virt5x", "kubevms");

    @Test
    void replacesTheHostElement() {
        assertEquals(Arrays.asList("DC", "virt4x", "kubevms"), HostFolderPath.rewrite(PATH, "virt5x", "virt4x"));
    }

    @Test
    void matchesShortNameOfFullHostAndKeepsTheForm() {
        assertEquals(
                Arrays.asList("DC", "virt4x", "kubevms"),
                HostFolderPath.rewrite(PATH, "virt5x.example.com", "virt4x.example.com"));
        assertEquals(
                Arrays.asList("DC", "virt4x.example.com", "kubevms"),
                HostFolderPath.rewrite(
                        Arrays.asList("DC", "virt5x.example.com", "kubevms"), "virt5x", "virt4x.example.com"));
    }

    @Test
    void ignoresCase() {
        assertEquals(Arrays.asList("DC", "virt4x", "kubevms"), HostFolderPath.rewrite(PATH, "VIRT5X", "virt4x"));
    }

    @Test
    void noHostElementMeansNoRewrite() {
        assertNull(HostFolderPath.rewrite(PATH, "virt6x", "virt4x"));
        assertNull(HostFolderPath.rewrite(PATH, null, "virt4x"));
        assertNull(HostFolderPath.rewrite(PATH, "virt5x", ""));
    }

    @Test
    void ipAddressesAreNotShortened() {
        assertEquals(
                Arrays.asList("DC", "10.0.0.2", "x"),
                HostFolderPath.rewrite(Arrays.asList("DC", "10.0.0.1", "x"), "10.0.0.1", "10.0.0.2"));
        assertNull(HostFolderPath.rewrite(Arrays.asList("DC", "10", "x"), "10.0.0.1", "10.0.0.2"));
    }

    @Test
    void onlyTheFirstMatchIsReplacedAndInputIsKept() {
        final List<String> twice = Arrays.asList("virt5x", "virt5x");
        assertEquals(Arrays.asList("virt4x", "virt5x"), HostFolderPath.rewrite(twice, "virt5x", "virt4x"));
        assertEquals(Arrays.asList("virt5x", "virt5x"), twice);
    }

    @Test
    void optionDefaultsOffAndIsCarriedByCopies() {
        assertFalse(HostSelectionOptions.NONE.isFolderFollowsHost());
        final HostSelectionOptions on = HostSelectionOptions.NONE.withFolderFollowsHost(true);
        assertTrue(on.isFolderFollowsHost());
        assertTrue(on.withVmSize(1, 1L).isFolderFollowsHost());
        assertTrue(on.withWeights(null).isFolderFollowsHost());
    }
}
