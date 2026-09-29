# Installed only inside the NEW disposable test world, never as a global pack.
execute as @a[tag=!league_test_ready] at @s run function league_test:setup
