@echo off
REM Cake Delight - local Kafka 3.7.1 broker, KRaft single node, no Docker.
REM Invoked via cmd rather than PowerShell because PowerShell mangles -D system
REM property arguments, and via java directly rather than kafka-server-start.bat
REM because that script builds a literal CLASSPATH that exceeds the Windows
REM command-line limit ("The input line is too long").
setlocal
set JAVA=C:\Program Files\Zulu\zulu-21\bin\java.exe
set KAFKA=%USERPROFILE%\kafka_2.13-3.7.1
set CFG=%USERPROFILE%\cd-kraft.properties
"%JAVA%" -Xmx512m -Xms256m -Dlog4j.configuration=file:"%KAFKA%\config\log4j.properties" -Dkafka.logs.dir="%USERPROFILE%\kdata-logs" -cp "%KAFKA%\libs\*" kafka.Kafka "%CFG%"
