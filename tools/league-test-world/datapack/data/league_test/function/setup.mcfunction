# Mark first: do not repeatedly grant Pokemon if another command fails.
tag @s add league_test_ready
fill -4 126 -4 4 126 4 minecraft:stone
fill -4 127 -4 4 132 4 minecraft:air
setblock 0 127 -2 cobblemon_more_battle_content_league_challenge:league_terminal
setblock -3 127 -3 minecraft:torch
setblock 3 127 -3 minecraft:torch
spawnpoint @s 0 127 1
tp @s 0.5 127 1.5 180 10
gamemode survival @s
function league_test:give_pokemon {species:"piplup",level:14}
function league_test:give_pokemon {species:"turtwig",level:15}
function league_test:give_pokemon {species:"chimchar",level:14}
function league_test:give_pokemon {species:"shinx",level:14}
function league_test:give_pokemon {species:"starly",level:14}
function league_test:give_pokemon {species:"bidoof",level:14}
give @s cobblemon:exp_candy_xs 32
give @s cobblemon:exp_candy_s 16
give @s cobblemon:exp_candy_xl 8
give @s cobblemon:rare_candy 8
give @s cobblemon_more_battle_content_league_challenge:league_terminal 1
tellraw @s {"text":"[League Test] 앞의 터미널을 빈손으로 우클릭하세요. / Right-click the terminal with an empty hand.","color":"aqua"}
tellraw @s {"text":"초기 상한 15. 팽도리 Lv14 / 모부기 Lv15로 사탕을 시험하세요. / Cap 15: test candies on Lv14 Piplup and Lv15 Turtwig.","color":"yellow"}
tellraw @s {"text":"강석 승리 후 상한 20, 성장 재개를 확인하세요. 기존 월드와 파티는 별개입니다. / Beat Roark for cap 20. This is a disposable world.","color":"green"}
