@echo off
REM Cake Delight - local Kafka 3.7.1 broker, KRaft single node, no Docker.
REM
REM Invoked via cmd rather than PowerShell because PowerShell mangles -D system
REM property arguments, and via java directly rather than kafka-server-start.bat
REM because that script builds a literal CLASSPATH that exceeds the Windows
REM command-line limit ("The input line is too long").
REM
REM Override any of the three paths below by setting the variable before running,
REM e.g.  set KAFKA=D:\tools\kafka_2.13-3.7.1  &  kafka-run.cmd
setlocal

if "%JAVA_HOME%"=="" (
  echo JAVA_HOME is not set. Point it at a JDK 21 installation and retry.
  exit /b 1
)
set JAVA=%JAVA_HOME%\bin\java.exe

if "%KAFKA%"=="" set KAFKA=%USERPROFILE%\kafka_2.13-3.7.1
if "%CFG%"==""   set CFG=%USERPROFILE%\cd-kraft.properties
if "%KAFKA_LOGS%"=="" set KAFKA_LOGS=%USERPROFILE%\kdata-logs

if not exist "%KAFKA%\libs" (
  echo Kafka distribution not found at "%KAFKA%".
  echo Extract Apache Kafka 3.7.1 there, or set KAFKA to its location.
  exit /b 1
)
if not exist "%CFG%" (
  echo KRaft config not found at "%CFG%". See the README for the one-time setup.
  exit /b 1
)

"%JAVA%" -Xmx512m -Xms256m ^
  -Dlog4j.configuration=file:"%KAFKA%\config\log4j.properties" ^
  -Dkafka.logs.dir="%KAFKA_LOGS%" ^
  -cp "%KAFKA%\libs\*" kafka.Kafka "%CFG%"
