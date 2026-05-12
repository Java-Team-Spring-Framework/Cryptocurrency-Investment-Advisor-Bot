<#
.SYNOPSIS
    Secure helper to call admin HTTP API of the Crypto Investment Advisor Bot.

.DESCRIPTION
    Spring Security на стороне сервера требует HTTP Basic-аутентификации для
    /users и /admin/**. Этот скрипт позволяет вызывать защищённые эндпоинты
    без явной передачи пароля в командной строке (чтобы он не попадал
    в историю оболочки, в `ps`-логи и т. п.).

    Поддерживаются три источника учётных данных (по убыванию приоритета):
      1. -Credential <PSCredential>            -- передан явно в параметрах
      2. $env:ADMIN_USERNAME / $env:ADMIN_PASSWORD
      3. Интерактивный запрос через Get-Credential (пароль маскируется)

.PARAMETER BaseUrl
    Базовый URL API. По умолчанию http://localhost:8081 (порт,
    на который docker-compose пробрасывает контейнер).

.PARAMETER Path
    Путь эндпоинта. По умолчанию /users.

.PARAMETER Credential
    Готовый PSCredential (например, загруженный из защищённого хранилища).

.EXAMPLE
    # Интерактивный запрос имени/пароля — пароль не показывается на экране
    .\scripts\Invoke-AdminApi.ps1

.EXAMPLE
    # Креды из переменных окружения
    $env:ADMIN_USERNAME = 'admin'
    $env:ADMIN_PASSWORD = 'super-secret'
    .\scripts\Invoke-AdminApi.ps1 -Path /users

.EXAMPLE
    # Другой эндпоинт
    .\scripts\Invoke-AdminApi.ps1 -Path /healthcheck -BaseUrl http://localhost:8081
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$Path = '/users',
    [System.Management.Automation.PSCredential]$Credential
)

$ErrorActionPreference = 'Stop'

function Get-AdminCredential {
    param([System.Management.Automation.PSCredential]$Explicit)

    if ($Explicit) { return $Explicit }

    $envUser = $env:ADMIN_USERNAME
    $envPass = $env:ADMIN_PASSWORD
    if ($envUser -and $envPass) {
        $secure = ConvertTo-SecureString $envPass -AsPlainText -Force
        return New-Object System.Management.Automation.PSCredential($envUser, $secure)
    }

    return Get-Credential -Message 'Введите учётные данные администратора (HTTP Basic)' -UserName 'admin'
}

$cred = Get-AdminCredential -Explicit $Credential

$uri = "$BaseUrl$Path"
Write-Verbose "GET $uri as $($cred.UserName)"

# Invoke-RestMethod сам формирует заголовок Authorization из PSCredential —
# пароль никогда не попадает в аргументы процесса.
try {
    Invoke-RestMethod -Method Get -Uri $uri -Authentication Basic -Credential $cred
}
catch [System.Management.Automation.ParameterBindingException] {
    # PowerShell 5.1 не знает параметр -Authentication, собираем Basic-заголовок руками
    $pair = "$($cred.UserName):$($cred.GetNetworkCredential().Password)"
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
    Invoke-RestMethod -Method Get -Uri $uri -Headers @{ Authorization = "Basic $b64" }
}
