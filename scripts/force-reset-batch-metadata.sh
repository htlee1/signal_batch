#!/bin/bash

# Spring Batch 메타데이터 강제 초기화 스크립트
# 실행 중인 작업 상태와 관계없이 강제로 초기화

echo "================================================"
echo "Spring Batch Metadata FORCE Reset"
echo "WARNING: This will FORCE delete ALL batch job history!"
echo "         Including running jobs!"
echo "Time: $(date)"
echo "================================================"

# 색상 코드
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 데이터베이스 연결 정보
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="mdadb"
DB_USER="mda"
DB_SCHEMA="public"

echo -e "${RED}⚠️  CRITICAL WARNING: This is a FORCE reset operation!${NC}"
echo "This will:"
echo "- Delete ALL batch job history"
echo "- Clear ALL running job states"
echo "- Reset ALL sequences"
echo "- Cannot be undone (except from backup)"
echo ""
echo -e "${YELLOW}This should only be used when normal reset fails!${NC}"
echo ""
read -p "Type 'FORCE RESET' to confirm: " CONFIRM

if [ "$CONFIRM" != "FORCE RESET" ]; then
    echo "Operation cancelled."
    exit 0
fi

echo ""
echo "1. Creating full backup before force reset..."

# 백업 디렉토리 생성
BACKUP_DIR="/devdata/apps/bridge-db-monitoring/backup"
mkdir -p $BACKUP_DIR

# 백업 파일명
BACKUP_FILE="$BACKUP_DIR/batch_metadata_FORCE_backup_$(date +%Y%m%d_%H%M%S).sql"

# 전체 메타데이터 백업 (스키마 포함)
pg_dump -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
    --schema=$DB_SCHEMA \
    --table="batch_*" \
    --file=$BACKUP_FILE 2>/dev/null

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Full backup created: $BACKUP_FILE${NC}"
else
    echo -e "${YELLOW}⚠ Backup may have failed, but continuing...${NC}"
fi

echo ""
echo "2. Stopping application if running..."

# PID 확인
if [ -f "/devdata/apps/bridge-db-monitoring/vessel-batch.pid" ]; then
    PID=$(cat /devdata/apps/bridge-db-monitoring/vessel-batch.pid)
    if kill -0 $PID 2>/dev/null; then
        echo "   Stopping application (PID: $PID)..."
        kill -15 $PID
        sleep 5
        if kill -0 $PID 2>/dev/null; then
            echo "   Force killing application..."
            kill -9 $PID
        fi
    fi
fi

echo ""
echo "3. FORCE resetting batch metadata tables..."

# CASCADE를 사용한 강제 초기화
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOF
-- 트랜잭션 시작
BEGIN;

-- 외래 키 제약 임시 비활성화
SET session_replication_role = 'replica';

-- 모든 배치 테이블 강제 초기화
TRUNCATE TABLE $DB_SCHEMA.batch_step_execution_context CASCADE;
TRUNCATE TABLE $DB_SCHEMA.batch_step_execution CASCADE;
TRUNCATE TABLE $DB_SCHEMA.batch_job_execution_context CASCADE;
TRUNCATE TABLE $DB_SCHEMA.batch_job_execution_params CASCADE;
TRUNCATE TABLE $DB_SCHEMA.batch_job_execution CASCADE;
TRUNCATE TABLE $DB_SCHEMA.batch_job_instance CASCADE;

-- 시퀀스 강제 리셋
ALTER SEQUENCE IF EXISTS $DB_SCHEMA.batch_job_execution_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS $DB_SCHEMA.batch_job_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS $DB_SCHEMA.batch_step_execution_seq RESTART WITH 1;

-- 외래 키 제약 재활성화
SET session_replication_role = 'origin';

-- 커밋
COMMIT;

-- 통계 업데이트
ANALYZE $DB_SCHEMA.batch_job_instance;
ANALYZE $DB_SCHEMA.batch_job_execution;
ANALYZE $DB_SCHEMA.batch_job_execution_params;
ANALYZE $DB_SCHEMA.batch_job_execution_context;
ANALYZE $DB_SCHEMA.batch_step_execution;
ANALYZE $DB_SCHEMA.batch_step_execution_context;
EOF

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Batch metadata tables FORCE reset successfully${NC}"
else
    echo -e "${RED}✗ Force reset encountered errors, but may have partially succeeded${NC}"
fi

echo ""
echo "4. Verifying force reset..."

# 각 테이블 개별 확인
for table in batch_job_instance batch_job_execution batch_job_execution_params batch_job_execution_context batch_step_execution batch_step_execution_context; do
    COUNT=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "
    SELECT COUNT(*) FROM $DB_SCHEMA.$table;" 2>/dev/null | xargs)
    
    if [ -z "$COUNT" ]; then
        COUNT="ERROR"
    fi
    
    if [ "$COUNT" = "0" ]; then
        echo -e "   ${GREEN}✓${NC} $table: $COUNT records"
    elif [ "$COUNT" = "ERROR" ]; then
        echo -e "   ${RED}✗${NC} $table: Could not query"
    else
        echo -e "   ${YELLOW}⚠${NC} $table: $COUNT records remaining"
    fi
done

echo ""
echo "5. Optional: Clear ALL aggregation data (complete fresh start)"
read -p "Do you want to clear ALL aggregation data too? (yes/no): " CLEAR_ALL

if [ "$CLEAR_ALL" = "yes" ]; then
    echo ""
    echo "Clearing ALL aggregation data..."
    
    psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOF
BEGIN;

-- 강제로 모든 집계 데이터 초기화
SET session_replication_role = 'replica';

-- 최신 위치 정보
TRUNCATE TABLE signal.t_vessel_latest_position CASCADE;

-- 모든 파티션 테이블 초기화
DO \$\$
DECLARE
    r RECORD;
BEGIN
    FOR r IN 
        SELECT tablename 
        FROM pg_tables 
        WHERE schemaname = 'signal' 
        AND (tablename LIKE 't_tile_summary_%' 
             OR tablename LIKE 't_area_statistics_%' 
             OR tablename LIKE 't_vessel_daily_tracks_%')
    LOOP
        EXECUTE 'TRUNCATE TABLE signal.' || r.tablename || ' CASCADE';
        RAISE NOTICE 'Truncated table: signal.%', r.tablename;
    END LOOP;
END\$\$;

-- 배치 성능 메트릭
TRUNCATE TABLE signal.t_batch_performance_metrics CASCADE;

SET session_replication_role = 'origin';

COMMIT;
EOF

    echo -e "${GREEN}✓ All aggregation data cleared${NC}"
fi

echo ""
echo "================================================"
echo "FORCE Reset Complete!"
echo ""
echo -e "${YELLOW}IMPORTANT: The application needs to be restarted!${NC}"
echo ""
echo "Next steps:"
echo "1. Start the application:"
echo "   cd /devdata/apps/bridge-db-monitoring"
echo "   ./run-on-query-server.sh"
echo ""
echo "2. Verify health:"
echo "   curl http://localhost:8090/actuator/health"
echo ""
echo "3. Start fresh batch job:"
echo "   curl -X POST http://localhost:8090/admin/batch/job/run \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"jobName\": \"vesselAggregationJob\", \"parameters\": {\"tileLevel\": 1}}'"
echo ""
echo "Full backup saved to: $BACKUP_FILE"
echo "================================================"

# 자동 시작 옵션
echo ""
read -p "Do you want to start the application now? (yes/no): " START_NOW

if [ "$START_NOW" = "yes" ]; then
    echo "Starting application..."
    cd /devdata/apps/bridge-db-monitoring
    ./run-on-query-server.sh
fi
