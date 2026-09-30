# jbro Policy

jbro 포켓몬 서버에 적용하는 소소한 정책과 다듬기 기능을 모아 두는 Fabric 모드입니다.

- Mod ID: `jbro_policy`
- 실행 환경: 서버와 클라이언트 모두 필요
- 필요 모드: Fabric API, Fabric Language Kotlin, Cobblemon
- 선택 모드: More Cobblemon Contents: League Challenge

## 채팅 리그 등급

플레이어가 채팅을 치면 닉네임 앞에 리그 챌린지 등급을 나타내는 볼 아이콘이 붙습니다.

```
<[볼] 닉네임> 대화
```

| 리그 등급 | 아이콘 |
| --- | --- |
| 몬스터볼 (배지 0~2개) | 몬스터볼 |
| 수퍼볼 (배지 3~4개) | 수퍼볼 |
| 하이퍼볼 (배지 5~7개) | 하이퍼볼 |
| 마스터볼 (배지 8개) | 마스터볼 |
| 챔피언 | 마스터볼 |

아이콘에 마우스를 올리면 등급 이름이 보입니다. 아이콘은 서버가 플레이어 채팅에 묶는 이름에만 붙기 때문에 머리 위 이름표, 탭 목록, 시스템 메시지는 바뀌지 않습니다. League Challenge가 설치되지 않았거나 리그 카탈로그가 아직 로드되지 않았다면 아이콘 없이 원래 이름을 보여 줍니다.

아이콘은 Cobblemon의 볼 아이템 텍스처를 `jbro_policy:rank_icons` 폰트 글리프로 불러와서 그립니다. 따라서 이 모드가 없는 클라이언트에서는 아이콘 자리에 빈 글자가 보입니다.

## 빌드와 확인

```powershell
.\gradlew.bat --offline --no-daemon --configure-on-demand :jbro-policy:build
```
