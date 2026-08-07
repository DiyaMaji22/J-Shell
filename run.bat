@echo off
setlocal
cd /d "%~dp0\jshell"
mvn -q compile exec:java