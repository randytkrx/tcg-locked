/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.util.Collections;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class TcgLiveCatalogSnapshotTest
{
	static final String SMALL = "{\"items\":[{\"id\":100,\"name\":\"Live parent\",\"tcg\":{\"variants\":[{\"id\":101,\"name\":\"Different variant\"}]}}],"
		+ "\"npcs\":[{\"id\":200,\"name\":\"Live parent\",\"imagePath\":\"/images/npcs/detail/Test.png\",\"tcg\":{\"variants\":[{\"id\":201,\"name\":\"Live creature\"}]}}]}";

	@Test
	public void resolvesVariantIdsAndExactCrossKindStorageKeys()
	{
		TcgLiveCatalogSnapshot snapshot = TcgLiveCatalogSnapshot.parse(SMALL);
		assertEquals(Set.of("live parent", "different variant"), snapshot.itemKeysById.get(101));
		assertEquals(Set.of("npc:200"), snapshot.npcKeysById.get(201));
		assertEquals(Set.of("npc:200"), snapshot.npcKeysByName.get("live creature"));
		assertEquals("https://osrs-tcg.net/images/npcs/detail/Test.png", snapshot.entries.get(1).imageUrl);
	}

	@Test
	public void activationChangesIdentitiesButNeverTheReleasedBitmap()
	{
		TcgCardCatalog catalog = new TcgCardCatalog();
		String old = catalog.packKeys(Set.of("abyssal whip"));
		assertTrue(catalog.hasItemCard(2));
		catalog.activate(TcgLiveCatalogSnapshot.parse(SMALL));
		assertTrue(catalog.hasItemCard(101));
		assertFalse(catalog.hasItemCard(2));
		assertFalse(catalog.hasNpcCard(0));
		assertTrue(catalog.allCardKeys().contains("live parent"));
		assertEquals(old, catalog.packKeys(Set.of("abyssal whip")));
		assertEquals(Set.of("abyssal whip"), catalog.unpackKeysOrNull(old));
		assertTrue(catalog.itemCards(101, "different variant").contains("live parent"));
		catalog.activate(null);
		assertTrue(catalog.hasItemCard(2));
		assertFalse(catalog.hasItemCard(100_001));
	}

	@Test
	public void ambiguousEntityIdsFailOpen()
	{
		String json = SMALL.replace("\"id\":100", "\"id\":101")
			.replace("}],\"npcs\"", "},{\"id\":102,\"name\":\"Second parent\",\"tcg\":{\"variants\":[{\"id\":101,\"name\":\"Other entity\"}]}}],\"npcs\"");
		assertFalse(TcgLiveCatalogSnapshot.parse(json).itemKeysById.containsKey(101));
	}

	@Test
	public void rejectsMissingKindsFractionalIdsAndIncompleteActivation()
	{
		assertThrows(RuntimeException.class, () -> TcgLiveCatalogSnapshot.parse("{\"items\":[],\"npcs\":[]}"));
		assertThrows(RuntimeException.class, () -> TcgLiveCatalogSnapshot.parse(SMALL.replace("100", "100.5")));
		assertThrows(RuntimeException.class, () -> TcgLiveCatalogSnapshot.parse(SMALL.replace("100", "-1")));
		assertThrows(RuntimeException.class, () -> TcgLiveCatalogSnapshot.parse(SMALL).validateForActivation());
	}

	@Test
	public void snapshotsCannotBeMutated()
	{
		TcgLiveCatalogSnapshot snapshot = TcgLiveCatalogSnapshot.parse(SMALL);
		assertThrows(UnsupportedOperationException.class, () -> snapshot.itemKeysById.clear());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.itemKeysById.get(100).clear());
	}

	@Test
	public void newKeysSurviveOfflineRestoreAndDifferentCatalogVersions()
	{
		TcgCardCatalog sender = new TcgCardCatalog();
		sender.activate(TcgLiveCatalogSnapshot.parse(SMALL));
		Set<String> owned = Set.of("abyssal whip", "live parent", "future catalogue key");
		String stored = sender.packStoredKeys(owned);
		assertTrue(stored.startsWith("4:"));
		TcgCardCatalog offline = new TcgCardCatalog();
		assertEquals(owned, offline.unpackKeysOrNull(stored));
		assertEquals(owned, offline.mergeExtraKeys(offline.unpackKeysOrNull(sender.packKeys(owned)),
			TcgCollectionCodec.encode(sender.extraKeys(owned))));
		assertEquals(sender.packKeys(Set.of("abyssal whip")), sender.packStoredKeys(Set.of("abyssal whip")));
	}

	@Test
	public void corruptExtensionsDoNotTurnIntoEmptyOwnership()
	{
		TcgCardCatalog catalog = new TcgCardCatalog();
		assertNull(catalog.unpackKeysOrNull("4:" + catalog.packKeys(Set.of("rope")) + ":garbage"));
		assertNull(catalog.unpackKeysOrNull("4:4::"));
		assertNull(catalog.mergeExtraKeys(Set.of("rope"), "garbage"));
		assertNull(catalog.mergeExtraKeys(null, TcgCollectionCodec.encode(Set.of("new key"))));
		assertNull(catalog.packStoredKeys(Set.of("Invalid Key")));
		assertNull(TcgCollectionCodec.decode("a".repeat(TcgCollectionCodec.MAX_ENCODED + 1)));
		assertThrows(IllegalArgumentException.class, () -> TcgCollectionCodec.encode(Set.of("Invalid Key")));
		assertEquals(Collections.emptySet(), TcgCollectionCodec.decode(""));
	}

	@Test
	public void authoredRequirementsFollowLiveParentsAndRemovedCardsFailOpen()
	{
		TcgCardCatalog catalog = new TcgCardCatalog();
		assertFalse(catalog.requirementSatisfied("shark", Set.of("live parent")::contains));
		catalog.activate(TcgLiveCatalogSnapshot.parse(SMALL));
		assertTrue(catalog.requirementSatisfied("different variant", Set.of("live parent")::contains));
		assertFalse(catalog.requirementSatisfied("different variant", Set.of("unrelated")::contains));
		assertTrue(catalog.requirementSatisfied("shark", Set.of("live parent")::contains));
		assertTrue(catalog.requirementSatisfied("live parent", Set.of("unrelated")::contains));
		assertFalse(catalog.requirementSatisfied("npc:200", Set.of("live parent")::contains));
		catalog.activate(null);
		assertFalse(catalog.requirementSatisfied("shark", Set.of("live parent")::contains));
	}

	@Test
	public void releasedV3ProtocolHashIsFrozen()
	{
		assertTrue(new TcgCardCatalog().packKeys(Set.of("abyssal whip")).startsWith("3:cf8685bbbdcde896:"));
	}

	static String fullCatalog(String itemName)
	{
		StringBuilder json = new StringBuilder("{\"items\":[");
		for (int i = 0; i < 1000; i++)
		{
			if (i > 0) json.append(',');
			json.append("{\"id\":").append(100_000 + i).append(",\"name\":\"").append(itemName).append(i).append("\"}");
		}
		json.append("],\"npcs\":[");
		for (int i = 0; i < 300; i++)
		{
			if (i > 0) json.append(',');
			json.append("{\"id\":").append(200_000 + i).append(",\"name\":\"Creature ").append(i).append("\"}");
		}
		return json.append("]}").toString();
	}
}
