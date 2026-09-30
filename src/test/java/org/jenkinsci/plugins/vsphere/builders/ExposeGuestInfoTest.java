package org.jenkinsci.plugins.vsphere.builders;

import java.util.HashMap;
import java.util.Map;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@WithJenkins
class ExposeGuestInfoTest {

    @Test
    void waitForIp4IsAKnownDataBoundParameter(JenkinsRule r) {
        DescribableModel<ExposeGuestInfo> model = DescribableModel.of(ExposeGuestInfo.class);
        assertThat(model.getParameter("waitForIp4"), notNullValue());
    }

    @Test
    void waitForIp4DefaultsToFalseWhenOmitted(JenkinsRule r) throws Exception {
        // A pipeline step that omits the flag, or a job configured before the field existed, leaves the
        // Boolean null - which must read as "do not wait" rather than throw.
        Map<String, Object> args = new HashMap<>();
        args.put("vm", "some-vm");
        args.put("envVariablePrefix", "VSPHERE");

        ExposeGuestInfo step = DescribableModel.of(ExposeGuestInfo.class).instantiate(args);

        assertThat(step.getVm(), is("some-vm"));
        assertThat(step.isWaitForIp4(), is(false));
    }

    @Test
    void waitForIp4IsHonouredWhenSet(JenkinsRule r) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("vm", "some-vm");
        args.put("envVariablePrefix", "VSPHERE");
        args.put("waitForIp4", true);

        ExposeGuestInfo step = DescribableModel.of(ExposeGuestInfo.class).instantiate(args);

        assertThat(step.isWaitForIp4(), is(true));
    }
}
