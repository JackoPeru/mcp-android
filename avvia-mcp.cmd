@echo off
setlocal
cd /d "%~dp0"
if "%ANDROID_MCP_TOKEN%"=="" echo ATTENZIONE: ANDROID_MCP_TOKEN non impostato. & pause & exit /b 1
node --version || (pause & exit /b 1)
node bridge\server.js
set ERR=%errorlevel%
pause
exit /b %ERR%
