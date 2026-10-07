/*   Copyright 2026, Jim Klimov
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
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
