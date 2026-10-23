$log = 'C:\Users\18383\AppData\Local\JetBrains\IntelliJIdea2026.1\log\idea.log'
Write-Output '===== CompressTag ====='
Get-Content $log -Encoding UTF8 | Select-String -SimpleMatch 'Compress]' | Select-Object -Last 25 | ForEach-Object { if ($_.Line.Length -gt 220) { $_.Line.Substring(0,220) } else { $_.Line } }
Write-Output '===== CompressedOK ====='
Get-Content $log -Encoding UTF8 | Select-String -SimpleMatch 'savedCount' | Select-Object -Last 5 | ForEach-Object { if ($_.Line.Length -gt 220) { $_.Line.Substring(0,220) } else { $_.Line } }
