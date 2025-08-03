#!/bin/bash

# 선박 궤적 집계 시스템 부하 테스트 실행 스크립트
# 실행 전 JMeter가 설치되어 있어야 합니다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
JMETER_HOME="${JMETER_HOME:-/opt/jmeter}"
RESULTS_DIR="$PROJECT_ROOT/load-test-results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 함수: 메시지 출력
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# JMeter 설치 확인
check_jmeter() {
    if [ ! -d "$JMETER_HOME" ]; then
        log_error "JMeter가 설치되어 있지 않습니다. JMETER_HOME을 설정하세요."
        exit 1
    fi
    
    if [ ! -f "$JMETER_HOME/bin/jmeter" ]; then
        log_error "JMeter 실행 파일을 찾을 수 없습니다: $JMETER_HOME/bin/jmeter"
        exit 1
    fi
    
    log_info "JMeter 경로: $JMETER_HOME"
}

# 결과 디렉토리 생성
create_results_dir() {
    mkdir -p "$RESULTS_DIR/$TIMESTAMP"
    log_info "결과 디렉토리 생성: $RESULTS_DIR/$TIMESTAMP"
}

# 시스템 상태 모니터링 시작
start_monitoring() {
    log_info "시스템 모니터링 시작..."
    
    # CPU, 메모리, 네트워크 사용률 모니터링
    nohup vmstat 5 > "$RESULTS_DIR/$TIMESTAMP/vmstat.log" 2>&1 &
    VMSTAT_PID=$!
    
    nohup iostat -x 5 > "$RESULTS_DIR/$TIMESTAMP/iostat.log" 2>&1 &
    IOSTAT_PID=$!
    
    # 데이터베이스 연결 모니터링
    nohup watch -n 5 "psql -h 10.26.252.48 -U mdauser -d mdadb -c 'SELECT count(*) FROM pg_stat_activity;'" > "$RESULTS_DIR/$TIMESTAMP/db_connections.log" 2>&1 &
    DB_MON_PID=$!
    
    echo "$VMSTAT_PID $IOSTAT_PID $DB_MON_PID" > "$RESULTS_DIR/$TIMESTAMP/monitoring.pids"
}

# 시스템 모니터링 중지
stop_monitoring() {
    log_info "시스템 모니터링 중지..."
    
    if [ -f "$RESULTS_DIR/$TIMESTAMP/monitoring.pids" ]; then
        while read pid; do
            kill $pid 2>/dev/null
        done < "$RESULTS_DIR/$TIMESTAMP/monitoring.pids"
        rm "$RESULTS_DIR/$TIMESTAMP/monitoring.pids"
    fi
}

# JMeter 테스트 실행
run_jmeter_test() {
    local test_file=$1
    local test_name=$(basename "$test_file" .jmx)
    
    log_info "JMeter 테스트 실행: $test_name"
    
    # JMeter 실행
    "$JMETER_HOME/bin/jmeter" \
        -n \
        -t "$test_file" \
        -l "$RESULTS_DIR/$TIMESTAMP/${test_name}-results.jtl" \
        -e \
        -o "$RESULTS_DIR/$TIMESTAMP/${test_name}-report" \
        -Jjmeter.save.saveservice.output_format=csv \
        -Jjmeter.save.saveservice.assertion_results_failure_message=true \
        -Jjmeter.save.saveservice.data_type=true \
        -Jjmeter.save.saveservice.label=true \
        -Jjmeter.save.saveservice.response_code=true \
        -Jjmeter.save.saveservice.response_data.on_error=true \
        -Jjmeter.save.saveservice.response_message=true \
        -Jjmeter.save.saveservice.successful=true \
        -Jjmeter.save.saveservice.thread_name=true \
        -Jjmeter.save.saveservice.time=true \
        -Jjmeter.save.saveservice.connect_time=true \
        -Jjmeter.save.saveservice.latency=true \
        -Jjmeter.save.saveservice.bytes=true \
        -Jjmeter.save.saveservice.sent_bytes=true \
        -Jjmeter.save.saveservice.url=true
    
    if [ $? -eq 0 ]; then
        log_info "테스트 완료: $test_name"
        log_info "결과 파일: $RESULTS_DIR/$TIMESTAMP/${test_name}-results.jtl"
        log_info "HTML 리포트: $RESULTS_DIR/$TIMESTAMP/${test_name}-report/index.html"
    else
        log_error "테스트 실패: $test_name"
        return 1
    fi
}

