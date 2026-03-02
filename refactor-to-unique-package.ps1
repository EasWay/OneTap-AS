# PowerShell script to refactor package name to unique identifier

$oldPackage = "com.onetap.app"
$newPackage = "com.tapstream.downloader"
$oldPath = "com/onetap/app"
$newPath = "com/tapstream/downloader"

Write-Host "Starting package refactoring to unique name..." -ForegroundColor Green
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

# Step 2: Create new directory structure
Write-Host "`nStep 2: Creating new directory structure..." -ForegroundColor Cyan
$newMainPath = "app/src/main/java/com/tapstream/downloader"
$newTestPath = "app/src/androidTest/java/com/tapstream/downloader"  
$newUnitTestPath = "app/src/test/java/com/tapstream/downloader"

New-Item -ItemType Directory -Path $newMainPath -Force | Out-Null
New-Item -ItemType Directory -Path "app/src/androidTest/java/com/tapstream" -Force | Out-Null
New-Item -ItemType Directory -Path "app/src/test/java/com/tapstream" -Force | Out-Null

# Step 3: Move files to new structure
Write-Host "`nStep 3: Moving files to new structure..." -ForegroundColor Cyan

# Move main source files
$oldMainPath = "app/src/main/java/com/onetap/app"
if (Test-Path $oldMainPath) {
    Copy-Item -Path "$oldMainPath/*" -Destination $newMainPath -Recurse -Force
    Write-Host "  Moved main source files" -ForegroundColor Gray
}

# Move androidTest files
$oldAndroidTestPath = "app/src/androidTest/java/com/onetap/app"
if (Test-Path $oldAndroidTestPath) {
    Copy-Item -Path "$oldAndroidTestPath/*" -Destination $newTestPath -Recurse -Force
    Write-Host "  Moved androidTest files" -ForegroundColor Gray
}

# Move unit test files
$oldUnitTestPathActual = "app/src/test/java/com/onetap/app"
if (Test-Path $oldUnitTestPathActual) {
    Copy-Item -Path "$oldUnitTestPathActual/*" -Destination $newUnitTestPath -Recurse -Force
    Write-Host "  Moved unit test files" -ForegroundColor Gray
}

# Step 4: Clean up old directories
Write-Host "`nStep 4: Cleaning up old directories..." -ForegroundColor Cyan
if (Test-Path "app/src/main/java/com/onetap") {
    Remove-Item -Path "app/src/main/java/com/onetap" -Recurse -Force
    Write-Host "  Removed old main directory" -ForegroundColor Gray
}
if (Test-Path "app/src/androidTest/java/com/onetap") {
    Remove-Item -Path "app/src/androidTest/java/com/onetap" -Recurse -Force
    Write-Host "  Removed old androidTest directory" -ForegroundColor Gray
}
if (Test-Path "app/src/test/java/com/onetap") {
    Remove-Item -Path "app/src/test/java/com/onetap" -Recurse -Force
    Write-Host "  Removed old test directory" -ForegroundColor Gray
}

Write-Host "`nPackage refactoring to unique name complete!" -ForegroundColor Green
Write-Host "`nNew package: com.tapstream.downloader" -ForegroundColor Yellow
Write-Host "`nNext steps:" -ForegroundColor Yellow
Write-Host "1. Clean and rebuild: ./gradlew clean build" -ForegroundColor White
Write-Host "2. Verify all imports are correct" -ForegroundColor White
Write-Host "3. Test the app thoroughly" -ForegroundColor White
Write-Host "4. This package name should be unique on Google Play" -ForegroundColor White