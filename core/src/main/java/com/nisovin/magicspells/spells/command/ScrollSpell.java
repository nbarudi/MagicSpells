package com.nisovin.magicspells.spells.command;

import java.util.*;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventPriority;
import org.bukkit.entity.LivingEntity;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;

import com.nisovin.magicspells.Perm;
import com.nisovin.magicspells.Spell;
import com.nisovin.magicspells.util.*;
import com.nisovin.magicspells.Spellbook;
import com.nisovin.magicspells.MagicSpells;
import com.nisovin.magicspells.spells.CommandSpell;

public class ScrollSpell extends CommandSpell {

	private static final String key = "scroll_data";
	private static final Pattern CAST_ARGUMENT_USE_COUNT_PATTERN = Pattern.compile("^-?[0-9]+$");
	private static final Pattern SCROLL_DATA_USES_PATTERN = Pattern.compile("^[0-9]+$");

	private final List<String> predefinedScrolls;

	private Map<Integer, Spell> predefinedScrollSpells;
	private Map<Integer, Integer> predefinedScrollUses;

	private Material itemType;

	private String strUsage;
	private String strOnUse;
	private String strNoSpell;
	private String strUseFail;
	private String strCantTeach;
	private String strScrollName;
	private String strScrollSubtext;
	private String strConsoleUsage;

	private int maxUses;
	private int defaultUses;

	private boolean castForFree;
	private boolean leftClickCast;
	private boolean rightClickCast;
	private boolean ignoreCastPerm;
	private boolean requireTeachPerm;
	private boolean textContainsUses;
	private boolean bypassNormalChecks;
	private boolean removeScrollWhenDepleted;
	private boolean requireScrollCastPermOnUse;
	private boolean chargeReagentsForSpellPerCharge;
		
	public ScrollSpell(MagicConfig config, String spellName) {
		super(config, spellName);

		predefinedScrolls = getConfigStringList("predefined-scrolls", null);

		itemType = Util.getMaterial(getConfigString("item-id", "paper"));

		strUsage = getConfigString("str-usage", "You must hold a single blank paper \nand type /cast scroll <spell> <uses>.");
		strOnUse = getConfigString("str-on-use", "Spell Scroll: %s used. %u uses remaining.");
		strNoSpell = getConfigString("str-no-spell", "You do not know a spell by that name.");
		strUseFail = getConfigString("str-use-fail", "Unable to use this scroll right now.");
		strCantTeach = getConfigString("str-cant-teach", "You cannot create a scroll with that spell.");
		strScrollName = getConfigString("str-scroll-name", "Magic Scroll: %s");
		strScrollSubtext = getConfigString("str-scroll-subtext", "Uses remaining: %u");
		strConsoleUsage = getConfigString("str-console-usage", "Invalid arguments defined!\nValid arguments: <playerName> <spell> <scrollUses>");

		maxUses = getConfigInt("max-uses", 10);
		defaultUses = getConfigInt("default-uses", 5);

		castForFree = getConfigBoolean("cast-for-free", true);
		leftClickCast = getConfigBoolean("left-click-cast", false);
		rightClickCast = getConfigBoolean("right-click-cast", true);
		ignoreCastPerm = getConfigBoolean("ignore-cast-perm", false);
		requireTeachPerm = getConfigBoolean("require-teach-perm", true);
		bypassNormalChecks = getConfigBoolean("bypass-normal-checks", false);
		removeScrollWhenDepleted = getConfigBoolean("remove-scroll-when-depleted", true);
		requireScrollCastPermOnUse = getConfigBoolean("require-scroll-cast-perm-on-use", true);
		chargeReagentsForSpellPerCharge = getConfigBoolean("charge-reagents-for-spell-per-charge", false);

		textContainsUses = strScrollName.contains("%u") || strScrollSubtext.contains("%u");
	}
	
	@Override
	public void initialize() {
		super.initialize();

		if (predefinedScrolls == null || predefinedScrolls.isEmpty()) return;

		predefinedScrollSpells = new HashMap<>();
		predefinedScrollUses = new HashMap<>();
		for (String s : predefinedScrolls) {
			String[] data = s.split(" ");
			try {
				int id = Integer.parseInt(data[0]);
				Spell spell = MagicSpells.getSpellByInternalName(data[1]);
				int uses = defaultUses;
				if (data.length > 2) uses = Integer.parseInt(data[2]);
				if (id > 0 && spell != null) {
					predefinedScrollSpells.put(id, spell);
					predefinedScrollUses.put(id, uses);
				} else MagicSpells.error("ScrollSpell '" + internalName + "' has invalid predefined scroll: " + s);
			} catch (Exception e) {
				MagicSpells.error("ScrollSpell '" + internalName + "' has invalid predefined scroll: " + s);
			}
		}
	}

