// Animation Controller for vessel tracking
// Optimized for 20,000-30,000 vessels with dynamic FPS adjustment

class VesselAnimationController {
    constructor(map, deckOverlay, trackStore) {
        this.map = map;
        this.deckOverlay = deckOverlay;
        this.trackStore = trackStore;
        
        this.animationFrame = null;
        this.lastFrameTime = 0;
        this.frameCount = 0;
        this.fpsTimer = 0;
        this.actualFPS = 0;
        
        // 성능 측정
        this.performanceMetrics = {
            frameTime: 0,
            updateTime: 0,
            renderTime: 0
        };
        
        // 아이콘 레이어 설정
        this.iconMapping = '/static/icons/vessel-icon.png';
        this.iconAtlas = null;
    }
    
    initialize() {
        // 아이콘 아틀라스 생성 (성능 최적화)
        this.createIconAtlas();
        
        // 타임라인 UI 생성
        this.createTimelineUI();
        
        // 이벤트 리스너
        this.setupEventListeners();
        
        // 초기 시간 설정
        const state = this.trackStore.getState();
        if (state.animation.startTime && state.animation.endTime) {
            this.updateUI();
            this.updateVesselPositions();
        }
        
        console.log('Animation controller initialized');
    }
    
    createIconAtlas() {
        // 간단한 원형 아이콘을 Canvas로 생성
        const canvas = document.createElement('canvas');
        canvas.width = 64;
        canvas.height = 64;
        const ctx = canvas.getContext('2d');
        
        // 원형 선박 아이콘
        ctx.beginPath();
        ctx.arc(32, 32, 20, 0, 2 * Math.PI);
        ctx.fillStyle = '#ffffff';
        ctx.fill();
        ctx.strokeStyle = '#000000';
        ctx.lineWidth = 2;
        ctx.stroke();
        
        // 방향 표시
        ctx.beginPath();
        ctx.moveTo(32, 12);
        ctx.lineTo(42, 32);
        ctx.lineTo(32, 28);
        ctx.lineTo(22, 32);
        ctx.closePath();
        ctx.fillStyle = '#ff0000';
        ctx.fill();
        
        this.iconAtlas = canvas.toDataURL();
    }
    
