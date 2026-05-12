<#
.SYNOPSIS
    Build and push the Crypto Investment Advisor Bot image to Docker Hub.

.DESCRIPTION
    Собирает Docker-образ из текущего репозитория и пушит его в Docker Hub
    под тегами `<repo>:<version>` и `<repo>:latest`.

    Требуется: Docker Desktop, выполненный `docker login`.

.PARAMETER Repo
    Docker Hub репозиторий в формате <user>/<image>. По умолчанию
    sergger/crypto-advisor-bot.

.PARAMETER Version
    Версионный тег (например, 1.0.0 или $(git describe --tags)). По умолчанию
    берётся короткий git commit hash, а если git недоступен — текущая дата.

.EXAMPLE
    .\scripts\Publish-DockerImage.ps1
    .\scripts\Publish-DockerImage.ps1 -Version 1.2.0
    .\scripts\Publish-DockerImage.ps1 -Repo myorg/crypto-advisor-bot -Version 1.2.0
#>
[CmdletBinding()]
param(
    [string]$Repo = 'sergger/crypto-advisor-bot',
    [string]$Version
)

$ErrorActionPreference = 'Stop'

if (-not $Version) {
    try {
        $Version = (git rev-parse --short HEAD 2>$null).Trim()
    } catch { }
    if (-not $Version) { $Version = Get-Date -Format 'yyyyMMdd-HHmm' }
}

Write-Host "Building $Repo`:$Version  (and tagging as :latest)" -ForegroundColor Cyan

docker build -t "$Repo`:$Version" -t "$Repo`:latest" .
if ($LASTEXITCODE -ne 0) { throw "docker build failed" }

Write-Host "Pushing $Repo`:$Version" -ForegroundColor Cyan
docker push "$Repo`:$Version"
if ($LASTEXITCODE -ne 0) { throw "docker push ($Version) failed" }

Write-Host "Pushing $Repo`:latest" -ForegroundColor Cyan
docker push "$Repo`:latest"
if ($LASTEXITCODE -ne 0) { throw "docker push (latest) failed" }

Write-Host ''
Write-Host "Published:" -ForegroundColor Green
Write-Host "  $Repo`:$Version"
Write-Host "  $Repo`:latest"
Write-Host ''
Write-Host "On the Linux server:" -ForegroundColor Yellow
Write-Host "  docker compose -f docker-compose.prod.yml pull"
Write-Host "  docker compose -f docker-compose.prod.yml up -d"
