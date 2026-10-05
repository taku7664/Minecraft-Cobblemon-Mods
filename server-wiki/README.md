# 서버 위키

클라이언트에 함께 배포해서 브라우저로 여는 서버 전용 위키입니다. MCC 허브와 같은 테마(UI Kit의 `ds_window` 스타일,
`tower_lobby` 팔레트)로 보입니다.

빌드 없이 여는 정적 HTML입니다. 문서마다 HTML 파일을 두고, 공통 틀은 일반 `<script>`가 채웁니다.
`file://`로 열어도 문서는 보이지만, 플레이어 정보는 MCC 서버가 위키를 띄울 때만 보입니다(아래 "서버에서 띄우기").

## 구조

| 경로 | 내용 |
|---|---|
| `index.html` | 홈 |
| `pages/*.html` | 문서 |
| `pages/_template.html` | 새 문서를 만들 때 복사할 틀. 쓸 수 있는 요소를 모두 담고 있습니다 |
| `assets/nav.js` | 목차: 섹션, 문서, 아이콘, 검색 키워드 |
| `assets/wiki.js` | 헤더, 검색, 왼쪽 목차, 경로 표시, 문서 목차, 이전/다음을 문서의 `<main>` 둘레에 붙입니다 |
| `assets/wiki.css` | 테마. 색은 허브 팔레트 값 그대로입니다 |
| `assets/fonts/` | Galmuri11 보통·굵게(SIL OFL 1.1, `LICENSE.txt`를 함께 배포합니다) |

## 문서 추가

1. `pages/_template.html`을 복사해 `pages/<이름>.html`로 만들고 `<main>` 안을 채웁니다.
2. `assets/nav.js`의 알맞은 섹션에 `{ path: "pages/<이름>.html", title: "...", icon: "...", keywords: "..." }`를 넣습니다.

`<h2>`가 두 개 이상이면 문서 목차가 자동으로 붙습니다. 빼려면 `<body>`에 `data-no-toc`를 답니다.
`pages/` 아래 문서는 `<body data-root="..">`, 루트의 문서는 `data-root="."`입니다.

## 쓸 수 있는 요소

| 요소 | 쓰는 법 |
|---|---|
| 카드 | `<div class="card"><h3>제목</h3>…</div>`, 여러 개는 `<div class="grid">`로 감쌉니다 |
| 수치 | `<div class="stat"><small>이름</small><b>값</b></div>` |
| 안내 | `<div class="note">`, `note tip`, `note warn`, `note danger` |
| 배지 | `<span class="badge">`, `badge gold`, `badge red`, `badge green`, `badge gray` |
| 표, 코드, 키 | `<table>`, `<code>`, `<pre><code>`, `<kbd>` |

## 서버에서 띄우기

MCC 서버가 이 위키를 HTTP로 띄우고, 접속한 플레이어의 대시보드를 `/api/me`로 실시간 제공합니다.

1. 이 폴더의 `index.html`, `pages/`, `assets/`를 서버의 `config/more-cobblemon-contents/wiki/`에 복사합니다.
2. `config/more-cobblemon-contents/wiki.json`에서 `enabled`를 `true`로 바꿉니다. 설정은 서버를 다시 켜면 반영됩니다.
   위키는 게임 포트로도 열리고 링크는 플레이어가 접속할 때 입력한 주소(예: `http://play.example.com:25566`)를 쓰므로,
   게임 포트 말고 따로 열 포트는 없습니다. `port`(기본 8100)는 서버 안에서만 쓰는 포트입니다. 위키를 별도 주소나
   프록시 뒤에 둘 때만 `public_url`을 적습니다. 디스코드의 위키 링크는 마지막으로 외부에서 접속한 플레이어의 주소를 씁니다.
3. 플레이어는 게임에서 `/wiki`를 입력하고 채팅의 링크를 누릅니다. 링크에 든 토큰은 브라우저에 저장되고
   주소창에서는 지워지며, 그다음부터는 새로고침할 때마다 최신 정보를 받아 옵니다. `/wiki reset`은 새 링크를
   만들고 예전 링크를 막습니다.

문서를 고치면 서버의 `wiki/` 폴더만 바꾸면 되고, 서버를 다시 켤 필요도 없습니다.

## 맵 보기

목차의 **참고 → 맵 보기**는 BlueMap을 문서 안에 표시합니다. 서버 지도는 위키가 사용하는 게임 서버의
호스트에서 8101 포트로 접속합니다. 클라이언트가 위키를 localhost에서 띄워도 `MccWiki`에 저장된 게임
서버 주소를 사용합니다. 외부 유저에게 제공하려면 지도 포트도 해당 서버에서 접근 가능해야 합니다.
HTTPS 위키를 사용할 때는 지도에도 HTTPS를 제공해야 합니다.

개발 BlueMap의 공개 지도는 오버월드(`world`), 네더(`world_the_nether`), 엔드(`world_the_end`),
광장(`world_jbro_policy_plaza`)입니다. 배틀 라운지와 MyRoom 지도 설정은 서버의
`config/bluemap/disabled-maps/`로 옮겨 자동 렌더 및 지도 목록에서 제외합니다. 기존 렌더 데이터는 보존합니다.
이는 지도 목록 제한이며, 저장된 타일 URL에 대한 접근 제어 기능은 아닙니다.

