#!/usr/bin/perl -w

use strict;
use warnings;

use Config qw();
use Cwd qw(abs_path);
use File::Basename;
use File::Find;
use File::Path;
use File::ShareDir qw(:ALL);
use File::Slurp;
use File::Spec;
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

	$SCRIPT
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
$SCRIPT = File::Spec->catfile($TOPDIR, 'do-smoke-test.sh');

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
clean_yum();

fail(1);

chdir($OPENNMS_TESTDIR);
system($XVFB_RUN, '--wait=20', '--server-args=-screen 0 1920x1080x24', '--server-num=80', '--auto-servernum', '--listen-tcp', $SCRIPT);
my $ret = $? >> 8;

fail($ret);

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
			find(
				sub {
					chown($uid, $gid, $File::Find::name);
				},
				$OPENNMS_TESTDIR
			);
			print "done\n";
		} else {
			print "unable to determine proper owner\n";
		}
	}
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
						#unlink($File::Find::name);
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

sub stop_opennms {
	print "- stopping OpenNMS... ";
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

sub remove_opennms {
	my @packages = grep { /(opennms|meridian)/ && !/^opennms-repo-/ } get_installed_packages();
	if (@packages > 0) {
		print "- removing the following packages: ", join(', ', @packages), "\n";
		system('yum', '-y', 'remove', @packages) == 0 or fail(1, "Unable to remove packages: " . join(", ", @packages));
	} else {
		print "- There are no existing OpenNMS packages to remove.\n";
	}
	for my $dir ($OPENNMS_HOME, '/usr/lib/opennms', '/usr/share/opennms', '/var/lib/opennms', '/var/log/opennms', '/var/opennms') {
		if (-e $dir) {
			rmtree($dir) or fail(1, "Failed to remove $dir: $!");
		}
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
