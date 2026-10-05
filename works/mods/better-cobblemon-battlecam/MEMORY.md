# Better Cobblemon Battlecam MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
구현, 빌드, JAR 배치, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-10-06 08:40] Yarn → Mojang 매핑 변환 (빌드·바이트코드 비교 확인, 실게임 미확인)

- **빡대리님 지시:** 저장소에서 이 모듈만 Yarn이었다. 원본 BattleCam 2.0.3 JAR을 CFR로 디컴파일해 그대로 옮기면서 Yarn이 남은 것으로 보이고, 남긴 이유는 기록에 없다. 다음 작업(MCC 코어가 승패·특정 상황에서 배틀캠에 엔티티 포커스와 대화창 연출을 요청하는 API)에서 MCC·`cobblemon-ui`가 배틀캠 클래스를 바로 참조할 수 있도록 다른 모듈과 같은 Mojang 매핑으로 바꿨다.
- **방법:** Loom `migrateMappings --mappings net.minecraft:mappings:1.21.1`로 자동 변환했다. 믹스인 `@Inject` 대상 디스크립터 문자열까지 바뀌었다. 손으로 고친 곳은 두 가지다. `Level.getEntities(null, ...)`가 `EntityTypeTest` 오버로드와 겹쳐 모호해서 `(Entity) null`로 적었고, 테스트의 `Vec3d` import를 `Vec3`로 바꿨다. 디컴파일 머리말(“Could not load the following classes” + Yarn 클래스 목록)은 틀린 정보가 돼서 지웠다.
- **검증:** `:better-cobblemon-battlecam:build` 성공, 단위 테스트 12개 통과. 새 JAR과 개발 클라이언트에 배포돼 있던 Yarn 빌드 JAR을 비교했더니 클래스 목록이 같고, 모든 클래스가 참조하는 intermediary 심볼(`class_`/`method_`/`field_`) 집합도 하나도 다르지 않았다. 실행 시 동작은 바뀌지 않는다고 본다.
- **주의:** 믹스인은 `defaultRequire: 1`이라 대상이 틀리면 게임이 시작할 때 바로 크래시한다. 리플렉션 문자열(`getBattleId`, `getPokemon` 등)은 Cobblemon 메서드라 매핑과 무관하다. `BattleGUISelectActionMixin`은 소스에만 있고 `battlecam.mixins.json`에 등록돼 있지 않다(변환 전부터 그랬다).
- **미확인:** 개발 클라이언트에서 실제로 띄워 전투 카메라가 도는지는 아직 보지 않았다. 바이트코드가 같아서 JAR은 따로 배포하지 않았다.
