@echo off
setlocal
cd /d "%~dp0"
node bridge\server.js
exit /b %errorlevel%
