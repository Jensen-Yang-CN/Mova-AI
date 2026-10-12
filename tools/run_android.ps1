<#
本机 Android 编译与实机运行入口。只读取已安装的工具，不保存设备序列号。
先在另一个终端启动 scene_ai_server，再使用 -Install -ReverseLocalServer 联调。
GGUF 通过 App 设置导入，脚本不把权重打包到 APK。
#>
param(
    [string]$SdkRoot = 'E:\Efilefolder\Android\sdk',
    [string]$JavaPath = 'E:\Efilefolder\JDK-20\jdk-20',
    [string]$GradlePath = 'E:\Efilefolder\Android\gradle\gradle-8.13',
    [switch]$Install,
    [switch]$ReverseLocalServer,
    [switch]$Smoke
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradleExecutable = Join-Path $GradlePath 'bin\gradle.bat'
$adbExecutable = Join-Path $SdkRoot 'platform-tools\adb.exe'
foreach ($required in @($gradleExecutable, $adbExecutable, (Join-Path $JavaPath 'bin\java.exe'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "缺少工具：$required" }
}
$env:JAVA_HOME = $JavaPath
$env:ANDROID_HOME = $SdkRoot
$env:GRADLE_USER_HOME = Join-Path (Split-Path $SdkRoot -Parent) 'gradle-cache'
Push-Location (Join-Path $repoRoot 'SceneAi_App')
try {
    $tasks = @(':app:assembleDebug')
    if ($Smoke) { $tasks += ':app:assembleDebugAndroidTest' }
    & $gradleExecutable @tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android 编译失败，请查看上方诊断' }
    if ($Install -or $Smoke) {
        & $adbExecutable install -r 'app\build\outputs\apk\debug\app-debug.apk'
        if ($LASTEXITCODE -ne 0) { throw 'APK 安装失败；检查手机的 USB 安装确认页面' }
        if ($ReverseLocalServer) { & $adbExecutable reverse tcp:8000 tcp:8000 }
        & $adbExecutable shell am start -n com.mova.sceneai/.MainActivity
    }
    if ($Smoke) {
        & $adbExecutable install -r 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
        if ($LASTEXITCODE -ne 0) { throw '测试包安装失败' }
        # am instrument 的进程退出码不能代表 JUnit 成败，必须检查报告正文。
        $report = & $adbExecutable shell am instrument -w -r -e class com.mova.sceneai.data.edge.EdgeDeviceSmokeTest com.mova.sceneai.test/androidx.test.runner.AndroidJUnitRunner
        $report | Write-Output
        $reportText = $report -join "`n"
        if ($reportText -notmatch 'OK \(1 test\)' -or $reportText -match 'INSTRUMENTATION_STATUS_CODE: -[34]') {
            throw '真机冒烟未通过或被跳过；请核对模型导入状态及报告'
        }
    }
} finally { Pop-Location }
