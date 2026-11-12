param(
    [Parameter(Mandatory=$true)][string]$PythonPath,
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'config.json')
)
$ErrorActionPreference = 'Stop'
$agentPath = Join-Path $PSScriptRoot 'nzqb_print_agent.py'
if (!(Test-Path -LiteralPath $PythonPath) -or !(Test-Path -LiteralPath $ConfigPath)) {
    throw 'Python 실행 파일과 config.json을 먼저 준비하세요.'
}
$account = [Security.Principal.WindowsIdentity]::GetCurrent().Name
# Keep the API key readable only by this Windows account and SYSTEM.
& icacls $ConfigPath /inheritance:r /grant:r "${account}:(R)" '*S-1-5-18:(F)' | Out-Null
if ($LASTEXITCODE -ne 0) { throw '연결 설정 파일의 접근 권한을 제한하지 못했습니다.' }
$arguments = '"' + $agentPath + '" --config "' + (Resolve-Path -LiteralPath $ConfigPath).Path + '"'
$pythonw = Join-Path (Split-Path -Parent $PythonPath) 'pythonw.exe'
$executable = if (Test-Path -LiteralPath $pythonw) { $pythonw } else { $PythonPath }
$action = New-ScheduledTaskAction -Execute $executable -Argument $arguments -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $account
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName 'NZQueenBee Label Connector' -Action $action -Trigger $trigger -Settings $settings -Description '작업장 Godex 라벨 인쇄 연결 프로그램' -User $account -RunLevel Limited -Force | Out-Null
Start-ScheduledTask -TaskName 'NZQueenBee Label Connector'
Write-Output '연결 프로그램을 시작했습니다. Windows 로그인 시 자동으로 실행됩니다.'
