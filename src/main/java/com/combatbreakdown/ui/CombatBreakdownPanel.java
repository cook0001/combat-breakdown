package com.combatbreakdown.ui;

import com.combatbreakdown.CombatBreakdownConfig;
import com.combatbreakdown.CombatBreakdownPlugin;
import com.combatbreakdown.logic.CombatSessionTracker;
import com.combatbreakdown.logic.CombatSessionTracker.SessionSnapshot;
import com.combatbreakdown.logic.SupplyBurnTracker.BurnSnapshot;
import com.combatbreakdown.model.FightRecord;
import com.combatbreakdown.model.HitDistribution;
import com.combatbreakdown.model.StatusDamageBreakdown;
import com.combatbreakdown.model.SupplyUsage;
import com.combatbreakdown.model.TargetSummary;
import com.combatbreakdown.model.WeaponStats;
import com.combatbreakdown.overlay.CombatHudOverlay;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

public class CombatBreakdownPanel extends PluginPanel
{
	private static final String KEY_ACTIVE_TAB = "activePanelTab";

	private enum Tab
	{
		LIVE("Live"),
		SUPPLIES("Supplies"),
		HISTORY("History");

		final String label;

		Tab(String label)
		{
			this.label = label;
		}
	}

	private static final Color GREEN = new Color(46, 196, 118);
	private static final Color GOLD = new Color(235, 180, 48);
	private static final Color RED = new Color(232, 92, 92);
	private static final Color BLUE = new Color(110, 186, 255);
	private static final Color PURPLE = new Color(180, 134, 245);
	private static final Color SURFACE_CARD = new Color(36, 39, 44);
	private static final Color SURFACE_ELEVATED = new Color(46, 51, 58);
	private static final Color BORDER_SUBTLE = new Color(58, 63, 71);
	private static final Color PILL_ACTIVE = new Color(42, 138, 96);
	private static final Color BAR_TRACK = new Color(22, 25, 29);

	private final CombatBreakdownPlugin plugin;
	private final ConfigManager configManager;
	private final ItemManager itemManager;

	private Tab activeTab = Tab.LIVE;
	private final Map<Tab, JButton> tabButtons = new LinkedHashMap<>();
	private final JLabel statusBadge = new JLabel("IDLE");
	private final JLabel toastLabel = new JLabel("");
	private final Timer toastClearTimer;
	private JButton pauseBtn;
	private final ViewportWidthPanel bodyPanel = new ViewportWidthPanel();

	public CombatBreakdownPanel(
		CombatBreakdownPlugin plugin,
		ConfigManager configManager,
		ItemManager itemManager)
	{
		super(false);
		this.plugin = plugin;
		this.configManager = configManager;
		this.itemManager = itemManager;

		this.toastClearTimer = new Timer(2200, e ->
		{
			toastLabel.setText("");
			toastLabel.setVisible(false);
		});
		this.toastClearTimer.setRepeats(false);

		loadPersistedTab();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);

		bodyPanel.setLayout(new BoxLayout(bodyPanel, BoxLayout.Y_AXIS));
		bodyPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bodyPanel.setBorder(new EmptyBorder(6, 8, 12, 8));

