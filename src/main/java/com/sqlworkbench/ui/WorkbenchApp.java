package com.sqlworkbench.ui;

import com.sqlworkbench.core.AppConfig;
import com.sqlworkbench.core.ConnectionSpec;
import com.sqlworkbench.core.Database;
import com.sqlworkbench.core.QueryHistory;
import com.sqlworkbench.core.SchemaReader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WorkbenchApp extends Application {

    private static final String TITLE = "SQL Workbench";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "workbench-db");
        thread.setDaemon(true);
        return thread;
    });
    private final TreeView<Object> schemaTree = new TreeView<>();
    private final ListView<QueryHistory.Entry> historyList = new ListView<>();
    private final TabPane tabs = new TabPane();
    private final Label status = new Label();
    private AppConfig config;
    private QueryHistory history;
    private Database database;
    private Stage stage;
    private int tabCount;

    @Override
    public void start(Stage stage) throws IOException {
        this.stage = stage;
        config = AppConfig.load(Path.of("workbench.properties"));
        history = new QueryHistory(config.historyFile(), config.historyLimit());

        schemaTree.setRoot(new TreeItem<>("no database open"));
        schemaTree.setCellFactory(tree -> new TreeCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : schemaText(item));
            }
        });
        schemaTree.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                previewSelectedTable();
            }
        });

        historyList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(QueryHistory.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : historyText(entry));
            }
        });
        historyList.setOnMouseClicked(event -> {
            QueryHistory.Entry entry = historyList.getSelectionModel().getSelectedItem();
            if (event.getClickCount() == 2 && entry != null) {
                currentTab().setSql(entry.sql());
            }
        });

        tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected != null) {
                queryTab(selected).focusEditor();
            }
        });

        SplitPane sidebar = new SplitPane(titled("Schema", schemaTree), titled("History", historyList));
        sidebar.setOrientation(Orientation.VERTICAL);
        sidebar.setDividerPositions(0.6);

        SplitPane main = new SplitPane(sidebar, tabs);
        main.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(sidebar, false);

        status.setPadding(new Insets(4, 8, 4, 8));
        status.setText("no database open");

        BorderPane root = new BorderPane();
        root.setTop(menuBar());
        root.setCenter(main);
        root.setBottom(status);

        Scene scene = new Scene(root, 1200, 760);
        scene.getStylesheets().add(WorkbenchApp.class.getResource("workbench.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle(TITLE);
        stage.show();

        newTab();
        refreshHistory();

        List<String> args = getParameters().getUnnamed();
        if (!args.isEmpty()) {
            openDatabase(Path.of(args.get(0)), false);
        }
    }

    @Override
    public void stop() {
        for (Tab tab : tabs.getTabs()) {
            QueryTab queryTab = queryTab(tab);
            queryTab.cancel();
            queryTab.useDatabase(null);
        }
        Database open = database;
        database = null;
        if (open != null) {
            worker.execute(open::close);
        }
        worker.shutdown();
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private MenuBar menuBar() {
        MenuItem open = new MenuItem("Open database");
        open.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        open.setOnAction(event -> chooseDatabase(false));
        MenuItem create = new MenuItem("New database");
        create.setOnAction(event -> chooseDatabase(true));
        MenuItem close = new MenuItem("Close database");
        close.setOnAction(event -> closeDatabase());
        MenuItem newTab = new MenuItem("New tab");
        newTab.setAccelerator(KeyCombination.keyCombination("Shortcut+T"));
        newTab.setOnAction(event -> newTab());
        MenuItem quit = new MenuItem("Quit");
        quit.setOnAction(event -> Platform.exit());
        Menu file = new Menu("File");
        file.getItems().addAll(open, create, close, new SeparatorMenuItem(), newTab, new SeparatorMenuItem(), quit);

        MenuItem refresh = new MenuItem("Refresh schema");
        refresh.setAccelerator(KeyCombination.keyCombination("F5"));
        refresh.setOnAction(event -> refreshSchema());
        MenuItem clearHistory = new MenuItem("Clear history");
        clearHistory.setOnAction(event -> clearHistory());
        Menu view = new Menu("View");
        view.getItems().addAll(refresh, clearHistory);

        MenuBar bar = new MenuBar();
        bar.getMenus().addAll(file, view);
        return bar;
    }

    private static VBox titled(String title, javafx.scene.Node content) {
        Label label = new Label(title);
        label.getStyleClass().add("pane-title");
        VBox box = new VBox(label, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    private void chooseDatabase(boolean create) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(create ? "New database" : "Open database");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Database files", "*.db", "*.sqlite", "*.sqlite3", "*.duckdb", "*.ddb"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = create ? chooser.showSaveDialog(stage) : chooser.showOpenDialog(stage);
        if (file != null) {
            openDatabase(file.toPath(), create);
        }
    }

    private void openDatabase(Path file, boolean create) {
        ConnectionSpec spec = ConnectionSpec.of(file);
        detachTabs();
        Database previous = database;
        database = null;
        status.setText("opening " + spec.label());
        Task<Database> task = new Task<>() {
            @Override
            protected Database call() {
                if (previous != null) {
                    previous.close();
                }
                return Database.open(spec, create);
            }
        };
        task.setOnSucceeded(event -> {
            database = task.getValue();
            stage.setTitle(TITLE + " - " + spec.label());
            status.setText("connected to " + spec.label());
            for (Tab tab : tabs.getTabs()) {
                queryTab(tab).useDatabase(database);
            }
            refreshSchema();
        });
        task.setOnFailed(event -> {
            stage.setTitle(TITLE);
            status.setText("no database open");
            schemaTree.setRoot(new TreeItem<>("no database open"));
            showError("Could not open the database", task.getException().getMessage());
        });
        worker.execute(task);
    }

    private void closeDatabase() {
        detachTabs();
        Database previous = database;
        database = null;
        if (previous != null) {
            worker.execute(previous::close);
        }
        schemaTree.setRoot(new TreeItem<>("no database open"));
        stage.setTitle(TITLE);
        status.setText("no database open");
    }

    private void detachTabs() {
        for (Tab tab : tabs.getTabs()) {
            QueryTab queryTab = queryTab(tab);
            queryTab.cancel();
            queryTab.useDatabase(null);
        }
    }

    private void refreshSchema() {
        Database open = database;
        if (open == null) {
            return;
        }
        Task<List<SchemaReader.Table>> task = new Task<>() {
            @Override
            protected List<SchemaReader.Table> call() {
                return open.schema();
            }
        };
        task.setOnSucceeded(event -> {
            if (open == database) {
                schemaTree.setRoot(schemaRoot(open.spec().label(), task.getValue()));
                status.setText(schemaSummary(task.getValue()));
            }
        });
        task.setOnFailed(event -> status.setText("could not read the schema: " + task.getException().getMessage()));
        worker.execute(task);
    }

    private static TreeItem<Object> schemaRoot(String label, List<SchemaReader.Table> tables) {
        TreeItem<Object> root = new TreeItem<>(label);
        root.setExpanded(true);
        for (SchemaReader.Table table : tables) {
            TreeItem<Object> node = new TreeItem<>(table);
            for (SchemaReader.Column column : table.columns()) {
                node.getChildren().add(new TreeItem<>(column));
            }
            root.getChildren().add(node);
        }
        return root;
    }

    private static String schemaSummary(List<SchemaReader.Table> tables) {
        long views = tables.stream().filter(SchemaReader.Table::isView).count();
        long plain = tables.size() - views;
        return "connected, " + plain + (plain == 1 ? " table" : " tables") + " and " + views + (views == 1 ? " view" : " views");
    }

    private static String schemaText(Object item) {
        if (item instanceof SchemaReader.Table table) {
            return table.isView() ? table.name() + "  (view)" : table.name();
        }
        if (item instanceof SchemaReader.Column column) {
            String type = column.typeName() == null ? "" : column.typeName();
            return column.name() + "  " + type;
        }
        return item.toString();
    }

    private void previewSelectedTable() {
        TreeItem<Object> selected = schemaTree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() instanceof SchemaReader.Table table) {
            currentTab().runSql("select * from " + quote(table.name()));
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private void newTab() {
        tabCount++;
        QueryTab queryTab = new QueryTab("query " + tabCount, worker, config, history, this::afterRun);
        queryTab.useDatabase(database);
        Tab tab = queryTab.tab();
        tab.setUserData(queryTab);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    private QueryTab currentTab() {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        if (selected == null) {
            newTab();
            selected = tabs.getSelectionModel().getSelectedItem();
        }
        return queryTab(selected);
    }

    private static QueryTab queryTab(Tab tab) {
        return (QueryTab) tab.getUserData();
    }

    private void afterRun(List<String> statements) {
        refreshHistory();
        for (String sql : statements) {
            String start = sql.stripLeading().toLowerCase(Locale.ROOT);
            if (start.startsWith("create") || start.startsWith("drop") || start.startsWith("alter")) {
                refreshSchema();
                return;
            }
        }
    }

    private void refreshHistory() {
        Task<List<QueryHistory.Entry>> task = new Task<>() {
            @Override
            protected List<QueryHistory.Entry> call() throws IOException {
                return history.recent();
            }
        };
        task.setOnSucceeded(event -> historyList.getItems().setAll(task.getValue()));
        task.setOnFailed(event -> status.setText("could not read the history file"));
        worker.execute(task);
    }

    private void clearHistory() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws IOException {
                history.clear();
                return null;
            }
        };
        task.setOnSucceeded(event -> historyList.getItems().clear());
        task.setOnFailed(event -> status.setText("could not clear the history file"));
        worker.execute(task);
    }

    private static String historyText(QueryHistory.Entry entry) {
        String first = entry.sql().strip().lines().findFirst().orElse("");
        if (first.length() > 60) {
            first = first.substring(0, 57) + "...";
        }
        String outcome = entry.ok() ? entry.rows() + " rows" : "failed";
        return first + "\n" + TIME.format(entry.at()) + "   " + entry.millis() + " ms   " + outcome;
    }

    private void showError(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(TITLE);
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
