/*
 * Regenerates the bundled catalogs from OSRS TCG's v1 live catalog response.
 *
 * Usage: node scripts/generate-card-catalogs.js <path-to-live-catalog.json>
 */
'use strict';

const fs = require('fs');
const path = require('path');

const source = process.argv[2];
if (!source)
{
	console.error('usage: node scripts/generate-card-catalogs.js <path-to-live-catalog.json>');
	process.exit(1);
}

const repoRoot = path.resolve(__dirname, '..');
const outDir = path.join(repoRoot, 'src', 'main', 'resources', 'com', 'tcglocked');
const resource = name => path.join(outDir, name);
const catalog = JSON.parse(fs.readFileSync(source, 'utf8'));
if (!catalog || !Array.isArray(catalog.items) || !Array.isArray(catalog.npcs))
{
	console.error('expected the live {items,npcs} catalog schema');
	process.exit(1);
}

const byName = (a, b) => a.localeCompare(b, 'en');
const header = lines => lines.map(line => '# ' + line).join('\n') + '\n';
const dataLines = name => fs.existsSync(resource(name))
	? fs.readFileSync(resource(name), 'utf8').split(/\r?\n/)
		.map(line => line.trim()).filter(line => line && !line.startsWith('#'))
	: [];

// This matches TcgItemNameNormalizer. The released v2 bitmap was indexed by this exact sorted set.
const normalize = raw =>
{
	let value = String(raw || '').trim().toLowerCase()
		.replace(/[’‘]/g, "'").replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
	let previous;
	do
	{
		previous = value;
		value = value.replace(/\(\d+\)\s*$/, '').trim()
			.replace(/\((?:i|g|t|l|c|u|e|p|p\+|p\+\+|kp|or|cr|nz|bh|lms|deadman|uncharged|charged|empty|full|inactive|deployed|broken|damaged|locked|last man standing|historical|ornament|guthix|saradomin|zamorak|armadyl|bandos|ancient|rune|gilded|ruby|emerald|diamond|dragonstone|onyx|zenyte)\)\s*$/i, '').trim()
			.replace(/\s+(?:0|25|50|75|100)$/, '').trim()
			.replace(/\s+\d+%$/, '').trim();
	}
	while (value !== previous);
	return value;
};

const legacyItems = dataLines('beta-item-cards.txt');
const legacyNpcLines = dataLines('beta-npc-cards.txt');
if (!legacyItems.length || !legacyNpcLines.length)
{
	console.error('missing frozen beta catalog compatibility resources');
	process.exit(1);
}
const legacyIndexPath = resource('legacy-v2-card-keys.txt');
if (!fs.existsSync(legacyIndexPath))
{
	const keys = new Set(legacyItems.map(normalize).filter(Boolean));
	for (const line of legacyNpcLines)
	{
		const parts = line.split('|');
		for (const name of parts.length === 1 ? parts : parts.slice(1))
		{
			const key = normalize(name);
			if (key)
			{
				keys.add(key);
			}
		}
	}
	const sorted = [...keys].sort();
	fs.writeFileSync(legacyIndexPath, header([
		'Exact sorted normalized card-key index used by the released packed v2 catalog.',
		'Compatibility resource: never regenerate from a newer catalog or edit by hand.',
		sorted.length + ' keys.',
	]) + sorted.join('\n') + '\n', 'utf8');
}

// The released v3 bitmap is a protocol index, not a live gameplay catalogue. Capture it once
// before regenerating any resources, then leave it unchanged on all subsequent runs.
const frozenV3Path = resource('legacy-v3-card-keys.txt');
if (!fs.existsSync(frozenV3Path))
{
	const keys = new Set(dataLines('item-cards.txt').map(normalize).filter(Boolean));
	for (const line of dataLines('npc-cards.txt'))
	{
		const parts = line.split('|');
		(parts.length === 1 ? parts : parts.slice(1)).forEach(name => keys.add(normalize(name)));
	}
	keys.delete('');
	fs.writeFileSync(frozenV3Path, header([
		'Exact sorted normalized key index of the released v3 bitmap.',
		'Compatibility resource: never regenerate from a newer catalog or edit by hand.',
		keys.size + ' keys.',
	]) + [...keys].sort().join('\n') + '\n', 'utf8');
}

const itemNames = new Set(legacyItems);
// Match v1's LiveCardsCatalogParser exactly: only an exact current parent-name collision gets an
// npc:<id> storage identity. Normalized or legacy aliases must not create identities v1 never emits.
const liveItemParentNames = new Set(catalog.items
	.map(card => typeof card.name === 'string' ? card.name.trim() : '').filter(Boolean));
const npcEntities = new Map();
for (const line of legacyNpcLines)
{
	const parts = line.split('|');
	const cards = npcEntities.get(parts[0]) || new Set();
	(parts.length === 1 ? parts : parts.slice(1)).forEach(name => cards.add(name));
	npcEntities.set(parts[0], cards);
}

