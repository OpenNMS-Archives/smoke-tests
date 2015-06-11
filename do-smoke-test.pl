#!/usr/bin/perl -w

use strict;
use warnings;

use Config qw();
use Cwd qw(abs_path);
use DBI;
use File::Basename;
use File::Copy;
use File::Find;
use File::Path;
use File::ShareDir qw(:ALL);
use File::Slurp;
use File::Spec;
use File::Tail;
use IO::Handle;
use IPC::Open3;
use POSIX;
use Proc::ProcessTable;
use version;

use OpenNMS::Release 2.9.12;

use vars qw(
	$OPENNMS_HOME

	$TOPDIR
	$OPENNMS_TESTDIR
	$RPMDIR

	$XVFB_RUN
	$JAVA

	$NON_DESTRUCTIVE
);

END {
	clean_up();
}

my $login = (getpwuid $>);
if ($login ne 'root') {
	fail(1, 'You must be root to run this!');
}

print $0 . ' ' . version->new($OpenNMS::Release::VERSION) . "\n";

$ENV{'PATH'} = $ENV{'PATH'} . $Config::Config{path_sep} . '/usr/sbin' . $Config::Config{path_sep} . '/sbin';

$TOPDIR = dirname(abs_path($0));

$OPENNMS_TESTDIR = shift @ARGV;
$RPMDIR = shift @ARGV;

$OPENNMS_HOME = $ENV{'OPENNMS_HOME'};
if (not defined $OPENNMS_HOME or not -d $OPENNMS_HOME) {
	$OPENNMS_HOME='/opt/opennms';
}
$ENV{'OPENNMS_HOME'} = $OPENNMS_HOME;

if (not defined $OPENNMS_TESTDIR or not -d $OPENNMS_TESTDIR) {
	print STDERR "ERROR: You must specify a valid OpenNMS source directory and a valid OpenNMS RPM directory!\n";
	usage();
}

if (not defined $RPMDIR or not -d $RPMDIR) {
	print STDERR "ERROR: You must specify a valid OpenNMS RPM directory!\n";
	usage();
}

$OPENNMS_TESTDIR = abs_path($OPENNMS_TESTDIR);
$RPMDIR = abs_path($RPMDIR);

delete $ENV{'DISPLAY'};

if (not exists $ENV{'JAVA_HOME'} or not -d $ENV{'JAVA_HOME'}) {
	die "\$JAVA_HOME is not set, or not valid!";
}

$JAVA = File::Spec->catfile($ENV{'JAVA_HOME'}, 'bin', 'java');

chomp($XVFB_RUN = `which xvfb-run`);
if (not defined $XVFB_RUN or $XVFB_RUN eq "" or ! -x $XVFB_RUN) {
	die "Unable to locate xvfb-run!\n";
}

stop_opennms();
remove_opennms();
clean_temp();
clean_yum();
install_opennms();
drop_database();
configure_opennms();
clean_logs();
start_opennms();
#build_test_api();
build_smoke_tests();
run_smoke_tests();
clean_mozilla_home();

exit(0);

sub usage {
	print STDERR "usage: $0 <opennms-test-source-directory> <opennms-rpm-directory>\n\n";
	exit 1;
}

sub fail {
	my $ret = shift;
	my $reason = shift;

	if (defined $reason) {
		print STDERR "Smoke Tests Failed: $reason\n\n";
	}

	if (not defined $ret) {
		$ret = 1;
	}

	if (defined $OPENNMS_HOME and -d $OPENNMS_HOME) {
		for my $logfile ('manager.log', 'output.log') {
			my $file = File::Spec->catfile($OPENNMS_HOME, 'logs', $logfile);
			if (! -e $file) {
				$file = File::Spec->catfile($OPENNMS_HOME, 'logs', 'daemon', $logfile);
			}
	
			if (-e $file) {
				my $contents = read_file($file);
				print STDOUT "=== contents of $file ===\n";
				print STDOUT $contents, "\n\n";
			}
		}
	}

	exit $ret;
}

sub clean_up {
	# shut down OpenNMS if we can
	stop_opennms();

	# make sure everything is owned by non-root
	if (defined $OPENNMS_TESTDIR) {
		print "- fixing ownership of $OPENNMS_TESTDIR... ";
		my $uid = getpwnam('bamboo');
		if (not defined $uid) {
			$uid = getpwnam('opennms');
		}
		my $gid = getgrnam('bamboo');
		if (not defined $gid) {
			$gid = getgrnam('opennms');
		}
		if (defined $uid and defined $gid) {
			find({
				wanted => sub {
					chown($uid, $gid, $File::Find::name);
				},
				bydepth => 1,
				follow => 1,
			}, $OPENNMS_TESTDIR);
			print "done\n";
		} else {
			print "unable to determine proper owner\n";
		}
	}
}

