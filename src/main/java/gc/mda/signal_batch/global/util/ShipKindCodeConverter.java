package gc.mda.signal_batch.global.util;

import java.util.HashMap;
import java.util.Map;


/**
 * sig_src_cd와 shipType을 조합하여 shipKindCode로 변환하는 유틸리티
 */
public class ShipKindCodeConverter {
    
    private static final Map<String, String> SHIP_KIND_MAP = new HashMap<>();
    
    static {
        // 어선 (000020)
        SHIP_KIND_MAP.put("000001_30", "000020");      // AIS - 어선
        SHIP_KIND_MAP.put("000004_30", "000020");      // VTS_AIS - 어선
        SHIP_KIND_MAP.put("000002_B005", "000020");    // ENVI - 어선(채낚기)
        SHIP_KIND_MAP.put("000002_B009", "000020");    // ENVI - 어선(복합어업)
        SHIP_KIND_MAP.put("000002_B001", "000020");    // ENVI - 어선(일반)
        SHIP_KIND_MAP.put("000002_B008", "000020");    // ENVI - 어선(통발)
        SHIP_KIND_MAP.put("000002_B002", "000020");    // ENVI - 어선(유자망)
        SHIP_KIND_MAP.put("000002_B004", "000020");    // ENVI - 어선(안강망)
        SHIP_KIND_MAP.put("000002_B007", "000020");    // ENVI - 어선(트롤)
        SHIP_KIND_MAP.put("000002_B006", "000020");    // ENVI - 어선(연승)
        SHIP_KIND_MAP.put("000002_B003", "000020");    // ENVI - 어선(선망)
        SHIP_KIND_MAP.put("000002_B016", "000020");    // ENVI - 어선(원양트롤어업)
        SHIP_KIND_MAP.put("000002_B014", "000020");    // ENVI - 어선(원양참치연승어업)
        SHIP_KIND_MAP.put("000002_B019", "000020");    // ENVI - 어선(원양통발어업)
        SHIP_KIND_MAP.put("000002_B012", "000020");    // ENVI - 어선(권현망)
        SHIP_KIND_MAP.put("000002_B013", "000020");    // ENVI - 어선(원양어선)
        SHIP_KIND_MAP.put("000002_B021", "000020");    // ENVI - 어선(원양봉수망어업)
        SHIP_KIND_MAP.put("000002_B017", "000020");    // ENVI - 어선(원양저인망어업)
        SHIP_KIND_MAP.put("000002_B020", "000020");    // ENVI - 어선(원양저연승어업)
        SHIP_KIND_MAP.put("000002_B023", "000020");    // ENVI - 어선(원양어업운반선)
        SHIP_KIND_MAP.put("000002_B015", "000020");    // ENVI - 어선(원양선망어업)
        SHIP_KIND_MAP.put("000002_B010", "000020");    // ENVI - 어선(저인망)
        SHIP_KIND_MAP.put("000002_B011", "000020");    // ENVI - 어선(자망)
        SHIP_KIND_MAP.put("000002_B018", "000020");    // ENVI - 어선(원양채낚기어업)
        SHIP_KIND_MAP.put("000002_B022", "000020");    // ENVI - 어선(원양모선식어업)
        SHIP_KIND_MAP.put("000003_00", "000020");      // VPASS
        
        // 함정 (000021)
        SHIP_KIND_MAP.put("000001_51", "000021");      // AIS
        SHIP_KIND_MAP.put("000001_35", "000021");      // AIS
        SHIP_KIND_MAP.put("000004_51", "000021");      // VTS_AIS
        SHIP_KIND_MAP.put("000004_35", "000021");      // VTS_AIS
        
        // 여객선 (000022)
        for (int i = 60; i <= 69; i++) {
            SHIP_KIND_MAP.put("000001_" + i, "000022");    // AIS
            SHIP_KIND_MAP.put("000004_" + i, "000022");    // VTS_AIS
        }
        SHIP_KIND_MAP.put("000002_A006", "000022");    // ENVI - 여객선(차도선)
        SHIP_KIND_MAP.put("000002_A007", "000022");    // ENVI - 여객선(화객선)
        SHIP_KIND_MAP.put("000002_A001", "000022");    // ENVI - 여객선(일반)
        SHIP_KIND_MAP.put("000002_A002", "000022");    // ENVI - 여객선(고속선)
        SHIP_KIND_MAP.put("000002_A005", "000022");    // ENVI - 여객선(카훼리)
        SHIP_KIND_MAP.put("000002_A003", "000022");    // ENVI - 여객선(쾌속선)
        SHIP_KIND_MAP.put("000002_A004", "000022");    // ENVI - 여객선(초쾌속선)
        // KSU는 SignalSourceCode에 없으므로 생략
        
        // 화물선 (000023)
        for (int i = 70; i <= 79; i++) {
            SHIP_KIND_MAP.put("000001_" + i, "000023");    // AIS
            SHIP_KIND_MAP.put("000004_" + i, "000023");    // VTS_AIS
        }
        SHIP_KIND_MAP.put("000002_C018", "000023");    // ENVI - 화물선(기타 유조선)
        SHIP_KIND_MAP.put("000002_C007", "000023");    // ENVI - 화물선(시멘트운반선)
        SHIP_KIND_MAP.put("000002_C021", "000023");    // ENVI - 화물선(LPG 운반선)
        SHIP_KIND_MAP.put("000002_C013", "000023");    // ENVI - 화물선(코일운반선-RORO선)
        SHIP_KIND_MAP.put("000002_C005", "000023");    // ENVI - 화물선(광목운반선)
        SHIP_KIND_MAP.put("000002_C015", "000023");    // ENVI - 화물선(컨테이너선)
        SHIP_KIND_MAP.put("000002_C008", "000023");    // ENVI - 화물선(자동차운반선)
        SHIP_KIND_MAP.put("000002_C010", "000023");    // ENVI - 화물선(철강재운반선)
        SHIP_KIND_MAP.put("000002_C003", "000023");    // ENVI - 화물선(양곡운반선)
        SHIP_KIND_MAP.put("000002_C012", "000023");    // ENVI - 화물선(폐기물운반선)
        SHIP_KIND_MAP.put("000002_C016", "000023");    // ENVI - 화물선(원유운반선)
        SHIP_KIND_MAP.put("000002_C001", "000023");    // ENVI - 화물선(일반)
        SHIP_KIND_MAP.put("000002_C023", "000023");    // ENVI - 화물선(일반탱커)
        SHIP_KIND_MAP.put("000002_C022", "000023");    // ENVI - 화물선(LNG 운반선)
        SHIP_KIND_MAP.put("000002_C009", "000023");    // ENVI - 화물선(핫코일운반선)
        SHIP_KIND_MAP.put("000002_C011", "000023");    // ENVI - 화물선(모래운반선)
        SHIP_KIND_MAP.put("000002_C004", "000023");    // ENVI - 화물선(원목운반선)
        SHIP_KIND_MAP.put("000002_C002", "000023");    // ENVI - 화물선(벌크선)
        SHIP_KIND_MAP.put("000002_C014", "000023");    // ENVI - 화물선(냉동, 냉장선)
        SHIP_KIND_MAP.put("000002_C017", "000023");    // ENVI - 화물선(석유제품 운반선)
        SHIP_KIND_MAP.put("000002_C006", "000023");    // ENVI - 화물선(석탄운반선)
        SHIP_KIND_MAP.put("000002_C019", "000023");    // ENVI - 화물선(케미칼 운반선)
        SHIP_KIND_MAP.put("000002_C024", "000023");    // ENVI - 화물선(세미 컨테이너선)
        
        // 유조선 (000024)
        for (int i = 80; i <= 89; i++) {
            SHIP_KIND_MAP.put("000001_" + i, "000024");    // AIS
            SHIP_KIND_MAP.put("000004_" + i, "000024");    // VTS_AIS
        }
        
        // 관공선 (000025)
        SHIP_KIND_MAP.put("000001_59", "000025");      // AIS
        SHIP_KIND_MAP.put("000002_D008", "000025");    // ENVI - 관공선(방제선)
        SHIP_KIND_MAP.put("000002_D006", "000025");    // ENVI - 관공선(군선)
        SHIP_KIND_MAP.put("000002_D002", "000025");    // ENVI - 관공선(해경정)
        SHIP_KIND_MAP.put("000002_D004", "000025");    // ENVI - 관공선(지도선)
        SHIP_KIND_MAP.put("000002_D003", "000025");    // ENVI - 관공선(시험조사선)
        SHIP_KIND_MAP.put("000002_D009", "000025");    // ENVI - 관공선(의료선)
        SHIP_KIND_MAP.put("000002_D007", "000025");    // ENVI - 관공선(해경항공기)
        SHIP_KIND_MAP.put("000002_D001", "000025");    // ENVI - 관공선(일반)
        SHIP_KIND_MAP.put("000002_D005", "000025");    // ENVI - 관공선(시험선)
    }
    
    /**
     * sig_src_cd와 shipType을 조합하여 shipKindCode를 반환
     * 
     * @param sigSrcCd 신호 소스 코드 (ex: 000001, 000002, ...)
     * @param shipType 선박 타입 (ex: 30, B005, ...)
     * @return shipKindCode (ex: 000020, 000021, ...) 매칭되지 않으면 000027(기타)
     */
    public static String getShipKindCode(String sigSrcCd, String shipType) {
        if (sigSrcCd == null || shipType == null) {
            return "000027"; // 기타
        }
        
        String key = sigSrcCd + "_" + shipType;
        return SHIP_KIND_MAP.getOrDefault(key, "000027"); // 기본값: 기타
    }
    
    /**
     * 선박 종류 명칭 반환
     * 
     * @param shipKindCode 선박 종류 코드
     * @return 선박 종류 명칭
     */
    public static String getShipKindName(String shipKindCode) {
        switch (shipKindCode) {
            case "000020": return "어선";
            case "000021": return "함정";
            case "000022": return "여객선";
            case "000023": return "화물선";
            case "000024": return "유조선";
            case "000025": return "관공선";
            case "000027": return "기타";
            default: return "기타";
        }
    }
}