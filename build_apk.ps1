param(
    [ValidateSet("assembleDebug", "assembleRelease", "testDebugUnitTest")]
    [string]$Task = "assembleDebug"
)

$ErrorActionPreference = "Stop"
$AndroidPlayer = "D:\Unity\Editor\2022.3.62f1\Editor\Data\PlaybackEngines\AndroidPlayer"
$env:JAVA_HOME = "$AndroidPlayer\OpenJDK"
$env:ANDROID_HOME = "$AndroidPlayer\SDK"
$env:ANDROID_SDK_ROOT = "$AndroidPlayer\SDK"

$JavaExe = "$env:JAVA_HOME\bin\java.exe"
$GradleJar = "$AndroidPlayer\Tools\gradle\lib\gradle-launcher-7.5.1.jar"

Push-Location $PSScriptRoot
try {
    & $JavaExe -Xmx2048m -cp $GradleJar org.gradle.launcher.GradleMain $Task --no-daemon --console=plain
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
