#!/bin/bash

# Query DB 서버 리소스 모니터링 스크립트
# PostgreSQL과 배치 애플리케이션 리소스 경합 모니터링

# 애플리케이션 경로
APP_HOME="/devdata/apps/bridge-db-monitoring"
LOG_DIR="$APP_HOME/logs"
mkdir -p $LOG_DIR

# Java 경로 (jstat 명령어용)
JAVA_HOME="/devdata/apps/jdk-17.0.8"
JSTAT="$JAVA_HOME/bin/jstat"

# 색상 코드
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# CSV 헤더 생성 (첫 실행 시)
if [ ! -f "$LOG_DIR/resource-monitor.csv" ]; then
    echo "timestamp,pg_cpu,java_cpu,delay_minutes,throughput,collect_connections" > $LOG_DIR/resource-monitor.csv
fi

while true; do
    clear
    echo "========================================="
    echo "Vessel Batch Resource Monitor"
    echo "Time: $(date)"
    echo "App Home: $APP_HOME"
    echo "========================================="
    
    # PID 파일에서 프로세스 ID 읽기
    if [ -f "$APP_HOME/vessel-batch.pid" ]; then
        JAVA_PID=$(cat $APP_HOME/vessel-batch.pid)
    else
        JAVA_PID=$(pgrep -f "vessel-batch-aggregation.jar")
    fi
    
    # 1. CPU 사용률
    echo -e "\n${GREEN}[CPU Usage]${NC}"
    # PostgreSQL CPU 사용률
    PG_CPU=$(ps aux | grep postgres | grep -v grep | awk '{sum+=$3} END {printf "%.1f", sum}' || echo "0")
    if [ -z "$PG_CPU" ]; then PG_CPU="0"; fi
    echo "PostgreSQL Total: ${PG_CPU}%"
    
    # Java 배치 CPU 사용률
    if [ ! -z "$JAVA_PID" ] && kill -0 $JAVA_PID 2>/dev/null; then
        JAVA_CPU=$(ps aux | grep $JAVA_PID | grep -v grep | awk '{printf "%.1f", $3}' || echo "0")
        if [ -z "$JAVA_CPU" ]; then JAVA_CPU="0"; fi
        echo "Batch Application: ${JAVA_CPU}% (PID: $JAVA_PID)"
    else
        JAVA_CPU="0.0"
        echo "Batch Application: Not Running"
    fi
    
    # Top 5 PostgreSQL 프로세스
    echo -e "\nTop PostgreSQL Processes:"
    ps aux | grep postgres | grep -v grep | sort -k3 -nr | head -5 | awk '{printf "  %-8s %5s%% %s\n", $2, $3, $11}'
    
    # 2. 메모리 사용률
    echo -e "\n${GREEN}[Memory Usage]${NC}"
    free -h | grep -E "Mem|Swap"
    
    # PostgreSQL 공유 메모리
    PG_SHARED=$(ipcs -m 2>/dev/null | grep postgres | awk '{sum+=$5} END {printf "%.1f", sum/1024/1024/1024}')
    if [ ! -z "$PG_SHARED" ]; then
        echo "PostgreSQL Shared Memory: ${PG_SHARED}GB"
    fi
    
    # Java 힙 사용률
    if [ ! -z "$JAVA_PID" ] && kill -0 $JAVA_PID 2>/dev/null; then
        if [ -x "$JSTAT" ]; then
            JAVA_HEAP=$($JSTAT -gc $JAVA_PID 2>/dev/null | tail -1 | awk '{printf "%.1f", ($3+$4+$6+$8)/1024}')
            if [ ! -z "$JAVA_HEAP" ]; then
                echo "Java Heap Used: ${JAVA_HEAP}MB"
            fi
        fi
    fi
    
    # 3. 디스크 I/O
    echo -e "\n${GREEN}[Disk I/O]${NC}"
    iostat -x 1 2 2>/dev/null | grep -A5 "Device" | tail -n +7 | head -5
    
    # 4. PostgreSQL 연결 상태
    echo -e "\n${GREEN}[Database Connections]${NC}"
    # psql 명령어가 PATH에 없을 수 있으므로 전체 경로 사용 시도
    if command -v psql >/dev/null 2>&1; then
        PSQL_CMD="psql"
    else
        # 일반적인 PostgreSQL 설치 경로들
        for path in /usr/pgsql-*/bin/psql /usr/bin/psql /usr/local/bin/psql; do
            if [ -x "$path" ]; then
                PSQL_CMD="$path"
                break
            fi
        done
    fi
    
    if [ ! -z "$PSQL_CMD" ]; then
        $PSQL_CMD -h localhost -U mda -d mdadb -c "
        SELECT 
            application_name,
            client_addr,
            COUNT(*) as connections,
            string_agg(DISTINCT state, ', ') as states
        FROM pg_stat_activity 
        WHERE datname = 'mdadb'
        GROUP BY application_name, client_addr
        ORDER BY connections DESC
        LIMIT 10;" 2>/dev/null || echo "Unable to query database connections"
    else
        echo "psql command not found"
    fi
    
    # 5. 배치 처리 상태
    echo -e "\n${GREEN}[Batch Processing Status]${NC}"
    
    if [ ! -z "$PSQL_CMD" ]; then
        # 처리 지연 확인
        DELAY=$($PSQL_CMD -h localhost -U mda -d mdadb -t -c "
        SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MAX(last_update))) / 60, 0)::numeric(10,1)
        FROM signal.t_vessel_latest_position;" 2>/dev/null | xargs)
        
        if [ ! -z "$DELAY" ] && [ "$DELAY" != "" ]; then
            if [ $(echo "$DELAY > 120" | bc 2>/dev/null || echo 0) -eq 1 ]; then
                echo -e "${RED}Processing Delay: ${DELAY} minutes ⚠️${NC}"
            elif [ $(echo "$DELAY > 60" | bc 2>/dev/null || echo 0) -eq 1 ]; then
                echo -e "${YELLOW}Processing Delay: ${DELAY} minutes ⚠️${NC}"
            else
                echo -e "${GREEN}Processing Delay: ${DELAY} minutes ✓${NC}"
            fi
        else
            DELAY="0"
            echo "Processing Delay: Unable to determine"
        fi
        
        # 최근 처리량
        THROUGHPUT=$($PSQL_CMD -h localhost -U mda -d mdadb -t -c "
        SELECT COALESCE(COUNT(*), 0)
        FROM signal.t_vessel_latest_position 
        WHERE last_update > NOW() - INTERVAL '1 minute';" 2>/dev/null | xargs)
        
        if [ ! -z "$THROUGHPUT" ]; then
            echo "Throughput: ${THROUGHPUT} vessels/minute"
        else
            THROUGHPUT="0"
            echo "Throughput: Unable to determine"
        fi
    else
        DELAY="0"
        THROUGHPUT="0"
        echo "Database metrics unavailable (psql not found)"
    fi
    
    # 6. 네트워크 연결 (수집 DB)
    echo -e "\n${GREEN}[Network to Collect DB]${NC}"
    COLLECT_CONN=$(ss -tunp 2>/dev/null | grep :5432 | grep 10.26.252.39 | wc -l)
    echo "Active connections to collect DB: ${COLLECT_CONN}"
    
    # 네트워크 통계
    if [ "$COLLECT_CONN" -gt 0 ]; then
        ss -i dst 10.26.252.39:5432 2>/dev/null | grep -E "rtt|cwnd" | head -3
    fi
    
    # 7. 애플리케이션 로그 최근 에러
    echo -e "\n${GREEN}[Recent Application Errors]${NC}"
    if [ -f "$LOG_DIR/app.log" ]; then
        ERROR_COUNT=$(grep -c "ERROR" $LOG_DIR/app.log 2>/dev/null || echo 0)
        echo "Total Errors in Log: $ERROR_COUNT"
        
        # 최근 5개 에러 표시
        if [ "$ERROR_COUNT" -gt 0 ]; then
            echo "Recent Errors:"
            grep "ERROR" $LOG_DIR/app.log | tail -5 | cut -c1-120
        fi
    else
        echo "Log file not found at $LOG_DIR/app.log"
    fi
    
    # 8. 경고 사항
    echo -e "\n${YELLOW}[Warnings]${NC}"
    
    # CPU 경고
    TOTAL_CPU=$(echo "$PG_CPU + $JAVA_CPU" | bc 2>/dev/null || echo "0")
    if [ ! -z "$TOTAL_CPU" ] && [ "$TOTAL_CPU" != "0" ]; then
        if [ $(echo "$TOTAL_CPU > 80" | bc 2>/dev/null || echo 0) -eq 1 ]; then
            echo -e "${RED}⚠ High CPU usage: ${TOTAL_CPU}%${NC}"
        fi
    fi
    
    # 메모리 경고
    MEM_AVAILABLE=$(free -g | grep Mem | awk '{print $7}')
    if [ ! -z "$MEM_AVAILABLE" ] && [ "$MEM_AVAILABLE" -lt 10 ]; then
        echo -e "${RED}⚠ Low available memory: ${MEM_AVAILABLE}GB${NC}"
    fi
    
    # 처리 지연 경고
    if [ ! -z "$DELAY" ] && [ "$DELAY" != "0" ]; then
        if [ $(echo "$DELAY > 120" | bc 2>/dev/null || echo 0) -eq 1 ]; then
            echo -e "${RED}⚠ Processing delay exceeds 2 hours!${NC}"
        fi
    fi
    
    # 로그에 기록
    echo "$(date '+%Y-%m-%d %H:%M:%S'),${PG_CPU},${JAVA_CPU},${DELAY},${THROUGHPUT},${COLLECT_CONN}" >> $LOG_DIR/resource-monitor.csv
    
    # 다음 업데이트까지 대기
    echo -e "\n${GREEN}Next update in 30 seconds... (Ctrl+C to exit)${NC}"
    sleep 30
done
