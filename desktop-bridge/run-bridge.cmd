@echo off
setlocal
cd /d "%~dp0"
if not exist data mkdir data
"C:\Program Files\nodejs\node.exe" --no-warnings src\server.mjs >> data\bridge.out.log 2>> data\bridge.err.log
