package org.jenkinsci.plugins.vsphere.builders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.vSphereCloud;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies the optional host-placement fields ({@code host}, {@code hostSelectionMode},
 * {@code hostSelectionCandidates}) are wired up the same way a Groovy pipeline step invocation
 * would bind them (named-parameter {@link DescribableModel#instantiate}), and that
 * omitting them entirely preserves the pre-existing behaviour of this build step.
 */
@WithJenkins
class DeployTest {

    private static Map<String, Object> baseArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("template", "linux-template");
        args.put("clone", "new-vm");
        args.put("linkedClone", false);
        args.put("resourcePool", "Resources");
        args.put("cluster", "my-cluster");
        args.put("datastore", "");
        args.put("folder", "");
        args.put("powerOn", false);
        return args;
    }

    @Test
    void hostPlacementFieldsAreKnownDataBoundParameters() {
        DescribableModel<Deploy> model = DescribableModel.of(Deploy.class);
        assertThat(model.getParameter("host"), notNullValue());
        assertThat(model.getParameter("hostSelectionMode"), notNullValue());
        assertThat(model.getParameter("hostSelectionCandidates"), notNullValue());
        assertThat(model.getParameter("hostSelectionCandidatesAsString"), notNullValue());
    }

    @Test
    void vmSizeIsOptionalAndBoundLikeAPipelineWouldBindIt() throws Exception {
        Deploy omitted = DescribableModel.of(Deploy.class).instantiate(baseArgs());
        assertThat(omitted.getCpuCores(), nullValue());
        assertThat(omitted.getCoresPerSocket(), nullValue());
        assertThat(omitted.getCpuLimitMHz(), nullValue());
        assertThat(omitted.getMemorySize(), nullValue());

        Map<String, Object> args = baseArgs();
        args.put("cpuCores", "8");
        args.put("coresPerSocket", "4");
        args.put("cpuLimitMHz", "2000");
        args.put("memorySize", "16384");
        Deploy sized = DescribableModel.of(Deploy.class).instantiate(args);
        assertThat(sized.getCpuCores(), is("8"));
        assertThat(sized.getCoresPerSocket(), is("4"));
        assertThat(sized.getCpuLimitMHz(), is("2000"));
        assertThat(sized.getMemorySize(), is("16384"));
    }

    @Test
    void hostWeightsCanBeGivenPerCallAndAreUnsetWhenOmitted() throws Exception {
        Deploy omitted = DescribableModel.of(Deploy.class).instantiate(baseArgs());
        assertThat(omitted.getHostWeightFreeCpuMhz(), nullValue());
        assertThat(omitted.getHostWeightFreeCpuPercent(), nullValue());
        assertThat(omitted.getHostWeightFreeMemoryMB(), nullValue());
        assertThat(omitted.getHostWeightFreeMemoryPercent(), nullValue());

        Map<String, Object> args = baseArgs();
        args.put("hostWeightFreeCpuMhz", "1");
        args.put("hostWeightFreeCpuPercent", "2");
        args.put("hostWeightFreeMemoryMB", "3");
        args.put("hostWeightFreeMemoryPercent", "4");
        Deploy weighted = DescribableModel.of(Deploy.class).instantiate(args);
        assertThat(weighted.getHostWeightFreeCpuMhz(), is("1"));
        assertThat(weighted.getHostWeightFreeCpuPercent(), is("2"));
        assertThat(weighted.getHostWeightFreeMemoryMB(), is("3"));
        assertThat(weighted.getHostWeightFreeMemoryPercent(), is("4"));
    }

    @Test
    void hostPlacementFieldsDefaultToNullWhenOmitted() throws Exception {
        Deploy step = DescribableModel.of(Deploy.class).instantiate(baseArgs());

        assertThat(step.getHost(), nullValue());
        assertThat(step.getHostSelectionMode(), nullValue());
        assertThat(step.getHostSelectionCandidates(), nullValue());
        assertThat(step.getHostSelectionCandidatesAsString(), is(""));
    }

    @Test
    void hostSelectionCandidatesAsStringAcceptsACommaSeparatedString() throws Exception {
        Map<String, Object> args = baseArgs();
        args.put("hostSelectionCandidatesAsString", "esx01.example.com, esx02.example.com");

        Deploy step = DescribableModel.of(Deploy.class).instantiate(args);

        assertThat(step.getHostSelectionCandidates(), is(Set.of("esx01.example.com", "esx02.example.com")));
        assertThat(step.getHostSelectionCandidatesAsString(), is("esx01.example.com, esx02.example.com"));
    }

    @Test
    void hostSelectionCandidatesAcceptsAFlatList() throws Exception {
        Map<String, Object> args = baseArgs();
        args.put("hostSelectionCandidates", List.of("esx01.example.com", "esx02.example.com"));

        Deploy step = DescribableModel.of(Deploy.class).instantiate(args);

        assertThat(step.getHostSelectionCandidates(), is(Set.of("esx01.example.com", "esx02.example.com")));
    }

    @Test
    void hostPlacementFieldsAreHonouredWhenSet() throws Exception {
        Map<String, Object> args = baseArgs();
        args.put("host", "esx01.example.com");
        args.put("hostSelectionMode", "DRS_RECOMMENDED");
        args.put("hostSelectionCandidates", List.of("esx01.example.com", "esx02.example.com"));

        Deploy step = DescribableModel.of(Deploy.class).instantiate(args);

        assertThat(step.getHost(), is("esx01.example.com"));
        assertThat(step.getHostSelectionMode(), is("DRS_RECOMMENDED"));
        assertThat(step.getHostSelectionCandidates(), is(Set.of("esx01.example.com", "esx02.example.com")));
    }

    @Test
    void setHostSelectionCandidatesAsStringUpdatesTheCanonicalSet() throws Exception {
        Deploy step = new Deploy("linux-template", "new-vm", false, "Resources", "my-cluster", "", "", "", null, false);

        step.setHostSelectionCandidatesAsString("esx01.example.com, esx02.example.com");

        assertThat(step.getHostSelectionCandidates(), is(Set.of("esx01.example.com", "esx02.example.com")));
    }

    @Test
    void setHostSelectionCandidatesAsStringBlankMeansInherit() throws Exception {
        Deploy step = new Deploy("linux-template", "new-vm", false, "Resources", "my-cluster", "", "", "", null, false);

        step.setHostSelectionCandidatesAsString("");

        assertThat(step.getHostSelectionCandidates(), nullValue());
    }

    @Test
    void setHostSelectionCandidatesAsStringCommaExplicitlyOverridesToEmpty() throws Exception {
        Deploy step = new Deploy("linux-template", "new-vm", false, "Resources", "my-cluster", "", "", "", null, false);

        step.setHostSelectionCandidatesAsString(",");

        assertThat(step.getHostSelectionCandidates(), is(Set.of()));
        assertThat(step.getHostSelectionCandidatesAsString(), is(","));
    }

    @Test
    void hostSelectionModeNoneIsStoredVerbatim() throws Exception {
        Deploy step = new Deploy("linux-template", "new-vm", false, "Resources", "my-cluster", "", "", "", null, false);

        step.setHostSelectionMode("NONE");

        assertThat(step.getHostSelectionMode(), is("NONE"));
    }

    @Test
    void sourceCloudCanBeSetAndRetrieved() throws Exception {
        Deploy step = new Deploy("linux-template", "new-vm", false, "Resources", "my-cluster", "", "", "", null, false);
        assertThat(step.getSourceCloud(), nullValue());

        vSphereCloud cloud = new vSphereCloud(null, "my-vcenter", 0, 0, false, null);
        step.setSourceCloud(cloud);

        assertThat(step.getSourceCloud(), is(cloud));
    }

    @Test
    void waitNotificationClosureBindsLikeAPipelineWouldPassItButIsNeverReadBackOrSaved(
            org.jvnet.hudson.test.JenkinsRule r) throws Exception {
        groovy.lang.Closure<Object> closure = new groovy.lang.Closure<Object>(this) {
            @SuppressWarnings("unused")
            public Object doCall(String message, Object reason) {
                return null;
            }
        };
        Map<String, Object> args = baseArgs();
        args.put("hostSelectionWaitNotification", closure);
        args.put("hostSelectionWaitNotificationIgnoreErrors", true);
        Deploy step = DescribableModel.of(Deploy.class).instantiate(args);

        assertThat(step.isHostSelectionWaitNotificationIgnoreErrors(), is(true));
        // not readable as a property, so it cannot leak into recorded step arguments or generated snippets
        assertThat(step.getHostSelectionWaitNotification(), nullValue());
        assertThat(
                DescribableModel.of(Deploy.class)
                        .uninstantiate2(step)
                        .toMap()
                        .containsKey("hostSelectionWaitNotification"),
                is(false));
        // and not persisted with a job's configuration
        String xml = jenkins.model.Jenkins.XSTREAM2.toXML(step);
        assertThat(xml.contains("hostSelectionWaitNotification"), is(false));
        assertThat(xml.contains("hostSelectionWaitNotificationIgnoreErrors"), is(false));
    }

    @Test
    void waitNotificationIsOffByDefault() throws Exception {
        Deploy step = DescribableModel.of(Deploy.class).instantiate(baseArgs());
        assertThat(step.isHostSelectionWaitNotificationIgnoreErrors(), is(false));
    }
}
