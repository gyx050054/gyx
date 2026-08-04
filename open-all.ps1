param([switch]$NoPause)

$dirs = @(
    'C:\Users\15079\Desktop\物联网灌溉系统',
    'C:\Users\15079\Desktop\q\物联网灌溉系统第一代第三版',
    'C:\Users\15079\Desktop\q\kotlin-demo',
    'C:\Users\15079\Desktop\java\webtest\task-service',
    'C:\Users\15079\ThingsBoard'
)

Write-Host '============================================'
Write-Host '  智能灌溉物联网系统 - 一键打开全部目录'
Write-Host '============================================'
Write-Host ''

foreach ($d in $dirs) {
    if (Test-Path $d) {
        Start-Process explorer.exe -ArgumentList "`"$d`"" | Out-Null
        Write-Host "已打开: $d"
    } else {
        Write-Host "路径不存在: $d" -ForegroundColor Yellow
    }
}

Write-Host ''
Write-Host '共打开 ' $dirs.Count ' 个目录窗口'
if (-not $NoPause) {
    Write-Host '按任意键退出...'
    $null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
}
