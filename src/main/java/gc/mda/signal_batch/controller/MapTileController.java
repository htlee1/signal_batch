package gc.mda.signal_batch.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/tiles")
public class MapTileController {

    private static final String TILE_BASE_PATH = "/devdata/MAPS/WORLD_webp";
    private static final String TILE_ENC_PATH = "/devdata/MAPS/ENC_RAS_webp";

    @GetMapping("/world/{z}/{x}/{y}.webp")
    public ResponseEntity<Resource> getWorldTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y) {

        try {
            // 안전한 경로 생성
            Path tilePath = Paths.get(TILE_BASE_PATH, String.valueOf(z),
                    String.valueOf(x), y + ".webp");
            File tileFile = tilePath.toFile();

            if (!tileFile.exists() || !tileFile.isFile()) {
                return ResponseEntity.notFound().build();
            }

            // 경로 탐색 공격 방지
            if (!tileFile.getCanonicalPath().startsWith(new File(TILE_BASE_PATH).getCanonicalPath())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            FileSystemResource resource = new FileSystemResource(tileFile);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("image/webp"));
            headers.setCacheControl("public, max-age=86400"); // 24시간 캐시

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/enc/{z}/{x}/{y}.webp")
    public ResponseEntity<Resource> getEncTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y) {

        try {
            // 안전한 경로 생성
            Path tilePath = Paths.get(TILE_ENC_PATH, String.valueOf(z),
                    String.valueOf(x), y + ".webp");
            File tileFile = tilePath.toFile();

            if (!tileFile.exists() || !tileFile.isFile()) {
                return ResponseEntity.notFound().build();
            }

            // 경로 탐색 공격 방지
            if (!tileFile.getCanonicalPath().startsWith(new File(TILE_ENC_PATH).getCanonicalPath())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            FileSystemResource resource = new FileSystemResource(tileFile);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("image/webp"));
            headers.setCacheControl("public, max-age=86400"); // 24시간 캐시

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<String> checkTileService() {
        File baseDir = new File(TILE_BASE_PATH);
        if (baseDir.exists() && baseDir.isDirectory()) {
            return ResponseEntity.ok("Tile service is operational. Base path: " + TILE_BASE_PATH);
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Tile directory not found: " + TILE_BASE_PATH);
        }
    }
}
