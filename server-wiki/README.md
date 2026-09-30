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
2. `config/more-cobblemon-contents/wiki.json`에서 `enabled`를 `true`로, `public_url`을 플레이어가 접속할 주소로
   바꾸고(예: `http://play.example.com:8100`) 그 포트를 엽니다. 설정은 서버를 다시 켜면 반영됩니다.
3. 플레이어는 게임에서 `/mcc wiki`를 입력하고 채팅의 링크를 누릅니다. 링크에 든 토큰은 브라우저에 저장되고
   주소창에서는 지워지며, 그다음부터는 새로고침할 때마다 최신 정보를 받아 옵니다. `/mcc wiki reset`은 새 링크를
   만들고 예전 링크를 막습니다.

문서를 고치면 서버의 `wiki/` 폴더만 바꾸면 되고, 서버를 다시 켤 필요도 없습니다.

## 플레이어 정보 쓰기

`pages/me.html`이 대시보드를 그립니다. 다른 문서에서도 `<span data-me="bp"></span>`처럼 쓰면 값이 채워집니다.
`player.name`, `bp`, 콘텐츠가 더한 값은 `sections.<키>.<항목>`입니다. 스크립트에서는 `MccWiki.me()`가 데이터를
돌려줍니다.
