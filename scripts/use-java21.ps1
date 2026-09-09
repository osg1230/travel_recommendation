param([string]$JdkPath)

$ErrorActionPreference = 'Stop'
if (-not $JdkPath) {
    $jdkFolder = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -like 'jdk-21*' | Sort-Object Name -Descending | Select-Object -First 1
    if ($jdkFolder) { $JdkPath = $jdkFolder.FullName }
}
if (-not $JdkPath) { throw 'JDK 21을 설치한 뒤 -JdkPath로 설치 폴더를 지정하세요.' }
$javaExecutable = Join-Path $JdkPath 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw '지정한 폴더에 bin/java.exe가 없습니다.' }
$javaVersion = (& $javaExecutable -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "21[.\"]') {
    throw 'Java 21 설치 폴더를 지정해야 합니다.'
}
# 현재 터미널에서만 적용해 다른 프로젝트의 Java 설정은 유지한다.
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkPath).Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Write-Output "JAVA_HOME=$env:JAVA_HOME"
Write-Output $javaVersion.Trim()
