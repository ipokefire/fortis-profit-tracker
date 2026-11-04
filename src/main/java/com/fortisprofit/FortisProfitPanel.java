package com.fortisprofit;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Component;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.net.URL;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ImageIcon;
import javax.swing.Icon;
import javax.swing.table.TableCellRenderer;
import javax.swing.SwingUtilities;
import net.runelite.client.util.ImageUtil;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.DefaultTableModel;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.Filepath;

/** Small-screen sidebar: Today, day-by-day History, and cumulative All-Time totals. */
final class FortisProfitPanel extends PluginPanel
{
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("MMM d, uuuu");
    private static final ZoneId LOCAL_ZONE = ZoneId.systemDefault();

    private final FortisProfitPlugin plugin;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel todaySummary = new JLabel();
    private final JLabel todayClearCount = new JLabel("0");
    private final JLabel todayCashCount = new JLabel("0");
    private final JLabel todayDeathCount = new JLabel("0");
    private final JLabel historyClearCount = new JLabel("0");
    private final JLabel historyCashCount = new JLabel("0");
    private final JLabel historyDeathCount = new JLabel("0");
    private final JLabel todaySolIcon = new JLabel("Sol");
    private final JLabel historySolIcon = new JLabel("Sol");
    private final JLabel todayHeading = new JLabel();
    private final JLabel historyHeading = new JLabel("Select a day above to inspect its ledger");
    private final JLabel historySummary = new JLabel();
    private final JLabel allTimeSummary = new JLabel();
    private final JLabel allTimePeriod = new JLabel();
    private final JLabel currentRunHeading = new JLabel("No Colosseum run in progress");
    private final JLabel currentRunTotal = new JLabel("Supplies: 0 GP");
    private final JPanel currentSupplyGrid = new JPanel(new GridLayout(0, 4, 4, 6));
    private final List<LedgerEvent> todayVisible = new ArrayList<>();
    private final List<LedgerEvent> historyVisible = new ArrayList<>();
    private final List<LocalDate> historyDates = new ArrayList<>();

    private final DefaultTableModel todayModel = ledgerModel();
    private final DefaultTableModel historyDetailModel = ledgerModel();
    private final DefaultTableModel historyDaysModel = new DefaultTableModel(
        new Object[]{"Date", "Sol", "GP", "Dead", "Net"},0)
    {
        @Override public boolean isCellEditable(int row,int column) { return false; }
    };

    private final JTable todayTable = ledgerTable(todayModel, todayVisible);
    private final JTable historyDetailTable = ledgerTable(historyDetailModel, historyVisible);
    private final JTable historyDaysTable = new JTable(historyDaysModel);
    // Real Swing icons rather than emoji or Icon objects rendered as text.
    private Icon solHeaderIcon;
    private final Icon coinHeaderIcon = loadGrandExchangeIcon();
    private final Icon skullHeaderIcon = new ImageIcon(skullIcon());
    private final Timer midnightRefresh;
    private boolean refreshing;
    private LocalDate selectedHistoryDate;
    private String accountShown;

    FortisProfitPanel(FortisProfitPlugin plugin)
    {
        this.plugin = plugin;
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        setLayout(new BorderLayout());
        // JTableHeader's default renderer stringifies an ImageIcon. Render a JLabel
        // containing a Swing Icon instead, and use short text if Sol is unavailable.
        final TableCellRenderer defaultHeader = historyDaysTable.getTableHeader().getDefaultRenderer();
        historyDaysTable.getTableHeader().setDefaultRenderer((table, value, selected, focus, row, col) -> {
            Component result = defaultHeader.getTableCellRendererComponent(table, value, selected, focus, row, col);
            if (result instanceof JLabel)
            {
                JLabel label = (JLabel) result;
                label.setHorizontalAlignment(JLabel.CENTER);
                label.setIcon(null);
                Icon icon = col == 1 ? solHeaderIcon : col == 2 ? coinHeaderIcon : col == 3 ? skullHeaderIcon : null;
                if (icon != null)
                {
                    label.setText("");
                    label.setIcon(icon);
                }
                else if (col == 1)
                {
                    label.setText("Sol");
                }
            }
            return result;
        });
        plugin.loadSolHereditIcon(image -> {
            if (image == null) return;
            SwingUtilities.invokeLater(() -> {
                solHeaderIcon = new ImageIcon(ImageUtil.resizeImage(image, 18, 18));
                todaySolIcon.setText("");
                historySolIcon.setText("");
                todaySolIcon.setIcon(solHeaderIcon);
                historySolIcon.setIcon(solHeaderIcon);
                historyDaysTable.getTableHeader().repaint();
            });
        });
        tabs.addTab("Today", scrollableTab(makeToday()));
        tabs.addTab("History", scrollableTab(makeHistory()));
        tabs.addTab("All-Time", scrollableTab(makeAllTime()));
        tabs.addTab("Current Run", scrollableTab(makeCurrentRun()));
        tabs.addChangeListener(e -> refresh());
        add(tabs, BorderLayout.CENTER);
        // Date rollover and login/account changes must update the display even without a ledger event.
        midnightRefresh = new Timer(60_000, e -> refresh());
        midnightRefresh.start();
        refresh();
    }

