package com.arachnode.ui;

import com.arachnode.crawler.SeoCrawler;
import com.arachnode.export.CrawlExporter;
import com.arachnode.model.CrawlConfig;
import com.arachnode.model.CrawledPage;
import com.arachnode.model.LinkRef;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Screaming Frog-style main window: URL bar, tab filters, results grid,
 * URL details + right-side issue overview. Friendly + dense like SF.
 */
public class MainView {
    private final Stage stage;
    private final ObservableList<CrawledPage> allPages = FXCollections.observableArrayList();
    private final FilteredList<CrawledPage> filtered = new FilteredList<>(allPages, p -> true);
    private TableView<CrawledPage> table;
    private TextArea details;
    private TableView<LinkRef> outTable, inTable;
    private Tab outTab, inTab;
    private final Map<String, CrawledPage> pageIndex = new HashMap<>();
    private Map<String, List<LinkRef>> inlinkIndex = Map.of();
    private ListView<String> issuesView;
    private final Map<String, Integer> issueCounts = new HashMap<>();

    private TextField urlField, searchField;
    private Spinner<Integer> threadSpinner;
    private CheckBox subBox;
    private Button startBtn, pauseBtn, stopBtn;
    private ProgressBar progress;
    private Label statusLabel;
    private TabPane filterTabs;
    private TabPane detailTabs;
    private String currentFilter = "Internal";

    private SeoCrawler crawler;
    private final CrawlConfig config = new CrawlConfig();
    private long crawlStartMs;
    private String lastNote = "";
    private int lastCrawled, lastQueued, lastActive;
    // Coalesced UI updates: at high crawl speed, hundreds of callbacks/sec
    // collapse into one FX-thread flush per pulse instead of one per page.
    private final ConcurrentLinkedQueue<CrawledPage> pendingUi = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    public MainView(Stage stage) { this.stage = stage; }

    public Scene build() {
        BorderPane root = new BorderPane();
        root.setTop(buildTop());
        root.setCenter(buildCenter());
        root.setRight(buildIssuesPanel());
        root.setBottom(buildBottom());

        Scene scene = new Scene(root, 1380, 820);
        scene.getStylesheets().add(getClass().getResource("/app.css") == null ? "" : getClass().getResource("/app.css").toExternalForm());
        return scene;
    }

