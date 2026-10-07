# Rounding-Block 배포 준비 검토

검토일: 2026-10-07. 대상: 이 모듈의 소스, 리소스, 메타데이터, 빌드 설정과 자동 테스트. 이번 작업은 배포 준비이며 Modrinth 게시나 제품 폴더 설치를 포함하지 않습니다.

## 라이선스

- 기존 저장소 `LICENSE`와 `fabric.mod.json`의 MIT 선언을 유지합니다.
- 모듈 `LICENSE`는 루트 파일과 동일하며 저작권 표기는 `Copyright (c) 2026 jbro`입니다.
- MIT 원문 기준: <https://opensource.org/license/mit>.
- 일반 JAR와 소스 JAR에 `LICENSE_rounding-block`을 포함합니다. 재배포 시 저작권·허가 고지 유지가 필요하고, MIT는 수정·재배포·상업적 사용을 허용합니다.
- 이 모듈의 소스·리소스에서 별도 제3자 저작권 고지나 복사 출처 표기는 발견하지 못했습니다. 표기 검색은 코드의 모든 과거 출처를 증명하는 검사가 아닙니다.
- Fabric API는 외부 필수 의존성, Mod Menu는 선택 연동입니다. 이 모듈은 해당 라이브러리를 JAR에 내장하지 않습니다. Minecraft 코드·텍스처도 배포 자산으로 복사하지 않습니다.

## 아이콘

- 파일: `src/main/resources/assets/rounding_block/icon.png`.
- 생성 방식: 기본 제공 `image_gen` 도구. 외부 이미지나 게임 텍스처를 입력하지 않고 새로 생성했습니다.
- 용도: Fabric/Mod Menu 아이콘과 향후 배포 페이지 아이콘. 실제 게임 화면의 증거로 사용하지 않습니다.
- 생성 프롬프트:

> Use case: logo-brand. Asset type: square Minecraft Fabric mod icon for Rounding-Block. Create a polished simple icon: one large isometric grass-topped earth cube with visibly rounded beveled edges and corners, retaining flat square faces, occupying 75 percent of image. Original stylized voxel illustration with large readable green grass and brown earth color patches, a few blocky texture accents, clean silhouette. Three faces visible. Soft dark teal solid background. Small subtle shadow. Crisp and readable at 64x64. No text, letters, border, watermark, characters, scenery or Minecraft official logos. Square composition. The defining feature is softly beveled cube edges rather than a sphere.

## 설정

- 기본값과 범위는 `RoundingBlockConfig`에서 확인했습니다. 이번 준비 작업에서 기본값을 바꾸지 않습니다.
- 기존 설정 화면의 Cloth Config 사용을 제거하고 Minecraft 기본 화면으로 교체합니다.
- `/roundingblock config`로 외부 설정 모드 없이 열 수 있습니다. 기존 Mod Menu entrypoint는 선택 바로가기로 유지합니다.
- 숫자 범위 검사, 저장 후 리소스 재로드, 실패 시 이전 설정 복원과 한·영 문구를 포함합니다.

## 자동 검증

- JUnit 84개 통과, 전체 빌드 통과. 최종 화면 배치 반영 후 산출물 재조립도 통과했습니다.
- JUnit 범위: 설정 파일 파싱·저장, 명령 구조, 렌더/메시 회귀, 설정 입력의 숫자 범위 검사와 Cloth Config 없는 Mod Menu factory 탐색을 확인합니다.
- 메타데이터: 클라이언트 전용, Fabric API 필수, Mod Menu 선택, Cloth Config 선언 없음.
- JAR: 아이콘 경로, 라이선스 고지, 외부 의존 JAR 내장 없음.
- 한·영 번역 키의 일치와 화면 메시지 존재를 확인합니다.
- 게임을 실행한 화면 확인은 이번 작업에서 수행하지 않았습니다.

## 게시 전 확인

빡대리가 실제 클라이언트에서 다음을 확인한 뒤 배포 여부를 결정합니다. 자동 검사는 아래 화면 확인을 대신하지 않습니다.

- Mod Menu와 Cloth Config 없이 `/roundingblock config` 열기, 세 탭 전환, 저장·취소·전체 초기화.
- 반경과 분할 수 변경 후 블록·반블록·계단·물 접점 외형.
- 잘못된 숫자의 저장 차단과 설정 적용 중 화면 상태.
- 사용하는 리소스팩·렌더러 조합의 화면 및 성능.
- 배포 페이지에는 실제 게임 캡처를 추가하고, 아이콘을 비교 캡처로 사용하지 않기.
