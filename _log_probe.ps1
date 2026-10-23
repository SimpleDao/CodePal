$log = 'C:\Users\18383\AppData\Local\JetBrains\IntelliJIdea2026.1\log\idea.log'
Write-Output '===== 标记0行 ====='
Get-Content $log -Encoding UTF8 | Select-String -SimpleMatch 'marked 0 rows' | Select-Object -Last 5 | ForEach-Object { $_.LineNumber }
Write-Output '===== HistoryLoad 最近15条 ====='
Get-Content $log -Encoding UTF8 | Select-String -SimpleMatch '[HistoryLoad]' | Select-Object -Last 15 | ForEach-Object { if ($_.Line.Length -gt 180) { $_.Line.Substring(0,180) } else { $_.Line } }
Write-Output '===== UsageStats 最近5条 ====='
Get-Content $log -Encoding UTF8 | Select-String -SimpleMatch '[UsageStats]' | Select-Object -Last 5 | ForEach-Object { if ($_.Line.Length -gt 250) { $_.Line.Substring(0,250) } else { $_.Line } }
Write-Output '===== 上下文已满/爆满 ====='
Get-Content $log -Encoding UTF8 | Select-String -SimpleMatch '上下文已' | Select-Object -Last 5 | ForEach-Object { if ($_.Line.Length -gt 250) { $_.Line.Substring(0,250) } else { $_.Line } }
