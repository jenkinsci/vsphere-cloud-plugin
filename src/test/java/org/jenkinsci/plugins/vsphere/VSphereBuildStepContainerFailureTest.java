package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** JENKINS-38472: a vSphere build step that fails must fail the build, in freestyle jobs too. */
@WithJenkins
class VSphereBuildStepContainerFailureTest {

    /** Fails the way the real steps do: by exception in the Run flavour, by false in the AbstractBuild one. */
    private static final class FailingStep extends VSphereBuildStep {
        boolean runFlavourCalled;

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            // what the old code path looked at: the real steps log the exception and return false here
            return false;
        }

        @Override
        public void perform(
                @NonNull Run<?, ?> run,
                @NonNull FilePath filePath,
                @NonNull Launcher launcher,
                @NonNull TaskListener listener)
                throws IOException {
            runFlavourCalled = true;
            throw new AbortException("no images match the specified names");
        }
    }

    private static final class QuietStep extends VSphereBuildStep {
        boolean runFlavourCalled;

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            return true;
        }

        @Override
        public void perform(
                @NonNull Run<?, ?> run,
                @NonNull FilePath filePath,
                @NonNull Launcher launcher,
                @NonNull TaskListener listener) {
            runFlavourCalled = true;
        }
    }

    private static FreeStyleBuild someFreestyleBuild(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        return r.buildAndAssertSuccess(p);
    }

    @Test
    @Issue("JENKINS-38472")
    void failureOfAStepInAFreestyleBuildIsPropagated(JenkinsRule r) throws Exception {
        FreeStyleBuild build = someFreestyleBuild(r);
        FailingStep step = new FailingStep();

        AbortException e = assertThrows(
                AbortException.class,
                () -> VSphereBuildStepContainer.performStep(
                        step, build, new FilePath(r.jenkins.getRootDir()), null, TaskListener.NULL));

        assertThat(e.getMessage(), containsString("no images match"));
        assertThat(step.runFlavourCalled, is(true));
    }

    @Test
    @Issue("JENKINS-38472")
    void aSuccessfulStepInAFreestyleBuildStillSucceeds(JenkinsRule r) throws Exception {
        FreeStyleBuild build = someFreestyleBuild(r);
        QuietStep step = new QuietStep();

        VSphereBuildStepContainer.performStep(
                step, build, new FilePath(r.jenkins.getRootDir()), null, TaskListener.NULL);

        assertThat(step.runFlavourCalled, is(true));
    }
}
