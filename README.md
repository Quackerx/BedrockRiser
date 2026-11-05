# BedrockRiser
Does exactly as the name implies.

- Makes the bedrock rise (Inspiration from FlowKool's video) DO NOT USE ON IMPORTANT WORLDS (OR SERVER)!

## COMMANDS:
- /bedrockriser OR /brise

## SUB-COMMANDS:
- **status**: Tells you whether the riser has started or not, if it hasn't started it tells you the start Y position.
  Also tells you the MAX Y position that it will rise to. And lastly tells you when the next rise happens.
- **rise**: Rises the bedrock on command. Example usage: (/bedrockriser rise 10), this rises the bedrock by 10 blocks, DOES NOT CHANGE THE START Y POSITION!
- **reset**: Resets the bedrock and the world to it's original state. (Can be laggy)
- **reload**: Reloads the plugin's config.yml file, run this if you changed anything in there to apply the changes in real time.


## CONFIG:
*Read more in the config.yml file itself*
- **worlds**: This lets you add/remove worlds where the riser is working. *(world by default)*
- **rise-interval-ticks**: How often the bedrock rises in server ticks. *(24000 (20 mins) by default)*
- **start-height**: Where will the bedrock start rising, important if you use on older versions or modded worldgens. *("auto" by default)*
- **max-height**: What is the max height where the bedrock will *STOP* rising. *(20 by default)*
- **blocks-per-rise**: How many *blocks* upwards will the bedrock rise on each rise. *(1 by default)*
- **scatter-layers**: How *scattered* the top edge of the bedrock is. *Read more from config.yml* *(5 by default)*
- **pause-when-empty**: Pauses the riser when no players are in the server. *(Off by default)*
- **chunks-per-tick**: How many chunks to get modified per tick (DOES NOT AFFECT *RESET*). *(2 by default)*
- **alarm**: Whether to alarm players that the bedrock has risen. Custom messages are available. *(Off by default)*