		JScrollPane scroll = new JScrollPane(bodyPanel);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		refresh();
	}

	public void dispose()
	{
		toastClearTimer.stop();
	}

	private JPanel buildHeader()
	{
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		top.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_SUBTLE),
			new EmptyBorder(8, 8, 6, 8)
		));

		JPanel titleRow = new JPanel(new BorderLayout(4, 0));
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel title = new JLabel("Combat Breakdown");
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 13.5f));
		titleRow.add(title, BorderLayout.WEST);

		statusBadge.setFont(statusBadge.getFont().deriveFont(Font.BOLD, 10f));
		statusBadge.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusBadge.setHorizontalAlignment(SwingConstants.RIGHT);
		titleRow.add(statusBadge, BorderLayout.EAST);
		top.add(titleRow);
		top.add(Box.createVerticalStrut(7));

		JPanel actions = new JPanel(new GridLayout(1, 3, 4, 0));
		actions.setOpaque(false);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));

		pauseBtn = flatButton("Pause", SURFACE_ELEVATED);
		pauseBtn.addActionListener(e -> plugin.togglePaused());

		JButton resetBtn = flatButton("Reset", new Color(138, 52, 56));
		resetBtn.setToolTipText("Reset live session metrics and supply counters (keeps saved target history)");
		resetBtn.addActionListener(e ->
		{
			plugin.resetSession();
			showToast("Session reset");
		});

		JButton copyBtn = flatButton("Copy", SURFACE_ELEVATED);
		copyBtn.setToolTipText("Copy combat summary to clipboard");
		copyBtn.addActionListener(e -> copyToClipboard(plugin.buildSummaryText(), "Copied combat summary"));

		actions.add(pauseBtn);
		actions.add(resetBtn);
		actions.add(copyBtn);
		top.add(actions);
		top.add(Box.createVerticalStrut(6));

		JPanel tabs = new JPanel(new GridLayout(1, 3, 4, 0));
		tabs.setOpaque(false);
		tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
		tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		for (Tab tab : Tab.values())
		{
			JButton btn = flatButton(tab.label, SURFACE_ELEVATED);
			btn.addActionListener(e ->
			{
				activeTab = tab;
				configManager.setConfiguration(CombatBreakdownConfig.GROUP, KEY_ACTIVE_TAB, tab.name());
				refresh();
			});
			tabButtons.put(tab, btn);
			tabs.add(btn);
		}
		top.add(tabs);

		toastLabel.setForeground(BLUE);
		toastLabel.setFont(toastLabel.getFont().deriveFont(Font.BOLD, 10f));
		toastLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		toastLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
		toastLabel.setVisible(false);
		top.add(toastLabel);

		return top;
	}

	public void refresh()
	{
		SessionSnapshot session = plugin.getLatestSession();
		BurnSnapshot burn = plugin.getLatestBurn();

		if (session == null || !session.isSessionStarted())
		{
			statusBadge.setText("IDLE");
			statusBadge.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			pauseBtn.setText("Pause");
			pauseBtn.setBackground(SURFACE_ELEVATED);
		}
		else if (session.isPaused())
		{
			statusBadge.setText("PAUSED");
			statusBadge.setForeground(RED);
			pauseBtn.setText("Resume");
			pauseBtn.setBackground(PILL_ACTIVE);
		}
		else if (session.isInActiveCombat())
		{
			statusBadge.setText("IN COMBAT");
			statusBadge.setForeground(GREEN);
			pauseBtn.setText("Pause");
			pauseBtn.setBackground(SURFACE_ELEVATED);
		}
		else
		{
			statusBadge.setText("OUT OF COMBAT");
			statusBadge.setForeground(GOLD);
			pauseBtn.setText("Pause");
			pauseBtn.setBackground(SURFACE_ELEVATED);
		}

		for (Map.Entry<Tab, JButton> entry : tabButtons.entrySet())
		{
			boolean selected = entry.getKey() == activeTab;
			entry.getValue().setBackground(selected ? PILL_ACTIVE : SURFACE_ELEVATED);
			entry.getValue().setForeground(selected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		}

		bodyPanel.removeAll();
		switch (activeTab)
		{
			case LIVE:
				buildLiveTab(session);
				break;
			case SUPPLIES:
				buildSuppliesTab(session, burn);
				break;
			case HISTORY:
				buildHistoryTab(session);
				break;
			default:
				break;
		}
		bodyPanel.revalidate();
		bodyPanel.repaint();
	}

	private void buildLiveTab(SessionSnapshot session)
	{
		if (session == null || !session.isSessionStarted())
		{
			JPanel empty = cardPanel(BORDER_SUBTLE);
			empty.add(sectionTitle("Ready to Track Combat", Color.WHITE));
			empty.add(Box.createVerticalStrut(4));
			empty.add(wrappingText(
				"Attack any NPC to start tracking live Active DPS, continuous Session DPS, hit distribution, per-weapon comparisons, damage taken, and combat supply burn.",
				ColorScheme.LIGHT_GRAY_COLOR,
				11f
			));
			bodyPanel.add(empty);
			return;
		}

		// 1. Dual DPS + Core Stats Card
		JPanel dpsCard = cardPanel(session.isInActiveCombat() ? new Color(40, 126, 82) : BORDER_SUBTLE);
		if (!session.getActiveTargetName().isEmpty())
		{
			String targetLine = session.getActiveTargetName();
			if (session.getActiveTargetCombatLevel() > 0)
			{
				targetLine += " (lvl " + session.getActiveTargetCombatLevel() + ")";
			}
			dpsCard.add(sectionTitle(targetLine, Color.WHITE));
			dpsCard.add(Box.createVerticalStrut(6));
		}

		JPanel dpsGrid = new JPanel(new GridLayout(1, 2, 6, 0));
		dpsGrid.setOpaque(false);
		dpsGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
		dpsGrid.add(metricTile(
			"Active DPS",
			String.format(Locale.US, "%.2f", session.getActiveDps()),
			CombatSessionTracker.formatDuration(session.getActiveSeconds()),
			GREEN
		));
		dpsGrid.add(metricTile(
			"Session DPS",
			String.format(Locale.US, "%.2f", session.getSessionDps()),
			CombatSessionTracker.formatDuration(session.getSessionSeconds()),
			BLUE
		));
		dpsCard.add(dpsGrid);
		dpsCard.add(Box.createVerticalStrut(6));

		addKeyValueRow(dpsCard, "Total Damage", String.format(Locale.US, "%,d", session.getTotalDamage()), Color.WHITE);
		addKeyValueRow(dpsCard, "Accuracy",
			String.format(Locale.US, "%.1f%% (%d/%d)", session.getAccuracyPercent(), session.getTotalHitsLanded(), session.getTotalSwings()),
			Color.WHITE);
		addKeyValueRow(dpsCard, "Avg Hit (Landed)", String.format(Locale.US, "%.1f", session.getAverageLandedHit()), Color.WHITE);
		String maxText = session.getNativeMaxSplats() > 0
			? (session.getMaxHit() + " (" + session.getNativeMaxSplats() + " max)")
			: Integer.toString(session.getMaxHit());
		addKeyValueRow(dpsCard, "Max Hit", maxText, GOLD);
		String killText = session.getSessionKills() > 0
			? String.format(Locale.US, "%d (%.1f/hr)", session.getSessionKills(), session.getKillsPerHour())
			: "0";
		addKeyValueRow(dpsCard, "Kills", killText, GREEN);

		bodyPanel.add(dpsCard);
		bodyPanel.add(Box.createVerticalStrut(6));

		// 2. Hit Distribution Card
		List<HitDistribution.Bucket> buckets = session.getHitBuckets();
		if (!buckets.isEmpty())
		{
			JPanel distCard = cardPanel(BORDER_SUBTLE);
			distCard.add(sectionTitle("Hit Distribution", ColorScheme.LIGHT_GRAY_COLOR));
			distCard.add(Box.createVerticalStrut(5));

			for (HitDistribution.Bucket bucket : buckets)
			{
				Color barColor = colorForBucket(bucket.getLabel());
				JPanel row = new JPanel(new BorderLayout(4, 0));
				row.setOpaque(false);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);

				JLabel lbl = new JLabel(bucket.getLabel() + " (" + bucket.getRangeLabel() + ")");
				lbl.setForeground(Color.WHITE);
				lbl.setFont(lbl.getFont().deriveFont(10.5f));
				row.add(lbl, BorderLayout.WEST);

				JLabel val = new JLabel(String.format(Locale.US, "%d (%.0f%%)", bucket.getCount(), bucket.getPercent()));
				val.setForeground(barColor);
				val.setFont(val.getFont().deriveFont(Font.BOLD, 10.5f));
				row.add(val, BorderLayout.EAST);

				distCard.add(row);
				distCard.add(Box.createVerticalStrut(2));
				distCard.add(new PercentBar(bucket.getPercent() / 100.0, barColor));
				distCard.add(Box.createVerticalStrut(4));
			}
			bodyPanel.add(distCard);
			bodyPanel.add(Box.createVerticalStrut(6));
		}

		// 3. Defense, Healing & Status Damage Card
		StatusDamageBreakdown.Snapshot st = session.getStatusAndDefense();
		JPanel defCard = cardPanel(BORDER_SUBTLE);
		defCard.add(sectionTitle("Defense, Healing & Status", ColorScheme.LIGHT_GRAY_COLOR));
		defCard.add(Box.createVerticalStrut(4));
		addKeyValueRow(defCard, "Damage Taken", String.format(Locale.US, "%,d", st.getTotalDamageTaken()),
			st.getTotalDamageTaken() > 0 ? RED : ColorScheme.LIGHT_GRAY_COLOR);
		if (st.getStatusDamageTaken() > 0)
		{
			addKeyValueRow(defCard, "  Status Taken", String.format(Locale.US, "%,d", st.getStatusDamageTaken()), PURPLE);
		}
		if (st.getIncomingHits() + st.getIncomingBlocks() > 0)
		{
			addKeyValueRow(defCard, "Blocked / Evaded",
				String.format(Locale.US, "%.1f%% (%d/%d)", st.getEvadePercent(), st.getIncomingBlocks(),
					st.getIncomingHits() + st.getIncomingBlocks()),
				BLUE);
		}
		addKeyValueRow(defCard, "Healing Received", String.format(Locale.US, "+%,d", st.getHealingReceived()), GREEN);
		if (st.getPrayerDrained() > 0)
		{
			addKeyValueRow(defCard, "Prayer Drained", String.format(Locale.US, "-%,d", st.getPrayerDrained()), BLUE);
		}
		if (st.getTotalStatusDealt() > 0)
		{
			defCard.add(Box.createVerticalStrut(4));
			addKeyValueRow(defCard, "Status Dmg Dealt", String.format(Locale.US, "%,d", st.getTotalStatusDealt()), PURPLE);
			if (st.getPoisonDealt() > 0)
			{
				addKeyValueRow(defCard, "  Poison", Long.toString(st.getPoisonDealt()), GREEN);
			}
			if (st.getVenomDealt() > 0)
			{
				addKeyValueRow(defCard, "  Venom", Long.toString(st.getVenomDealt()), GREEN);
			}
			if (st.getBurnDealt() > 0)
			{
				addKeyValueRow(defCard, "  Burn", Long.toString(st.getBurnDealt()), GOLD);
			}
			if (st.getBleedDealt() > 0)
			{
				addKeyValueRow(defCard, "  Bleed", Long.toString(st.getBleedDealt()), RED);
			}
			if (st.getCorruptionDealt() + st.getDoomDealt() > 0)
			{
				addKeyValueRow(defCard, "  Corruption / Doom", Long.toString(st.getCorruptionDealt() + st.getDoomDealt()), PURPLE);
			}
		}
		bodyPanel.add(defCard);

		// 4. Per-Weapon Comparison Cards
		if (!session.getWeapons().isEmpty())
		{
			bodyPanel.add(Box.createVerticalStrut(6));
			JPanel wepCard = cardPanel(BORDER_SUBTLE);
			wepCard.add(sectionTitle("Weapon Breakdown", ColorScheme.LIGHT_GRAY_COLOR));
			wepCard.add(Box.createVerticalStrut(5));

			for (WeaponStats.Snapshot wep : session.getWeapons())
			{
				wepCard.add(buildWeaponRow(wep));
				wepCard.add(Box.createVerticalStrut(4));
			}
			bodyPanel.add(wepCard);
		}
	}

	private JPanel buildWeaponRow(WeaponStats.Snapshot wep)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(SURFACE_ELEVATED);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER_SUBTLE),
			new EmptyBorder(5, 6, 5, 6)
		));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridy = 0;
		gbc.insets = new Insets(0, 0, 0, 6);
		gbc.anchor = GridBagConstraints.WEST;

		JLabel iconLbl = new JLabel();
		iconLbl.setPreferredSize(new Dimension(26, 22));
		iconLbl.setHorizontalAlignment(SwingConstants.CENTER);
		if (wep.getWeaponItemId() > 0)
		{
			AsyncBufferedImage img = itemManager.getImage(wep.getWeaponItemId());
			img.addTo(iconLbl);
		}
		gbc.gridx = 0;
		gbc.weightx = 0;
		row.add(iconLbl, gbc);

		JPanel mid = new JPanel();
		mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
		mid.setOpaque(false);

		JTextArea nameArea = wrappingText(wep.getWeaponName(), Color.WHITE, 11f);
		nameArea.setFont(nameArea.getFont().deriveFont(Font.BOLD, 11f));
		mid.add(nameArea);

		String sub = String.format(Locale.US, "Acc %.1f%% | Avg %.1f | Max %d | %d sw",
			wep.getAccuracyPercent(), wep.getAverageLandedHit(), wep.getMaxHit(), wep.getTotalSwings());
		mid.add(wrappingText(sub, ColorScheme.LIGHT_GRAY_COLOR, 9.8f));

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		row.add(mid, gbc);

		JLabel dpsLbl = new JLabel(String.format(Locale.US, "%.2f", wep.getActiveDps()));
		dpsLbl.setForeground(GREEN);
		dpsLbl.setFont(dpsLbl.getFont().deriveFont(Font.BOLD, 11.5f));
		gbc.gridx = 2;
		gbc.weightx = 0;
		gbc.fill = GridBagConstraints.NONE;
		gbc.insets = new Insets(0, 4, 0, 0);
		gbc.anchor = GridBagConstraints.EAST;
		row.add(dpsLbl, gbc);

		return row;
	}

	private void buildSuppliesTab(SessionSnapshot session, BurnSnapshot burn)
	{
		boolean showGp = plugin.shouldDisplayGpValues();
		double sessionSec = session != null ? session.getSessionSeconds() : 0.0;
		int kills = session != null ? session.getSessionKills() : 0;

		JPanel summary = cardPanel(BORDER_SUBTLE);
		summary.add(sectionTitle("Supply Burn Summary", Color.WHITE));
		summary.add(Box.createVerticalStrut(5));

		if (burn == null || burn.isEmpty())
		{
			summary.add(wrappingText(
				"No combat supplies consumed yet. Food, potion doses, ammo, cannonballs, and runes used during combat will appear here. Banking, GE, trades, and dropped items are automatically ignored.",
				ColorScheme.LIGHT_GRAY_COLOR,
				10.8f
			));
			bodyPanel.add(summary);
			return;
		}

		if (showGp)
		{
			JPanel gpGrid = new JPanel(new GridLayout(1, 2, 6, 0));
			gpGrid.setOpaque(false);
			gpGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
			gpGrid.add(metricTile("Total Burn", CombatHudOverlay.formatCompactGp(burn.getTotalCostGp()),
				kills > 0 ? (CombatHudOverlay.formatCompactGp(burn.getGpPerKill()) + "/kill") : "Session", GOLD));
			gpGrid.add(metricTile("Burn Rate", CombatHudOverlay.formatCompactGp(burn.getGpPerHour()), "per hour", RED));
			summary.add(gpGrid);
			summary.add(Box.createVerticalStrut(6));
		}
		else if (plugin.isIronmanAccount())
		{
			summary.add(wrappingText("Ironman mode: showing exact supply counts (GE GP hidden in config).", BLUE, 10f));
			summary.add(Box.createVerticalStrut(4));
		}

		addKeyValueRow(summary, "Food Eaten", Long.toString(burn.getFoodEaten()), Color.WHITE);
		addKeyValueRow(summary, "Potion Doses", Long.toString(burn.getPotionDoses()), BLUE);
		addKeyValueRow(summary, "Ammo / Cannonballs", Long.toString(burn.getAmmoUsed()), GOLD);
		addKeyValueRow(summary, "Runes Cast", Long.toString(burn.getRunesUsed()), PURPLE);
		bodyPanel.add(summary);
		bodyPanel.add(Box.createVerticalStrut(6));

		JPanel listCard = cardPanel(BORDER_SUBTLE);
		listCard.add(sectionTitle("Consumed Supplies", ColorScheme.LIGHT_GRAY_COLOR));
		listCard.add(Box.createVerticalStrut(5));

		for (SupplyUsage.Snapshot item : burn.getItems())
		{
			listCard.add(buildSupplyRow(item, sessionSec, kills, showGp));
			listCard.add(Box.createVerticalStrut(4));
		}
		bodyPanel.add(listCard);
	}

	private JPanel buildSupplyRow(SupplyUsage.Snapshot item, double sessionSec, int kills, boolean showGp)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(SURFACE_ELEVATED);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER_SUBTLE),
			new EmptyBorder(5, 6, 5, 6)
		));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridy = 0;
		gbc.insets = new Insets(0, 0, 0, 6);
		gbc.anchor = GridBagConstraints.WEST;

		JLabel iconLbl = new JLabel();
		iconLbl.setPreferredSize(new Dimension(26, 22));
		iconLbl.setHorizontalAlignment(SwingConstants.CENTER);
		if (item.getIconItemId() > 0)
		{
			AsyncBufferedImage img = itemManager.getImage(item.getIconItemId());
			img.addTo(iconLbl);
		}
		gbc.gridx = 0;
		gbc.weightx = 0;
		row.add(iconLbl, gbc);

		JPanel mid = new JPanel();
		mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
		mid.setOpaque(false);

		JTextArea nameArea = wrappingText(item.getDisplayName(), Color.WHITE, 11f);
		nameArea.setFont(nameArea.getFont().deriveFont(Font.BOLD, 11f));
		mid.add(nameArea);

		StringBuilder meta = new StringBuilder();
		meta.append(item.getUnitsConsumed()).append(' ').append(item.getCategory().getUnitLabel());
		if (sessionSec >= 10.0)
		{
			meta.append(String.format(Locale.US, " (%.0f/hr)", item.getUnitsPerHour(sessionSec)));
		}
		if (kills > 0)
		{
			meta.append(String.format(Locale.US, " | %.1f/kc", item.getUnitsPerKill(kills)));
		}
		mid.add(wrappingText(meta.toString(), ColorScheme.LIGHT_GRAY_COLOR, 9.8f));

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		row.add(mid, gbc);

		String rightText = showGp && item.getTotalCostGp() > 0
			? CombatHudOverlay.formatCompactGp(item.getTotalCostGp())
			: ("x" + item.getUnitsConsumed());
		JLabel rightLbl = new JLabel(rightText);
		rightLbl.setForeground(showGp ? GOLD : BLUE);
		rightLbl.setFont(rightLbl.getFont().deriveFont(Font.BOLD, 10.5f));
		gbc.gridx = 2;
		gbc.weightx = 0;
		gbc.fill = GridBagConstraints.NONE;
		gbc.insets = new Insets(0, 4, 0, 0);
		gbc.anchor = GridBagConstraints.EAST;
		row.add(rightLbl, gbc);

		return row;
	}

	private void buildHistoryTab(SessionSnapshot session)
	{
		boolean showGp = plugin.shouldDisplayGpValues();
		List<TargetSummary> targets = session != null ? session.getTargetSummaries() : java.util.Collections.emptyList();
		List<FightRecord> recent = session != null ? session.getRecentFights() : java.util.Collections.emptyList();

		JPanel tools = new JPanel(new GridLayout(1, 2, 4, 0));
		tools.setOpaque(false);
		tools.setAlignmentX(Component.LEFT_ALIGNMENT);
		tools.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

		JButton csvBtn = flatButton("Copy CSV", SURFACE_ELEVATED);
		csvBtn.addActionListener(e -> copyToClipboard(plugin.buildHistoryCsv(), "Copied target history CSV"));

		JButton clearBtn = flatButton("Clear History", new Color(138, 52, 56));
		clearBtn.addActionListener(e ->
		{
			int confirm = JOptionPane.showConfirmDialog(
				this,
				"Clear all saved per-target combat history?",
				"Clear Combat History",
				JOptionPane.YES_NO_OPTION
			);
			if (confirm == JOptionPane.YES_OPTION)
			{
				plugin.clearHistory();
				showToast("Combat history cleared");
			}
		});
		tools.add(csvBtn);
		tools.add(clearBtn);
		bodyPanel.add(tools);
		bodyPanel.add(Box.createVerticalStrut(6));

		if (targets.isEmpty())
		{
			JPanel empty = cardPanel(BORDER_SUBTLE);
			empty.add(sectionTitle("No Completed Kills Yet", Color.WHITE));
			empty.add(Box.createVerticalStrut(4));
			empty.add(wrappingText(
				"Finish a kill to record per-NPC kill count, average and fastest kill time, DPS, accuracy, damage taken, and supply cost per kill.",
				ColorScheme.LIGHT_GRAY_COLOR,
				10.8f
			));
			bodyPanel.add(empty);
			return;
		}

		for (TargetSummary target : targets)
		{
			JPanel card = cardPanel(BORDER_SUBTLE);
			String title = target.getNpcName() + (target.getCombatLevel() > 0 ? (" (lvl " + target.getCombatLevel() + ")") : "");
			JPanel topRow = new JPanel(new BorderLayout(4, 0));
			topRow.setOpaque(false);
			topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel nameLbl = new JLabel(title);
			nameLbl.setForeground(Color.WHITE);
			nameLbl.setFont(nameLbl.getFont().deriveFont(Font.BOLD, 11.5f));
			JLabel kcLbl = new JLabel(target.getKills() + " kc");
			kcLbl.setForeground(GREEN);
			kcLbl.setFont(kcLbl.getFont().deriveFont(Font.BOLD, 11f));
			topRow.add(nameLbl, BorderLayout.CENTER);
			topRow.add(kcLbl, BorderLayout.EAST);
			card.add(topRow);
			card.add(Box.createVerticalStrut(4));

			addKeyValueRow(card, "Avg / Fastest TTK",
				String.format(Locale.US, "%.1fs / %.1fs", target.getAverageKillSeconds(), target.getFastestKillSeconds()),
				BLUE);
			addKeyValueRow(card, "Avg DPS | Acc",
				String.format(Locale.US, "%.2f | %.1f%%", target.getAverageDps(), target.getAccuracyPercent()),
				GREEN);
			addKeyValueRow(card, "Max Hit | Taken",
				String.format(Locale.US, "%d | %,d", target.getMaxHit(), target.getTotalDamageTaken()),
				GOLD);
			if (showGp && target.getTotalSupplyCostGp() > 0)
			{
				addKeyValueRow(card, "Cost / Kill", CombatHudOverlay.formatCompactGp(target.getSupplyCostPerKillGp()), RED);
			}
			bodyPanel.add(card);
			bodyPanel.add(Box.createVerticalStrut(5));
		}

		if (!recent.isEmpty())
		{
			JPanel recentCard = cardPanel(BORDER_SUBTLE);
			recentCard.add(sectionTitle("Recent Kills (Session)", ColorScheme.LIGHT_GRAY_COLOR));
			recentCard.add(Box.createVerticalStrut(4));
			int limit = Math.min(12, recent.size());
			for (int i = 0; i < limit; i++)
			{
				FightRecord f = recent.get(i);
				String left = f.getNpcName() + " (" + String.format(Locale.US, "%.1fs", f.getDurationSeconds()) + ")";
				String right = String.format(Locale.US, "%.1f DPS | %d max", f.getDps(), f.getMaxHit());
				addKeyValueRow(recentCard, left, right, Color.WHITE);
			}
			bodyPanel.add(recentCard);
		}
	}

	private void copyToClipboard(String text, String toast)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		showToast(toast);
	}

	private void showToast(String message)
	{
		toastLabel.setText(message);
		toastLabel.setVisible(true);
		toastClearTimer.restart();
	}

	private void loadPersistedTab()
	{
		String saved = configManager.getConfiguration(CombatBreakdownConfig.GROUP, KEY_ACTIVE_TAB);
		if (saved != null && !saved.isEmpty())
		{
			try
			{
				activeTab = Tab.valueOf(saved);
			}
			catch (IllegalArgumentException ignored)
			{
				activeTab = Tab.LIVE;
			}
		}
	}

	private static Color colorForBucket(String label)
	{
		if ("Miss".equals(label))
		{
			return RED;
		}
		if ("Low".equals(label))
		{
			return new Color(235, 138, 62);
		}
		if ("Mid".equals(label) || "Hit".equals(label))
		{
			return GOLD;
		}
		if ("High".equals(label))
		{
			return BLUE;
		}
		return GREEN;
	}

	private static JPanel cardPanel(Color borderColor)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(SURFACE_CARD);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(borderColor),
			new EmptyBorder(7, 8, 7, 8)
		));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		return card;
	}

	private static JPanel metricTile(String label, String value, String sub, Color accent)
	{
		JPanel tile = new JPanel();
		tile.setLayout(new BoxLayout(tile, BoxLayout.Y_AXIS));
		tile.setBackground(SURFACE_ELEVATED);
		tile.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER_SUBTLE),
			new EmptyBorder(5, 7, 5, 7)
		));

		JLabel l = new JLabel(label);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setFont(l.getFont().deriveFont(10f));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel v = new JLabel(value);
		v.setForeground(accent);
		v.setFont(v.getFont().deriveFont(Font.BOLD, 15f));
		v.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel s = new JLabel(sub);
		s.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		s.setFont(s.getFont().deriveFont(9.8f));
		s.setAlignmentX(Component.LEFT_ALIGNMENT);

		tile.add(l);
		tile.add(v);
		tile.add(s);
		return tile;
	}

	private static JLabel sectionTitle(String text, Color color)
	{
		JLabel lbl = new JLabel(text);
		lbl.setForeground(color);
		lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 11.5f));
		lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
		return lbl;
	}

	private static void addKeyValueRow(JPanel parent, String key, String value, Color valueColor)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel k = new JLabel(key);
		k.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		k.setFont(k.getFont().deriveFont(10.8f));
		row.add(k, BorderLayout.WEST);

		JLabel v = new JLabel(value);
		v.setForeground(valueColor);
		v.setFont(v.getFont().deriveFont(Font.BOLD, 10.8f));
		row.add(v, BorderLayout.EAST);

		parent.add(row);
		parent.add(Box.createVerticalStrut(2));
	}

	private static JTextArea wrappingText(String text, Color color, float fontSize)
	{
		JTextArea area = new JTextArea(text);
		area.setWrapStyleWord(true);
		area.setLineWrap(true);
		area.setOpaque(false);
		area.setEditable(false);
		area.setFocusable(false);
		area.setForeground(color);
		area.setFont(area.getFont().deriveFont(fontSize));
		area.setBorder(BorderFactory.createEmptyBorder());
		area.setAlignmentX(Component.LEFT_ALIGNMENT);
		return area;
	}

	private static JButton flatButton(String text, Color bg)
	{
		JButton btn = new JButton(text);
		btn.setFocusPainted(false);
		btn.setBackground(bg);
		btn.setForeground(Color.WHITE);
		btn.setBorder(new EmptyBorder(4, 6, 4, 6));
		btn.setFont(btn.getFont().deriveFont(Font.BOLD, 10.5f));
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return btn;
	}

	private static final class PercentBar extends JPanel
	{
		private final double ratio;
		private final Color fillColor;

		PercentBar(double ratio, Color fillColor)
		{
			this.ratio = Math.max(0.0, Math.min(1.0, ratio));
			this.fillColor = fillColor;
			setOpaque(false);
			setPreferredSize(new Dimension(100, 5));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 5));
			setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g;
			int w = getWidth();
			int h = getHeight();
			g2.setColor(BAR_TRACK);
			g2.fillRoundRect(0, 0, w, h, 3, 3);
			int fillW = (int) Math.round(w * ratio);
			if (fillW > 0)
			{
				g2.setColor(fillColor);
				g2.fillRoundRect(0, 0, fillW, h, 3, 3);
			}
		}
	}

	private static final class ViewportWidthPanel extends JPanel implements Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return Math.max(visibleRect.height - 16, 16);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
