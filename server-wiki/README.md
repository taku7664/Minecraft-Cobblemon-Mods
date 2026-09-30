# 서버 위키

클라이언트에 함께 배포해서 브라우저로 여는 서버 전용 위키입니다. MCC 허브와 같은 테마(UI Kit의 `ds_window` 스타일,
`tower_lobby` 팔레트)로 보입니다.

빌드 없이 여는 정적 HTML입니다. 클라이언트에 묶으면 `file://`로 열리는데, 이때 브라우저가 `fetch`와 ES 모듈을 막기
때문에 문서마다 HTML 파일을 두고, 공통 틀은 일반 `<script>`가 채웁니다. 인터넷 연결 없이도 열립니다.

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

## 배포

이 폴더를 통째로 클라이언트 프로필에 넣고 `index.html`을 열면 됩니다. 게임 안에서 여는 버튼은 아직 없습니다.
