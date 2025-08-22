@echo off
chcp 65001 >nul
REM ===============================================
REM Signal Batch Deploy Only Script
REM (Build with IntelliJ UI first)
REM ===============================================

setlocal enabledelayedexpansion

REM Configuration
set "SERVER_IP=10.26.252.48"
set "SERVER_USER=root"
set "SERVER_PATH=/devdata/apps/bridge-db-monitoring"
set "JAR_NAME=vessel-batch-aggregation.jar"
set "BACKUP_DIR=!SERVER_PATH!/backups"

echo ===============================================
echo Signal Batch Deploy System (Deploy Only)
echo ===============================================
echo [INFO] Deploy Start: !date! !time!
echo [INFO] Target Server: !SERVER_IP!
echo.

REM 1. Set correct working directory and check JAR file
echo =============== Working Directory Setup ===============
echo [INFO] Current directory: !CD!
echo [INFO] Script directory: %~dp0

REM Change to project root directory (parent of scripts)
cd /d "%~dp0.."
echo [INFO] Project root directory: !CD!

echo.
echo =============== JAR File Check ===============
set "JAR_PATH=target\!JAR_NAME!"

if not exist "!JAR_PATH!" (
    echo [ERROR] JAR file not found: !JAR_PATH!
    echo [INFO] Current directory: !CD!
    echo.
    echo Please build the project first using IntelliJ IDEA:
    echo 1. Open Maven tool window: View ^> Tool Windows ^> Maven
    echo 2. Double-click: Lifecycle ^> clean
    echo 3. Double-click: Lifecycle ^> package
    echo 4. Verify target/!JAR_NAME! exists
    echo.
    echo Checking for any JAR files in target directory:
    if exist "target\" (
        dir target\*.jar 2>nul
        if !ERRORLEVEL! neq 0 (
            echo [INFO] Target directory exists but no JAR files found
        )
    ) else (
        echo [INFO] Target directory does not exist - project not built yet
    )
    pause
    exit /b 1
)

for %%I in ("!JAR_PATH!") do (
    echo [INFO] JAR File: %%~nxI
    echo [INFO] File Size: %%~zI bytes
    echo [INFO] Modified: %%~tI
)

echo [SUCCESS] JAR file ready for deployment

REM 2. SSH Connection Test
echo.
echo =============== SSH Connection Test ===============
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "echo 'SSH connection OK'" 2>nul
set CONNECTION_RESULT=!ERRORLEVEL!
if !CONNECTION_RESULT! neq 0 (
    echo [ERROR] SSH connection failed
    echo [INFO] Please check:
    echo - SSH key authentication setup
    echo - Network connectivity to !SERVER_IP!
    echo - Server is accessible
    echo.
    echo Run setup-ssh-key.bat to configure SSH keys
    pause
    exit /b 1
)
echo [SUCCESS] SSH connection successful

REM 3. Check current server status
echo.
echo =============== Current Server Status ===============
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh status" 2>nul
set SERVER_RUNNING=!ERRORLEVEL!

REM 4. Create backup
echo.
echo =============== Create Backup ===============
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "mkdir -p !BACKUP_DIR!"

REM Generate backup timestamp
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do if not "%%I"=="" set DATETIME=%%I
set BACKUP_TIMESTAMP=!DATETIME:~0,8!_!DATETIME:~8,6!

ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "if [ -f !SERVER_PATH!/!JAR_NAME! ]; then echo '[INFO] Creating backup...'; cp !SERVER_PATH!/!JAR_NAME! !BACKUP_DIR!/!JAR_NAME!.backup.!BACKUP_TIMESTAMP!; echo '[INFO] Backup created: !BACKUP_DIR!/!JAR_NAME!.backup.!BACKUP_TIMESTAMP!'; ls -la !BACKUP_DIR!/!JAR_NAME!.backup.!BACKUP_TIMESTAMP!; else echo '[INFO] No existing JAR file to backup (first deployment)'; fi"

