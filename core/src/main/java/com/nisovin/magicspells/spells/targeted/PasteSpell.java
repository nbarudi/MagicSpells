package com.nisovin.magicspells.spells.targeted;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.IOException;
import java.io.FileInputStream;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import com.nisovin.magicspells.MagicSpells;
import com.nisovin.magicspells.util.MagicConfig;
import com.nisovin.magicspells.spells.TargetedSpell;
import com.nisovin.magicspells.util.config.ConfigData;
import com.nisovin.magicspells.spelleffects.EffectPosition;
import com.nisovin.magicspells.spells.TargetedLocationSpell;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;

public class PasteSpell extends TargetedSpell implements TargetedLocationSpell {

	private List<EditSession> sessions;
	private List<Builder> builders;
	private List<String> buildStartOffsetStrings;
	private List<String> dismantleStartOffsetStrings;
	private List<Vector3f> buildStartOffsets;
	private List<Vector3f> dismantleStartOffsets;

	private File file;
	private Clipboard clipboard;

	private ConfigData<Integer> yOffset;
	private ConfigData<Integer> undoDelay;

	private final int buildInterval;
	private final int maxWorkingBlocks;
	private final int buildIntervalRandomness;

	private boolean pasteAir;
	private boolean removePaste;
	private boolean pasteAtCaster;
	private boolean displayAnimation;
	private boolean playBlockBreakEffect;
	private boolean dismantleFirst;


	public PasteSpell(MagicConfig config, String spellName) {
		super(config, spellName);

		File folder = new File(MagicSpells.plugin.getDataFolder(), "schematics");
		if (!folder.exists()) folder.mkdir();
		String schematic = getConfigString("schematic", "none");
		file = new File(folder, schematic);
		if (!file.exists()) MagicSpells.error("PasteSpell " + spellName + " has non-existant schematic: " + schematic);

		yOffset = getConfigDataInt("y-offset", 0);
		undoDelay = getConfigDataInt("undo-delay", 0);

		buildInterval = getConfigInt("build-interval", 0);
		buildIntervalRandomness = getConfigInt("build-interval-randomness", 7) + 1;
		maxWorkingBlocks = getConfigInt("max-working-blocks", 1000);

		pasteAir = getConfigBoolean("paste-air", false);
		dismantleFirst = getConfigBoolean("dismantle-first", false);
		removePaste = getConfigBoolean("remove-paste", true);
		pasteAtCaster = getConfigBoolean("paste-at-caster", false);
		displayAnimation = getConfigBoolean("display-animation", true);
		playBlockBreakEffect = getConfigBoolean("play-block-break-effect", true);

		buildStartOffsetStrings = getConfigStringList("build-start-offsets", null);
		dismantleStartOffsetStrings = getConfigStringList("dismantle-start-offsets", null);

		sessions = new ArrayList<EditSession>();
		builders = new ArrayList<Builder>();
	}

	@Override
	public void initialize() {
		super.initialize();

		ClipboardFormat format = ClipboardFormats.findByFile(file);
		try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
			clipboard = reader.read();
		} catch (IOException e) {
			e.printStackTrace();
		}

		if (clipboard == null) MagicSpells.error("PasteSpell " + internalName + " has a wrong schematic!");

		this.buildStartOffsets = new ArrayList<Vector3f>();
		if (this.buildStartOffsetStrings != null) {
			for (String s : this.buildStartOffsetStrings) {
				if (s.matches("^-?\\d+ -?\\d+ -?\\d+$")) {
				    String[] parts = s.split(" ");
				    int x = Integer.parseInt(parts[0]);
				    int y = Integer.parseInt(parts[1]);
				    int z = Integer.parseInt(parts[2]);
				    this.buildStartOffsets.add(new Vector3f(x, y, z));
				} else {
					MagicSpells.error("PasteSpell " + internalName + " has a wrong build-start-offset. The correct syntax is `- 1 2 3`");
				}
			}
		}

