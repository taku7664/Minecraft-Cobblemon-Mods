# LumaVale 작업 기록

## [2026-10-06 19:30] 0.1.3: 배틀캠 투시 — 카메라와 포켓몬 사이 블록을 반투명하게 (glslang 검증·개발 클라이언트 캡처 확인)

- **빡대리님 지시:** 배틀캠이 포켓몬을 볼 때 나무 같은 블록이 가리면 반투명하게 한다. 셰이더팩마다 따로 할 수 없어서 LumaVale 전용으로 정했다(셰이더팩 형식은 블록을 칠하는 셰이더를 팩이 들고 있어 모드가 바꿀 수 없다).
- **값 받는 통로:** Iris 1.8.8에는 모드가 팩에 값을 넘기는 공개 API가 없다. 배틀캠이 Iris 내부 `HardcodedCustomUniforms.addHardcodedCustomUniforms` 끝에 믹스인으로 `mcc_seeThrough`(0~1, 0.4초 동안 오르내림), `mcc_seeThroughA`/`B`(카메라 기준 상대 위치)를 등록한다(배틀캠 `MEMORY.md`). 다른 팩은 이 유니폼을 읽지 않으니 영향이 없고, 배틀캠이 없으면 0이라 LumaVale도 그대로다.
- **셰이더:** `gbuffers_terrain`에만 `#define SEE_THROUGH`. 공용 `program/gbuffer_textured`가 카메라 기준 위치(`seeThroughPos`)를 넘기고, `program/see_through.glsl`이 카메라→대상 원뿔(반지름 0.7→1.8블록, 대상 0.8블록 앞에서 멈춤) 안의 조각을 4×4 Bayer 디더링으로 버린다. 최대 75%까지만 비운다(Claude 판단: 가리는 블록이 있다는 건 남게). 지형 패스는 불투명 패스라 진짜 알파 혼합 대신 스크린 도어 방식이다. 그림자 패스는 그대로라 나무 그림자는 남는다.
- **검증:** `validate.ps1`(glslangValidator로 36개 단계 컴파일·18쌍 링크) 통과, `dist/LumaVale-0.1.3.zip` 빌드. 리그 개발 실행에 Sodium·Iris를 `run/mods`에 넣고 LumaVale 0.1.3을 켠 채 전투 양옆에 나뭇잎 벽을 세워(`MCC_SCENE_CAPTURE_LEAVES=1`) 캡처했다. 배틀캠 구도에서 벽 가운데가 디더링으로 비어 난천과 잉어킹이 보이고, 전투가 끝나 카메라가 돌아오면 벽이 다시 불투명하다. 셰이더 컴파일 오류 없음.
- **배포 [2026-10-06 19:32]:** 개발 클라이언트 `develop-product/client/shaderpacks`의 0.1.2를 0.1.3으로 바꾸고 `config/iris.properties`의 `shaderPack`도 0.1.3으로 바꿨다. 배틀캠 JAR(유니폼 믹스인 포함)도 같이 넣었다. 백업 `develop-product/deployment-backups/2026-10-06_1932-lumavale-see-through`(0.1.2 zip, 이전 iris.properties 포함). 실제 클라이언트에서는 아직 안 봤다.
- **주의:** 경기장 안 지형은 MCC 배틀 홀로그램이 덧칠해서 투시가 흰 점무늬처럼 보인다. 실제 모드팩(Complementary 등 다른 팩)에서는 효과가 없다.