    // ---------- top ----------
    private VBox buildTop() {
        MenuBar menu = new MenuBar();
        Menu file = new Menu("File");
        MenuItem exportCsv = new MenuItem("Export CSV…");
        MenuItem exportMap = new MenuItem("Generate XML Sitemap…");
        MenuItem clear = new MenuItem("Clear Results");
        MenuItem exit = new MenuItem("Exit");
        exportCsv.setOnAction(e -> doExportCsv());
        exportMap.setOnAction(e -> doExportSitemap());
        clear.setOnAction(e -> { allPages.clear(); issueCounts.clear(); refreshIssues(); setStatus("Cleared."); });
        exit.setOnAction(e -> Platform.exit());
        file.getItems().addAll(exportCsv, exportMap, new SeparatorMenuItem(), clear, exit);

        Menu crawlMenu = new Menu("Crawl");
        MenuItem mStart = new MenuItem("Start"); MenuItem mStop = new MenuItem("Stop");
        mStart.setOnAction(e -> startCrawl()); mStop.setOnAction(e -> stopCrawl());
        crawlMenu.getItems().addAll(mStart, mStop);

        Menu help = new Menu("Help");
        MenuItem about = new MenuItem("About Arachnode");
        about.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "Arachnode 1.0 — Screaming Frog-style SEO spider.\nJava 21 + JavaFX + Virtual Threads.\nEnter a URL and press Start.").showAndWait());
        help.getItems().add(about);
        menu.getMenus().addAll(file, crawlMenu, help);

        urlField = new TextField("https://example.com");
        urlField.setPromptText("Enter URL to spider — e.g. https://example.com");
        urlField.setPrefWidth(420);
        urlField.setTooltip(new Tooltip("Start URL. Only this host is crawled unless subdomains enabled."));

        startBtn = new Button("Start"); pauseBtn = new Button("Pause"); stopBtn = new Button("Stop");
        startBtn.setDefaultButton(true);
        pauseBtn.setDisable(true); stopBtn.setDisable(true);
        startBtn.setOnAction(e -> startCrawl());
        pauseBtn.setOnAction(e -> togglePause());
        stopBtn.setOnAction(e -> stopCrawl());

        threadSpinner = new Spinner<>(1, 100, 20); threadSpinner.setPrefWidth(80); threadSpinner.setTooltip(new Tooltip("Crawl threads (20 default; lower if the server throttles you)"));

        searchField = new TextField(); searchField.setPromptText("Filter URLs…"); searchField.setPrefWidth(200);
        searchField.textProperty().addListener((o, a, b) -> applyFilter());

        subBox = new CheckBox("Subdomains");
        subBox.setSelected(true);
        subBox.setTooltip(new Tooltip("Include subdomains (e.g. www) in the crawl — on by default like Screaming Frog."));

        Region spacer = new Region();
        HBox bar = new HBox(8, new Label("URL:"), urlField, startBtn, pauseBtn, stopBtn,
                new Label("Threads:"), threadSpinner,
                subBox, spacer, new Label("Search:"), searchField);
        bar.setStyle("-fx-padding: 8;");
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox.setHgrow(urlField, Priority.ALWAYS);

        progress = new ProgressBar(0); progress.setPrefWidth(400);
        statusLabel = new Label("Ready. Enter a URL and press Start.");
        HBox statusRow = new HBox(10, progress, statusLabel);
        statusRow.setStyle("-fx-padding: 0 8 8 8;");

        return new VBox(menu, bar, statusRow);
    }

    // ---------- center ----------
    private VBox buildCenter() {
        filterTabs = new TabPane();
        for (String name : List.of("Internal", "External", "Response Codes", "Page Titles",
                "Meta Description", "H1", "H2", "Images", "Directives", "Canonicals", "Issues")) {
            Tab t = new Tab(name);
            t.setClosable(false);
            filterTabs.getTabs().add(t);
        }
        filterTabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            currentFilter = b.getText(); applyFilter();
        });

        table = new TableView<>(filtered);
        table.setPlaceholder(new Label("No URLs yet — start a crawl above."));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        addCol("Address", "url", 300);
        addCol("Status", "statusCode", 70);
        addCol("Title", "title", 220);
        addCol("Title Len", "titleLength", 70);
        addCol("Meta Desc", "metaDescription", 220);
        addCol("Meta Len", "metaDescLength", 70);
        addCol("H1", "h1", 180);
        addCol("H1 #", "h1Count", 55);
        addCol("H2 #", "h2Count", 55);
        addCol("Words", "wordCount", 70);
        addCol("Canonical", "canonical", 200);
        addCol("Robots", "metaRobots", 120);
        addCol("Depth", "depth", 60);
        addCol("Inlinks", "inlinks", 65);
        addCol("Outlinks", "outlinks", 70);
        addCol("Images", "imageCount", 65);
        addCol("No Alt", "imagesMissingAlt", 60);
        addCol("Resp ms", "responseTimeMs", 75);
        addCol("Issues", "issues", 320);

        // status colouring
        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(CrawledPage p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) { setStyle(""); return; }
                int c = p.getStatusCode();
                if (c >= 500 || c == 0) setStyle("-fx-background-color: #f8d7da;");
                else if (c >= 400) setStyle("-fx-background-color: #fff3cd;");
                else if (c >= 300) setStyle("-fx-background-color: #e2e3ff;");
                else setStyle("");
            }
        });

        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showDetails(b));
        table.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) openInBrowser(table.getSelectionModel().getSelectedItem().getUrl()); });

        MenuItem copy = new MenuItem("Copy URL(s)");
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            StringBuilder sb = new StringBuilder();
            for (CrawledPage p : table.getSelectionModel().getSelectedItems()) sb.append(p.getUrl()).append("\n");
            cc.putString(sb.toString().trim()); Clipboard.getSystemClipboard().setContent(cc);
        });
        MenuItem open = new MenuItem("Open in Browser");
        open.setOnAction(e -> { var p = table.getSelectionModel().getSelectedItem(); if (p != null) openInBrowser(p.getUrl()); });
        table.setContextMenu(new ContextMenu(copy, open));

        details = new TextArea();
        details.setEditable(false); details.setPrefRowCount(6);
        details.setWrapText(true);
        details.setPromptText("Select a URL to see details: directives, canonical, redirect chain, issues…");
        // TextArea fills the tab width directly (a ScrollPane wrapper kept it narrow).
        Tab detailTab = new Tab("URL Details", details);
        detailTab.setClosable(false);

        outTable = makeLinkTable();
        outTab = new Tab("Outlinks", outTable);
        outTab.setClosable(false);
        inTable = makeLinkTable();
        inTab = new Tab("Inlinks", inTable);
        inTab.setClosable(false);

        detailTabs = new TabPane(detailTab, outTab, inTab);
        detailTabs.setPrefHeight(220);
        detailTabs.setMinHeight(180);

        // Apply the default tab filter immediately: the table's initial
        // always-true predicate would otherwise show every row in every tab
        // until a tab is clicked or search is typed.
        applyFilter();

        VBox box = new VBox(filterTabs, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private void addCol(String title, String prop, int width) {
        TableColumn<CrawledPage, String> c = new TableColumn<>(title);
        c.setCellValueFactory(new PropertyValueFactory<>(prop));
        c.setPrefWidth(width);
        c.setSortable(true);
        table.getColumns().add(c);
    }

    /** SF-style link list: URL + anchor + rel + live status; double-click jumps. */
    private TableView<LinkRef> makeLinkTable() {
        TableView<LinkRef> tv = new TableView<>();
        tv.setPlaceholder(new Label("Select a URL above to see links."));
        TableColumn<LinkRef, String> addr = new TableColumn<>("Address");
        addr.setCellValueFactory(new PropertyValueFactory<>("target"));
        addr.setPrefWidth(480);
        TableColumn<LinkRef, String> anchor = new TableColumn<>("Anchor Text");
        anchor.setCellValueFactory(new PropertyValueFactory<>("anchor"));
        anchor.setPrefWidth(300);
        TableColumn<LinkRef, String> rel = new TableColumn<>("Rel");
        rel.setCellValueFactory(new PropertyValueFactory<>("rel"));
        rel.setPrefWidth(110);
        TableColumn<LinkRef, String> st = new TableColumn<>("Status");
        st.setCellValueFactory(cd -> new SimpleStringProperty(statusOf(cd.getValue().getTarget())));
        st.setPrefWidth(200);
        tv.getColumns().addAll(addr, anchor, rel, st);
        tv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tv.getSelectionModel().getSelectedItem() != null)
                jumpTo(tv.getSelectionModel().getSelectedItem().getTarget());
        });
        return tv;
    }

    private VBox buildIssuesPanel() {
        issuesView = new ListView<>();
        issuesView.setPrefWidth(300);
        issuesView.setPlaceholder(new Label("Issue overview appears during crawl."));
        VBox box = new VBox(new Label("Issues Overview"), issuesView);
        box.setStyle("-fx-padding: 8; -fx-spacing: 6;");
        box.setPrefWidth(310);
        return box;
    }

    private String statusOf(String u) {
        CrawledPage p = pageIndex.get(u);
        if (p == null) return "—";
        if ("External".equals(p.getContentKind())) return "External";
        return p.getStatusCode() + " " + p.getStatusText();
    }

    private void jumpTo(String url) {
        for (int i = 0; i < table.getItems().size(); i++) {
            if (table.getItems().get(i).getUrl().equals(url)) {
                table.getSelectionModel().select(i);
                table.scrollTo(i);
                return;
            }
        }
        setStatus("Linked URL is not in the current filter — clear Search or switch tabs.");
    }

    private VBox buildBottom() {
        Label hint = new Label("Tip: double-click a URL to open it • right-click to copy • use Search to filter • Export CSV / Sitemap from File menu.");
        hint.setStyle("-fx-padding: 6 8 6 8; -fx-text-fill: #555;");
        HBox tip = new HBox(hint);
        // BorderPane bottom spans the full window width (SF-style lower pane).
        VBox box = new VBox(detailTabs, tip);
        return box;
    }

    // ---------- crawl control ----------
    private void startCrawl() {
        if (crawler != null) return;
        allPages.clear(); issueCounts.clear(); pageIndex.clear(); pendingUi.clear(); refreshIssues();
        // Tolerate "example.com" without scheme — assume https like browsers do.
        String typed = urlField.getText() == null ? "" : urlField.getText().trim();
        if (!typed.contains("://")) typed = "https://" + typed;
        urlField.setText(typed);
        config.startUrl = typed;
        config.threads = threadSpinner.getValue();
        config.maxUrls = Integer.MAX_VALUE; // unlimited crawl
        config.includeSubdomains = subBox.isSelected();
        crawlStartMs = System.currentTimeMillis();
        lastCrawled = 0; lastQueued = 0; lastActive = 0;
        crawlStartMs = System.currentTimeMillis();
        lastNote = "";
        startBtn.setDisable(true); pauseBtn.setDisable(false); stopBtn.setDisable(false);
        pauseBtn.setText("Pause");
        progress.setProgress(-1);
        setStatus("Crawling " + config.startUrl + " …");

        crawler = new SeoCrawler(config, new SeoCrawler.Listener() {
            @Override public void onPage(CrawledPage page) {
                pendingUi.add(page);
                scheduleFlush();
            }
            @Override public void onProgress(int crawled, int queued, int active) {
                lastCrawled = crawled; lastQueued = queued; lastActive = active;
                scheduleFlush();
            }
            @Override public void onFinished(int crawled) {
                Platform.runLater(() -> {
                    flushUi(); // drain any coalesced pages first
                    long secs = (System.currentTimeMillis() - crawlStartMs) / 1000;
                    if (crawled == 0) {
                        setStatus("Finished: 0 URLs — nothing crawled. " + (lastNote.isEmpty()
                                ? "Check the URL (include https://) and that the site is reachable."
                                : lastNote));
                        progress.setProgress(0);
                    } else {
                        setStatus("Finished: " + crawled + " URLs in " + secs + "s.");
                        progress.setProgress(1);
                    }
                    startBtn.setDisable(false); pauseBtn.setDisable(true); stopBtn.setDisable(true);
                    crawler = null;
                });
            }
            @Override public void onMessage(String msg) { lastNote = msg; Platform.runLater(() -> setStatus(msg)); }
        });
        new Thread(crawler::start, "arachnode-starter").start();
        inlinkIndex = crawler.getInlinkIndex();
    }

    private void togglePause() {
        if (crawler == null) return;
        crawler.pause(!crawler.isPaused());
        pauseBtn.setText(crawler.isPaused() ? "Resume" : "Pause");
    }

    private void stopCrawl() {
        if (crawler != null) { crawler.stop(); setStatus("Stopping…"); }
    }

    private void updateProgress() {
        // % of discovered URLs completed (total is unknowable upfront in a crawl).
        int known = lastCrawled + lastQueued;
        double frac = known == 0 ? -1 : Math.min(1.0, (double) lastCrawled / known);
        progress.setProgress(frac);
        String pct = frac < 0 ? "—" : (int) Math.round(frac * 100) + "%";
        setStatus("Crawled " + lastCrawled + " • Queued " + lastQueued + " • Active " + lastActive
                + " • " + pct + " of discovered • " + elapsed()
                + "  •  filter: " + currentFilter + " (" + filtered.size() + " shown)");
    }

    private String elapsed() {
        long s = (System.currentTimeMillis() - crawlStartMs) / 1000;
        return s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
    }

    /** At most one queued FX flush at a time; bursts collapse into one table update. */
    private void scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) Platform.runLater(this::flushUi);
    }

    private void flushUi() {
        flushScheduled.set(false);
        CrawledPage p;
        while ((p = pendingUi.poll()) != null) {
            allPages.add(p);
            pageIndex.put(p.getUrl(), p);
            for (String i : p.getIssueList()) {
                String key = i.split("\\(")[0].trim();
                issueCounts.merge(key, 1, Integer::sum);
            }
        }
        refreshIssues();
        updateProgress();
        if (!pendingUi.isEmpty()) scheduleFlush();
    }

    private void setStatus(String s) { statusLabel.setText(s); }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().toLowerCase();
        Predicate<CrawledPage> tab = predicateFor(currentFilter);
        filtered.setPredicate(p -> {
            if (!q.isEmpty() && !p.getUrl().toLowerCase().contains(q)
                    && !(p.getTitle() != null && p.getTitle().toLowerCase().contains(q))) return false;
            return tab.test(p);
        });
    }

    private Predicate<CrawledPage> predicateFor(String tab) {
        return switch (tab) {
            case "External" -> p -> "External".equals(p.getContentKind());
            case "Response Codes" -> p -> !"External".equals(p.getContentKind());
            case "Page Titles" -> p -> "HTML".equals(p.getContentKind()) && (p.getTitle().isEmpty() || p.getTitleLength() > 60 || p.getTitleLength() < 30);
            case "Meta Description" -> p -> "HTML".equals(p.getContentKind()) && (p.getMetaDescription().isEmpty() || p.getMetaDescLength() > 160);
            case "H1" -> p -> "HTML".equals(p.getContentKind()) && (p.getH1Count() != 1);
            case "H2" -> p -> "HTML".equals(p.getContentKind()) && p.getH2Count() == 0;
            case "Images" -> p -> "HTML".equals(p.getContentKind()) && p.getImageCount() > 0;
            case "Directives" -> p -> !p.getMetaRobots().isEmpty() || !p.getXRobots().isEmpty() || (p.getIssues() != null && p.getIssues().contains("Noindex"));
            case "Canonicals" -> p -> "HTML".equals(p.getContentKind()) && (p.getCanonical().isEmpty() || (p.getIssues() != null && p.getIssues().contains("Canonical")));
            case "Issues" -> p -> p.getIssues() != null && !p.getIssues().isEmpty() && !"External Link".equals(p.getIssues());
            default -> p -> !"External".equals(p.getContentKind()); // Internal
        };
    }

    private void showDetails(CrawledPage p) {
        if (p == null) { details.setText(""); outTable.setItems(FXCollections.observableArrayList()); inTable.setItems(FXCollections.observableArrayList()); return; }
        details.setText(
            "URL: " + p.getUrl() + "\n" +
            "Status: " + p.getStatusCode() + " " + p.getStatusText() + "  •  Type: " + p.getContentType() + "  •  Kind: " + p.getContentKind() + "\n" +
            "Title (" + p.getTitleLength() + "): " + p.getTitle() + "\n" +
            "Meta Description (" + p.getMetaDescLength() + "): " + p.getMetaDescription() + "\n" +
            "H1 (" + p.getH1Count() + "): " + p.getH1() + "  •  H2: " + p.getH2Count() + "  •  Words: " + p.getWordCount() + "\n" +
            "Canonical: " + p.getCanonical() + "\n" +
            "Meta Robots: " + p.getMetaRobots() + "  •  X-Robots: " + p.getXRobots() + "\n" +
            "Depth: " + p.getDepth() + "  •  Inlinks: " + p.getInlinks() + "  •  Outlinks: " + p.getOutlinks() + "\n" +
            "Images: " + p.getImageCount() + " (missing alt: " + p.getImagesMissingAlt() + ")  •  Response: " + p.getResponseTimeMs() + "ms  •  Size: " + p.getSizeBytes() + "b\n" +
            "Redirect chain: " + (p.getRedirectChain().isEmpty() ? "—" : p.getRedirectChain()) + "\n" +
            "Content hash: " + p.getContentHash() + "\n" +
            "Issues: " + (p.getIssues().isEmpty() ? "None ✔" : p.getIssues())
        );
        // SF-style link tabs
        outTable.setItems(FXCollections.observableArrayList(p.getOutlinkRefs()));
        List<LinkRef> in = new ArrayList<>(inlinkIndex.getOrDefault(p.getUrl(), List.of()));
        in.sort(Comparator.comparing(LinkRef::getTarget));
        inTable.setItems(FXCollections.observableArrayList(in));
        outTab.setText("Outlinks (" + p.getOutlinkRefs().size() + ")");
        inTab.setText("Inlinks (" + in.size() + ")");
    }

    private void refreshIssues() {
        List<String> rows = new ArrayList<>();
        issueCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> rows.add(e.getValue() + " ×  " + e.getKey()));
        issuesView.setItems(FXCollections.observableArrayList(rows));
    }

    private void openInBrowser(String url) {
        try { Desktop.getDesktop().browse(new URI(url)); }
        catch (Exception e) { setStatus("Cannot open browser: " + e.getMessage()); }
    }

    private void doExportCsv() {
        FileChooser fc = new FileChooser(); fc.setInitialFileName("arachnode-crawl.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try { CrawlExporter.toCsv(new ArrayList<>(allPages), f.toPath()); setStatus("Exported CSV: " + f); }
        catch (Exception e) { new Alert(Alert.AlertType.ERROR, "Export failed: " + e.getMessage()).showAndWait(); }
    }

    private void doExportSitemap() {
        FileChooser fc = new FileChooser(); fc.setInitialFileName("sitemap.xml");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML", "*.xml"));
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try { CrawlExporter.toSitemap(new ArrayList<>(allPages), f.toPath()); setStatus("Exported sitemap: " + f); }
        catch (Exception e) { new Alert(Alert.AlertType.ERROR, "Export failed: " + e.getMessage()).showAndWait(); }
    }

    public void stop() { if (crawler != null) crawler.stop(); }
}
