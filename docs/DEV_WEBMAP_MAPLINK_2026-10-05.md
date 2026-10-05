# 개발 웹 지도와 Map Link 설치

2026-10-05 개발 서버와 클라이언트에 웹 지도 연동 모드를 설치했다. 실제 런타임은 같은 저장소의 기본 체크아웃 `C:\Users\박주형\Documents\GitHub\Cobblemon-Mods\develop-product`에 있다. 작업용 worktree에는 런타임 폴더가 없다.

## 설치 파일

대상은 Minecraft 1.21.1 / Fabric Loader 0.19.5 / Java 21이다. Modrinth에서 해당 Minecraft/Fabric 조합의 최신 release를 조회했으며, 다운로드 및 배포 파일의 SHA-512를 배포처 값과 대조했다.

| 대상 | 파일 | Modrinth 버전 |
| --- | --- | --- |
| `develop-product/server/mods` | `bluemap-5.7-fabric.jar` | [Dr2hvJBc](https://modrinth.com/mod/bluemap/version/Dr2hvJBc) |
| `develop-product/client/mods` | `maplink-fabric-4.6.0-1.21-1.21.1.jar` | [P41dAaZZ](https://modrinth.com/mod/maplink/version/P41dAaZZ) |

서버 Fabric API와 클라이언트 Fabric API / Cloth Config / Mod Menu / Xaero Minimap / Xaero World Map은 이미 설치되어 있었다. 다른 JAR과 release 제품 폴더는 변경하지 않았다.

## 승인된 설정

사용자가 Mojang 지도 리소스 다운로드와 개발용 localhost 연결 설정을 승인했다.

서버 `config/bluemap/core.conf`:

```hocon
accept-download: true
```

서버 `config/bluemap/webserver.conf`:

```hocon
enabled: true
port: 8101
```

나머지 BlueMap 설정과 차원별 지도 설정은 첫 서버 실행에서 자동 생성된다.

클라이언트 `config/maplink/general.json5`는 partitioned Jankson 설정이다. `enabled`, `enableMarkerWaypoints`, `enableMarkerIcons`는 true이며 `serverEntries`에는 다음 항목을 넣었다. 필터 목록은 비어 있고 필터 모드는 모드 기본값인 Auto/BlackList를 유지했다.

```json
{"ip":"localhost:25566","link":"http://localhost:8101","maptype":"Bluemap"}
```

Map Link는 게임 접속 주소와 설정의 `ip`를 일치시킨다. 다른 컴퓨터에서 접속할 때는 운영자가 실제 게임 접속 주소와 그 컴퓨터에서 접근 가능한 웹 지도 주소로 변경해야 한다. localhost는 해당 클라이언트 컴퓨터를 가리킨다.

## 확인 범위와 포트 충돌

처음 제안한 지도 포트 8100은 서버의 기존 위키가 사용하고 있었다. 첫 실행에서 BlueMap의 bind 오류를 확인하고 서버를 정상 종료했다. 사용자가 지도 포트 8101과 클라이언트 URL 변경을 승인하여 수정했다. 위키 포트는 8100을 유지한다. 향후 웹 서비스 추가 시 현재 리스너뿐 아니라 서버 시작 후 포트를 여는 모드의 설정도 확인해야 한다.

JAR 내부 `fabric.mod.json`의 Minecraft/Java/Loader 의존성과 Map Link의 설정 필드를 확인했다. 별도 소스 변경이 없는 외부 모드 설치이므로 자체 단위 테스트는 추가하지 않았다. 개발 서버는 기존 실행 파일과 같은 Java 메모리/게임 포트 인자로 실행했다. 관련 없는 설정을 쓰는 startup hook은 DryRun으로만 확인했다.

수정 후 서버에서 `BlueMap loaded!`, 8101의 `WebServer started.`를 확인했다. `http://localhost:8101/`은 HTTP 200과 BlueMap HTML을 반환했고, `settings.json`은 오버월드, 네더, 엔드, 광장, 배틀 라운지, MyRoom의 지도 6개를 반환했다. 기존 위키 `http://localhost:8100/`도 동시에 HTTP 200을 반환했다. 검증용 서버는 `stop`으로 정상 저장 후 종료하여 작업 전의 정지 상태로 돌린다. 다음 개발 서버 실행 후 지도 주소를 사용할 수 있다.

게임 안 Map Link 로딩, 서버 접속과 마커 렌더링은 아직 확인하지 않았다. 장소 아이콘 마커 등록 및 위키 iframe 추가는 이 설치 요청에 포함하지 않았다. 지도 전체 렌더 완료와 성능 개선을 주장하지 않는다.

## 후속 요청: 위키 삽입과 차원 제한

같은 날 사용자의 후속 요청으로 위키 **참고 → 맵 보기**에 `pages/map.html`과 `assets/map.js`를 추가했다.
위키가 클라이언트에서 제공될 경우에도 `MccWiki.api()`의 게임 서버 호스트를 사용하며, 지도 포트는 8101이다.
서버와 클라이언트의 `config/more-cobblemon-contents/wiki/`에 새 페이지와 JS, 변경된 nav.js와 wiki.css를
복사하고 원본과 해시 일치를 확인했다.

BlueMap의 배틀 라운지 및 MyRoom `.conf`를 `config/bluemap/disabled-maps/`로 이동하고 나머지 지도 이름을
오버월드·네더·엔드·광장으로 지정했다. 사용자가 실행 중인 서버에서 `/bluemap reload`를 실행한 뒤,
8101의 `settings.json`에 `world`, `world_the_nether`, `world_the_end`, `world_jbro_policy_plaza`만 있는 것을
확인했다. 기존 타일 데이터는 삭제하지 않았으므로 이 변경은 목록 제한이지 타일 URL의 접근 제어가 아니다.

클라이언트 로그에는 Map Link 4.6.0 초기화가 확인됐지만 게임이 `127.0.0.1:25566`으로 접속하여 기존
localhost 항목을 찾지 못한 기록이 있었다. 동일 서버의 `127.0.0.1:25566` 항목을 추가했고, 지도 URL은
기존과 같은 `http://localhost:8101`이다. 실행 중인 Map Link의 설정 재로딩 및 마커 표시까지는 미확인이다.

브라우저에서는 위키 메뉴·페이지·iframe URL과 지도 단독 화면의 지형 렌더를 확인했다. Codex 내장 브라우저의
위키 iframe에는 '맵이 로드되지 않았습니다'가 표시됐다. 일반 브라우저 비교를 시도하는 도중 사용자가
Computer Use를 중단하여 iframe의 일반 브라우저 정상 동작은 확인하지 못했다. 원인을 내장 브라우저 문제로
단정하지 않는다. 서버는 작업 중 계속 실행한 상태를 유지했다. 서버 장소 마커의 파일·좌표·아이콘·reload
절차는 `server-wiki/README.md`의 '서버 장소 마커 등록'에 기록했다.
