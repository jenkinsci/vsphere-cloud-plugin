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
package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.junit.jupiter.api.Test;

/** A pooled session that is only a connection (SSH) is checked when it is handed out after it was idle. */
class VSphereConnectionPoolVerifyTest {

    private final AtomicInteger connected = new AtomicInteger();
    private final AtomicInteger asked = new AtomicInteger();
    private final AtomicBoolean answers = new AtomicBoolean(true);

    private VSphere connection(boolean checkedWhenAcquired) {
        connected.incrementAndGet();
        return (VSphere) Proxy.newProxyInstance(
                VSphere.class.getClassLoader(), new Class<?>[] {VSphere.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isSessionAlive":
                            asked.incrementAndGet();
                            return answers.get();
                        case "shouldBeCheckedWhenAcquired":
                            return checkedWhenAcquired;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "stub connection";
                        case "markAsPooled":
                        case "forceDisconnect":
                        case "disconnect":
                            return null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private VSphereConnectionPool pool(boolean checkedWhenAcquired, long verifyAfterIdleMs) {
        return new VSphereConnectionPool(
                new VSphereConnectionConfig("esxi.example.com"),
                null,
                0,
                0,
                0,
                0,
                config -> connection(checkedWhenAcquired),
                verifyAfterIdleMs);
    }

    private static void idle() throws InterruptedException {
        Thread.sleep(20);
    }

    @Test
    void aSessionThatAnswersAfterBeingIdleIsKept() throws Exception {
        VSphereConnectionPool pool = pool(true, 1);
        try {
            VSphere first = pool.acquire();
            idle();
            VSphere second = pool.acquire();

            assertThat(second, sameInstance(first));
            assertThat(asked.get(), is(1));
            assertThat(connected.get(), is(1));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aSessionThatDoesNotAnswerAfterBeingIdleIsReplaced() throws Exception {
        VSphereConnectionPool pool = pool(true, 1);
        try {
            VSphere first = pool.acquire();
            idle();
            answers.set(false);
            VSphere second = pool.acquire();

            assertThat(second, not(sameInstance(first)));
            assertThat(connected.get(), is(2));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aSessionThatWasJustUsedIsNotAskedAgain() throws Exception {
        VSphereConnectionPool pool = pool(true, 60_000);
        try {
            pool.acquire();
            pool.acquire();
            pool.acquire();

            assertThat(asked.get(), is(0));
            assertThat(connected.get(), is(1));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aSessionOfAKindThatTheServerKeepsIsNeverAsked() throws Exception {
        VSphereConnectionPool pool = pool(false, 1);
        try {
            pool.acquire();
            idle();
            answers.set(false);
            pool.acquire();

            assertThat(asked.get(), is(0));
            assertThat(connected.get(), is(1));
        } finally {
            pool.shutdown();
        }
    }
}