		this.dismantleStartOffsets = new ArrayList<Vector3f>();
		if (this.dismantleStartOffsetStrings != null) {
			for (String s : this.dismantleStartOffsetStrings) {
				if (s.matches("^-?\\d+ -?\\d+ -?\\d+$")) {
				    String[] parts = s.split(" ");
				    int x = Integer.parseInt(parts[0]);
				    int y = Integer.parseInt(parts[1]);
				    int z = Integer.parseInt(parts[2]);
				    this.dismantleStartOffsets.add(new Vector3f(x, y, z));
				} else {
					MagicSpells.error("PasteSpell " + internalName + " has a wrong dismantle-start-offset. The correct syntax is `- 1 2 3`");
				}
			}
		}
	}

	@Override
	public void turnOff() {
		for (EditSession session : sessions) {
			session.undo(session);
		}
		for (Builder builder : builders) {
			builder.stop = true;
		}
		sessions.clear();
		builders.clear();
	}

	@Override
	public PostCastAction castSpell(LivingEntity caster, SpellCastState state, float power, String[] args) {
		if (state == SpellCastState.NORMAL) {
			Block target = pasteAtCaster ? caster.getLocation().getBlock() : getTargetedBlock(caster, power, args);
			if (target == null) return noTarget(caster, args);
			Location loc = target.getLocation();
			boolean ok = castAtLocation(caster, loc, power, args);
			if (!ok) return noTarget(caster, args);
		}
		return PostCastAction.HANDLE_NORMALLY;
	}

	@Override
	public boolean castAtLocation(LivingEntity caster, Location target, float power, String[] args) {
		boolean ok;

		if (buildInterval < 1) {
			ok = pasteInstant(caster, target, power, args);
		} else {
			ok = pasteOverTime(caster, target, power, args);
		}
		if (!ok) return false;
		if (caster != null) playSpellEffects(caster, target, power, args);
		else playSpellEffects(EffectPosition.TARGET, target, power, args);
		return true;
	}

	@Override
	public boolean castAtLocation(LivingEntity caster, Location target, float power) {
		return castAtLocation(caster, target, power, null);
	}

	@Override
	public boolean castAtLocation(Location target, float power, String[] args) {
		return castAtLocation(null, target, power, null);
	}

	@Override
	public boolean castAtLocation(Location target, float power) {
		return castAtLocation(null, target, power, null);
	}

	private boolean pasteInstant(LivingEntity caster, Location target, float power, String[] args) {
		if (clipboard == null) return false;

		int yOffset = this.yOffset.get(caster, null, power, args);
		target.add(0, yOffset, 0);

		try (EditSession editSession = WorldEdit.getInstance().getEditSessionFactory().getEditSession(BukkitAdapter.adapt(target.getWorld()), -1)) {
			Operation operation = new ClipboardHolder(clipboard)
					.createPaste(editSession)
					.to(BlockVector3.at(target.getX(), target.getY(), target.getZ()))
					.ignoreAirBlocks(!pasteAir)
					.build();
			Operations.complete(operation);
			if (removePaste) sessions.add(editSession);

			int undoDelay = this.undoDelay.get(caster, null, power, args);
			if (undoDelay > 0) {
				MagicSpells.scheduleDelayedTask(() -> {
					editSession.undo(editSession);
					sessions.remove(editSession);
				}, undoDelay);
			}
		} catch (WorldEditException e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

	private boolean pasteOverTime(LivingEntity caster, Location target, float power, String[] args) {
		try {
			builders.add(new Builder(caster, target, power, args));
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	class Builder {
		List<BlockFace> CARDINAL_BLOCK_FACES = new ArrayList<BlockFace>(Arrays.asList(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN));

	    Location target;

	    Clipboard clipboard;

	    int workingBlocks = 0;
	    int workingAir = 0;

	    List<BlockVector3> blockVectors;
	    List<BlockVector3> airVectors;

	    boolean stop = false;

		public Builder(LivingEntity caster, Location target, float power, String[] args) {
			this.target = target.clone();
			this.clipboard = PasteSpell.this.clipboard;

			this.parseClipboard();

	        BlockVector3 origin = this.clipboard.getOrigin();

        	if (PasteSpell.this.dismantleFirst && PasteSpell.this.pasteAir) {
    	        for (BlockVector3 pos : this.blockVectors) {
    				Block bl = this.target.getBlock().getRelative(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ());
    				if (!bl.getBlockData().getMaterial().isAir()) {
    					this.airVectors.add(pos);
    				}
    	        }

    	        if (this.airVectors.size() > 0) this.firstWithdrawInit(origin);
    	        else if (this.blockVectors.size() > 0) this.firstBuildInit(origin);

	        } else {
    	        if (this.blockVectors.size() > 0) this.firstBuildInit(origin);
    	        if (PasteSpell.this.pasteAir && this.airVectors.size() > 0) this.firstWithdrawInit(origin);
	        }
		}

		private void firstBuildInit(BlockVector3 origin) {
			List<Vector3f> vecs = PasteSpell.this.buildStartOffsets;

			if (vecs.isEmpty()) {
				this.intialize(origin);
			} else {
				for (Vector3f vec : vecs) {
					this.intialize(BlockVector3.at(origin.getX() + vec.x, origin.getY() + vec.y, origin.getZ() + vec.z));
				}
			}
		}

		private void firstWithdrawInit(BlockVector3 origin) {
			List<Vector3f> vecs = PasteSpell.this.dismantleStartOffsets;

			if (vecs.isEmpty()) {
				this.intializeWithdraw(origin);
			} else {
				for (Vector3f vec : vecs) {
					this.intializeWithdraw(BlockVector3.at(origin.getX() + vec.x, origin.getY() + vec.y, origin.getZ() + vec.z));
				}
			}
		}

		private void parseClipboard() {
		    this.blockVectors = new ArrayList<BlockVector3>();
		    this.airVectors = new ArrayList<BlockVector3>();

	        BlockVector3 origin = this.clipboard.getOrigin();

	        for (BlockVector3 pos : this.clipboard.getRegion()) {
				BlockData data = BukkitAdapter.adapt(clipboard.getBlock(pos));

				Block bl = this.target.getBlock().getRelative(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ());

				if (!data.matches(bl.getBlockData())) {
					if (data.getMaterial().isAir()) {
						this.airVectors.add(pos);
					} else {
						this.blockVectors.add(pos);
					}
				}
	        }
		}

		private void intialize(BlockVector3 pos) {
	        int x = pos.getX() - this.clipboard.getOrigin().getX();
	        int y = pos.getY() - this.clipboard.getOrigin().getY();
	        int z = pos.getZ() - this.clipboard.getOrigin().getZ();

	        Location loc = this.target.clone().add(x, y, z);
			Block startingBlock = loc.getBlock();
			BlockData data = BukkitAdapter.adapt(clipboard.getBlock(pos));
			Block animatorBlock = null;
			BlockFace face = null;

			for (BlockFace f : CARDINAL_BLOCK_FACES) {
				Block b = startingBlock.getRelative(f);
				BlockData bd = b.getBlockData();
				if (bd.getMaterial().isSolid()) {
					animatorBlock = b;
					face = f;
					if (data.matches(bd)) break;
				}
			}

			if (animatorBlock == null) {
				if (PasteSpell.this.playBlockBreakEffect) this.moveBlockEffects(startingBlock, data, 0, 0, 0, 0);
				startingBlock.setBlockData(data);
		        this.placeBlock(startingBlock, pos.getX(), pos.getY(), pos.getZ());
			} else {
		        this.placeBlock(animatorBlock, pos.getX() + face.getModX(), pos.getY() + face.getModY(), pos.getZ() + face.getModZ());
			}
		}

		private void intializeWithdraw(BlockVector3 pos) {
	        int x = pos.getX() - this.clipboard.getOrigin().getX();
	        int y = pos.getY() - this.clipboard.getOrigin().getY();
	        int z = pos.getZ() - this.clipboard.getOrigin().getZ();

	        Location loc = this.target.clone().add(x, y, z);
			Block startingBlock = loc.getBlock();

			BlockFace face = null;

			for (BlockFace f : CARDINAL_BLOCK_FACES) {
				Block b = startingBlock.getRelative(f);
				BlockData bd = b.getBlockData();
				if (bd.getMaterial().isSolid()) {
					face = f;
					if (startingBlock.getBlockData().matches(bd)) break;
				}
			}

			this.withdrawBlock(startingBlock, pos.getX(), pos.getY(), pos.getZ(), face);
		}

		private void reInitialize() {
	        BlockVector3 origin = this.clipboard.getOrigin();

			double closestDistanceSq = 0;
			BlockVector3 closestPos = null;

	        for (BlockVector3 pos : this.blockVectors) {
	        	double distanceX = pos.getX() - origin.getX();
	        	double distanceY = pos.getY() - origin.getY();
	        	double distanceZ = pos.getZ() - origin.getZ();
	        	double distanceSq = distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ;

	        	if (closestPos == null || distanceSq < closestDistanceSq) {
	        		closestDistanceSq = distanceSq;
	        		closestPos = pos;
	        	}
			}

	        if (closestPos != null) this.intialize(closestPos);
		}

		private void reInitializeWithdraw() {
	        BlockVector3 origin = this.clipboard.getOrigin();

			double furthestDistanceSq = 0;
			BlockVector3 furthestPos = null;

	        for (BlockVector3 pos : this.airVectors) {
	        	double distanceX = pos.getX() - origin.getX();
	        	double distanceY = pos.getY() - origin.getY();
	        	double distanceZ = pos.getZ() - origin.getZ();
	        	double distanceSq = distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ;

	        	if (furthestPos == null || distanceSq > furthestDistanceSq) {
	        		furthestDistanceSq = distanceSq;
	        		furthestPos = pos;
	        	}
			}

	        if (furthestPos != null) this.intializeWithdraw(furthestPos);
	        else if (PasteSpell.this.dismantleFirst) {
	        	this.parseClipboard();
    	        if (this.blockVectors.size() > 0) this.firstBuildInit(origin);
	        }
		}

		private void placeBlock(Block block, int x, int y, int z) {
			if (this.stop) return;

			for (BlockFace face : CARDINAL_BLOCK_FACES) {
				if ((this.workingBlocks + this.workingAir) > PasteSpell.this.maxWorkingBlocks) return; //TODO: Make this something more elegant

				Block to = block.getRelative(face);
				BlockVector3 pos = BlockVector3.at(x + face.getModX(), y + face.getModY(), z + face.getModZ());
				if (!this.blockVectors.contains(pos)) continue;

				BlockData data = BukkitAdapter.adapt(clipboard.getBlock(pos));

		        this.blockVectors.remove(pos);

				int duration = new Random().nextInt(buildIntervalRandomness) + buildInterval;

				this.workingBlocks++;

				if (PasteSpell.this.playBlockBreakEffect) this.moveBlockEffects(block, data, face.getModX(), face.getModY(), face.getModZ(), duration);
				if (PasteSpell.this.displayAnimation) this.moveBlock(block, data, face.getModX(), face.getModY(), face.getModZ(), duration, true);

				MagicSpells.scheduleDelayedTask(() -> {
					to.setBlockData(data);
					this.placeBlock(to, pos.getX(), pos.getY(), pos.getZ());
					this.workingBlocks--;

					if (this.workingBlocks < 1 && !this.blockVectors.isEmpty()) {
						this.reInitialize();
					}
				}, duration);
	        }
			if (this.workingBlocks < 1 && !this.blockVectors.isEmpty()) {
				this.reInitialize();
			}
	    }

		private void withdrawBlock(Block block, int x, int y, int z, BlockFace priorityFace) {
			if (this.stop) return;

			BlockVector3 currPos = BlockVector3.at(x, y, z);
			if (this.airVectors.contains(currPos)) {
				this.workingAir++;

				BlockData data = block.getBlockData();
				Block withdrawBlock = null;

				if (priorityFace != null) {
					withdrawBlock = block.getRelative(priorityFace);
					if (withdrawBlock.getBlockData().getMaterial().isAir()) {
						withdrawBlock = null;
					}
				}

				for (BlockFace face : CARDINAL_BLOCK_FACES) {
					if ((this.workingBlocks + this.workingAir) > PasteSpell.this.maxWorkingBlocks) return;
					this.airVectors.remove(currPos);

					Block to = block.getRelative(face);

					if (to.getBlockData().getMaterial().isAir()) continue;

					int duration = new Random().nextInt(8) + buildInterval;

					if (withdrawBlock == null) {
						withdrawBlock = to;
					}

					if (to.getLocation().equals(withdrawBlock.getLocation())) {
						if (PasteSpell.this.playBlockBreakEffect) this.moveBlockEffects(block, data, face.getModX(), face.getModY(), face.getModZ(), duration);
						if (PasteSpell.this.displayAnimation) this.moveBlock(block, data, face.getModX(), face.getModY(), face.getModZ(), duration, false);

						MagicSpells.scheduleDelayedTask(() -> {
							this.withdrawBlock(to, x + face.getModX(), y + face.getModY(), z + face.getModZ(), face);
							this.workingAir--;

							if (this.workingAir < 1) {
								this.reInitializeWithdraw();
							}
						}, duration);
					} else {
						MagicSpells.scheduleDelayedTask(() -> {
							this.withdrawBlock(to, x + face.getModX(), y + face.getModY(), z + face.getModZ(), face);
						}, duration);
					}
				}

				if (withdrawBlock == null) {
					if (PasteSpell.this.playBlockBreakEffect) this.moveBlockEffects(block, data, 0, 0, 0, 0);
					block.setType(Material.AIR);
					this.workingAir--;
				}
			}

			if (this.workingAir < 1) {
				this.reInitializeWithdraw();
			}
		}

		private void moveBlock(Block block, BlockData data, int x, int y, int z, int duration, boolean keepOld) {
	        BlockDisplay ent = (BlockDisplay)block.getWorld().spawnEntity(block.getLocation(), EntityType.BLOCK_DISPLAY);
	        Block b = block.getRelative(x, y, z);

	        if (!keepOld) block.setType(Material.AIR);

	        ent.setBlock(data);

	        Block lightBlock = null;

			for (BlockFace face : CARDINAL_BLOCK_FACES) {
				lightBlock = block.getRelative(face);
	        	if (lightBlock.getBlockData().getMaterial().isAir()) break;
			}

	        ent.setBrightness(new Display.Brightness(lightBlock.getLightFromBlocks(), lightBlock.getLightFromSky()));
	        if (keepOld) ent.setTransformation(new Transformation(new Vector3f(0.005f), new AxisAngle4f(), new Vector3f(0.955f), new AxisAngle4f()));
	        else ent.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1), new AxisAngle4f()));

			MagicSpells.scheduleDelayedTask(() -> {
	            ent.setInterpolationDelay(-1);
	            ent.setInterpolationDuration(duration);
	            ent.setTransformation(new Transformation(new Vector3f(x, y, z), new AxisAngle4f(), new Vector3f(1), new AxisAngle4f()));
			}, 2);

			if (keepOld) {
				MagicSpells.scheduleDelayedTask(() -> {
		            b.setBlockData(data);
		            b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), data.getSoundGroup().getPlaceSound(), 0.2f, data.getSoundGroup().getPitch());
				}, duration + 2);
			}

			MagicSpells.scheduleDelayedTask(() -> {
	            ent.remove();
			}, duration + 3);
	    }

	    private void moveBlockEffects(Block block, BlockData data, int x, int y, int z, int duration) {
	        Vector3f vec = new Vector3f(x, y, z);
	        vec.normalize();
	        vec = vec.sub(new Vector3f(0.1f));
	        Location loc = block.getLocation().add(0.5, 0.5, 0.5).add(vec.x, vec.y, vec.z);

	        for (int i = 0; i < duration / 6; i++) MagicSpells.scheduleDelayedTask(() ->
	        {
	            Block b = block.getRelative(x, y, z);
	            b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), data.getSoundGroup().getBreakSound(), 0.1f, 0.1f);
	            block.getWorld().spawnParticle(Particle.BLOCK_CRACK, loc, 5, 0.2, 0.2, 0.2, data);
	        }, i * 6);
	    }
	}
}
