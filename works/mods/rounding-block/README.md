# Rounding-Block

Minecraft의 블록 격자는 그대로 유지하면서, 바깥으로 드러난 블록·반블록·계단의 엣지와 꼭짓점만 둥글게 그리는 Fabric 클라이언트 모드입니다.

## 현재 범위

- Minecraft 1.21.1 / Fabric
- 기본 반경 `3/32` 블록, 곡면 3분할과 연속 법선 보간
- 충돌 판정과 월드 저장 데이터 변경 없음
- Fabric 기본 렌더러 Indigo 지원
- Fabric 공식 유체 렌더 API를 사용해 바닐라와 Sodium/Iris의 기본 물 렌더러를 그대로 위임
- 넓은 물 표면은 건드리지 않고 실제 라운딩 블록과 맞닿아 생기는 곡면 빈 공간에만 수면 패치를 생성
- 흐르는 물의 네 모서리 높이, 바이옴 색상, 흐름 UV, 광량과 양면 수면을 원래 유체 렌더러에 맞춰 유지
- 바닐라·모드 블록을 구분하지 않고 전체 블록·표준 반블록·계단을 지원하며, 그 밖의 해석할 수 없는 렌더 윤곽은 원래 모델로 자동 복귀
- 바닐라와 표준 `slab_type` 상태를 쓰는 모드 반블록의 상단·하단·이중 상태 지원
- 바닐라와 `stairs` 태그를 쓰는 모드 계단의 방향·상하·직선·안쪽·바깥쪽 상태 지원
- 계단이 포함된 접점은 블록마다 실제 윤곽을 `2×2×2` 반칸 점유로 읽어 전체 블록·반블록과 같은 격자에서 연결
- 반블록이 포함된 접점만 세로 1/2칸 점유 메시를 사용하고, 일반 전체 블록 지형은 기존 빠른 경로 유지
- 진흙처럼 충돌 높이만 낮고 화면에는 완전한 큐브로 그려지는 블록도 라운딩하며 충돌 판정은 바꾸지 않음
- 모래처럼 회전 모델을 무작위 선택하는 전체 큐브는 블록별 seed에 맞춰 UV 방향을 보존
- 잔디처럼 한 면에 기본층과 색상 오버레이가 함께 있는 전체 큐브도 두 레이어를 보존
- 월드 격자 꼭짓점마다 주변 8블록을 하나의 점유 상태로 읽어 바닥·벽·안쪽 코너를 한 곡면으로 연결
- 빈 공간 쪽으로 이어지는 안쪽 곡면도 가장 가까운 고체 블록 하나가 맡아 중복 면과 끝마개 없이 출력
- 256개 꼭짓점 점유 조합을 동일한 연속 밀도 함수로 처리하며 평평한 블록 면은 그대로 유지
- 완전히 가려진 블록은 곡면·UV 계산 전에 건너뛰고, 가중치 모델의 UV 해석 결과를 재사용
- 256개 꼭짓점 템플릿의 옥탄트 분할 결과를 모델 준비 중에 미리 만들고, 최근 이웃 형상은 제한된 캐시에 보관해 월드 렌더 중 같은 형상을 다시 자르지 않음
- 일반 전체 블록만 있는 지형은 27칸 점유만 읽고, 반블록·계단이 실제로 가까이 있을 때만 세부 점유 격자를 추가로 구성
- 실제로 보이는 면의 같은 층 3×3이 차 있고 바깥층 3×3이 비어 있으면 그 방향의 바닐라 쿼드만 합성하고, 이웃 코너를 대신 소유한 곡면 조각은 항상 유지

## 설정

첫 실행 시 Minecraft 프로필의 `config/rounding-block.json`이 생성됩니다.

```json
{
  "enabled": true,
  "quality": {
    "radius": 0.09375,
    "segments": 3
  },
  "cache": {
    "fullBlockPlans": 256,
    "slabPlans": 256,
    "complexShapePlans": 256,
    "fluidContactPlans": 256,
    "weightedModelVariants": 32
  },
  "debug": {
    "diagnosticLogging": true
  }
}
```

- `enabled`: `false`면 모델 수정 자체를 등록하지 않고 원래 물 핸들러도 복원해 바닐라/Sodium 기본 렌더 경로만 사용합니다.
- `radius`: 라운딩 반경입니다. `0.015625`~`0.21875` 범위에서 설정합니다.
- `segments`: 곡면 분할 수입니다. `1`~`8`이며 높을수록 부드럽지만 청크 메시와 VRAM 사용량이 늘어납니다.
- `fullBlockPlans`, `slabPlans`, `complexShapePlans`: 형상별 메시 캐시 항목 수입니다. 각각 `16`~`4096`입니다.
- `fluidContactPlans`: 물과 라운딩 블록이 맞닿는 수면 패치 캐시 항목 수입니다. `16`~`4096`입니다.
- `weightedModelVariants`: 모래처럼 가중 모델을 쓰는 블록의 UV 분석 캐시 수입니다. `1`~`256`입니다.
- `diagnosticLogging`: 최초 렌더 경로 진단 로그를 출력할지 정합니다.

범위를 벗어나거나 자료형이 잘못된 항목은 기본값으로 복구합니다. JSON 문법이 깨진 파일은 모드가 임의로 덮어쓰지 않습니다. 직접 편집한 값은 `/roundingblock reload`로 재시작 없이 적용할 수 있습니다.

## 명령어

모든 명령은 서버 권한이 필요 없는 클라이언트 명령입니다. 값을 바꾸는 명령은 JSON 저장과 모델 재적용을 함께 수행합니다.

```text
/roundingblock show
/roundingblock reload
/roundingblock enabled <true|false>
/roundingblock quality radius <0.015625~0.21875>
/roundingblock quality segments <1~8>
/roundingblock cache fullBlockPlans <16~4096>
/roundingblock cache slabPlans <16~4096>
/roundingblock cache complexShapePlans <16~4096>
/roundingblock cache fluidContactPlans <16~4096>
/roundingblock cache weightedModelVariants <1~256>
/roundingblock debug diagnosticLogging <true|false>
```

`enabled false`나 JSON의 `"enabled": false`를 재적용하면 모델을 다시 불러온 뒤 라운딩 래퍼가 사라집니다. 다시 `true`로 바꾸면 같은 리로드 과정으로 라운딩 모델을 새로 만듭니다.

## 빌드와 확인

```powershell
.\gradlew.bat :rounding-block:unitTest :rounding-block:build --no-daemon
```

산출물은 `rounding-block/build/libs/rounding-block-1.5.0.jar`입니다.
