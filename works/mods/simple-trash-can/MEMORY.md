# simple-trash-can 작업 기록

## [2026-10-18 07:15] 삭제 서버 로그 추가·개발 재배치 (실게임 미확인)

- **사용자 결정:** 아이템이 실제로 지워질 때만 서버 `latest.log`에 남긴다. 올리기만 한 건 꺼낼 수 있어서 남기지 않는다. 형식은 `[SimpleTrashCan] <플레이어> deleted <개수>x <아이템 ID> (<이유>)`, 이유는 `replaced`·`disconnect`·`death`이며 따로 파일을 만들지 않는다.
- **구현:** `TrashLog`가 기록한다. 교체는 `AbstractContainerMenuMixin`의 PICKUP·SWAP 교체 분기에서 남기는데, 클릭이 양쪽에서 돌기 때문에 `ServerPlayer`일 때만 남긴다. 접속 종료는 새 main 엔트리포인트에서 Fabric `ServerPlayConnectionEvents.DISCONNECT`로, 죽음은 `restoreFrom`의 keepInventory 아닌 분기에서 남긴다. 커밋 `fb2ee90e0`, main 푸시 후 origin/main 0/0.
- **검증:** 빌드 성공, `jar --validate`·`unzip -t` 통과. Codex 읽기 전용 리뷰(`3f7512520`·`fb2ee90e0`)에서 지적 없음. 세 경우가 각각 한 번씩만 기록되는지, 클라이언트 쪽 로그가 없는지를 봤다. 배치본 `develop-product/{client,server}/mods/simple-trash-can-1.0.0.jar` SHA-256은 `f914c3bac80decbe2c0bb666155bc0f47797f7bc68ff223542177604bbad4496`로 빌드본과 같다. 배치 전 게임·서버 정지 확인.
- **미확인:** 실제 로그 줄 출력은 서버를 켜지 않아 보지 못했다. 서버를 끌 때 접속 중이던 플레이어도 DISCONNECT 이벤트로 기록되는지 확인하지 않았다.

## [2026-10-18 06:55] 새 모드 1.0.0 구현·개발 배치 (실게임 미확인)

- **사용자 결정:** 모드 이름 `simple-trash-can`(기존 모드에 넣지 않고 새로 추가). 위치는 서바이벌 인벤토리 오른쪽 바깥, 메인 인벤토리 마지막 줄 높이, 슬롯 위 "휴지통" 글자와 빈 슬롯 휴지통 아이콘. 올린 아이템은 다른 아이템이 올라올 때 지운다. 인벤토리를 닫아도 남기고, 저장하지 않으므로 재접속·튕김에는 사라진다. 죽으면 지우되 `keepInventory`면 남긴다. Shift+클릭으로는 넣지 못하고 꺼내기만 된다. 아이콘은 직접 그린 16×16. 나중 배포용으로 모드 아이콘(128×128)과 README도 요청받아 넣었다.
- **구현:** `InventoryMenu` 생성자 끝에 슬롯을 추가한다(인덱스 46). 바닐라 슬롯 번호는 그대로이고, 바닐라 Shift+클릭은 9~44만 대상으로 해서 따로 막지 않았다. 교체·쌓기는 `AbstractContainerMenu.doClick` HEAD에서 PICKUP·SWAP을 직접 처리하고 취소한다. 더블클릭 모으기는 `canTakeItemForPickAll`에서 뺐다. 크리에이티브 인벤토리 탭은 슬롯을 그대로 복사해 핫바 위에 겹쳐 놓기 때문에, `selectTab` TAIL에서 비활성 슬롯으로 바꿔 숨겼다. 패널은 리소스팩의 `inventory.png`에서 잘라 그린다(파티 사이드바 `InventorySkin` 방식). 패널은 화면 밖 클릭으로 판정되어 아이템을 떨어뜨리므로 `hasClickedOutside`에서 제외했다. EMI 제외 영역도 등록했다.
- **Codex 리뷰로 고친 것(원본과 대조해 모두 사실 확인):** ① 기존 아이템만 지우고 바닐라에 맡기면 번들처럼 클릭 동작을 따로 가진 아이템은 아무것도 놓지 않아 기존 아이템만 사라졌다. ② 숫자키는 같은 아이템도 맞바꿨다(①② `c3d2f547b`). ③ SWAP 버튼 번호를 검사하지 않아 조작된 패킷(`button=-1`)이면 `Inventory.getItem(-1)`에서 서버가 죽을 수 있었다. 바닐라 `doClick`의 `0~8 || 40` 검사를 바이트코드로 확인하고 똑같이 넣었다(`3f7512520`). 두 번째 리뷰는 ①② 수정이 맞고 복제·비동기화는 없다고 봤다. ③ 수정은 리뷰를 다시 돌리지 않았다.
- **양쪽 설치 필수:** 슬롯이 서버 메뉴에도 있어야 하므로 클라이언트와 서버 모두에 설치한다.
- **빌드·소스:** 커밋 `23dc13698`(기능), `c3d2f547b`·`3f7512520`(리뷰 수정), `165f71a4a`(아이콘·README·LICENSE)를 main에 푸시했고 origin/main 0/0을 확인했다. `:simple-trash-can:build` 성공. 출력은 공용 `F:/AI/builds/cobblemon-mods-main/out/simple-trash-can`. `jar --validate`·`unzip -t` 통과. refmap은 없고, 믹스인 대상 이름이 바이트코드 안에서 intermediary로 바뀐 것을 `javap`로 확인했다. 대상 메서드 시그니처도 1.21.1 Mojang 매핑 JAR과 대조했다. 단위 테스트는 없다.
- **배치:** `develop-product/{client,server}/mods/simple-trash-can-1.0.0.jar`, SHA-256 `33b610941f5bdbb15905de925acc858a16a6a23f856274c98c85d3954596f262`(빌드본과 같음). 배치 전 게임·서버가 꺼져 있었고 25566 포트도 닫혀 있었다. 기존 JAR이 없는 새 설치라 백업할 것이 없었다. PPakemon 프로필·운영 서버·deploy-product는 손대지 않았다.
- **미확인:** 게임과 서버를 실행하지 않았다. 믹스인 적용, 슬롯 위치·글자·아이콘 표시, 교체·쌓기·꺼내기, 죽음·keepInventory, 크리에이티브 숨김, 다른 모드(인벤 정렬·InvMove·Sophisticated Backpacks·EMI)와의 동작은 확인하지 않았다. 리소스팩에 따라 패널 테두리가 어긋날 수 있다. README에 넣을 실게임 스크린샷도 아직 없다.
