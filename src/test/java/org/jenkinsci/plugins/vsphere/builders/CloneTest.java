package org.jenkinsci.plugins.vsphere.builders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.vSphereCloud;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies the optional host-placement fields ({@code host}, {@code hostSelectionMode},
 * {@code hostSelectionCandidates}) are wired up the same way a Groovy pipeline step invocation
 * would bind them (named-parameter {@link DescribableModel#instantiate}), and that
 * omitting them entirely preserves the pre-existing behaviour of this build step.
 */
@WithJenkins
class CloneTest {

    private static Map<String, Object> baseArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("sourceName", "linux-template");
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
        // Guards against a regression of the "Unknown parameter" warning that pipeline
        // authors would otherwise hit when passing these fields.
        DescribableModel<Clone> model = DescribableModel.of(Clone.class);
        assertThat(model.getParameter("host"), notNullValue());
        assertThat(model.getParameter("hostSelectionMode"), notNullValue());
        assertThat(model.getParameter("hostSelectionCandidates"), notNullValue());
        assertThat(model.getParameter("hostSelectionCandidatesAsString"), notNullValue());
    }

    @Test
    void hostPlacementFieldsDefaultToNullWhenOmitted() throws Exception {
        // Backward compatibility: existing jobs/pipelines that don't mention these
        // fields must behave exactly as before (no host restriction of any kind).
        Clone step = DescribableModel.of(Clone.class).instantiate(baseArgs());

        assertThat(step.getHost(), nullValue());
        assertThat(step.getHostSelectionMode(), nullValue());
        assertThat(step.getHostSelectionCandidates(), nullValue());
        assertThat(step.getHostSelectionCandidatesAsString(), is(""));
    }

    @Test
    void hostSelectionCandidatesAsStringAcceptsACommaSeparatedString() throws Exception {
        Map<String, Object> args = baseArgs();
        args.put("hostSelectionCandidatesAsString", "esx01.example.com, esx02.example.com");

        Clone step = DescribableModel.of(Clone.class).instantiate(args);

        assertThat(step.getHostSelectionCandidates(), is(Set.of("esx01.example.com", "esx02.example.com")));
        assertThat(step.getHostSelectionCandidatesAsString(), is("esx01.example.com, esx02.example.com"));
    }

    @Test
    void hostSelectionCandidatesAcceptsAFlatList() throws Exception {
        Map<String, Object> args = baseArgs();
        args.put("hostSelectionCandidates", List.of("esx01.example.com", "esx02.example.com"));

        Clone step = DescribableModel.of(Clone.class).instantiate(args);

        assertThat(step.getHostSelectionCandidates(), is(Set.of("esx01.example.com", "esx02.example.com")));
    }

    @Test
    void hostPlacementFieldsAreHonouredWhenSet() throws Exception {
        Map<String, Object> args = baseArgs();
        args.put("host", "esx01.example.com");
        args.put("hostSelectionMode", "LEAST_LOADED");
        args.put("hostSelectionCandidates", List.of("esx01.example.com", "esx02.example.com"));

        Clone step = DescribableModel.of(Clone.class).instantiate(args);

        assertThat(step.getHost(), is("esx01.example.com"));
        assertThat(step.getHostSelectionMode(), is("LEAST_LOADED"));
        assertThat(step.getHostSelectionCandidates(), is(Set.of("esx01.example.com", "esx02.example.com")));
    }

    @Test
    void setHostSelectionCandidatesAsStringUpdatesTheCanonicalSet() throws Exception {
        Clone step = new Clone("linux-template", "new-vm", false, "Resources", "my-cluster",
                "", "", false, null, null, null, null, null);

        step.setHostSelectionCandidatesAsString("esx01.example.com, esx02.example.com");

        assertThat(step.getHostSelectionCandidates(), is(Set.of("esx01.example.com", "esx02.example.com")));
    }

    @Test
    void setHostSelectionCandidatesAsStringBlankMeansInherit() throws Exception {
        Clone step = new Clone("linux-template", "new-vm", false, "Resources", "my-cluster",
                "", "", false, null, null, null, null, null);

        step.setHostSelectionCandidatesAsString("");

        assertThat(step.getHostSelectionCandidates(), nullValue());
    }

    @Test
    void setHostSelectionCandidatesAsStringCommaExplicitlyOverridesToEmpty() throws Exception {
        Clone step = new Clone("linux-template", "new-vm", false, "Resources", "my-cluster",
                "", "", false, null, null, null, null, null);

        step.setHostSelectionCandidatesAsString(",");

        assertThat(step.getHostSelectionCandidates(), is(Set.of()));
        assertThat(step.getHostSelectionCandidatesAsString(), is(","));
    }

    @Test
    void hostSelectionModeNoneIsStoredVerbatim() throws Exception {
        // Translation of "NONE" into "no selection" happens only in
        // VSphereHostSelection.resolveMode at perform-time, not in storage.
        Clone step = new Clone("linux-template", "new-vm", false, "Resources", "my-cluster",
                "", "", false, null, null, null, null, null);

        step.setHostSelectionMode("NONE");

        assertThat(step.getHostSelectionMode(), is("NONE"));
    }

    @Test
    void sourceCloudCanBeSetAndRetrieved() throws Exception {
        Clone step = new Clone("linux-template", "new-vm", false, "Resources", "my-cluster",
                "", "", false, null, null, null, null, null);
        assertThat(step.getSourceCloud(), nullValue());

        vSphereCloud cloud = new vSphereCloud(null, "my-vcenter", 0, 0, null);
        step.setSourceCloud(cloud);

        assertThat(step.getSourceCloud(), is(cloud));
    }
}