    createTimelineUI() {
        // 타임라인 컨테이너 생성
        const timelineContainer = document.createElement('div');
        timelineContainer.id = 'timelineContainer';
        timelineContainer.className = 'timeline-container';
        timelineContainer.innerHTML = `
            <div class="timeline-controls">
                <button id="playBtn" class="btn btn-sm btn-primary">
                    <i class="bi bi-play-fill"></i>
                </button>
                <button id="pauseBtn" class="btn btn-sm btn-secondary" style="display: none;">
                    <i class="bi bi-pause-fill"></i>
                </button>
                <button id="resetBtn" class="btn btn-sm btn-secondary">
                    <i class="bi bi-skip-backward-fill"></i>
                </button>
                
                <div class="speed-control">
                    <label>속도:</label>
                    <select id="speedSelect" class="form-select form-select-sm">
                        <option value="0.5">0.5x</option>
                        <option value="1" selected>1x</option>
                        <option value="2">2x</option>
                        <option value="4">4x</option>
                        <option value="8">8x</option>
                        <option value="50">50x</option>
                        <option value="100">100x</option>
                    </select>
                </div>
                
                <div class="fps-control">
                    <label>FPS:</label>
                    <select id="fpsSelect" class="form-select form-select-sm">
                        <option value="auto" selected>Auto</option>
                        <option value="10">10</option>
                        <option value="15">15</option>
                        <option value="20">20</option>
                        <option value="30">30</option>
                    </select>
                </div>
                
                <div class="time-display">
                    <span id="currentTimeDisplay">--:--:--</span>
                    <span class="text-muted">/</span>
                    <span id="totalTimeDisplay">--:--:--</span>
                </div>
                
                <div class="track-visibility-control">
                    <label class="form-check-label">
                        <input type="checkbox" id="showTracksCheck" class="form-check-input" checked>
                        궤적 표시
                    </label>
                </div>
            </div>
            
            <div class="timeline-bar">
                <input type="range" id="timelineSlider" class="timeline-slider" 
                       min="0" max="1000" value="0" step="1">
                <div class="timeline-progress" id="timelineProgress"></div>
            </div>
            
            <div class="performance-info">
                <span>FPS: <span id="currentFPS">0</span></span>
                <span>선박: <span id="animatingVessels">0</span></span>
                <span>프레임시간: <span id="frameTime">0</span>ms</span>
            </div>
        `;
        
        document.body.appendChild(timelineContainer);
        
        // CSS 추가
        const style = document.createElement('style');
        style.textContent = `
            .timeline-container {
                position: absolute;
                bottom: 220px; /* 로그 패널과 격치지 않도록 조정 */
                left: 10px;
                width: calc(50% - 20px); /* 화면 절반 너비로 제한 */
                max-width: 800px; /* 최대 너비 제한 */
                background: rgba(255, 255, 255, 0.95);
                padding: 15px;
                border-radius: 8px;
                box-shadow: 0 4px 6px rgba(0,0,0,0.1);
                z-index: 1000;
            }
            
            .timeline-controls {
                display: flex;
                align-items: center;
                gap: 15px;
                margin-bottom: 10px;
            }
            
            .timeline-controls button {
                width: 40px;
                height: 32px;
                padding: 0;
            }
            
            .speed-control, .fps-control {
                display: flex;
                align-items: center;
                gap: 5px;
            }
            
            .speed-control label, .fps-control label {
                margin: 0;
                font-size: 14px;
            }
            
            .speed-control select, .fps-control select {
                width: 80px;
            }
            
            .time-display {
                margin-left: auto;
                font-family: monospace;
                font-size: 14px;
            }
            
            .track-visibility-control {
                margin-left: 15px;
                font-size: 14px;
            }
            
            .track-visibility-control input {
                margin-right: 5px;
            }
            
            .timeline-bar {
                position: relative;
                height: 30px;
                background: #f0f0f0;
                border-radius: 15px;
                overflow: hidden;
                margin-bottom: 10px;
            }
            
            .timeline-slider {
                width: 100%;
                height: 30px;
                margin: 0;
                cursor: pointer;
                opacity: 0;
                position: relative;
                z-index: 2;
            }
            
            .timeline-progress {
                position: absolute;
                top: 0;
                left: 0;
                height: 100%;
                background: linear-gradient(90deg, #007bff, #0056b3);
                width: 0%;
                transition: width 0.1s;
                pointer-events: none;
            }
            
            .performance-info {
                display: flex;
                gap: 20px;
                font-size: 12px;
                color: #666;
            }
            
            .performance-info span {
                font-family: monospace;
            }
        `;
        document.head.appendChild(style);
    }
    
    setupEventListeners() {
        // 재생/일시정지
        document.getElementById('playBtn').addEventListener('click', () => this.play());
        document.getElementById('pauseBtn').addEventListener('click', () => this.pause());
        document.getElementById('resetBtn').addEventListener('click', () => this.reset());
        
        // 속도 조절
        document.getElementById('speedSelect').addEventListener('change', (e) => {
            this.trackStore.setAnimationSpeed(parseFloat(e.target.value));
        });
        
        // FPS 조절
        document.getElementById('fpsSelect').addEventListener('change', (e) => {
            if (e.target.value === 'auto') {
                this.trackStore.getState().performance.dynamicFPS = true;
            } else {
                this.trackStore.getState().performance.dynamicFPS = false;
                this.trackStore.setTargetFPS(parseInt(e.target.value));
            }
        });
        
        // 타임라인 슬라이더
        document.getElementById('timelineSlider').addEventListener('input', (e) => {
            const state = this.trackStore.getState();
            const ratio = e.target.value / 1000;
            const time = state.animation.startTime + 
                        (state.animation.endTime - state.animation.startTime) * ratio;
            this.trackStore.setAnimationTime(time);
            this.updateVesselPositions();
        });
        
        // 궤적 표시/숨김 체크박스
        document.getElementById('showTracksCheck').addEventListener('change', (e) => {
            this.updateTrackVisibility(e.target.checked);
        });
    }
    
