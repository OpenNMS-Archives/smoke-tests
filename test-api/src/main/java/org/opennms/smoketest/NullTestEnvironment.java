package org.opennms.smoketest;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;

import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.opennms.test.system.api.NewTestEnvironment.ContainerAlias;
import org.opennms.test.system.api.TestEnvironment;

import com.spotify.docker.client.messages.ContainerInfo;

public class NullTestEnvironment implements TestEnvironment {

	@Override
	public Statement apply(Statement base, Description description) {
		return base;
	}

	@Override
	public InetSocketAddress getServiceAddress(ContainerAlias alias, int port) {
		return null;
	}

	@Override
	public InetSocketAddress getServiceAddress(ContainerAlias alias, int port, String type) {
		return null;
	}

	@Override
	public ContainerInfo getContainerInfo(ContainerAlias alias) {
		return null;
	}

	@Override
	public Set<ContainerAlias> getContainerAliases() {
		return Collections.emptySet();
	}

}
