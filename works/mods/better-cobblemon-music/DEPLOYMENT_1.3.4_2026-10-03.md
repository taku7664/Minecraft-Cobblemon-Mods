# 1.3.4 루기아 음원 배포 확인

- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`. 배포 직전 해당 프로필 또는 Minecraft/Fabric 클라이언트 실행 프로세스가 없었습니다. 게임 실행 및 서버 변경은 없습니다.
- JAR SHA-256: `8b881b289c222efa9be1bc2be685e08db4eff445e616cac7e270713a2b1472e5`.
- 공식 ZIP SHA-256: `fe7446abcdc342a9ddd0d6aa3ce1a9a180d51d32a7e800d5aaacc97d83790d2a`.
- 빌드·설치 해시 일치, 활성 Music JAR 1개, 선택 음악팩 1.3.4를 확인했습니다. `options.txt`는 줄바꿈 차이를 제외하면 음악팩 이름 1.3.3 → 1.3.4 교체만 변경되었습니다.
- 기존 JAR과 `options.txt`는 `deployment-backups/music-lugia-1.3.4-20261003/`에 보존했습니다. 이전 음악팩은 비활성 상태로 유지했습니다.
- 개인 설정 SHA-256 `955012e15fc330acc63eb377a86cf539267a674c582d8824ee44d87a2fe22337`, 개인 매핑 SHA-256 `c6f30a5d298d8b65cc3069274d6e46facea629dac32aa312b8e4d96db31625c9`는 배포 전후 동일합니다.
- 1.3.3과 1.3.4 ZIP의 엔트리 목록이 같으며, 모든 파일 내용을 비교한 결과 `assets/cobleserver/sounds/music/battle/legendary/hgss_lugia_battle.ogg`만 변경되었습니다. 카탈로그·매핑·다른 88곡·빨피 경고음·타격음은 바뀌지 않았습니다.
- 새 루기아 음원 SHA-256은 `b1e82d1430c823f9b195a4f1b3c4f835865f530de621a0a296ab3b699b8de278`입니다. 단일 Vorbis 스트림, 스테레오, 44.1kHz, 132초, 전체 디코딩 성공. 제공받은 FLAC 18,906,598바이트를 2,058,763바이트로 압축했으며 증폭·정규화·잘라내기는 적용하지 않았습니다.
- 루기아 플레이리스트 ID와 `sounds.json`의 스트림 재생을 확인했습니다. 야생·알파 루기아의 전용곡 우선 정책은 기존과 동일합니다.
- 1.3.3과 1.3.4 JAR의 엔트리 목록·내용 비교에서 `fabric.mod.json`의 버전 표기만 변경되었습니다. 실제 메타데이터는 1.3.4, client 환경입니다. JAR·ZIP 모든 엔트리의 CRC 검사에 성공했습니다.
- 전체 빌드 및 JUnit 122개 통과. 새 루기아 자산 검사에서 교체 전 실패·교체 후 성공을 확인했습니다. 별도의 음원 변환 스킬 테스트 5개도 통과했습니다.
- 게임에서의 실제 선곡·청감은 확인하지 않았습니다.

교체 근거와 변환 정보는 [루기아 교체 기록](LUGIA_REPLACEMENT_2026-10-03.md), 직전 배포 상태는 [1.3.3 배포 기록](DEPLOYMENT_1.3.3_2026-10-03.md)을 참고하세요.
