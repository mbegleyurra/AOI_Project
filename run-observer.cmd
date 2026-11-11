@echo off
rem Starts the server full-view observer window (server must be running). Optional args: host spectatorPort (default 7778)
cd /d "%~dp0"
call mvnw-local.cmd -q -DskipTests install || exit /b 1
call mvnw-local.cmd -q -pl client javafx:run@observer -Djavafx.args="%*"
