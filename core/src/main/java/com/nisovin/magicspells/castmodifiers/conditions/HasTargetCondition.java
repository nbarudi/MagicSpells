package com.nisovin.magicspells.castmodifiers.conditions;

import org.bukkit.Location;
import org.bukkit.entity.Creature;
import org.bukkit.entity.LivingEntity;

import com.nisovin.magicspells.castmodifiers.Condition;

public class HasTargetCondition extends Condition {

	@Override
	public boolean initialize(String var) {
		return true;
	}

	@Override
	public boolean check(LivingEntity livingEntity) {
		return hasTarget(livingEntity);
	}

	@Override
	public boolean check(LivingEntity livingEntity, LivingEntity target) {
		return hasTarget(target);
	}

	@Override
	public boolean check(LivingEntity livingEntity, Location location) {
		return false;
	}

	private boolean hasTarget(LivingEntity livingEntity) {
		if (!(livingEntity instanceof Creature creature)) return false;
		LivingEntity target = creature.getTarget();
		return target != null && target.isValid();
	}

}
