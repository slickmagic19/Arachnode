# Arachnode portable build: produces target\dist\Arachnode\Arachnode.exe
# with a bundled Java runtime — copy the folder anywhere and double-click, no install.
$ErrorActionPreference = "Stop"

$JavaHome = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) { $env:JAVA_HOME } else { "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot" }
$Mvn = "C:\Users\Achi\AppData\Local\Temp\opencode\maven\apache-maven-3.9.9\bin\mvn.cmd"
if (-not (Test-Path $Mvn)) { $Mvn = "mvn" }
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;" + $env:PATH
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

Write-Output "==> mvn package"
& $Mvn package -DskipTests -q
Write-Output "==> staging input jars"
$InDir = "$Root\target\portable-in"
$FxDir = "$Root\target\fxmods"
Remove-Item $InDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $FxDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $InDir | Out-Null
New-Item -ItemType Directory $FxDir | Out-Null
& $Mvn dependency:copy-dependencies "-DoutputDirectory=$InDir" -q
& "$JavaHome\bin\jar.exe" --create --file "$InDir\arachnode-app.jar" -C "$Root\target\classes" .
Get-ChildItem $InDir -Filter "javafx-*-win.jar" | ForEach-Object { Copy-Item $_.FullName $FxDir }

Write-Output "==> app icon (best effort)"
$IconArg = @()
try {
    Add-Type -AssemblyName System.Drawing
    $png = "$Root\src\main\resources\logo.png"
    $ico = "$Root\target\arachnode.ico"
    if ((Test-Path $png) -and -not (Test-Path $ico)) {
        $bmp = [System.Drawing.Bitmap]::FromFile($png)
        $thumb = New-Object System.Drawing.Bitmap($bmp, 256, 256)
        $h = $thumb.GetHicon()
        $icon = [System.Drawing.Icon]::FromHandle($h)
        $fs = [System.IO.File]::OpenWrite($ico)
        $icon.Save($fs); $fs.Close()
        $bmp.Dispose(); $thumb.Dispose()
        Write-Output "icon written"
    }
    if (Test-Path $ico) { $IconArg = @("--icon", $ico) }
} catch { Write-Output "icon skipped: $($_.Exception.Message)" }

Write-Output "==> jpackage app-image"
$Dist = "$Root\target\dist"
Remove-Item "$Dist\Arachnode" -Recurse -Force -ErrorAction SilentlyContinue
& "$JavaHome\bin\jpackage.exe" --type app-image --dest $Dist --name Arachnode `
  --app-version 1.0.0 --vendor Arachnode --description "Arachnode SEO Spider" `
  --input $InDir --main-jar arachnode-app.jar --main-class com.arachnode.App `
  --module-path $FxDir --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base,java.net.http,jdk.crypto.ec @IconArg
Write-Output ""
Write-Output "Portable app folder: $Dist\Arachnode\Arachnode.exe"
Get-Item "$Dist\Arachnode\Arachnode.exe" | Select-Object Name, Length

# ---- single-file portable exe (silent self-extractor, no install) ----
Write-Output "==> single-file exe"
$Stage = "$Root\target\sfx-stage"
Remove-Item $Stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $Stage | Out-Null
Copy-Item "$Dist\Arachnode\*" $Stage -Recurse -Force
Compress-Archive -LiteralPath "$Stage\app", "$Stage\runtime", "$Stage\Arachnode.exe" -DestinationPath "$Stage\bundle.zip" -Force
$BuildId = Get-Date -Format "yyyyMMdd-HHmm"
Set-Content -LiteralPath "$Stage\bundle.id" -Value $BuildId -NoNewline -Encoding Ascii
$RunCmd = "@echo off`r`nset T=%TEMP%\ArachnodePortable`r`nset NEED=1`r`nif exist `"%T%\bundle.id`" (for /f `"usebackq delims=`" %%I in (`"%T%\bundle.id`") do if `"%%I`"==`"$BuildId`" set NEED=0)`r`nif `%NEED%`==`1` (rmdir /s /q `"%T%`" 2>nul & mkdir `"%T%`" & powershell -NoProfile -ExecutionPolicy Bypass -Command `"Expand-Archive -LiteralPath '%~dp0bundle.zip' -DestinationPath '%T%' -Force`" & copy /y `"%~dp0bundle.id`" `"%T%\bundle.id`" >nul)`r`nstart `"`" `"%T%\Arachnode.exe`""
Set-Content -LiteralPath "$Stage\run.cmd" -Value $RunCmd -Encoding Ascii
$Tgt = "$Root\target"
Remove-Item "$Tgt\Arachnode-Portable.exe" -Force -ErrorAction SilentlyContinue
$sed = @("[Version]", 'Signature="$Chicago$"', "Class=IEXPRESS", "SEDVersion=3", "[Options]", "PackagePurpose=InstallApp", "ShowInstallProgramWindow=0", "HideExtractAnimation=1", "UseLongFileName=1", "InsideCompressed=0", "CAB_FixedSize=0", "CAB_ResvCodePaging=0", "RebootMode=N", "InstallPrompt=", "DisplayLicense=", "FinishMessage=", "TargetName=$Tgt\Arachnode-Portable.exe", "FriendlyName=Arachnode SEO Spider", "AppLaunched=run.cmd", "PostInstallCmd=<None>", "AdminQuietInstCmd=", "UserQuietInstCmd=", "SourceFiles=SourceFiles", "[Strings]", "[SourceFiles]", "SourceFiles0=$Stage\", "[SourceFiles0]", "bundle.zip=", "run.cmd=", "bundle.id=")
Set-Content -LiteralPath "$Tgt\portable.sed" -Value ($sed -join "`r`n") -Encoding Ascii
# NOTE: bare iexpress silently no-ops when nested — run waited so the exe is actually produced.
Start-Process "$env:SystemRoot\System32\iexpress.exe" -ArgumentList "/N", "$Tgt\portable.sed" -Wait -NoNewWindow
Write-Output ""
Write-Output "Single-file portable exe: $Tgt\Arachnode-Portable.exe"
Get-Item "$Tgt\Arachnode-Portable.exe" | Select-Object Name, Length
