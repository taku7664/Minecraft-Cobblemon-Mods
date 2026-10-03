# 1.3.1 라인업 배포 확인

- 대상: Modrinth `cobblemon-dev` 클라이언트. 배포 직전 해당 프로필의 Java 실행 프로세스가 없음을 확인했습니다. 서버는 변경하지 않았습니다.
- JAR: `better-cobblemon-music-1.3.1.jar`, SHA-256 `91a98afd8f398d12d21e2e561b8057c24966556301b1bb67941260399f6fb831`.
- ZIP: `cobleserver-music-resourcepack-1.3.1.zip`, SHA-256 `2137ecd169f9fa6127576e340ff04a8266103d3742602c76d1461273418508d0`.
- 빌드 산출물과 설치 파일의 해시가 일치합니다. 활성 Music JAR은 1개이며 `options.txt`의 선택 ZIP은 1.3.1입니다. 다른 팩의 선택 순서는 유지했습니다.
- 1.3.0 JAR과 기존 `options.txt`는 저장소의 로컬 `deployment-backups/music-lineup-1.3.1-20261003/`에 보존했습니다. 기존 ZIP도 클라에 비활성 상태로 유지했습니다.
- 개인 `settings.json` SHA-256 `955012e15fc330acc63eb377a86cf539267a674c582d8824ee44d87a2fe22337`, `overrides.json` SHA-256 `c6f30a5d298d8b65cc3069274d6e46facea629dac32aa312b8e4d96db31625c9`: 배포 전후 동일합니다.

## 검사 결과

- 전달 음원 50개: 813,370,436 → 141,565,705바이트(82.6% 감소). 전부 단일 Vorbis 스트림, 스테레오, 44.1kHz; 전체 디코딩·길이 검사 성공.
- 공식 ZIP: 음악 87곡과 효과음 4개. 모든 음악 이벤트에 `stream: true`; 일반 야생은 DP 트랙입니다. 기존 음악 ID 호환을 위한 미사용 음원도 포함되어 있어 ZIP 전체 크기와 신규 50곡 크기는 다릅니다.
- 루기아 원본·ZIP 파일 SHA-256: `9dd5945566a8b2a587262c0c0555a2bbf6b2c4421cbc9a306531b61d10d33629`. 변경 전과 일치합니다.
- `:better-cobblemon-music:build`: 성공. JUnit 108개 전부 통과했습니다. 신규 선곡 테스트는 파서·팩 생성·실제 선곡기를 함께 검사합니다.
- 변환 스킬: 테스트 5개와 스킬 형식 검사 통과. `tools/skills/minecraft-audio-ogg`를 사용자 Codex 스킬 경로에 설치했습니다.

게임을 실행하거나 전투에서 청감 검사를 하지는 않았습니다. 실제 재생은 다음 게임 실행에서 별도로 확인해야 합니다. 이 버전에는 알파포켓몬 식별과 기타 보스 2곡은 아직 포함하지 않습니다.
