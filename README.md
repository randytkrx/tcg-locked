# TCG Locked

TCG Locked turns your card collection into a progression gamemode you can play
on your own or as a group. You can only equip or use an item, or fight a monster,
once its card has been pulled in the OSRS TCG plugin. Open packs, pull cards, and
unlock the game one piece at a time.

## How it works

The plugin reads your OSRS TCG collection and treats every card as an unlocked
item. Anything without its card stays locked: the equip or use option is removed,
and a padlock is drawn over it in your inventory, bank and worn equipment. Owning
the card for a base item also unlocks its variants, so a single Amulet of glory
card covers every charge.

Only items that actually have a card are part of the challenge. Plenty of items
have no card at all, so there would be nothing to collect to open them up, and
locking those would be a permanent dead end rather than a goal. They are left
alone, the same way monsters with no card are.

An optional outline setting marks NPCs and items green when unlocked, red when
locked, and purple when no matching card exists.

## What gets locked

Equipment is locked by default, so you cannot wield or wear gear without its card.

Teleport items such as amulets, jewellery and teleport tabs are locked too, so you
cannot rub, break or operate them to teleport. Spellbook teleports are spells
rather than items, so those always work.

Monsters can be locked as well. Until you own a monster's card you cannot attack,
talk to or otherwise interact with it, though you can always examine it. Monsters
with no card in the catalog are free.

Consumables are optional. When enabled you cannot eat or drink an item without its
card. A one click difficulty picker sets all of this for you, or choose Custom to
control each lock yourself.

## Playing as a group

Join a RuneLite party and TCG Locked pools the whole group's collection. A card
owned by any member unlocks that item for everyone, so you can grind a shared
unlock together. The panel lists every member's progress, and chat announces each
unlock as it happens.

## The panel and the unlocks

A side panel tracks your run: cards owned, unlocks this session, a feed of your
most recent unlocks, a collection lockbook of everything you have seen with a
locked or unlocked filter, and what you are carrying that is still locked. When a
new card frees an item, a reveal card shows the item art with a chime.

The Shared Cards button opens a searchable catalog of every item and NPC card,
including which synced party member owns each card.

A bronze starter option keeps basic bronze gear usable so you are never fully
locked out, and an always allow list lets you exempt anything by name.

## Requires the OSRS TCG plugin

TCG Locked reads its unlocks from the OSRS TCG plugin, so you need that plugin
installed and up to date. It asks OSRS TCG for your collection over that plugin's
own plugin-to-plugin API, and is then told about every card you pull as it
happens, so there is nothing to sync by hand.

Until OSRS TCG has answered, TCG Locked locks nothing at all and the panel says
so. "No collection yet" means the challenge is not running, never that you own no
cards, so a missing or outdated OSRS TCG plugin can't leave you unable to use your
gear.

## Bugs and feature requests

There is a Discord for the plugin at https://discord.gg/tcglocked, linked from the
bottom of the side panel. Bug reports, feature requests and general questions are
all welcome there.

TCG Locked is an unofficial community plugin and is not affiliated with the OSRS
TCG plugin or its developers.

## Building

This is a standard RuneLite external plugin. Build it with Gradle:

```
./gradlew build
```
