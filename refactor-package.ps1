# PowerShell script to refactor package name from com.example.onetap to com.onetap.app

$oldPackage = "com.example.onetap"
$newPackage = "com.onetap.app"
$oldPath = "com/example/onetap"
$newPath = "com/onetap/app"

Write-Host "Starting package refactoring..." -ForegroundColor Green
Write-Host "Old package: $oldPackage" -ForegroundColor Yellow
Write-Host "New package: $newPackage" -ForegroundColor Yellow

# Step 1: Update all Kotlin and Java files
Write-Host "`nStep 1: Updating package declarations in source files..." -ForegroundColor Cyan
$sourceFiles = Get-ChildItem -Path "app/src" -Include "*.kt","*.java" -Recurse
foreach ($file in $sourceFiles) {
    $content = Get-Content $file.FullName -Raw
    if ($content -match $oldPackage) {
        $newContent = $content -replace [regex]::Escape($oldPackage), $newPackage
        Set-Content -Path $file.FullName -Value $newContent -NoNewline
        Write-Host "  Updated: $($file.Name)" -ForegroundColor Gray
    }
}

# Step 2: Update AndroidManifest.xml files
Write-Host "`nStep 2: Updating AndroidManifest.xml files..." -ForegroundColor Cyan
$manifestFiles = Get-ChildItem -Path "app/src" -Include "AndroidManifest.xml" -Recurse
foreach ($file in $manifestFiles) {
    $content = Get-Content $file.FullName -Raw
    if ($content -match $oldPackage) {
        $newContent = $content -replace [regex]::Escape($oldPackage), $newPackage
        Set-Content -Path $file.FullName -Value $newContent -NoNewline
        Write-Host "  Updated: $($file.FullName)" -ForegroundColor Gray
    }
}

# Step 3: Move directory structure
Write-Host "`nStep 3: Moving directory structure..." -ForegroundColor Cyan
$sourceDirs = Get-ChildItem -Path "app/src" -Directory -Recurse | Where-Object { $_.FullName -like "*$oldPath*" }

foreach ($dir in $sourceDirs) {
    $newDir = $dir.FullName -replace [regex]::Escape($oldPath), $newPath
    $newDirParent = Split-Path $newDir -Parent
    
    # Create parent directories if they don't exist
    if (-not (Test-Path $newDirParent)) {
        New-Item -ItemType Directory -Path $newDirParent -Force | Out-Null
    }
    
    # Move the directory
    if (-not (Test-Path $newDir)) {
        Write-Host "  Moving: $($dir.FullName) -> $newDir" -ForegroundColor Gray
        Move-Item -Path $dir.FullName -Destination $newDir -Force
    }
}

# Step 4: Clean up old empty directories
Write-Host "`nStep 4: Cleaning up old directories..." -ForegroundColor Cyan
$oldDirs = Get-ChildItem -Path "app/src" -Directory -Recurse | Where-Object { $_.FullName -like "*com/example*" } | Sort-Object -Property FullName -Descending
foreach ($dir in $oldDirs) {
    if ((Get-ChildItem $dir.FullName -Force | Measure-Object).Count -eq 0) {
        Write-Host "  Removing empty: $($dir.FullName)" -ForegroundColor Gray
        Remove-Item $dir.FullName -Force
    }
}

Write-Host "`nPackage refactoring complete!" -ForegroundColor Green
Write-Host "`nNext steps:" -ForegroundColor Yellow
Write-Host "1. Clean and rebuild the project: ./gradlew clean build" -ForegroundColor White
Write-Host "2. Verify all imports are correct" -ForegroundColor White
Write-Host "3. Test the app thoroughly" -ForegroundColor White
