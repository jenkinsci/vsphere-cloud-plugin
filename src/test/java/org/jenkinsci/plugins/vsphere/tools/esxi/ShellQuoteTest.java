package org.jenkinsci.plugins.vsphere.tools.esxi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.Test;

class ShellQuoteTest {

    @Test
    void aQuoteIsClosedEscapedAndReopened() throws Exception {
        assertThat(ShellQuote.quote("it's"), is("'it'\\''s'"));
        assertThat(ShellQuote.quote("'"), is("''\\'''"));
    }

    @Test
    void plainTextJustGetsQuotes() throws Exception {
        assertThat(ShellQuote.quote("my vm"), is("'my vm'"));
        assertThat(ShellQuote.quote(""), is("''"));
    }

    @Test
    void whateverItIsItComesBackAsOneWordWithNothingElse() throws Exception {
        for (String value : List.of(
                "plain",
                "with space",
                "x'; rm -rf / #",
                "it's \"quoted\" $(reboot) `halt` \\ end",
                "'''",
                "a\nb",
                "$HOME ; | & > < ( ) * ? ~ !")) {
            List<String> words = FakeEsxiHost.split("cmd " + ShellQuote.quote(value) + " tail");

            assertThat(words, contains("cmd", value, "tail"));
        }
    }

    @Test
    void aNulCharacterIsRefused() {
        assertThrows(VSphereException.class, () -> ShellQuote.quote("a\0b"));
        assertThrows(VSphereException.class, () -> ShellQuote.quote(null));
    }

    @Test
    void idsAreNumbersAndNothingElse() throws Exception {
        assertThat(ShellQuote.id(12), is("12"));
        assertThrows(VSphereException.class, () -> ShellQuote.id(0));
        assertThrows(VSphereException.class, () -> ShellQuote.id(-1));
    }
}
