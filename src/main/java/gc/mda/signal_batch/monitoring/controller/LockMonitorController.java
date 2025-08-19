package gc.mda.signal_batch.monitoring.controller;

import gc.mda.signal_batch.global.util.ConcurrentUpdateManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/locks")
@RequiredArgsConstructor
public class LockMonitorController {

    private final ConcurrentUpdateManager concurrentUpdateManager;

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getLockStatistics() {
        Map<String, Object> response = new HashMap<>();
        response.put("lockStats", concurrentUpdateManager.getLockStatistics());
        response.put("currentLocks", concurrentUpdateManager.getCurrentLocks());
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/deadlocks")
    public ResponseEntity<Map<String, Object>> getDeadlockInfo() {
        Map<String, Object> response = new HashMap<>();
        response.put("deadlocks", concurrentUpdateManager.getDeadlockInfo());
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }
}