# 리소스팩 곡명 입력

2026-10-03. 대상: 빡대리와 음악팩 제작자. 곡 ID·파일 경로·매핑·음량을 변경하지 않고 표시 이름만 편집합니다.

## 공식 팩 제작

[`resource-pack/catalog-layout.json`](resource-pack/catalog-layout.json)의 `trackTitles`에 112곡의 이름 입력란을 마련했습니다. 기존 파일명 기반 이름을 초기값으로 넣었으므로 원래 이름은 그대로입니다. 예:

```json
"trackTitles": {
  "cobleserver:field/myroom/eterna_forest": "Eterna Forest"
}
```

키는 트랙 ID이며 `track/` 플레이리스트 ID나 `.ogg` 파일명 전체가 아닙니다. 한글·공백·기호를 사용할 수 있습니다. 값이 없으면 파일명에서 이름을 자동 생성합니다. 빈 이름·문자열이 아닌 값·존재하지 않는 트랙 ID는 빌드를 중단하고 이전 정상 출력은 유지합니다. 곡을 삭제할 때 해당 곡명 항목도 제거하세요.

빌드는 입력란을 실제 ZIP의 `assets/better_cobblemon_music/catalogs/base/cobleserver.json` 안의 `tracks[트랙 ID].title`로 변환합니다. ZIP을 직접 편집할 때는 이 `title`을 고칩니다. 카탈로그의 실제 `title`이 곡명 알림의 표시 이름입니다. 변경 후 리소스를 다시 불러오면 이후 새 곡 알림부터 사용합니다.

## 개인 확장팩

[`extension-template/my-music-pack/track-titles.json`](extension-template/my-music-pack/track-titles.json)에 `"mymusic:hub/a": "내 곡명"`처럼 적고 `update-music.bat`을 실행합니다. 다시 생성해도 입력 파일을 덮어쓰지 않습니다. 잘못된 이름 입력은 생성 파일을 수정하기 전에 거부합니다. 자세한 예시는 [사용법](extension-template/my-music-pack/사용법.txt)에 있습니다.

검증: 한글·따옴표·역슬래시를 포함한 이름의 Windows PowerShell 5.1 재생성, 반복 실행 보존, 잘못된 입력 시 기존 결과 보존을 테스트했습니다. 카탈로그 로드·컴파일에서도 기본/확장팩 이름이 재생 이벤트에 연결되는지 확인했습니다.
