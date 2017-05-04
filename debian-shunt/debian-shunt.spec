# keep RPM from making an empty debug package
%define debug_package %{nil}

Name:			debian-shunt
Summary:		Placeholder package to make RPM on debian happy
Version:		1.0
Release:		8
License:		Public Domain
Group:			Development/Tools
BuildArch:		noarch

AutoReqProv:		no

Provides: /bin/sh
Provides: /bin/bash
Provides: jrrd = 1.0.9
Provides: jrrd2 = 2.0.3
Provides: jicmp = 2.0.0
Provides: jicmp6 = 2.0.0
Provides: postgresql-server = 9.3
Provides: jdk = 2000:1.8.0
Provides: java-1.8.0
Provides: jre-1.8.0
Provides: opennms-rrdtool = 1.4.7
Provides: rrdtool = 1.5.3
Provides: rrdtool-devel = 1.5.3
Provides: util-linux = 2.23.2
Provides: openssh = 6.6.1p1
Provides: openssh-clients = 6.6.1p1
Provides: rsync = 3.0.9

%description
This is a placeholder wrapper package to provide the dependencies necessary
to make installing OpenNMS on our Ubuntu-based bamboo systems possible.

%files
