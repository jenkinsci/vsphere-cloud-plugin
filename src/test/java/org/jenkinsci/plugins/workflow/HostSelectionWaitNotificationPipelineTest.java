package org.jenkinsci.plugins.workflow;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import hudson.Extension;
import hudson.model.Result;
import java.util.Collections;
import java.util.Set;
import org.jenkinsci.plugins.vsphere.tools.HostSelectionOptions;
import org.jenkinsci.plugins.vsphere.tools.HostSelectionWaitNotification;
import org.jenkinsci.plugins.vsphere.tools.HostSelectionWaitReason;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Pins down what a pipeline's {@code hostSelectionWaitNotification} can be: a closure written in the script
 * (CPS) cannot be called from the Java thread a synchronous non-blocking step runs in, which is where the
 * vSphere step does its work, while a plain Groovy one can.
 */
@WithJenkins
class HostSelectionWaitNotificationPipelineTest {

    public static class CallbackStep extends Step {
        private Object callback;

        @DataBoundConstructor
        public CallbackStep() {}

        @DataBoundSetter
        public void setCallback(Object callback) {
            this.callback = callback;
        }

        @Override
        public StepExecution start(StepContext context) {
            return new Exec(context, callback);
        }

        static class Exec extends SynchronousNonBlockingStepExecution<String> {
            private static final long serialVersionUID = 1;
            private final transient Object cb;

            Exec(StepContext context, Object cb) {
                super(context);
                this.cb = cb;
            }

            @Override
            protected String run() throws Exception {
                HostSelectionOptions.Listener l = HostSelectionWaitNotification.of(cb);
                l.hostSelectionWaiting("message from java", HostSelectionWaitReason.NO_USABLE_HOSTS);
                return "called";
            }
        }

        @Extension
        public static class DescriptorImpl extends StepDescriptor {
            @Override
            public Set<? extends Class<?>> getRequiredContext() {
                return Collections.emptySet();
            }

            @Override
            public String getFunctionName() {
                return "callbackStep";
            }
        }
    }

    /** What the closure from {@code @NonCPS} code recorded. */
    public static final java.util.List<String> SEEN = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Test
    void aPipelineClosureIsRejectedUpFrontWithAnExplanation(JenkinsRule r) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "cps");
        job.setDefinition(new CpsFlowDefinition(
                "callbackStep(callback: { msg, reason -> echo \"CLOSURE GOT: ${msg}\" })\n", true));
        WorkflowRun run = r.waitForCompletion(job.scheduleBuild2(0).get());
        r.assertBuildStatus(Result.FAILURE, run);
        r.assertLogContains("is a Pipeline (CPS) closure", run);
        r.assertLogContains("@NonCPS", run);
        r.assertLogNotContains("CLOSURE GOT", run);
    }

    @Test
    void aNonCpsClosureIsCalledWithTheMessageAndTheReason(JenkinsRule r) throws Exception {
        SEEN.clear();
        WorkflowJob job = r.createProject(WorkflowJob.class, "noncps");
        job.setDefinition(new CpsFlowDefinition(
                "@NonCPS def notifier() {\n"
                        + "  return { msg, reason ->\n"
                        + "    org.jenkinsci.plugins.workflow.HostSelectionWaitNotificationPipelineTest.SEEN.add(msg + ' | ' + reason + ' | ' + reason.isTransient())\n"
                        + "  }\n"
                        + "}\n"
                        + "callbackStep(callback: notifier())\n",
                false));
        r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        assertThat(SEEN.toString(), containsString("message from java | NO_USABLE_HOSTS | false"));
    }
}
