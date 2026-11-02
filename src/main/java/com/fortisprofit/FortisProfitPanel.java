package com.fortisprofit;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
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
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
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
    private final JLabel todayHeading = new JLabel();
    private final JLabel historyHeading = new JLabel("Select a day above to inspect its ledger");
    private final JLabel historySummary = new JLabel();
    private final JLabel allTimeSummary = new JLabel();
    private final JLabel allTimePeriod = new JLabel();
    private final List<LedgerEvent> todayVisible = new ArrayList<>();
    private final List<LedgerEvent> historyVisible = new ArrayList<>();
    private final List<LocalDate> historyDates = new ArrayList<>();

    private final DefaultTableModel todayModel = ledgerModel();
    private final DefaultTableModel historyDetailModel = ledgerModel();
    private final DefaultTableModel historyDaysModel = new DefaultTableModel(
        new Object[]{"Date", "Clear", "Cash", "Dead", "Net"},0)
    {
        @Override public boolean isCellEditable(int row,int column) { return false; }
    };

    private final JTable todayTable = ledgerTable(todayModel, todayVisible);
    private final JTable historyDetailTable = ledgerTable(historyDetailModel, historyVisible);
    private final JTable historyDaysTable = new JTable(historyDaysModel);
    private final Timer midnightRefresh;
    private boolean refreshing;
    private LocalDate selectedHistoryDate;
    private String accountShown;

    FortisProfitPanel(FortisProfitPlugin plugin)
    {
        this.plugin = plugin;
        setLayout(new BorderLayout());
        tabs.addTab("Today", scrollableTab(makeToday()));
        tabs.addTab("History", scrollableTab(makeHistory()));
        tabs.addTab("All-Time", scrollableTab(makeAllTime()));
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


    private JPanel makeToday()
    {
        JPanel content = new JPanel(new BorderLayout(0, 6));
        JPanel summary = new JPanel(new BorderLayout(0, 3));
        todayHeading.setText("Today");
        summary.add(todayHeading, BorderLayout.NORTH);
        summary.add(todaySummary, BorderLayout.CENTER);
        content.add(summary, BorderLayout.NORTH);
        JScrollPane todayLedgerScroller = new JScrollPane(todayTable);
        todayLedgerScroller.setPreferredSize(new Dimension(220, 280));
        todayLedgerScroller.getVerticalScrollBar().setUnitIncrement(18);
        content.add(todayLedgerScroller, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(0, 2, 3, 3));
        button(actions, "Start attempt", plugin::manualStart);
        button(actions, "Record clear", plugin::manualClear);
        button(actions, "Record death", plugin::manualDeath);
        button(actions, "Cash out", plugin::manualCashOut);
        button(actions, "Add GP entry", this::addEntry);
        button(actions, "Mark cash-out", () -> markCashOut(todayTable, todayVisible));
        button(actions, "Mark Sol clear", () -> markSolClear(todayTable, todayVisible));
        button(actions, "Set entry wave", () -> setWave(todayTable, todayVisible));
        button(actions, "Set active wave", this::setActiveWave);
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
        historyDaysTable.getColumnModel().getColumn(1).setPreferredWidth(35);
        historyDaysTable.getColumnModel().getColumn(2).setPreferredWidth(35);
        historyDaysTable.getColumnModel().getColumn(3).setPreferredWidth(35);
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
        detailsHeading.add(historySummary, BorderLayout.CENTER);
        bottom.add(detailsHeading, BorderLayout.NORTH);
        bottom.add(new JScrollPane(historyDetailTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.32);
        split.setBorder(null);
        split.setPreferredSize(new Dimension(230, 420));
        content.add(split, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(0, 2, 3, 3));
        button(actions, "Mark cash-out", () -> markCashOut(historyDetailTable, historyVisible));
        button(actions, "Mark Sol clear", () -> markSolClear(historyDetailTable, historyVisible));
        button(actions, "Set entry wave", () -> setWave(historyDetailTable, historyVisible));
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
                    + " | Wave " + (entry.wave == 0 ? "unknown" : entry.wave)
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
            String account=plugin.account();
            // The active account can change on login; never accidentally carry a selection across accounts.
            if (!Objects.equals(account,accountShown)) selectedHistoryDate=null;
            accountShown=account;
            List<LedgerEvent> entries=plugin.entries();
            Map<LocalDate, DailySummary> days=DailySummary.byDay(entries,account,LOCAL_ZONE);
            LocalDate today=LocalDate.now(LOCAL_ZONE);
            DailySummary current=days.getOrDefault(today,new DailySummary());
            DailySummary lifetime=DailySummary.allTime(entries,account);
            allTimeSummary.setText(summaryHtml(lifetime));
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
            return;
        }
        historyHeading.setText(selectedHistoryDate.format(DATE_LABEL));
        DailySummary stats=days.getOrDefault(selectedHistoryDate,new DailySummary());
        historySummary.setText(summaryHtml(stats));
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
                .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            model.addRow(new Object[]{localTime, friendlyType(entry.type),
                entry.wave > 0 ? Integer.toString(entry.wave) : "?", GpFormat.shortGp(entry.gp)});
        }
    }

    private static String summaryHtml(DailySummary day)
    {
        return "<html><table cellpadding='1' cellspacing='0'>"
            + "<tr><td>Clears: <b>"+day.clears+"</b></td><td>Cash-outs: <b>"+day.cashOuts+"</b></td><td>Deaths: <b>"+day.deaths+"</b></td></tr>"
            + "</table>Full clears: " + GpFormat.shortGp(day.fullClearLoot) + " GP"
            + "<br>Early cash-outs: " + GpFormat.shortGp(day.cashOutLoot) + " GP"
            + "<br>Total credits: " + GpFormat.shortGp(day.totalCredits) + " GP"
            + "<br>Costs: " + GpFormat.shortGp(day.costs) + " GP"
            + "<br><b>NET: " + GpFormat.shortGp(day.net()) + " GP</b></html>";
    }

    private static String friendlyType(LedgerEvent.Type type)
    {
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
        JComboBox<String> wave = waveChooser(0);
        JTextField note=new JTextField("Manual entry");
        JPanel form=new JPanel(new GridLayout(0,1));
        form.add(new JLabel("Event type"));form.add(type);
        form.add(new JLabel("GP amount (positive for costs, stored negative)"));form.add(amount);
        form.add(new JLabel("Wave (unknown if not observed)"));form.add(wave);
        form.add(new JLabel("Note"));form.add(note);
        if (JOptionPane.showConfirmDialog(this,form,"Add ledger entry",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        try
        {
            long gp=Long.parseLong(amount.getText().replace(",","").trim());
            LedgerEvent.Type selected=(LedgerEvent.Type)type.getSelectedItem();
            if (selected==LedgerEvent.Type.DEATH_FEE || selected==LedgerEvent.Type.SUPPLIES) gp=-Math.abs(gp);
            if (selected==LedgerEvent.Type.DEATH) gp=0;
            plugin.record(selected,gp,note.getText(),wave.getSelectedIndex());
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

    private void markCashOut(JTable table,List<LedgerEvent> entries)
    {
        LedgerEvent selected=selectedEntry(table,entries);
        if (selected==null)
        {
            JOptionPane.showMessageDialog(this,"Select a previously unassigned loot entry first.");
            return;
        }
        if (selected.type!=LedgerEvent.Type.LOOT)
        {
            JOptionPane.showMessageDialog(this,"Only a legacy unclassified reward can be marked as an early cash-out.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
            "Convert this old loot row into one cash-out entry on the same date?",
            "Classify cash-out",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)
        {
            plugin.classifyAsCashOut(selected);
        }
    }

    private void markSolClear(JTable table, List<LedgerEvent> entries)
    {
        LedgerEvent selected = selectedEntry(table, entries);
        if (selected == null || selected.type != LedgerEvent.Type.CASH_OUT || selected.wave != 12)
        {
            JOptionPane.showMessageDialog(this, "Select a wave-12 early cash-out entry first.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
            "Confirm you defeated Sol Heredit? Reclassify this entry as a full clear without changing GP.",
            "Correct Sol clear", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
        {
            plugin.classifyAsClear(selected);
        }
    }

    private static JComboBox<String> waveChooser(int initial)
    {
        JComboBox<String> chooser = new JComboBox<>();
        chooser.addItem("Unknown");
        for (int wave = 1; wave <= 12; wave++) chooser.addItem("Wave " + wave);
        chooser.setSelectedIndex(Math.max(0, Math.min(12, initial)));
        return chooser;
    }

    private void setWave(JTable table, List<LedgerEvent> entries)
    {
        LedgerEvent selected = selectedEntry(table, entries);
        if (selected == null)
        {
            JOptionPane.showMessageDialog(this, "Select a ledger entry first.");
            return;
        }
        JComboBox<String> choice = waveChooser(selected.wave);
        if (JOptionPane.showConfirmDialog(this, choice, "Wave for selected entry",
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
        {
            plugin.changeWave(selected, choice.getSelectedIndex());
        }
    }

    private void setActiveWave()
    {
        JComboBox<String> choice = waveChooser(plugin.activeWave());
        if (JOptionPane.showConfirmDialog(this, choice,
            "Current Colosseum wave (used for next death / cash-out)",
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION && choice.getSelectedIndex() > 0)
        {
            plugin.setActiveWave(choice.getSelectedIndex());
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
