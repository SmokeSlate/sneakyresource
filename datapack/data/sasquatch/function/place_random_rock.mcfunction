# Randomly picks one of the four rock variants and places it at the current position.
# Fill in the exact tripwire states in sasquatch:place_rock_1 through sasquatch:place_rock_4.

execute store result storage sasquatch:random rock_choice int 1 run random value 1..4
execute if data storage sasquatch:random {rock_choice:1} run function sasquatch:place_rock_1
execute if data storage sasquatch:random {rock_choice:2} run function sasquatch:place_rock_2
execute if data storage sasquatch:random {rock_choice:3} run function sasquatch:place_rock_3
execute if data storage sasquatch:random {rock_choice:4} run function sasquatch:place_rock_4