const itemIds = new Set();
const npcIds = new Set();
const npcImages = new Map();
const names = card => [card.name, card.parent && card.parent.name,
	...((card.tcg && card.tcg.variants) || []).map(variant => variant.name)]
	.filter(name => typeof name === 'string' && name.trim()).map(name => name.trim());
const addIds = (card, ids) =>
{
	const parent = card.parent && typeof card.parent === 'object' ? card.parent.id : card.parent;
	[card.id, parent, ...((card.tcg && card.tcg.variants) || []).map(variant => variant.id)]
		.filter(Number.isInteger).forEach(id => ids.add(id));
};

for (const card of catalog.items)
{
	names(card).forEach(name => itemNames.add(name));
	addIds(card, itemIds);
}

const itemKeys = new Set([...itemNames].map(normalize));
let collisionCount = 0;
for (const card of catalog.npcs)
{
	const cardNames = names(card);
	if (!cardNames.length || !Number.isInteger(card.id))
	{
		continue;
	}
	addIds(card, npcIds);
	const storageKey = liveItemParentNames.has(card.name.trim()) ? 'npc:' + card.id : card.name.trim();
	if (storageKey.startsWith('npc:'))
	{
		collisionCount++;
	}
	for (const entity of cardNames)
	{
		const cards = npcEntities.get(entity) || new Set();
		cards.add(storageKey);
		npcEntities.set(entity, cards);
	}
	if (typeof card.imagePath === 'string' && card.imagePath.trim())
	{
		const image = new URL(card.imagePath.trim(), 'https://osrs-tcg.net').toString();
		npcImages.set(card.name.trim(), image);
	}
}

const itemLines = [...itemNames].sort(byName);
fs.writeFileSync(resource('item-cards.txt'), header([
	'Current OSRS TCG item names plus legacy beta names for name fallback.',
	'Generated by scripts/generate-card-catalogs.js - do not edit by hand.',
	itemLines.length + ' names.',
]) + itemLines.join('\n') + '\n', 'utf8');

const npcLines = [...npcEntities.keys()].sort(byName)
	.map(entity => [entity, ...[...npcEntities.get(entity)].sort(byName)].join('|'));
const npcCardKeys = new Set([...npcEntities.values()].flatMap(set => [...set].map(normalize)));
fs.writeFileSync(resource('npc-cards.txt'), header([
	'Current OSRS TCG NPC names plus legacy beta mappings for name fallback.',
	'Format: NPC name|storage key[|storage key...] - owning any listed key unlocks that NPC.',
	'Generated by scripts/generate-card-catalogs.js - do not edit by hand.',
	npcEntities.size + ' NPC names using ' + npcCardKeys.size + ' storage keys.',
]) + npcLines.join('\n') + '\n', 'utf8');

const writeIds = (name, label, ids) =>
{
	const sorted = [...ids].sort((a, b) => a - b);
	fs.writeFileSync(resource(name), header([
		'Current OSRS TCG ' + label + ' IDs (card, parent, and tcg.variants).',
		'Generated by scripts/generate-card-catalogs.js - do not edit by hand.',
		sorted.length + ' IDs.',
	]) + sorted.join('\n') + '\n', 'utf8');
};
writeIds('item-card-ids.txt', 'item', itemIds);
writeIds('npc-card-ids.txt', 'NPC', npcIds);

const escapeTsv = value => String(value)
	.replace(/\\/g, '\\\\').replace(/\t/g, '\\t').replace(/\r/g, '\\r').replace(/\n/g, '\\n');
const npcImageLines = [...npcImages.entries()].sort(([a], [b]) => byName(a, b))
	.map(([name, url]) => escapeTsv(name) + '\t' + escapeTsv(url));
fs.writeFileSync(resource('npc-card-images.tsv'), header([
	'Current NPC card image URLs derived from the v1 catalog imagePath.',
	'Generated by scripts/generate-card-catalogs.js - do not edit by hand.',
	npcImageLines.length + ' cards.',
]) + npcImageLines.join('\n') + '\n', 'utf8');

console.log('live item cards:    ' + catalog.items.length);
console.log('live NPC cards:     ' + catalog.npcs.length);
console.log('item names:         ' + itemNames.size);
console.log('NPC names:          ' + npcEntities.size);
console.log('normalized items:   ' + itemKeys.size);
console.log('NPC storage keys:   ' + npcCardKeys.size);
console.log('v3 packed keys:     ' + new Set([...itemKeys, ...npcCardKeys]).size);
console.log('item IDs:           ' + itemIds.size);
console.log('NPC IDs:            ' + npcIds.size);
console.log('NPC collisions:     ' + collisionCount);
console.log('legacy v2 keys:     ' + dataLines('legacy-v2-card-keys.txt').length);