    void stopUpdates() { midnightRefresh.stop(); }

    /** Allow the full sidebar (including the actions) to scroll in short RuneLite windows. */
    private JScrollPane scrollableTab(JPanel content)
    {
        JScrollPane pane = new JScrollPane(content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setBorder(null);
        pane.getVerticalScrollBar().setUnitIncrement(18);
        return pane;
    }


    /** A bundled pixel-art copy of the GE hand-and-coin icon supplied for the UI. */
    private static Icon loadGrandExchangeIcon()
    {
        URL resource = FortisProfitPanel.class.getResource("/com/fortisprofit/grand_exchange.png");
        if (resource == null) return null;
        try
        {
            return new ImageIcon(ImageUtil.resizeImage(ImageIO.read(resource), 18, 18));
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** Keep the three attempt counters in distinct, spaced icon/count pairs. */
    private static JPanel counterStrip(JLabel solIcon, JLabel clears,
        JLabel cashOuts, JLabel deaths)
    {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 11, 1));
        strip.add(counterPair(solIcon, clears, "Sol Heredit clears"));
        JLabel cashIcon = new JLabel();
        if (loadGrandExchangeIcon() != null) cashIcon.setIcon(loadGrandExchangeIcon());
        else cashIcon.setText("GP");
        strip.add(counterPair(cashIcon, cashOuts, "Early cash-outs"));
        strip.add(counterPair(new JLabel(new ImageIcon(skullIcon())), deaths, "Deaths"));
        return strip;
    }

    private static JPanel counterPair(JLabel icon, JLabel value, String tooltip)
    {
        JPanel pair = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        icon.setToolTipText(tooltip);
        value.setToolTipText(tooltip);
        pair.add(icon);
        pair.add(value);
        return pair;
    }

    /** A small stable skull image; avoids platform-dependent emoji font rendering. */
    private static java.awt.image.BufferedImage skullIcon()
    {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(18, 18,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        try
        {
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(220, 220, 210));
            g.fillOval(3, 1, 12, 12);
            g.fillRoundRect(6, 10, 6, 6, 2, 2);
            g.setColor(new Color(40, 40, 40));
            g.fillOval(5, 6, 3, 3);
            g.fillOval(10, 6, 3, 3);
            g.fillOval(8, 10, 2, 2);
            g.drawLine(8, 13, 8, 15);
            g.drawLine(10, 13, 10, 15);
        }
        finally { g.dispose(); }
        return image;
    }

    private JPanel makeToday()
    {
        JPanel content = new JPanel(new BorderLayout(0, 6));
        JPanel summary = new JPanel(new BorderLayout(0, 3));
        todayHeading.setText("Today");
        summary.add(todayHeading, BorderLayout.NORTH);
        JPanel figures = new JPanel(new BorderLayout(0, 2));
        figures.add(counterStrip(todaySolIcon, todayClearCount, todayCashCount, todayDeathCount), BorderLayout.NORTH);
        figures.add(todaySummary, BorderLayout.CENTER);
        summary.add(figures, BorderLayout.CENTER);
        content.add(summary, BorderLayout.NORTH);
        JScrollPane todayLedgerScroller = new JScrollPane(todayTable);
        todayLedgerScroller.setPreferredSize(new Dimension(220, 280));
        todayLedgerScroller.getVerticalScrollBar().setUnitIncrement(18);
        content.add(todayLedgerScroller, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(0, 2, 3, 3));
        button(actions, "Manual entry", this::addEntry);
        button(actions, "Quiver turn-in", this::quiverTurnIn);
        button(actions, "Edit entry", () -> editEntry(todayTable, todayVisible));
        button(actions, "Delete entry", () -> deleteEntry(todayTable, todayVisible));
        button(actions, "Export CSV", this::exportCsv);
        content.add(actions, BorderLayout.SOUTH);
        content.setPreferredSize(new Dimension(230, 640));
        return content;
    }

    private JPanel makeHistory()
    {
        JPanel content = new JPanel(new BorderLayout(0, 4));
        JPanel top = new JPanel(new BorderLayout(0, 3));
        top.add(new JLabel("History by day (your local time)"), BorderLayout.NORTH);
        historyDaysTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyDaysTable.setAutoCreateRowSorter(false);
        historyDaysTable.setRowHeight(23);
        historyDaysTable.getColumnModel().getColumn(0).setPreferredWidth(88);
        historyDaysTable.getColumnModel().getColumn(1).setPreferredWidth(26);
        historyDaysTable.getColumnModel().getColumn(2).setPreferredWidth(34);
        historyDaysTable.getColumnModel().getColumn(3).setPreferredWidth(26);
        historyDaysTable.getTableHeader().setToolTipText("Sol Heredit = Full clears, GP = Early cash-outs, Skull = Deaths");
        historyDaysTable.getColumnModel().getColumn(4).setPreferredWidth(75);
        historyDaysTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || refreshing) return;
            int selected = historyDaysTable.getSelectedRow();
            selectedHistoryDate = selected>=0 && selected<historyDates.size() ? historyDates.get(selected) : null;
            showHistoryDetails();
        });
        JScrollPane daysScroller = new JScrollPane(historyDaysTable);
        daysScroller.setPreferredSize(new Dimension(220, 184));
        top.add(daysScroller, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 3));
        JPanel detailsHeading = new JPanel(new BorderLayout(0, 2));
        detailsHeading.add(historyHeading, BorderLayout.NORTH);
        JPanel figures = new JPanel(new BorderLayout(0, 2));
        figures.add(counterStrip(historySolIcon, historyClearCount, historyCashCount, historyDeathCount), BorderLayout.NORTH);
        figures.add(historySummary, BorderLayout.CENTER);
        detailsHeading.add(figures, BorderLayout.CENTER);
        bottom.add(detailsHeading, BorderLayout.NORTH);
        bottom.add(new JScrollPane(historyDetailTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.32);
        split.setBorder(null);
        split.setPreferredSize(new Dimension(230, 420));
        content.add(split, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(0, 2, 3, 3));
        button(actions, "Edit entry", () -> editEntry(historyDetailTable, historyVisible));
        button(actions, "Delete entry", () -> deleteEntry(historyDetailTable, historyVisible));
        button(actions, "Export CSV", this::exportCsv);
        button(actions, "Refresh", this::refresh);
        content.add(actions, BorderLayout.SOUTH);
        content.setPreferredSize(new Dimension(230, 620));
        return content;
    }

    private JPanel makeAllTime()
    {
        JPanel content = new JPanel(new BorderLayout(0, 8));
        JPanel summary = new JPanel(new BorderLayout(0, 6));
        summary.add(new JLabel("All-Time Fortis Colosseum results"), BorderLayout.NORTH);
        summary.add(allTimeSummary, BorderLayout.CENTER);
        summary.add(allTimePeriod, BorderLayout.SOUTH);
        content.add(summary, BorderLayout.NORTH);

        JPanel actions = new JPanel(new GridLayout(0, 2, 3, 3));
        button(actions, "Refresh", this::refresh);
        button(actions, "Export CSV", this::exportCsv);
        content.add(actions, BorderLayout.SOUTH);
        content.setPreferredSize(new Dimension(230, 270));
        return content;
    }

    /** RuneLite item sprites and a compact grid, similar to Supplies Tracker. */
    private JPanel makeCurrentRun()
    {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 4));
        header.add(new JLabel("Current run supplies"));
        header.add(currentRunHeading);
        header.add(currentRunTotal);
        root.add(header, BorderLayout.NORTH);
        currentSupplyGrid.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
        JPanel container = new JPanel(new BorderLayout());
        container.add(currentSupplyGrid, BorderLayout.NORTH);
        root.add(container, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(230, 490));
        return root;
    }

    private void refreshCurrentRun()
    {
        boolean tracking = plugin.trackingSupplies();
        currentRunHeading.setText(tracking ? "Tracking: lobby / arena" : "No Colosseum run in progress");
        currentRunTotal.setText("Supplies: -" + GpFormat.shortGp(plugin.currentSupplyCost()) + " GP");
        currentSupplyGrid.removeAll();
        for (WaveSupplyTracker.ItemUsage item : plugin.currentSupplyItems())
        {
            JPanel tile = new JPanel();
            tile.setLayout(new BoxLayout(tile, BoxLayout.Y_AXIS));
            tile.setBorder(BorderFactory.createLineBorder(new Color(67, 67, 67)));
            JLabel icon = new JLabel();
            icon.setAlignmentX(Component.CENTER_ALIGNMENT);
            net.runelite.client.util.AsyncBufferedImage sprite = plugin.supplyIcon(item.itemId);
            if (sprite != null) sprite.addTo(icon);
            tile.add(icon);
            JLabel quantity = new JLabel("x" + item.count);
            quantity.setAlignmentX(Component.CENTER_ALIGNMENT);
            tile.add(quantity);
            JLabel cost = new JLabel(GpFormat.shortGp(item.cost));
            cost.setAlignmentX(Component.CENTER_ALIGNMENT);
            tile.add(cost);
            tile.setToolTipText("<html>" + item.name.replace("<", "&lt;")
                + "<br>" + item.count + " used<br>" + GpFormat.exactGp(item.cost) + " GP</html>");
            currentSupplyGrid.add(tile);
        }
        currentSupplyGrid.revalidate();
        currentSupplyGrid.repaint();
    }

    private static DefaultTableModel ledgerModel()
    {
        return new DefaultTableModel(new Object[]{"Time", "Event", "Wave", "GP"},0)
        {
            @Override public boolean isCellEditable(int row,int column) { return false; }
        };
    }

    private static JTable ledgerTable(DefaultTableModel model, List<LedgerEvent> rows)
    {
        JTable table = new JTable(model)
        {
            @Override public String getToolTipText(MouseEvent event)
            {
                int viewRow = rowAtPoint(event.getPoint());
                if (viewRow<0) return null;
                int row = convertRowIndexToModel(viewRow);
                if (row>=rows.size()) return null;
                LedgerEvent entry = rows.get(row);
                return "<html>" + (entry.note == null ? "" : entry.note.replace("<", "&lt;"))
                    + " | Wave " + (entry.wave < 0 ? "Adj." : entry.wave == 0 ? "unknown" : entry.wave)
                    + " | " + GpFormat.exactGp(entry.gp) + "</html>";
            }
        };
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(55);
        table.getColumnModel().getColumn(1).setPreferredWidth(96);
        table.getColumnModel().getColumn(2).setPreferredWidth(40);
        table.getColumnModel().getColumn(3).setPreferredWidth(68);
        return table;
    }

    private static void button(JPanel area, String label, Runnable action)
    {
        JButton button = new JButton(label);
        button.addActionListener(e -> action.run());
        area.add(button);
    }

    void refresh()
    {
        if (refreshing) return;
        refreshing=true;
        try
        {
            if (tabs.getSelectedIndex() == 3) refreshCurrentRun();
            String account=plugin.account();
            // The active account can change on login; never accidentally carry a selection across accounts.
            if (!Objects.equals(account,accountShown)) selectedHistoryDate=null;
            accountShown=account;
            List<LedgerEvent> entries=plugin.entries();
            Map<LocalDate, DailySummary> days=DailySummary.byDay(entries,account,LOCAL_ZONE);
            LocalDate today=LocalDate.now(LOCAL_ZONE);
            DailySummary current=days.getOrDefault(today,new DailySummary());
            DailySummary lifetime=DailySummary.allTime(entries,account);
            allTimeSummary.setText(allTimeBreakdownHtml(lifetime));
            if (days.isEmpty())
            {
                allTimePeriod.setText("No recorded attempts yet");
            }
            else
            {
                LocalDate first=days.keySet().stream().min(LocalDate::compareTo).get();
                LocalDate last=days.keySet().stream().max(LocalDate::compareTo).get();
                allTimePeriod.setText("<html>Recorded days: " + days.size()
                    + "<br>Period: " + first.format(DATE_LABEL) + " - " + last.format(DATE_LABEL) + "</html>");
            }
            todayHeading.setText("Today  |  " + today.format(DATE_LABEL));
            todaySummary.setText(summaryHtml(current));
            setCounters(current, todayClearCount, todayCashCount, todayDeathCount);

            todayVisible.clear();
            todayModel.setRowCount(0);
            for (LedgerEvent entry:entries)
            {
                if (account.equals(entry.account) && today.equals(entry.date(LOCAL_ZONE))) todayVisible.add(entry);
            }
            todayVisible.sort(Comparator.comparing((LedgerEvent e)->e.timestamp).reversed());
            addLedgerRows(todayModel,todayVisible);

            historyDates.clear();
            historyDaysModel.setRowCount(0);
            days.forEach((date,stats)->{
                historyDates.add(date);
                historyDaysModel.addRow(new Object[]{date.toString(),stats.clears,stats.cashOuts,stats.deaths,GpFormat.shortGp(stats.net())});
            });
            if (selectedHistoryDate == null || !days.containsKey(selectedHistoryDate)) selectedHistoryDate=historyDates.isEmpty()?null:historyDates.get(0);
            if (selectedHistoryDate!=null)
            {
                int selected = historyDates.indexOf(selectedHistoryDate);
                if (selected>=0) historyDaysTable.setRowSelectionInterval(selected,selected);
            }
            showHistoryDetails(entries,days);
        }
        finally { refreshing=false; }
    }

    private void showHistoryDetails()
    {
        showHistoryDetails(plugin.entries(), DailySummary.byDay(plugin.entries(),plugin.account(),LOCAL_ZONE));
    }

    private void showHistoryDetails(List<LedgerEvent> entries, Map<LocalDate,DailySummary> days)
    {
        historyVisible.clear();
        historyDetailModel.setRowCount(0);
        if (selectedHistoryDate==null)
        {
            historyHeading.setText("No history recorded yet");
            historySummary.setText("");
            setCounters(new DailySummary(), historyClearCount, historyCashCount, historyDeathCount);
            return;
        }
        historyHeading.setText(selectedHistoryDate.format(DATE_LABEL));
        DailySummary stats=days.getOrDefault(selectedHistoryDate,new DailySummary());
        historySummary.setText(summaryHtml(stats));
        setCounters(stats, historyClearCount, historyCashCount, historyDeathCount);
        for (LedgerEvent entry:entries)
        {
            if (plugin.account().equals(entry.account) && selectedHistoryDate.equals(entry.date(LOCAL_ZONE))) historyVisible.add(entry);
        }
        historyVisible.sort(Comparator.comparing((LedgerEvent e)->e.timestamp).reversed());
        addLedgerRows(historyDetailModel,historyVisible);
    }

    private static void addLedgerRows(DefaultTableModel model,List<LedgerEvent> entries)
    {
        for (LedgerEvent entry:entries)
        {
            String localTime = java.time.Instant.parse(entry.timestamp).atZone(LOCAL_ZONE)
                .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
            model.addRow(new Object[]{localTime, friendlyType(entry),
                entry.wave < 0 ? "Adj." : entry.wave > 0 ? Integer.toString(entry.wave) : "?", GpFormat.shortGp(entry.gp)});
        }
    }

    /** Spacious, reconciled lifetime P&L; shorter daily summaries stay unchanged. */
    private static String allTimeBreakdownHtml(DailySummary total)
    {
        long otherProceeds = total.totalCredits - total.fullClearLoot
            - total.cashOutLoot - total.quiverTurnIns;
        long otherCosts = total.otherCosts();
        String green = "#63c985";
        String red = "#ef7777";
        String netColor = total.net() >= 0 ? green : red;
        StringBuilder out = new StringBuilder("<html><table cellpadding='3' cellspacing='0'>")
            .append("<tr><td colspan='2'><b>ATTEMPTS</b></td></tr>")
            .append("<tr><td>Sol clears</td><td align='right'>").append(total.clears).append("</td></tr>")
            .append("<tr><td>Cash-outs</td><td align='right'>").append(total.cashOuts).append("</td></tr>")
            .append("<tr><td>Deaths</td><td align='right'>").append(total.deaths).append("</td></tr>")
            .append("<tr><td colspan='2'>&nbsp;</td></tr>")
            .append("<tr><td colspan='2'><b>PROCEEDS</b></td></tr>");
        addPnlRow(out, "Full clears", total.fullClearLoot, green, true);
        addPnlRow(out, "Early cash-outs", total.cashOutLoot, green, true);
        addPnlRow(out, "Quiver turn-ins", total.quiverTurnIns, green, true);
        if (otherProceeds != 0)
        {
            addPnlRow(out, "Other proceeds", otherProceeds, otherProceeds >= 0 ? green : red, false);
        }
        addPnlRow(out, "Gross proceeds", total.totalCredits, green, true, true);
        out.append("<tr><td colspan='2'>&nbsp;</td></tr>")
            .append("<tr><td colspan='2'><b>COSTS</b></td></tr>");
        addPnlRow(out, "Deaths / recovery", -total.deathCosts, red, false);
        addPnlRow(out, "Supplies", -total.supplyCosts, red, false);
        if (otherCosts != 0)
        {
            addPnlRow(out, "Other costs", -otherCosts, red, false);
        }
        addPnlRow(out, "Total costs", -total.costs, red, false, true);
        out.append("<tr><td colspan='2'>&nbsp;</td></tr>");
        addPnlRow(out, "NET PROFIT", total.net(), netColor, false, true);
        return out.append("</table></html>").toString();
    }

    private static void addPnlRow(StringBuilder out, String label, long gp, String color,
        boolean positive)
    {
        addPnlRow(out, label, gp, color, positive, false);
    }

    private static void addPnlRow(StringBuilder out, String label, long gp, String color,
        boolean positive, boolean bold)
    {
        out.append("<tr><td>");
        if (bold) out.append("<b>");
        out.append(label);
        if (bold) out.append("</b>");
        out.append("</td><td align='right'><font color='").append(color).append("'>");
        if (bold) out.append("<b>");
        out.append(gp >= 0 ? "+" : "-")
            .append(GpFormat.shortGp(Math.abs(gp))).append(" GP");
        if (bold) out.append("</b>");
        out.append("</font></td></tr>");
    }

    private static void setCounters(DailySummary day, JLabel clears, JLabel cashOuts, JLabel deaths)
    {
        clears.setText(Integer.toString(day.clears));
        cashOuts.setText(Integer.toString(day.cashOuts));
        deaths.setText(Integer.toString(day.deaths));
    }

    private static String summaryHtml(DailySummary day)
    {
        // Gross proceeds are revenue before expenses; never label them gross profit.
        // Other proceeds preserves the reconciliation if future credit types are added.
        long otherProceeds = day.totalCredits - day.fullClearLoot - day.cashOutLoot - day.quiverTurnIns;
        String green = "#63c985";
        String red = "#ef7777";
        String netColor = day.net() >= 0 ? green : red;
        StringBuilder out = new StringBuilder("<html>")
            .append("Full clears: <font color='").append(green).append("'>+")
            .append(GpFormat.shortGp(day.fullClearLoot)).append(" GP</font>")
            .append("<br>Early cash-outs: <font color='").append(green).append("'>+")
            .append(GpFormat.shortGp(day.cashOutLoot)).append(" GP</font>")
            .append("<br>Quiver turn-ins: <font color='").append(green).append("'>+")
            .append(GpFormat.shortGp(day.quiverTurnIns)).append(" GP</font>");
        if (otherProceeds != 0)
        {
            out.append("<br>Other proceeds: <font color='").append(green).append("'>+")
                .append(GpFormat.shortGp(otherProceeds)).append(" GP</font>");
        }
        return out.append("<br>Gross proceeds: <font color='").append(green).append("'>+")
            .append(GpFormat.shortGp(day.totalCredits)).append(" GP</font>")
            .append("<br>Costs: <font color='").append(red).append("'>-")
            .append(GpFormat.shortGp(day.costs)).append(" GP</font>")
            .append("<br><b>NET: <font color='").append(netColor).append("'>")
            .append(day.net() >= 0 ? "+" : "-")
            .append(GpFormat.shortGp(Math.abs(day.net()))).append(" GP</font></b></html>")
            .toString();
    }

    private static String friendlyType(LedgerEvent entry)
    {
        if (entry.note != null && entry.note.startsWith("Dizana's quiver exchanged for 4,000 Sunfire splinters"))
        {
            return "Quiver turn-in";
        }
        LedgerEvent.Type type = entry.type;
        if (type == null) return "Unclassified";
        switch (type)
        {
            case CLEAR: return "Sol clear";
            case DEATH: return "Death";
            case CASH_OUT: return "Early cash-out";
            case CLEAR_LOOT: return "Clear loot";
            case CASH_OUT_LOOT: return "Cash-out loot";
            case LOOT: return "Legacy reward (review)";
            case DEATH_FEE: return "Death fee";
            case SUPPLIES: return "Supplies";
            case ADJUSTMENT: return "Adjustment";
            case QUIVER_TURN_IN: return "Quiver turn-in";
            default: return type.name();
        }
    }

    private void addEntry()
    {
        JComboBox<LedgerEvent.Type> type = new JComboBox<>(new LedgerEvent.Type[]{
            LedgerEvent.Type.CLEAR, LedgerEvent.Type.CASH_OUT, LedgerEvent.Type.DEATH,
            LedgerEvent.Type.DEATH_FEE, LedgerEvent.Type.SUPPLIES, LedgerEvent.Type.ADJUSTMENT
        });
        JTextField amount=new JTextField("0");
        JComboBox<String> wave = adjustmentWaveChooser(-1);
        JTextField note=new JTextField("Manual entry");
        JPanel form=new JPanel(new GridLayout(0,1));
        form.add(new JLabel("Event type"));form.add(type);
        form.add(new JLabel("GP amount (positive for costs, stored negative)"));form.add(amount);
        form.add(new JLabel("Wave (Adj. for supplies / adjustments)"));form.add(wave);
        form.add(new JLabel("Note"));form.add(note);
        type.addActionListener(e -> {
            LedgerEvent.Type selected = (LedgerEvent.Type) type.getSelectedItem();
            if (selected == LedgerEvent.Type.SUPPLIES || selected == LedgerEvent.Type.ADJUSTMENT
                || selected == LedgerEvent.Type.DEATH_FEE) wave.setSelectedIndex(0);
        });
        if (JOptionPane.showConfirmDialog(this,form,"Add ledger entry",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        try
        {
            long gp=Long.parseLong(amount.getText().replace(",","").trim());
            LedgerEvent.Type selected=(LedgerEvent.Type)type.getSelectedItem();
            if (selected==LedgerEvent.Type.DEATH_FEE || selected==LedgerEvent.Type.SUPPLIES) gp=-Math.abs(gp);
            if (selected==LedgerEvent.Type.DEATH) gp=0;
            plugin.record(selected,gp,note.getText(),adjustmentWaveValue(wave));
        }
        catch(NumberFormatException ex)
        {
            JOptionPane.showMessageDialog(this,"Enter a valid whole-number GP amount.");
        }
    }

    private LedgerEvent selectedEntry(JTable table,List<LedgerEvent> entries)
    {
        int view=table.getSelectedRow();
        if (view<0) return null;
        int modelIndex=table.convertRowIndexToModel(view);
        return modelIndex>=0 && modelIndex<entries.size() ? entries.get(modelIndex) : null;
    }

    private void quiverTurnIn()
    {
        plugin.requestQuiverTurnInValue(this::confirmQuiverTurnIn);
    }

    private void confirmQuiverTurnIn(long value)
    {
        if (value <= 0)
        {
            JOptionPane.showMessageDialog(this,
                "Sunfire splinters price is currently unavailable. Try again after RuneLite loads item prices.");
            return;
        }
        long unitPrice = value / 4_000;
        String prompt = "Record exchange of one Dizana's quiver for 4,000 Sunfire splinters?\n"
            + "Price: " + GpFormat.exactGp(unitPrice) + " GP each\n"
            + "Credit: +" + GpFormat.exactGp(value) + " GP\nWave: Adj.";
        if (JOptionPane.showConfirmDialog(this, prompt, "Quiver turn-in",
            JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
        {
            plugin.recordQuiverTurnIn(value, unitPrice);
        }
    }

    /** The negative wave sentinel represents a non-wave adjustment in stored ledger data. */
    private static JComboBox<String> adjustmentWaveChooser(int initial)
    {
        JComboBox<String> chooser = new JComboBox<>();
        chooser.addItem("Adj.");
        chooser.addItem("Unknown");
        for (int wave = 1; wave <= 12; wave++) chooser.addItem("Wave " + wave);
        chooser.setSelectedIndex(initial < 0 ? 0 : Math.min(13, initial + 1));
        return chooser;
    }

    private static int adjustmentWaveValue(JComboBox<String> chooser)
    {
        return chooser.getSelectedIndex() - 1;
    }

    private void editEntry(JTable table, List<LedgerEvent> entries)
    {
        LedgerEvent selected = selectedEntry(table, entries);
        if (selected == null)
        {
            JOptionPane.showMessageDialog(this, "Select an entry to edit first.");
            return;
        }
        JComboBox<LedgerEvent.Type> type = new JComboBox<>(LedgerEvent.Type.values());
        type.setSelectedItem(selected.type == null ? LedgerEvent.Type.ADJUSTMENT : selected.type);
        JTextField amount = new JTextField(Long.toString(selected.gp));
        JComboBox<String> wave = adjustmentWaveChooser(selected.wave);
        JTextField note = new JTextField(selected.note == null ? "" : selected.note);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 3));
        form.add(new JLabel("Event type"));
        form.add(type);
        form.add(new JLabel("GP amount (negative for costs)"));
        form.add(amount);
        form.add(new JLabel("Wave"));
        form.add(wave);
        form.add(new JLabel("Note"));
        form.add(note);
        if (JOptionPane.showConfirmDialog(this, form, "Edit ledger entry",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try
        {
            long gp = Long.parseLong(amount.getText().replace(",", "").trim());
            LedgerEvent.Type chosen = (LedgerEvent.Type) type.getSelectedItem();
            if (chosen == LedgerEvent.Type.SUPPLIES || chosen == LedgerEvent.Type.DEATH_FEE)
            {
                gp = -Math.abs(gp);
            }
            if (!plugin.editEntry(selected, chosen, gp, adjustmentWaveValue(wave), note.getText()))
            {
                JOptionPane.showMessageDialog(this, "Could not update this ledger entry.");
            }
        }
        catch (NumberFormatException ex)
        {
            JOptionPane.showMessageDialog(this, "Enter a valid whole-number GP amount.");
        }
    }

    private void deleteEntry(JTable table,List<LedgerEvent> entries)
    {
        LedgerEvent selected=selectedEntry(table,entries);
        if (selected==null) return;
        if(JOptionPane.showConfirmDialog(this,"Permanently delete selected ledger entry?",
            "Delete",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION) plugin.delete(selected);
    }

    private void exportCsv()
    {
        List<Filepath> selected = new Filepath.Chooser()
            .setIsSave()
            .setAcceptsFiles()
            .setDialogTitle("Export Fortis Ledger")
            .setFileName("fortis-ledger.csv")
            .setDefaultExtension("csv")
            .addExtensionFilter("CSV files", "csv")
            .showDialog(this);
        if (selected == null || selected.isEmpty()) return;
        StringBuilder output=new StringBuilder("account,timestamp,date,type,wave,gp,note\n");
        for (LedgerEvent entry:plugin.entries()) if(plugin.account().equals(entry.account))
        {
            output.append(csv(entry.account)).append(',').append(csv(entry.timestamp)).append(',')
                .append(entry.date(LOCAL_ZONE)).append(',').append(entry.type).append(',')
                .append(entry.wave).append(',').append(entry.gp).append(',').append(csv(entry.note)).append('\n');
        }
        try
        {
            selected.get(0).write(output.toString());
        }
        catch(Exception ex)
        {
            JOptionPane.showMessageDialog(this,"Export failed: "+ex.getMessage());
        }
    }

    private static String csv(String value)
    {
        return "\""+(value==null?"":value.replace("\"","\"\"").replace("\n"," ").replace("\r"," "))+"\"";
    }
}
