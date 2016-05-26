package org.opennms.smoketest;

import java.net.InetSocketAddress;
import java.util.Set;

import org.opennms.test.system.api.NewTestEnvironment.ContainerAlias;
import org.opennms.test.system.api.TestEnvironment;
import org.opennms.test.system.api.junit.ExternalResourceRule;

import com.spotify.docker.client.messages.ContainerInfo;

public class LocalTestEnvironment extends ExternalResourceRule implements TestEnvironment {

    @Override
    public InetSocketAddress getServiceAddress(final ContainerAlias alias, final int port) {
        return getServiceAddress(alias, port, "tcp");
    }

    @Override
    public InetSocketAddress getServiceAddress(final ContainerAlias alias, final int port, final String type) {
        if (alias == ContainerAlias.OPENNMS) {
            final String hostname;
            final Integer overriddenPort;
            final String opennmsWebHostname = System.getProperty("org.opennms.smoketest.web-host", "localhost");
            if (port == 5817) {
                hostname = System.getProperty("org.opennms.smoketest.event-host", opennmsWebHostname);
                overriddenPort = Integer.getInteger("org.opennms.smoketest.event-port", port);
            } else {
                hostname = opennmsWebHostname;
                overriddenPort = Integer.getInteger("org.opennms.smoketest.web-port", port);
            }
            return new InetSocketAddress(hostname, overriddenPort);
        }
        throw new UnsupportedOperationException("Unsure how to answer for non-OpenNMS local requests!");
    }

    @Override
    public ContainerInfo getContainerInfo(ContainerAlias alias) {
        throw new UnsupportedOperationException("Unsure how to answer for local requests!");
    }

    @Override
    public Set<ContainerAlias> getContainerAliases() {
        throw new UnsupportedOperationException("Unsure how to answer for local requests!");
    }

}
