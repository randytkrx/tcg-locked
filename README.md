# TCG Locked

TCG Locked turns your card collection into a progression gamemode. You can only
equip or use an item once you own its card in the OSRS TCG plugin. Open packs,
pull cards, and unlock the game one item at a time.

## How it works

The plugin reads your OSRS TCG collection and treats every card you own as an
unlocked item. Anything you have not pulled a card for stays locked. When an item
is locked the equip or use option is removed so you cannot wield, wear or activate
it, and a padlock is drawn over it in your inventory, bank and worn equipment.

Owning the card for a base item also unlocks its variants, so a single Amulet of
glory card covers every charge of the amulet, and a Dharok's greataxe card covers
every degrade state.

## What gets locked

Equipment is locked by default, so you cannot wield or wear gear without its card.

Teleport items such as amulets, jewellery and teleport tabs are locked as well, so
you cannot rub, break or operate them to teleport. Spellbook teleports are spells
rather than items, so those always work.

Consumables are optional and off by default. When enabled you cannot eat or drink
an item without its card.

You can also allow bronze gear for a starter kit, or type your own list of items
that are always allowed, which is handy for untradeable quest gear that has no card.

## The side panel

A panel tracks your run. It shows how many cards you own, how many items you have
unlocked this session, a feed of your most recent unlocks with the newest one
highlighted, the locked items you are currently carrying, and any locked gear you
still have equipped. It updates itself as you open packs and change your gear, and
there is a manual refresh button whenever you want it.

## Requires the OSRS TCG plugin

TCG Locked reads its unlocks from the OSRS TCG plugin, so you need that plugin
installed with a collection started. With no collection the gamemode stays inactive
and nothing is locked, so you can install it ahead of time safely.

## Building

This is a standard RuneLite external plugin. Build it with Gradle:

```
./gradlew build
```
