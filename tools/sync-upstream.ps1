# 同步上游 Guise 更新，同时保留本地二改
#
# 用法：
#   .\tools\sync-upstream.ps1                  # 只检查：列出上游有哪些新版本（默认，不改任何东西）
#   .\tools\sync-upstream.ps1 -Apply           # 只 rebase（自动备份，冲突需手工解决）
#   .\tools\sync-upstream.ps1 -Apply -Build    # rebase + 编译
#   .\tools\sync-upstream.ps1 -Auto            # 全自动：rebase + 编译 + 安装到设备，任何一步失败自动回滚
#
# 设计原则：
#   - 上游（origin）永远只读，main 分支不提交
#   - 本地改动只以 commit 形式叠加在 tag 之上
#   - 版本号 = 900000 + 上游 versionCode，使内置更新器永远不会误判上游为新版

param(
    [switch]$Apply,
    [switch]$Build,
    [switch]$Install,
    [switch]$Auto,
    [string]$Tag = "",
    [string]$Branch = "local",
    [string]$Adb = "D:\Technology\shuaji\platform-tools\adb.exe",
    [string]$JavaHome = "C:\Users\ASUS\tools\jdk\jdk-17.0.18+8"
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

if ($Auto) { $Apply = $true; $Build = $true; $Install = $true }

function Info($m) { Write-Host $m }
function Step($m) { Write-Host ""; Write-Host "=== $m ===" -ForegroundColor Cyan }

Step "仓库状态"
Info "路径: $repo"
$cur = (git branch --show-current)
Info "当前分支: $cur"
if ($cur -ne $Branch) {
    Info "切换到 $Branch ..."
    git checkout $Branch
    if ($LASTEXITCODE -ne 0) { throw "切换分支失败" }
}
if ((git status --porcelain)) {
    throw "工作区不干净，请先提交或 stash，避免同步时丢改动"
}

Step "拉取上游 tags（需访问 GitHub，仅本进程内设置代理）"
$env:HTTP_PROXY = "http://127.0.0.1:7897"
$env:HTTPS_PROXY = "http://127.0.0.1:7897"
git fetch origin --tags --prune 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "git fetch 失败（检查网络/代理）" }
Info "已拉取"

# 本地二改的基线提交
$baseCommit = (git merge-base HEAD (git rev-list --max-parents=0 HEAD | Select-Object -First 1)) 2>$null
# 更可靠：找 local 分支上第一个 local: 提交的父提交
$firstLocal = (git log --format='%H %s' "$(git rev-parse HEAD)" | Select-String -Pattern 'local:' | Select-Object -Last 1)
if ($firstLocal) {
    $firstLocalSha = ($firstLocal -split ' ')[0]
    $baseCommit = (git rev-parse "$firstLocalSha^")
}
$baseTag = (git tag --points-at $baseCommit) | Select-Object -First 1

Info "二改基线提交: $baseCommit  (tag: $(if($baseTag){$baseTag}else{'未知'}))"

Step "本地二改提交（rebase 时会被重放）"
git log --oneline "$baseCommit..HEAD" | ForEach-Object { Info "  $_" }

Step "上游新版本"
$tags = git tag --list 'v*' --sort=v:refname | Where-Object { $_ -notmatch 'beta' }
$newer = @()
foreach ($t in $tags) {
    if ($t -eq $baseTag) { continue }
    git merge-base --is-ancestor $baseCommit $t 2>$null
    if ($LASTEXITCODE -eq 0) { $newer += $t }
}

if ($newer.Count -eq 0) {
    Info "已是最新，无需同步。"
    exit 0
}
$newer | ForEach-Object { Info "  $_" }
$newest = $newer[-1]
Info "最新可同步: $newest"
if (-not $Tag) { $Tag = $newest }

if (-not $Apply) {
    Step "检查模式"
    Info "未做任何修改。"
    Info "同步单步:  .\tools\sync-upstream.ps1 -Apply"
    Info "同步+编译: .\tools\sync-upstream.ps1 -Apply -Build"
    Info "全自动:    .\tools\sync-upstream.ps1 -Auto"
    exit 0
}

Step "备份"
git branch -f backup-local HEAD
Info "已备份到 backup-local"

Step "rebase 到 $Tag"
git rebase $Tag
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "存在冲突，需手工解决。常见冲突点：" -ForegroundColor Yellow
    Write-Host "  ModuleConfig.kt        —— 两边都在新增字段（两边都留）"
    Write-Host "  ModuleConfigState.kt   —— nonBooleanStates 列表（两边都留）"
    Write-Host "  ConfigEditor.kt        —— import 段（两边都留）"
    Write-Host "  version.properties     —— 保留 900000+ 偏移"
    Write-Host ""
    Write-Host "解决后:  git add -A ; git rebase --continue"
    Write-Host "放弃:    git rebase --abort"
    exit 1
}
Info "rebase 成功"

Step "更新版本号（900000 + 上游 versionCode）"
$up = (git show "${Tag}:app/version.properties" | Select-String -Pattern '^versionCode=(\d+)')
if ($up) {
    $upCode = [int]($up.Matches[0].Groups[1].Value)
    $newCode = 900000 + $upCode
    $verName = $Tag -replace '^v', ''
    $content = @"
#Tue Aug 25 23:12:43 CST 2026
versionCode=$newCode
versionName=$verName-local

prerelease=false
"@
    $content = $content -replace "`r`n", "`n"
    Set-Content "app/version.properties" -Value $content -Encoding utf8NoBOM -NoNewline
    Info "versionCode -> $newCode (上游 $upCode), versionName -> $verName-local"
    git add app/version.properties
    git commit -q -m "local: 同步到 $Tag，版本号 $newCode"
    Info "已提交版本号变更"
} else {
    Info "未能从 $Tag 读取 versionCode，跳过版本号更新"
}

if (-not $Build) {
    Step "完成（未编译）"
    Info "编译: .\gradlew.bat assembleDebug"
    exit 0
}

Step "编译"
$env:JAVA_HOME = $JavaHome
& "$repo\gradlew.bat" assembleDebug --no-daemon 2>&1 | Select-Object -Last 12
if ($LASTEXITCODE -ne 0) {
    Write-Host "编译失败，回滚到 backup-local" -ForegroundColor Red
    git reset --hard backup-local
    exit 1
}
Info "编译成功"

$apk = "$repo\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "未找到 APK: $apk" }

if (-not $Install) {
    Step "完成"
    Info "APK: $apk"
    exit 0
}

Step "安装到设备"
& $Adb push $apk /data/local/tmp/guise.apk 2>&1 | Out-Null
& $Adb shell su -c "pm install -r -t /data/local/tmp/guise.apk; rm -f /data/local/tmp/guise.apk" 2>&1

Step "完成"
Info "已同步到 $Tag 并安装。"
Info "注意：Guise 配置在进程启动时读取，需重启微信/目标应用才生效。"
