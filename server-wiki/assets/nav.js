/*
 * The wiki's table of contents: every section and page, in rail order. Adding a page means adding its file and
 * one entry here. Paths are relative to the wiki root; keywords feed the search box along with the title.
 */
window.WIKI_NAV = {
  title: "MORE COBBLEMON CONTENTS",
  subtitle: "SERVER WIKI",
  sections: [
    {
      title: "시작하기",
      pages: [
        { path: "index.html", title: "홈", icon: "🏠", keywords: "처음 메인 소개" },
        { path: "pages/getting-started.html", title: "처음 접속했다면", icon: "🚩", keywords: "접속 설치 리소스팩 규칙" },
        { path: "pages/me.html", title: "내 정보", icon: "👤", keywords: "내 정보 대시보드 bp 전적 연승" },
      ],
    },
    {
      title: "콘텐츠",
      pages: [
        { path: "pages/hub.html", title: "MCC 허브", icon: "📘", keywords: "mcc 대시보드 상점 bp 터미널" },
        { path: "pages/league.html", title: "리그 챌린지", icon: "🏆", keywords: "관장 사천왕 챔피언 레벨캡 뱃지" },
        { path: "pages/wild-trainers.html", title: "야생 트레이너", icon: "🧢", keywords: "트레이너 에이스 bp 스폰" },
      ],
    },
    {
      title: "참고",
      pages: [
        { path: "pages/commands.html", title: "명령어", icon: "⌨️", keywords: "명령어 command mcc bp" },
      ],
    },
  ],
};
