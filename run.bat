@echo off
setlocal

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set MAVEN_HOME=D:\AAA_xmx\maven\apache-maven-3.9.9
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

cd /d "%~dp0"

if "%1"=="build" goto :build
if "%1"=="test" goto :test
if "%1"=="rebuild" goto :rebuild

:run
echo [PaiCLI] 启动中...
java -jar target\paicli-1.0-SNAPSHOT.jar %*
goto :end

:build
echo [PaiCLI] 编译项目...
call mvn clean package
goto :end

:rebuild
echo [PaiCLI] 重新编译并启动...
call mvn clean package
if exist target\paicli-1.0-SNAPSHOT.jar (
    java -jar target\paicli-1.0-SNAPSHOT.jar
)
goto :end

:test
echo [PaiCLI] 运行测试...
call mvn test -Pquick
goto :end

:end
endlocal
