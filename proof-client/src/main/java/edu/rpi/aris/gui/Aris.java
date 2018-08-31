package edu.rpi.aris.gui;

import edu.rpi.aris.LibAris;
import edu.rpi.aris.assign.ArisClientModule;
import edu.rpi.aris.assign.ArisModuleException;
import edu.rpi.aris.assign.EditMode;
import edu.rpi.aris.assign.Problem;
import edu.rpi.aris.proof.ArisProofProblem;
import edu.rpi.aris.proof.Proof;
import edu.rpi.aris.proof.SaveInfoListener;
import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Optional;

public class Aris extends Application implements ArisClientModule<LibAris>, SaveInfoListener {

    private static Aris instance = null;

//    private MainWindow mainWindow = null;

    public Aris() {
    }

    public static void main(String[] args) {
        launch(args);
    }

    public static MainWindow showProofWindow(Stage stage, Proof p) throws IOException {
        MainWindow window = p == null ? new MainWindow(stage, EditMode.UNRESTRICTED_EDIT) : new MainWindow(stage, p, EditMode.UNRESTRICTED_EDIT);
        window.show();
        return window;
    }

    public static Aris getInstance() {
        if (instance == null)
            instance = new Aris();
        return instance;
    }

    @Override
    public void start(Stage stage) throws IOException {
        instance = this;
        /*mainWindow = */
        showProofWindow(stage, null);
    }

//    public MainWindow getMainWindow() {
//        return mainWindow;
//    }

    @Override
    public MainWindow createModuleGui(EditMode editMode, String description) throws Exception {
        return createModuleGui(editMode, description, null);
    }

    @Override
    public MainWindow createModuleGui(EditMode editMode, String description, Problem<LibAris> problem) throws Exception {
        try {
            if (editMode == EditMode.CREATE_EDIT_PROBLEM)
                editMode = EditMode.UNRESTRICTED_EDIT;
            if (problem instanceof ArisProofProblem)
                return new MainWindow(new Stage(), ((ArisProofProblem) problem).getProof(), editMode);
            else
                return new MainWindow(new Stage(), editMode);
        } catch (IOException e) {
            throw new ArisModuleException("Failed to create " + LibAris.NAME + " window", e);
        }
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
        return option.isPresent() && option.get() == ButtonType.YES;
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
}