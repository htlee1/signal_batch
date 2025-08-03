#!/bin/bash

# Vessel Batch 관리 스크립트
# 시작, 중지, 상태 확인 등 기본 관리 기능

# 애플리케이션 경로
APP_HOME="/devdata/apps/bridge-db-monitoring"
JAR_FILE="$APP_HOME/vessel-batch-aggregation.jar"
PID_FILE="$APP_HOME/vessel-batch.pid"
LOG_DIR="$APP_HOME/logs"

# Java 17 경로
JAVA_HOME="/devdata/apps/jdk-17.0.8"
JAVA_BIN="$JAVA_HOME/bin/java"

# 색상 코드
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 함수: PID 확인
get_pid() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat $PID_FILE)
        if kill -0 $PID 2>/dev/null; then
            echo $PID
        else
            rm -f $PID_FILE
            echo ""
        fi
    else
        PID=$(pgrep -f "$JAR_FILE")
        echo $PID
    fi
}

# 함수: 상태 확인
status() {
    PID=$(get_pid)
    if [ ! -z "$PID" ]; then
        echo -e "${GREEN}✓ Vessel Batch is running (PID: $PID)${NC}"
        
        # 프로세스 정보
        echo ""
        ps aux | grep $PID | grep -v grep
        
        # Health Check
        echo ""
        echo "Health Check:"
        curl -s http://localhost:8090/actuator/health 2>/dev/null | python -m json.tool || echo "Health endpoint not available"
        
        # 처리 상태
        echo ""
        echo "Processing Status:"
        if command -v psql >/dev/null 2>&1; then
            psql -h localhost -U mda -d mdadb -c "
            SELECT 
                NOW() - MAX(last_update) as processing_delay,
                COUNT(*) as vessel_count
            FROM signal.t_vessel_latest_position;" 2>/dev/null || echo "Unable to query database"
        fi
        
        return 0
    else
        echo -e "${RED}✗ Vessel Batch is not running${NC}"
        return 1
    fi
}

# 함수: 시작
start() {
    PID=$(get_pid)
    if [ ! -z "$PID" ]; then
        echo -e "${YELLOW}Vessel Batch is already running (PID: $PID)${NC}"
        return 1
    fi
    
    echo "Starting Vessel Batch..."
    cd $APP_HOME
    $APP_HOME/run-on-query-server.sh
}

# 함수: 중지
stop() {
    PID=$(get_pid)
    if [ -z "$PID" ]; then
        echo -e "${YELLOW}Vessel Batch is not running${NC}"
        return 1
    fi
    
    echo "Stopping Vessel Batch (PID: $PID)..."
    kill -15 $PID
    
    # 종료 대기
    for i in {1..30}; do
        if ! kill -0 $PID 2>/dev/null; then
            echo -e "${GREEN}✓ Vessel Batch stopped successfully${NC}"
            rm -f $PID_FILE
            return 0
        fi
        echo -n "."
        sleep 1
    done
    
    echo ""
    echo -e "${RED}Process did not stop gracefully, force killing...${NC}"
    kill -9 $PID
    rm -f $PID_FILE
}

# 함수: 재시작
restart() {
    echo "Restarting Vessel Batch..."
    stop
    sleep 3
    start
}

# 함수: 로그 보기
logs() {
    if [ ! -d "$LOG_DIR" ]; then
        echo "Log directory not found: $LOG_DIR"
        return 1
    fi
    
    echo "Available log files:"
    ls -lh $LOG_DIR/*.log 2>/dev/null
    
    echo ""
    echo "Tailing app.log (Ctrl+C to exit)..."
    tail -f $LOG_DIR/app.log
}

# 함수: 최근 에러 확인
errors() {
    if [ ! -f "$LOG_DIR/app.log" ]; then
        echo "Log file not found: $LOG_DIR/app.log"
        return 1
    fi
    
    echo "Recent errors (last 50 lines with ERROR):"
    grep "ERROR" $LOG_DIR/app.log | tail -50
    
    echo ""
    echo "Error summary:"
    echo "Total errors: $(grep -c "ERROR" $LOG_DIR/app.log)"
    echo "Errors today: $(grep "ERROR" $LOG_DIR/app.log | grep "$(date +%Y-%m-%d)" | wc -l)"
}

# 함수: 성능 통계
stats() {
    echo "Performance Statistics"
    echo "===================="
    
    if [ -f "$LOG_DIR/resource-monitor.csv" ]; then
        echo "Recent resource usage:"
        tail -5 $LOG_DIR/resource-monitor.csv | column -t -s,
    fi
    
    echo ""
    echo "Batch job statistics:"
    if command -v psql >/dev/null 2>&1; then
        psql -h localhost -U mda -d mdadb -c "
        SELECT 
            job_name,
            COUNT(*) as executions,
            AVG(EXTRACT(EPOCH FROM (end_time - start_time))/60)::numeric(10,2) as avg_duration_min,
            MAX(end_time) as last_execution
        FROM batch_job_execution je
        JOIN batch_job_instance ji ON je.job_instance_id = ji.job_instance_id
        WHERE end_time > CURRENT_DATE - INTERVAL '7 days'
        GROUP BY job_name;" 2>/dev/null || echo "Unable to query batch statistics"
    fi
}

# 메인 로직
case "$1" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        restart
        ;;
    status)
        status
        ;;
    logs)
        logs
        ;;
    errors)
        errors
        ;;
    stats)
        stats
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|status|logs|errors|stats}"
        echo ""
        echo "Commands:"
        echo "  start    - Start the Vessel Batch application"
        echo "  stop     - Stop the Vessel Batch application"
        echo "  restart  - Restart the Vessel Batch application"
        echo "  status   - Check application status and health"
        echo "  logs     - Tail application logs"
        echo "  errors   - Show recent errors from logs"
        echo "  stats    - Show performance statistics"
        exit 1
        ;;
esac

exit $?
