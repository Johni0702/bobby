# Bobby
https://www.curseforge.com/minecraft/mc-mods/bobby

Bobby is a Minecraft mod which allows for render distances greater than the server's view-distance setting.

It accomplishes this goal by recording and storing (in `.minecraft/.bobby`) all chunks sent by the server which it
then can load and display at a later point when the chunk is outside the server's view-distance.

Optionally, it can also use an existing single player world to load in chunks which the server has never sent before.
To make use of this feature, simply rename the world folder (not the name of the world! the name of its folder!) to "bobby-fallback".

Bobby automatically reloads its config file when it is changed.
For an in-game config screen, install [ModMenu] and [ClothConfig].

[ModMenu]: https://github.com/Prospector/ModMenu
[ClothConfig]: https://github.com/shedaniel/cloth-config

## Setup

For setup instructions please see the [fabric wiki page](https://fabricmc.net/wiki/tutorial:setup) that relates to the IDE that you are using.

## License

Bobby is provided under the terms of the GNU Lesser General Public License Version 3 or (at your option) any later version.
See LICENSE.md and LICENSE.LESSER.md for the full license text.
