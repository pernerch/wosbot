@echo off
set "PATH=C:\apache-maven-3.9.12\bin;%PATH%"
echo ==========================================
echo      Frostguard Quick Recompile Script
echo ==========================================

echo.
echo Stopping any running Java and ADB processes...
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM javaw.exe >nul 2>&1
taskkill /F /IM adb.exe >nul 2>&1
timeout /t 2 >nul

echo.
echo Building project (clean + install)...
call mvn clean install package
if errorlevel 1 (
    echo [ERROR] Build failed!
    pause
    exit /b %errorlevel%
)

echo.
echo ==========================================
echo BUILD SUCCESSFUL!
echo ==========================================
echo.
pause

