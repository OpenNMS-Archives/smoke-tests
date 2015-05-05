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

if (-d $OPENNMS_TESTDIR and -d $RPMDIR) {
	$OPENNMS_TESTDIR = abs_path($OPENNMS_TESTDIR);
	$RPMDIR = abs_path($RPMDIR);
} else {
	print "usage: $0 <opennms-test-source-dir> <opennms-rpm-dir>\n\n";
	exit 1
}

delete $ENV{'DISPLAY'};

if (not exists $ENV{'JAVA_HOME'} or not -d $ENV{'JAVA_HOME'}) {
	die "\$JAVA_HOME is not set, or not valid!";
}

$JAVA = File::Spec->catfile($ENV{'JAVA_HOME'}, 'bin', 'java');

chomp($XVFB_RUN = `which xvfb-run`);
if (not defined $XVFB_RUN or $XVFB_RUN eq "" or ! -x $XVFB_RUN) {
	die "Unable to locate xvfb-run!\n";
}

my $dir = dirname($SCRIPT);
chdir($dir);
my $result = system($XVFB_RUN, '--wait=10', '--server-args=-screen 0 1920x1080x24', '--server-num=80', '--auto-servernum', '--listen-tcp', $SCRIPT);
my $ret = $? >> 8;

fail($ret);

sub fail {
	my $ret = shift;
	if (not defined $ret) {
		$ret = 1;
	}
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
	exit $ret;
}

sub clean_up {
	# make sure everything is owned by non-root
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
