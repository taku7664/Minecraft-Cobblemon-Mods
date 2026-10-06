# Better Cobblemon Battlecam MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
구현, 빌드, JAR 배치, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-10-06 19:45] 투시 유니폼이 실제로는 안 들어가고 있었다 — 등록 지점 수정 (개발 클라이언트 비교 캡처 확인)

- **빡대리님 지적:** 19:30에 보낸 첫 사진이 전혀 투명하지 않다. 확인해 보니 Iris 1.8.8에서 `HardcodedCustomUniforms.addHardcodedCustomUniforms`를 부르는 곳이 **하나도 없어서**(JAR 전체 검색) 값이 셰이더에 가지 않았다. 19:30 기록의 "투시 확인"은 경기장 지형을 덧칠하는 MCC 배틀 홀로그램의 반투명을 Claude가 잘못 본 것이다.
- **수정:** 믹스인을 `CommonUniforms.addNonDynamicUniforms`(렌더링 파이프라인이 프로그램마다 부름) TAIL로 옮겼다. 셰이더팩이 값을 처음 읽을 때 `See-through uniforms are read by the shader pack` 로그를 한 번 남긴다.
- **검증:** 같은 하네스(나뭇잎 벽)로 다시 찍어 이전(값 없음) 사진과 비교했다. 10초 구도에서 오른쪽 블록과 잉어킹 앞 블록이 점무늬로 비고, 19초 구도에서 벽 너머 난천·잉어킹·뮤츠·플레이어가 보인다(이전엔 뮤츠·플레이어가 가려짐). 전투 뒤에는 벽이 다시 불투명. 로그에 믹스인·셰이더 오류 없음.

## [2026-10-06 19:30] Iris 셰이더팩에 투시 값을 넘긴다 (LumaVale 0.1.3 전용 효과, 개발 클라이언트 캡처 확인)

- **빡대리님 지시:** 배틀캠이 포켓몬을 볼 때 나무가 가리면 반투명하게, LumaVale 전용으로.
- **값:** `BattlecamSeeThrough`가 감독이 잡는 구도마다 대상 두 곳을 받는다(일반 구도: 싱글은 양쪽 포켓몬, 더블은 양 팀 중심 / 액션 연출: 초점 / 기믹: 얼굴과 몸 / 대사 연출: 얼굴과 몸 중심). 강도는 배틀캠이 카메라를 잡고 있으면 0.4초 동안 1로, 놓으면 0으로.
- **Iris 통로:** Iris 공개 API에는 유니폼을 넘길 방법이 없어 내부 `HardcodedCustomUniforms.addHardcodedCustomUniforms` TAIL에 믹스인(`IrisSeeThroughUniformsMixin`)으로 `mcc_seeThrough`, `mcc_seeThroughA/B`를 등록한다. 별도 믹스인 설정(`battlecam.iris.mixins.json`, `required: false`)과 플러그인(`IrisMixinPlugin`)이 Iris가 있을 때만 적용한다. 컴파일은 `modCompileOnly("maven.modrinth:iris:1.8.8+1.21.1-fabric")`. Iris 버전이 바뀌면 이 내부 메서드를 다시 확인한다.
- **개발 실행 주의:** Iris·Sodium을 Gradle 의존성(`modLocalRuntime`)으로 넣으면 클래스패스가 길어져 Gradle이 매니페스트 JAR 하나로 묶고, Fabric Loader가 그 안을 못 봐서 "Minecraft game provider couldn't locate the game"으로 시작조차 안 된다. 개발 실행 폴더 `run/mods`에 프로덕션 JAR을 넣으면 된다(Loader가 런타임에 이름을 바꾼다).

## [2026-10-06 12:55] 연출 카메라가 플레이어 뒤에서 시작하지 않게

- 대화창이 아래 띠 위로 올라와 얼굴 아래쪽을 가려서, 조준점을 얼굴 아래 0.12→0.45(크기 비례)로 내려 얼굴이 화면 위쪽에 오게 했다. 전투 연출은 얼굴이 박스 위로 다 보인다. NPC 대화는 거리가 2.3블록으로 줄어 머리 끝이 위 띠에 살짝 닿는다(캡처 확인).

- 연출 카메라는 화자 앞 3.4블록에서 2.6블록으로 다가오는데, NPC가 플레이어와 3블록 거리면 처음 몇 프레임은 카메라가 플레이어 뒤에 있어 플레이어 몸이 화면을 가렸다(연속 캡처에서 발견). 거리를 화자-플레이어 수평 거리 - 0.7(최소 1.4)로 제한한다.

## [2026-10-06 10:55] 연출 카메라를 화자와 플레이어 사이 선 위에, 장애물은 같은 선 위에서 피한다

- **방향:** 플레이어가 아닌 화자는 플레이어에게 말하고 플레이어를 보므로, 카메라를 화자→플레이어 방향(수평)에 세운다. 머리 방향으로 잡으면 장면 시작 직후 NPC가 플레이어 쪽으로 고개를 돌려 옆을 보는 것처럼 찍혔다. 플레이어 자신을 비출 때만 머리 방향을 쓴다. 방향은 장면 시작 때 한 번 정한다.
- **장애물:** 연출 포커스는 메가진화 장면이 쓰는 `adjustFrontalCinematicPoseForObstructions`(같은 선 위에서 높이·거리 조정)를 쓴다. 전에는 옆으로 돌아가는 회피를 써서 벽이 있으면 화자가 옆을 보게 됐다.

