@echo off
chcp 65001 >nul
REM ===============================================
REM Signal Batch Server Log Viewer
REM ===============================================

setlocal

set SERVER_IP=10.26.252.48
set SERVER_USER=root
set SERVER_PATH=/devdata/apps/bridge-db-monitoring

echo ===============================================
echo Signal Batch Server Log Viewer
echo ===============================================
echo Server: %SERVER_IP%
echo Time: %date% %time%
echo.

if "%1"=="tail" (
    echo Starting real-time log monitoring... (Ctrl+C to exit)
    ssh %SERVER_USER%@%SERVER_IP% "cd %SERVER_PATH% && ./vessel-batch-control.sh logs"
) else if "%1"=="errors" (
    echo Retrieving recent error logs...
    ssh %SERVER_USER%@%SERVER_IP% "cd %SERVER_PATH% && ./vessel-batch-control.sh errors"
) else if "%1"=="stats" (
    echo Retrieving performance statistics...
    ssh %SERVER_USER%@%SERVER_IP% "cd %SERVER_PATH% && ./vessel-batch-control.sh stats"
) else (
    echo Usage:
    echo   server-logs.bat          - Show recent 50 lines
    echo   server-logs.bat tail     - Real-time log monitoring
    echo   server-logs.bat errors   - Show error logs only
    echo   server-logs.bat stats    - Show performance statistics
    echo.
    echo Recent 50 lines of log:
    ssh %SERVER_USER%@%SERVER_IP% "tail -50 %SERVER_PATH%/logs/app.log 2>/dev/null || echo 'Log file not available'"
)

endlocal