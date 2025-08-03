# 파일 검색 가이드

## 효과적인 파일 검색 방법

### 1. 계층적 디렉토리 탐색
```bash
# 최상위에서 시작
filesystem:list_directory path="src/main/java/gc/mda/signal_batch"

# 하위 디렉토리 확인
filesystem:list_directory path="src/main/java/gc/mda/signal_batch/controller"
filesystem:list_directory path="src/main/java/gc/mda/signal_batch/controller/websocket"
```

### 2. 검색 시 주의사항
- `filesystem:search_files`는 파일명 패턴만 매칭
- 하위 디렉토리는 자동으로 검색하지 않음
- 정확한 경로를 모를 때는 계층적 탐색 필요

### 3. WebSocket 관련 파일 위치
```
controller/
├── websocket/
│   └── StompTrackController.java  # 실제 WebSocket 컨트롤러
├── AbnormalTrackController.java
└── ...

service/
├── StompTrackStreamingService.java
├── ChunkedTrackStreamingService.java
└── ...

dto/
└── websocket/
    ├── TrackQueryRequest.java     # 올바른 DTO 위치
    ├── ChunkedTrackResponse.java
    └── ...
```

### 4. 중복 파일 확인 방법
```bash
# 같은 이름의 파일이 여러 패키지에 있을 수 있음
filesystem:search_files pattern="TrackQueryRequest.java"
# dto/TrackQueryRequest.java와 dto/websocket/TrackQueryRequest.java 구분 필요
```
