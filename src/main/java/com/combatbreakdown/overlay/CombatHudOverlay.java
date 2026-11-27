package com.combatbreakdown.overlay;

import com.combatbreakdown.CombatBreakdownConfig;
import com.combatbreakdown.CombatBreakdownPlugin;
import com.combatbreakdown.logic.CombatSessionTracker;
import com.combatbreakdown.logic.CombatSessionTracker.SessionSnapshot;
import com.combatbreakdown.logic.SupplyBurnTracker.BurnSnapshot;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

@Singleton
public class CombatHudOverlay extends OverlayPanel
{
	private static final Color ACTIVE_GREEN = new Color(56, 214, 134);
	private static final Color WARN_GOLD = new Color(245, 194, 66);
	private static final Color DAMAGE_RED = new Color(239, 98, 98);
	private static final Color ACCENT_BLUE = new Color(112, 190, 255);
	private static final Color MUTED_TEXT = new Color(175, 185, 195);

	private final CombatBreakdownPlugin plugin;
	private final CombatBreakdownConfig config;

	@Inject
	public CombatHudOverlay(CombatBreakdownPlugin plugin, CombatBreakdownConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Pause / Resume", "Combat Breakdown", e -> plugin.togglePaused());
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Reset Session", "Combat Breakdown", e -> plugin.resetSession());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		CombatBreakdownConfig.HudVisibility vis = config.hudVisibility();
		if (vis == CombatBreakdownConfig.HudVisibility.OFF)
		{
			return null;
		}
		SessionSnapshot session = plugin.getLatestSession();
		if (session == null || !session.isSessionStarted())
		{
			return null;
		}
		if (vis == CombatBreakdownConfig.HudVisibility.IN_COMBAT_ONLY && !session.isInActiveCombat())
		{
			return null;
		}

		FontMetrics fm = graphics.getFontMetrics();
		String header = session.isPaused()
			? "Combat Breakdown (Paused)"
			: (!session.getActiveTargetName().isEmpty() ? truncate(session.getActiveTargetName(), 24) : "Combat Breakdown");
		int maxWidth = Math.max(178, fm.stringWidth(header) + 24);

		Color titleColor = session.isPaused() ? DAMAGE_RED : (session.isInActiveCombat() ? ACTIVE_GREEN : WARN_GOLD);
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(header)
			.color(titleColor)
			.build());

		String activeDpsText = String.format(Locale.US, "%.2f (%s)",
			session.getActiveDps(), CombatSessionTracker.formatDuration(session.getActiveSeconds()));
		maxWidth = addRow(fm, maxWidth, "Active DPS", activeDpsText, Color.WHITE, ACTIVE_GREEN);

		String sessionDpsText = String.format(Locale.US, "%.2f (%s)",
			session.getSessionDps(), CombatSessionTracker.formatDuration(session.getSessionSeconds()));
		maxWidth = addRow(fm, maxWidth, "Session DPS", sessionDpsText, MUTED_TEXT, ACCENT_BLUE);

		String accText = String.format(Locale.US, "%.1f%% (%d/%d)",
			session.getAccuracyPercent(), session.getTotalHitsLanded(), session.getTotalSwings());
		maxWidth = addRow(fm, maxWidth, "Accuracy", accText, MUTED_TEXT, Color.WHITE);

		String maxHitText = session.getNativeMaxSplats() > 0
			? (session.getMaxHit() + " (" + session.getNativeMaxSplats() + " max)")
			: Integer.toString(session.getMaxHit());
		maxWidth = addRow(fm, maxWidth, "Max Hit", maxHitText, MUTED_TEXT, WARN_GOLD);

		if (config.hudShowDamageTaken())
		{
			long taken = session.getStatusAndDefense().getTotalDamageTaken();
			long healed = session.getStatusAndDefense().getHealingReceived();
			String takenText = healed > 0 ? (taken + " / +" + healed) : Long.toString(taken);
			maxWidth = addRow(fm, maxWidth, "Taken / Heal", takenText, MUTED_TEXT, taken > 0 ? DAMAGE_RED : MUTED_TEXT);
		}

		if (config.hudShowSupplyBurn() && config.trackSupplyBurn())
		{
			BurnSnapshot burn = plugin.getLatestBurn();
			if (burn != null && !burn.isEmpty())
			{
				if (plugin.shouldDisplayGpValues())
				{
					String gpText = formatCompactGp(burn.getGpPerHour()) + "/hr";
					maxWidth = addRow(fm, maxWidth, "Supply Burn", gpText, MUTED_TEXT, WARN_GOLD);
				}
				else
				{
					String countText = burn.getFoodEaten() + "f " + burn.getPotionDoses() + "d " + (burn.getAmmoUsed() + burn.getRunesUsed()) + "a";
					maxWidth = addRow(fm, maxWidth, "Supplies", countText, MUTED_TEXT, ACCENT_BLUE);
				}
			}
		}

		panelComponent.setPreferredSize(new Dimension(Math.min(maxWidth, 290), 0));
		return super.render(graphics);
	}

	private int addRow(FontMetrics fm, int currentMaxWidth, String left, String right, Color leftColor, Color rightColor)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(left)
			.leftColor(leftColor)
			.right(right)
			.rightColor(rightColor)
			.build());
		int needed = fm.stringWidth(left) + fm.stringWidth(right) + 30;
		return Math.max(currentMaxWidth, needed);
	}

	public static String formatCompactGp(long gp)
	{
		if (gp >= 10_000_000L)
		{
			return String.format(Locale.US, "%.1fM gp", gp / 1_000_000.0);
		}
		if (gp >= 100_000L)
		{
			return String.format(Locale.US, "%.0fK gp", gp / 1_000.0);
		}
		if (gp >= 10_000L)
		{
			return String.format(Locale.US, "%.1fK gp", gp / 1_000.0);
		}
		return String.format(Locale.US, "%,d gp", gp);
	}

	private static String truncate(String text, int maxLen)
	{
		if (text == null || text.length() <= maxLen)
		{
			return text != null ? text : "";
		}
		return text.substring(0, maxLen - 1) + "\u2026";
	}
}
