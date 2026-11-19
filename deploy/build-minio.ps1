$ErrorActionPreference = 'Stop'
$revision = '9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a'
$tag = 'RELEASE.2025-10-15T17-29-55Z'
$root = Split-Path $PSScriptRoot -Parent
$source = Join-Path $root 'target/minio-source'
New-Item -ItemType Directory -Force (Join-Path $root 'target') | Out-Null
if (!(Test-Path $source)) {
    git clone --depth 1 --branch $tag https://github.com/minio/minio.git $source
    if ($LASTEXITCODE -ne 0) { throw 'MinIO source download failed' }
}
if ((git -C $source rev-parse HEAD).Trim() -ne $revision) { throw 'Unexpected MinIO source revision' }
$previousOs = $env:GOOS
$previousArch = $env:GOARCH
$previousCgo = $env:CGO_ENABLED
try {
    $env:GOOS = 'linux'
    $env:GOARCH = 'amd64'
    $env:CGO_ENABLED = '0'
    Push-Location $source
    try {
        go build -trimpath -ldflags "-s -w -X github.com/minio/minio/cmd.Version=2025-10-15T17:29:55Z -X github.com/minio/minio/cmd.ReleaseTag=$tag -X github.com/minio/minio/cmd.CommitID=$revision" -o (Join-Path $root 'target/minio-linux-amd64') .
        if ($LASTEXITCODE -ne 0) { throw 'MinIO compilation failed' }
    } finally { Pop-Location }
} finally {
    $env:GOOS = $previousOs
    $env:GOARCH = $previousArch
    $env:CGO_ENABLED = $previousCgo
}
Get-FileHash (Join-Path $root 'target/minio-linux-amd64') -Algorithm SHA256
