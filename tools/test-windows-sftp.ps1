# Exercise Windows OpenSSH's SFTP implementation over stdio, using only test-owned
# paths. The native server does not listen on a port or use an installed SSH service.
[CmdletBinding()]
param(
    [string]$OpenSshDirectory = (Join-Path $env:SystemRoot 'System32/OpenSSH')
)

$ErrorActionPreference = 'Stop'
$client = (Resolve-Path -LiteralPath (Join-Path $OpenSshDirectory 'sftp.exe')).Path
$server = (Resolve-Path -LiteralPath (Join-Path $OpenSshDirectory 'sftp-server.exe')).Path
$temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Join-Path $temp ('mangossh-sftp-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null

try {
    $fixture = (Resolve-Path -LiteralPath $fixture).Path
    $remote = (New-Item -ItemType Directory -Path (Join-Path $fixture 'remote')).FullName
    $payload = [byte[]](0..255)
    [IO.File]::WriteAllBytes((Join-Path $fixture 'payload.bin'), $payload)
    [IO.File]::WriteAllBytes((Join-Path $remote 'staging.part'), $payload[0..63])
    $existing = Join-Path $remote 'existing.bin'
    [IO.File]::WriteAllBytes($existing, $payload)
    $acl = (Get-Acl -LiteralPath $existing).Sddl
    [IO.File]::WriteAllBytes((Join-Path $fixture 'replacement.bin'), $payload[0..15])

    Push-Location -LiteralPath $fixture
    try {
        @(
            'cd remote'
            'reput payload.bin staging.part'
            'rename staging.part new.bin'
            'put replacement.bin existing.bin'
            'get new.bin download.bin'
            'bye'
        ) | & $client -q -D $server -b -
        if ($LASTEXITCODE -ne 0) { throw 'Windows OpenSSH SFTP commands failed.' }
    } finally {
        Pop-Location
    }

    $download = [IO.File]::ReadAllBytes((Join-Path $fixture 'download.bin'))
    if ([Convert]::ToBase64String($download) -cne [Convert]::ToBase64String($payload)) {
        throw 'The resumed upload did not preserve the payload.'
    }
    if ((Test-Path -LiteralPath (Join-Path $remote 'staging.part')) -or (Get-Item -LiteralPath $existing).Length -ne 16) {
        throw 'Staging or in-place replacement left unexpected output.'
    }
    if ((Get-Acl -LiteralPath $existing).Sddl -cne $acl) {
        throw 'The in-place replacement changed the original Windows ACL.'
    }
    Write-Output 'Windows OpenSSH resume, rename, and ACL-preserving replacement passed.'
} finally {
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    if ((Split-Path -Parent $resolved).TrimEnd('\') -ne $temp.TrimEnd('\') -or
        (Split-Path -Leaf $resolved) -notmatch '^mangossh-sftp-[a-f0-9]{32}$') {
        throw 'The test directory failed the cleanup containment check.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
