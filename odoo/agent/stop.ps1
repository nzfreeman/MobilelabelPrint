$ErrorActionPreference = 'Stop'
Stop-ScheduledTask -TaskName 'NZQueenBee Label Connector'
Write-Output '연결 프로그램을 중지했습니다. Odoo의 새 요청은 대기합니다.'
