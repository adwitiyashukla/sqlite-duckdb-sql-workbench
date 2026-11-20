package com.sqlworkbench.ui;

import com.sqlworkbench.core.AppConfig;
import com.sqlworkbench.core.ColumnMeta;
import com.sqlworkbench.core.CsvExporter;
import com.sqlworkbench.core.Database;
import com.sqlworkbench.core.PagedReader;
import com.sqlworkbench.core.QueryException;
import com.sqlworkbench.core.QueryHistory;
import com.sqlworkbench.core.QueryRunner;
import com.sqlworkbench.core.ResultPage;
import com.sqlworkbench.core.SqlSplitter;
import com.sqlworkbench.core.SqlTokens;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Task;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

final class QueryTab {

    private record RunResult(String sql, PagedReader reader, ResultPage firstPage, int updated, long millis) {
    }

    private final Tab tab;
    private final CodeArea editor = new CodeArea();
    private final TableView<List<String>> table = new TableView<>();
    private final Button runButton = new Button("Run");
    private final Button cancelButton = new Button("Cancel");
    private final Button moreButton = new Button("Load more");
    private final Button exportButton = new Button("Export CSV");
    private final Label info = new Label();
    private final ExecutorService worker;
    private final AppConfig config;
    private final QueryHistory history;
    private final Consumer<List<String>> afterRun;
    private QueryRunner runner;
    private PagedReader reader;
    private List<ColumnMeta> columns = List.of();
    private String lastRowsSql;
    private boolean busy;