sub clean_temp {
	print "- cleaning out old /tmp files from tests... ";
	find({
		wanted => sub {
			if ($_ =~ /^(FileAnticipator_temp|StringResource\d+|com\.vaadin\.client\.metadata\.ConnectorBundleLoaderImpl|mockSnmpAgent\d+|stdout\d+)/) {
				if (-d $_) {
					rmtree($File::Find::name);
				} else {
					unlink($File::Find::name);
				}
			}
		},
		bydepth => 1,
		follow => 1,
	}, '/tmp');
	print "done\n";
}

sub clean_yum {
	system('yum', 'clean', 'expire-cache');
	if (-d '/var/cache/yum') {
		print "- cleaning out old RPMs from /var/cache/yum:\n";
		my $now = time();
		find({
			wanted => sub {
				if (-f $_ and $_ =~ /\.rpm$/) {
					my @stats = stat($File::Find::name);
					if ($now - $stats[9] > 86400) { # 1 day
						print "  * $File::Find::name\n";
						unlink($File::Find::name);
					}
				}
			},
			bydepth => 1,
			follow => 1,
		}, '/var/cache/yum');
	} else {
		print STDERR "WARNING: Unable to locate YUM cache directory.  Running 'yum clean all' just to be safe.\n";
		system('yum', 'clean', 'all');
	}
}

sub clean_logs {
	print "- cleaning out old OpenNMS logs... ";
	find({
		wanted => sub {
			if (-f $_) {
				unlink($_);
			}
		},
		bydepth => 1,
		follow => 1,
	}, File::Spec->catdir($OPENNMS_HOME, 'logs'));
	print "done\n";
}

sub start_opennms {
	print "- starting OpenNMS... ";
	my $opennms = File::Spec->catfile($OPENNMS_HOME, "bin", "opennms");
	my $timeout = 300;

	if (-x '/bin/systemctl') {
		system("systemctl", "start", "opennms") == 0 or fail(1, "Unable to start OpenNMS: $!");
	} elsif (-x '/usr/sbin/service' or -x '/sbin/service') {
		system("service", "opennms", "start") == 0 or fail(1, "Unable to start OpenNMS: $!");
	} else {
		system($opennms, "start") == 0 or fail(1, "Unable to start OpenNMS: $!");
	}

	# wait for 2 minutes for OpenNMS to start
	my $wait_until = (time() + $timeout);
	while (time() < $wait_until) {
		my $status = `"$opennms" status`;
		if ($status =~ /opennms is running/) {
			print "done\n";
			return 1;
		}
		sleep(1);
	}

	fail(1, "\`opennms status\` never said OpenNMS is running after waiting $timeout seconds... :(");
}

sub stop_opennms {
	print "- stopping OpenNMS...";
	if (is_opennms_running()) {
		print "\n";
		system("systemctl", "stop", "opennms");
		system("service", "opennms", "stop");
		system(File::Spec->catfile($OPENNMS_HOME, "bin", "opennms"), "stop");
		system(File::Spec->catfile($OPENNMS_HOME, "bin", "opennms"), "kill");
	} else {
		print "OpenNMS is not running.\n";
	}
}

sub build_test_api {
	print "- building the OpenNMS smoke test API:\n";

	chdir(File::Spec->catdir($TOPDIR, 'test-api'));
	system(File::Spec->catfile($OPENNMS_TESTDIR, 'compile.pl'), 'install') == 0 or fail(1, "failed to build smoke test API: $!");
}

sub build_smoke_tests {
	print "- building OpenNMS smoke tests:\n";

	chdir($OPENNMS_TESTDIR);
	system("./compile.pl", "-Dsmoke=true", "--also-make", "--projects", "org.opennms:smoke-test", "install") == 0 or fail(1, "failed to compile smoke tests: $!");
}

sub run_smoke_tests {
	print "- running OpenNMS smoke tests:\n";

	chdir(File::Spec->catdir($OPENNMS_TESTDIR, "smoke-test"));
	system($XVFB_RUN, '--wait=20', '--server-args=-screen 0 1920x1080x24', '--server-num=80', '--auto-servernum', '--listen-tcp', '../compile.pl', '-Dorg.opennms.smoketest.logLevel=INFO', '-Dsmoke=true', '-Dorg.opennms.smoketest.webdriver.use-chrome=true', '-t', 'test') == 0 or fail(1, "failed to run smoke tests: $!");
}