    play() {
        this.trackStore.toggleAnimation();
        document.getElementById('playBtn').style.display = 'none';
        document.getElementById('pauseBtn').style.display = 'block';
        this.lastFrameTime = performance.now();
        this.animate(performance.now());
    }
    
    pause() {
        this.trackStore.toggleAnimation();
        document.getElementById('playBtn').style.display = 'block';
        document.getElementById('pauseBtn').style.display = 'none';
        if (this.animationFrame) {
            cancelAnimationFrame(this.animationFrame);
        }
    }
    
    reset() {
        const state = this.trackStore.getState();
        this.trackStore.setAnimationTime(state.animation.startTime);
        this.updateVesselPositions();
    }
    
    animate(timestamp) {
        const state = this.trackStore.getState();
        
        if (!state.animation.playing) return;
        
        if (!timestamp) timestamp = performance.now();
        
        // FPS 제한
        const targetFrameTime = 1000 / state.performance.targetFPS;
        const elapsed = timestamp - this.lastFrameTime;
        
        if (elapsed < targetFrameTime) {
            this.animationFrame = requestAnimationFrame(this.animate.bind(this));
            return;
        }
        
        const frameStartTime = performance.now();
        
        // 시간 업데이트 - 실제 경과 시간 계산
        const deltaTime = elapsed * state.animation.speed; // elapsed는 이미 ms 단위
        const newTime = state.animation.currentTime + deltaTime;
        
        // 디버그: 1초마다 로그 출력 (제거됨)
        
        if (newTime > state.animation.endTime) {
            // 루프 또는 정지
            this.trackStore.setAnimationTime(state.animation.startTime);
        } else {
            this.trackStore.setAnimationTime(newTime);
        }
        
        // 선박 위치 업데이트
        const updateStartTime = performance.now();
        this.updateVesselPositions();
        this.performanceMetrics.updateTime = performance.now() - updateStartTime;
        
        // UI 업데이트
        this.updateUI();
        
        // 성능 측정
        this.performanceMetrics.frameTime = performance.now() - frameStartTime;
        this.measureFPS(timestamp);
        
        this.lastFrameTime = timestamp;
        this.animationFrame = requestAnimationFrame(this.animate.bind(this));
    }
    
    updateVesselPositions() {
        const state = this.trackStore.getState();
        const positions = [];
        
        // 보이는 선박만 처리
        const visibleVessels = this.trackStore.getVisibleVessels();
        
        visibleVessels.forEach(vessel => {
            const pos = this.trackStore.getVesselPositionAtTime(vessel.id, state.animation.currentTime);
            if (pos) {
                positions.push({
                    id: vessel.id,
                    position: [pos.lon, pos.lat],
                    speed: vessel.metadata.avgSpeed,
                    color: this.getVesselColor(vessel.metadata.avgSpeed),
                    size: 12
                });
            }
        });
        
        // IconLayer 업데이트
        const iconLayer = new deck.IconLayer({
            id: 'vessel-icons',
            data: positions,
            pickable: true,
            getIcon: d => ({
                url: this.iconAtlas,
                width: 64,
                height: 64
            }),
            getPosition: d => d.position,
            getSize: d => d.size,
            getColor: d => d.color,
            sizeScale: 1,
            sizeMinPixels: 8,
            sizeMaxPixels: 24,
            billboard: false,
            // 성능 최적화
            updateTriggers: {
                getPosition: [state.animation.currentTime]
            }
        });
        
        // 전역 deckOverlay 사용
        const globalDeckOverlay = window.deckOverlay || this.deckOverlay;
        const currentLayers = globalDeckOverlay.props?.layers || [];
        
        // 궤적 표시 체크박스 상태 확인
        const showTracks = document.getElementById('showTracksCheck')?.checked || false;
        
        // vessel-icons 레이어만 업데이트
        let newLayers = currentLayers.filter(l => l.id !== 'vessel-icons' && l.id !== 'vessel-tracks');
        
        // 궤적 레이어 추가 (체크된 경우만)
        if (showTracks && window.updateGisLayers) {
            const trackLayer = window.updateGisLayers(true);
            if (trackLayer) {
                newLayers.push(trackLayer);
            }
        }
        
        // 아이콘 레이어 추가 (가장 위에)
        newLayers.push(iconLayer);
        
        globalDeckOverlay.setProps({ layers: newLayers });
        
        // 통계 업데이트
        document.getElementById('animatingVessels').textContent = positions.length;
    }
    
