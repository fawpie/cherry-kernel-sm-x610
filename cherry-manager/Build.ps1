param(
    [Parameter(Mandatory)] [string] $BuildTools,
    [Parameter(Mandatory)] [string] $AndroidJar
)

$ErrorActionPreference = 'Stop'
$BuildTools = (Resolve-Path -LiteralPath $BuildTools).Path
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
$source = Join-Path $PSScriptRoot 'app'
$work = Join-Path $env:TEMP ('cherry-manager-build-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $work 'classes'
$keyDir = Join-Path $env:LOCALAPPDATA 'Fawpie\CherryManager'
New-Item -ItemType Directory -Force -Path $classes,$keyDir | Out-Null
$keystore = Join-Path $keyDir 'signing.p12'
$protectedPass = Join-Path $keyDir 'signing-password.dpapi'

function Invoke-BuildTool {
    param([string] $Program, [string[]] $Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

if (Test-Path -LiteralPath $protectedPass) {
    $pass = Get-Content -LiteralPath $protectedPass -Raw | ConvertTo-SecureString
} else {
    if (Test-Path -LiteralPath $keystore) { throw 'Signing password is missing.' }
    $pass = ConvertTo-SecureString ([Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))) -AsPlainText -Force
    $pass | ConvertFrom-SecureString | Set-Content -LiteralPath $protectedPass -NoNewline
}

try {
    $env:CHERRY_SIGNING_PASSWORD = [System.Net.NetworkCredential]::new('', $pass).Password
    if (!(Test-Path -LiteralPath $keystore)) {
        Invoke-BuildTool keytool @('-genkeypair','-storetype','PKCS12','-keystore',$keystore,
            '-storepass:env','CHERRY_SIGNING_PASSWORD','-keypass:env','CHERRY_SIGNING_PASSWORD',
            '-alias','cherry-manager','-dname','CN=Cherry Manager','-keyalg','RSA','-keysize','3072','-validity','10000')
    }
    $javaFiles = (Get-ChildItem -LiteralPath $source -Filter '*.java').FullName
    Invoke-BuildTool javac (@('-encoding','UTF-8','--release','11','-cp',$AndroidJar,'-d',$classes) + $javaFiles)
    $classFiles = (Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class').FullName
    Invoke-BuildTool java (@('-cp',(Join-Path $BuildTools 'lib\d8.jar'),'com.android.tools.r8.D8',
        '--min-api','33','--lib',$AndroidJar,'--output',$work) + $classFiles)
    $aapt = Join-Path $BuildTools 'aapt.exe'
    $unsigned = Join-Path $work 'unsigned.apk'
    Invoke-BuildTool $aapt @('package','-f','-M',(Join-Path $source 'AndroidManifest.xml'),
        '-S',(Join-Path $source 'res'),'-A',(Join-Path $source 'assets'),'-I',$AndroidJar,'-F',$unsigned)
    Push-Location -LiteralPath $work
    try { Invoke-BuildTool $aapt @('add',$unsigned,'classes.dex') } finally { Pop-Location }
    $aligned = Join-Path $work 'aligned.apk'
    $zipalign = Join-Path $BuildTools 'zipalign.exe'
    Invoke-BuildTool $zipalign @('-f','4',$unsigned,$aligned)
    $signer = Join-Path $BuildTools 'lib\apksigner.jar'
    $apk = Join-Path $PSScriptRoot 'Cherry-Manager.apk'
    Invoke-BuildTool java @('--enable-native-access=ALL-UNNAMED','-jar',$signer,'sign',
        '--ks',$keystore,'--ks-pass','env:CHERRY_SIGNING_PASSWORD','--v4-signing-enabled','false',
        '--out',$apk,$aligned)
    Invoke-BuildTool $zipalign @('-c','4',$apk)
    Invoke-BuildTool java @('--enable-native-access=ALL-UNNAMED','-jar',$signer,'verify',$apk)
    Get-Item -LiteralPath $apk | Select-Object FullName,Length
} finally {
    Remove-Item Env:CHERRY_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
