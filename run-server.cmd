@echo off
rem Builds, then starts the AOI server. Optional args: bots radius port seed (defaults 300 150 7777 42)
rem Add --algo=ray for ray visibility (obstacles + line of sight); default is --algo=distance.
rem   e.g.  run-server.cmd 300 150 7777 42 --algo=ray
cd /d "%~dp0"
call mvnw-local.cmd -q -DskipTests package || exit /b 1
java -cp "common\target\classes;server\target\classes" aoi.server.ServerMain %*
