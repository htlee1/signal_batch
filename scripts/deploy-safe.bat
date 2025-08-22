@echo off
chcp 65001 >nul
REM ===============================================
REM Signal Batch Safe Deploy Script
REM (with running application check)
REM ===============================================

setlocal enabledelayedexpansion

REM Configuration
set "SERVER_IP=10.26.252.48"
set "SERVER_USER=root"
set "SERVER_PATH=/devdata/apps/bridge-db-monitoring"
set "JAR_NAME=vessel-batch-aggregation.jar"
set "BACKUP_DIR=!SERVER_PATH!/backups"

echo ===============================================
echo Signal Batch Safe Deploy System
echo ===============================================
echo [INFO] Deploy Start: !date! !time!
echo [INFO] Target Server: !SERVER_IP!
echo.

REM Set working directory
cd /d "%~dp0.."
echo [INFO] Project directory: !CD!

REM 1. Check JAR file
echo.
echo =============== JAR File Check ===============
set "JAR_PATH=target\!JAR_NAME!"

if not exist "!JAR_PATH!" (
    echo [ERROR] JAR file not found: !JAR_PATH!
    echo [INFO] Please build the project first using IntelliJ Maven
    pause
    exit /b 1
)

for %%I in ("!JAR_PATH!") do (
    echo [INFO] JAR File: %%~nxI
    echo [INFO] File Size: %%~zI bytes
    echo [INFO] Modified: %%~tI
)

REM 2. SSH Connection Test
echo.
echo =============== SSH Connection Test ===============
ssh !SERVER_USER!@!SERVER_IP! "echo 'SSH connection OK'" 2>nul
if !ERRORLEVEL! neq 0 (
    echo [ERROR] SSH connection failed
    pause
    exit /b 1
)
echo [SUCCESS] SSH connection successful

REM 3. Check current application status
echo.
echo =============== Current Application Status ===============
echo [INFO] Checking if application is currently running...

ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh status" 2>nul
set APP_STATUS=!ERRORLEVEL!

if !APP_STATUS! equ 0 (
    echo.
    echo [WARNING] Application is currently RUNNING on the server!
    echo.
    echo =============== Deployment Options ===============
    echo 1. Continue with deployment (stop → deploy → start)
    echo 2. Cancel deployment (keep current version running)
    echo 3. Check application details first
    echo.
    set /p DEPLOY_CHOICE="Choose option (1-3): "
    
    if "!DEPLOY_CHOICE!"=="2" (
        echo [INFO] Deployment cancelled by user
        echo [INFO] Current application continues running
        pause
        exit /b 0
    )
    
    if "!DEPLOY_CHOICE!"=="3" (
        echo.
        echo =============== Application Details ===============
        ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh status"
        echo.
        ssh !SERVER_USER!@!SERVER_IP! "curl -s http://localhost:8090/actuator/health --max-time 5 2>/dev/null | python -m json.tool 2>/dev/null || echo 'Health endpoint not available'"
        echo.
        set /p FINAL_CHOICE="Proceed with deployment? (y/N): "
        if /i not "!FINAL_CHOICE!"=="y" (
            echo [INFO] Deployment cancelled
            pause
            exit /b 0
        )
    )
    
    if not "!DEPLOY_CHOICE!"=="1" if not "!DEPLOY_CHOICE!"=="3" (
        echo [ERROR] Invalid choice. Deployment cancelled.
        pause
        exit /b 1
    )
    
    echo.
    echo [INFO] Proceeding with deployment...
    echo [INFO] Current application will be stopped during deployment
    
) else (
    echo [INFO] Application is not currently running
    echo [INFO] Proceeding with fresh deployment
)

REM 4. Create backup timestamp
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do if not "%%I"=="" set DATETIME=%%I
set BACKUP_TIMESTAMP=!DATETIME:~0,8!_!DATETIME:~8,6!

REM 5. Create backup (if existing JAR exists)
echo.
echo =============== Create Backup ===============
ssh !SERVER_USER!@!SERVER_IP! "mkdir -p !BACKUP_DIR!"

