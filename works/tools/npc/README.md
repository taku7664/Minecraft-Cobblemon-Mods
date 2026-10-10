# plaza 간호순 설치 자료 생성

`New-PlazaNurseBundle.ps1`은 간호순 대화 JSON과 콘솔 명령, 설치 안내를 새 폴더에 만듭니다. 서버 파일을 변경하거나 명령을 실행하지 않습니다.

필수 인자는 `-OutputDirectory`, `-X`, `-Y`, `-Z`입니다. 좌표는 대상 서버 plaza에서 간호순을 세울 지점의 발 위치를 지정하세요. `-Yaw`는 시선 방향이며 기본 0입니다. `-SkinVariant 1`은 `nurse_joy.png`, `-SkinVariant 2`는 `nurse_joy_2.png`를 사용합니다. 기본은 1입니다.

```powershell
# 좌표를 확인한 뒤 변수를 지정합니다. 출력 폴더는 아직 없는 경로여야 합니다.
# $nurseX = ...; $nurseY = ...; $nurseZ = ...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\New-PlazaNurseBundle.ps1 `
  -OutputDirectory F:/AI/Temp/plaza-nurse-install `
  -X $nurseX -Y $nurseY -Z $nurseZ -Yaw 0 -SkinVariant 1
```

출력된 `설치안내.txt`를 따라 대화 파일 하나를 대상 서버에 설치하고, 운영자가 배치 청크를 불러온 상태에서 `commands.txt`를 순서대로 실행합니다. 같은 태그를 가진 NPC가 로드되어 있으면 소환하지 않습니다. 설치를 자동 실행하거나 서버를 선택하는 기능은 없습니다.

NPC는 Cobblemon NPC의 고정형 NPC입니다. 파티 전체를 무료로 회복하며 반복 사용 가능합니다. 배틀 중에는 Cobblemon이 회복을 거절합니다. 성공 여부에 따라 대사를 나눠 실패할 때 회복 완료라고 말하지 않습니다. 대상 서버와 클라이언트에 MCC League Challenge가 있어야 해당 스킨을 볼 수 있습니다.

대화 원본: [plaza_nurse_joy.json](../../mods/cobblemon-npc/docs/dialogues/plaza_nurse_joy.json).