	@Override
	public PostCastAction castSpell(LivingEntity caster, SpellCastState state, float power, String[] args) {
		if (state == SpellCastState.NORMAL && caster instanceof Player player) {
			if (args == null || args.length == 0) {
				sendMessage(strUsage, player, args);
				return PostCastAction.ALREADY_HANDLED;
			}
			
			ItemStack inHand = player.getInventory().getItemInMainHand();
			if (inHand.getAmount() != 1 || itemType != inHand.getType()) {
				sendMessage(strUsage, player, args);
				return PostCastAction.ALREADY_HANDLED;
			}
			
			Spell spell = MagicSpells.getSpellByInGameName(args[0]);
			Spellbook spellbook = MagicSpells.getSpellbook(player);
			if (spell == null || !spellbook.hasSpell(spell)) {
				sendMessage(strNoSpell, player, args);
				return PostCastAction.ALREADY_HANDLED;			
			}
			if (requireTeachPerm && !spellbook.canTeach(spell)) {
				sendMessage(strCantTeach, player, args);
				return PostCastAction.ALREADY_HANDLED;
			}
			
			int uses = defaultUses;
			if (args.length > 1 && RegexUtil.matches(CAST_ARGUMENT_USE_COUNT_PATTERN, args[1])) uses = Integer.parseInt(args[1]);
			if (uses > maxUses || (maxUses > 0 && uses <= 0)) uses = maxUses;
			
			if (chargeReagentsForSpellPerCharge && uses > 0) {
				SpellReagents reagents = spell.getReagents().multiply(uses);
				if (!hasReagents(player, reagents)) {
					sendMessage(strMissingReagents, player, args);
					return PostCastAction.ALREADY_HANDLED;
				}
				removeReagents(player, reagents);
			}
			
			inHand = createScroll(spell, uses, inHand);
			player.getInventory().setItemInMainHand(inHand);
			
			sendMessage(strCastSelf, player, args, "%s", spell.getName());
			return PostCastAction.NO_MESSAGES;
		}
		return PostCastAction.HANDLE_NORMALLY;
	}

	@Override
	public boolean castFromConsole(CommandSender sender, String[] args) {
		if (args == null || args.length < 1) {
			sender.sendMessage(strConsoleUsage);
			return false;
		}

		List<Player> players = MagicSpells.plugin.getServer().matchPlayer(args[0]);
		if (players.size() < 1) {
			sender.sendMessage("Invalid player defined!");
			return false;
		}

		Spell spell = MagicSpells.getSpellByInGameName(args[1]);
		if (spell == null) {
			sender.sendMessage(strNoSpell);
			return false;
		}

		int uses = defaultUses;
		if (args.length > 2 && RegexUtil.matches(CAST_ARGUMENT_USE_COUNT_PATTERN, args[2])) {
			uses = Integer.parseInt(args[2]);
		}

		if (uses > maxUses || (maxUses > 0 && uses <= 0)) uses = maxUses;

		int slot = -1;
		for (int i = 0; i <= 35; i++) {
			if (players.get(0).getInventory().getItem(i) == null) {
				slot = i;
				break;
			}
		}

		if (slot != -1) players.get(0).getInventory().setItem(slot, createScroll(spell, uses, new ItemStack(itemType, 1)));

		return true;
	}
	
	public ItemStack createScroll(Spell spell, int uses, ItemStack item) {
		if (item == null) item = new ItemStack(itemType);

		ItemMeta meta = item.getItemMeta();
		if (meta instanceof Damageable) ((Damageable) meta).setDamage(0);

		String displayName = strScrollName.replace("%s", spell.getName()).replace("%u", (uses >= 0 ? uses + "" : "many"));
		meta.displayName(Util.getMiniMessage(displayName));
		if (strScrollSubtext != null && !strScrollSubtext.isEmpty()) {
			Component lore = Util.getMiniMessage(strScrollSubtext.replace("%s", spell.getName()).replace("%u", (uses >= 0 ? uses + "" : "many")));
			meta.lore(Collections.singletonList(lore));
		}

		ItemUtil.addFakeEnchantment(meta);
		item.setItemMeta(meta);
		DataUtil.setString(item, key, spell.getInternalName() + (uses > 0 ? "," + uses : ""));
		return item;
	}
	
