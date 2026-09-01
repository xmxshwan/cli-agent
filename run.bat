@echo off
setlocal

cd /d "%~dp0"

where java >nul 2>&1 || (
    echo [PaiCLI] Java was not found on PATH. Install Java 17 or newer.
    exit /b 1
)

if /i "%~1"=="build" goto build
if /i "%~1"=="test" goto test
if /i "%~1"=="rebuild" goto rebuild
goto run

:run
if not exist "target\paicli-1.0-SNAPSHOT.jar" (
    echo [PaiCLI] JAR not found. Run "run.bat build" first.
    exit /b 1
)
java -jar "target\paicli-1.0-SNAPSHOT.jar" %*
exit /b %errorlevel%

:build
call mvn clean package
exit /b %errorlevel%

:rebuild
call mvn clean package
if errorlevel 1 exit /b %errorlevel%
java -jar "target\paicli-1.0-SNAPSHOT.jar"
exit /b %errorlevel%

:test
call mvn test -Pquick
exit /b %errorlevel%
