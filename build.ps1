param([switch]$Check)
$ErrorActionPreference = 'Stop'
# 优先使用已有 JDK，否则寻找 Android Studio 自带的运行时。
if (-not $env:JAVA_HOME) {
    $studioEntries = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'Android Studio' }
    foreach ($studioEntry in $studioEntries) {
        $studioExe = ($studioEntry.DisplayIcon -replace ',\d+$', '').Trim('"')
        if ($studioExe) {
            $runtimePath = Join-Path (Split-Path (Split-Path $studioExe)) 'jbr'
            if (Test-Path (Join-Path $runtimePath 'bin\java.exe')) { $env:JAVA_HOME = $runtimePath; break }
        }
    }
}
if (-not $env:JAVA_HOME) { throw '未找到 JDK。请设置 JAVA_HOME，或在 Android Studio 中构建。' }
Push-Location $PSScriptRoot
try {
    $gradleTasks = @(':app:assembleDebug')
    if ($Check) { $gradleTasks += @(':app:testDebugUnitTest', ':app:lintDebug') }
    & '.\gradlew.bat' @gradleTasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw '构建失败，请查看上方日志。' }
    Write-Output "APK：$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
} finally { Pop-Location }
