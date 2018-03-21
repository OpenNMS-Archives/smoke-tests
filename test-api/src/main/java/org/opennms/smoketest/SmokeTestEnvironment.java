/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018-2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
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

import static org.opennms.test.system.api.NewTestEnvironment.ContainerAlias;

import java.net.InetSocketAddress;
import java.util.Set;

import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.opennms.test.system.api.TestEnvironment;
import org.opennms.test.system.api.TestEnvironmentBuilder;
import org.opennms.test.system.api.junit.ExternalResourceRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spotify.docker.client.messages.ContainerInfo;

public class SmokeTestEnvironment extends ExternalResourceRule implements TestEnvironment {

    public static final SmokeTestEnvironment DEFAULT = new SmokeTestEnvironment(Boolean.getBoolean("org.opennms.smoketest.docker"));

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final boolean useDocker;

    private final TestEnvironment delegate;

    public SmokeTestEnvironment(boolean useDocker) {
        // First setup logging
        LoggingUtils.setupLoggingForSeleniumTests();

        // Determine weather to use docker or a local environment
        this.useDocker = useDocker;
        if (useDocker) {
            logger.warn("Setting up Docker test environment.");
            final TestEnvironmentBuilder builder = TestEnvironment.builder().opennms();
            applyDefaults(builder);
            delegate = builder.build();
        } else {
            logger.warn("Setting up local test environment.");
            delegate = new LocalTestEnvironment();
        }
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return delegate.apply(base, description);
    }

    @Override
    public InetSocketAddress getServiceAddress(ContainerAlias alias, int port) {
        return delegate.getServiceAddress(alias, port);
    }

    @Override
    public InetSocketAddress getServiceAddress(ContainerAlias alias, int port, String type) {
        return delegate.getServiceAddress(alias, port, type);
    }

    @Override
    public InetSocketAddress getServiceAddress(ContainerInfo alias, int port, String type) {
        return delegate.getServiceAddress(alias, port, type);
    }

    @Override
    public ContainerInfo getContainerInfo(ContainerAlias alias) {
        return delegate.getContainerInfo(alias);
    }

    @Override
    public Set<ContainerAlias> getContainerAliases() {
        return delegate.getContainerAliases();
    }

    public String getServerAddress() {
        return getServiceAddress(ContainerAlias.OPENNMS, 8980).getAddress().getHostAddress();
    }
    public int getServerHttpPort() {
        return getServiceAddress(ContainerAlias.OPENNMS, 8980).getPort();
    }
    public int getServerEventPort() {
        return getServiceAddress(ContainerAlias.OPENNMS, 5817).getPort();
    }

    public InetSocketAddress getPostgresService() {
        return getServiceAddress(ContainerAlias.POSTGRES, 5432);
    }

    public String getBaseUrl() {
        return new StringBuilder()
                .append("http://").append(getServerAddress())
                .append(":").append(getServerHttpPort()).append("/")
                .toString();
    }

    public String buildUrl(String urlFragment) {
        return getBaseUrl() + "opennms" + (urlFragment.startsWith("/")? urlFragment : "/" + urlFragment);
    }

    public boolean isDocker() {
        return useDocker;
    }

    public boolean isUseDocker() {
        return useDocker;
    }

    public static void applyDefaults(TestEnvironmentBuilder builder) {
        builder.skipTearDown(Boolean.getBoolean("org.opennms.smoketest.docker.skipTearDown"));
        builder.useExisting(Boolean.getBoolean("org.opennms.smoketest.docker.useExisting"));

        builder.withOpenNMSEnvironment()
                .optIn(false)
                .addFile(OpenNMSSeleniumTestCase.class.getResource("etc/monitoring-locations.xml"), "etc/monitoring-locations.xml");
    }
}
