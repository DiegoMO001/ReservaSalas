@echo off
setlocal
cd /d "%~dp0"
java -cp "dist\ReservaSalasSimplificada.jar;dist\lib\*" servidor.ServidorMain
pause
