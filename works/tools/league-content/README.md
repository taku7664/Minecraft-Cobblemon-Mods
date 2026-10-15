# 가라르 리그 콘텐츠 생성

리그 데이터의 원본은 `generate_galar.py`입니다. 종족·폼·기술·특성을 지정한 Cobblemon JAR과 대조하여 관장전·컵 일반/하드 36개, 외형 참조 14개, 한·영 번역과 엔트리 보고서를 생성합니다.

```powershell
python generate_galar.py --cobblemon '<Cobblemon 1.8.1 JAR>'
python generate_galar_skins.py
```

첫 스크립트는 Python 표준 라이브러리만 사용합니다. 스킨 스크립트는 Pillow가 필요하며, 직접 작성한 Minecraft 64×64 UV 픽셀 스킨 14종을 생성합니다. 공식 캐릭터 이미지나 제삼자의 Minecraft 스킨 파일을 내려받아 변환하지 않습니다. 기존 신오 RCT Trainers+ 외형은 별개입니다.

생성된 JSON과 PNG도 소스와 함께 추적합니다. 데이터팩으로 덮어쓸 수 있으며, 생성기를 다시 실행하면 내장 가라르 파일은 생성기 설정으로 돌아갑니다. 일반 관장전의 원작 기준과 자체 조정 범위는 [엔트리 보고서](../../mods/more-cobblemon-contents-league-challenge/docs/GALAR_TEAMS.md)에 있습니다.

실제 작은 화면과 레벨 보정 검증은 일회용 `league-wiring-smoke` 복사 월드에서만 실행할 수 있습니다. 실행 폴더 CLC 설정에는 리그 보상 캡 16·24·28·34·39·43·46·52·64·80·100 단계가 필요하고 야생 스케일링은 꺼야 합니다. `MCC_LEAGUE_CAPTURE_LIVE=1`, `MCC_LEAGUE_CAPTURE_GALAR=1`, 선택적으로 `MCC_LEAGUE_CAPTURE_GUI_SCALE=2`를 설정하고 리그 `runClient`에 `--quickPlaySingleplayer league-wiring-smoke --width 854 --height 480`을 전달합니다. GUI 배율 2에서 논리 크기는 427×240입니다. 640×400 창은 Minecraft의 최소 GUI 높이 때문에 배율이 1로 제한되므로 작은 논리 화면 검증을 대신하지 못합니다. 이 하네스는 복사 월드에 테스트 진행도·파티를 만들며, 사용자의 기존 월드에 실행하면 안 됩니다. 검증 후 클라이언트가 자동 종료합니다.
