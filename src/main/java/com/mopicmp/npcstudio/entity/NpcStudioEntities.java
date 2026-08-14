package com.mopicmp.npcstudio.entity;

import com.mopicmp.npcstudio.NpcStudio;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Registration for everything this mod adds to the entity registry. */
public final class NpcStudioEntities {

	private NpcStudioEntities() { }

	private static final ResourceKey<EntityType<?>> NPC_KEY =
		ResourceKey.create(Registries.ENTITY_TYPE, NpcStudio.id("npc"));

	/**
	 * The NPC type.
	 *
	 * {@code MobCategory.MISC} because an NPC is placed, never spawned by the
	 * world — putting it in a living category would make mob caps and spawn rules
	 * apply to something they were never meant to count.
	 *
	 * The size is a player's, since the model is a player's. Taking it from
	 * anywhere else would make the hitbox disagree with what is drawn, and a
	 * click that misses the visible body is the sort of thing players report as
	 * "the NPC ignores me".
	 */
	// The type witness is not decoration: without it javac infers the factory's
	// entity as a bare Entity and the whole builder chain comes out as
	// EntityType<Entity>, which then refuses to be assigned here.
	public static final EntityType<NpcEntity> NPC = EntityType.Builder
		.<NpcEntity>of((type, level) -> NpcEntity.factory.apply(type, level), MobCategory.MISC)
		.sized(0.6f, 1.8f)
		.eyeHeight(1.62f)
		.build(NPC_KEY);

	public static void register() {
		Registry.register(BuiltInRegistries.ENTITY_TYPE, NPC_KEY, NPC);

		// Without this the entity throws the moment it is spawned: LivingEntity
		// reads its attributes during construction, and an unregistered supplier
		// leaves them missing rather than defaulted.
		FabricDefaultAttributeRegistry.register(NPC, NpcEntity.createAttributes());
	}
}