# WebSocket 부하 테스트
run_websocket_test() {
    log_info "WebSocket 부하 테스트 준비..."
    
    # Python 스크립트로 WebSocket 테스트 실행
    cat > "$RESULTS_DIR/$TIMESTAMP/websocket_load_test.py" << 'EOF'
import asyncio
import websockets
import json
import time
from datetime import datetime, timedelta
import statistics

class WebSocketLoadTester:
    def __init__(self, base_url, num_clients, queries_per_client):
        self.base_url = base_url
        self.num_clients = num_clients
        self.queries_per_client = queries_per_client
        self.metrics = {
            'total_queries': 0,
            'successful_queries': 0,
            'failed_queries': 0,
            'latencies': [],
            'throughput': []
        }
    
    async def client_session(self, client_id):
        async with websockets.connect(f"{self.base_url}/ws-tracks") as websocket:
            for query_id in range(self.queries_per_client):
                try:
                    # 쿼리 요청 생성
                    query = {
                        "startTime": (datetime.now() - timedelta(days=7)).isoformat(),
                        "endTime": datetime.now().isoformat(),
                        "viewport": {
                            "minLon": 124.0,
                            "maxLon": 132.0,
                            "minLat": 33.0,
                            "maxLat": 38.0
                        },
                        "chunkSize": 1000
                    }
                    
                    start_time = time.time()
                    await websocket.send(json.dumps(query))
                    
                    # 응답 수신
                    chunks_received = 0
                    while True:
                        response = await websocket.recv()
                        data = json.loads(response)
                        chunks_received += 1
                        
                        if data.get('isLastChunk', False):
                            break
                    
                    end_time = time.time()
                    latency = (end_time - start_time) * 1000  # ms
                    
                    self.metrics['latencies'].append(latency)
                    self.metrics['successful_queries'] += 1
                    
                    print(f"Client {client_id} - Query {query_id}: {latency:.2f}ms, {chunks_received} chunks")
                    
                except Exception as e:
                    print(f"Client {client_id} - Query {query_id} failed: {str(e)}")
                    self.metrics['failed_queries'] += 1
                
                self.metrics['total_queries'] += 1
                await asyncio.sleep(1)  # 쿼리 간 딜레이
    
    async def run_test(self):
        print(f"Starting WebSocket load test with {self.num_clients} clients...")
        start_time = time.time()
        
        # 모든 클라이언트 동시 실행
        tasks = []
        for i in range(self.num_clients):
            task = asyncio.create_task(self.client_session(i))
            tasks.append(task)
        
        await asyncio.gather(*tasks)
        
        end_time = time.time()
        total_duration = end_time - start_time
        
        # 결과 분석
        print("\n=== 부하 테스트 결과 ===")
        print(f"총 실행 시간: {total_duration:.2f}초")
        print(f"총 쿼리 수: {self.metrics['total_queries']}")
        print(f"성공: {self.metrics['successful_queries']}")
        print(f"실패: {self.metrics['failed_queries']}")
        
        if self.metrics['latencies']:
            print(f"평균 레이턴시: {statistics.mean(self.metrics['latencies']):.2f}ms")
            print(f"최소 레이턴시: {min(self.metrics['latencies']):.2f}ms")
            print(f"최대 레이턴시: {max(self.metrics['latencies']):.2f}ms")
            print(f"중앙값 레이턴시: {statistics.median(self.metrics['latencies']):.2f}ms")
        
        print(f"처리량: {self.metrics['total_queries'] / total_duration:.2f} queries/sec")

if __name__ == "__main__":
    tester = WebSocketLoadTester(
        base_url="ws://10.26.252.48:8090",
        num_clients=10,
        queries_per_client=5
    )
    asyncio.run(tester.run_test())
EOF
    
    # Python WebSocket 테스트 실행
    if command -v python3 &> /dev/null; then
        python3 "$RESULTS_DIR/$TIMESTAMP/websocket_load_test.py" > "$RESULTS_DIR/$TIMESTAMP/websocket_test_results.log" 2>&1
    else
        log_warn "Python3가 설치되어 있지 않아 WebSocket 테스트를 건너뜁니다."
    fi
}

# 메인 실행 함수
main() {
    log_info "선박 궤적 집계 시스템 부하 테스트 시작"
    log_info "타임스탬프: $TIMESTAMP"
    
    # JMeter 확인
    check_jmeter
    
    # 결과 디렉토리 생성
    create_results_dir
    
    # 시스템 모니터링 시작
    start_monitoring
    
    # 애플리케이션 상태 확인
    log_info "애플리케이션 상태 확인..."
    curl -s "http://10.26.252.48:8090/actuator/health" > "$RESULTS_DIR/$TIMESTAMP/app_health_before.json"
    
    # JMeter 테스트 실행
    if [ -f "$PROJECT_ROOT/src/main/resources/jmeter/comprehensive-load-test.jmx" ]; then
        run_jmeter_test "$PROJECT_ROOT/src/main/resources/jmeter/comprehensive-load-test.jmx"
    fi
    
    # WebSocket 테스트 실행
    run_websocket_test
    
    # 10분간 부하 테스트 실행
    log_info "부하 테스트 진행 중... (10분)"
    sleep 600
    
    # 시스템 모니터링 중지
    stop_monitoring
    
    # 최종 애플리케이션 상태 확인
    curl -s "http://10.26.252.48:8090/actuator/health" > "$RESULTS_DIR/$TIMESTAMP/app_health_after.json"
    
    # 결과 요약
    log_info "부하 테스트 완료!"
    log_info "결과 디렉토리: $RESULTS_DIR/$TIMESTAMP"
    
    # 간단한 결과 분석
    if [ -f "$RESULTS_DIR/$TIMESTAMP/comprehensive-load-test-results.jtl" ]; then
        log_info "JMeter 결과 요약:"
        awk -F',' 'NR>1 {sum+=$2; count++} END {print "평균 응답 시간: " sum/count " ms"}' "$RESULTS_DIR/$TIMESTAMP/comprehensive-load-test-results.jtl"
    fi
}

# 스크립트 실행
main "$@"
