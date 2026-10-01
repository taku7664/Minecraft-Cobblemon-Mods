/*
 * The wiki's table of contents: every section and page, in rail order. Adding a page means adding its file and
 * one entry here. Paths are relative to the wiki root; keywords feed the search box along with the title.
 */
window.WIKI_NAV = {
  title: "빡켓몬 위키",
  sections: [
    {
      title: "시작하기",
      pages: [
        { path: "index.html", title: "홈", icon: "🏠", keywords: "처음 메인 소개" },
        { path: "pages/getting-started.html", title: "처음 접속했다면", icon: "🚩", keywords: "접속 설치 리소스팩 스타팅 키트 처음 성장 순서" },
        { path: "pages/me.html", title: "내 정보", icon: "👤", keywords: "내 정보 대시보드 bp 전적 연승 링크" },
      ],
    },
    {
      title: "성장",
      pages: [
        { path: "pages/levels.html", title: "레벨캡과 야생 포켓몬", icon: "📈", keywords: "레벨캡 레벨 야생 스폰 개체값 iv 숨겨진 특성 메가 다이맥스 테라스탈 잠금" },
        { path: "pages/legends.html", title: "전설 스폰 가이드", icon: "✨", keywords: "전설 환상 패러독스 스폰 포획 등급 엔트리 legends 포케스낵" },
        { path: "pages/pokemon-items.html", title: "포켓몬 아이템화와 합성", icon: "🧪", keywords: "poketoitem itemtopoke pokefusion 합성 개체값 놓아주기 release" },
      ],
    },
    {
      title: "배틀 콘텐츠",
      pages: [
        { path: "pages/hub.html", title: "MCC 허브와 BP 상점", icon: "📘", keywords: "mcc 허브 대시보드 상점 bp 터미널 레시피 민트 특성캡슐 구애" },
        { path: "pages/league.html", title: "리그 챌린지", icon: "🏆", keywords: "관장 체육관 사천왕 챔피언 난천 레벨캡 뱃지 하드 등급" },
        { path: "pages/tower.html", title: "배틀타워", icon: "🗼", keywords: "배틀타워 연승 보스 싱글 더블 bp" },
        { path: "pages/factory.html", title: "배틀팩토리", icon: "🏭", keywords: "배틀팩토리 렌탈 교환 층 bp" },
        { path: "pages/pvp.html", title: "PvP", icon: "⚔️", keywords: "pvp 대전 방 관전 라운지 타이머" },
        { path: "pages/wild-trainers.html", title: "야생 트레이너", icon: "🧢", keywords: "트레이너 에이스 엘리트 bp 스폰 쿨다운" },
      ],
    },
    {
      title: "생활",
      pages: [
        { path: "pages/systems.html", title: "서버 시스템", icon: "🛡️", keywords: "채팅 등급 배지 공지 안내 팁 레시피 화약 네더의 별 사탕" },
        { path: "pages/places.html", title: "광장과 마이룸", icon: "🏛️", keywords: "광장 plaza 마이룸 room 방 공개 초대" },
        { path: "pages/features.html", title: "편의 기능", icon: "🎮", keywords: "배틀 ui 배틀캠 음악 이모트 셰이더 라운딩 지도 음성 채팅 도감" },
      ],
    },
    {
      title: "참고",
      pages: [
        { path: "pages/commands.html", title: "명령어", icon: "⌨️", keywords: "명령어 command mcc bp legends plaza room" },
        { path: "pages/keys.html", title: "단축키", icon: "🎹", keywords: "단축키 키 조작 키설정 충돌" },
        { path: "pages/faq.html", title: "자주 묻는 질문", icon: "❓", keywords: "렉 문제 해결 질문 faq" },
      ],
    },
  ],
};
