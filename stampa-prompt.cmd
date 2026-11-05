@echo off
setlocal
cd /d "%~dp0"
node scripts\collega-telefono.mjs --print-prompt
echo.
echo ============================================================
echo Copia tutto il testo qui sopra, sostituisci INCOLLA_QUI_LA_KEY
echo con la key del telefono e incollalo al tuo agente.
echo ============================================================
pause
