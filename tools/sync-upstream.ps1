# 同步上游 Guise 更新，同时保留本地二改
#
# 用法：
#   .\tools\sync-upstream.ps1              # 只检查，列出有哪些新版本（默认，不改任何东西）
#   .\tools\sync-upstream.ps1 -Apply       # 实际 rebase 到最新上游 tag
#   .\tools\sync-upstream.ps1 -Apply -Tag v2.1.5
#
# 原理：本地改动是 local 分支上的若干提交，rebase 到新的上游 tag 之上。
# 冲突点通常在 ModuleConfig / ModuleConfigState / ConfigEditor 三处，
# 因为上游和本地都在同一位置新增了字段。

param(
    [switch]$Apply,
    [string]$Tag = "",
    [string]$Branch = "local"
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

Write-Host "=== 仓库: $repo ==="
Write-Host "=== 当前分支: $(git branch --show-current) ==="

if ((git branch --show-current) -ne $Branch) {
    Write-Host "切换到 $Branch ..."
    git checkout $Branch
}

Write-Host ""
Write-Host "=== 拉取上游 tags（需要能访问 GitHub）==="
# 仅本次命令生效，不写入任何配置
$env:HTTP_PROXY = "http://127.0.0.1:7897"
$env:HTTPS_PROXY = "http://127.0.0.1:7897"
git fetch origin --tags --prune 2>&1 | Out-Null
Write-Host "已拉取"

# 找到 local 分支所基于的上游提交
$baseCommit = (git merge-base HEAD origin/HEAD 2>$null)
$baseTags = git tag --contains $baseCommit 2>$null | Select-Object -First 1
Write-Host ""
Write-Host "本地二改的基线提交: $baseCommit  (tag: $baseTags)"

# 列出本地所有二改提交
Write-Host ""
Write-Host "=== 本地二改提交（rebase 时会重放这些）==="
git log --oneline "$baseCommit..HEAD" 2>$null | ForEach-Object { Write-Host "  $_" }

# 找出比基线更新的 tag
Write-Host ""
Write-Host "=== 比基线更新的上游 tag ==="
$allTags = git tag --list 'v*' --sort=v:refname |
    Where-Object { $_ -notmatch 'beta' } |
    Where-Object { (git rev-list -n1 $_) -ne $baseCommit }

$newer = @()
foreach ($t in $allTags) {
    $isAncestor = git merge-base --is-ancestor $baseCommit $t 2>$null
    if ($LASTEXITCODE -eq 0 -and $t -ne $baseTags) { $newer += $t }
}

if ($newer.Count -eq 0) {
    Write-Host "  已是最新，没有需要同步的上游版本。"
    exit 0
}

$newer | ForEach-Object { Write-Host "  $_" }
$newest = $newer[-1]
Write-Host ""
Write-Host "最新可同步版本: $newest"

if (-not $Tag) { $Tag = $newest }

if (-not $Apply) {
    Write-Host ""
    Write-Host "这是检查模式，未做任何修改。"
    Write-Host "要同步请执行：  .\tools\sync-upstream.ps1 -Apply"
    Write-Host "然后解决冲突并重新构建：  .\gradlew.bat assembleDebug"
    exit 0
}

Write-Host ""
Write-Host "=== 开始 rebase 到 $Tag ==="
Write-Host "（已自动创建备份分支 backup-local）"
git branch -f backup-local HEAD

git rebase $Tag
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "存在冲突，需要手工解决。常见冲突点："
    Write-Host "  - ModuleConfig.kt        两边都在新增字段"
    Write-Host "  - ModuleConfigState.kt   nonBooleanStates 列表"
    Write-Host "  - ConfigEditor.kt        import 与 UI 段落"
    Write-Host ""
    Write-Host "解决后执行："
    Write-Host "  git add -A ; git rebase --continue"
    Write-Host ""
    Write-Host "放弃本次同步："
    Write-Host "  git rebase --abort ; git checkout local ; git reset --hard backup-local"
    exit 1
}

Write-Host ""
Write-Host "=== rebase 成功 ==="
git log --oneline -5

Write-Host ""
Write-Host "接下来重新构建并安装："
Write-Host "  .\gradlew.bat assembleDebug"
