@echo off
rem Runs Maven from IntelliJ's bundled copy so "mvn" does not need to be on PATH.
rem Usage: mvnw-local.cmd <maven args>   e.g.  mvnw-local.cmd -q -DskipTests package
"C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" %*
