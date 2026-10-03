# 루기아 음원 교체 — 1.3.4

Updates: [2026-10-03 공식 라인업](MUSIC_LINEUP_2026-10-03.md)의 루기아 음원 유지 항목만 갱신합니다. 같은 날 사용자가 새 FLAC으로 교체를 요청했습니다. 기존 파일 유지 결정은 1.3.3까지의 기록으로 남기며, 1.3.4부터 새 음원을 사용합니다.

- 원본: `C:\Users\박주형\Downloads\75 - Battle! (Lugia).flac`. 원본 파일은 변경하지 않습니다.
- 원본 SHA-256: `5e28a8bf4d00e09afbad4c786dd0ef5d7bb3879cc570cebcaa055395ac3b99b7`.
- 변환 스킬: `minecraft-audio-ogg`, 음악 모드, OGG Vorbis 품질 4, 스테레오, 44.1kHz, 132초. 영상·첨부 썸네일·상속 메타데이터를 제외합니다. 증폭·정규화·잘라내기는 적용하지 않습니다.
- 18,906,598 → 2,058,763바이트, 원본 FLAC 대비 약 89.1% 감소. 단일 Vorbis 스트림·채널·샘플레이트·길이 검사 및 전체 디코딩에 성공했습니다. 이 검사는 청감 품질을 보장하지 않습니다.
- 새 OGG SHA-256: `b1e82d1430c823f9b195a4f1b3c4f835865f530de621a0a296ab3b699b8de278`.
- 교체 경로: `resource-pack/src/assets/cobleserver/sounds/music/battle/legendary/hgss_lugia_battle.ogg`. 파일명과 트랙 ID `cobleserver:battle/legendary/hgss_lugia_battle`, 플레이리스트 ID `cobleserver:track/battle/legendary/hgss_lugia_battle`는 사용자 매핑 호환을 위해 유지합니다.
- 야생 루기아·알파 루기아의 포켓몬 전용곡 우선 정책은 동일합니다. 다른 선곡, 알파 식별, 빨피 경고음과 사용자 설정은 변경하지 않습니다. 새 UI 항목이 없으므로 번역 문자열도 추가하지 않습니다.
- 실제 팩 빌더로 만든 루기아 파일의 해시를 확인하는 회귀 테스트를 추가했습니다. 교체 전에는 이 검사만 실패하고 기존 선곡 검사 4개는 통과했으며, 음원 교체 후 전체 빌드에서 JUnit 122개가 모두 통과했습니다.

배포 파일·검증 결과는 [1.3.4 배포 기록](DEPLOYMENT_1.3.4_2026-10-03.md)에 남깁니다. 예전 배포 문서와 라인업은 당시 상태를 설명하는 기록입니다.
