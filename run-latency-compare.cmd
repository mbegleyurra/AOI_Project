@echo off
rem Windows wrapper: needs sh (e.g. Git for Windows) on PATH; runs the POSIX script.
rem Usage: run-latency-compare.cmd [seconds] [bots] [radius] [seed] [port]
cd /d "%~dp0"
sh run-latency-compare.sh %*
