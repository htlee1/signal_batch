#!/bin/bash

# Query DB 서버에서 최적화된 실행 스크립트
# Rocky Linux 환경에 맞춰 조정됨
# Java 17 경로 명시적 지정

# 애플리케이션 경로
APP_HOME="/devdata/apps/bridge-db-monitoring"
JAR_FILE="$APP_HOME/vessel-batch-aggregation.jar"

# Java 17 경로
JAVA_HOME="/devdata/apps/jdk-17.0.8"
JAVA_BIN="$JAVA_HOME/bin/java"

# 로그 디렉토리
LOG_DIR="$APP_HOME/logs"
mkdir -p $LOG_DIR

echo "================================================"
echo "Vessel Batch Aggregation - Query Server Edition"
echo "Start Time: $(date)"
echo "================================================"

# 경로 확인
echo "Environment Check:"
echo "- App Home: $APP_HOME"
echo "- JAR File: $JAR_FILE"
echo "- Java Path: $JAVA_BIN"
echo "- Java Version: $($JAVA_BIN -version 2>&1 | head -1)"

# JAR 파일 존재 확인
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found at $JAR_FILE"
    exit 1
fi

# Java 실행 파일 확인
if [ ! -x "$JAVA_BIN" ]; then
    echo "ERROR: Java not found or not executable at $JAVA_BIN"
    exit 1
fi

# 서버 정보 확인
echo ""
echo "Server Info:"
echo "- Hostname: $(hostname)"
echo "- CPU Cores: $(nproc)"
echo "- Total Memory: $(free -h | grep Mem | awk '{print $2}')"
echo "- PostgreSQL Version: $(psql --version 2>/dev/null | head -1 || echo 'PostgreSQL client not in PATH')"

# 환경 변수 설정 (localhost 최적화)
export SPRING_PROFILES_ACTIVE=dev

# Query DB와 Batch Meta DB를 localhost로 오버라이드
export SPRING_DATASOURCE_QUERY_JDBC_URL="jdbc:postgresql://localhost:5432/mdadb?currentSchema=signal&options=-csearch_path=signal,public&assumeMinServerVersion=12&reWriteBatchedInserts=true"
export SPRING_DATASOURCE_BATCH_JDBC_URL="jdbc:postgresql://localhost:5432/mdadb?currentSchema=public&assumeMinServerVersion=12&reWriteBatchedInserts=true"

# 서버 CPU 코어 수에 따른 병렬 처리 조정
CPU_CORES=$(nproc)
export VESSEL_BATCH_PARTITION_SIZE=$((CPU_CORES * 2))
export VESSEL_BATCH_BULK_INSERT_PARALLEL_THREADS=$((CPU_CORES / 2))

echo ""
echo "Optimized Settings:"
echo "- Partition Size: $VESSEL_BATCH_PARTITION_SIZE"
echo "- Parallel Threads: $VESSEL_BATCH_BULK_INSERT_PARALLEL_THREADS"
echo "- Query DB: localhost (optimized)"
echo "- Batch Meta DB: localhost (optimized)"

# JVM 옵션 (서버 메모리에 맞게 조정)
TOTAL_MEM=$(free -g | grep Mem | awk '{print $2}')
JVM_HEAP=$((TOTAL_MEM / 4))  # 전체 메모리의 25% 사용

# 최소 16GB, 최대 64GB로 제한
if [ $JVM_HEAP -lt 16 ]; then
    JVM_HEAP=16
elif [ $JVM_HEAP -gt 64 ]; then
    JVM_HEAP=64
fi

JAVA_OPTS="-Xms${JVM_HEAP}g -Xmx${JVM_HEAP}g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+UseStringDeduplication \
    -XX:+ParallelRefProcEnabled \
    -XX:ParallelGCThreads=$((CPU_CORES / 2)) \
    -XX:ConcGCThreads=$((CPU_CORES / 4)) \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=$LOG_DIR/heapdump.hprof \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Seoul \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.profiles.active=dev"

echo "- JVM Heap Size: ${JVM_HEAP}GB"

# 기존 프로세스 확인 및 종료
echo ""
echo "Checking for existing process..."
PID=$(pgrep -f "$JAR_FILE")
if [ ! -z "$PID" ]; then
    echo "Stopping existing process (PID: $PID)..."
    kill -15 $PID
    
    # 프로세스 종료 대기 (최대 30초)
    for i in {1..30}; do
        if ! kill -0 $PID 2>/dev/null; then
            echo "Process stopped successfully."
            break
        fi
        if [ $i -eq 30 ]; then
            echo "Force killing process..."
            kill -9 $PID
        fi
        sleep 1
    done
fi

# 작업 디렉토리로 이동
cd $APP_HOME

# 애플리케이션 실행 (nice로 우선순위 조정)
echo ""
echo "Starting application with reduced priority..."
echo "Command: nice -n 10 $JAVA_BIN $JAVA_OPTS -jar $JAR_FILE"
echo ""

# nohup으로 백그라운드 실행
nohup nice -n 10 $JAVA_BIN $JAVA_OPTS -jar $JAR_FILE \
    > $LOG_DIR/app.log 2>&1 &

NEW_PID=$!
echo "Application started with PID: $NEW_PID"

# PID 파일 생성
echo $NEW_PID > $APP_HOME/vessel-batch.pid

# 시작 확인 (30초 대기)
echo "Waiting for application startup..."
STARTUP_SUCCESS=false
for i in {1..30}; do
    if grep -q "Started SignalBatchApplication" $LOG_DIR/app.log 2>/dev/null; then
        echo "✅ Application started successfully!"
        STARTUP_SUCCESS=true
        break
    fi
    echo -n "."
    sleep 1
done

if [ "$STARTUP_SUCCESS" = false ]; then
    echo ""
    echo "⚠️  Application startup timeout. Check logs for errors."
    echo "Log file: $LOG_DIR/app.log"
    tail -20 $LOG_DIR/app.log
fi

echo ""
echo "================================================"
echo "Deployment Complete!"
echo "- PID: $NEW_PID"
echo "- PID File: $APP_HOME/vessel-batch.pid"
echo "- Log: $LOG_DIR/app.log"
echo "- Monitor: tail -f $LOG_DIR/app.log"
echo "================================================"

# 초기 상태 확인
sleep 5
echo ""
echo "Initial Status Check:"
curl -s http://localhost:8090/actuator/health 2>/dev/null | python -m json.tool || echo "Health endpoint not yet available"

# 리소스 사용량 표시
echo ""
echo "Resource Usage:"
ps aux | grep $NEW_PID | grep -v grep

# 빠른 명령어 안내
echo ""
echo "Useful Commands:"
echo "- Stop: kill -15 \$(cat $APP_HOME/vessel-batch.pid)"
echo "- Logs: tail -f $LOG_DIR/app.log"
echo "- Status: curl http://localhost:8090/actuator/health"
echo "- Monitor: $APP_HOME/monitor-query-server.sh"
