@echo off
setlocal
cd /d "%~dp0"
java -cp "dist\ReservaSalasSimplificada.jar;dist\lib\*" cliente.ClienteMain
pause
