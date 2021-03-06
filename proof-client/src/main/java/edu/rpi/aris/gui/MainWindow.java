package edu.rpi.aris.gui;

import edu.rpi.aris.LibAris;
import edu.rpi.aris.assign.EditMode;
import edu.rpi.aris.assign.ModuleUI;
import edu.rpi.aris.assign.ModuleUIListener;
import edu.rpi.aris.assign.ModuleUIOptions;
import edu.rpi.aris.gui.event.GoalChangedEvent;
import edu.rpi.aris.gui.event.LineChangedEvent;
import edu.rpi.aris.gui.event.PremiseChangeEvent;
import edu.rpi.aris.gui.event.RuleChangeEvent;
import edu.rpi.aris.proof.*;
import edu.rpi.aris.rules.Rule;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.tuple.Triple;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class MainWindow implements StatusChangeListener, SaveInfoListener, ModuleUI<LibAris> {

    public static final HashMap<Proof.Status, Image> STATUS_ICONS = new HashMap<>();
    private static FileChooser.ExtensionFilter extensionFilter = new FileChooser.ExtensionFilter("Bram Proof File (." + SaveManager.FILE_EXTENSION + ")", "*." + SaveManager.FILE_EXTENSION);
    private static FileChooser.ExtensionFilter allFiles = new FileChooser.ExtensionFilter("All Files", "*");

    static {
        for (Proof.Status status : Proof.Status.values())
            STATUS_ICONS.put(status, new Image(MainWindow.class.getResourceAsStream(status.imgName)));
    }

    public final EditMode editMode;
    @FXML
    private VBox proofTable;
    @FXML
    private ScrollPane scrollPane;
    @FXML
    private ScrollPane goalScroll;
    @FXML
    private Label statusLbl;
    @FXML
    private Label goalLbl;
    @FXML
    private Label errorRangeLbl;
    @FXML
    private GridPane operatorPane;
    @FXML
    private GridPane constantPane;
    @FXML
    private VBox rulesPane;
    @FXML
    private TitledPane oprTitlePane;
    @FXML
    private TitledPane constTitlePane;
    @FXML
    private BorderPane proofBorderPane;
    @FXML
    private HBox descriptionBox;
    @FXML
    private Label descriptionText;
    @FXML
    private Label arisAssignLbl;
    @FXML
    private Button uploadBtn;
    private ObjectProperty<Font> fontObjectProperty;
    private ArrayList<ProofLine> proofLines = new ArrayList<>();
    private ArrayList<GoalLine> goalLines = new ArrayList<>();
    private SimpleIntegerProperty selectedLine = new SimpleIntegerProperty(-1);
    private Proof proof;
    private Stage primaryStage;
    private GuiConfig configuration = GuiConfig.getConfigManager();
    private RulesManager rulesManager;
    private File saveFile = null;
    private HistoryManager history = new HistoryManager(this);
    private CopyManager copyManager = new CopyManager(this);
    private boolean loaded = false;
    private SaveManager saveManager;
    private Node headerNode;
    private ArisProofProblem assignProblem;
    private ModuleUIListener moduleUIListener;
    private MenuItem saveProof;
    private MenuItem saveAsProof;
    private ModuleUIOptions moduleOptions;
    private RuleRestrictionUI ruleRestrictionUI;

    public MainWindow(Stage primaryStage, EditMode editMode) throws IOException {
        this(primaryStage, editMode, null);
    }

    public MainWindow(Stage primaryStage, EditMode editMode, Node headerNode) throws IOException {
        this(primaryStage, new Proof(LibAris.getInstance().getProperties().get("username") == null ? "UNKNOWN" : LibAris.getInstance().getProperties().get("username")), editMode, headerNode);
        proof.saved();
    }

    public MainWindow(Stage primaryStage, Proof proof, EditMode editMode) throws IOException {
        this(primaryStage, proof, editMode, null);
    }

    public MainWindow(Stage primaryStage, Proof proof, EditMode editMode, Node headerNode) throws IOException {
        Objects.requireNonNull(primaryStage);
        Objects.requireNonNull(proof);
        Objects.requireNonNull(editMode);
        this.primaryStage = primaryStage;
        this.proof = proof;
        this.editMode = editMode;
        this.headerNode = headerNode;
        primaryStage.setTitle(LibAris.NAME);
        primaryStage.setOnHidden(windowEvent -> new Thread(() -> {
            if (moduleUIListener != null)
                moduleUIListener.guiClosed();
            System.gc();
        }).start());
        primaryStage.setOnCloseRequest(event -> {
            if ((moduleOptions == null || moduleOptions.warnBeforeUnsavedClose()) && proof.isModified()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Exit?");
                alert.setHeaderText("Are you sure you want to exit?");
                alert.setContentText("There are unsaved changes in your proof that will be lost.");
                alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
                alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
                Optional<ButtonType> result = alert.showAndWait();
                if (!result.isPresent() || result.get() != ButtonType.YES)
                    event.consume();
            } else if (moduleUIListener != null && !moduleUIListener.guiCloseRequest(proof.isModified())) {
                event.consume();
            }
        });
        saveManager = new SaveManager(this);
        fontObjectProperty = new SimpleObjectProperty<>(new Font(14));
        rulesManager = new RulesManager(proof.getAllowedRules());
        ruleRestrictionUI = new RuleRestrictionUI(primaryStage, rulesManager);
        rulesManager.addRuleSelectionHandler(ruleSelectEvent -> {
            if (selectedLine.get() > -1) {
                Line line = proof.getLine(selectedLine.get());
                if (!line.isAssumption()) {
                    RuleChangeEvent event = new RuleChangeEvent(selectedLine.get(), line.getSelectedRule(), ruleSelectEvent.getRule());
                    line.setSelectedRule(ruleSelectEvent.getRule());
                    history.addHistoryEvent(event);
                }
            }
        });
        setupScene();
        selectedLine.addListener((observableValue, oldVal, newVal) -> {
            errorRangeLbl.textProperty().unbind();
            if (newVal.intValue() >= 0) {
                proof.getLine(newVal.intValue()).verifyClaim();
                proofLines.get(newVal.intValue()).requestFocus();
            } else if (newVal.intValue() < -1) {
                goalLines.get(newVal.intValue() * -1 - 2).requestFocus();
                Goal goal = proof.getGoal(newVal.intValue() * -1 - 2);
                statusString(goal, goal.getStatusString());
            }
            updateHighlighting(newVal.intValue());
        });
    }

    private static File showSaveDialog(Window parent, String defaultFileName) throws IOException {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialDirectory(GuiConfig.getConfigManager().getSaveDirectory());
        fileChooser.getExtensionFilters().add(extensionFilter);
        fileChooser.getExtensionFilters().add(allFiles);
        fileChooser.setSelectedExtensionFilter(extensionFilter);
        fileChooser.setTitle("Save Proof");
        fileChooser.setInitialFileName(defaultFileName);
        File f = fileChooser.showSaveDialog(parent);
        if (f != null) {
            f = f.getCanonicalFile();
            GuiConfig.getConfigManager().setSaveDirectory(f.getParentFile());
        }
        return f;
    }

    private static File showOpenDialog(Window parent) throws IOException {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialDirectory(GuiConfig.getConfigManager().getSaveDirectory());
        fileChooser.getExtensionFilters().add(extensionFilter);
        fileChooser.getExtensionFilters().add(allFiles);
        fileChooser.setSelectedExtensionFilter(extensionFilter);
        fileChooser.setTitle("Open Proof");
        File f = fileChooser.showOpenDialog(parent);
        if (f != null) {
            f = f.getCanonicalFile();
            GuiConfig.getConfigManager().setSaveDirectory(f.getParentFile());
            if (!f.getName().toLowerCase().endsWith("." + SaveManager.FILE_EXTENSION))
                f = new File(f.getParent(), f.getName() + "." + SaveManager.FILE_EXTENSION);
        }
        return f;
    }

    private void setupScene() throws IOException {
        FXMLLoader loader = new FXMLLoader(Aris.class.getResource("main_window.fxml"));
        loader.setController(this);
        Parent root = loader.load();
        VBox box = new VBox();
        MenuBar bar = setupMenu();
        box.getChildren().add(bar);
        if (headerNode != null)
            box.getChildren().add(headerNode);
        BorderPane pane = new BorderPane();
        pane.setTop(box);
        pane.setCenter(root);
        Scene scene = new Scene(pane, 1000, 800);
        primaryStage.setScene(scene);
        scene.setOnKeyPressed(this::handleKeyEvent);
    }

    private synchronized void lineUp() {
        if (selectedLine.get() > 0) {
            requestFocus(selectedLine.get() - 1);
            autoScroll(scrollPane.getContent().getBoundsInLocal());
        } else if (selectedLine.get() < -2) {
            requestFocus(selectedLine.get() + 1);
            autoScroll(goalScroll.getContent().getBoundsInLocal());
        } else if (selectedLine.get() == -2) {
            requestFocus(proof.getNumLines() - 1);
            autoScroll(scrollPane.getContent().getBoundsInLocal());
        }
    }

    private synchronized void lineDown() {
        if (selectedLine.get() >= 0 && selectedLine.get() + 1 < proof.getNumLines()) {
            requestFocus(selectedLine.get() + 1);
            autoScroll(scrollPane.getContent().getBoundsInLocal());
        } else if (selectedLine.get() < -1 && selectedLine.get() > proof.getNumGoals() * -1 - 1) {
            requestFocus(selectedLine.get() - 1);
            autoScroll(goalScroll.getContent().getBoundsInLocal());
        } else if (selectedLine.get() == proof.getNumLines() - 1) {
            requestFocus(-2);
            autoScroll(scrollPane.getContent().getBoundsInLocal());
        }
    }

    public boolean handleKeyEvent(KeyEvent keyEvent) {
        switch (keyEvent.getCode()) {
            case UP:
                lineUp();
                break;
            case DOWN:
                lineDown();
                break;
            default:
                return false;
        }
        return true;
    }

    private MenuBar setupMenu() {
        MenuBar bar = new MenuBar();

        Menu file = new Menu("File");
        Menu edit = new Menu("Edit");
        Menu proof = new Menu("Proof");
        Menu submit = new Menu("Submit");
        Menu help = new Menu("Help");

        // File menu items

        MenuItem newProof = new MenuItem("New Proof");
        MenuItem openProof = new MenuItem("Open Proof");
        saveProof = new MenuItem("Save Proof");
        saveAsProof = new MenuItem("Save Proof As");
        MenuItem quit = new MenuItem("Quit");

        newProof.setOnAction(actionEvent -> newProof());
        openProof.setOnAction(actionEvent -> openProof());
        saveAsProof.setOnAction(actionEvent -> saveProof(true));
        saveProof.setOnAction(actionEvent -> saveProof(false));

        newProof.acceleratorProperty().bind(configuration.newProofKey);
        openProof.acceleratorProperty().bind(configuration.openProofKey);
        saveProof.acceleratorProperty().bind(configuration.saveProofKey);
        saveAsProof.acceleratorProperty().bind(configuration.saveAsProofKey);

        file.getItems().addAll(newProof, openProof, saveProof, saveAsProof, quit);

        // Edit menu items

        MenuItem undo = new MenuItem("Undo");
        MenuItem redo = new MenuItem("Redo");
        MenuItem copy = new MenuItem("Copy");
        MenuItem cut = new MenuItem("Cut");
        MenuItem paste = new MenuItem("Paste");
        MenuItem settings = new MenuItem("Settings");

        undo.disableProperty().bind(history.canUndo().not());
        redo.disableProperty().bind(history.canRedo().not());

        undo.setOnAction(actionEvent -> {
            copyManager.deselect();
            history.undo();
        });
        redo.setOnAction(actionEvent -> {
            copyManager.deselect();
            history.redo();
        });

        copy.setOnAction(actionEvent -> copyManager.copy());
        cut.setOnAction(actionEvent -> copyManager.cut());
        paste.setOnAction(actionEvent -> copyManager.paste());

        settings.setOnAction(actionEvent -> GuiConfig.getConfigManager().showConfig());

        undo.acceleratorProperty().bind(configuration.undoKey);
        redo.acceleratorProperty().bind(configuration.redoKey);
        copy.acceleratorProperty().bind(configuration.copyKey);
        cut.acceleratorProperty().bind(configuration.cutKey);
        paste.acceleratorProperty().bind(configuration.pasteKey);

        edit.getItems().addAll(undo, redo, copy, cut, paste);

        if (editMode == EditMode.READ_ONLY) {
            edit.getItems().forEach(item -> {
                item.disableProperty().unbind();
                item.setDisable(true);
            });
        }

        edit.getItems().add(settings);

        // Proof menu items

        MenuItem addLine = new MenuItem("Add Line");
        MenuItem deleteLine = new MenuItem("Delete Line");
        MenuItem startSubProof = new MenuItem("Start Subproof");
        MenuItem endSubProof = new MenuItem("End Subproof");
        MenuItem newPremise = new MenuItem("Add Premise");
        MenuItem addGoal = new MenuItem("Add Goal");
        MenuItem verifyLine = new MenuItem("Verify Line");
        MenuItem verifyProof = new MenuItem("Verify Proof");
        MenuItem proofRestrictions = new MenuItem("Proof Restrictions");

        addLine.setOnAction(actionEvent -> {
            copyManager.deselect();
            if (selectedLine.get() < 0)
                return;
            int line = selectedLine.get() + 1;
            line = line < this.proof.getNumPremises() ? this.proof.getNumPremises() : line;
            addProofLine(false, this.proof.getLine(selectedLine.get()).getSubProofLevel(), line);
            selectedLine.set(-1);
            selectedLine.set(line);
        });

        deleteLine.setOnAction(actionEvent -> deleteLine(selectedLine.get()));

        startSubProof.setOnAction(actionEvent -> startSubProof());

        endSubProof.setOnAction(actionEvent -> endSubproof());

        newPremise.setOnAction(actionEvent -> {
            selectedLine.set(-1);
            selectedLine.set(addPremise());
        });

        addGoal.setOnAction(actionEvent -> {
            selectedLine.set(-1);
            selectedLine.set(-2 - addGoal());
        });

        verifyLine.setOnAction(actionEvent -> verifyLine());

        verifyProof.setOnAction(actionEvent -> this.proof.verifyProof());

        proofRestrictions.setOnAction(actionEvent -> ruleRestrictionUI.show(this.proof));

        addLine.acceleratorProperty().bind(configuration.newProofLineKey);
        deleteLine.acceleratorProperty().bind(configuration.deleteProofLineKey);
        startSubProof.acceleratorProperty().bind(configuration.startSubProofKey);
        endSubProof.acceleratorProperty().bind(configuration.endSubProofKey);
        newPremise.acceleratorProperty().bind(configuration.newPremiseKey);
        addGoal.acceleratorProperty().bind(configuration.addGoalKey);
        verifyLine.acceleratorProperty().bind(configuration.verifyLineKey);
        verifyProof.acceleratorProperty().bind(configuration.verifyProofKey);

        proof.getItems().addAll(proofRestrictions, addLine, deleteLine, startSubProof, endSubProof, newPremise, addGoal);

        if (editMode == EditMode.READ_ONLY) {
            proof.getItems().forEach(item -> {
                item.setDisable(true);
            });
        } else if (editMode == EditMode.RESTRICTED_EDIT) {
            newPremise.setDisable(true);
            addGoal.setDisable(true);
            proofRestrictions.setDisable(true);
        }

        proof.getItems().addAll(verifyLine, verifyProof);

        // Submit menu items

        MenuItem showAssignments = new MenuItem("Show assignments window");

        //TODO: this should be a service callback
//        showAssignments.setOnAction(actionEvent -> AssignmentWindow.instance.show());

        submit.getItems().addAll(showAssignments);

        // Help menu items

        MenuItem checkUpdate = new MenuItem("Check for updates");
        MenuItem helpItem = new MenuItem("Aris Help");
        MenuItem about = new MenuItem("About Aris");

        help.getItems().addAll(checkUpdate, helpItem, about);

        bar.getMenus().addAll(file, edit, proof, submit, help);

        return bar;
    }

    private void openProof() {
        String error = null;
        try {
            File f = showOpenDialog(primaryStage.getScene().getWindow());
            if (f != null && !f.exists()) {
                error = "The selected file does not exist";
            } else if (f != null) {
                Proof p = saveManager.loadProof(f, LibAris.getInstance().getProperties().get("username"));
                if (p == null) {
                    error = "Invalid file format";
                } else {
                    Aris.showProofWindow(new Stage(), p);
                }
            }
        } catch (IOException | TransformerException e) {
            error = "An error occurred while attempting to load the file";
            e.printStackTrace();
        }
        if (error != null) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Failed to load file");
            alert.setHeaderText("Failed to load file");
            alert.setContentText(error);
            alert.showAndWait();
        }
    }

    private boolean saveProof(boolean saveAs) {
        if (!saveAs && moduleOptions != null) {
            if (moduleOptions.arisHandleDefaultSave() && moduleUIListener != null) {
                return moduleUIListener.saveProblemLocally();
            } else if (!moduleOptions.allowDefaultSave()) {
                saveAs = true;
            }
        }
        boolean error;
        try {
            if (saveAs || saveFile == null) {
                File f = showSaveDialog(primaryStage.getScene().getWindow(), saveFile == null ? "" : saveFile.getName());
                if (f != null && !f.getName().toLowerCase().endsWith("." + SaveManager.FILE_EXTENSION)) {
                    f = new File(f.getParentFile(), f.getName() + "." + SaveManager.FILE_EXTENSION);
                    if (f.exists()) {
                        Alert exists = new Alert(Alert.AlertType.CONFIRMATION);
                        exists.setTitle("File exists");
                        exists.setHeaderText("The selected file already exists");
                        exists.setContentText("Would you like to replace the selected file?");
                        Optional<ButtonType> result = exists.showAndWait();
                        if (result.isPresent() && result.get() == ButtonType.CANCEL) {
                            saveProof(saveAs);
                            return false;
                        } else if (!result.isPresent())
                            return false;
                    }
                }
                if (f != null)
                    saveFile = f;
            }
            error = !saveManager.saveProof(proof, saveFile);
        } catch (TransformerException | IOException e) {
            e.printStackTrace();
            error = true;
        }
        if (error) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error saving file");
            alert.setHeaderText("Error saving file");
            alert.setContentText("An error occurred while attempting to save the proof");
        }
        return !error;
    }

    private void newProof() {
        try {
            Aris.showProofWindow(new Stage(), null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private synchronized void verifyLine() {
        int lineNum = selectedLine.get();
        if (lineNum >= 0) {
            Line line = proof.getLine(lineNum);
            if (line.getExpressionString().trim().length() == 0 && line.getSelectedRule() != null) {
                Rule rule = line.getSelectedRule().rule;
                if (rule != null && rule.canAutoFill()) {
                    ArrayList<String> candidates = rule.getAutoFillCandidates(line.getClaimPremises());
                    if (candidates != null && candidates.size() > 0) {
                        HashSet<String> existingPremises = proof.getPossiblePremiseLines(line).stream().map(i -> proofLines.get(i).getText().replace(" ", "")).collect(Collectors.toCollection(HashSet::new));
                        for (String s : candidates) {
                            if (!existingPremises.contains(s.replace(" ", ""))) {
                                proofLines.get(lineNum).setText(s);
                                line.verifyClaim();
                                return;
                            }
                        }
                        proofLines.get(lineNum).setText(candidates.get(0));
                    }
                }
            }
            line.verifyClaim();
        } else if (lineNum < -1)
            this.proof.verifyProof();
    }

    private void startSubProof() {
        copyManager.deselect();
        if (selectedLine.get() < 0)
            return;
        int level = proof.getLine(selectedLine.get()).getSubProofLevel() + 1;
        int lineNum = selectedLine.get();
        selectedLine.set(-1);
        if (lineNum < proof.getNumPremises())
            lineNum = proof.getNumPremises();
        else if (proofLines.get(lineNum).getText().trim().length() == 0 && !proof.getLine(lineNum).isAssumption())
            deleteLine(lineNum);
        else
            lineNum++;
        addProofLine(true, level, lineNum);
        selectedLine.set(lineNum);
    }

    @Override
    public void show() {
        Platform.runLater(() -> {
            primaryStage.show();
            selectedLine.set(0);
            proofLines.get(0).requestFocus();
        });
    }

    @Override
    public void hide() {
        Platform.runLater(primaryStage::hide);
    }

    @Override
    public void setModal(@NotNull Modality modality, @NotNull Window owner) {
        Platform.runLater(() -> {
            primaryStage.initModality(modality);
            primaryStage.initOwner(owner);
        });
    }

    @Override
    public void setDescription(@NotNull String description) {
        Platform.runLater(() -> descriptionText.setText(description));
    }

    @Override
    public void setModuleUIListener(@NotNull ModuleUIListener listener) {
        moduleUIListener = listener;
    }

    @Override
    @Nullable
    public Window getUIWindow() {
        return primaryStage;
    }

    @NotNull
    @Override
    public ArisProofProblem getProblem() {
        if (assignProblem == null)
            assignProblem = new ArisProofProblem(proof);
        return assignProblem;
    }

    private synchronized void autoScroll(Bounds contentBounds) {
        HBox root = null;
        ScrollPane scroll = null;
        double startY = 0;
        if (selectedLine.get() >= 0) {
            root = proofLines.get(selectedLine.get()).getRootNode();
            scroll = scrollPane;
            for (int i = 0; i < proof.getNumLines(); ++i) {
                if (i < selectedLine.get()) {
                    startY += proofLines.get(i).getRootNode().getHeight();
                } else
                    break;
            }
        } else if (selectedLine.get() < -1) {
            root = goalLines.get(selectedLine.get() * -1 - 2).getRootNode();
            scroll = goalScroll;
            startY += goalLbl.getHeight();
            for (int i = 0; i < proof.getNumGoals(); ++i) {
                if (i < selectedLine.get() * -1 - 2) {
                    startY += goalLines.get(i).getRootNode().getHeight();
                } else
                    break;
            }
        }
        if (root != null && scroll != null && root.getHeight() != 0) {
            double downScroll = (startY + root.getHeight() - scroll.getHeight()) / (contentBounds.getHeight() - scroll.getHeight());
            double upScroll = (startY) / (contentBounds.getHeight() - scroll.getHeight());
            double currentScroll = scroll.getVvalue();
            if (currentScroll < downScroll) {
                scroll.setVvalue(downScroll);
            } else if (currentScroll > upScroll) {
                scroll.setVvalue(upScroll);
            }
        }
    }

    @FXML
    private void initialize() {
        primaryStage.getIcons().add(new Image(LibAris.getInstance().getModuleIcon()));
        scrollPane.getContent().boundsInLocalProperty().addListener((observableValue, oldBounds, newBounds) -> {
            if (oldBounds.getHeight() != newBounds.getHeight() && selectedLine.get() >= 0)
                autoScroll(newBounds);
        });
        goalScroll.getContent().boundsInLocalProperty().addListener((ov, oldVal, newBounds) -> {
            if (oldVal.getHeight() != newBounds.getHeight() && selectedLine.get() < -1)
                autoScroll(newBounds);
        });
        goalScroll.maxHeightProperty().bind(Bindings.createDoubleBinding(() -> Math.max(100, goalScroll.getContent().getBoundsInLocal().getHeight()), goalScroll.getContent().boundsInLocalProperty()));
        statusLbl.fontProperty().bind(fontObjectProperty);
        errorRangeLbl.fontProperty().bind(Bindings.createObjectBinding(() -> Font.font(fontObjectProperty.get().getFamily(), FontWeight.BOLD, fontObjectProperty.get().getSize()), fontObjectProperty));
        errorRangeLbl.setOnMouseClicked(mouseEvent -> {
            if (mouseEvent.getEventType() == MouseEvent.MOUSE_CLICKED) {
                if (selectedLine.get() >= 0)
                    proofLines.get(selectedLine.get()).selectError();
                else if (selectedLine.get() < -1) {
                    goalLines.get(selectedLine.get() * -1 - 2).selectError();
                }
            }
        });
        rulesPane.getChildren().add(rulesManager.getRulesTable());
        VBox.setVgrow(rulesManager.getRulesTable(), Priority.ALWAYS);
        populateOperatorPane();
        if (proof.getNumLines() == 0) {
            addPremise();
            addGoal();
        } else {
            for (int i = 0; i < proof.getNumLines(); ++i)
                addProofLine(proof.getLine(i));
            for (int i = 0; i < proof.getNumGoals(); ++i)
                addGoal(proof.getGoal(i));
            proof.verifyProof();
        }
        selectedLine.set(-1);

        descriptionBox.visibleProperty().bind(Bindings.createBooleanBinding(() -> (descriptionText.getText() != null && descriptionText.getText().length() > 0) || uploadBtn.isVisible(), descriptionText.textProperty(), uploadBtn.visibleProperty()));
        descriptionBox.managedProperty().bind(descriptionBox.visibleProperty());
        arisAssignLbl.textProperty().bind(Bindings.createStringBinding(() -> descriptionText.getText() != null && descriptionText.getText().length() > 0 ? "Aris Assign: " : "Aris Assign", descriptionText.textProperty()));

        uploadBtn.setVisible(false);
        uploadBtn.setManaged(false);

        // This should be the last thing in the initialize method
        loaded = true;
    }

    private void populateOperatorPane() {
        oprTitlePane.visibleProperty().bind(configuration.hideOperatorsPanel.not());
        oprTitlePane.managedProperty().bind(configuration.hideOperatorsPanel.not());
        oprTitlePane.setOnMouseClicked(mouseEvent -> {
            if (mouseEvent.getEventType() == MouseEvent.MOUSE_CLICKED)
                oprTitlePane.setExpanded(!oprTitlePane.isExpanded());
        });
        operatorPane.setOnMouseClicked(Event::consume);
        operatorPane.setPadding(new Insets(3));
        operatorPane.setVgap(3);
        operatorPane.setHgap(3);
        for (int i = 0; i < GuiConfig.SYMBOL_BUTTONS.length; i++) {
            String sym = GuiConfig.SYMBOL_BUTTONS[i];
            Button btn = new Button(sym);
            operatorPane.add(btn, i % 6, i / 6);
            btn.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(btn, Priority.ALWAYS);
            GridPane.setFillWidth(btn, true);
            btn.setOnAction(actionEvent -> insertString(sym));
        }
        constTitlePane.setOnMouseClicked(mouseEvent -> {
            if (mouseEvent.getEventType() == MouseEvent.MOUSE_CLICKED)
                constTitlePane.setExpanded(!constTitlePane.isExpanded());
        });
        constantPane.setOnMouseClicked(Event::consume);
        constantPane.setPadding(new Insets(3));
        constantPane.setVgap(3);
        constantPane.setHgap(3);
        int constant = 'a';
        for (int i = 0; i < 12; i++) {
            Button btn = new Button(Character.toString((char) constant));
            constantPane.add(btn, i % 6, i / 6);
            btn.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(btn, Priority.ALWAYS);
            GridPane.setFillWidth(btn, true);
            int finalConstant = constant;
            btn.setOnAction(actionEvent -> {
                if (selectedLine.get() > -1) {
                    ProofLine line = proofLines.get(selectedLine.get());
                    line.insertConstant(Character.toString((char) finalConstant));
                }
            });
            constant++;
        }
    }

    private void insertString(String str) {
        if (selectedLine.get() > -1) {
            ProofLine line = proofLines.get(selectedLine.get());
            line.insertText(str);
        } else if (selectedLine.get() < -1) {
            GoalLine line = goalLines.get(selectedLine.get() * -1 - 2);
            line.insertText(str);
        }
    }

    private synchronized void addProofLine(boolean assumption, int proofLevel, int index) {
        copyManager.deselect();
        if (proofLevel < 0 || index < 0)
            return;
        addProofLine(proof.addLine(index, assumption, proofLevel));
    }

    public synchronized void addProofLine(Line line) {
        line.setStatusListener(this);
        FXMLLoader loader = new FXMLLoader(MainWindow.class.getResource("proof_line.fxml"));
        ProofLine controller = new ProofLine(this, line);
        loader.setController(controller);
        HBox box = null;
        try {
            box = loader.load();
        } catch (IOException e) {
            e.printStackTrace();
        }
        int index = line.getLineNum();
        proofTable.getChildren().add(index, box);
        proofLines.add(index, controller);
        TreeMap<Integer, Line> added = new TreeMap<>();
        added.put(index, line);
        LineChangedEvent event = new LineChangedEvent(added, false);
        history.addHistoryEvent(event);
    }

    private synchronized int addPremise() {
        copyManager.deselect();
        Line line = proof.addPremise();
        addProofLine(line);
        return line.getLineNum();
    }

    private synchronized int addGoal() {
        copyManager.deselect();
        Goal goal = proof.addGoal(proof.getNumGoals());
        return addGoal(goal);
    }

    public int addGoal(Goal goal) {
        goal.setStatusListener(this);
        FXMLLoader loader = new FXMLLoader(MainWindow.class.getResource("goal_line.fxml"));
        GoalLine controller = new GoalLine(this, goal);
        loader.setController(controller);
        HBox box = null;
        try {
            box = loader.load();
        } catch (IOException e) {
            e.printStackTrace();
        }
        int index = goal.getGoalNum();
        VBox content = (VBox) goalScroll.getContent();
        content.getChildren().add(index + 1, box);
        goalLines.add(index, controller);
        GoalChangedEvent event = new GoalChangedEvent(index, goal, false);
        history.addHistoryEvent(event);
        return index;
    }

    public ObjectProperty<Font> getFontProperty() {
        return fontObjectProperty;
    }

    public synchronized void requestFocus(ProofLine line) {
        int index = proofLines.indexOf(line);
        requestFocus(index);
    }

    public void requestFocus(GoalLine line) {
        copyManager.deselect();
        if (selectedLine.get() == -2 - line.getLineNum())
            return;
        selectedLine.set(-1);
        selectedLine.set(-2 - line.getLineNum());
    }

    private synchronized void requestFocus(int lineNum) {
        copyManager.deselect();
        if (selectedLine.get() == lineNum)
            return;
        selectedLine.set(-1);
        selectedLine.set(lineNum);
    }

    public void requestSelect(ProofLine line) {
        copyManager.deselect();
        Triple<Integer, Integer, Boolean> eventData = proof.togglePremise(selectedLine.get(), line.getModel());
        updateHighlighting(selectedLine.get());
        if (eventData != null)
            history.addHistoryEvent(new PremiseChangeEvent(eventData.getLeft(), eventData.getMiddle(), eventData.getRight()));
    }

    public int numLines() {
        return proof.getNumLines();
    }

    public boolean ignoreKeyEvent(KeyEvent event) {
        return configuration.ignore(event);
    }

    public IntegerProperty selectedLineProperty() {
        return selectedLine;
    }

    public synchronized void updateHighlighting(int selectedLine) {
        if (selectedLine >= 0) {
            Line line = proof.getLine(selectedLine);
            if (line != null) {
                HashSet<Line> highlighted = proof.getHighlighted(line);
                for (ProofLine p : proofLines)
                    p.setHighlighted(highlighted.contains(p.getModel()) && p.getModel() != line);
            }
        } else {
            for (ProofLine p : proofLines)
                p.setHighlighted(false);
        }
    }

    public synchronized void deleteLine(int lineNum) {
        copyManager.deselect();
        if (editMode == EditMode.RESTRICTED_EDIT && lineNum < proof.getNumPremises())
            return;
        if (lineNum > 0 || (proof.getNumPremises() > 1 && lineNum >= 0)) {
            TreeMap<Integer, Line> deleted = new TreeMap<>();
            if (lineNum >= proof.getNumPremises()) {
                Line line = proof.getLine(lineNum);
                if (line.isAssumption() && lineNum + 1 < proof.getNumLines()) {
                    int indent = line.getSubProofLevel();
                    Line l = proof.getLine(lineNum + 1);
                    while (l != null && (l.getSubProofLevel() > indent || (l.getSubProofLevel() == indent && !l.isAssumption()))) {
                        deleted.put(lineNum + 1 + deleted.size(), l);
                        removeLine(l.getLineNum());
                        if (lineNum + 1 == proof.getNumLines())
                            l = null;
                        else
                            l = proof.getLine(lineNum + 1);
                    }
                }
            }
            deleted.put(lineNum, proof.getLine(lineNum));
            removeLine(lineNum);
            LineChangedEvent event = new LineChangedEvent(deleted, true);
            history.addHistoryEvent(event);
        } else if (lineNum < -1) {
            if (proof.getNumGoals() <= 1)
                return;
            lineNum = lineNum * -1 - 2;
            selectedLine.set(-1);
            Goal goal = proof.getGoal(lineNum);
            proof.removeGoal(lineNum);
            goalLines.remove(lineNum);
            GoalChangedEvent event = new GoalChangedEvent(lineNum, goal, true);
            ((VBox) goalScroll.getContent()).getChildren().remove(lineNum + 1);
            if (lineNum >= proof.getNumGoals())
                lineNum = proof.getNumGoals() - 1;
            selectedLine.set(-2 - lineNum);
            history.addHistoryEvent(event);
        }
    }

    public synchronized void removeLine(int lineNum) {
        int selected = selectedLine.get();
        selectedLine.set(-1);
        proofLines.remove(lineNum);
        proofTable.getChildren().remove(lineNum);
        proof.delete(lineNum);
        if (selected == proof.getNumLines())
            --selected;
        selectedLine.set(selected < 0 ? 0 : selected);
    }

    private void endSubproof() {
        copyManager.deselect();
        int level = proof.getLine(selectedLine.get()).getSubProofLevel();
        if (level == 0)
            return;
        int newLine = proof.getNumLines();
        for (int i = selectedLine.get() + 1; i < newLine; ++i) {
            Line l = proof.getLine(i);
            if (l.getSubProofLevel() < level || (l.getSubProofLevel() == level && l.isAssumption()))
                newLine = i;
        }
        addProofLine(false, level - 1, newLine);
        selectedLine.set(newLine);
    }

    public Stage getStage() {
        return primaryStage;
    }


    public RulesManager getRulesManager() {
        return rulesManager;
    }

    public Proof getProof() {
        return proof;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public ArrayList<ProofLine> getProofLines() {
        return proofLines;
    }

    public ArrayList<GoalLine> getGoalLines() {
        return goalLines;
    }

    public HistoryManager getHistory() {
        return history;
    }

    public void commitSentenceChanges() {
        int selected = selectedLine.get();
        if (selected >= 0) {
            ProofLine line = proofLines.get(selected);
            line.commitSentenceChange();
        } else if (selected < -1) {
            GoalLine line = goalLines.get(selected * -1 - 2);
            line.commitSentenceChange();
        }
    }

    @Override
    public void statusString(Line line, String statusString) {
        Platform.runLater(() -> {
            if (line.getLineNum() == selectedLine.get())
                statusLbl.setText(statusString);
        });
    }

    @Override
    public void errorRange(Line line, Range<Integer> range) {
        Platform.runLater(() -> {
            if (line.getLineNum() == selectedLine.get()) {
                String str = null;
                if (range != null) {
                    if (range.getMinimum().equals(range.getMaximum()))
                        str = String.valueOf(range.getMinimum() + 1);
                    else
                        str = (range.getMinimum() + 1) + " - " + (range.getMaximum() + 1);
                }
                errorRangeLbl.setText(str);
            }
        });
    }

    @Override
    public void statusString(Goal goal, String statusString) {
        Platform.runLater(() -> {
            int goalNum = selectedLine.get() * -1 - 2;
            if (goalNum == goal.getGoalNum())
                statusLbl.setText(statusString);
        });
    }

    @Override
    public void errorRange(Goal goal, Range<Integer> range) {
        Platform.runLater(() -> {
            int goalNum = selectedLine.get() * -1 - 2;
            if (goalNum == goal.getGoalNum()) {
                String str = null;
                if (range != null) {
                    if (range.getMinimum().equals(range.getMaximum()))
                        str = String.valueOf(range.getMinimum() + 1);
                    else
                        str = (range.getMinimum() + 1) + " - " + (range.getMaximum() + 1);
                }
                errorRangeLbl.setText(str);
            }
        });
    }

    @Override
    public boolean notArisFile(String filename, String programName, String programVersion) {
        Alert noAris = new Alert(Alert.AlertType.CONFIRMATION);
        noAris.setTitle("Not Aris File");
        noAris.setHeaderText("Not Aris File");
        noAris.setContentText("The given file \"" + filename + "\" was written by " + programName + " version " + programVersion + "\n" +
                "Aris may still be able to read this file with varying success\n" +
                "Would you like to attempt to load this file?");
        Optional<ButtonType> option = noAris.showAndWait();
        if (!option.isPresent() || option.get() != ButtonType.YES)
            return false;
        return true;
    }

    @Override
    public void integrityCheckFailed(String filename) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("File integrity check failed");
        alert.setHeaderText("File integrity check failed");
        alert.setContentText("This file may be corrupted or may have been tampered with.\n" +
                "If this file successfully loads the author will be marked as UNKNOWN.\n" +
                "This will show up if this file is submitted and may affect your grade.");
        alert.getDialogPane().setPrefWidth(500);
        alert.showAndWait();
    }

    public void setUIOptions(ModuleUIOptions options) {
        this.moduleOptions = options;
        saveProof.disableProperty().set(!options.allowDefaultSave() && !options.allowSaveAs());
        saveAsProof.disableProperty().set(!options.allowSaveAs());
        uploadBtn.setVisible(options.showUploadButton());
        uploadBtn.setManaged(options.showUploadButton());
        setDescription(options.getGuiDescription());
    }

    @FXML
    public void upload() {
        if (moduleUIListener != null)
            moduleUIListener.uploadProblem();
    }

    public void selectRequest(ProofLine proofLine) {
        copyManager.selectRange(proofLine.getLineNum());
    }
}
