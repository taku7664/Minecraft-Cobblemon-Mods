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
        { path: "pages/getting-started.html", title: "처음 접속했다면", icon: "🚩", keywords: "접속 설치 리소스팩 스타팅 키트 처음" },
        { path: "pages/me.html", title: "내 정보", icon: "👤", keywords: "내 정보 대시보드 bp 전적 연승 링크" },
      ],
    },
    {
      title: "성장",
      pages: [
        { path: "pages/growth.html", title: "성장 가이드", icon: "🧭", keywords: "성장 순서 팁 강해지는 법 육성 키우기 bp 합성" },
        { path: "pages/levels.html", title: "레벨캡과 야생 포켓몬", icon: "📈", keywords: "레벨캡 레벨 야생 스폰 개체값 iv 숨겨진 특성 메가 다이맥스 테라스탈 잠금" },
        { path: "pages/leveling.html", title: "경험치와 기술", icon: "⬆️", keywords: "경험치 레벨업 학습장치 경험사탕 이상한사탕 기술 기술 바꾸기 pp 포인트업" },
        { path: "pages/natures.html", title: "성격과 민트", icon: "🌿", keywords: "성격 민트 고집 명랑 조심 겁쟁이 능력치 보정 nature mint" },
        { path: "pages/evs.html", title: "노력치", icon: "💪", keywords: "노력치 ev 파워 도구 영양제 타우린 깃털 열매 배분 252" },
        { path: "pages/ivs-abilities.html", title: "개체값과 특성", icon: "🧬", keywords: "개체값 iv 특성 숨겨진 특성 특성캡슐 특성패치 합성 사탕" },
        { path: "pages/pokedex.html", title: "포켓몬 도감", icon: "📕", keywords: "도감 포켓몬 종족값 노력치 기술 무브셋 특성 진화 출현 스폰 바이옴" },
        { path: "pages/legends.html", title: "전설 스폰 가이드", icon: "✨", keywords: "전설 환상 패러독스 스폰 포획 등급 엔트리 legends 포케스낵" },
        { path: "pages/pokemon-items.html", title: "포켓몬 아이템화와 합성", icon: "🧪", keywords: "poketoitem itemtopoke pokefusion 합성 개체값 놓아주기 release" },
      ],
    },
    {
      title: "배틀 콘텐츠",
      pages: [
        { path: "pages/hub.html", title: "MCC 허브와 BP 상점", icon: "📘", keywords: "mcc 허브 대시보드 상점 bp 터미널 민트 특성캡슐 구애 메가 메가스톤 z 테라 다이맥스 기믹" },
        { path: "pages/league.html", title: "리그 챌린지", icon: "🏆", keywords: "관장 체육관 사천왕 챔피언 난천 레벨캡 뱃지 하드 등급" },
        { path: "pages/tower.html", title: "배틀타워", icon: "🗼", keywords: "배틀타워 연승 보스 싱글 더블 bp" },
        { path: "pages/factory.html", title: "배틀팩토리", icon: "🏭", keywords: "배틀팩토리 렌탈 교환 층 bp" },
        { path: "pages/pvp.html", title: "PvP", icon: "⚔️", keywords: "pvp 대전 방 관전 라운지 타이머" },
        { path: "pages/pvp-matches.html", title: "PvP 전적", icon: "📜", keywords: "pvp 전적 기록 대전 승패 상대 랭킹" },
        { path: "pages/wild-trainers.html", title: "야생 트레이너", icon: "🧢", keywords: "트레이너 에이스 엘리트 bp 스폰 쿨다운 교환 교환꾼 퀴즈 회복 돌보미 여행자 선물" },
        { path: "pages/gimmicks.html", title: "배틀 기믹", icon: "💎", keywords: "기믹 메가진화 메가링 메가스톤 z기술 z-링 z크리스탈 빈z 다이맥스 거다이맥스 다이맥스밴드 다이수프 다이버섯 소원의별 테라스탈 테라스탈 오브 테라피스 액세서리 키스톤" },
      ],
    },
    {
      title: "생활",
      pages: [
        { path: "pages/crafting.html", title: "제작법", icon: "🔨", keywords: "제작법 레시피 조합 몬스터볼 소모품 회복약 사탕 PP에이드 학습장치 PC 회복기 냄비 양조기 도구" },
        { path: "pages/systems.html", title: "서버 시스템", icon: "🛡️", keywords: "채팅 등급 배지 공지 안내 팁 레시피 사탕" },
        { path: "pages/plaza.html", title: "광장", icon: "🏛️", keywords: "광장 plaza 터미널 이동" },
        { path: "pages/myroom.html", title: "마이룸", icon: "🏠", keywords: "마이룸 room 방 공개 비공개 초대 신뢰 차단 방문" },
        { path: "pages/dimensions.html", title: "차원과 울트라홀", icon: "🌀", keywords: "차원 울트라홀 웜홀 울트라스페이스 울트라비스트 고대 미래 포탈 패러독스 자격 입장 챔피언" },
        { path: "pages/features.html", title: "편의 기능", icon: "🎮", keywords: "배틀 ui 배틀캠 음악 이모트 셰이더 라운딩 지도 미니맵 xaero 웨이포인트 정보창 우클릭 음성 채팅 도감" },
        { path: "pages/discord.html", title: "디스코드", icon: "💬", keywords: "디스코드 discord 인증 디코인증 verify 연결 등급 역할 피츄 봇 채널 접속자 전적 랭킹" },
      ],
    },
    {
      title: "참고",
      pages: [
        { path: "pages/commands.html", title: "명령어", icon: "⌨️", keywords: "명령어 command mcc bp legends plaza room waypoint music 문의 디코인증 디스코드 verify" },
        { path: "pages/keys.html", title: "단축키", icon: "🎹", keywords: "단축키 키 조작 키설정 충돌 웨이포인트 지도" },
        { path: "pages/faq.html", title: "자주 묻는 질문", icon: "❓", keywords: "렉 문제 해결 질문 faq" },
        { path: "pages/support.html", title: "문의하기", icon: "✉️", keywords: "문의 신고 건의 버그 운영자 메일 inquiry" },
      ],
    },
  ],
};