## [2026-10-06 10:36] 연출 카메라 방향을 장면 시작 때 고정

- NPC 대화 포커스(cobblemon-npc)에서 NPC가 `RandomLookAroundGoal`로 고개를 돌리면 카메라가 머리를 따라 돌았을 것이다. 장면(`SceneFocus`)이 바뀔 때 한 번만 머리 방향을 읽고 그 방향을 유지한다. 위치(눈높이)는 계속 따라간다.

## [2026-10-06 10:27] 연출 포커스를 정면 구도로 (개발 클라이언트 캡처 확인)

- **빡대리님 지적:** 대사 연출에서 트레이너가 정면을 안 본다. 원인은 카메라를 얼굴 앞에서 옆으로 0.7~1.1블록(크기 비례) 비켜 3/4 각도로 잡은 `sideOffset`이었다. 트레이너는 자기 앞을 보는데 카메라가 옆에 있어 고개를 돌린 것처럼 보였다.
- **수정:** 옆 오프셋을 없애 머리가 향한 방향 정면에서 잡는다(거리·높이 밀어 들어가기는 그대로). 리그 하네스로 세 장면 모두 정면으로 보이는 것을 캡처로 확인했다. 배포: `develop-product/client/mods`.

## [2026-10-06 09:05] 연출 포커스 API `BattlecamScenes` (빌드·단위 테스트 확인, 실게임 미확인)

- **빡대리님 지시:** 전투에서 이기거나 졌을 때, 또는 특정 상황에서 코드가 배틀캠에 엔티티 포커스 + 대화창 연출을 요청한다. 대화창이 끝날 때까지 카메라를 유지하고, 그 뒤에 배틀이 끝난다. 호출하는 쪽은 MCC 코어다(MCC `MEMORY.md` 참고).
- **API:** `jbro.cobblemon.battlecam.api.BattlecamScenes.focus(entityId, durationMillis)` / `release()` / `isFocusing()`. 클라이언트 스레드에서 부른다. 포커스는 배틀 구도·액션·기믹 연출보다 우선하고, 전투 컨텍스트가 끝나도(`NONE`) 유지된다. 시간 제한은 안전장치일 뿐이고, MCC는 대화창이 끝날 때 `release()`한다.
- **카메라:** 대상의 머리가 향한 쪽 앞에서 눈높이를 보고, 4초 동안 천천히 다가간다. 대상 크기에 맞춰 거리를 늘린다. 장애물은 기존 `adjustCinematicPoseForObstructions`로 피한다. 대상이 사라지면 포커스가 끝난다. 연출 중에는 3인칭으로 바꿔서 자기 자신을 비출 때도 몸이 보인다.
- **존중:** 그 전투(또는 방금 끝난 전투)에서 플레이어가 배틀캠을 끈 상태(`Mode.OFF`)면 카메라를 움직이지 않는다. 대화창은 MCC가 그대로 띄운다.

## [2026-10-06 08:40] Yarn → Mojang 매핑 변환 (빌드·바이트코드 비교 확인, 실게임 미확인)

- **빡대리님 지시:** 저장소에서 이 모듈만 Yarn이었다. 원본 BattleCam 2.0.3 JAR을 CFR로 디컴파일해 그대로 옮기면서 Yarn이 남은 것으로 보이고, 남긴 이유는 기록에 없다. 다음 작업(MCC 코어가 승패·특정 상황에서 배틀캠에 엔티티 포커스와 대화창 연출을 요청하는 API)에서 MCC·`cobblemon-ui`가 배틀캠 클래스를 바로 참조할 수 있도록 다른 모듈과 같은 Mojang 매핑으로 바꿨다.
- **방법:** Loom `migrateMappings --mappings net.minecraft:mappings:1.21.1`로 자동 변환했다. 믹스인 `@Inject` 대상 디스크립터 문자열까지 바뀌었다. 손으로 고친 곳은 두 가지다. `Level.getEntities(null, ...)`가 `EntityTypeTest` 오버로드와 겹쳐 모호해서 `(Entity) null`로 적었고, 테스트의 `Vec3d` import를 `Vec3`로 바꿨다. 디컴파일 머리말(“Could not load the following classes” + Yarn 클래스 목록)은 틀린 정보가 돼서 지웠다.
- **검증:** `:better-cobblemon-battlecam:build` 성공, 단위 테스트 12개 통과. 새 JAR과 개발 클라이언트에 배포돼 있던 Yarn 빌드 JAR을 비교했더니 클래스 목록이 같고, 모든 클래스가 참조하는 intermediary 심볼(`class_`/`method_`/`field_`) 집합도 하나도 다르지 않았다. 실행 시 동작은 바뀌지 않는다고 본다.
- **주의:** 믹스인은 `defaultRequire: 1`이라 대상이 틀리면 게임이 시작할 때 바로 크래시한다. 리플렉션 문자열(`getBattleId`, `getPokemon` 등)은 Cobblemon 메서드라 매핑과 무관하다. `BattleGUISelectActionMixin`은 소스에만 있고 `battlecam.mixins.json`에 등록돼 있지 않다(변환 전부터 그랬다).
- **미확인:** 개발 클라이언트에서 실제로 띄워 전투 카메라가 도는지는 아직 보지 않았다. 바이트코드가 같아서 JAR은 따로 배포하지 않았다.
