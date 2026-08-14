@echo off
rem ---------------------------------------------------------------
rem  IDE_IT - запуск локального середовища розробки
rem  Подвійний клік по цьому файлу відкриває IDE у браузері.
rem ---------------------------------------------------------------
setlocal
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo   Node.js не знайдено. Встанови його: https://nodejs.org/
  echo.
  pause
  exit /b 1
)

rem Стабільний токен, щоб посилання не мінялося при кожному запуску.
if "%IDE_TOKEN%"=="" set IDE_TOKEN=local-dev

rem Порт можна перевизначити:  start.bat 5000
set PORT=4321
if not "%~1"=="" set PORT=%~1

rem браузер відкриваємо із затримкою, щоб сервер устиг піднятися
start "" /min cmd /c "timeout /t 2 /nobreak >nul & start """" ""http://127.0.0.1:%PORT%/?t=%IDE_TOKEN%"""
node server.js --port %PORT%

echo.
echo   Сервер зупинено.
pause