	@Override
	public List<String> tabComplete(CommandSender sender, String partial) {
		String[] args = Util.splitParams(partial);
		if (args.length == 1) return tabCompleteSpellName(sender, args[0]);
		return null;
	}
	
	@EventHandler(priority=EventPriority.MONITOR)
	public void onPlayerInteract(PlayerInteractEvent event) {
		if (!actionAllowedForCast(event.getAction())) return;
		Player player = event.getPlayer();
		ItemStack inHand = player.getInventory().getItemInMainHand();
		if (itemType != inHand.getType() || inHand.getAmount() > 1) return;
		
		// Check for predefined scroll
		if (ItemUtil.getDurability(inHand) > 0 && predefinedScrollSpells != null) {
			Spell spell = predefinedScrollSpells.get(ItemUtil.getDurability(inHand));
			if (spell != null) {
				int uses = predefinedScrollUses.get(ItemUtil.getDurability(inHand));
				inHand = createScroll(spell, uses, inHand);
				player.getInventory().setItemInMainHand(inHand);
			}
		}
		
		// Get scroll data (spell and uses)
		String scrollDataString = DataUtil.getString(inHand, key);
		if (scrollDataString == null || scrollDataString.isEmpty()) return;
		String[] scrollData = scrollDataString.split(",");
		Spell spell = MagicSpells.getSpellByInternalName(scrollData[0]);
		if (spell == null) return;
		int uses = 0;
		if (scrollData.length > 1 && RegexUtil.matches(SCROLL_DATA_USES_PATTERN, scrollData[1])) uses = Integer.parseInt(scrollData[1]);

		if (requireScrollCastPermOnUse && !MagicSpells.getSpellbook(player).canCast(this)) {
			sendMessage(strUseFail, player, MagicSpells.NULL_ARGS);
			return;
		}

		if (ignoreCastPerm && !Perm.CAST.has(player, spell)) player.addAttachment(MagicSpells.plugin, Perm.CAST.getNode(spell), true, 1);
		if (castForFree && !Perm.NO_REAGENTS.has(player)) player.addAttachment(MagicSpells.plugin, Perm.NO_REAGENTS.getNode(), true, 1);

		SpellCastState state;
		PostCastAction action;
		if (bypassNormalChecks) {
			state = SpellCastState.NORMAL;
			action = spell.castSpell(player, SpellCastState.NORMAL, 1.0F, null);
		} else {
			SpellCastResult result = spell.cast(player);
			state = result.state;
			action = result.action;
		}

		if (state != SpellCastState.NORMAL || action == PostCastAction.ALREADY_HANDLED) return;

		if (uses > 0) {
			uses -= 1;
			if (uses > 0) {
				inHand = createScroll(spell, uses, inHand);
				if (textContainsUses) player.getInventory().setItemInMainHand(inHand);
			} else {
				if (removeScrollWhenDepleted) {
					player.getInventory().setItemInMainHand(null);
					event.setCancelled(true);
				} else player.getInventory().setItemInMainHand(new ItemStack(itemType));
			}
		}

		sendMessage(strOnUse, player, MagicSpells.NULL_ARGS, "%s", spell.getName(), "%u", uses >= 0 ? uses + "" : "many");
	}
	
	@EventHandler
	public void onItemSwitch(PlayerItemHeldEvent event) {
		Player player = event.getPlayer();
		ItemStack inHand = player.getInventory().getItem(event.getNewSlot());
		
		if (inHand == null || inHand.getType() != itemType) return;
		
		if (isDurable(inHand) && predefinedScrollSpells != null) {
			Spell spell = predefinedScrollSpells.get(ItemUtil.getDurability(inHand));
			if (spell == null) return;
			
			int uses = predefinedScrollUses.get(ItemUtil.getDurability(inHand));
			inHand = createScroll(spell, uses, inHand);
			player.getInventory().setItem(event.getNewSlot(), inHand);
		}

	}

	private boolean isDurable(ItemStack item) {
		ItemMeta meta = item.getItemMeta();
		if (!(meta instanceof Damageable)) return false;
		return ((Damageable) meta).getDamage() > 0;
	}
	
	private boolean actionAllowedForCast(Action action) {
		return switch (action) {
			case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> rightClickCast;
			case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> leftClickCast;
			default -> false;
		};
	}

	public static Pattern getCastArgumentUseCountPattern() {
		return CAST_ARGUMENT_USE_COUNT_PATTERN;
	}

	public static Pattern getScrollDataUsesPattern() {
		return SCROLL_DATA_USES_PATTERN;
	}

