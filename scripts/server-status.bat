@echo off
chcp 65001 >nul
REM ===============================================
REM Signal Batch Server Status Checker
REM ===============================================

setlocal enabledelayedexpansion

REM Configuration
set "SERVER_IP=10.26.252.48"
set "SERVER_USER=root"
set "SERVER_PATH=/devdata/apps/bridge-db-monitoring"

echo ===============================================
echo Signal Batch Server Status
echo ===============================================
echo [INFO] Query Time: !date! !time!
echo [INFO] Target Server: !SERVER_IP!

REM 1. Server Connection Test
echo.
echo =============== Server Connection Test ===============
ssh !SERVER_USER!@!SERVER_IP! "echo 'Server connection OK'" 2>nul
set CONNECTION_RESULT=!ERRORLEVEL!
if !CONNECTION_RESULT! neq 0 (
    echo [ERROR] Server connection failed
    exit /b 1
)
echo [INFO] Server connection successful

REM 2. Application Status
echo.
echo =============== Application Status ===============
ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh status"

REM 3. Additional Status Information
echo.
echo =============== Additional Status Information ===============

REM Health Check
echo [INFO] Health Check:
ssh !SERVER_USER!@!SERVER_IP! "curl -s http://localhost:8090/actuator/health --max-time 5 2>/dev/null | python -m json.tool 2>/dev/null || echo 'Health endpoint not available'"

echo.
REM Metrics Information
echo [INFO] Metrics Information:
ssh !SERVER_USER!@!SERVER_IP! "curl -s http://localhost:8090/actuator/metrics --max-time 5 2>/dev/null | head -20 || echo 'Metrics endpoint not available'"

echo.
REM Disk Usage
echo [INFO] Disk Usage:
ssh !SERVER_USER!@!SERVER_IP! "df -h !SERVER_PATH!"

echo.
REM Memory Usage
echo [INFO] Memory Usage:
ssh !SERVER_USER!@!SERVER_IP! "free -h"

echo.
REM Recent Log Check
echo [INFO] Recent Logs (last 10 lines):
ssh !SERVER_USER!@!SERVER_IP! "tail -10 !SERVER_PATH!/logs/app.log 2>/dev/null || echo 'Log file not available'"

endlocal