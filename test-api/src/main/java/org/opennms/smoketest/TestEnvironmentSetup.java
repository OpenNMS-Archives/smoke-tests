/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2017-2017 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2017 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.smoketest;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.Assume;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.opennms.test.system.api.NewTestEnvironment;
import org.opennms.test.system.api.TestEnvironment;
import org.opennms.test.system.api.TestEnvironmentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spotify.docker.client.messages.ContainerInfo;

public class TestEnvironmentSetup implements TestEnvironment, TestRule {

    public static final TestEnvironmentSetup DEFAULTS = new TestEnvironmentSetup().withDefaults();

    public static final TestEnvironmentSetup MINIONS = new TestEnvironmentSetup().withDefaults()
            .withBuilder(TestEnvironment.builder().all())
            .enforceDocker();

    private static final Logger LOG = LoggerFactory.getLogger(TestEnvironmentSetup.class);

    private boolean useDocker;
    private TestEnvironmentBuilder builder;
    private TestEnvironment testEnvironment;
    private boolean enforceDocker;
    private final List<Consumer> consumerList = new ArrayList<>();

    public TestEnvironmentSetup withDefaults() {
        useDocker(Boolean.getBoolean("org.opennms.smoketest.docker"));
        withBuilder(TestEnvironment.builder().opennms());
        return this;
    }

    public TestEnvironmentSetup useDocker(boolean useDocker) {
        this.useDocker = useDocker;
        return this;
    }

    public TestEnvironmentSetup enforceDocker(boolean enforceDocker) {
        this.enforceDocker = enforceDocker;
        return this;
    }

    public TestEnvironmentSetup enforceDocker() {
        enforceDocker(true);
        return this;
    }

    public TestEnvironmentSetup withBuilder(TestEnvironmentBuilder builder) {
        this.builder = builder;
        return this;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                if (enforceDocker) {
                    Assume.assumeTrue("Docker is required for this test!  Enable it by setting -Dorg.opennms.smoketest.docker=true when running.", isDockerEnabled());
                }
                if (useDocker) {
                    LOG.warn("Setting up Docker test environment.");
                    try {
                        configureTestEnvironment(builder);

                        consumerList.forEach(consumer -> consumer.accept(builder));
                        testEnvironment = builder.build();

                        // Also apply to actual environment
                        testEnvironment.apply(base, description).evaluate();
                    } catch (final Throwable t) {
                        throw new RuntimeException(t);
                    }
                }
                if (testEnvironment == null) {
                    LOG.warn("Setting up local test environment.");
                    testEnvironment = new LocalTestEnvironment();
                }
            }
        };
    }

    public boolean isDockerEnabled() {
        return useDocker;
    }

    public TestEnvironment getTestEnvironment() {
        return testEnvironment;
    }

    private void configureTestEnvironment(final TestEnvironmentBuilder builder) {
        builder.skipTearDown(Boolean.getBoolean("org.opennms.smoketest.docker.skipTearDown"));
        builder.useExisting(Boolean.getBoolean("org.opennms.smoketest.docker.useExisting"));
        builder.withOpenNMSEnvironment()
                .optIn(false)
                .addFile(OpenNMSSeleniumTestCase.class.getResource("etc/monitoring-locations.xml"), "etc/monitoring-locations.xml");
        }

    public TestEnvironmentSetup consume(Consumer<TestEnvironmentBuilder> consumer) {
        consumerList.add(consumer);
        return this;
    }

    @Override
    public InetSocketAddress getServiceAddress(NewTestEnvironment.ContainerAlias alias, int port) {
        return testEnvironment.getServiceAddress(alias, port);
    }

    @Override
    public InetSocketAddress getServiceAddress(NewTestEnvironment.ContainerAlias alias, int port, String type) {
        return testEnvironment.getServiceAddress(alias, port, type);
    }

    @Override
    public InetSocketAddress getServiceAddress(ContainerInfo alias, int port, String type) {
        return testEnvironment.getServiceAddress(alias, port, type);
    }

    @Override
    public ContainerInfo getContainerInfo(NewTestEnvironment.ContainerAlias alias) {
        return testEnvironment.getContainerInfo(alias);
    }

    @Override
    public Set<NewTestEnvironment.ContainerAlias> getContainerAliases() {
        return testEnvironment.getContainerAliases();
    }
}
