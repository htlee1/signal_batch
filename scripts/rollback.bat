@echo off
chcp 65001 >nul
REM ===============================================
REM Signal Batch Rollback Script
REM Safely restore to previous version
REM ===============================================

setlocal enabledelayedexpansion

REM Configuration
set "SERVER_IP=10.26.252.48"
set "SERVER_USER=root"
set "SERVER_PATH=/devdata/apps/bridge-db-monitoring"
set "JAR_NAME=vessel-batch-aggregation.jar"
set "BACKUP_DIR=!SERVER_PATH!/backups"

echo ===============================================
echo Signal Batch Rollback System
echo ===============================================
echo [INFO] Rollback Start: !date! !time!

REM 1. Check backup timestamp parameter
if "%1"=="" (
    echo.
    echo =============== Available Backup Versions ===============
    echo [INFO] Checking backup files on server...
    ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "if [ -d !BACKUP_DIR! ]; then echo 'Available backup versions:'; ls -la !BACKUP_DIR!/!JAR_NAME!.backup.* 2>/dev/null | awk '{print \$9, \$5, \$6, \$7, \$8}' | sed 's|.*/||'; echo ''; echo 'Usage: rollback.bat [TIMESTAMP]'; echo 'Example: rollback.bat 20250822_143052'; else echo 'No backup directory found!'; exit 1; fi"
    exit /b 1
)

set "BACKUP_TIMESTAMP=%1"
set "BACKUP_FILE=!BACKUP_DIR!/!JAR_NAME!.backup.!BACKUP_TIMESTAMP!"

echo [INFO] Rollback Target: !BACKUP_TIMESTAMP!
echo [INFO] Backup File: !BACKUP_FILE!

REM 2. Server connection test
echo.
echo =============== Server Connection Test ===============
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "echo 'Server connection OK'" 2>nul
if !ERRORLEVEL! neq 0 (
    echo [ERROR] Server connection failed
    exit /b 1
)

REM 3. Verify backup file exists
echo.
echo =============== Backup File Verification ===============
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "if [ ! -f !BACKUP_FILE! ]; then echo '[ERROR] Backup file not found: !BACKUP_FILE!'; echo '[INFO] Available backup files:'; ls -la !BACKUP_DIR!/!JAR_NAME!.backup.* 2>/dev/null || echo 'No backup files found'; exit 1; else echo '[INFO] Backup file verified: !BACKUP_FILE!'; ls -la !BACKUP_FILE!; fi"
if !ERRORLEVEL! neq 0 exit /b 1

REM 4. Rollback confirmation
echo.
echo =============== Rollback Confirmation ===============
echo WARNING: Current running application will be stopped and restored to previous version.
echo [INFO] Rollback Version: !BACKUP_TIMESTAMP!
set /p CONFIRM="Do you really want to perform rollback? (yes/no): "

if /i not "!CONFIRM!"=="yes" (
    echo [INFO] Rollback cancelled.
    exit /b 0
)

REM 5. Backup current version (emergency)
echo.
echo =============== Current Version Temporary Backup ===============
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do if not "%%I"=="" set DATETIME=%%I
set "CURRENT_BACKUP=!DATETIME:~0,8!_!DATETIME:~8,6!_prerollback"

ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "if [ -f !SERVER_PATH!/!JAR_NAME! ]; then echo '[INFO] Creating temporary backup of current version...'; cp !SERVER_PATH!/!JAR_NAME! !BACKUP_DIR!/!JAR_NAME!.backup.!CURRENT_BACKUP!; echo '[INFO] Temporary backup completed: !BACKUP_DIR!/!JAR_NAME!.backup.!CURRENT_BACKUP!'; fi"

REM 6. Stop application
echo.
echo =============== Stop Application ===============
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh status" 2>nul
set "APP_RUNNING=!ERRORLEVEL!"

if !APP_RUNNING! equ 0 (
    echo [INFO] Stopping running application...
    ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh stop"
    if !ERRORLEVEL! neq 0 (
        echo [ERROR] Failed to stop application
        exit /b 1
    )
    echo [INFO] Application stopped successfully
) else (
    echo [INFO] Application is not running.
)

REM 7. Restore from backup file
echo.
echo =============== Restore Previous Version ===============
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "echo '[INFO] Restoring backup file...'; cp !BACKUP_FILE! !SERVER_PATH!/!JAR_NAME!; if [ \$? -eq 0 ]; then echo '[INFO] File restoration completed'; chmod +x !SERVER_PATH!/!JAR_NAME!; echo '[INFO] Permissions set'; ls -la !SERVER_PATH!/!JAR_NAME!; else echo '[ERROR] File restoration failed'; exit 1; fi"
if !ERRORLEVEL! neq 0 (
    echo [ERROR] Backup file restoration failed
    exit /b 1
)

REM 8. Start application
echo.
echo =============== Start Application ===============
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh start"
if !ERRORLEVEL! neq 0 (
    echo [ERROR] Failed to start application
    echo [INFO] Try manual start: ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh start"
    exit /b 1
)

REM 9. Post-rollback verification
echo.
echo =============== Post-Rollback Verification ===============
echo [INFO] Waiting for application startup... (30 seconds)
timeout /t 30 /nobreak > nul

REM Status check
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh status"
if !ERRORLEVEL! neq 0 (
    echo [ERROR] Application did not start properly after rollback.
    echo [INFO] Check logs: ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh logs"
    exit /b 1
)

REM Health Check
echo.
echo [INFO] Performing Health Check...
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "curl -f http://localhost:8090/actuator/health --max-time 10" 2>nul
if !ERRORLEVEL! neq 0 (
    echo [WARN] Health Check failed but application is running.
    echo [INFO] Please check again in a few minutes.
) else (
    echo [INFO] Health Check successful
)

REM 10. Rollback completed
echo.
echo =============== Rollback Completed ===============
echo [SUCCESS] Rollback completed successfully!
echo [INFO] Rollback Time: !date! !time!
echo [INFO] Restored Version: !BACKUP_TIMESTAMP!
echo [INFO] Temporary Backup: !BACKUP_DIR!/!JAR_NAME!.backup.!CURRENT_BACKUP!
echo [INFO] Server Status: ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh status"
echo [INFO] Server Logs: ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh logs"

REM Version info check (if available)
echo.
echo [INFO] Restored version information:
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "if [ -f !SERVER_PATH!/version.txt ]; then cat !SERVER_PATH!/version.txt; else echo 'Version file not available'; fi"

endlocal