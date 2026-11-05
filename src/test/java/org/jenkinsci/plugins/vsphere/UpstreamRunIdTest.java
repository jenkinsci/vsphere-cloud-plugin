package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * {@link UsageGate} puts together the identifier of the run that a cause points to, instead of asking the run for it.
 * This is what would fail if Jenkins changed the format of {@link Run#getExternalizableId}, which the registry counts
 * runs by.
 */
@WithJenkins
class UpstreamRunIdTest {

    private static void assertSameAsTheRunsOwn(Run<?, ?> run) {
        final String id = UsageGate.upstreamRunId(new Cause.UpstreamCause(run));

        assertThat(id, is(run.getExternalizableId()));
        assertThat(Run.fromExternalizableId(id), sameInstance(run));
    }

    @Test
    void aJobAtTheTopLevel(JenkinsRule r) throws Exception {
        final FreeStyleProject job = r.createFreeStyleProject("top");

        assertSameAsTheRunsOwn(r.buildAndAssertSuccess(job));
    }

    @Test
    void aJobTwoFoldersDown(JenkinsRule r) throws Exception {
        final MockFolder outer = r.createFolder("outer folder");
        final MockFolder inner = outer.createProject(MockFolder.class, "inner");
        final FreeStyleProject job = inner.createProject(FreeStyleProject.class, "job");

        assertSameAsTheRunsOwn(r.buildAndAssertSuccess(job));
    }

    @Test
    void aPipelineInAFolder(JenkinsRule r) throws Exception {
        final MockFolder folder = r.createFolder("folder");
        final WorkflowJob job = folder.createProject(WorkflowJob.class, "pipeline");
        job.setDefinition(new CpsFlowDefinition("echo 'built'", true));

        assertSameAsTheRunsOwn(r.buildAndAssertSuccess(job));
    }
}
