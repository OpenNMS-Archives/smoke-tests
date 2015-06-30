# keep RPM from making an empty debug package
%define debug_package %{nil}

Name:			debian-shunt
Summary:		Placeholder package to make RPM on debian happy
Version:		1.0
Release:		4
License:		Public Domain
Group:			Development/Tools
BuildArch:		noarch

AutoReqProv:		no

Provides: /bin/sh
Provides: /bin/bash
Provides: jrrd = 1.0.9
Provides: jicmp = 1.4.3
Provides: jicmp6 = 1.2.3
Provides: postgresql-server = 9.3
Provides: jdk = 2000:1.8.0
Provides: java-1.8.0
Provides: jre-1.8.0
Provides: opennms-rrdtool = 1.4.7

%description
This is a placeholder wrapper package to provide the dependencies necessary
to make installing OpenNMS on our Ubuntu-based bamboo systems possible.

%files