    QueryTab(String title, ExecutorService worker, AppConfig config, QueryHistory history,
             Consumer<List<String>> afterRun) {
        this.worker = worker;
        this.config = config;
        this.history = history;
        this.afterRun = afterRun;

        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.textProperty().addListener((observable, oldText, newText) -> editor.setStyleSpans(0, highlight(newText)));
        editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.ENTER) {
                runEditor();
                event.consume();
            }
        });

        table.setPlaceholder(new Label("no results yet"));
        table.getSelectionModel().setCellSelectionEnabled(true);

        runButton.setOnAction(event -> runEditor());
        cancelButton.setOnAction(event -> cancel());
        moreButton.setOnAction(event -> loadMore());
        exportButton.setOnAction(event -> export());

        ToolBar toolbar = new ToolBar(runButton, cancelButton, new Separator(Orientation.VERTICAL),
                moreButton, exportButton, new Separator(Orientation.VERTICAL), info);

        SplitPane split = new SplitPane(new VirtualizedScrollPane<>(editor), table);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.35);

        BorderPane content = new BorderPane();
        content.setTop(toolbar);
        content.setCenter(split);

        tab = new Tab(title, content);
        tab.setOnClosed(event -> {
            cancel();
            closeReader();
        });
        updateButtons();
    }

    Tab tab() {
        return tab;
    }

    void focusEditor() {
        if (editor.getScene() != null) {
            Platform.runLater(editor::requestFocus);
            return;
        }
        editor.sceneProperty().addListener(new ChangeListener<Scene>() {
            @Override
            public void changed(ObservableValue<? extends Scene> observable, Scene previous, Scene scene) {
                if (scene != null) {
                    editor.sceneProperty().removeListener(this);
                    Platform.runLater(editor::requestFocus);
                }
            }
        });
    }

    void setSql(String sql) {
        editor.replaceText(sql);
        editor.requestFocus();
    }

    void runSql(String sql) {
        setSql(sql);
        runEditor();
    }

    void useDatabase(Database database) {
        closeReader();
        clearResults();
        runner = database == null ? null : database.runner(config);
        setInfo(database == null ? "no database open" : "ready, Ctrl+Enter runs the editor", false);
        updateButtons();
    }

    void cancel() {
        QueryRunner active = runner;
        if (busy && active != null) {
            active.cancel();
            setInfo("cancelling", false);
        }
    }

    static StyleSpans<Collection<String>> highlight(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int position = 0;
        for (SqlTokens.Token token : SqlTokens.tokenize(text)) {
            spans.add(Collections.emptyList(), token.start() - position);
            spans.add(Collections.singleton(token.kind().name().toLowerCase(Locale.ROOT)), token.end() - token.start());
            position = token.end();
        }
        spans.add(Collections.emptyList(), text.length() - position);
        return spans.create();
    }

    private void runEditor() {
        String selected = editor.getSelectedText();
        String script = selected == null || selected.isBlank() ? editor.getText() : selected;
        run(SqlSplitter.split(script));
    }

    private void run(List<String> statements) {
        if (runner == null) {
            setInfo("open a database first", true);
            return;
        }
        if (busy || statements.isEmpty()) {
            return;
        }
        closeReader();
        clearResults();
        setBusy(true);
        setInfo(statements.size() == 1 ? "running" : "running " + statements.size() + " statements", false);
        QueryRunner active = runner;
        Task<RunResult> task = new Task<>() {
            @Override
            protected RunResult call() {
                RunResult result = null;
                for (String sql : statements) {
                    if (result != null && result.reader() != null) {
                        result.reader().close();
                    }
                    result = runOne(active, sql);
                }
                return result;
            }
        };
        task.setOnSucceeded(event -> {
            RunResult result = task.getValue();
            setBusy(false);
            if (active != runner) {
                if (result.reader() != null) {
                    worker.execute(result.reader()::close);
                }
                return;
            }
            showResult(result, statements.size());
            afterRun.accept(statements);
        });
        task.setOnFailed(event -> {
            setBusy(false);
            setInfo(errorText(task.getException(), active), true);
            afterRun.accept(statements);
        });
        worker.execute(task);
    }

    private RunResult runOne(QueryRunner active, String sql) {
        long started = System.nanoTime();
        try {
            RunResult result = switch (active.run(sql)) {
                case QueryRunner.Rows rows -> new RunResult(sql, rows.reader(), firstPage(rows.reader()), 0, elapsed(started));
                case QueryRunner.Updated updated -> new RunResult(sql, null, null, updated.count(), elapsed(started));
            };
            long rowCount = result.reader() != null ? result.firstPage().size() : result.updated();
            record(new QueryHistory.Entry(Instant.now(), sql, result.millis(), rowCount, true));
            return result;
        } catch (QueryException e) {
            record(new QueryHistory.Entry(Instant.now(), sql, elapsed(started), 0, false));
            throw e;
        }
    }

    private static ResultPage firstPage(PagedReader reader) {
        try {
            return reader.nextPage();
        } catch (RuntimeException e) {
            reader.close();
            throw e;
        }
    }

    private void record(QueryHistory.Entry entry) {
        try {
            history.add(entry);
        } catch (IOException ignored) {
        }
    }

    private void showResult(RunResult result, int statementCount) {
        String prefix = statementCount > 1 ? "ran " + statementCount + " statements, " : "";
        if (result.reader() != null) {
            reader = result.reader();
            lastRowsSql = result.sql();
            showColumns(result.firstPage().columns());
            table.getItems().setAll(result.firstPage().rows());
            setInfo(prefix + rowsText() + " in " + result.millis() + " ms", false);
        } else {
            setInfo(prefix + result.updated() + " rows changed in " + result.millis() + " ms", false);
        }
        updateButtons();
    }

    private void showColumns(List<ColumnMeta> metas) {
        columns = metas;
        List<TableColumn<List<String>, String>> created = new ArrayList<>();
        for (int i = 0; i < metas.size(); i++) {
            int index = i;
            String name = metas.get(i).name();
            TableColumn<List<String>, String> column = new TableColumn<>(name);
            column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().get(index)));
            column.setSortable(false);
            column.setPrefWidth(Math.max(90, Math.min(320, name.length() * 9 + 40)));
            created.add(column);
        }
        table.getColumns().setAll(created);
    }

    private void loadMore() {
        PagedReader active = reader;
        if (busy || active == null || !active.hasMore()) {
            return;
        }
        setBusy(true);
        setInfo("loading more rows", false);
        long started = System.nanoTime();
        Task<ResultPage> task = new Task<>() {
            @Override
            protected ResultPage call() {
                return active.nextPage();
            }
        };
        task.setOnSucceeded(event -> {
            setBusy(false);
            if (active != reader) {
                return;
            }
            table.getItems().addAll(task.getValue().rows());
            setInfo(rowsText() + ", last page took " + elapsed(started) + " ms", false);
            updateButtons();
        });
        task.setOnFailed(event -> {
            setBusy(false);
            setInfo(errorText(task.getException(), runner), true);
        });
        worker.execute(task);
    }

    private void export() {
        if (busy || lastRowsSql == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export results to CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        chooser.setInitialFileName("results.csv");
        File file = chooser.showSaveDialog(tab.getTabPane().getScene().getWindow());
        if (file == null) {
            return;
        }
        String sql = lastRowsSql;
        QueryRunner active = runner;
        boolean rerun = SqlTokens.isReadOnly(sql);
        List<List<String>> loaded = List.copyOf(table.getItems());
        List<ColumnMeta> loadedColumns = columns;
        setBusy(true);
        setInfo(rerun ? "exporting every row" : "exporting the loaded rows", false);
        long started = System.nanoTime();
        Task<Long> task = new Task<>() {
            @Override
            protected Long call() throws IOException {
                try (Writer out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                    if (rerun) {
                        return active.exportCsv(sql, out);
                    }
                    return CsvExporter.write(new ResultPage(loadedColumns, loaded, 0, false), out);
                }
            }
        };
        task.setOnSucceeded(event -> {
            setBusy(false);
            setInfo("exported " + task.getValue() + " rows to " + file.getName() + " in " + elapsed(started) + " ms", false);
        });
        task.setOnFailed(event -> {
            setBusy(false);
            setInfo(errorText(task.getException(), active), true);
        });
        worker.execute(task);
    }

    private String rowsText() {
        String text = table.getItems().size() + " rows";
        if (reader != null && reader.wasCapped()) {
            return text + ", stopped at the row cap of " + config.maxRows();
        }
        if (reader != null && reader.hasMore()) {
            return text + ", more available";
        }
        return text;
    }

    private String errorText(Throwable error, QueryRunner active) {
        if (active != null && active.wasCancelled()) {
            return "cancelled";
        }
        String message = error == null ? null : error.getMessage();
        return "error: " + (message == null ? "unknown failure" : message);
    }

    private void clearResults() {
        lastRowsSql = null;
        columns = List.of();
        table.getColumns().clear();
        table.getItems().clear();
    }

    private void closeReader() {
        PagedReader old = reader;
        reader = null;
        if (old != null) {
            worker.execute(old::close);
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        updateButtons();
    }

    private void setInfo(String text, boolean error) {
        info.setText(text);
        info.getStyleClass().remove("info-error");
        if (error) {
            info.getStyleClass().add("info-error");
        }
    }

    private void updateButtons() {
        runButton.setDisable(busy || runner == null);
        cancelButton.setDisable(!busy);
        moreButton.setDisable(busy || reader == null || !reader.hasMore());
        exportButton.setDisable(busy || lastRowsSql == null);
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
