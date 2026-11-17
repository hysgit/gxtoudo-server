param(
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')]
    [string]$Version,
    [ValidateRange(1024, 65535)]
    [int]$Port = 18765,
    [ValidatePattern('^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$')]
    [string]$BindAddress = '10.10.10.38'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '.')).Path
$tar = Join-Path $root 'target'
$sshConfig = Join-Path $env:USERPROFILE '.ssh/config'
$commit = (& git -C $root rev-parse --short HEAD).Trim()
if (!$Version) {
    $Version = "1.0.0-$commit"
}
$archive = Join-Path $tar "gxtudo-server-$Version.tar.gz"
$remoteArchive = "/tmp/gxtudo-server-$Version.tar.gz"
$remoteRoot = "/home/hy/deploy/gxtudo-server/releases/$Version"

if (!(Test-Path (Join-Path $root 'target/gxtodo-server.jar'))) {
    throw 'Build the Java JAR first with mvn -B -ntp -DskipTests package.'
}

$tarExe = Join-Path $env:WINDIR 'System32/tar.exe'
& $tarExe -czf $archive -C $root Dockerfile deploy/db2-deploy.sh deploy/healthcheck.sh target/gxtodo-server.jar
if ($LASTEXITCODE -ne 0) {
    throw 'Could not create the DB2 deployment archive.'
}

& scp.exe -F $sshConfig $archive "db2:$remoteArchive"
if ($LASTEXITCODE -ne 0) {
    throw 'Could not copy the release archive to DB2.'
}

$remoteCommand = "set -eu; mkdir -p '$remoteRoot'; tar -xzf '$remoteArchive' -C '$remoteRoot'; cd '$remoteRoot'; chmod 755 deploy/db2-deploy.sh deploy/healthcheck.sh; ./deploy/db2-deploy.sh '$Version' '$Port' '$BindAddress'; rm -f '$remoteArchive'"
& ssh.exe -F $sshConfig db2 $remoteCommand
if ($LASTEXITCODE -ne 0) {
    throw 'The DB2 deployment failed. Inspect the container with ssh db2 docker ps -a.'
}
