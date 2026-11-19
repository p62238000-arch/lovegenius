# 一键准备 Gradle 8.7（走腾讯云镜像，约 130MB，只跑一次）
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\bootstrap_gradle.ps1
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$dist = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\lovegenius"
$zip  = "$dist\gradle-8.7-bin.zip"
$ext  = "$dist\extract\gradle-8.7"

if (Test-Path "$ext\bin\gradle.bat") {
    Write-Host "[+] Gradle 8.7 已就绪：$ext" -ForegroundColor Green
    exit 0
}

New-Item -ItemType Directory -Force -Path $dist | Out-Null
if (-not (Test-Path $zip) -or (Get-Item $zip).Length -lt 50MB) {
    Write-Host "下载 gradle-8.7-bin.zip（腾讯云镜像）…"
    Invoke-WebRequest -Uri 'https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip' `
        -OutFile $zip -UseBasicParsing
}
Write-Host ("下载完成：" + [math]::Round((Get-Item $zip).Length / 1MB, 1) + " MB")

Add-Type -AssemblyName System.IO.Compression.FileSystem
$tmp = "$dist\_tmp"
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
[System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmp)
if (Test-Path "$dist\extract") { Remove-Item "$dist\extract" -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$dist\extract" | Out-Null
Move-Item "$tmp\gradle-8.7" "$dist\extract\gradle-8.7"
Remove-Item $tmp -Recurse -Force

if (-not (Test-Path "$ext\bin\gradle.bat")) {
    Write-Host "[!] 解压失败" -ForegroundColor Red
    exit 1
}
Write-Host "[+] 完成：$ext" -ForegroundColor Green
