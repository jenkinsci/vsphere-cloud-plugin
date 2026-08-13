package org.jenkinsci.plugins.vsphere.builders;

import java.util.HashMap;
import java.util.Map;

import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies the optional host-placement fields ({@code host}, {@code hostSelectionMode},
 * {@code candidateHosts}) are wired up the same way a Groovy pipeline step invocation
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
        assertThat(model.getParameter("candidateHosts"), notNullValue());
    }

    @Test
    void hostPlacementFieldsDefaultToNullWhenOmitted() throws Exception {
        Deploy step = DescribableModel.of(Deploy.class).instantiate(baseArgs());

        assertThat(step.getHost(), nullValue());
        assertThat(step.getHostSelectionMode(), nullValue());
        assertThat(step.getCandidateHosts(), nullValue());
    }

    @Test
    void hostPlacementFieldsAreHonouredWhenSet() throws Exception {
        Map<String, Object> args = baseArgs();
        args.put("host", "esx01.example.com");
        args.put("hostSelectionMode", "DRS_RECOMMENDED");
        args.put("candidateHosts", "esx01.example.com, esx02.example.com");

        Deploy step = DescribableModel.of(Deploy.class).instantiate(args);

        assertThat(step.getHost(), is("esx01.example.com"));
        assertThat(step.getHostSelectionMode(), is("DRS_RECOMMENDED"));
        assertThat(step.getCandidateHosts(), is("esx01.example.com, esx02.example.com"));
    }
}
