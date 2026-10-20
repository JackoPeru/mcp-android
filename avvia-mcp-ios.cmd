@echo off
setlocal
cd /d "%~dp0"
if "%IOS_MCP_TOKEN%"=="" echo ATTENZIONE: IOS_MCP_TOKEN non impostato. & pause & exit /b 1
node --version || (pause & exit /b 1)
node bridge\ios-server.js
set ERR=%errorlevel%
pause
exit /b %ERR%
