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
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
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
 * Screaming Frog-style main window, modernised.
 * Fixes from v1 review:
 *  - 301 redirect rows were blank/confusing -> dedicated Redirect URI + chain columns,
 *    Response Codes tab tuned for status audit, details pane explains redirects.
 *  - Inlinks/Outlinks tables both said "Address" -> now "From (source)" vs "To (target)",
 *    backed by LinkRef(source,target) so counts + jump-to are correct.
 *  - Inlink counts were frozen at fetch time -> refreshed from live index on finish/progress.
 *  - "External Link" polluted Issues Overview -> externals are no longer issues.
 * SF parity: per-tab columns, Security / Hreflang / Duplicates / All tabs,
 * clickable issue overview, spider configuration dialog, duplicate detection.
 */
public class MainView {
    private final Stage stage;
    private final ObservableList<CrawledPage> allPages = FXCollections.observableArrayList();
    private final FilteredList<CrawledPage> filtered = new FilteredList<>(allPages, p -> true);
    private SortedList<CrawledPage> sorted;
    private TableView<CrawledPage> table;
    private VBox detailsBox;
    private ScrollPane detailsScroll;
    private TableView<LinkRef> outTable, inTable;
    private Tab outTab, inTab;
    private TableView<com.arachnode.model.ImageRef> imgTable;
    private Tab imgTab;
    private TableView<CrawledPage> resTable, dupTable;
    private FilteredList<CrawledPage> resFiltered;
    private SortedList<CrawledPage> resSorted;
    private Tab resTab, dupTab;
    private Label serpTitle, serpUrl, serpDesc;
    private Tab serpTab;
    private TextArea sourceArea;
    private Tab sourceTab;
    private final java.util.Map<String, String> sourceCache = new java.util.LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) { return size() > 30; }
    };
    private final java.util.concurrent.atomic.AtomicLong sourceReq = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.net.http.HttpClient SRC_HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();
    private GridPane headersGrid;
    private Tab headersTab;
    private TextArea cookiesArea;
    private Tab cookiesTab;
    private TextArea structArea;
    private Label structCount;
    private Tab structTab;
    private final Map<String, CrawledPage> pageIndex = new HashMap<>();
    private Map<String, List<LinkRef>> inlinkIndex = Map.of();
    private ListView<String> issuesView;
    private final Map<String, Integer> issueCounts = new HashMap<>();
    private String issueFilter = "";

    private TextField urlField, searchField;
    private Spinner<Integer> threadSpinner;
    private Spinner<Integer> maxUrlsSpinner;
    private CheckBox subBox;
    private Button startBtn, pauseBtn, stopBtn, configBtn;
    private ComboBox<String> contentBox, statusBox, issueBox;
    private Button advancedBtn;
    private Label advancedBadge;
    private Button clearFiltersBtn;
    // Advanced Table Search (SF parity): OR of AND-groups. Empty = inactive.
    private final List<List<FilterCondition>> advancedGroups = new ArrayList<>();

    private record FilterCondition(String column, String operator, String query) {}
    private Label resultCountLabel;
    private ProgressBar progress;
    private Label progressText;
    private Label statusLabel;
    private Label rateLabel;
    private final ConcurrentLinkedQueue<Long> recentHits = new ConcurrentLinkedQueue<>();
    private HBox statsBox;
    private Button statOk, statRedir, statErr, statExt, statTotal;
    private ToggleGroup tabGroup;
    private HBox chipsBox;
    private final Map<String, ToggleButton> chipMap = new LinkedHashMap<>();
    private TabPane detailTabs;
    private SplitPane mainSplit;
    private SplitPane centerSplit;
    private String currentFilter = "Internal";

    private SeoCrawler crawler;
    private final CrawlConfig config = new CrawlConfig();
    private long crawlStartMs;
    private String lastNote = "";
    private int lastCrawled, lastQueued, lastActive;
    private final ConcurrentLinkedQueue<CrawledPage> pendingUi = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    // SF 19.x order: Internal, External, Security, Response Codes, URL, Page Titles,
    // Meta Description, Meta Keywords, H1, H2, Content, Images, Canonicals, Pagination,
    // Directives, Hreflang, JavaScript, Links, AMP, Structured Data, Custom Search (+ our All/Duplicates/Issues).
    private static final List<String> TABS = List.of("All", "Internal", "External", "Security",
            "Response Codes", "URL", "Page Titles", "Meta Description", "Meta Keywords", "H1", "H2",
            "Content", "Images", "Canonicals", "Pagination", "Directives", "Hreflang",
            "JavaScript", "Links", "AMP", "Structured Data", "Custom Search", "Duplicates", "Issues");
    // Custom Search (SF parity): up to 5 text/regex queries counted per HTML page.
    private final List<CustomQuery> customQueries = new ArrayList<>();

    private static class CustomQuery {
        String name, query;
        boolean regex;
        CustomQuery(String n, String q, boolean r) { name = n; query = q; regex = r; }
    }

    public MainView(Stage stage) { this.stage = stage; }

    public Scene build() {
        BorderPane root = new BorderPane();
        root.setTop(buildTop());

        VBox centerBox = buildCenter();
        VBox issuesBox = buildIssuesPanel();
        VBox bottomBox = buildBottom();

        // Resizable splits: small screens can collapse right/bottom panes.
        mainSplit = new SplitPane(centerBox, issuesBox);
        mainSplit.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        mainSplit.setDividerPositions(0.78);
        mainSplit.getStyleClass().add("main-split");
        SplitPane.setResizableWithParent(issuesBox, Boolean.FALSE);

        centerSplit = new SplitPane(mainSplit, bottomBox);
        centerSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        centerSplit.setDividerPositions(0.68);
        centerSplit.getStyleClass().add("center-split");

        root.setCenter(centerSplit);

        Scene scene = new Scene(root, 1280, 800);
        var css = getClass().getResource("/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        var darkCss = getClass().getResource("/dark.css");
        if (darkCss != null) scene.getStylesheets().add(darkCss.toExternalForm());
        var frogCss = getClass().getResource("/frog.css");
        if (frogCss != null) scene.getStylesheets().add(frogCss.toExternalForm());
        this.scene = scene;
        applyTheme(getTheme());
        installResize(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(620);
        return scene;
    }

    /** Called once after show: start maximized within the work area (taskbar-safe). */
    public void startMaximized() {
        javafx.geometry.Rectangle2D vb = screenBounds();
        prevX = stage.getX(); prevY = stage.getY(); prevW = stage.getWidth(); prevH = stage.getHeight();
        stage.setX(vb.getMinX()); stage.setY(vb.getMinY());
        stage.setWidth(vb.getWidth()); stage.setHeight(vb.getHeight());
        winMaximized = true;
    }

    private javafx.geometry.Rectangle2D screenBounds() {
        try {
            for (var s : javafx.stage.Screen.getScreensForRectangle(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()))
                return s.getVisualBounds();
        } catch (Exception ignored) {}
        return javafx.stage.Screen.getPrimary().getVisualBounds();
    }

    private HBox buildTitleBar() {
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView();
        try {
            var in = getClass().getResourceAsStream("/logo.png");
            if (in != null) { logo.setImage(new javafx.scene.image.Image(in)); in.close(); }
        } catch (Exception ignored) {}
        logo.setFitWidth(16); logo.setFitHeight(16);
        logo.setPreserveRatio(true);
        Label name = new Label("Arachnode — SEO Spider");
        name.getStyleClass().add("title-label");
        Region dragSpacer = new Region();
        HBox.setHgrow(dragSpacer, Priority.ALWAYS);
        HBox dragZone = new HBox(8, logo, name, dragSpacer);
        dragZone.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(dragZone, Priority.ALWAYS);
        dragZone.setOnMousePressed(e -> {
            if (e.getClickCount() == 2) { toggleMaximize(); return; }
            dragOffX = e.getScreenX() - stage.getX();
            dragOffY = e.getScreenY() - stage.getY();
        });
        dragZone.setOnMouseDragged(e -> {
            if (winMaximized || !e.isPrimaryButtonDown()) return;
            stage.setX(e.getScreenX() - dragOffX);
            stage.setY(e.getScreenY() - dragOffY);
        });
        dragZone.setOnMouseClicked(e -> { if (e.getClickCount() == 2) toggleMaximize(); });

        Button min = new Button("–");
        min.getStyleClass().add("win-btn");
        min.setTooltip(new Tooltip("Minimize"));
        min.setOnAction(e -> stage.setIconified(true));
        winMax = new Button("□");
        winMax.getStyleClass().add("win-btn");
        winMax.setTooltip(new Tooltip("Maximize / Restore"));
        winMax.setOnAction(e -> toggleMaximize());
        Button close = new Button("✕");
        close.getStyleClass().addAll("win-btn", "win-close");
        close.setTooltip(new Tooltip("Close"));
        close.setOnAction(e -> { stop(); stage.close(); });
        for (Button b : List.of(min, winMax, close)) {
            b.setMinWidth(46); b.setPrefWidth(46); b.setMaxWidth(46);
            b.setMinHeight(32); b.setPrefHeight(32); b.setMaxHeight(32);
            b.setFocusTraversable(false);
        }
        titleBar = new HBox(dragZone, min, winMax, close);
        titleBar.getStyleClass().add("title-bar");
        titleBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return titleBar;
    }

    private boolean insideTitleBar(Object target) {
        Object n = target;
        while (n instanceof javafx.scene.Node node) {
            if (node == titleBar) return true;
            n = node.getParent();
        }
        return false;
    }

    private void toggleMaximize() {
        if (!winMaximized) {
            prevX = stage.getX(); prevY = stage.getY(); prevW = stage.getWidth(); prevH = stage.getHeight();
            var vb = screenBounds();
            stage.setX(vb.getMinX()); stage.setY(vb.getMinY());
            stage.setWidth(vb.getWidth()); stage.setHeight(vb.getHeight());
            winMaximized = true;
        } else {
            stage.setX(prevX); stage.setY(prevY);
            stage.setWidth(Math.max(prevW, 960)); stage.setHeight(Math.max(prevH, 620));
            winMaximized = false;
        }
    }

    /** Edge resize for the undecorated window (6px zones, min size honored). */
    private void installResize(Scene scene) {
        scene.setOnMouseMoved(e -> {
            if (winMaximized) { scene.setCursor(javafx.scene.Cursor.DEFAULT); return; }
            double x = e.getX(), y = e.getY(), w = scene.getWidth(), h = scene.getHeight();
            boolean l = x < RESIZE_PAD, r = x > w - RESIZE_PAD, t = y < RESIZE_PAD, b = y > h - RESIZE_PAD;
            var c = javafx.scene.Cursor.DEFAULT;
            if (l && t) c = javafx.scene.Cursor.NW_RESIZE;
            else if (r && t) c = javafx.scene.Cursor.NE_RESIZE;
            else if (l && b) c = javafx.scene.Cursor.SW_RESIZE;
            else if (r && b) c = javafx.scene.Cursor.SE_RESIZE;
            else if (l) c = javafx.scene.Cursor.W_RESIZE;
            else if (r) c = javafx.scene.Cursor.E_RESIZE;
            else if (t) c = javafx.scene.Cursor.N_RESIZE;
            else if (b) c = javafx.scene.Cursor.S_RESIZE;
            scene.setCursor(c);
        });
        scene.setOnMouseDragged(e -> {
            if (winMaximized || !e.isPrimaryButtonDown()) return;
            if (insideTitleBar(e.getTarget())) return; // title-bar drag moves the window instead
            double x = e.getScreenX(), y = e.getScreenY();
            double sx = stage.getX(), sy = stage.getY(), sw = stage.getWidth(), sh = stage.getHeight();
            var cur = scene.getCursor();
            double minW = 960, minH = 620;
            if (cur == javafx.scene.Cursor.E_RESIZE || cur == javafx.scene.Cursor.NE_RESIZE || cur == javafx.scene.Cursor.SE_RESIZE)
                stage.setWidth(Math.max(minW, x - sx));
            if (cur == javafx.scene.Cursor.S_RESIZE || cur == javafx.scene.Cursor.SE_RESIZE || cur == javafx.scene.Cursor.SW_RESIZE)
                stage.setHeight(Math.max(minH, y - sy));
            if (cur == javafx.scene.Cursor.W_RESIZE || cur == javafx.scene.Cursor.NW_RESIZE || cur == javafx.scene.Cursor.SW_RESIZE) {
                double nw = Math.max(minW, sx + sw - x);
                stage.setX(sx + sw - nw); stage.setWidth(nw);
            }
            if (cur == javafx.scene.Cursor.N_RESIZE || cur == javafx.scene.Cursor.NW_RESIZE || cur == javafx.scene.Cursor.NE_RESIZE) {
                double nh = Math.max(minH, sy + sh - y);
                stage.setY(sy + sh - nh); stage.setHeight(nh);
            }
        });
    }

    private Scene scene;
    private static final String PREF_THEME = "theme";

    // Custom window chrome (lets dark mode theme the title bar — OS chrome is unthemeable).
    private HBox titleBar;
    private Button winMax;
    private double dragOffX, dragOffY;
    private boolean winMaximized = false;
    private double prevX, prevY, prevW, prevH;
    private static final int RESIZE_PAD = 6;

    private String getTheme() {
        try {
            var prefs = java.util.prefs.Preferences.userNodeForPackage(MainView.class);
            String t = prefs.get(PREF_THEME, null);
            if (t == null && prefs.getBoolean("darkMode", false)) t = "dark"; // migrate 1.x pref
            return ("dark".equals(t) || "frog".equals(t)) ? t : "default";
        } catch (Exception e) { return "default"; }
    }

    private void setTheme(String theme) {
        try {
            java.util.prefs.Preferences.userNodeForPackage(MainView.class).put(PREF_THEME, theme);
        } catch (Exception ignored) {}
        applyTheme(theme);
    }

    private String themeLabel(String theme) {
        return "dark".equals(theme) ? "Dark Mode" : "frog".equals(theme) ? "Screaming Frog" : "Default";
    }

    private void applyTheme(String theme) {
        if (scene == null) return;
        var root = scene.getRoot();
        root.getStyleClass().removeAll("dark", "sfrog");
        if ("dark".equals(theme)) root.getStyleClass().add("dark");
        else if ("frog".equals(theme)) root.getStyleClass().add("sfrog");
        setStatus("Theme: " + themeLabel(theme) + ".");
    }

    // ---------- top ----------
    private VBox buildTop() {
        MenuBar menu = new MenuBar();
        menu.getStyleClass().add("menu-bar");
        Menu file = new Menu("File");
        MenuItem exportCsv = new MenuItem("Export CSV…");
        MenuItem exportMap = new MenuItem("Generate XML Sitemap…");
        MenuItem clear = new MenuItem("Clear Results");
        MenuItem exit = new MenuItem("Exit");
        exportCsv.setOnAction(e -> doExportCsv());
        exportMap.setOnAction(e -> doExportSitemap());
        clear.setOnAction(e -> { allPages.clear(); issueCounts.clear(); pageIndex.clear(); issueFilter = ""; advancedGroups.clear(); updateAdvancedBadge(); if (issueBox != null) issueBox.setValue("All Issues"); refreshIssues(); setStatus("Cleared."); updateStats(); updateResultCount(); });
        exit.setOnAction(e -> Platform.exit());
        file.getItems().addAll(exportCsv, exportMap, new SeparatorMenuItem(), clear, exit);

        Menu crawlMenu = new Menu("Crawl");
        MenuItem mStart = new MenuItem("Start"); MenuItem mStop = new MenuItem("Stop"); MenuItem mConf = new MenuItem("Spider Configuration…");
        MenuItem mCustom = new MenuItem("Configure Custom Search…");
        mStart.setOnAction(e -> startCrawl()); mStop.setOnAction(e -> stopCrawl()); mConf.setOnAction(e -> showConfigDialog());
        mCustom.setOnAction(e -> showCustomSearchDialog());
        crawlMenu.getItems().addAll(mStart, mStop, new SeparatorMenuItem(), mConf, mCustom);

        Menu viewMenu = new Menu("View");
        ToggleGroup themeGroup = new ToggleGroup();
        RadioMenuItem themeDefault = new RadioMenuItem("Default");
        RadioMenuItem themeDark = new RadioMenuItem("Dark Mode");
        RadioMenuItem themeFrog = new RadioMenuItem("Screaming Frog");
        for (RadioMenuItem item : List.of(themeDefault, themeDark, themeFrog)) {
            item.setToggleGroup(themeGroup);
            viewMenu.getItems().add(item);
        }
        String cur = getTheme();
        if ("dark".equals(cur)) themeDark.setSelected(true);
        else if ("frog".equals(cur)) themeFrog.setSelected(true);
        else themeDefault.setSelected(true);
        themeDefault.setOnAction(e -> setTheme("default"));
        themeDark.setOnAction(e -> setTheme("dark"));
        themeFrog.setOnAction(e -> setTheme("frog"));

        Menu help = new Menu("Help");
        MenuItem checkUpdates = new MenuItem("Check for Updates");
        checkUpdates.setOnAction(e -> showUpdateCheckDialog(true));
        MenuItem about = new MenuItem("About Arachnode");
        about.setOnAction(e -> showAboutDialog());
        help.getItems().addAll(checkUpdates, new SeparatorMenuItem(), about);
        menu.getMenus().addAll(file, crawlMenu, viewMenu, help);

        // Row 1: URL + crawl actions (fixed-size buttons so labels never truncate to "S…")
        urlField = new TextField("https://example.com");
        urlField.setPromptText("Enter URL to spider — e.g. https://example.com");
        urlField.setPrefWidth(320);
        urlField.setMinWidth(180);
        urlField.getStyleClass().add("url-field");
        urlField.setTooltip(new Tooltip("Start URL. Only this host is crawled unless subdomains enabled."));

        startBtn = new Button("▶ Start"); pauseBtn = new Button("⏸ Pause"); stopBtn = new Button("⏹ Stop"); configBtn = new Button("⚙ Config");
        for (Button b : List.of(startBtn, pauseBtn, stopBtn, configBtn)) {
            b.setMinWidth(88); b.setPrefWidth(88); b.setMaxWidth(88);
            b.getStyleClass().add("btn");
        }
        startBtn.getStyleClass().add("btn-primary");
        startBtn.setDefaultButton(true);
        pauseBtn.setDisable(true); stopBtn.setDisable(true);
        startBtn.setOnAction(e -> startCrawl());
        pauseBtn.setOnAction(e -> togglePause());
        stopBtn.setOnAction(e -> stopCrawl());
        configBtn.setOnAction(e -> showConfigDialog());

        Label urlLabel = new Label("URL:");
        urlLabel.getStyleClass().add("field-label");
        urlLabel.setMinWidth(30);

        HBox row1 = new HBox(8, urlLabel, urlField, startBtn, pauseBtn, stopBtn, configBtn);
        row1.getStyleClass().add("toolbar-row");
        row1.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(urlField, Priority.ALWAYS);

        // Row 2: spider options + SF-style filters. Wraps gracefully on narrow windows.
        threadSpinner = new Spinner<>(1, 100, 10);
        threadSpinner.setPrefWidth(72); threadSpinner.setMinWidth(72);
        threadSpinner.setTooltip(new Tooltip("Crawl threads (10 default; lower if the server throttles you)"));

        maxUrlsSpinner = new Spinner<>(100, 1_000_000, 5000, 500);
        maxUrlsSpinner.setPrefWidth(84); maxUrlsSpinner.setMinWidth(84);
        maxUrlsSpinner.setEditable(true);
        maxUrlsSpinner.setTooltip(new Tooltip("Max URLs to crawl (SF free tier = 500). Set high for unlimited."));

        subBox = new CheckBox("Subdomains");
        subBox.setSelected(true);
        subBox.setMinWidth(96);
        subBox.setTooltip(new Tooltip("Include subdomains (e.g. www) in the crawl — on by default."));

        contentBox = new ComboBox<>();
        contentBox.getItems().addAll("All Content", "HTML", "Image", "CSS", "JS", "Redirect", "Error", "External", "Indexable", "Non-Indexable");
        contentBox.setValue("All Content");
        contentBox.setPrefWidth(128); contentBox.setMinWidth(110);
        contentBox.setTooltip(new Tooltip("Filter by content kind / indexability"));
        contentBox.valueProperty().addListener((o, a, b) -> applyFilter());

        statusBox = new ComboBox<>();
        statusBox.getItems().addAll("All Status", "2xx Success", "3xx Redirect", "4xx Client Error", "5xx Server Error", "0 Blocked", "✖ Errors (4xx/5xx/0)");
        statusBox.setValue("All Status");
        statusBox.setPrefWidth(128); statusBox.setMinWidth(110);
        statusBox.setTooltip(new Tooltip("Filter by HTTP status class"));
        statusBox.valueProperty().addListener((o, a, b) -> applyFilter());

        issueBox = new ComboBox<>();
        issueBox.getItems().add("All Issues");
        issueBox.setValue("All Issues");
        issueBox.setPrefWidth(170); issueBox.setMinWidth(130);
        issueBox.setTooltip(new Tooltip("Filter by specific issue (SF Overview parity)"));
        issueBox.valueProperty().addListener((o, a, b) -> {
            String v = issueBox.getValue();
            issueFilter = (v == null || "All Issues".equals(v)) ? "" : v;
            applyFilter();
        });

        clearFiltersBtn = new Button("✕ Clear");
        clearFiltersBtn.setMinWidth(76);
        clearFiltersBtn.getStyleClass().add("btn");
        clearFiltersBtn.setTooltip(new Tooltip("Clear search + content/status/issue filters"));
        clearFiltersBtn.setOnAction(e -> clearAllFilters());

        // Old-style quick search: simple Contains across URL/title/meta (SF top filter parity).
        searchField = new TextField();
        searchField.setPromptText("Filter URLs, titles, meta…");
        searchField.setPrefWidth(200); searchField.setMinWidth(140);
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        searchField.setOnAction(e -> jumpToFirstMatch());
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) { searchField.clear(); }
        });

        advancedBtn = new Button("Advanced…");
        advancedBtn.setMinWidth(96);
        advancedBtn.getStyleClass().add("btn");
        advancedBtn.setTooltip(new Tooltip("Advanced Table Search — column + operator + query with AND/OR groups"));
        advancedBtn.setOnAction(e -> showAdvancedSearchDialog());

        advancedBadge = new Label("");
        advancedBadge.getStyleClass().add("advanced-badge");
        advancedBadge.setVisible(false);
        advancedBadge.setManaged(false);

        resultCountLabel = new Label("");
        resultCountLabel.getStyleClass().add("result-count");
        resultCountLabel.setMinWidth(100);

        // FlowPane lets row 2 wrap on small screens instead of truncating buttons.
        FlowPane row2 = new FlowPane();
        row2.setHgap(8); row2.setVgap(6);
        row2.getStyleClass().add("toolbar-row");
        row2.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row2.getChildren().addAll(
                labeled("Threads:", threadSpinner), labeled("Max:", maxUrlsSpinner), subBox,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                labeled("Content:", contentBox), labeled("Status:", statusBox), labeled("Issue:", issueBox),
                clearFiltersBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                labeled("Search:", searchField), advancedBtn, advancedBadge, resultCountLabel);

        VBox toolbar = new VBox(row1, row2);
        toolbar.getStyleClass().add("toolbar");

        return new VBox(buildTitleBar(), menu, toolbar);
    }

    /** Bottom status bar (SF parity): progress + message + rate + clickable stats. */
    private HBox buildStatusRow() {
        progress = new ProgressBar(0);
        progress.getStyleClass().add("crawl-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progressText = new Label("—");
        progressText.getStyleClass().add("progress-text");
        progressText.setMouseTransparent(true);
        StackPane progressStack = new StackPane(progress, progressText);
        progressStack.setPrefWidth(220); progressStack.setMinWidth(140);
        Tooltip.install(progressStack, new Tooltip("Crawl progress: crawled / discovered"));
        StackPane.setAlignment(progressText, javafx.geometry.Pos.CENTER);
        statusLabel = new Label("Ready. Enter a URL and press Start.");
        statusLabel.getStyleClass().add("status-label");
        statOk = statSegment("✔", "stat-ok", "Show only 2xx successes (Internal tab)");
        statOk.setOnAction(e -> { statusBox.setValue("2xx Success"); selectTab("Internal"); setStatus("Showing 2xx successes — Status filter applied (✕ Clear to reset)."); });
        statRedir = statSegment("↪", "stat-redirect", "Show only 3xx redirects (Response Codes tab)");
        statRedir.setOnAction(e -> { statusBox.setValue("3xx Redirect"); selectTab("Response Codes"); setStatus("Showing 3xx redirects — Status filter applied (✕ Clear to reset)."); });
        statErr = statSegment("✖", "stat-err", "Show only errors: 4xx / 5xx / blocked (Response Codes tab)");
        statErr.setOnAction(e -> { statusBox.setValue("✖ Errors (4xx/5xx/0)"); selectTab("Response Codes"); setStatus("Showing errors — Status filter applied (✕ Clear to reset)."); });
        statExt = statSegment("↗", "stat-ext", "Show external links (External tab)");
        statExt.setOnAction(e -> { statusBox.setValue("All Status"); selectTab("External"); setStatus("Showing external links — switch tabs or ✕ Clear to reset."); });
        statTotal = statSegment("Σ", "stat-total", "Clear status filter — show everything");
        statTotal.setOnAction(e -> { statusBox.setValue("All Status"); selectTab("All"); setStatus("Status filter cleared — showing everything."); });
        statsBox = new HBox(6, statOk, statRedir, statErr, statExt, statTotal);
        statsBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        rateLabel = new Label("");
        rateLabel.getStyleClass().add("rate-label");
        rateLabel.setTooltip(new Tooltip("Crawl rate — average since start vs current (last 5s), like SF's status bar"));
        Region mid = new Region(); HBox.setHgrow(mid, Priority.ALWAYS);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        HBox statusRow = new HBox(10, progressStack, statusLabel, mid, rateLabel, statsBox);
        statusRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        statusRow.setPadding(new Insets(6, 12, 8, 12));
        statusRow.getStyleClass().add("status-row");
        return statusRow;
    }

    private HBox labeled(String text, Control c) {
        Label l = new Label(text);
        l.getStyleClass().add("field-label");
        HBox h = new HBox(5, l, c);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return h;
    }

    private Button statSegment(String icon, String cls, String tip) {
        Button b = new Button(icon + " 0");
        b.getStyleClass().addAll("stat-seg", cls);
        b.setTooltip(new Tooltip(tip + " — click to filter"));
        b.setFocusTraversable(false);
        return b;
    }

    private void clearAllFilters() {
        searchField.clear();
        contentBox.setValue("All Content");
        statusBox.setValue("All Status");
        issueBox.setValue("All Issues");
        advancedGroups.clear();
        updateAdvancedBadge();
        issueFilter = "";
        selectTab("Internal");
        setStatus("Filters cleared — showing everything.");
    }

    private void jumpToFirstMatch() {
        if (!filtered.isEmpty()) {
            table.getSelectionModel().select(0);
            table.scrollTo(0);
            table.requestFocus();
        }
    }

    private Region spacer2() { Region r = new Region(); HBox.setHgrow(r, Priority.ALWAYS); return r; }

    // ---------- center ----------
    private VBox buildCenter() {
        tabGroup = new ToggleGroup();
        chipsBox = new HBox(4);
        chipsBox.getStyleClass().add("chips-box");
        chipsBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        rebuildFilterTabs();
        ScrollPane chipScroll = new ScrollPane(chipsBox);
        chipScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        chipScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chipScroll.setFitToHeight(true);
        chipScroll.setPannable(true);
        chipScroll.getStyleClass().add("chip-scroll");
        chipScroll.setMinHeight(34);
        chipScroll.setPrefHeight(34);
        chipScroll.setMaxHeight(34);
        HBox.setHgrow(chipScroll, Priority.ALWAYS);

        table = new TableView<>();
        sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.setPlaceholder(new Label("No URLs yet — start a crawl above."));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setMinHeight(200);
        table.setTableMenuButtonVisible(true);
        refreshColumns();

        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(CrawledPage p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().removeAll("row-err", "row-client", "row-redirect", "row-blocked");
                if (empty || p == null) { setStyle(""); return; }
                int c = p.getStatusCode();
                if (c == 0) getStyleClass().add("row-blocked");
                else if (c >= 500) getStyleClass().add("row-err");
                else if (c >= 400) getStyleClass().add("row-client");
                else if (c >= 300) getStyleClass().add("row-redirect");
            }
        });

        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showDetails(b));
        table.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) openInBrowser(table.getSelectionModel().getSelectedItem().getUrl()); });

        table.setContextMenu(buildMainContextMenu());

        detailsBox = new VBox(10);
        detailsBox.setPadding(new Insets(12));
        detailsBox.getStyleClass().add("details-box");
        showDetailsEmpty();
        detailsScroll = new ScrollPane(detailsBox);
        detailsScroll.setFitToWidth(true);
        detailsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailsScroll.getStyleClass().add("detail-scroll");
        Tab detailTab = new Tab("URL Details", detailsScroll);
        detailTab.setClosable(false);

        outTable = makeOutlinkTable();
        outTab = new Tab("Outlinks", outTable);
        outTab.setClosable(false);
        inTable = makeInlinkTable();
        inTab = new Tab("Inlinks", inTable);
        inTab.setClosable(false);

        imgTable = makeImageTable();
        imgTab = new Tab("Image Details", imgTable);
        imgTab.setClosable(false);

        resFiltered = new FilteredList<>(allPages, p ->
                !"HTML".equals(p.getContentKind()) && !"Redirect".equals(p.getContentKind())
                        && !"External".equals(p.getContentKind()));
        resTable = makeResourceTable();
        resSorted = new SortedList<>(resFiltered);
        resSorted.comparatorProperty().bind(resTable.comparatorProperty());
        resTable.setItems(resSorted);
        resTab = new Tab("Resources", resTable);
        resTab.setClosable(false);

        VBox serpBox = new VBox(4);
        serpBox.setPadding(new Insets(14));
        serpBox.getStyleClass().add("serp-box");
        serpTitle = new Label("Select a URL to preview its search snippet.");
        serpTitle.getStyleClass().add("serp-title");
        serpTitle.setWrapText(true);
        serpUrl = new Label("");
        serpUrl.getStyleClass().add("serp-url");
        serpDesc = new Label("");
        serpDesc.getStyleClass().add("serp-desc");
        serpDesc.setWrapText(true);
        serpBox.getChildren().addAll(serpTitle, serpUrl, serpDesc);
        ScrollPane serpScroll = new ScrollPane(serpBox);
        serpScroll.setFitToWidth(true);
        serpScroll.getStyleClass().add("detail-scroll");
        serpTab = new Tab("SERP Snippet", serpScroll);
        serpTab.setClosable(false);

        sourceArea = new TextArea();
        sourceArea.setEditable(false);
        sourceArea.getStyleClass().add("source-area");
        sourceArea.setPromptText("Select an HTML URL to view the start of its source.");
        sourceTab = new Tab("View Source", sourceArea);
        sourceTab.setClosable(false);

        headersGrid = new GridPane();
        headersGrid.setHgap(16); headersGrid.setVgap(6);
        headersGrid.setPadding(new Insets(12));
        ScrollPane headScroll = new ScrollPane(headersGrid);
        headScroll.setFitToWidth(true);
        headScroll.getStyleClass().add("detail-scroll");
        headersTab = new Tab("HTTP Headers", headScroll);
        headersTab.setClosable(false);

        dupTable = makeDuplicateTable();
        dupTab = new Tab("Duplicate Details", dupTable);
        dupTab.setClosable(false);

        cookiesArea = new TextArea();
        cookiesArea.setEditable(false);
        cookiesArea.getStyleClass().add("source-area");
        cookiesArea.setPromptText("Select a URL to see cookies set by its response.");
        cookiesTab = new Tab("Cookies", cookiesArea);
        cookiesTab.setClosable(false);

        structCount = new Label("");
        structCount.getStyleClass().add("panel-hint");
        structArea = new TextArea();
        structArea.setEditable(false);
        structArea.getStyleClass().add("source-area");
        structArea.setPromptText("Select an HTML URL to see its first JSON-LD block.");
        VBox structBox = new VBox(6, structCount, structArea);
        structBox.setPadding(new Insets(8));
        VBox.setVgrow(structArea, Priority.ALWAYS);
        structTab = new Tab("Structured Data Details", structBox);
        structTab.setClosable(false);

        detailTabs = new TabPane(detailTab, inTab, outTab, imgTab, resTab, serpTab, sourceTab, headersTab, cookiesTab, structTab, dupTab);
        detailTabs.getStyleClass().add("detail-tabs");
        detailTabs.setPrefHeight(230);
        detailTabs.setMinHeight(190);
        detailTabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b == sourceTab) {
                CrawledPage sel = table.getSelectionModel().getSelectedItem();
                if (sel != null) ensureSource(sel);
            }
        });

        applyFilter();

        MenuButton tabsMenu = new MenuButton("Tabs");
        tabsMenu.getStyleClass().addAll("btn", "tabs-menu");
        tabsMenu.setMinWidth(96); tabsMenu.setPrefWidth(96); tabsMenu.setMaxWidth(96);
        tabsMenu.setMinHeight(28);
        tabsMenu.setTooltip(new Tooltip("Configure Tabs — show/hide tabs, or jump to one that overflowed off-screen"));
        rebuildTabsMenu(tabsMenu);
        HBox tabBar = new HBox(8, chipScroll, tabsMenu);
        tabBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setMargin(tabsMenu, new Insets(0, 0, 0, 0));
        tabBar.getStyleClass().add("tab-bar");

        VBox box = new VBox(tabBar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.getStyleClass().add("center-box");
        return box;
    }

    private final Set<String> visibleTabs = new LinkedHashSet<>(TABS);

    /** SF-style Configure Tabs: checkbox per tab (hide/show) + click label jumps to it. */
    private void rebuildTabsMenu(MenuButton menu) {
        menu.getItems().clear();
        MenuItem reset = new MenuItem("⟳ Reset Tabs");
        reset.setOnAction(e -> {
            visibleTabs.clear();
            visibleTabs.addAll(TABS);
            rebuildFilterTabs();
            rebuildTabsMenu(menu);
        });
        menu.getItems().add(reset);
        menu.getItems().add(new SeparatorMenuItem());
        for (String name : TABS) {
            CheckMenuItem item = new CheckMenuItem(name);
            item.setSelected(visibleTabs.contains(name));
            item.setOnAction(e -> {
                if (item.isSelected()) visibleTabs.add(name);
                else visibleTabs.remove(name);
                if (visibleTabs.isEmpty()) { visibleTabs.add(name); item.setSelected(true); return; }
                rebuildFilterTabs();
                if (!visibleTabs.contains(currentFilter)) selectTab(visibleTabs.iterator().next());
                else selectTab(currentFilter);
            });
            menu.getItems().add(item);
        }
    }

    private void rebuildFilterTabs() {
        chipsBox.getChildren().clear();
        chipMap.clear();
        for (String name : TABS) {
            if (!visibleTabs.contains(name)) continue;
            ToggleButton chip = new ToggleButton(name);
            chip.setToggleGroup(tabGroup);
            chip.getStyleClass().add("filter-chip");
            chip.setTooltip(new Tooltip(name + " — click to filter the table"));
            chip.setOnAction(e -> selectTab(name));
            chipsBox.getChildren().add(chip);
            chipMap.put(name, chip);
        }
        ToggleButton cur = chipMap.get(currentFilter);
        if (cur != null) tabGroup.selectToggle(cur);
    }

    private static final Set<String> NUMERIC_PROPS = Set.of("statusCode", "titleLength", "metaDescLength",
            "h1Count", "h2Count", "wordCount", "depth", "inlinks", "outlinks", "imageCount", "imagesMissingAlt",
            "responseTimeMs", "sizeBytes", "hreflangCount", "urlLength", "scriptCount", "scriptSrcCount",
            "jsonLdCount", "nofollowCount", "followCount");

    /** Shared header setup: plain text header (native ▲/▼ arrow shows when sorted).
     *  NOTE: no custom graphic here — an earlier ↕ graphic likely swallowed the
     *  first header click, forcing a double-click to sort. */
    private static void markSortable(TableColumn<?, ?> col, String title) {
        col.setText(title);
        col.setGraphic(null);
        col.setSortable(true);
    }

    private void addCol(String title, String prop, int width) {
        if (NUMERIC_PROPS.contains(prop)) {
            TableColumn<CrawledPage, Number> c = new TableColumn<>();
            c.setCellValueFactory(new PropertyValueFactory<>(prop));
            c.setPrefWidth(width);
            markSortable(c, title);
            // Null-safe numeric sort (missing values go last).
            c.setComparator((a, b) -> {
                double x = a == null ? Double.POSITIVE_INFINITY : a.doubleValue();
                double y = b == null ? Double.POSITIVE_INFINITY : b.doubleValue();
                return Double.compare(x, y);
            });
            table.getColumns().add(c);
        } else {
            TableColumn<CrawledPage, String> c = new TableColumn<>();
            c.setCellValueFactory(new PropertyValueFactory<>(prop));
            c.setPrefWidth(width);
            markSortable(c, title);
            // Case-insensitive, null-safe text sort (Screaming Frog sorts titles/meta naturally).
            c.setComparator((a, b) -> {
                if (a == b) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                return String.CASE_INSENSITIVE_ORDER.compare(a, b);
            });
            table.getColumns().add(c);
        }
    }

    // ---------- SF-style context menus ----------
    private List<CrawledPage> selectedPages() {
        var sel = table.getSelectionModel().getSelectedItems();
        return sel == null || sel.isEmpty() ? List.of() : new ArrayList<>(sel);
    }

    private void copyText(String s, String statusMsg) {
        if (s == null || s.isEmpty()) { setStatus("Nothing to copy."); return; }
        ClipboardContent cc = new ClipboardContent();
        cc.putString(s);
        Clipboard.getSystemClipboard().setContent(cc);
        setStatus(statusMsg);
    }

    private String join(java.util.function.Function<CrawledPage, String> f, List<CrawledPage> pages) {
        StringBuilder sb = new StringBuilder();
        for (CrawledPage p : pages) {
            String v = f.apply(p);
            if (v != null) sb.append(v).append("\n");
        }
        return sb.toString().trim();
    }

    private ContextMenu buildMainContextMenu() {
        MenuItem open = new MenuItem("Open in Browser");
        open.setOnAction(e -> { var p = table.getSelectionModel().getSelectedItem(); if (p != null) openInBrowser(p.getUrl()); });

        MenuItem openSource = new MenuItem("View Page Source");
        openSource.setOnAction(e -> { var p = table.getSelectionModel().getSelectedItem(); if (p != null) openInBrowser("view-source:" + p.getUrl()); });

        Menu copyMenu = new Menu("Copy");
        MenuItem cUrl = new MenuItem("URL(s)");
        cUrl.setOnAction(e -> copyText(join(CrawledPage::getUrl, selectedPages()), "Copied URL(s)."));
        MenuItem cTitle = new MenuItem("Title(s)");
        cTitle.setOnAction(e -> copyText(join(CrawledPage::getTitle, selectedPages()), "Copied title(s)."));
        MenuItem cMeta = new MenuItem("Meta Description(s)");
        cMeta.setOnAction(e -> copyText(join(CrawledPage::getMetaDescription, selectedPages()), "Copied meta description(s)."));
        MenuItem cH1 = new MenuItem("H1(s)");
        cH1.setOnAction(e -> copyText(join(CrawledPage::getH1, selectedPages()), "Copied H1(s)."));
        MenuItem cCanon = new MenuItem("Canonical(s)");
        cCanon.setOnAction(e -> copyText(join(CrawledPage::getCanonical, selectedPages()), "Copied canonical(s)."));
        MenuItem cIssues = new MenuItem("Issue(s)");
        cIssues.setOnAction(e -> copyText(join(CrawledPage::getIssues, selectedPages()), "Copied issue(s)."));
        MenuItem cRow = new MenuItem("Row(s) as TSV");
        cRow.setOnAction(e -> {
            var pages = selectedPages(); if (pages.isEmpty()) return;
            StringBuilder sb = new StringBuilder("URL\tStatus\tTitle\tMeta\tH1\tCanonical\tIssues\n");
            for (CrawledPage p : pages)
                sb.append(p.getUrl()).append("\t").append(p.getStatusCode()).append("\t")
                  .append(tab(p.getTitle())).append("\t").append(tab(p.getMetaDescription())).append("\t")
                  .append(tab(p.getH1())).append("\t").append(tab(p.getCanonical())).append("\t")
                  .append(tab(p.getIssues())).append("\n");
            copyText(sb.toString().trim(), "Copied " + pages.size() + " row(s) as TSV.");
        });
        copyMenu.getItems().addAll(cUrl, cTitle, cMeta, cH1, cCanon, cIssues, new SeparatorMenuItem(), cRow);

        Menu filterMenu = new Menu("Filter");
        MenuItem fStatus = new MenuItem("Show only this Status");
        fStatus.setOnAction(e -> {
            var p = table.getSelectionModel().getSelectedItem(); if (p == null) return;
            int sc = p.getStatusCode();
            statusBox.setValue(sc >= 200 && sc < 300 ? "2xx Success" : sc >= 300 && sc < 400 ? "3xx Redirect" : sc >= 400 && sc < 500 ? "4xx Client Error" : sc >= 500 ? "5xx Server Error" : "0 Blocked");
        });
        MenuItem fKind = new MenuItem("Show only this Content Kind");
        fKind.setOnAction(e -> {
            var p = table.getSelectionModel().getSelectedItem(); if (p == null) return;
            String k = p.getContentKind();
            for (String opt : contentBox.getItems()) if (opt.equalsIgnoreCase(k)) { contentBox.setValue(opt); return; }
            if ("HTML".equals(k)) contentBox.setValue("HTML");
        });
        MenuItem fClear = new MenuItem("Clear all filters");
        fClear.setOnAction(e -> clearAllFilters());
        filterMenu.getItems().addAll(fStatus, fKind, new SeparatorMenuItem(), fClear);

        MenuItem inlinks = new MenuItem("Show Inlinks");
        inlinks.setOnAction(e -> { var p = table.getSelectionModel().getSelectedItem(); if (p != null) { showDetails(p); detailTabs.getSelectionModel().select(inTab); } });
        MenuItem outlinks = new MenuItem("Show Outlinks");
        outlinks.setOnAction(e -> { var p = table.getSelectionModel().getSelectedItem(); if (p != null) { showDetails(p); detailTabs.getSelectionModel().select(outTab); } });
        MenuItem followRedir = new MenuItem("Follow Redirect Target");
        followRedir.setOnAction(e -> {
            var p = table.getSelectionModel().getSelectedItem();
            if (p != null && !p.getRedirectUri().isEmpty()) jumpTo(p.getRedirectUri());
            else setStatus("Selected row has no redirect target.");
        });

        MenuItem exportSel = new MenuItem("Export Selected to CSV…");
        exportSel.setOnAction(e -> {
            var pages = selectedPages(); if (pages.isEmpty()) { setStatus("Nothing selected to export."); return; }
            FileChooser fc = new FileChooser(); fc.setInitialFileName("arachnode-selected.csv");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            File f = fc.showSaveDialog(stage); if (f == null) return;
            try { CrawlExporter.toCsv(pages, f.toPath()); setStatus("Exported " + pages.size() + " selected rows: " + f); }
            catch (Exception ex) {
                var a = new Alert(Alert.AlertType.ERROR);
                a.setTitle("Export Failed");
                a.setHeaderText(null);
                a.setContentText("Export failed: " + ex.getMessage());
                brandDialog(a);
                a.showAndWait();
            }
        });

        MenuItem copyDiag = new MenuItem("Copy Error Details");
        copyDiag.setOnAction(e -> {
            var p = table.getSelectionModel().getSelectedItem(); if (p == null) return;
            copyText("URL: " + p.getUrl() + "\nStatus: " + p.getStatusCode() + " " + p.getStatusText()
                    + "\nAttempts: " + p.getFetchAttempts() + "  Proto: " + p.getFetchProto()
                    + "\nError: " + (p.getErrorDetail().isEmpty() ? "(none recorded)" : p.getErrorDetail()),
                    "Copied error details.");
        });

        MenuItem remove = new MenuItem("Remove from results");
        remove.setOnAction(e -> {
            var pages = selectedPages(); if (pages.isEmpty()) return;
            allPages.removeAll(pages);
            for (CrawledPage p : pages) pageIndex.remove(p.getUrl());
            rebuildIssueCounts();
            applyFilter();
            setStatus("Removed " + pages.size() + " row(s) from results (display only).");
        });

        return new ContextMenu(open, openSource, new SeparatorMenuItem(), copyMenu, filterMenu,
                new SeparatorMenuItem(), inlinks, outlinks, followRedir, copyDiag,
                new SeparatorMenuItem(), exportSel, remove);
    }

    private String tab(String s) { return s == null ? "" : s.replace("\t", " ").replace("\n", " "); }

    private ContextMenu buildLinkContextMenu(TableView<LinkRef> tv, boolean isInlink) {
        MenuItem open = new MenuItem("Open in Browser");
        open.setOnAction(e -> {
            var l = tv.getSelectionModel().getSelectedItem(); if (l == null) return;
            openInBrowser(isInlink ? l.getSource() : l.getTarget());
        });
        MenuItem copy = new MenuItem(isInlink ? "Copy Source URL" : "Copy Target URL");
        copy.setOnAction(e -> {
            var l = tv.getSelectionModel().getSelectedItem(); if (l == null) return;
            copyText(isInlink ? l.getSource() : l.getTarget(), "Copied link URL.");
        });
        MenuItem jump = new MenuItem("Jump to in results");
        jump.setOnAction(e -> {
            var l = tv.getSelectionModel().getSelectedItem(); if (l == null) return;
            jumpTo(isInlink ? l.getSource() : l.getTarget());
        });
        return new ContextMenu(open, copy, jump);
    }

    /** Per-tab columns like Screaming Frog — same data, focused views. */
    private void refreshColumns() {
        if (table == null) return;
        table.getSortOrder().clear();
        table.getColumns().clear();
        switch (currentFilter) {
            case "All" -> {
                addCol("Address", "url", 300); addCol("Content", "contentKind", 85);
                addCol("Status", "statusCode", 70); addCol("Indexable", "indexable", 95);
                addCol("Content Type", "contentType", 170); addCol("Title", "title", 200); addCol("Meta Desc", "metaDescription", 200);
                addCol("H1", "h1", 150); addCol("Canonical", "canonical", 180);
                addCol("Depth", "depth", 60); addCol("Inlinks", "inlinks", 65);
                addCol("Outlinks", "outlinks", 70); addCol("Resp ms", "responseTimeMs", 75);
                addCol("Issues", "issues", 300);
            }
            case "External" -> {
                addCol("Address", "url", 380); addCol("Content", "contentKind", 90);
                addCol("Status", "statusText", 180); addCol("Inlinks", "inlinks", 70);
                addCol("Depth", "depth", 60); addCol("Issues", "issues", 300);
            }
            case "Security" -> {
                addCol("Address", "url", 320); addCol("Status", "statusCode", 70);
                addCol("Indexable", "indexable", 100); addCol("Content", "contentKind", 85);
                addCol("Meta Robots", "metaRobots", 130); addCol("X-Robots", "xRobots", 130);
                addCol("Canonical", "canonical", 220); addCol("Issues", "issues", 320);
            }
            case "Response Codes" -> {
                addCol("Address", "url", 340); addCol("Status", "statusCode", 70);
                addCol("Status Text", "statusText", 160); addCol("Content", "contentKind", 85);
                addCol("Content Type", "contentType", 170); addCol("Redirect URI", "redirectUri", 300);
                addCol("Redirect Chain", "redirectChain", 260); addCol("Depth", "depth", 60);
                addCol("Inlinks", "inlinks", 65); addCol("Resp ms", "responseTimeMs", 75);
                addCol("Issues", "issues", 280);
            }
            case "Page Titles" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Title", "title", 320); addCol("Title Len", "titleLength", 70);
                addCol("Words", "wordCount", 65); addCol("Issues", "issues", 340);
            }
            case "Meta Description" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Meta Desc", "metaDescription", 340); addCol("Meta Len", "metaDescLength", 70);
                addCol("Meta Keywords", "metaKeywords", 160); addCol("Issues", "issues", 320);
            }
            case "H1" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("H1", "h1", 300); addCol("H1 #", "h1Count", 55);
                addCol("Words", "wordCount", 65); addCol("Issues", "issues", 320);
            }
            case "H2" -> {
                addCol("Address", "url", 320); addCol("Status", "statusCode", 70);
                addCol("H1", "h1", 220); addCol("H2 #", "h2Count", 55);
                addCol("Words", "wordCount", 65); addCol("Issues", "issues", 320);
            }
            case "Images" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Title", "title", 180); addCol("Images", "imageCount", 65);
                addCol("No Alt", "imagesMissingAlt", 60); addCol("Words", "wordCount", 65);
                addCol("Issues", "issues", 320);
            }
            case "Directives" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Indexable", "indexable", 95); addCol("Meta Robots", "metaRobots", 150);
                addCol("X-Robots", "xRobots", 150); addCol("Canonical", "canonical", 240);
                addCol("Issues", "issues", 300);
            }
            case "Canonicals" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Canonical", "canonical", 320); addCol("Indexable", "indexable", 95);
                addCol("Title", "title", 180); addCol("Issues", "issues", 300);
            }
            case "Hreflang" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Hreflang #", "hreflangCount", 80); addCol("Hreflang", "hreflangVals", 200);
                addCol("Canonical", "canonical", 220); addCol("Indexable", "indexable", 95);
                addCol("Issues", "issues", 280);
            }
            case "URL" -> {
                addCol("Address", "url", 320); addCol("Status", "statusCode", 70);
                addCol("Scheme", "urlScheme", 70); addCol("Host", "urlHost", 180);
                addCol("Path", "urlPath", 240); addCol("Query", "urlQuery", 180);
                addCol("URL Len", "urlLength", 70); addCol("Depth", "depth", 55);
                addCol("Issues", "issues", 280);
            }
            case "Meta Keywords" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Meta Keywords", "metaKeywords", 320); addCol("Title", "title", 200);
                addCol("Issues", "issues", 300);
            }
            case "Content" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Words", "wordCount", 70); addCol("Title", "title", 200);
                addCol("H1", "h1", 160); addCol("Hash", "contentHash", 120);
                addCol("Size", "sizeBytes", 80); addCol("Issues", "issues", 300);
            }
            case "Pagination" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Rel Prev", "linkPrev", 280); addCol("Rel Next", "linkNext", 280);
                addCol("Title", "title", 180); addCol("Issues", "issues", 280);
            }
            case "JavaScript" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Scripts", "scriptCount", 65); addCol("Ext Scripts", "scriptSrcCount", 80);
                addCol("Content", "contentKind", 85); addCol("Title", "title", 180);
                addCol("Issues", "issues", 280);
            }
            case "Links" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Outlinks", "outlinks", 70); addCol("Follow", "followCount", 60);
                addCol("Nofollow", "nofollowCount", 70); addCol("Inlinks", "inlinks", 65);
                addCol("Title", "title", 180); addCol("Issues", "issues", 280);
            }
            case "AMP" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("AMP URL", "ampUrl", 320); addCol("Title", "title", 200);
                addCol("Issues", "issues", 300);
            }
            case "Structured Data" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("JSON-LD #", "jsonLdCount", 80); addCol("Title", "title", 200);
                addCol("Issues", "issues", 300);
            }
            case "Custom Search" -> {
                addCol("Address", "url", 340); addCol("Status", "statusCode", 70);
                addCol("Matches", "customMatches", 380); addCol("Title", "title", 200);
                addCol("Issues", "issues", 260);
            }
            case "Duplicates" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Title", "title", 200); addCol("Meta Desc", "metaDescription", 200);
                addCol("Hash", "contentHash", 120); addCol("Words", "wordCount", 65);
                addCol("Issues", "issues", 320);
            }
            case "Issues" -> {
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Content", "contentKind", 85); addCol("Title", "title", 180);
                addCol("Meta Desc", "metaDescription", 180); addCol("H1", "h1", 140);
                addCol("Redirect URI", "redirectUri", 200); addCol("Issues", "issues", 360);
            }
            default -> { // Internal
                addCol("Address", "url", 300); addCol("Status", "statusCode", 70);
                addCol("Content Type", "contentType", 170); addCol("Title", "title", 200); addCol("Title Len", "titleLength", 70);
                addCol("Meta Desc", "metaDescription", 200); addCol("Meta Len", "metaDescLength", 70);
                addCol("H1", "h1", 160); addCol("H1 #", "h1Count", 55); addCol("H2 #", "h2Count", 55);
                addCol("Words", "wordCount", 65); addCol("Canonical", "canonical", 180);
                addCol("Robots", "metaRobots", 110); addCol("Depth", "depth", 55);
                addCol("Inlinks", "inlinks", 60); addCol("Outlinks", "outlinks", 65);
                addCol("Images", "imageCount", 60); addCol("No Alt", "imagesMissingAlt", 55);
                addCol("Resp ms", "responseTimeMs", 70); addCol("Issues", "issues", 300);
            }
        }
    }

    private TableView<LinkRef> makeOutlinkTable() {
        TableView<LinkRef> tv = new TableView<>();
        tv.setPlaceholder(new Label("Select a URL above to see outlinks."));
        TableColumn<LinkRef, String> to = new TableColumn<>();
        to.setCellValueFactory(new PropertyValueFactory<>("target")); to.setPrefWidth(460);
        markSortable(to, "To (target)");
        to.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<LinkRef, String> anchor = new TableColumn<>();
        anchor.setCellValueFactory(new PropertyValueFactory<>("anchor")); anchor.setPrefWidth(300);
        markSortable(anchor, "Anchor Text");
        anchor.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<LinkRef, String> rel = new TableColumn<>();
        rel.setCellValueFactory(new PropertyValueFactory<>("rel")); rel.setPrefWidth(100);
        markSortable(rel, "Rel");
        rel.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<LinkRef, String> st = new TableColumn<>();
        st.setCellValueFactory(cd -> new SimpleStringProperty(statusOf(cd.getValue().getTarget()))); st.setPrefWidth(180);
        markSortable(st, "Status");
        st.setComparator(String.CASE_INSENSITIVE_ORDER);
        tv.getColumns().addAll(to, anchor, rel, st);
        tv.setContextMenu(buildLinkContextMenu(tv, false));
        tv.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tv.getSelectionModel().getSelectedItem() != null)
                jumpTo(tv.getSelectionModel().getSelectedItem().getTarget());
        });
        return tv;
    }

    private TableView<LinkRef> makeInlinkTable() {
        TableView<LinkRef> tv = new TableView<>();
        tv.setPlaceholder(new Label("Select a URL above to see inlinks."));
        TableColumn<LinkRef, String> from = new TableColumn<>();
        from.setCellValueFactory(new PropertyValueFactory<>("source")); from.setPrefWidth(460);
        markSortable(from, "From (source)");
        from.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<LinkRef, String> anchor = new TableColumn<>();
        anchor.setCellValueFactory(new PropertyValueFactory<>("anchor")); anchor.setPrefWidth(300);
        markSortable(anchor, "Anchor Text");
        anchor.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<LinkRef, String> rel = new TableColumn<>();
        rel.setCellValueFactory(new PropertyValueFactory<>("rel")); rel.setPrefWidth(100);
        markSortable(rel, "Rel");
        rel.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<LinkRef, String> st = new TableColumn<>();
        st.setCellValueFactory(cd -> new SimpleStringProperty(statusOf(cd.getValue().getSource()))); st.setPrefWidth(180);
        markSortable(st, "Status");
        st.setComparator(String.CASE_INSENSITIVE_ORDER);
        tv.getColumns().addAll(from, anchor, rel, st);
        tv.setContextMenu(buildLinkContextMenu(tv, true));
        tv.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tv.getSelectionModel().getSelectedItem() != null)
                jumpTo(tv.getSelectionModel().getSelectedItem().getSource());
        });
        return tv;
    }

    private TableView<com.arachnode.model.ImageRef> makeImageTable() {
        TableView<com.arachnode.model.ImageRef> tv = new TableView<>();
        tv.setPlaceholder(new Label("Select a URL above to see its images."));
        TableColumn<com.arachnode.model.ImageRef, String> src = new TableColumn<>();
        src.setCellValueFactory(new PropertyValueFactory<>("src")); src.setPrefWidth(480);
        markSortable(src, "Image Source");
        src.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<com.arachnode.model.ImageRef, String> alt = new TableColumn<>();
        alt.setCellValueFactory(new PropertyValueFactory<>("alt")); alt.setPrefWidth(300);
        markSortable(alt, "Alt Text");
        alt.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<com.arachnode.model.ImageRef, String> st = new TableColumn<>();
        st.setCellValueFactory(new PropertyValueFactory<>("altStatus")); st.setPrefWidth(100);
        markSortable(st, "Alt Status");
        st.setComparator(String.CASE_INSENSITIVE_ORDER);
        tv.getColumns().addAll(src, alt, st);
        tv.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        MenuItem open = new MenuItem("Open image in Browser");
        open.setOnAction(e -> { var i = tv.getSelectionModel().getSelectedItem(); if (i != null) openInBrowser(i.getSrc()); });
        MenuItem copy = new MenuItem("Copy image URL");
        copy.setOnAction(e -> { var i = tv.getSelectionModel().getSelectedItem(); if (i != null) copyText(i.getSrc(), "Copied image URL."); });
        tv.setContextMenu(new ContextMenu(open, copy));
        return tv;
    }

    private TableView<CrawledPage> makeResourceTable() {
        TableView<CrawledPage> tv = new TableView<>();
        tv.setPlaceholder(new Label("Linked resources (images, CSS, JS, files) appear here during the crawl."));
        TableColumn<CrawledPage, String> addr = new TableColumn<>();
        addr.setCellValueFactory(new PropertyValueFactory<>("url")); addr.setPrefWidth(380);
        markSortable(addr, "Address");
        addr.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<CrawledPage, String> kind = new TableColumn<>();
        kind.setCellValueFactory(new PropertyValueFactory<>("contentKind")); kind.setPrefWidth(80);
        markSortable(kind, "Content");
        kind.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<CrawledPage, Number> sc = new TableColumn<>();
        sc.setCellValueFactory(new PropertyValueFactory<>("statusCode")); sc.setPrefWidth(65);
        markSortable(sc, "Status");
        sc.setComparator((a, b) -> Double.compare(a == null ? Double.POSITIVE_INFINITY : a.doubleValue(),
                b == null ? Double.POSITIVE_INFINITY : b.doubleValue()));
        TableColumn<CrawledPage, String> type = new TableColumn<>();
        type.setCellValueFactory(new PropertyValueFactory<>("contentType")); type.setPrefWidth(200);
        markSortable(type, "Content Type");
        type.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<CrawledPage, Number> size = new TableColumn<>();
        size.setCellValueFactory(new PropertyValueFactory<>("sizeBytes")); size.setPrefWidth(90);
        markSortable(size, "Size");
        size.setComparator((a, b) -> Double.compare(a == null ? Double.POSITIVE_INFINITY : a.doubleValue(),
                b == null ? Double.POSITIVE_INFINITY : b.doubleValue()));
        tv.getColumns().addAll(addr, kind, sc, type, size);
        tv.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tv.getSelectionModel().getSelectedItem() != null)
                openInBrowser(tv.getSelectionModel().getSelectedItem().getUrl());
        });
        return tv;
    }

    private TableView<CrawledPage> makeDuplicateTable() {
        TableView<CrawledPage> tv = new TableView<>();
        tv.setPlaceholder(new Label("Select a URL to see pages sharing its content, title or meta description."));
        TableColumn<CrawledPage, String> addr = new TableColumn<>();
        addr.setCellValueFactory(new PropertyValueFactory<>("url")); addr.setPrefWidth(380);
        markSortable(addr, "Duplicate URL");
        addr.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<CrawledPage, String> how = new TableColumn<>();
        how.setCellValueFactory(cd -> new SimpleStringProperty(duplicateMatchKind(
                table.getSelectionModel().getSelectedItem(), cd.getValue())));
        how.setPrefWidth(220);
        markSortable(how, "Match Type");
        how.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<CrawledPage, String> title = new TableColumn<>();
        title.setCellValueFactory(new PropertyValueFactory<>("title")); title.setPrefWidth(280);
        markSortable(title, "Title");
        title.setComparator(String.CASE_INSENSITIVE_ORDER);
        tv.getColumns().addAll(addr, how, title);
        tv.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tv.getSelectionModel().getSelectedItem() != null)
                jumpTo(tv.getSelectionModel().getSelectedItem().getUrl());
        });
        return tv;
    }

    private String duplicateMatchKind(CrawledPage sel, CrawledPage cand) {
        if (sel == null || cand == null) return "";
        List<String> kinds = new ArrayList<>();
        if (!sel.getContentHash().isEmpty() && sel.getContentHash().equals(cand.getContentHash())) kinds.add("Content");
        if (!sel.getTitle().isEmpty() && sel.getTitle().equalsIgnoreCase(cand.getTitle())) kinds.add("Title");
        if (!sel.getMetaDescription().isEmpty() && sel.getMetaDescription().equalsIgnoreCase(cand.getMetaDescription())) kinds.add("Meta");
        return String.join(" + ", kinds);
    }

    private VBox buildIssuesPanel() {
        issuesView = new ListView<>();
        issuesView.setMinWidth(200);
        issuesView.setPrefWidth(300);
        issuesView.setPlaceholder(new Label("Issue overview appears during crawl."));
        issuesView.getStyleClass().add("issues-list");
        issuesView.setTooltip(new Tooltip("Click an issue to filter. Right-click to clear."));
        issuesView.setOnMouseClicked(e -> {
            String sel = issuesView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                String key = sel.replaceFirst("^\\d+\\s*[×x]\\s*", "").trim();
                issueFilter = key;
                issueBox.setValue(key);
                selectTab("Issues");
                setStatus("Filtered to issue: " + key + " — Clear to reset.");
            }
        });
        MenuItem clearIssue = new MenuItem("Clear issue filter");
        clearIssue.setOnAction(e -> { issueFilter = ""; issueBox.setValue("All Issues"); applyFilter(); });
        issuesView.setContextMenu(new ContextMenu(clearIssue));
        Label title = new Label("Issues Overview");
        title.getStyleClass().add("panel-title");
        title.setMaxWidth(Double.MAX_VALUE);
        Label hint = new Label("Internal URLs only — click an issue to filter.");
        hint.getStyleClass().add("panel-hint");
        hint.setWrapText(true);
        VBox box = new VBox(6, title, hint, issuesView);
        box.setPadding(new Insets(10, 10, 10, 12));
        box.setMinWidth(220);
        box.setPrefWidth(300);
        box.getStyleClass().add("issues-panel");
        VBox.setVgrow(issuesView, Priority.ALWAYS);
        return box;
    }

    private void selectTab(String name) {
        if (name == null || !visibleTabs.contains(name)) return;
        currentFilter = name;
        ToggleButton chip = chipMap.get(name);
        if (chip != null && tabGroup.getSelectedToggle() != chip) tabGroup.selectToggle(chip);
        if (!"Issues".equals(currentFilter) && !issueFilter.isEmpty()) {
            issueFilter = "";
            if (issueBox != null) issueBox.setValue("All Issues");
        }
        refreshColumns();
        applyFilter();
        if ("Custom Search".equals(currentFilter) && customQueries.isEmpty())
            setStatus("Custom Search has no queries — use Crawl > Configure Custom Search… to add up to 5.");
    }

    private String statusOf(String u) {
        if (u == null || u.isEmpty()) return "—";
        CrawledPage p = pageIndex.get(u);
        if (p == null) return "—";
        if ("External".equals(p.getContentKind())) return "External";
        return p.getStatusCode() + " " + p.getStatusText();
    }

    private void jumpTo(String url) {
        if (url == null || url.isEmpty()) return;
        // NOTE: table shows the sorted view, so navigate by view index, not filtered index.
        var items = table.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getUrl().equals(url)) {
                table.getSelectionModel().select(i);
                table.scrollTo(i);
                return;
            }
        }
        // Not in current filter — drop to All tab and retry (SF-like navigation).
        searchField.clear();
        issueFilter = "";
        contentBox.setValue("All Content");
        statusBox.setValue("All Status");
        issueBox.setValue("All Issues");
        selectTab("All");
        items = table.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getUrl().equals(url)) {
                table.getSelectionModel().select(i);
                table.scrollTo(i);
                return;
            }
        }
        setStatus("Linked URL is not crawled yet (external or queued): " + url);
    }

    private VBox buildBottom() {
        detailTabs.setMinHeight(160);
        detailTabs.setPrefHeight(210);
        VBox box = new VBox(detailTabs, buildStatusRow());
        box.setMinHeight(200);
        box.getStyleClass().add("bottom-box");
        VBox.setVgrow(detailTabs, Priority.ALWAYS);
        return box;
    }

    private void showConfigDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Spider Configuration");
        d.setHeaderText("Crawl limits & politeness");
        brandDialog(d);
        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        Spinner<Integer> maxDepth = new Spinner<>(1, 50, config.maxDepth);
        Spinner<Integer> timeout = new Spinner<>(5, 120, config.timeoutSeconds);
        TextField ua = new TextField(config.userAgent); ua.setPrefWidth(420);
        CheckBox robots = new CheckBox("Respect robots.txt"); robots.setSelected(config.respectRobots);
        CheckBox followExt = new CheckBox("Crawl external URLs (slower)"); followExt.setSelected(config.followExternal);
        CheckBox updCheck = new CheckBox("Check for updates on launch"); updCheck.setSelected(isUpdateCheckEnabled());
        g.add(new Label("Max depth:"), 0, 0); g.add(maxDepth, 1, 0);
        g.add(new Label("Timeout (s):"), 0, 1); g.add(timeout, 1, 1);
        g.add(new Label("User-Agent:"), 0, 2); g.add(ua, 1, 2);
        g.add(robots, 0, 3, 2, 1); g.add(followExt, 0, 4, 2, 1); g.add(updCheck, 0, 5, 2, 1);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var r = d.showAndWait();
        if (r.isPresent() && r.get() == ButtonType.OK) {
            config.maxDepth = maxDepth.getValue();
            config.timeoutSeconds = timeout.getValue();
            config.userAgent = ua.getText();
            config.respectRobots = robots.isSelected();
            config.followExternal = followExt.isSelected();
            setUpdateCheckEnabled(updCheck.isSelected());
            setStatus("Spider config saved (depth " + config.maxDepth + ", timeout " + config.timeoutSeconds + "s).");
        }
    }

    // ---------- crawl control ----------
    private void startCrawl() {
        if (crawler != null) return;
        allPages.clear(); issueCounts.clear(); pageIndex.clear(); pendingUi.clear(); issueFilter = "";
        if (contentBox != null) contentBox.setValue("All Content");
        if (statusBox != null) statusBox.setValue("All Status");
        if (issueBox != null) issueBox.setValue("All Issues");
        if (searchField != null) searchField.clear();
        refreshIssues();
        String typed = urlField.getText() == null ? "" : urlField.getText().trim();
        if (!typed.contains("://")) typed = "https://" + typed;
        urlField.setText(typed);
        config.startUrl = typed;
        config.threads = threadSpinner.getValue();
        config.maxUrls = maxUrlsSpinner.getValue();
        config.includeSubdomains = subBox.isSelected();
        crawlStartMs = System.currentTimeMillis();
        lastCrawled = 0; lastQueued = 0; lastActive = 0;
        recentHits.clear();
        if (rateLabel != null) rateLabel.setText("");
        lastNote = "";
        startBtn.setDisable(true); pauseBtn.setDisable(false); stopBtn.setDisable(false);
        pauseBtn.setText("⏸ Pause");
        setProgress(-1);
        setStatus("Crawling " + config.startUrl + " …");

        try {
            crawler = new SeoCrawler(config, new SeoCrawler.Listener() {
            @Override public void onPage(CrawledPage page) {
                pendingUi.add(page);
                recentHits.add(System.currentTimeMillis());
                scheduleFlush();
            }
            @Override public void onProgress(int crawled, int queued, int active) {
                lastCrawled = crawled; lastQueued = queued; lastActive = active;
                scheduleFlush();
            }
            @Override public void onFinished(int crawled) {
                Platform.runLater(() -> {
                    flushUi();
                    refreshInlinkCounts();
                    computeDuplicates();
                    long secs = (System.currentTimeMillis() - crawlStartMs) / 1000;
                    if (crawled == 0) {
                        setStatus("Finished: 0 URLs — nothing crawled. " + (lastNote.isEmpty()
                                ? "Check the URL (include https://) and that the site is reachable."
                                : lastNote));
                        setProgress(0);
                    } else {
                        long good = allPages.stream().filter(p -> "HTML".equals(p.getContentKind()) && p.getStatusCode() == 200).count();
                        String msg = "Finished: " + allPages.size() + " URLs in " + secs + "s.";
                        if (good == 0) msg += " No usable pages were fetched — the site may be refusing connections (rate-limit/block). Wait a while, try fewer threads, then retry.";
                        setStatus(msg);
                        setProgress(1);
                    }
                    updateStats();
                    startBtn.setDisable(false); pauseBtn.setDisable(true); stopBtn.setDisable(true);
                    crawler = null;
                });
            }
            @Override public void onMessage(String msg) { lastNote = msg; Platform.runLater(() -> setStatus(msg)); }
            });
            new Thread(crawler::start, "arachnode-starter").start();
            inlinkIndex = crawler.getInlinkIndex();
        } catch (Throwable t) {
            // Never leave the UI frozen in "crawling" state: restore controls + explain.
            crawler = null;
            setProgress(0);
            setStatus("Could not start crawl: " + t);
            startBtn.setDisable(false); pauseBtn.setDisable(true); stopBtn.setDisable(true);
        }
    }

    private void togglePause() {
        if (crawler == null) return;
        crawler.pause(!crawler.isPaused());
        pauseBtn.setText(crawler.isPaused() ? "▶ Resume" : "⏸ Pause");
    }

    private void stopCrawl() {
        if (crawler != null) { crawler.stop(); setStatus("Stopping…"); }
    }

    private void updateProgress() {
        // In-flight fetches count as outstanding work: without +active the bar hits
        // 100% while workers are still downloading (queued=0, active>0).
        int outstanding = lastQueued + lastActive;
        int known = lastCrawled + outstanding;
        double frac = known == 0 ? -1 : Math.min(0.999, (double) lastCrawled / known);
        if (crawler == null && lastCrawled > 0) frac = 1.0; // only full when actually finished
        setProgress(frac);
        updateRate();
        setStatus("Crawled " + lastCrawled + " • Queued " + lastQueued);
        statusLabel.setTooltip(new Tooltip("Active " + lastActive + " • " + elapsed()
                + " • filter: " + currentFilter + " (" + filtered.size() + " shown)"));
        updateStats();
    }

    /** Progress bar with the % baked inside it (SF-style). */
    private void setProgress(double frac) {
        progress.setProgress(frac);
        if (progressText == null) return;
        if (frac < 0) {
            progressText.setText("…");
            progressText.setStyle("-fx-text-fill: #64748b;");
        } else {
            int pct = (int) Math.round(frac * 100);
            progressText.setText(pct + "%");
            // dark on the light track, white once the fill slides underneath
            progressText.setStyle(frac < 0.5 ? "-fx-text-fill: #0f172a;" : "-fx-text-fill: white;");
        }
    }

    /** SF-style crawl rate: average since start vs current (last 5s window). */
    private void updateRate() {
        if (rateLabel == null) return;
        long now = System.currentTimeMillis();
        long elapsedMs = Math.max(1, now - crawlStartMs);
        double avg = lastCrawled * 1000.0 / elapsedMs;
        Long head;
        while ((head = recentHits.peek()) != null && now - head > 5000) recentHits.poll();
        double cur = recentHits.size() / 5.0;
        rateLabel.setText(String.format("⌀ %.2f URL/s • %.2f now", avg, cur));
    }

    private void updateStats() {
        if (statsBox == null) return;
        // External stubs (status 0, "Not crawled") are NOT errors — they live in the
        // External tab. Counting them as errors made the ✖ pill lie and open an empty view.
        long ok = allPages.stream().filter(p -> !"External".equals(p.getContentKind()) && p.getStatusCode() >= 200 && p.getStatusCode() < 300).count();
        long redir = allPages.stream().filter(p -> !"External".equals(p.getContentKind()) && p.getStatusCode() >= 300 && p.getStatusCode() < 400).count();
        long err = allPages.stream().filter(p -> !"External".equals(p.getContentKind()) && (p.getStatusCode() >= 400 || p.getStatusCode() == 0)).count();
        long ext = allPages.stream().filter(p -> "External".equals(p.getContentKind())).count();
        statOk.setText("✔ " + ok + " OK");
        statRedir.setText("↪ " + redir + " redirects");
        statErr.setText("✖ " + err + " errors");
        statExt.setText("↗ " + ext + " external");
        statTotal.setText("Σ " + allPages.size());
        statErr.setTooltip(new Tooltip("Show only errors: 4xx / 5xx / blocked" + (ext > 0 ? " — note: " + ext + " external links are not errors, see the External tab" : "") + " — click to filter"));
    }

    private String elapsed() {
        long s = (System.currentTimeMillis() - crawlStartMs) / 1000;
        return s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
    }

    private void scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) Platform.runLater(this::flushUi);
    }

    private void flushUi() {
        flushScheduled.set(false);
        CrawledPage p;
        boolean added = false;
        while ((p = pendingUi.poll()) != null) {
            allPages.add(p);
            pageIndex.put(p.getUrl(), p);
            // Issues Overview is internal-only (SF parity): external stubs never contribute.
            if (!"External".equals(p.getContentKind())) {
                for (String i : p.getIssueList()) {
                    String key = i.split("\\(")[0].trim();
                    issueCounts.merge(key, 1, Integer::sum);
                }
            }
            added = true;
        }
        if (added) {
            refreshInlinkCountsQuiet();
            refreshIssues();
            updateProgress();
        }
        if (!pendingUi.isEmpty()) scheduleFlush();
    }

    /** Inlink counts grow as more pages are parsed — refresh visible counts live. */
    private void refreshInlinkCountsQuiet() {
        if (inlinkIndex == null || inlinkIndex.isEmpty()) return;
        for (CrawledPage p : allPages) {
            List<LinkRef> in = inlinkIndex.get(p.getUrl());
            if (in != null) p.setInlinks(in.size());
        }
    }

    private void refreshInlinkCounts() {
        refreshInlinkCountsQuiet();
        if (table != null) table.refresh();
    }

    /** Post-crawl duplicate audit (SF Duplicates parity): hash / title / meta groups. */
    private void computeDuplicates() {
        Map<String, List<CrawledPage>> byHash = new HashMap<>();
        Map<String, List<CrawledPage>> byTitle = new HashMap<>();
        Map<String, List<CrawledPage>> byMeta = new HashMap<>();
        for (CrawledPage p : allPages) {
            if (!"HTML".equals(p.getContentKind()) || p.getStatusCode() != 200) continue;
            if (p.getContentHash() != null && !p.getContentHash().isEmpty())
                byHash.computeIfAbsent(p.getContentHash(), k -> new ArrayList<>()).add(p);
            if (p.getTitle() != null && !p.getTitle().isEmpty())
                byTitle.computeIfAbsent(p.getTitle().toLowerCase(), k -> new ArrayList<>()).add(p);
            if (p.getMetaDescription() != null && !p.getMetaDescription().isEmpty())
                byMeta.computeIfAbsent(p.getMetaDescription().toLowerCase(), k -> new ArrayList<>()).add(p);
        }
        boolean changed = false;
        for (var e : byHash.entrySet()) if (e.getValue().size() > 1)
            for (CrawledPage p : e.getValue()) { p.addIssue("Duplicate Content (" + e.getValue().size() + ")"); changed = true; }
        for (var e : byTitle.entrySet()) if (e.getValue().size() > 1)
            for (CrawledPage p : e.getValue()) { p.addIssue("Duplicate Title (" + e.getValue().size() + ")"); changed = true; }
        for (var e : byMeta.entrySet()) if (e.getValue().size() > 1)
            for (CrawledPage p : e.getValue()) { p.addIssue("Duplicate Meta Description (" + e.getValue().size() + ")"); changed = true; }
        if (changed) {
            for (CrawledPage p : allPages) p.rebuildIssueString();
            issueCounts.clear();
            for (CrawledPage p : allPages) {
                if ("External".equals(p.getContentKind())) continue;
                for (String i : p.getIssueList()) issueCounts.merge(i.split("\\(")[0].trim(), 1, Integer::sum);
            }
            refreshIssues();
            table.refresh();
        }
    }

    private void setStatus(String s) { statusLabel.setText(s); }

    private void applyFilter() {
        // Old-style quick search: multi-term AND Contains across URL/title/meta (case-insensitive).
        String q = searchField == null || searchField.getText() == null ? "" : searchField.getText().toLowerCase().trim();
        String contentF = contentBox == null ? "All Content" : contentBox.getValue();
        String statusF = statusBox == null ? "All Status" : statusBox.getValue();
        Predicate<CrawledPage> tab = predicateFor(currentFilter);
        boolean hasAdvanced = advancedGroups.stream().anyMatch(g -> !g.isEmpty());
        filtered.setPredicate(p -> {
            if (!tab.test(p)) return false;
            if (!matchesContent(p, contentF)) return false;
            if (!matchesStatus(p, statusF)) return false;
            if (!issueFilter.isEmpty()) {
                if (p.getIssues() == null || !p.getIssues().contains(issueFilter)) return false;
            }
            if (!q.isEmpty()) {
                String hay = (p.getUrl() + " " + nvl(p.getTitle()) + " " + nvl(p.getMetaDescription()) + " " + nvl(p.getH1())
                        + " " + nvl(p.getCanonical()) + " " + nvl(p.getIssues())).toLowerCase();
                for (String term : q.split("\\s+")) {
                    if (!term.isEmpty() && !hay.contains(term)) return false;
                }
            }
            if (hasAdvanced && !matchesAdvanced(p)) return false;
            return true;
        });
        if ("Custom Search".equals(currentFilter)) computeCustomMatches();
        else if (table != null) table.refresh();
        updateStats();
        updateResultCount();
    }

    /** Custom Search tab (SF parity): count each query's occurrences per HTML page. */
    private void computeCustomMatches() {
        if (customQueries.isEmpty()) {
            for (CrawledPage p : allPages) p.setCustomMatches("");
            if (table != null) table.refresh();
            return;
        }
        List<java.util.regex.Pattern> pats = new ArrayList<>();
        for (CustomQuery cq : customQueries) {
            if (cq.query == null || cq.query.isEmpty()) { pats.add(null); continue; }
            try {
                pats.add(cq.regex ? java.util.regex.Pattern.compile(cq.query)
                        : java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(cq.query),
                                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE));
            } catch (Exception e) { pats.add(null); }
        }
        for (CrawledPage p : allPages) {
            if (!"HTML".equals(p.getContentKind())) { p.setCustomMatches(""); continue; }
            String hay = nvl(p.getTitle()) + "\n" + nvl(p.getMetaDescription()) + "\n" + nvl(p.getH1())
                    + "\n" + nvl(p.getHtmlSnippet());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < customQueries.size(); i++) {
                var pat = pats.get(i);
                if (pat == null) continue;
                int n = 0;
                try {
                    var m = pat.matcher(hay);
                    while (m.find() && n < 10000) n++;
                } catch (Exception ignored) {}
                if (sb.length() > 0) sb.append("  |  ");
                sb.append(customQueries.get(i).name).append(": ").append(n);
            }
            p.setCustomMatches(sb.toString());
        }
        if (table != null) table.refresh();
    }

    private void showCustomSearchDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Custom Search");
        d.setHeaderText("Up to 5 text/regex queries counted per page. Text is case-insensitive; regex is case-sensitive — prefix (?i) for insensitive.");
        brandDialog(d);
        d.setResizable(true);
        d.getDialogPane().setPrefSize(620, 380);
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        List<TextField> names = new ArrayList<>();
        List<TextField> queries = new ArrayList<>();
        List<CheckBox> regexes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CustomQuery ex = i < customQueries.size() ? customQueries.get(i) : null;
            TextField nm = new TextField(ex != null ? ex.name : "Q" + (i + 1));
            nm.setPrefWidth(110); nm.setPromptText("Name");
            TextField qq = new TextField(ex != null ? ex.query : "");
            qq.setPromptText(i == 0 ? "e.g. toastr  •  \\bprice\\b|\\bcost\\b in regex" : "optional query");
            qq.setPrefWidth(300); HBox.setHgrow(qq, Priority.ALWAYS);
            CheckBox rx = new CheckBox("Regex");
            if (ex != null) rx.setSelected(ex.regex);
            names.add(nm); queries.add(qq); regexes.add(rx);
            HBox row = new HBox(8, new Label("Name:"), nm, qq, rx);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            box.getChildren().add(row);
        }
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var r = d.showAndWait();
        if (r.isPresent() && r.get() == ButtonType.OK) {
            customQueries.clear();
            for (int i = 0; i < 5; i++) {
                String q = queries.get(i).getText() == null ? "" : queries.get(i).getText().trim();
                if (q.isEmpty()) continue;
                String n = names.get(i).getText() == null || names.get(i).getText().trim().isEmpty()
                        ? "Q" + (i + 1) : names.get(i).getText().trim();
                customQueries.add(new CustomQuery(n, q, regexes.get(i).isSelected()));
            }
            computeCustomMatches();
            applyFilter();
            setStatus(customQueries.isEmpty() ? "Custom Search cleared."
                    : "Custom Search: " + customQueries.size() + " queries — open the Custom Search tab.");
        }
    }

    private boolean matchesContent(CrawledPage p, String f) {
        if (f == null || "All Content".equals(f)) return true;
        return switch (f) {
            case "HTML" -> "HTML".equals(p.getContentKind());
            case "Image" -> "Image".equals(p.getContentKind());
            case "CSS" -> "CSS".equals(p.getContentKind());
            case "JS" -> "JS".equals(p.getContentKind());
            case "Redirect" -> "Redirect".equals(p.getContentKind());
            case "Error" -> "Error".equals(p.getContentKind()) || p.getStatusCode() >= 400;
            case "External" -> "External".equals(p.getContentKind());
            case "Indexable" -> "Indexable".equals(p.getIndexable()) && !"External".equals(p.getContentKind());
            case "Non-Indexable" -> !"Indexable".equals(p.getIndexable());
            default -> true;
        };
    }

    private boolean matchesStatus(CrawledPage p, String f) {
        if (f == null || "All Status".equals(f)) return true;
        int sc = p.getStatusCode();
        return switch (f) {
            case "2xx Success" -> sc >= 200 && sc < 300;
            case "3xx Redirect" -> sc >= 300 && sc < 400;
            case "4xx Client Error" -> sc >= 400 && sc < 500;
            case "5xx Server Error" -> sc >= 500;
            case "0 Blocked" -> sc == 0;
            case "✖ Errors (4xx/5xx/0)" -> (sc >= 400 || sc == 0) && !"External".equals(p.getContentKind());
            default -> true;
        };
    }

    private void updateResultCount() {
        if (resultCountLabel != null) resultCountLabel.setText(filtered.size() + " / " + allPages.size());
        // Honest empty state: "start a crawl" only when nothing was crawled at all.
        if (table != null) {
            if (allPages.isEmpty()) table.setPlaceholder(new Label("No URLs yet — start a crawl above."));
            else if (filtered.isEmpty()) table.setPlaceholder(new Label("No URLs match the current filters — ✕ Clear to reset."));
        }
    }

    // ---------- Advanced Table Search (SF parity: OR of AND-groups) ----------
    private static final List<String> ADV_COLUMNS = List.of("Address", "Status", "Content Kind", "Title", "Title Len",
            "Meta Desc", "Meta Len", "H1", "H1 Count", "H2 Count", "Words", "Canonical", "Meta Robots", "X-Robots",
            "Indexable", "Hreflang", "Redirect URI", "Issues", "Depth", "Inlinks", "Outlinks", "Images", "Resp ms", "Size");
    private static final List<String> ADV_OPS = List.of("Contains (~)", "Does Not Contain", "Exact Match", "Starts With",
            "Ends With", "Regex", "Regex Not Match", "=", "≠", ">", "<", ">=", "<=", "Is Empty", "Is Not Empty");

    private String advField(CrawledPage p, String col) {
        return switch (col) {
            case "Address" -> nvl(p.getUrl());
            case "Status" -> p.getStatusCode() + " " + nvl(p.getStatusText());
            case "Content Kind" -> nvl(p.getContentKind()) + " " + nvl(p.getContentType());
            case "Title" -> nvl(p.getTitle());
            case "Title Len" -> String.valueOf(p.getTitleLength());
            case "Meta Desc" -> nvl(p.getMetaDescription());
            case "Meta Len" -> String.valueOf(p.getMetaDescLength());
            case "H1" -> nvl(p.getH1());
            case "H1 Count" -> String.valueOf(p.getH1Count());
            case "H2 Count" -> String.valueOf(p.getH2Count());
            case "Words" -> String.valueOf(p.getWordCount());
            case "Canonical" -> nvl(p.getCanonical());
            case "Meta Robots" -> nvl(p.getMetaRobots());
            case "X-Robots" -> nvl(p.getXRobots());
            case "Indexable" -> nvl(p.getIndexable());
            case "Hreflang" -> String.valueOf(p.getHreflangCount());
            case "Redirect URI" -> nvl(p.getRedirectUri()) + " " + nvl(p.getRedirectChain());
            case "Issues" -> nvl(p.getIssues());
            case "Depth" -> String.valueOf(p.getDepth());
            case "Inlinks" -> String.valueOf(p.getInlinks());
            case "Outlinks" -> String.valueOf(p.getOutlinks());
            case "Images" -> String.valueOf(p.getImageCount());
            case "Resp ms" -> String.valueOf(p.getResponseTimeMs());
            case "Size" -> String.valueOf(p.getSizeBytes());
            default -> "";
        };
    }

    private boolean matchesAdvanced(CrawledPage p) {
        boolean anyNonEmpty = false;
        for (List<FilterCondition> group : advancedGroups) {
            if (group.isEmpty()) continue;
            anyNonEmpty = true;
            boolean allMatch = true;
            for (FilterCondition c : group) {
                if (!matchesCondition(p, c)) { allMatch = false; break; }
            }
            if (allMatch) return true; // OR between groups
        }
        return !anyNonEmpty;
    }

    private boolean matchesCondition(CrawledPage p, FilterCondition c) {
        String field = advField(p, c.column());
        String q = c.query() == null ? "" : c.query();
        String op = c.operator();
        return switch (op) {
            case "Is Empty" -> field.trim().isEmpty() || field.trim().equals("0") && isNumericCol(c.column()) && field.trim().equals("0") && emptyMeansZero(c.column());
            case "Is Not Empty" -> !field.trim().isEmpty();
            case "Contains (~)" -> field.toLowerCase().contains(q.toLowerCase());
            case "Does Not Contain" -> !field.toLowerCase().contains(q.toLowerCase());
            case "Exact Match" -> field.equalsIgnoreCase(q);
            case "Starts With" -> field.toLowerCase().startsWith(q.toLowerCase());
            case "Ends With" -> field.toLowerCase().endsWith(q.toLowerCase());
            case "Regex" -> { try { yield java.util.regex.Pattern.compile(q).matcher(field).find(); } catch (Exception e) { yield true; } }
            case "Regex Not Match" -> { try { yield !java.util.regex.Pattern.compile(q).matcher(field).find(); } catch (Exception e) { yield true; } }
            case "=" -> numEq(field, q);
            case "≠" -> !numEq(field, q) && !field.equalsIgnoreCase(q);
            case ">" -> numCmp(field, q) > 0;
            case "<" -> numCmp(field, q) < 0;
            case ">=" -> numCmp(field, q) >= 0;
            case "<=" -> numCmp(field, q) <= 0;
            default -> field.toLowerCase().contains(q.toLowerCase());
        };
    }

    private boolean isNumericCol(String col) {
        return List.of("Status", "Title Len", "Meta Len", "H1 Count", "H2 Count", "Words", "Hreflang",
                "Depth", "Inlinks", "Outlinks", "Images", "Resp ms", "Size").contains(col);
    }

    private boolean emptyMeansZero(String col) { return isNumericCol(col); }

    private Double tryNum(String s) {
        try {
            String t = s.trim().split("\\s+")[0].replaceAll("[^0-9.\\-]", "");
            if (t.isEmpty() || t.equals("-") || t.equals(".")) return null;
            return Double.parseDouble(t);
        } catch (Exception e) { return null; }
    }

    private boolean numEq(String field, String q) {
        Double a = tryNum(field), b = tryNum(q);
        if (a != null && b != null) return Math.abs(a - b) < 1e-9;
        return field.equalsIgnoreCase(q.trim());
    }

    private int numCmp(String field, String q) {
        Double a = tryNum(field), b = tryNum(q);
        if (a != null && b != null) return Double.compare(a, b);
        return field.compareToIgnoreCase(q.trim());
    }

    private void updateAdvancedBadge() {
        int n = advancedGroups.stream().mapToInt(List::size).sum();
        int groups = (int) advancedGroups.stream().filter(g -> !g.isEmpty()).count();
        if (advancedBadge == null) return;
        if (n == 0) {
            advancedBadge.setVisible(false);
            advancedBadge.setManaged(false);
            advancedBtn.setText("Advanced…");
        } else {
            advancedBadge.setVisible(true);
            advancedBadge.setManaged(true);
            advancedBadge.setText("● " + n + " in " + groups + " group" + (groups == 1 ? "" : "s"));
            advancedBadge.setTooltip(new Tooltip(describeAdvanced()));
            advancedBtn.setText("Advanced ✓");
        }
    }

    private String describeAdvanced() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < advancedGroups.size(); i++) {
            var g = advancedGroups.get(i);
            if (g.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\nOR\n");
            for (int j = 0; j < g.size(); j++) {
                var c = g.get(j);
                if (j > 0) sb.append("  AND ");
                sb.append(c.column()).append(" ").append(c.operator()).append(" \"").append(c.query()).append("\"\n");
            }
        }
        return sb.toString().trim();
    }

    private void showAdvancedSearchDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Advanced Table Search");
        d.setHeaderText("Filter the results table — conditions in a group are ANDed, groups are ORed.");
        brandDialog(d);
        d.setResizable(true);
        d.getDialogPane().setPrefSize(880, 560);
        d.getDialogPane().setMinWidth(720);
        d.getDialogPane().getStyleClass().add("adv-dialog");

        // Working copy so Cancel discards edits.
        List<List<FilterRow>> uiGroups = new ArrayList<>();
        if (advancedGroups.isEmpty()) uiGroups.add(new ArrayList<>());
        else for (var g : advancedGroups) {
            List<FilterRow> ng = new ArrayList<>();
            for (var c : g) ng.add(new FilterRow(c.column(), c.operator(), c.query()));
            uiGroups.add(ng);
        }

        VBox groupsBox = new VBox(10);
        groupsBox.setPadding(new Insets(12));
        groupsBox.setFillWidth(true);
        ScrollPane scroll = new ScrollPane(groupsBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefViewportHeight(360);
        scroll.setMinHeight(200);
        scroll.getStyleClass().add("adv-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            groupsBox.getChildren().clear();
            for (int gi = 0; gi < uiGroups.size(); gi++) {
                final int gIdx = gi;
                VBox groupCard = new VBox(8);
                groupCard.getStyleClass().add("adv-group");
                groupCard.setFillWidth(true);
                groupCard.setMaxWidth(Double.MAX_VALUE);
                HBox header = new HBox(8);
                header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                Label gLabel = new Label(uiGroups.size() == 1 ? "Match ALL of these (AND)" : "Group " + (gi + 1) + " — match ALL (AND), groups are ORed");
                gLabel.getStyleClass().add("adv-group-label");
                gLabel.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(gLabel, Priority.ALWAYS);
                Button andBtn = new Button("+ AND");
                andBtn.setMinWidth(72);
                andBtn.getStyleClass().addAll("btn", "btn-primary");
                andBtn.setTooltip(new Tooltip("Add an AND condition to this group"));
                andBtn.setOnAction(e -> { uiGroups.get(gIdx).add(new FilterRow("Address", "Contains (~)", "")); rebuild[0].run(); });
                header.getChildren().addAll(gLabel, andBtn);
                groupCard.getChildren().add(header);

                List<FilterRow> rows = uiGroups.get(gi);
                if (rows.isEmpty()) rows.add(new FilterRow("Address", "Contains (~)", ""));
                for (int ri = 0; ri < rows.size(); ri++) {
                    final int rIdx = ri;
                    FilterRow fr = rows.get(ri);
                    GridPane row = new GridPane();
                    row.setHgap(6);
                    row.getStyleClass().add("adv-row");
                    ColumnConstraints c0 = new ColumnConstraints(140, 140, 160);
                    ColumnConstraints c1 = new ColumnConstraints(130, 130, 150);
                    ColumnConstraints c2 = new ColumnConstraints(120, 200, Double.MAX_VALUE);
                    c2.setHgrow(Priority.ALWAYS);
                    ColumnConstraints c3 = new ColumnConstraints(36, 36, 36);
                    ColumnConstraints c4 = new ColumnConstraints(36, 36, 36);
                    row.getColumnConstraints().addAll(c0, c1, c2, c3, c4);
                    ComboBox<String> col = new ComboBox<>(FXCollections.observableArrayList(ADV_COLUMNS));
                    col.setValue(fr.column); col.setMaxWidth(Double.MAX_VALUE);
                    ComboBox<String> op = new ComboBox<>(FXCollections.observableArrayList(ADV_OPS));
                    op.setValue(fr.operator); op.setMaxWidth(Double.MAX_VALUE);
                    TextField q = new TextField(fr.query);
                    q.setPromptText("Enter search query");
                    q.setMaxWidth(Double.MAX_VALUE);
                    GridPane.setHgrow(q, Priority.ALWAYS);
                    GridPane.setFillWidth(q, true);
                    col.valueProperty().addListener((o, a, b) -> fr.column = b);
                    op.valueProperty().addListener((o, a, b) -> fr.operator = b);
                    q.textProperty().addListener((o, a, b) -> fr.query = b);
                    try {
                        if (fr.operator.startsWith("Regex") && !fr.query.isEmpty())
                            java.util.regex.Pattern.compile(fr.query);
                        q.getStyleClass().remove("search-invalid");
                    } catch (Exception ex) {
                        if (!q.getStyleClass().contains("search-invalid")) q.getStyleClass().add("search-invalid");
                    }
                    q.textProperty().addListener((o, a, b) -> {
                        try {
                            if (!fr.operator.startsWith("Regex") || b.isEmpty()) { q.getStyleClass().remove("search-invalid"); return; }
                            java.util.regex.Pattern.compile(b);
                            q.getStyleClass().remove("search-invalid");
                        } catch (Exception ex) {
                            if (!q.getStyleClass().contains("search-invalid")) q.getStyleClass().add("search-invalid");
                        }
                    });
                    Button dup = new Button();
                    dup.setTooltip(new Tooltip("Duplicate this condition"));
                    dup.getStyleClass().addAll("mini-btn", "icon-btn");
                    dup.setMinWidth(36); dup.setMaxWidth(36); dup.setMinHeight(26);
                    dup.setGraphic(plusIcon("#0f172a"));
                    dup.setOnAction(e -> { uiGroups.get(gIdx).add(rIdx + 1, new FilterRow(fr.column, fr.operator, fr.query)); rebuild[0].run(); });
                    Button del = new Button();
                    del.setTooltip(new Tooltip("Delete this condition"));
                    del.getStyleClass().addAll("mini-btn", "icon-btn", "icon-btn-danger");
                    del.setMinWidth(36); del.setMaxWidth(36); del.setMinHeight(26);
                    del.setGraphic(crossIcon("#991b1b"));
                    del.setOnAction(e -> { uiGroups.get(gIdx).remove(rIdx); if (uiGroups.get(gIdx).isEmpty() && uiGroups.size() > 1) uiGroups.remove(gIdx); rebuild[0].run(); });
                    del.setOnAction(e -> { uiGroups.get(gIdx).remove(rIdx); if (uiGroups.get(gIdx).isEmpty() && uiGroups.size() > 1) uiGroups.remove(gIdx); rebuild[0].run(); });
                    row.add(col, 0, 0); row.add(op, 1, 0); row.add(q, 2, 0); row.add(dup, 3, 0); row.add(del, 4, 0);
                    groupCard.getChildren().add(row);
                }
                groupsBox.getChildren().add(groupCard);
                if (gi < uiGroups.size() - 1) {
                    HBox orRow = new HBox();
                    orRow.setAlignment(javafx.geometry.Pos.CENTER);
                    orRow.setPadding(new Insets(2, 0, 2, 0));
                    Button orBtn = new Button("+ OR");
                    orBtn.setMinWidth(72);
                    orBtn.getStyleClass().addAll("btn", "btn-primary");
                    orBtn.setTooltip(new Tooltip("Add a new OR group"));
                    orBtn.setOnAction(e -> { uiGroups.add(new ArrayList<>()); rebuild[0].run(); });
                    orRow.getChildren().add(orBtn);
                    groupsBox.getChildren().add(orRow);
                }
            }
            // Bottom +OR when only one group (matches SF screenshot with centered OR)
            if (uiGroups.size() == 1) {
                HBox orRow = new HBox();
                orRow.setAlignment(javafx.geometry.Pos.CENTER);
                orRow.setPadding(new Insets(2, 0, 2, 0));
                Button orBtn = new Button("+ OR");
                orBtn.setMinWidth(72);
                orBtn.getStyleClass().addAll("btn", "btn-primary");
                orBtn.setOnAction(e -> { uiGroups.add(new ArrayList<>()); rebuild[0].run(); });
                orRow.getChildren().add(orBtn);
                groupsBox.getChildren().add(orRow);
            }
        };
        rebuild[0].run();

        HBox footer = new HBox(8);
        footer.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        footer.setPadding(new Insets(8, 0, 0, 0));
        Button resetBtn = new Button("⟳ Reset");
        resetBtn.getStyleClass().addAll("btn", "btn-reset");
        resetBtn.setOnAction(e -> { uiGroups.clear(); uiGroups.add(new ArrayList<>()); rebuild[0].run(); });
        Region fsp = new Region(); HBox.setHgrow(fsp, Priority.ALWAYS);
        Label tip = new Label("Text matches are case-insensitive • Regex is case-sensitive — use (?i) for insensitive");
        tip.getStyleClass().add("panel-hint");
        footer.getChildren().addAll(resetBtn, tip, fsp);

        VBox content = new VBox(8, scroll, footer);
        content.setPadding(new Insets(0, 4, 0, 4));
        content.setFillWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var r = d.showAndWait();
        if (r.isPresent() && r.get() == ButtonType.OK) {
            advancedGroups.clear();
            for (var g : uiGroups) {
                List<FilterCondition> ng = new ArrayList<>();
                for (var fr : g) {
                    if ("Is Empty".equals(fr.operator) || "Is Not Empty".equals(fr.operator) || (fr.query != null && !fr.query.trim().isEmpty()))
                        ng.add(new FilterCondition(fr.column, fr.operator, fr.query == null ? "" : fr.query.trim()));
                }
                if (!ng.isEmpty()) advancedGroups.add(ng);
            }
            updateAdvancedBadge();
            applyFilter();
            int n = advancedGroups.stream().mapToInt(List::size).sum();
            setStatus(n == 0 ? "Advanced search cleared." : "Advanced search active: " + describeAdvanced().replace("\n", " • "));
        }
    }

    private static class FilterRow {
        String column, operator, query;
        FilterRow(String c, String o, String q) { column = c; operator = o; query = q; }
    }

    /** Font-independent vector icons (shapes, not glyphs) for row actions. */
    private static javafx.scene.Group plusIcon(String stroke) {
        javafx.scene.shape.Line h = new javafx.scene.shape.Line(-5, 0, 5, 0);
        javafx.scene.shape.Line v = new javafx.scene.shape.Line(0, -5, 0, 5);
        for (var l : List.of(h, v)) {
            l.setStroke(javafx.scene.paint.Color.web(stroke));
            l.setStrokeWidth(2.4);
            l.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        }
        return new javafx.scene.Group(h, v);
    }

    private static javafx.scene.Group crossIcon(String stroke) {
        javafx.scene.shape.Line d1 = new javafx.scene.shape.Line(-4.5, -4.5, 4.5, 4.5);
        javafx.scene.shape.Line d2 = new javafx.scene.shape.Line(4.5, -4.5, -4.5, 4.5);
        for (var l : List.of(d1, d2)) {
            l.setStroke(javafx.scene.paint.Color.web(stroke));
            l.setStrokeWidth(2.4);
            l.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        }
        return new javafx.scene.Group(d1, d2);
    }

    private String nvl(String s) { return s == null ? "" : s; }

    private javafx.scene.image.Image appIcon() {
        try {
            var in = getClass().getResourceAsStream("/logo.png");
            if (in != null) return new javafx.scene.image.Image(in);
        } catch (Exception ignored) { /* default icon */ }
        return null;
    }

    /** Every popup/modal gets the Arachnode logo in its title bar (no default JavaFX icon),
     *  is owned by the main window, and shares the app stylesheet. Call before showAndWait(). */
    /** About box: custom content (no Modena Alert header) so it themes in every mode. */
    private void showAboutDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("About");
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView();
        try {
            var in = getClass().getResourceAsStream("/logo.png");
            if (in != null) { logo.setImage(new javafx.scene.image.Image(in)); in.close(); }
        } catch (Exception ignored) {}
        logo.setFitWidth(48); logo.setFitHeight(48);
        logo.setPreserveRatio(true);
        Label name = new Label("Arachnode " + com.arachnode.update.AppVersion.current());
        name.getStyleClass().add("about-name");
        Label sub = new Label("Desktop SEO spider");
        sub.getStyleClass().add("about-sub");
        Label body = new Label("Java 21 + JavaFX + Virtual Threads.\nEnter a URL and press Start.");
        body.getStyleClass().add("about-body");
        Label credit = new Label("Built by slickmagic19.");
        credit.getStyleClass().add("about-credit");
        VBox box = new VBox(8, logo, name, sub, body, credit);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setPadding(new Insets(20, 28, 12, 28));
        box.getStyleClass().add("about-box");
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.OK);
        brandDialog(d);
        d.showAndWait();
    }

    // ---------- updates (GitHub Releases; portable-safe: download new exe + run once) ----------
    private static final String PREF_UPDATE = "updateCheck";
    private com.arachnode.update.UpdateChecker.ReleaseInfo latestRelease;

    private boolean isUpdateCheckEnabled() {
        try {
            return java.util.prefs.Preferences.userNodeForPackage(MainView.class).getBoolean(PREF_UPDATE, true);
        } catch (Exception e) { return true; }
    }

    private void setUpdateCheckEnabled(boolean on) {
        try {
            java.util.prefs.Preferences.userNodeForPackage(MainView.class).putBoolean(PREF_UPDATE, on);
        } catch (Exception ignored) {}
    }

    /** Silent on-launch check: only speaks up (status bar) when an update exists. */
    public void checkForUpdatesOnLaunch() {
        if (!isUpdateCheckEnabled()) return;
        Thread.ofVirtual().name("arachnode-update-check").start(() -> {
            var rel = com.arachnode.update.UpdateChecker.latest();
            if (rel != null && com.arachnode.update.UpdateChecker.isNewer(rel.tag())) {
                latestRelease = rel;
                Platform.runLater(() -> setStatus("Update available: " + rel.tag()
                        + " (you have " + com.arachnode.update.AppVersion.current()
                        + ") — Help > Check for Updates."));
            }
        });
    }

    private void showUpdateCheckDialog(boolean manual) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Check for Updates");
        Label title = new Label("Checking for updates…");
        title.getStyleClass().add("update-title");
        TextArea notes = new TextArea();
        notes.setEditable(false);
        notes.setWrapText(true);
        notes.setPrefRowCount(8);
        notes.setPrefWidth(460);
        notes.setVisible(false);
        notes.setManaged(false);
        notes.getStyleClass().add("update-notes");
        Label state = new Label("");
        state.getStyleClass().add("update-state");
        state.setWrapText(true);
        ButtonType dlType = new ButtonType("Download Update", ButtonBar.ButtonData.OK_DONE);
        ButtonType pageType = new ButtonType("Open Releases Page", ButtonBar.ButtonData.OTHER);
        VBox box = new VBox(8, title, notes, state);
        box.setPadding(new Insets(12));
        box.getStyleClass().add("update-box");
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(dlType, pageType, ButtonType.CLOSE);
        var dlBtn = d.getDialogPane().lookupButton(dlType);
        dlBtn.getStyleClass().addAll("btn", "btn-primary");
        var pageBtn = d.getDialogPane().lookupButton(pageType);
        pageBtn.getStyleClass().add("btn");
        var closeBtn = d.getDialogPane().lookupButton(ButtonType.CLOSE);
        closeBtn.getStyleClass().add("btn");
        dlBtn.setDisable(true);
        brandDialog(d);
        // Fill in async so the dialog opens instantly.
        Thread.ofVirtual().name("arachnode-update-check").start(() -> {
            var rel = com.arachnode.update.UpdateChecker.latest();
            Platform.runLater(() -> {
                if (rel == null) {
                    title.setText("Up to date (" + com.arachnode.update.AppVersion.current() + ")");
                    state.setText(manual ? "No newer release found (or offline — try again later)."
                            : "Could not reach the update server.");
                    return;
                }
                latestRelease = rel;
                if (!com.arachnode.update.UpdateChecker.isNewer(rel.tag())) {
                    title.setText("Up to date (" + com.arachnode.update.AppVersion.current() + ")");
                    state.setText("Latest release is " + rel.tag() + " — nothing to do.");
                    return;
                }
                title.setText("Update available: " + rel.tag() + " (you have "
                        + com.arachnode.update.AppVersion.current() + ")");
                if (rel.notes() != null && !rel.notes().isBlank()) {
                    String n = rel.notes().length() > 3000 ? rel.notes().substring(0, 3000) + "…" : rel.notes();
                    notes.setText(n);
                    notes.setVisible(true);
                    notes.setManaged(true);
                }
                boolean hasAsset = rel.assetUrl() != null && !rel.assetUrl().isEmpty();
                state.setText(hasAsset ? "Portable update available." : "No portable file attached.");
                dlBtn.setDisable(!hasAsset);
            });
        });
        var r = d.showAndWait();
        if (r.isPresent() && r.get() == pageType) {
            openInBrowser(com.arachnode.update.UpdateChecker.releasesPage());
        } else if (r.isPresent() && r.get() == dlType && latestRelease != null) {
            downloadRelease(latestRelease);
        }
    }

    private void downloadRelease(com.arachnode.update.UpdateChecker.ReleaseInfo rel) {
        setStatus("Downloading update " + rel.tag() + " …");
        Thread.ofVirtual().name("arachnode-update-dl").start(() -> {
            var file = com.arachnode.update.UpdateChecker.download(rel);
            Platform.runLater(() -> {
                if (file == null) {
                    setStatus("Download failed — open the releases page instead.");
                    new Alert(Alert.AlertType.WARNING, "Download failed. Open Help > Check for Updates > Open Releases Page and grab it manually.").showAndWait();
                    return;
                }
                setStatus("Downloaded update: " + file);
                var confirm = new Alert(Alert.AlertType.INFORMATION,
                        "Downloaded to:\n" + file + "\n\nLaunch the new version now and exit this one?");
                confirm.setTitle("Update Downloaded");
                confirm.setHeaderText(null);
                brandDialog(confirm);
                confirm.getButtonTypes().setAll(new ButtonType("Launch & Exit", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
                var c = confirm.showAndWait();
                if (c.isPresent() && "Launch & Exit".equals(c.get().getText())) {
                    try { new ProcessBuilder(file.toString()).start(); } catch (Exception ignored) {}
                    Platform.exit();
                }
            });
        });
    }

    private void brandDialog(Dialog<?> d) {
        try { d.initOwner(stage); } catch (Exception ignored) { /* owner already set */ }
        try {
            var cssUrl = getClass().getResource("/app.css");
            if (cssUrl != null && !d.getDialogPane().getStylesheets().contains(cssUrl.toExternalForm()))
                d.getDialogPane().getStylesheets().add(cssUrl.toExternalForm());
            var darkUrl = getClass().getResource("/dark.css");
            if (darkUrl != null && !d.getDialogPane().getStylesheets().contains(darkUrl.toExternalForm()))
                d.getDialogPane().getStylesheets().add(darkUrl.toExternalForm());
            var frogUrl = getClass().getResource("/frog.css");
            if (frogUrl != null && !d.getDialogPane().getStylesheets().contains(frogUrl.toExternalForm()))
                d.getDialogPane().getStylesheets().add(frogUrl.toExternalForm());
            // Dialogs live in their own scene: mirror the app's theme classes onto the
            // dialog pane itself (same-element match — descendant selectors can't be trusted here).
            String theme = getTheme();
            var sc = d.getDialogPane().getStyleClass();
            sc.removeAll("dark", "sfrog");
            if (!sc.contains("root")) sc.add("root");
            if ("dark".equals(theme)) sc.add("dark");
            else if ("frog".equals(theme)) sc.add("sfrog");
        } catch (Exception ignored) {}
        var icon = appIcon();
        if (icon == null) return;
        d.setOnShowing(e -> {
            try {
                var scene = d.getDialogPane().getScene();
                var win = scene == null ? null : scene.getWindow();
                if (win instanceof Stage st && st.getIcons().isEmpty()) st.getIcons().add(icon);
            } catch (Exception ignored) { /* keep default icon */ }
        });
    }

    private Predicate<CrawledPage> predicateFor(String tab) {
        return switch (tab) {
            case "All" -> p -> true;
            case "External" -> p -> "External".equals(p.getContentKind());
            case "Security" -> p -> !"External".equals(p.getContentKind());
            case "Response Codes" -> p -> !"External".equals(p.getContentKind());
            case "URL" -> p -> !"External".equals(p.getContentKind());
            case "Page Titles" -> p -> "HTML".equals(p.getContentKind());
            case "Meta Description" -> p -> "HTML".equals(p.getContentKind());
            case "Meta Keywords" -> p -> "HTML".equals(p.getContentKind());
            case "H1" -> p -> "HTML".equals(p.getContentKind());
            case "H2" -> p -> "HTML".equals(p.getContentKind());
            case "Content" -> p -> "HTML".equals(p.getContentKind());
            case "Images" -> p -> ("HTML".equals(p.getContentKind()) && p.getImageCount() > 0) || "Image".equals(p.getContentKind());
            case "Directives" -> p -> "HTML".equals(p.getContentKind()) || "Redirect".equals(p.getContentKind());
            case "Canonicals" -> p -> "HTML".equals(p.getContentKind());
            case "Pagination" -> p -> "HTML".equals(p.getContentKind());
            case "Hreflang" -> p -> "HTML".equals(p.getContentKind());
            case "JavaScript" -> p -> "HTML".equals(p.getContentKind()) || "JS".equals(p.getContentKind());
            case "Links" -> p -> "HTML".equals(p.getContentKind());
            case "AMP" -> p -> "HTML".equals(p.getContentKind());
            case "Structured Data" -> p -> "HTML".equals(p.getContentKind());
            case "Custom Search" -> p -> "HTML".equals(p.getContentKind());
            case "Duplicates" -> p -> p.getIssues() != null && p.getIssues().contains("Duplicate");
            case "Issues" -> p -> !"External".equals(p.getContentKind()) && p.getIssues() != null && !p.getIssues().isEmpty();
            default -> p -> !"External".equals(p.getContentKind()); // Internal
        };
    }

    private void showDetailsEmpty() {
        detailsBox.getChildren().clear();
        Label empty = new Label("Select a URL above to see a full audit — status, SEO, directives, performance, issues.");
        empty.getStyleClass().add("detail-empty");
        empty.setWrapText(true);
        detailsBox.getChildren().add(empty);
    }

    private void showDetails(CrawledPage p) {
        if (p == null) {
            showDetailsEmpty();
            outTable.setItems(FXCollections.observableArrayList());
            inTable.setItems(FXCollections.observableArrayList());
            imgTable.setItems(FXCollections.observableArrayList());
            dupTable.setItems(FXCollections.observableArrayList());
            sourceReq.incrementAndGet(); // cancel any in-flight source fetch
            sourceArea.setText("");
            sourceTab.setText("View Source");
            cookiesArea.setText("");
            structArea.setText("");
            structCount.setText("");
            headersGrid.getChildren().clear();
            serpTitle.setText("Select a URL to preview its search snippet.");
            serpUrl.setText(""); serpDesc.setText("");
            return;
        }
        detailsBox.getChildren().clear();

        // ---- header card: URL + badges + actions ----
        VBox header = new VBox(6);
        header.getStyleClass().add("detail-card");
        Label url = new Label(p.getUrl());
        url.getStyleClass().add("detail-url");
        url.setWrapText(true);
        url.setTooltip(new Tooltip(p.getUrl()));
        HBox badges = new HBox(6,
                statusBadge(p.getStatusCode(), p.getStatusText()),
                kindBadge(p.getContentKind()),
                indexBadge(p.getIndexable()));
        badges.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox actions = new HBox(6);
        Button openBtn = new Button("Open ↗");
        openBtn.getStyleClass().add("mini-btn");
        openBtn.setOnAction(e -> openInBrowser(p.getUrl()));
        Button copyBtn = new Button("Copy URL");
        copyBtn.getStyleClass().add("mini-btn");
        copyBtn.setOnAction(e -> copyText(p.getUrl(), "Copied URL."));
        if (!p.getRedirectUri().isEmpty()) {
            Button followBtn = new Button("Follow redirect →");
            followBtn.getStyleClass().addAll("mini-btn", "mini-btn-primary");
            followBtn.setOnAction(e -> jumpTo(p.getRedirectUri()));
            actions.getChildren().addAll(openBtn, copyBtn, followBtn);
        } else {
            actions.getChildren().addAll(openBtn, copyBtn);
        }
        header.getChildren().addAll(url, badges, actions);
        if ("Redirect".equals(p.getContentKind())) {
            Label note = new Label("This URL redirects — it has no title/meta by design. The target row holds the content audit.");
            note.getStyleClass().add("detail-note");
            note.setWrapText(true);
            header.getChildren().add(note);
        }
        detailsBox.getChildren().add(header);

        // ---- issues card ----
        VBox issuesCard = new VBox(6);
        issuesCard.getStyleClass().add("detail-card");
        issuesCard.getChildren().add(sectionTitle(p.getIssueList().isEmpty() ? "Issues — none ✔" : "Issues (" + p.getIssueList().size() + ")"));
        if (p.getIssueList().isEmpty()) {
            Label ok = new Label("No issues detected for this URL.");
            ok.getStyleClass().add("detail-ok");
            issuesCard.getChildren().add(ok);
        } else {
            FlowPane chips = new FlowPane();
            chips.setHgap(6); chips.setVgap(6);
            for (String issue : p.getIssueList()) chips.getChildren().add(issueChip(issue));
            issuesCard.getChildren().add(chips);
        }
        detailsBox.getChildren().add(issuesCard);

        // ---- SEO card ----
        VBox seo = new VBox(8);
        seo.getStyleClass().add("detail-card");
        seo.getChildren().add(sectionTitle("On-page SEO"));
        seo.getChildren().add(titleRow(p));
        seo.getChildren().add(metaRow(p));
        GridPane grid = new GridPane();
        grid.setHgap(16); grid.setVgap(6);
        grid.add(kv("H1 (" + p.getH1Count() + ")", p.getH1().isEmpty() ? "—" : p.getH1()), 0, 0);
        grid.add(kv("H2 count", String.valueOf(p.getH2Count()) + (p.getH2Count() == 0 ? " — missing" : "")), 1, 0);
        grid.add(kv("Words", p.getWordCount() + (p.getWordCount() < 200 && p.getStatusCode() == 200 ? " — thin (<200)" : "")), 0, 1);
        grid.add(kv("Meta keywords", p.getMetaKeywords().isEmpty() ? "—" : p.getMetaKeywords()), 1, 1);
        grid.add(kv("Canonical", p.getCanonical().isEmpty() ? "— missing" : p.getCanonical()
                + (p.getCanonical().equalsIgnoreCase(p.getUrl()) ? " (self)" : " (canonicalised)")), 0, 2, 2, 1);
        grid.add(kv("Meta robots", p.getMetaRobots().isEmpty() ? "—" : p.getMetaRobots()), 0, 3);
        grid.add(kv("X-Robots-Tag", p.getXRobots().isEmpty() ? "—" : p.getXRobots()), 1, 3);
        grid.add(kv("Hreflang", String.valueOf(p.getHreflangCount())), 0, 4);
        grid.add(kv("Content type", p.getContentType().isEmpty() ? "—" : p.getContentType()), 1, 4);
        for (var n : grid.getChildren()) GridPane.setHgrow(n, Priority.ALWAYS);
        seo.getChildren().add(grid);
        detailsBox.getChildren().add(seo);

        // ---- crawl / performance card ----
        VBox tech = new VBox(8);
        tech.getStyleClass().add("detail-card");
        tech.getChildren().add(sectionTitle("Crawl & performance"));
        GridPane tgrid = new GridPane();
        tgrid.setHgap(16); tgrid.setVgap(6);
        tgrid.add(kv("Depth", String.valueOf(p.getDepth())), 0, 0);
        HBox linkCounts = new HBox(8, linkBtn("Inlinks: " + p.getInlinks(), () -> detailTabs.getSelectionModel().select(inTab)),
                linkBtn("Outlinks: " + p.getOutlinks(), () -> detailTabs.getSelectionModel().select(outTab)));
        VBox linksBox = new VBox(2, fieldLabel("Links"), linkCounts);
        tgrid.add(linksBox, 1, 0);
        tgrid.add(kv("Images", p.getImageCount() + "  •  missing alt: " + p.getImagesMissingAlt()), 0, 1);
        tgrid.add(kv("Response", p.getResponseTimeMs() + " ms  •  " + fmtBytes(p.getSizeBytes())), 1, 1);
        tgrid.add(kv("Redirect URI", p.getRedirectUri().isEmpty() ? "—" : p.getRedirectUri()), 0, 2, 2, 1);
        tgrid.add(kv("Redirect chain", p.getRedirectChain().isEmpty() ? "—" : p.getRedirectChain()), 0, 3, 2, 1);
        tgrid.add(kv("Content hash", p.getContentHash().isEmpty() ? "—" : shortHash(p.getContentHash())), 0, 4, 2, 1);
        if (!p.getErrorDetail().isEmpty() || p.getFetchAttempts() > 0)
            tgrid.add(kv("Fetch diagnostics (" + p.getFetchAttempts() + " attempts, " + p.getFetchProto() + ")",
                    p.getErrorDetail().isEmpty() ? "—" : p.getErrorDetail()), 0, 5, 2, 1);
        tech.getChildren().add(tgrid);
        detailsBox.getChildren().add(tech);

        outTable.setItems(FXCollections.observableArrayList(p.getOutlinkRefs()));
        List<LinkRef> in = new ArrayList<>(inlinkIndex.getOrDefault(p.getUrl(), List.of()));
        in.sort(Comparator.comparing(l -> nvl(l.getSource())));
        inTable.setItems(FXCollections.observableArrayList(in));
        outTab.setText("Outlinks (" + p.getOutlinkRefs().size() + ")");
        inTab.setText("Inlinks (" + in.size() + ")");

        // Image Details tab (SF parity): per-image src + alt.
        imgTable.setItems(FXCollections.observableArrayList(p.getImageRefs()));
        imgTab.setText("Image Details (" + p.getImageRefs().size() + ")");

        // SERP Snippet tab: Google-style preview.
        serpTitle.setText(p.getTitle().isEmpty() ? p.getUrl() : p.getTitle());
        serpUrl.setText(displayHost(p.getUrl()));
        serpDesc.setText(p.getMetaDescription().isEmpty() ? "No meta description — Google will generate a snippet from page content." : p.getMetaDescription());

        // View Source tab: full source, fetched on open (never stored for every page).
        ensureSource(p);

        // Cookies tab: Set-Cookie response headers.
        cookiesArea.setText(p.getCookies().isEmpty() ? "No cookies set by this URL's response." : p.getCookies());
        cookiesTab.setText("Cookies" + (p.getCookieCount() == 0 ? "" : " (" + p.getCookieCount() + ")"));

        // Structured Data Details tab: JSON-LD count + first block.
        structCount.setText(p.getJsonLdCount() == 0 ? "No JSON-LD structured data found on this page."
                : p.getJsonLdCount() + " JSON-LD block" + (p.getJsonLdCount() == 1 ? "" : "s") + " found — showing the first:");
        structArea.setText(p.getFirstJsonLd());
        structTab.setText("Structured Data Details" + (p.getJsonLdCount() == 0 ? "" : " (" + p.getJsonLdCount() + ")"));

        // HTTP Headers tab.
        headersGrid.getChildren().clear();
        int hr = 0;
        headersGrid.add(kv("Status", p.getStatusCode() + (p.getStatusText().isEmpty() ? "" : " " + p.getStatusText())), 0, hr);
        headersGrid.add(kv("Content-Type", p.getContentType().isEmpty() ? "—" : p.getContentType()), 1, hr++);
        headersGrid.add(kv("Server", p.getServerHeader().isEmpty() ? "—" : p.getServerHeader()), 0, hr);
        headersGrid.add(kv("Content-Length", p.getContentLength().isEmpty() ? "—" : p.getContentLength()), 1, hr++);
        headersGrid.add(kv("X-Robots-Tag", p.getXRobots().isEmpty() ? "—" : p.getXRobots()), 0, hr);
        headersGrid.add(kv("Meta Robots", p.getMetaRobots().isEmpty() ? "—" : p.getMetaRobots()), 1, hr++);
        headersGrid.add(kv("Canonical", p.getCanonical().isEmpty() ? "—" : p.getCanonical()), 0, hr, 2, 1);

        // Duplicate Details tab: pages sharing content hash, title or meta.
        List<CrawledPage> dups = new ArrayList<>();
        for (CrawledPage c : allPages) {
            if (c == p) continue;
            if (!"HTML".equals(c.getContentKind()) || c.getStatusCode() != 200) continue;
            boolean sameHash = !p.getContentHash().isEmpty() && p.getContentHash().equals(c.getContentHash());
            boolean sameTitle = !p.getTitle().isEmpty() && p.getTitle().equalsIgnoreCase(c.getTitle());
            boolean sameMeta = !p.getMetaDescription().isEmpty() && p.getMetaDescription().equalsIgnoreCase(c.getMetaDescription());
            if (sameHash || sameTitle || sameMeta) dups.add(c);
        }
        dups.sort(Comparator.comparing(CrawledPage::getUrl));
        dupTable.setItems(FXCollections.observableArrayList(dups));
        dupTab.setText("Duplicate Details (" + dups.size() + ")");
    }

    /** View Source (fetch-on-open): full source for one URL, small LRU cache. */
    private void ensureSource(CrawledPage p) {
        String cached;
        synchronized (sourceCache) { cached = sourceCache.get(p.getUrl()); }
        if (cached != null) {
            sourceArea.setText(cached);
            sourceTab.setText("View Source (" + fmtBytes(cached.length()) + ")");
            return;
        }
        if (detailTabs.getSelectionModel().getSelectedItem() != sourceTab) {
            sourceArea.setText(p.getHtmlSnippet().isEmpty()
                    ? "Open the View Source tab to load the full source for this URL."
                    : p.getHtmlSnippet() + "\n\n… (stored preview — open the View Source tab for the full source)");
            sourceTab.setText("View Source");
            return;
        }
        fetchSource(p);
    }

    private void fetchSource(CrawledPage p) {
        String url = p.getUrl();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            sourceArea.setText("No fetchable URL for this row.");
            sourceTab.setText("View Source");
            return;
        }
        long myReq = sourceReq.incrementAndGet();
        sourceArea.setText("Fetching full source for " + url + " …");
        sourceTab.setText("View Source (…)");
        Thread.ofVirtual().name("arachnode-source").start(() -> {
            String result;
            try {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(config.timeoutSeconds))
                        .header("User-Agent", config.userAgent)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/*,*/*;q=0.8")
                        .header("Accept-Encoding", "gzip")
                        .GET().build();
                java.net.http.HttpResponse<byte[]> res =
                        SRC_HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                byte[] body = res.body() == null ? new byte[0] : res.body();
                String enc = res.headers().firstValue("content-encoding").orElse("");
                if (body.length > 0 && enc.contains("gzip")) {
                    try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
                        body = gis.readAllBytes();
                    } catch (Exception ignored) { /* show raw bytes as text */ }
                }
                result = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                if (result.isEmpty()) result = "(empty response body)";
            } catch (Exception e) {
                String m = e.getMessage();
                result = "Could not fetch source: " + e.getClass().getSimpleName()
                        + (m == null || m.isBlank() ? "" : " — " + m.trim());
            }
            final String out = result;
            Platform.runLater(() -> {
                if (sourceReq.get() != myReq) return; // superseded
                CrawledPage sel = table.getSelectionModel().getSelectedItem();
                if (sel == null || !sel.getUrl().equals(url)) return; // user moved on
                if (out.startsWith("Could not fetch source:")) {
                    sourceArea.setText(out);
                    sourceTab.setText("View Source");
                } else {
                    synchronized (sourceCache) { sourceCache.put(url, out); }
                    sourceArea.setText(out);
                    sourceTab.setText("View Source (" + fmtBytes(out.length()) + ")");
                }
            });
        });
    }

    private String displayHost(String url) {
        try {
            java.net.URI u = new java.net.URI(url);
            String h = u.getHost() == null ? url : u.getHost();
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            if (path.length() > 48) path = path.substring(0, 48) + "…";
            return h + " › " + (path.isEmpty() ? "/" : path);
        } catch (Exception e) { return url; }
    }

    private Label sectionTitle(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("detail-section");
        return l;
    }

    private Label fieldLabel(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("kv-key");
        return l;
    }

    private VBox kv(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("kv-key");
        Label v = new Label(value == null || value.isEmpty() ? "—" : value);
        v.getStyleClass().add("kv-val");
        v.setWrapText(true);
        v.setTooltip(new Tooltip(v.getText()));
        VBox b = new VBox(1, k, v);
        b.getStyleClass().add("kv-box");
        return b;
    }

    private VBox titleRow(CrawledPage p) {
        VBox b = new VBox(3);
        HBox top = new HBox(8, fieldLabel("Title"), meter(p.getTitleLength(), 30, 60, 70));
        Label v = new Label(p.getTitle().isEmpty() ? "— missing" : p.getTitle());
        v.getStyleClass().add("kv-val-emph");
        v.setWrapText(true);
        Label len = new Label(p.getTitleLength() + " chars — " + titleVerdict(p));
        len.getStyleClass().add(verdictClass(titleVerdict(p)));
        b.getChildren().addAll(top, v, len);
        return b;
    }

    private VBox metaRow(CrawledPage p) {
        VBox b = new VBox(3);
        HBox top = new HBox(8, fieldLabel("Meta description"), meter(p.getMetaDescLength(), 70, 160, 200));
        Label v = new Label(p.getMetaDescription().isEmpty() ? "— missing" : p.getMetaDescription());
        v.getStyleClass().add("kv-val-emph");
        v.setWrapText(true);
        Label len = new Label(p.getMetaDescLength() + " chars — " + metaVerdict(p));
        len.getStyleClass().add(verdictClass(metaVerdict(p)));
        b.getChildren().addAll(top, v, len);
        return b;
    }

    private String titleVerdict(CrawledPage p) {
        if (p.getTitle().isEmpty()) return "missing";
        if (p.getTitleLength() < 30) return "too short (<30)";
        if (p.getTitleLength() > 60) return "too long (>60)";
        return "optimal (30–60)";
    }

    private String metaVerdict(CrawledPage p) {
        if (p.getMetaDescription().isEmpty()) return "missing";
        if (p.getMetaDescLength() < 70) return "too short (<70)";
        if (p.getMetaDescLength() > 160) return "too long (>160)";
        return "optimal (70–160)";
    }

    private String verdictClass(String verdict) {
        if (verdict.startsWith("optimal")) return "verdict-ok";
        if (verdict.equals("missing")) return "verdict-bad";
        return "verdict-warn";
    }

    private ProgressBar meter(int len, int lo, int hi, int max) {
        ProgressBar bar = new ProgressBar(Math.min(1.0, len / (double) max));
        bar.setPrefWidth(110); bar.setMinWidth(80); bar.setMaxHeight(8); bar.setPrefHeight(8);
        String cls = len == 0 ? "meter-bad" : (len < lo || len > hi) ? "meter-warn" : "meter-ok";
        bar.getStyleClass().addAll("meter", cls);
        return bar;
    }

    private Label statusBadge(int code, String text) {
        Label l = new Label(code + (text == null || text.isEmpty() ? "" : " " + text));
        l.getStyleClass().addAll("badge", code == 0 ? "badge-blocked" : code < 300 ? "badge-ok" : code < 400 ? "badge-redirect" : code < 500 ? "badge-warn" : "badge-bad");
        return l;
    }

    private Label kindBadge(String kind) {
        Label l = new Label(kind == null || kind.isEmpty() ? "Unknown" : kind);
        l.getStyleClass().addAll("badge", "badge-neutral");
        return l;
    }

    private Label indexBadge(String idx) {
        Label l = new Label(idx == null || idx.isEmpty() ? "Indexable" : idx);
        l.getStyleClass().addAll("badge", idx != null && idx.contains("Non") ? "badge-warn" : "badge-ok-soft");
        return l;
    }

    private Label issueChip(String issue) {
        Label l = new Label(issue);
        String cls = "chip-info";
        String low = issue.toLowerCase();
        if (low.contains("missing") || low.contains("error") || low.contains("blocked") || low.contains("5xx") || low.contains("server error") || low.contains("noindex") && low.contains("4")) cls = "chip-bad";
        else if (low.contains("client error") || low.contains("404") || low.contains("4xx") || low.contains("redirect without") || low.contains("loop")) cls = "chip-bad";
        else if (low.contains("too long") || low.contains("too short") || low.contains("thin") || low.contains("long url") || low.contains("multiple h1") || low.contains("missing alt") || low.contains("canonical")) cls = "chip-warn";
        else if (low.contains("duplicate") || low.contains("redirect")) cls = "chip-info";
        l.getStyleClass().addAll("chip", cls);
        l.setTooltip(new Tooltip("Click Issues tab filter: " + issue.split("\\(")[0].trim()));
        l.setOnMouseClicked(e -> { issueFilter = issue.split("\\(")[0].trim(); issueBox.setValue(issueFilter); selectTab("Issues"); });
        return l;
    }

    private Button linkBtn(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("mini-btn");
        b.setOnAction(e -> action.run());
        return b;
    }

    private String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.2f MB", b / 1024.0 / 1024.0);
    }

    private String shortHash(String h) { return h.length() > 16 ? h.substring(0, 16) + "…" : h; }

    private void refreshIssues() {
        List<String> rows = new ArrayList<>();
        issueCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> rows.add(e.getValue() + " ×  " + e.getKey()));
        issuesView.setItems(FXCollections.observableArrayList(rows));
        // Keep issue dropdown in sync (SF Overview parity)
        if (issueBox != null) {
            String cur = issueBox.getValue();
            Set<String> keys = new TreeSet<>(issueCounts.keySet());
            List<String> opts = new ArrayList<>();
            opts.add("All Issues");
            opts.addAll(keys);
            issueBox.setItems(FXCollections.observableArrayList(opts));
            if (cur != null && opts.contains(cur)) issueBox.setValue(cur);
            else if (!issueFilter.isEmpty() && opts.contains(issueFilter)) issueBox.setValue(issueFilter);
            else issueBox.setValue("All Issues");
        }
    }

    private void rebuildIssueCounts() {
        issueCounts.clear();
        for (CrawledPage p : allPages) {
            if ("External".equals(p.getContentKind())) continue;
            for (String i : p.getIssueList()) issueCounts.merge(i.split("\\(")[0].trim(), 1, Integer::sum);
        }
        refreshIssues();
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
        catch (Exception e) {
            var a = new Alert(Alert.AlertType.ERROR);
            a.setTitle("Export Failed");
            a.setHeaderText(null);
            a.setContentText("Export failed: " + e.getMessage());
            brandDialog(a);
            a.showAndWait();
        }
    }

    private void doExportSitemap() {
        FileChooser fc = new FileChooser(); fc.setInitialFileName("sitemap.xml");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML", "*.xml"));
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try { CrawlExporter.toSitemap(new ArrayList<>(allPages), f.toPath()); setStatus("Exported sitemap: " + f); }
        catch (Exception e) {
            var a = new Alert(Alert.AlertType.ERROR);
            a.setTitle("Export Failed");
            a.setHeaderText(null);
            a.setContentText("Export failed: " + e.getMessage());
            brandDialog(a);
            a.showAndWait();
        }
    }

    public void stop() { if (crawler != null) crawler.stop(); }
}