	public List<String> getPredefinedScrolls() {
		return predefinedScrolls;
	}

	public Map<Integer, Spell> getPredefinedScrollSpells() {
		return predefinedScrollSpells;
	}

	public Map<Integer, Integer> getPredefinedScrollUses() {
		return predefinedScrollUses;
	}

	public Material getItemType() {
		return itemType;
	}

	public void setItemType(Material itemType) {
		this.itemType = itemType;
	}

	public String getStrUsage() {
		return strUsage;
	}

	public void setStrUsage(String strUsage) {
		this.strUsage = strUsage;
	}

	public String getStrOnUse() {
		return strOnUse;
	}

	public void setStrOnUse(String strOnUse) {
		this.strOnUse = strOnUse;
	}

	public String getStrNoSpell() {
		return strNoSpell;
	}

	public void setStrNoSpell(String strNoSpell) {
		this.strNoSpell = strNoSpell;
	}

	public String getStrUseFail() {
		return strUseFail;
	}

	public void setStrUseFail(String strUseFail) {
		this.strUseFail = strUseFail;
	}

	public String getStrCantTeach() {
		return strCantTeach;
	}

	public void setStrCantTeach(String strCantTeach) {
		this.strCantTeach = strCantTeach;
	}

	public String getStrScrollName() {
		return strScrollName;
	}

	public void setStrScrollName(String strScrollName) {
		this.strScrollName = strScrollName;
	}

	public String getStrScrollSubtext() {
		return strScrollSubtext;
	}

	public void setStrScrollSubtext(String strScrollSubtext) {
		this.strScrollSubtext = strScrollSubtext;
	}

	public String getStrConsoleUsage() {
		return strConsoleUsage;
	}

	public void setStrConsoleUsage(String strConsoleUsage) {
		this.strConsoleUsage = strConsoleUsage;
	}

	public int getMaxUses() {
		return maxUses;
	}

	public void setMaxUses(int maxUses) {
		this.maxUses = maxUses;
	}

	public int getDefaultUses() {
		return defaultUses;
	}

	public void setDefaultUses(int defaultUses) {
		this.defaultUses = defaultUses;
	}

	public boolean canCastForFree() {
		return castForFree;
	}

	public void setCastForFree(boolean castForFree) {
		this.castForFree = castForFree;
	}

	public boolean shouldLeftClickCast() {
		return leftClickCast;
	}

	public void setLeftClickCast(boolean leftClickCast) {
		this.leftClickCast = leftClickCast;
	}

	public boolean shouldRightClickCast() {
		return rightClickCast;
	}

	public void setRightClickCast(boolean rightClickCast) {
		this.rightClickCast = rightClickCast;
	}

	public boolean shouldIgnoreCastPerm() {
		return ignoreCastPerm;
	}

	public void setIgnoreCastPerm(boolean ignoreCastPerm) {
		this.ignoreCastPerm = ignoreCastPerm;
	}

	public boolean shouldRequireTeachPerm() {
		return requireTeachPerm;
	}

	public void setRequireTeachPerm(boolean requireTeachPerm) {
		this.requireTeachPerm = requireTeachPerm;
	}

	public boolean shouldTextContainUses() {
		return textContainsUses;
	}

	public void setTextContainUses(boolean textContainsUses) {
		this.textContainsUses = textContainsUses;
	}

	public boolean shouldBypassNormalChecks() {
		return bypassNormalChecks;
	}

	public void setBypassNormalChecks(boolean bypassNormalChecks) {
		this.bypassNormalChecks = bypassNormalChecks;
	}

	public boolean shouldRemoveScrollWhenDepleted() {
		return removeScrollWhenDepleted;
	}

	public void setRemoveScrollWhenDepleted(boolean removeScrollWhenDepleted) {
		this.removeScrollWhenDepleted = removeScrollWhenDepleted;
	}

	public boolean shouldScrollCastRequirePermOnUse() {
		return requireScrollCastPermOnUse;
	}

	public void setRequireScrollCastPermOnUse(boolean requireScrollCastPermOnUse) {
		this.requireScrollCastPermOnUse = requireScrollCastPermOnUse;
	}

	public boolean shouldChargeReagentsForSpellPerCharge() {
		return chargeReagentsForSpellPerCharge;
	}

	public void setChargeReagentsForSpellPerCharge(boolean chargeReagentsForSpellPerCharge) {
		this.chargeReagentsForSpellPerCharge = chargeReagentsForSpellPerCharge;
	}

}
