# Simple Trash Can

![Simple Trash Can](src/main/resources/assets/simple_trash_can/icon.png)

서바이벌 인벤토리에 휴지통 슬롯 하나를 붙이는 Fabric 모드입니다. 필요 없는 아이템을 바닥에 버리지 않고 그 자리에서 지울 수 있고, 실수로 넣었으면 다시 꺼낼 수 있습니다.

- Minecraft 1.21.1, Fabric Loader 0.19.5 이상
- 필수: Fabric API
- **클라이언트와 서버 양쪽에 설치해야 합니다.** 싱글플레이는 클라이언트만 있으면 됩니다.

## 휴지통 위치

인벤토리 창 오른쪽 바깥, 메인 인벤토리 마지막 줄(핫바 바로 윗줄) 높이에 작은 패널이 붙습니다. 패널 위에 "휴지통" 글자가 있고, 슬롯이 비어 있으면 휴지통 아이콘이 보입니다. 패널 테두리와 슬롯은 사용 중인 리소스팩의 인벤토리 텍스처를 따라 그립니다.

## 아이템이 지워지는 시점

휴지통에 올린 아이템은 바로 사라지지 않습니다. 다음 경우에 지워집니다.

| 상황 | 결과 |
|---|---|
| 다른 아이템을 올림 | 원래 아이템이 지워지고 새 아이템이 남습니다 |
| 같은 아이템을 올림 | 그대로 쌓입니다 |
| 인벤토리를 닫음 | 남아 있습니다 |
| 접속을 끊거나 게임이 튕김 | 사라집니다(저장하지 않습니다) |
| 죽음 | 사라집니다. 바닥에 떨어지지 않습니다 |
| 죽음, 게임룰 `keepInventory` 켜짐 | 남아 있습니다 |

## 서버 로그

아이템이 실제로 지워질 때마다 서버 로그(`logs/latest.log`)에 한 줄씩 남깁니다. 휴지통에 올리기만 한 아이템은 다시 꺼낼 수 있어서 남기지 않습니다.

```text
[SimpleTrashCan] PPAK_JU deleted 32x minecraft:cobblestone (replaced)
```

| 괄호 안 | 지워진 이유 |
|---|---|
| `replaced` | 다른 아이템을 올려서 교체됨 |
| `disconnect` | 휴지통에 남긴 채 접속을 끊음 |
| `death` | `keepInventory` 없이 죽음 |

## 넣고 꺼내는 법

| 조작 | 동작 |
|---|---|
| 아이템을 들고 좌클릭 | 들고 있는 아이템을 전부 넣습니다 |
| 아이템을 들고 우클릭 | 하나만 넣습니다 |
| 숫자키·보조손 키 | 그 칸의 아이템을 넣습니다. 같은 아이템이면 쌓습니다 |
| 빈손으로 클릭 | 휴지통 아이템을 집어 꺼냅니다 |
| 휴지통에서 Shift+클릭 | 인벤토리로 돌려놓습니다 |
| 인벤토리에서 Shift+클릭 | 휴지통으로 가지 않습니다(실수 방지) |
| 더블클릭으로 같은 아이템 모으기 | 휴지통 아이템은 모으지 않습니다 |

크리에이티브 인벤토리 탭에서는 휴지통이 보이지 않습니다. 크리에이티브에는 원래 아이템 삭제 칸이 있습니다.

## 함께 쓰는 모드

- EMI가 있으면 EMI 아이템 목록이 휴지통 패널을 가리지 않게 비켜 둡니다.
- 설정 화면과 명령어는 없습니다.

## 빌드

`works/`에서 AGENTS.md의 「Gradle 쓰기 경로」대로 F 드라이브 경로를 지정한 뒤 실행합니다.

```powershell
.\gradlew.bat :simple-trash-can:build
```

## English

Adds one trash slot to the right of the survival inventory, level with the last inventory row. An item placed there is not deleted right away: it stays until you put a different item in (the same item stacks), so a mistake can be taken back. The slot is never saved, so leaving the game or a crash empties it; on death it is deleted, not dropped, unless `keepInventory` is on. Shift-click never moves items into it, and it is hidden in the creative inventory tab. Every actual deletion is written to the server log with the player, item, count and reason (`replaced`, `disconnect`, `death`).

Requires Fabric API on Minecraft 1.21.1. Install it on both the client and the server.

## 라이선스

MIT. `LICENSE`를 보세요.