ssh !SERVER_USER!@!SERVER_IP! "
if [ -f !SERVER_PATH!/!JAR_NAME! ]; then
    echo '[INFO] Creating backup of current version...'
    cp !SERVER_PATH!/!JAR_NAME! !BACKUP_DIR!/!JAR_NAME!.backup.!BACKUP_TIMESTAMP!
    echo '[SUCCESS] Backup created: !BACKUP_DIR!/!JAR_NAME!.backup.!BACKUP_TIMESTAMP!'
    ls -la !BACKUP_DIR!/!JAR_NAME!.backup.!BACKUP_TIMESTAMP!
else
    echo '[INFO] No existing JAR file to backup (first deployment)'
fi
"

REM 6. Stop application (if running)
if !APP_STATUS! equ 0 (
    echo.
    echo =============== Stop Current Application ===============
    echo [INFO] Gracefully stopping current application...
    ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh stop"
    if !ERRORLEVEL! neq 0 (
        echo [ERROR] Failed to stop application gracefully
        set /p FORCE_STOP="Force stop and continue? (y/N): "
        if /i not "!FORCE_STOP!"=="y" (
            echo [INFO] Deployment cancelled
            exit /b 1
        )
        echo [INFO] Attempting force stop...
        ssh !SERVER_USER!@!SERVER_IP! "pkill -f !JAR_NAME! || true"
    )
    echo [SUCCESS] Application stopped
)

REM 7. Deploy new JAR
echo.
echo =============== Deploy New Version ===============
echo [INFO] Transferring new JAR file...

scp "!JAR_PATH!" !SERVER_USER!@!SERVER_IP!:!SERVER_PATH!/
if !ERRORLEVEL! neq 0 (
    echo [ERROR] File transfer failed
    goto :deployment_failed
)

ssh !SERVER_USER!@!SERVER_IP! "chmod +x !SERVER_PATH!/!JAR_NAME!"
echo [SUCCESS] New version deployed

REM 8. Transfer version info
if exist "target\version.txt" (
    scp "target\version.txt" !SERVER_USER!@!SERVER_IP!:!SERVER_PATH!/
)

REM 9. Start new application
echo.
echo =============== Start New Application ===============
echo [INFO] Starting new version...

ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh start"
if !ERRORLEVEL! neq 0 (
    echo [ERROR] Failed to start new application
    goto :deployment_failed
)

REM 10. Verify deployment
echo.
echo =============== Verify Deployment ===============
echo [INFO] Waiting for application startup (30 seconds)...
timeout /t 30 /nobreak > nul

ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh status"
if !ERRORLEVEL! neq 0 (
    echo [ERROR] New application is not running properly
    goto :deployment_failed
)

echo [INFO] Performing health check...
ssh !SERVER_USER!@!SERVER_IP! "curl -f http://localhost:8090/actuator/health --max-time 10" 2>nul
if !ERRORLEVEL! neq 0 (
    echo [WARN] Health check failed, but application is running
    echo [INFO] Manual verification recommended
)

REM 11. Success
echo.
echo =============== Deployment Successful ===============
echo [SUCCESS] Safe deployment completed successfully!
echo [INFO] Deployment time: !date! !time!
echo [INFO] Backup: !JAR_NAME!.backup.!BACKUP_TIMESTAMP!
echo [INFO] Dashboard: http://!SERVER_IP!:8090/static/admin/batch-admin.html
echo.
echo Quick commands:
echo   server-status.bat               - Check status
echo   server-logs.bat tail            - Monitor logs  
echo   rollback.bat !BACKUP_TIMESTAMP! - Rollback if needed

goto :end

:deployment_failed
echo.
echo =============== Deployment Failed ===============
echo [ERROR] Deployment failed!
echo.
set /p AUTO_ROLLBACK="Attempt automatic rollback? (y/N): "
if /i "!AUTO_ROLLBACK!"=="y" (
    if defined BACKUP_TIMESTAMP (
        echo [INFO] Attempting rollback to: !BACKUP_TIMESTAMP!
        call rollback.bat !BACKUP_TIMESTAMP!
    ) else (
        echo [ERROR] No backup available for automatic rollback
    )
) else (
    echo [INFO] Manual recovery required
    echo [INFO] Available backups:
    ssh !SERVER_USER!@!SERVER_IP! "ls -la !BACKUP_DIR!/!JAR_NAME!.backup.* 2>/dev/null || echo 'No backups found'"
)
exit /b 1

:end
endlocal