REM 5. Stop application
if !SERVER_RUNNING! equ 0 (
    echo.
    echo =============== Stop Application ===============
    echo [INFO] Stopping running application...
    ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh stop"
    if !ERRORLEVEL! neq 0 (
        echo [ERROR] Failed to stop application
        exit /b 1
    )
    echo [SUCCESS] Application stopped
) else (
    echo.
    echo [INFO] Application not running, proceeding with deployment
)

REM 6. Deploy new JAR
echo.
echo =============== Deploy New JAR ===============
echo [INFO] Transferring JAR file...
scp "!JAR_PATH!" !SERVER_USER!@!SERVER_IP!:!SERVER_PATH!/
if !ERRORLEVEL! neq 0 (
    echo [ERROR] File transfer failed
    goto :rollback_option
)

echo [INFO] Setting permissions...
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "chmod +x !SERVER_PATH!/!JAR_NAME!"

echo [SUCCESS] JAR file deployed

REM 7. Transfer version info (if exists)
echo.
echo =============== Version Information ===============
if exist "target\version.txt" (
    echo [INFO] Transferring version information...
    scp "target\version.txt" !SERVER_USER!@!SERVER_IP!:!SERVER_PATH!/
) else (
    echo [INFO] No version file found, creating basic version info...
    ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "echo 'DEPLOY_TIME=!date! !time!' > !SERVER_PATH!/version.txt"
)

REM 8. Start application
echo.
echo =============== Start Application ===============
echo [INFO] Starting application...
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh start"
if !ERRORLEVEL! neq 0 (
    echo [ERROR] Failed to start application
    goto :rollback_option
)

REM 9. Wait and verify
echo.
echo =============== Deployment Verification ===============
echo [INFO] Waiting for application startup (30 seconds)...
timeout /t 30 /nobreak > nul

echo [INFO] Checking application status...
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh status"
if !ERRORLEVEL! neq 0 (
    echo [ERROR] Application not running properly
    goto :rollback_option
)

echo [INFO] Performing health check...
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "curl -f http://localhost:8090/actuator/health --max-time 10" 2>nul
if !ERRORLEVEL! neq 0 (
    echo [WARN] Health check failed, but application appears to be running
    echo [INFO] Give it a few more minutes to fully start up
)

REM 10. Cleanup old backups
echo.
echo =============== Cleanup ===============
echo [INFO] Cleaning up old backups (keeping recent 7)...
ssh -o BatchMode=yes -o ConnectTimeout=10 !SERVER_USER!@!SERVER_IP! "cd !BACKUP_DIR!; ls -t !JAR_NAME!.backup.* 2>/dev/null | tail -n +8 | xargs rm -f 2>/dev/null || true; echo '[INFO] Backup cleanup completed'"

REM 11. Success
echo.
echo =============== Deployment Successful ===============
echo [SUCCESS] Deployment completed successfully!
echo [INFO] Deployment time: !date! !time!
echo [INFO] Backup created: !JAR_NAME!.backup.!BACKUP_TIMESTAMP!
echo [INFO] Server dashboard: http://!SERVER_IP!:8090/static/admin/batch-admin.html
echo [INFO] Server logs: ssh !SERVER_USER!@!SERVER_IP! "cd !SERVER_PATH! && ./vessel-batch-control.sh logs"
echo.
echo Quick commands:
echo   server-status.bat     - Check server status
echo   server-logs.bat tail  - Monitor logs
echo   rollback.bat !BACKUP_TIMESTAMP!  - Rollback if needed

goto :end

:rollback_option
echo.
echo =============== Deployment Failed ===============
echo [ERROR] Deployment failed!
echo.
set /p ROLLBACK="Attempt rollback to previous version? (y/N): "
if /i "!ROLLBACK!"=="y" (
    echo [INFO] Attempting rollback...
    if defined BACKUP_TIMESTAMP (
        call rollback.bat !BACKUP_TIMESTAMP!
    ) else (
        echo [ERROR] No backup timestamp available for rollback
        echo [INFO] Manual recovery may be required
    )
) else (
    echo [INFO] Manual recovery required
    echo [INFO] SSH to server: ssh !SERVER_USER!@!SERVER_IP!
    echo [INFO] Check status: cd !SERVER_PATH! && ./vessel-batch-control.sh status
)
exit /b 1

:end
endlocal