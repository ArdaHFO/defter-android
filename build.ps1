[CmdletBinding()]
param(
    [ValidateSet('Build', 'Test', 'Check', 'Native')][string]$Action = 'Check',
    [string]$SocketDirectory = '',
    [string]$GradleExecutable = ''
)

$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$localDirectory = Join-Path $projectDirectory '.local'
New-Item -ItemType Directory -Force -Path $localDirectory | Out-Null

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'JDK 17 kurulu olmalı ve JAVA_HOME bu klasörü göstermeli.'
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    throw 'Android SDK 35 ve Build Tools 35.0.0 kurulu olmalı. ANDROID_HOME ayarını yapın.'
}

# Windows JDK uses AF_UNIX for local process communication. Long TEMP paths can break it.
if (-not $SocketDirectory) {
    $SocketDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Temp\defter-socket'
}
if ($SocketDirectory.Length -gt 85) {
    throw 'Kısa bir SocketDirectory belirtin. Bu yol 85 karakteri aşmamalı.'
}
New-Item -ItemType Directory -Force -Path $SocketDirectory | Out-Null

$previousGradleDirectory = $env:GRADLE_USER_HOME
$previousAndroidDirectory = $env:ANDROID_USER_HOME
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:GRADLE_USER_HOME = Join-Path $localDirectory 'gradle-home'
    $env:ANDROID_USER_HOME = Join-Path $localDirectory 'android-user'
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djdk.net.unixdomain.tmpdir=$SocketDirectory".Trim()
    $keyFile = Join-Path $localDirectory 'debug.keystore'
    if ($Action -eq 'Build' -and -not (Test-Path -LiteralPath $keyFile)) {
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $keyFile -storepass android -alias androiddebugkey -keypass android -dname 'CN=Defter Development,O=Defter,C=TR' -keyalg RSA -keysize 2048 -validity 10000 -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Geliştirme imzası oluşturulamadı.' }
    }
    if (-not $GradleExecutable) { $GradleExecutable = Join-Path $projectDirectory 'gradlew.bat' }
    $tasks = @(switch ($Action) {
        'Build' { @(':app:assembleDebug') }
        'Test' { @(':app:testDebugUnitTest') }
        'Check' { @(':app:testDebugUnitTest', ':app:lintDebug') }
        'Native' { @(':app:externalNativeBuildDebug') }
    })
    & $GradleExecutable -p $projectDirectory @tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle başarısız oldu. Çıkış kodu $LASTEXITCODE" }
} finally {
    $env:GRADLE_USER_HOME = $previousGradleDirectory
    $env:ANDROID_USER_HOME = $previousAndroidDirectory
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
}
