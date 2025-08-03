#!/bin/bash

# 실시간 시스템 모니터링 스크립트
# 부하 테스트 중 시스템 상태를 실시간으로 모니터링

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 애플리케이션 정보
APP_HOST="10.26.252.48"
APP_PORT="8090"
DB_HOST_COLLECT="10.26.252.39"
DB_HOST_QUERY="10.26.252.48"
DB_PORT="5432"
DB_NAME="mdadb"
DB_USER="mdauser"

# 화면 지우기
clear_screen() {
    clear
}

# 헤더 출력
print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}   선박 궤적 시스템 실시간 모니터링   ${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo -e "시간: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""
}

# 애플리케이션 상태 확인
check_app_status() {
    echo -e "${GREEN}[애플리케이션 상태]${NC}"
    
    # Health check
    health=$(curl -s "http://$APP_HOST:$APP_PORT/actuator/health" | jq -r '.status' 2>/dev/null || echo "UNKNOWN")
    if [ "$health" == "UP" ]; then
        echo -e "상태: ${GREEN}$health${NC}"
    else
        echo -e "상태: ${RED}$health${NC}"
    fi
    
    # 실행 중인 Job
    running_jobs=$(curl -s "http://$APP_HOST:$APP_PORT/admin/batch/job/running" | jq -r '.[]' 2>/dev/null || echo "N/A")
    echo -e "실행 중인 Job: $running_jobs"
    
    # 메트릭 요약
    metrics=$(curl -s "http://$APP_HOST:$APP_PORT/admin/metrics/summary" 2>/dev/null)
    if [ ! -z "$metrics" ]; then
        echo -e "처리된 레코드: $(echo $metrics | jq -r '.processedRecords // "N/A"')"
        echo -e "평균 처리 시간: $(echo $metrics | jq -r '.avgProcessingTime // "N/A"')ms"
    fi
    echo ""
}

# 시스템 리소스 모니터링
check_system_resources() {
    echo -e "${GREEN}[시스템 리소스]${NC}"
    
    # CPU 사용률
    cpu_usage=$(top -bn1 | grep "Cpu(s)" | awk '{print $2}' | cut -d'%' -f1)
    echo -e "CPU 사용률: ${cpu_usage}%"
    
    # 메모리 사용률
    mem_info=$(free -g | grep "Mem:")
    mem_total=$(echo $mem_info | awk '{print $2}')
    mem_used=$(echo $mem_info | awk '{print $3}')
    mem_percent=$(awk "BEGIN {printf \"%.1f\", ($mem_used/$mem_total)*100}")
    echo -e "메모리: ${mem_used}GB / ${mem_total}GB (${mem_percent}%)"
    
    # 디스크 사용률
    disk_usage=$(df -h / | tail -1 | awk '{print $5}')
    echo -e "디스크 사용률: $disk_usage"
    echo ""
}

# 데이터베이스 연결 모니터링
check_db_connections() {
    echo -e "${GREEN}[데이터베이스 연결]${NC}"
    
    # CollectDB 연결
    collect_conn=$(PGPASSWORD=$DB_PASS psql -h $DB_HOST_COLLECT -U $DB_USER -d $DB_NAME -t -c "SELECT count(*) FROM pg_stat_activity WHERE datname='$DB_NAME';" 2>/dev/null || echo "N/A")
    echo -e "CollectDB 연결: $collect_conn"
    
    # QueryDB 연결
    query_conn=$(PGPASSWORD=$DB_PASS psql -h $DB_HOST_QUERY -U $DB_USER -d $DB_NAME -t -c "SELECT count(*) FROM pg_stat_activity WHERE datname='$DB_NAME';" 2>/dev/null || echo "N/A")
    echo -e "QueryDB 연결: $query_conn"
    echo ""
}

# WebSocket 연결 모니터링
check_websocket_status() {
    echo -e "${GREEN}[WebSocket 상태]${NC}"
    
    ws_status=$(curl -s "http://$APP_HOST:$APP_PORT/api/websocket/status" 2>/dev/null)
    if [ ! -z "$ws_status" ]; then
        echo -e "활성 세션: $(echo $ws_status | jq -r '.activeSessions // "N/A"')"
        echo -e "활성 쿼리: $(echo $ws_status | jq -r '.activeQueries // "N/A"')"
        echo -e "처리된 메시지: $(echo $ws_status | jq -r '.totalMessagesProcessed // "N/A"')"
    else
        echo -e "WebSocket 상태를 가져올 수 없습니다."
    fi
    echo ""
}

# 성능 최적화 상태
check_performance_status() {
    echo -e "${GREEN}[성능 최적화 상태]${NC}"
    
    perf_status=$(curl -s "http://$APP_HOST:$APP_PORT/api/v1/performance/status" 2>/dev/null)
    if [ ! -z "$perf_status" ]; then
        echo -e "동적 청크 크기: $(echo $perf_status | jq -r '.currentChunkSize // "N/A"')"
        echo -e "캐시 히트율: $(echo $perf_status | jq -r '.cacheHitRate // "N/A"')%"
        echo -e "메모리 사용률: $(echo $perf_status | jq -r '.memoryUsage.usedPercentage // "N/A"')%"
    else
        echo -e "성능 상태를 가져올 수 없습니다."
    fi
    echo ""
}

# 실시간 로그 tail (별도 터미널에서 실행)
tail_logs() {
    echo -e "${GREEN}[최근 로그]${NC}"
    echo "애플리케이션 로그는 별도 터미널에서 확인하세요:"
    echo "tail -f /path/to/application.log"
    echo ""
}

# 메인 루프
main() {
    while true; do
        clear_screen
        print_header
        check_app_status
        check_system_resources
        check_db_connections
        check_websocket_status
        check_performance_status
        
        echo -e "${YELLOW}5초 후 갱신... (Ctrl+C로 종료)${NC}"
        sleep 5
    done
}

# 트랩 설정
trap 'echo -e "\n${RED}모니터링 종료${NC}"; exit 0' INT TERM

# 실행
main