### 서버 장소 마커 등록

BlueMap 기본 설치는 설정 파일로 장소 마커를 등록합니다. 개인 Xaero 웨이포인트와 별개의 서버 공용 표시입니다.

1. F3로 등록할 위치의 좌표와 차원을 확인합니다.
2. 개발 서버 `config/bluemap/maps/` 안에서 해당 차원의 `.conf`를 엽니다.
   오버월드는 `world.conf`, 네더는 `world_the_nether.conf`, 엔드는 `world_the_end.conf`,
   광장은 `world_jbro_policy_plaza.conf`입니다.
3. 기존 `marker-sets` 블록 안에 아래 예시를 넣고 이름과 좌표를 바꿉니다. 예시 좌표는 실제 서버 시설의 위치가 아닙니다.

```hocon
server-places: {
  label: "서버 장소"
  toggleable: true
  default-hidden: false
  markers: {
    shop: {
      type: "poi"
      label: "상점"
      position: { x: 100, y: 64, z: -200 }
    }
  }
}
```

4. 서버 콘솔에서는 `bluemap reload`, 게임에서는 운영자 권한으로 `/bluemap reload`를 실행합니다.
   지도 지형을 다시 렌더할 필요 없이 마커 설정이 반영됩니다.
5. 그림 아이콘을 쓰려면 PNG를 `bluemap/web/assets/markers/shop.png`에 넣고 `shop` 블록에
   `icon: "assets/markers/shop.png"`와 `anchor: { x: 16, y: 32 }`를 추가합니다.
   anchor 예시는 32×32 이미지의 아래 중앙을 좌표에 맞춥니다. 이미지 크기에 맞춰 바꿉니다.

장소를 더 추가할 때는 `markers` 안에 다른 ID의 블록을 추가합니다. `marker-sets`를 중복으로 만들지 않습니다.
Map Link에서 동일한 웹 지도에 연결되어 있고 마커 표시가 켜져 있으면 이 마커를 가져옵니다.
클라이언트 접속 주소와 Map Link의 서버 항목은 정확히 일치해야 합니다. 개발 설정에는
`localhost:25566`과 `127.0.0.1:25566`을 모두 등록했습니다. 실행 중인 클라이언트는 다음 실행 때 파일 변경을 읽습니다.

공식 자료: [BlueMap 마커 설정](https://bluemap.bluecolored.de/wiki/customization/Markers.html),
[Map Link](https://modrinth.com/mod/maplink).

서버 실행 훅용 정적 파일 묶음은 다음 명령으로 만들 수 있습니다.

```powershell
python tools/server-wiki/build_startup_bundle.py --output "서버경로/startup-assets/server-wiki.zip"
```

`index.html`, `pages/`, `assets/`와 폰트 라이선스를 넣고 출처 커밋·SHA-256을
`server-wiki.bundle.json`에 기록합니다. 서버의 `startup-hooks.json`에도 새 압축 파일의
`wiki_files.sha256`을 반영해야 합니다. 서버 훅은 설치된 MCC의 위키 폴더에 누락 파일만
채웁니다. 이미 수정한 문서는 보존하므로 기존 문서를 갱신하려면 따로 백업·교체합니다.

## 플레이어 정보 쓰기

`pages/me.html`이 대시보드를 그립니다. 다른 문서에서도 `<span data-me="bp"></span>`처럼 쓰면 값이 채워집니다.
`player.name`, `bp`, 콘텐츠가 더한 값은 `sections.<키>.<항목>`입니다. 스크립트에서는 `MccWiki.me()`가 데이터를
돌려줍니다.

## 생성되는 데이터

전설 스폰 가이드, 포켓몬 도감, 야생 트레이너 목록은 서버가 실제로 쓰는 데이터에서 만듭니다. 서버가 따로 갱신해 주지 않는 고정 정보입니다. 손으로 고치지 말고, 원본이 바뀌면
다시 생성하세요.

| 파일 | 생성 스크립트 | 원본 |
|---|---|---|
| `assets/data/legends.js` | `python tools/server-wiki/gen_legends.py` | jbro-policy의 전설 스폰표, 전설 카탈로그, 등장 대사, Cobblemon과 마인크래프트의 한국어 이름 |
| `assets/data/dex/` | `python tools/server-wiki/gen_pokedex.py` | 서버 모드의 Cobblemon 종 데이터와 스폰표, 서버의 Showdown 기술 데이터, Cobblemon과 마인크래프트의 한국어 이름 |
| `assets/data/trainers.js` | `python tools/server-wiki/gen_trainers.py` | `tools/wild-trainers/kinds.py`와 리그 모듈의 트레이너 정의 |

전설 가이드의 "내 전설 현황"은 jbro-policy가 `/api/me`에 더하는 `legends` 항목(직접 잡은 종, 리그 등급, 지금 파티)을
씁니다.
