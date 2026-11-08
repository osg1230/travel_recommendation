# DB 자동 테스트(RUN_SQLSERVER_TESTS=true)를 실행한다. CI는 DB 테스트를 건너뛰므로 커밋 전에 이 PC에서 돌린다.
# 사용법: .\scripts\run-db-tests.ps1            (전체 clean verify)
#         .\scripts\run-db-tests.ps1 SavedItinerary*Tests   (일부 테스트만)
param([string]$Tests = '')

Set-Location (Split-Path $PSScriptRoot -Parent)

# 웹 서버(8080)를 켜 둔 채 돌리면 같은 DB를 함께 써서 잠금 대기나 개수 차이로 가짜 실패가 날 수 있다.
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host '8080 포트에서 서버가 실행 중이에요. 서버를 끄고 다시 실행해 주세요.'
    exit 1
}

# Windows 인증 DLL 경로(Maven 로컬 저장소를 옮겼다면 실제 폴더로 바꾼다).
$jdbcAuthPath = Join-Path $env:USERPROFILE '.m2\repository\com\microsoft\sqlserver\mssql-jdbc_auth\13.4.0.x64'
$env:RUN_SQLSERVER_TESTS = 'true'

if ($Tests) {
    .\mvnw.cmd test "-Dtest=$Tests" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine=-Djava.library.path=$jdbcAuthPath"
} else {
    .\mvnw.cmd clean verify "-DargLine=-Djava.library.path=$jdbcAuthPath"
}
exit $LASTEXITCODE