    getVesselColor(speed) {
        if (speed < 5) return [30, 144, 255, 200];
        if (speed < 10) return [0, 255, 0, 200];
        if (speed < 15) return [255, 255, 0, 200];
        if (speed < 20) return [255, 140, 0, 200];
        return [255, 0, 0, 200];
    }
    
    updateTrackVisibility(show) {
        // updateVesselPositions가 체크박스 상태를 직접 확인하므로
        // 여기서는 단순히 화면을 업데이트만 함
        this.updateVesselPositions();
    }
    
    updateUI() {
        const state = this.trackStore.getState();
        
        // 시간 표시
        if (state.animation.currentTime && !isNaN(state.animation.currentTime)) {
            const currentDate = new Date(state.animation.currentTime);
            document.getElementById('currentTimeDisplay').textContent = 
                currentDate.toTimeString().split(' ')[0];
        } else {
            document.getElementById('currentTimeDisplay').textContent = '--:--:--';
        }
        
        // 종료 시간 표시
        if (state.animation.endTime && !isNaN(state.animation.endTime)) {
            const endDate = new Date(state.animation.endTime);
            document.getElementById('totalTimeDisplay').textContent = 
                endDate.toTimeString().split(' ')[0];
        } else {
            document.getElementById('totalTimeDisplay').textContent = '--:--:--';
        }
        
        // 진행률
        if (state.animation.startTime && state.animation.endTime && state.animation.currentTime) {
            const progress = (state.animation.currentTime - state.animation.startTime) / 
                            (state.animation.endTime - state.animation.startTime);
            document.getElementById('timelineProgress').style.width = (progress * 100) + '%';
            document.getElementById('timelineSlider').value = progress * 1000;
        }
    }
    
    measureFPS(timestamp) {
        this.frameCount++;
        
        if (timestamp - this.fpsTimer > 1000) {
            this.actualFPS = Math.round(this.frameCount * 1000 / (timestamp - this.fpsTimer));
            document.getElementById('currentFPS').textContent = this.actualFPS;
            document.getElementById('frameTime').textContent = 
                Math.round(this.performanceMetrics.frameTime);
            
            this.frameCount = 0;
            this.fpsTimer = timestamp;
            
            // 동적 FPS 조정
            if (this.trackStore.getState().performance.dynamicFPS) {
                this.adjustFPSForPerformance();
            }
        }
    }
    
    adjustFPSForPerformance() {
        const targetFrameTime = 1000 / this.trackStore.getState().performance.targetFPS;
        
        if (this.performanceMetrics.frameTime > targetFrameTime * 1.5) {
            // 프레임 시간이 목표보다 50% 이상 길면 FPS 감소
            const newFPS = Math.max(5, this.trackStore.getState().performance.targetFPS - 5);
            this.trackStore.setTargetFPS(newFPS);
            console.log(`Performance: Reducing FPS to ${newFPS}`);
        } else if (this.performanceMetrics.frameTime < targetFrameTime * 0.7) {
            // 여유가 있으면 FPS 증가
            const newFPS = Math.min(30, this.trackStore.getState().performance.targetFPS + 5);
            this.trackStore.setTargetFPS(newFPS);
        }
    }
    
    stop() {
        this.pause();
        if (this.animationFrame) {
            cancelAnimationFrame(this.animationFrame);
        }
        
        // UI 제거
        const container = document.getElementById('timelineContainer');
        if (container) {
            container.remove();
        }
    }
}

// 전역 등록
window.VesselAnimationController = VesselAnimationController;
