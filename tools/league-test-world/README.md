# Disposable League test world

For the tester: open **League Cap Test - 15 to 20** in Singleplayer. On first join,
the world-local datapack gives six Pokemon (Piplup 14, Turtwig 15, Chimchar/Shinx/
Starly/Bidoof 14), experience candies and rare candies, and places a terminal
directly ahead. Right-click it with an empty hand. Test cap 15, then beat Roark
and test growth up to 20. Existing saves and their parties are not copied or edited.

테스터: 싱글플레이에서 **League Cap Test - 15 to 20**을 선택한다. 첫 접속 시
테스트 포켓몬 6마리·사탕이 지급되고 앞에 터미널이 설치된다. 빈손 우클릭으로 홈을
열고 상한 15 → 강석 승리 → 상한 20 및 성장 재개를 확인한다. 기존 월드는 별개다.

This is fixture preparation, not proof that candy restrictions or gym battles work.
The setup pack MUST NOT be installed globally or into an existing save: it changes
a small spawn platform and gives a test party. Setup is one-shot per player tag;
do not remove the tag to retry because that would grant additional Pokemon.

Reproduction (Minecraft closed; Python with `nbtlib==2.0.4` installed):

```text
python tools/league-test-world/prepare.py <disposable-template-world> <profile>/saves/league-cap-test
```

The destination MUST not exist. The script copies only terrain and level metadata,
removes the template's embedded player, and copies no Pokemon, CLC accounts, or
League progression. Production code is unchanged; this is test infrastructure.
