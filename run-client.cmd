@echo off
rem Starts the JavaFX client (server must be running). Optional args: host port
cd /d "%~dp0"
call mvnw-local.cmd -q -DskipTests install || exit /b 1
call mvnw-local.cmd -q -pl client javafx:run -Djavafx.args="%*"