sub remove_opennms {
	print "- Removing 'opennms-core' and 'meridian-core':\n";
	system('yum', '-y', 'remove', 'opennms-core', 'meridian-core', 'opennms-remote-poller', 'meridian-remote-poller') == 0 or fail(1, "Unable to remove Horizon and Meridian.");

	my @packages = grep { /(opennms|meridian)/ && !/^opennms-repo-/ } get_installed_packages();
	if (@packages > 0) {
		print "- removing the following packages: ", join(', ', @packages), "\n";
		system('yum', '-y', 'remove', @packages) == 0 or fail(1, "Unable to remove packages: " . join(", ", @packages));
	} else {
		print "- There are no other existing OpenNMS packages to remove.\n";
	}
	for my $dir ($OPENNMS_HOME, '/usr/lib/opennms', '/usr/share/opennms', '/var/lib/opennms', '/var/log/opennms', '/var/opennms') {
		if (-e $dir) {
			rmtree($dir) or fail(1, "Failed to remove $dir: $!");
		}
	}
}

sub clean_mozilla_home {
	my $mozilla_home = File::Spec->catdir($ENV{'HOME'}, '.mozilla');
	if (-d $mozilla_home) {
		rmtree($mozilla_home);
	}
}

sub install_opennms {
	opendir(DIR, $RPMDIR) or fail(1, "Failed to open $RPMDIR for reading: $!");

	my @files = map { File::Spec->catfile($RPMDIR, $_) } grep { /\.rpm$/ } readdir(DIR);
	print "- Installing the following packages:\n";
	print map { "  * " . $_ . "\n" } @files;
	print "\n";
	# 'install' now supports file names
	#system('yum', '-y', 'localinstall', @files) == 0 or fail(1, "Unable to install packages from $RPMDIR");
	system('yum', '-y', 'install', @files) == 0 or fail(1, "Unable to install packages from $RPMDIR");

	closedir(DIR) or fail(1, "Failed to close $RPMDIR: $!");
}

sub configure_opennms {
	print "- Updating default OpenNMS configuration files... ";
	my $rootdir = File::Spec->catdir($TOPDIR, "opennms-home");
	find({
		wanted => sub {
			my $relative = File::Spec->abs2rel($File::Find::name, $rootdir);
			my $opennms = File::Spec->catfile($OPENNMS_HOME, $relative);
			return unless (-f $File::Find::name);
			copy($File::Find::name, $opennms);
		},
		bydepth => 1,
		follow => 1,
	}, $rootdir);
	print "done\n";

	print "- Trying 'runjava -S $JAVA'... ";
	my $runjava = File::Spec->catfile($OPENNMS_HOME, "bin", "runjava");
	my $output = `"$runjava" -S "$JAVA" 2>&1`;
	if ($? == 0) {
		print "done\n";
	} else {
		print "failed\n";
		print "- Trying 'runjava -s'... ";
		$output = `"$runjava" -s`;
		if ($? == 0) {
			print "done\n";
		} else {
			fail(1, "'runjava -s' failed:\n" . $output);
		}
	}

	print "- Configuring OpenNMS:\n";
	system(File::Spec->catfile($OPENNMS_HOME, "bin", "install"), "-dis") == 0 or fail(1, "Failed while running 'install -dis'.");
}

sub drop_database {
	print "- Restarting PostgreSQL... ";
	system("service", "postgresql", "restart") == 0 or fail(1, "Unable to restart PostgreSQL: $!");
	print "done\n";

	my $in = IO::Handle->new();
	my $out = IO::Handle->new();
	my $err = IO::Handle->new();
	my $ok = 0;
	my $output = "";

	print "- Dropping the 'OpenNMS' database... ";
	my $pid = open3($in, $out, $err, 'dropdb', '-U', 'postgres', 'opennms');
	close($out) or fail(1, "Failed to close STDOUT on dropdb command: $!");
	close($in) or fail(1, "Failed to close STDIN on dropdb command: $!");
	while (my $line = <$err>) {
		chomp($line);
		if ($line =~ /does not exist/) {
			$ok = 1;
		}
		$output .= "\n" . $line;
	}
	close($err);
	waitpid($pid, 0);
	my $child_exit_status = $? >> 8;

	if ($child_exit_status == 0) {
		$ok = 1;
	}

	if ($ok) {
		print "done\n";
		return;
	} else {
		print "failed\n";
		fail(1, "Failed to drop database:\n" . $output);
	}
}

sub is_opennms_running {
	my $pt = new Proc::ProcessTable();
	for my $p (@{$pt->table}) {
		if ($p->cmndline =~ /opennms_bootstrap\.jar/) {
			return 1;
		}
	}
	return 0;
}

sub get_installed_packages {
	my $in = IO::Handle->new();
	my $out = IO::Handle->new();
	my $err = IO::Handle->new();

	# yum list --showduplicates --color=never installed \*opennms\*
	my $pid = open3($in, $out, $err, 'yum', 'list', '--showduplicates', '--color=never', 'installed');
	my @packages;
	while (my $line = <$out>) {
		chomp($line);
		next unless ($line =~ /installed$/);
		my ($package) = split(/\s+/, $line);
		push(@packages, $package);
	}
	waitpid($pid, 0);
	return @packages;
}
