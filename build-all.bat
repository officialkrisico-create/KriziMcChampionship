@echo off
REM ================================================================
REM  Build all KMC tournament plugins in one go.
REM
REM  Run this from the kmc-tournament/ folder.
REM  Jars will appear in each module's target/ subfolder.
REM  A 'dist/' folder at the top level will contain all finished jars
REM  ready to drop into your server's plugins/ folder.
REM ================================================================

echo [KMC] Building all modules...
call mvn clean package
if errorlevel 1 (
    echo [KMC] Build FAILED.
    exit /b 1
)

echo.
echo [KMC] Collecting jars into dist/ ...
if not exist dist mkdir dist

REM Copy each built jar (skip original-*.jar produced by shade)
for /r %%f in (target\*.jar) do (
    echo %%~nxf | findstr /v "original-" >nul
    if not errorlevel 1 copy /Y "%%f" dist\ >nul
)

echo.
echo [KMC] Done! Drop the jars in dist/ into your server's plugins/ folder.
dir /b dist
