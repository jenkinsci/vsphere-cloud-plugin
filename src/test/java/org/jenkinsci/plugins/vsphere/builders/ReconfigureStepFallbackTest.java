package org.jenkinsci.plugins.vsphere.builders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vmware.vim25.VirtualMachineConfigSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;

@Issue("JENKINS-34001")
class ReconfigureStepFallbackTest {

    /** A step that only records that it was performed, and may offer a more lenient variant of itself. */
    private static final class FakeStep extends ReconfigureStep {
        final String name;
        final ReconfigureStep variant;

        FakeStep(String name, ReconfigureStep variant) {
            this.name = name;
            this.variant = variant;
        }

        @Override
        public ReconfigureStep fallbackVariant() {
            return variant;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            return true;
        }

        @Override
        public void perform(
                @NonNull Run<?, ?> run,
                @NonNull FilePath filePath,
                @NonNull Launcher launcher,
                @NonNull TaskListener listener) {}

        @Override
        public void perform(@NonNull EnvVars env, @NonNull TaskListener listener) {}
    }

    private final List<String> performed = new ArrayList<>();
    private final List<VirtualMachineConfigSpec> submitted = new ArrayList<>();
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(logBytes);

    private ReconfigureStep.StepAction recordingAction() {
        return step -> performed.add(((FakeStep) step).name);
    }

    @Test
    void successOnTheFirstAttemptSubmitsOnceAndOffersNoRetry() throws Exception {
        ReconfigureStep.reconfigureVm(
                null,
                null,
                List.of(new FakeStep("a", new FakeStep("a-lenient", null))),
                recordingAction(),
                submitted::add,
                log);

        assertThat(performed, contains("a"));
        assertThat(submitted.size(), is(1));
    }

    @Test
    void refusedReconfigurationIsRetriedOnceWithTheLenientVariants() throws Exception {
        ReconfigureStep.SpecSubmitter refuseFirst = spec -> {
            submitted.add(spec);
            if (submitted.size() == 1) {
                throw new VSphereException("00:50:56:90:00:01 is not a valid static Ethernet address");
            }
        };

        ReconfigureStep.reconfigureVm(
                null,
                null,
                List.of(new FakeStep("memory", null), new FakeStep("nic", new FakeStep("nic-lenient", null))),
                recordingAction(),
                refuseFirst,
                log);

        // unchanged steps are used again, the one with a variant is replaced by it
        assertThat(performed, contains("memory", "nic", "memory", "nic-lenient"));
        assertThat(submitted.size(), is(2));
        assertThat(submitted.get(0) == submitted.get(1), is(false));
        assertThat(logBytes.toString(), containsString("retrying once"));
        assertThat(logBytes.toString(), containsString("not a valid static Ethernet address"));
    }

    @Test
    void withoutAnyLenientVariantTheOriginalErrorIsThrownAsIs() {
        ReconfigureStep.SpecSubmitter refuse = spec -> {
            submitted.add(spec);
            throw new VSphereException("disk full");
        };

        VSphereException e = assertThrows(
                VSphereException.class,
                () -> ReconfigureStep.reconfigureVm(
                        null, null, List.of(new FakeStep("a", null)), recordingAction(), refuse, log));

        assertThat(e.getMessage(), containsString("disk full"));
        assertThat(e.getMessage(), not(containsString("retry")));
        assertThat(submitted.size(), is(1));
    }

    @Test
    void whenTheRetryFailsTooBothReasonsAreReported() {
        ReconfigureStep.SpecSubmitter refuse = spec -> {
            submitted.add(spec);
            throw new VSphereException("refused #" + submitted.size());
        };

        VSphereException e = assertThrows(
                VSphereException.class,
                () -> ReconfigureStep.reconfigureVm(
                        null,
                        null,
                        List.of(new FakeStep("nic", new FakeStep("nic-lenient", null))),
                        recordingAction(),
                        refuse,
                        log));

        assertThat(submitted.size(), is(2));
        assertThat(e.getMessage(), containsString("refused #1"));
        assertThat(e.getMessage(), containsString("refused #2"));
    }
}
