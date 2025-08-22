# Signal Batch 배포 스크립트 가이드

## 개요

Windows 개발PC에서 10.26.252.48 서버로 자동 배포하는 스크립트 모음입니다.
기존 `vessel-batch-control.sh`와 연동하여 안전한 배포와 롤백을 지원합니다.

## 사전 요구사항

### 1. SSH 키 설정
```bash
# Windows에서 SSH 키 생성 (PowerShell 또는 Git Bash)
ssh-keygen -t rsa -b 4096

# 공개키를 서버에 복사
ssh-copy-id mda@10.26.252.48

# 또는 수동으로 복사
scp ~/.ssh/id_rsa.pub mda@10.26.252.48:~/.ssh/authorized_keys
```

### 2. 서버 환경 확인
- 서버 경로: `/devdata/apps/bridge-db-monitoring`
- 제어 스크립트: `vessel-batch-control.sh` 실행 가능
- 백업 디렉토리: `/devdata/apps/bridge-db-monitoring/backups` (자동 생성)

## 스크립트 목록

### 빌드 관련

#### `build.bat`
- Maven 빌드 실행
- 테스트 실행 여부 선택 가능
- 버전 정보 파일 생성
- JAR 파일 생성 확인

```cmd
# 기본 사용법
build.bat
```

### 배포 관련

#### `deploy.bat`
- 자동 백업 생성 (타임스탬프 기반)
- 서버 애플리케이션 중지
- 새 JAR 파일 전송
- 애플리케이션 시작
- 헬스체크 수행
- 배포 실패 시 자동 롤백 제안

```cmd
# 기본 사용법
deploy.bat
```

#### `quick-deploy.bat`
- build.bat + deploy.bat를 연속 실행
- 빠른 개발 주기에 적합

```cmd
# 빠른 빌드+배포
quick-deploy.bat
```

### 롤백 관리

#### `rollback.bat`
- 이전 버전으로 안전한 롤백
- 백업 파일 목록 조회
- 현재 버전 임시 백업 후 롤백

```cmd
# 사용 가능한 백업 버전 확인
rollback.bat

# 특정 버전으로 롤백
rollback.bat 20250822_143052
```

### 모니터링

#### `server-status.bat`
- 애플리케이션 상태 확인
- 헬스체크
- 시스템 리소스 확인
- 최근 로그 요약

```cmd
# 서버 상태 확인
server-status.bat
```

#### `server-logs.bat`
- 로그 확인 및 모니터링
- 에러 로그 필터링
- 성능 통계 조회

```cmd
# 최근 50줄 로그
server-logs.bat

# 실시간 로그 모니터링
server-logs.bat tail

# 에러 로그만 표시
server-logs.bat errors

# 성능 통계
server-logs.bat stats
```

## 사용 시나리오

### 1. 일반적인 배포 흐름
```cmd
# 1. 빌드
build.bat

# 2. 배포
deploy.bat

# 3. 상태 확인
server-status.bat
```

### 2. 빠른 개발 배포
```cmd
# 빌드+배포 한번에
quick-deploy.bat

# 상태 확인
server-status.bat
```

### 3. 문제 발생 시 롤백
```cmd
# 백업 버전 확인
rollback.bat

# 이전 버전으로 롤백
rollback.bat 20250822_143052

# 상태 확인
server-status.bat
```

### 4. 모니터링
```cmd
# 전체 상태 확인
server-status.bat

# 실시간 로그 모니터링
server-logs.bat tail

# 에러 확인
server-logs.bat errors
```

## 안전 기능

### 1. 자동 백업
- 배포 시 현재 버전 자동 백업 (타임스탬프 기반)
- 최근 7개 버전 유지 (자동 정리)
- 롤백 시 현재 버전 임시 백업

### 2. 검증 절차
- SSH 연결 테스트
- JAR 파일 존재 확인
- 애플리케이션 시작 확인
- 헬스체크 수행

### 3. 실패 처리
- 배포 실패 시 자동 롤백 제안
- 단계별 실패 지점 명확한 표시
- 수동 복구 명령어 제공

## 설정 변경

각 스크립트 상단의 설정 변수를 수정하여 환경에 맞게 조정 가능:

```batch
REM 설정 변수
set SERVER_IP=10.26.252.48
set SERVER_USER=mda
set SERVER_PATH=/devdata/apps/bridge-db-monitoring
set JAR_NAME=vessel-batch-aggregation.jar
```

## 문제 해결

### SSH 연결 실패
```cmd
# SSH 키 확인
ssh mda@10.26.252.48 "echo test"

# SSH 키 재설정
ssh-keygen -t rsa
ssh-copy-id mda@10.26.252.48
```

### 배포 실패
```cmd
# 서버 상태 확인
server-status.bat

# 로그 확인
server-logs.bat errors

# 수동 롤백
rollback.bat [TIMESTAMP]
```

### 애플리케이션 시작 실패
```cmd
# 서버에서 직접 확인
ssh mda@10.26.252.48
cd /devdata/apps/bridge-db-monitoring
./vessel-batch-control.sh status
./vessel-batch-control.sh logs
```

## 서버 명령어 연동

스크립트들은 서버의 기존 제어 스크립트와 연동됩니다:

- `vessel-batch-control.sh start` - 애플리케이션 시작
- `vessel-batch-control.sh stop` - 애플리케이션 중지
- `vessel-batch-control.sh restart` - 애플리케이션 재시작
- `vessel-batch-control.sh status` - 상태 확인
- `vessel-batch-control.sh logs` - 로그 모니터링
- `vessel-batch-control.sh errors` - 에러 로그
- `vessel-batch-control.sh stats` - 성능 통계

이를 통해 기존 운영 환경과의 호환성을 유지하면서 자동화된 배포 환경을 제공합니다.