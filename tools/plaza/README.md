# Skybound Village 광장 설치

2026-09-30에 `cobblemon-dev`의 `새로운 세계`에 설치했습니다. 대상은
`dimensions/jbro_policy/plaza/region`입니다. 아래 별도 기록대로 2026-10-01에 개발 서버에도 설치했습니다.

- 원작: [Skybound Village](https://www.planetminecraft.com/project/skybound-village-floating-medieval-island-free-map/), Itzz_Aspect
- Minecraft Java 1.21.1, `jbro-policy-0.1.0.jar`
- 스키매틱: 118×179×125, Sponge v3, DataVersion 3953
- 평행이동: X −60, Y −50, Z −62. 블록 회전이나 교체는 없습니다.
- `/plaza enter`의 기본 착지점 `(0.5, 80, 0.5)` 아래에는 원본의 응회암 돌길이 있습니다.
- 착지점 주위 3×3 바닥과 그 위 4칸의 빈 공간을 검사했습니다.
- 광장 바이옴을 지정한 64청크에 333,726개의 비공기 블록과 블록 엔티티 107개를 보존했습니다.
- 변환한 지역 파일을 다시 읽어 모든 블록, 블록 엔티티, 바이옴을 대조했습니다. 복사 후 해시도 일치합니다.
- 게임 내 렌더링과 실제 명령 이동은 아직 확인하지 않았습니다.

원본 파일, 설치 명세, 수정하지 않은 `level.dat` 사본은 프로필의
`plaza-assets/skybound-village-20260930/`에 보관했습니다. 기존 광장 차원은 없었으며,
다른 차원이나 플레이어 데이터는 변경하지 않았습니다. 설치 해시는 인접 JSON에 있습니다.
원작의 재배포 금지 조건에 따라 스키매틱과 지역 파일은 이 저장소에 올리지 않습니다.

## 재현

Python에 `numpy`, `nbtlib`이 필요합니다. 원작 페이지에서 스키매틱을 별도로 받으십시오.
월드를 닫고 다음 명령으로 먼저 변환 결과를 검증할 수 있습니다.

```powershell
python tools/plaza/install_skybound.py <schematic> <world> <new-output-directory>
python -m unittest discover -s tools/plaza -p 'test_*.py'
```

`--install`을 추가하면 Windows 월드 잠금을 잡은 상태에서 복사합니다.
이미 광장 폴더가 있으면 덮어쓰지 않고 실패합니다. 좌표가 기본값과 다른 설정이나
다른 버전의 스키매틱은 다시 배치 검토가 필요하므로 거부합니다.

## 2026-10-01 개발 서버 설치

대상은 `C:\Users\박주형\Documents\GitHub\Cobblemon-Mods\dev-server`입니다.
`server.properties`의 `level-name=mbc-dev-world`를 확인했습니다. 정책 설정은 기본 착지점과 같았습니다.
서버가 중지된 상태에서 월드 잠금을 확보하고 기존 광장 차원을 백업한 뒤 교체했습니다.
기존 광장은 2,531청크에 석재 벽돌 81개만 있었으며 블록 엔티티는 없었습니다.
다른 차원, 설정, 플레이어 데이터, 모드 JAR는 변경하지 않았습니다.

원본 스키매틱, 설치 명세, 기존 광장 전체와 `level.dat` 사본은 서버의
`plaza-assets/skybound-village-20261001/`에 있습니다. 검증 결과와 설치 해시는
`skybound-dev-server-20261001.json`에 기록했습니다. 원본 비교와 복사 해시 검사는 통과했으며,
서버 시작과 실제 게임 내 이동·렌더링은 아직 확인하지 않았습니다.

전용 서버에는 `--config <server>/config/jbro-policy.json`으로 설정 파일을 명시하십시오.
기존 광장의 교체는 `--install --replace-existing`으로 요청해야 합니다. 도구는 새 지역 파일을
임시 광장 폴더에 복사·검증하고 기존 폴더를 출력 디렉터리의 `backup/plaza`로 옮긴 뒤 교체합니다.
