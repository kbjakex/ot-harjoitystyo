package rollingball.ui;

import java.util.Stack;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;
import rollingball.Main;
import rollingball.dao.UserProgressDao;
import rollingball.functions.EvalContext;
import rollingball.functions.Function;
import rollingball.functions.FunctionParser;
import rollingball.functions.ParserException;
import rollingball.game.GameSimulator;
import rollingball.game.Level;
import rollingball.game.FunctionStorage.Graph;
import rollingball.game.Obstacles.Spike;

/**
 * A renderer for the game state. This class contains no game logic, and is
 * responsible solely for drawing the game state to the screen. It is,
 * however, responsible for calling the update() method of the GameSimulator.
 * 
 * <br>
 * Note that there are two coordinate spaces here. The coordinate space of the
 * level is defined by the LEVEL_WIDTH and LEVEL_HEIGHT constants in the GameSimulator class. 
 * The rendering operates in screen coordinates, with the origin at the top left corner.
 */
public final class GameRenderer {
    private static final int GRAPH_AREA_WIDTH = GameSimulator.LEVEL_WIDTH;
    private static final int GRAPH_AREA_HEIGHT = GameSimulator.LEVEL_HEIGHT;

    private static final double PX_PER_GRAPH_AREA_UNIT = 50.0;
    private static final double GRAPH_AREA_WIDTH_PX = GRAPH_AREA_WIDTH * PX_PER_GRAPH_AREA_UNIT;
    private static final double GRAPH_AREA_HEIGHT_PX = GRAPH_AREA_HEIGHT * PX_PER_GRAPH_AREA_UNIT;

    private final Image flagTexture;

    private final Canvas canvas;
    private final GraphicsContext graphics;

    private final Label timeDisplay;

    private final GameSimulator state;

    private GameRenderer(Canvas canvas, GameSimulator state, Label timeDisplay) {
        this.canvas = canvas;
        this.state = state;
        this.graphics = canvas.getGraphicsContext2D();
        this.timeDisplay = timeDisplay;

        this.flagTexture = new Image(Main.class.getClassLoader().getResourceAsStream("flag.png"));
    }

    private void onFrame(ActionEvent event) {
        state.update();

        var canvasWidth = canvas.getWidth();
        var canvasHeight = canvas.getHeight();
        graphics.clearRect(0, 0, canvasWidth, canvasHeight);
        graphics.translate(canvasWidth / 2.0, canvasHeight / 2.0);

        drawGrid();
        drawGraphs();
        drawBall();
        drawObstacles();

        var end = state.getLevel().getEnd();
        graphics.fillOval(end.x() * PX_PER_GRAPH_AREA_UNIT - 3.5, -end.y() * PX_PER_GRAPH_AREA_UNIT - 3.5,
                7, 7);
        graphics.drawImage(flagTexture, end.x() * PX_PER_GRAPH_AREA_UNIT - 1.5625,
                -end.y() * PX_PER_GRAPH_AREA_UNIT - 50, 50, 50);

        graphics.translate(-canvasWidth / 2, -canvasHeight / 2);

        timeDisplay.setText(String.format("Time: %.2fs", state.getPlayingTimeSeconds()));
    }

    private void drawBall() {
        graphics.setStroke(Color.BLACK);
        graphics.setFill(Color.GRAY);
        var ball = state.getBall();
        var diameter = GameSimulator.BALL_RADIUS * PX_PER_GRAPH_AREA_UNIT * 2.0;
        graphics.fillOval(
                ball.getX() * PX_PER_GRAPH_AREA_UNIT - diameter / 2.0,
                -ball.getY() * PX_PER_GRAPH_AREA_UNIT - diameter,
                diameter, diameter);
    }

    private void drawObstacles() {
        graphics.setStroke(Color.BLACK);
        graphics.setFill(Color.BLACK);

        var index = 0;
        for (var obstacle : state.getLevel().getObstacles()) {
            var random = ++index * 3; // poor way to get per-obstacle "random" value...
            if (obstacle instanceof Spike s) {
                for (var i = 0; i < 5; ++i) {
                    var angle = Math.PI * 2 * i / 5 + Math.sin(System.currentTimeMillis() * 0.002 + random) * 0.1;
                    var x = s.x() * PX_PER_GRAPH_AREA_UNIT;
                    var y = s.y() * PX_PER_GRAPH_AREA_UNIT;
                    var dx = Math.cos(angle) * 0.35 * PX_PER_GRAPH_AREA_UNIT;
                    var dy = Math.sin(angle) * 0.35 * PX_PER_GRAPH_AREA_UNIT;
                    graphics.strokeLine(x - dx, -y - dy, x + dx, -y + dy);
                }
            }
        }
    }

    private void drawGraphs() {
        var evalCtx = new EvalContext(state.getPlayingTimeSeconds());

        graphics.setLineWidth(2.0);
        for (var graph : state.getGraphs()) {
            renderGraph(graph, evalCtx);
        }
    }

    private void renderGraph(Graph graph, EvalContext ctx) {
        graphics.setStroke(graph.getColor());
        graphics.beginPath();

        // TODO: here's a *really* sweet opportunity to cut down on the amount of work
        // to do
        // by approximating the second derivative and adjusting stepSize such that
        // straight
        // lines need much less vertices
        var stepSize = 2.0;
        var fn = graph.geFunction();
        for (var pixelX = -GRAPH_AREA_WIDTH_PX; pixelX <= GRAPH_AREA_WIDTH_PX; pixelX += stepSize) {
            ctx.x = pixelX / PX_PER_GRAPH_AREA_UNIT;

            if (!fn.canEval(ctx)) {
                continue;
            }

            // up is negative in screen coords so negate the value
            graphics.moveTo(pixelX, -fn.eval(ctx) * PX_PER_GRAPH_AREA_UNIT);
            pixelX += stepSize;
            ctx.x += stepSize / PX_PER_GRAPH_AREA_UNIT;

            while (fn.canEval(ctx) && pixelX <= GRAPH_AREA_WIDTH_PX) {
                graphics.lineTo(pixelX, -fn.eval(ctx) * PX_PER_GRAPH_AREA_UNIT);

                pixelX += stepSize;
                ctx.x += stepSize / PX_PER_GRAPH_AREA_UNIT;
            }
        }

        graphics.stroke();
    }

    private void drawGrid() {
        graphics.setStroke(Color.LIGHTGRAY);
        graphics.setLineWidth(1.0);

        // Horizontal lines
        for (int i = -GRAPH_AREA_WIDTH; i <= GRAPH_AREA_WIDTH; ++i) {
            var x = i * PX_PER_GRAPH_AREA_UNIT;
            graphics.strokeLine(-GRAPH_AREA_WIDTH_PX, x, GRAPH_AREA_WIDTH_PX, x);
        }

        // Vertical lines
        for (int i = -GRAPH_AREA_HEIGHT; i <= GRAPH_AREA_HEIGHT; ++i) {
            var x = i * PX_PER_GRAPH_AREA_UNIT;
            graphics.strokeLine(x, -GRAPH_AREA_HEIGHT_PX, x, GRAPH_AREA_HEIGHT_PX);
        }

        // Coordinate axes
        graphics.setStroke(Color.DARKGRAY);
        graphics.setLineWidth(2.0);
        graphics.strokeLine(-GRAPH_AREA_WIDTH_PX, 0.0, GRAPH_AREA_WIDTH_PX, 0.0);
        graphics.strokeLine(0.0, -GRAPH_AREA_HEIGHT_PX, 0.0, GRAPH_AREA_HEIGHT_PX);
    }

    /**
     * Creates the user interface for the game for the given level.
     * @param primaryStage the stage variable used in the JavaFX application
     * @param sceneHistory the stack of previous scenes
     * @param level the level to create the user interface for
     */
    public static Scene createGameScene(Stage primaryStage, Stack<Supplier<Scene>> sceneHistory, UserProgressDao progressDao, Level level) {
        var equationList = new ListView<HBox>();
        equationList.setFocusTraversable(false);
        equationList.getItems().add(new HBox(new Label(""))); // make ListView render
        VBox.setVgrow(equationList, Priority.ALWAYS);

        var addEquationButton = new Button("+");
        addEquationButton.setFocusTraversable(false);

        var equationInput = new TextField();
        equationInput.setPromptText("Enter an equation (ex: `1 + sin(x)`)");
        HBox.setHgrow(equationInput, Priority.ALWAYS);
        Platform.runLater(() -> equationInput.requestFocus());

        var conditionInput = new TextField();
        conditionInput.setPrefWidth(200);
        conditionInput.setPromptText("Filter (ex: `0 < x < 1`)");
        HBox.setMargin(conditionInput, new Insets(0, 10, 0, 10));

        // Make life easier by allowing enter instead of requiring user to click
        equationInput.setOnKeyPressed(keyEvent -> {
            if (keyEvent.getCode() == javafx.scene.input.KeyCode.ENTER) {
                addEquationButton.fire();
            }
        });
        conditionInput.setOnKeyPressed(keyEvent -> {
            if (conditionInput.getText().isEmpty() || keyEvent.getCode() != javafx.scene.input.KeyCode.ENTER) {
                return;
            }
            if (equationInput.getText().isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText("Equation field is empty!");
                alert.showAndWait();
                return;
            }
            addEquationButton.fire();
        });

        var equationControls = new HBox(equationInput, conditionInput, addEquationButton);
        equationControls.setPadding(new Insets(10.0));
        equationControls.setPrefWidth(Double.MAX_VALUE);

        var timeLabel = new Label("Time: 0.0s");
        timeLabel.setFont(new Font("Arial", 14));
        timeLabel.setTextFill(Color.gray(0.3));
        var timeArea = new StackPane(timeLabel);
        StackPane.setAlignment(timeLabel, Pos.CENTER);
        StackPane.setMargin(timeLabel, new Insets(18, 18, 18, 18));
        timeArea.setPrefSize(120.0, 48.0);
        timeArea.setBackground(
                new Background(new BackgroundFill(Color.gray(1.0, 0.7), CornerRadii.EMPTY, Insets.EMPTY)));
        timeArea.setVisible(false);

        var levelLabel = new Label(level.getBlueprint().getName());
        levelLabel.setFont(new Font("Arial", 24));
        levelLabel.setTextFill(Color.gray(0.3));

        var playImg = new Image(Main.class.getClassLoader().getResourceAsStream("play.png"));
        var pauseImg = new Image(Main.class.getClassLoader().getResourceAsStream("pause.png"));

        var playButton = new ImageView(playImg);
        playButton.setFitWidth(48.0);
        playButton.setFitHeight(48.0);
        var playButtonFrame = new Pane(playButton);
        playButtonFrame.setMaxSize(48.0, 48.0);
        playButtonFrame
                .setBackground(new Background(new BackgroundFill(Color.WHITE, new CornerRadii(5.0), Insets.EMPTY)));
        playButtonFrame.setBorder(new Border(new BorderStroke(Color.LIGHTGRAY, BorderStrokeStyle.SOLID,
                new CornerRadii(5.0), new BorderWidths(2.0))));

        var state = new GameSimulator(level, (victory, gameTimeSeconds) -> Platform.runLater(() -> {
            if (victory) {
                var equations = equationList.getItems()
                    .stream()
                    .filter(node -> node.getChildren().get(0) instanceof TextField)
                    .map(node -> {
                        var equation = ((TextField) node.getChildren().get(0)).getText();
                        var condition = ((TextField) node.getChildren().get(1)).getText();
                        return new UserProgressDao.Equation(equation, condition);
                    })
                    .filter(eq -> !eq.formula().isEmpty())
                    .collect(Collectors.toList());

                var scorePercentage = level.getBlueprint().computeScorePercentage(equations.size(), gameTimeSeconds);

                progressDao.addLevelCompletion(level.getBlueprint(), equations, scorePercentage);

                var alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Victory!");
                alert.setHeaderText("You won!");
                alert.showAndWait();

                var nextLevel = level.getBlueprint().next();
                if (nextLevel == null) {
                    primaryStage.setScene(sceneHistory.pop().get());
                } else {
                    var scene = createGameScene(primaryStage, sceneHistory, progressDao, nextLevel.createInstance());
                    primaryStage.setScene(scene);
                }
                return;
            }

            playButton.setImage(playImg);
            timeArea.setVisible(false);
            levelLabel.setVisible(true);
        }));

        addEquationButton.setOnAction(e -> addExpression(equationInput, conditionInput, equationList, state));
        progressDao.getLevelCompletions().stream().filter(eq -> eq.level() == level.getBlueprint()).forEach(data -> {
            for (var equation : data.equations()) {
                equationInput.setText(equation.formula());
                conditionInput.setText(equation.condition());
                addExpression(equationInput, conditionInput, equationList, state);
            }
        });

        playButtonFrame.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (playButton.getImage() == playImg) {
                playButton.setImage(pauseImg);
                timeArea.setVisible(true);
                levelLabel.setVisible(false);
            } else {
                playButton.setImage(playImg);
                timeArea.setVisible(false);
                levelLabel.setVisible(true);
            }

            state.togglePlaying();
        });

        var centerPane = new StackPane(levelLabel, timeArea);

        var backButton = new Button("Exit");
        backButton.setPrefSize(64, 48);
        backButton.setFont(new Font("Arial", 14));
        backButton.setBackground(new Background(new BackgroundFill(Color.WHITE, new CornerRadii(5.0), Insets.EMPTY)));
        backButton.setBorder(new Border(new BorderStroke(Color.LIGHTGRAY, BorderStrokeStyle.SOLID, new CornerRadii(5.0),
                new BorderWidths(2.0))));
        backButton.setOnAction(e -> {
            if (!state.getGraphs().isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Discard progress?");
                alert.setHeaderText(
                        "State saving functionality is not yet implemented and exiting will discard any current progress.");
                alert.setContentText("Exit anyways?");
                alert.showAndWait();

                if (alert.getResult() == ButtonType.CANCEL) {
                    return;
                }
            }

            primaryStage.setScene(sceneHistory.pop().get());
        });

        var topUi = new HBox(playButtonFrame, createHSpacer(), centerPane, createHSpacer(), backButton);
        HBox.setMargin(playButtonFrame, new Insets(10, 26, 10, 10));
        HBox.setMargin(centerPane, new Insets(10, 10, 10, 10));
        HBox.setMargin(backButton, new Insets(10, 10, 10, 10));
        topUi.setMaxHeight(100.0);
        var desiredWidth = 800;
        var desiredHeight = 800;

        var canvas = new Canvas(desiredWidth, desiredHeight);
        var canvasPane = new Pane(new StackPane(canvas, new VBox(topUi, createVSpacer())));
        canvasPane.heightProperty().addListener((obs, oldVal, newVal) -> canvas.setHeight(newVal.doubleValue()));

        var split = new SplitPane(canvasPane, new VBox(equationControls, equationList));
        split.setDividerPositions(0.9);
        split.setOrientation(Orientation.VERTICAL);

        var scene = new Scene(split, desiredWidth, desiredHeight);

        var game = new GameRenderer(canvas, state, timeLabel);

        var timeline = new Timeline();
        timeline.getKeyFrames().add(new KeyFrame(Duration.millis(1000.0 / 60.0), game::onFrame));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();

        return scene;
    }

    private static Node createHSpacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static Node createVSpacer() {
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static void addExpression(TextField equationInput, TextField conditionInput, ListView<HBox> equationList,
            GameSimulator state) {
        var exprAsString = equationInput.getText();
        var condAsString = conditionInput.getText();
        var expr = readExpression(exprAsString, condAsString);
        if (expr == null) {
            return;
        }

        equationInput.clear();
        conditionInput.clear();

        var graph = state.addGraph(expr);

        var rgb = graph.getColor();
        var equationInputField = new TextField(exprAsString);
        equationInputField.setStyle(String.format("-fx-text-fill: rgb(%d, %d, %d);", (int) (255 * rgb.getRed()),
                (int) (255 * rgb.getGreen()), (int) (255 * rgb.getBlue())));
        HBox.setHgrow(equationInputField, Priority.ALWAYS);

        var conditionInputField = new TextField(condAsString);
        conditionInputField.setPrefWidth(200);
        conditionInputField.setPromptText("(Filter)");
        HBox.setMargin(conditionInputField, new Insets(0, 10, 0, 10));

        var equationListEntry = new HBox();
        var removeEquationButton = new Button("Remove");
        removeEquationButton.setOnAction(ee -> {
            state.removeGraph(graph);
            equationList.getItems().remove(equationListEntry);
        });

        equationListEntry.getChildren().addAll(equationInputField, conditionInputField, removeEquationButton);

        equationList.getItems().add(0, equationListEntry);

        equationInputField.setOnKeyPressed(keyEvent -> {
            if (keyEvent.getCode() == javafx.scene.input.KeyCode.ENTER) {
                if (equationInputField.getText().isEmpty()) {
                    removeEquationButton.fire();
                } else {
                    graph.setFunction(readExpression(equationInputField.getText(), conditionInputField.getText()));
                }
            }
        });
        conditionInputField.setOnKeyPressed(keyEvent -> {
            if (conditionInputField.getText().isEmpty() || keyEvent.getCode() != javafx.scene.input.KeyCode.ENTER) {
                return;
            }
            graph.setFunction(readExpression(equationInputField.getText(), conditionInputField.getText()));
        });
    }

    private static Function readExpression(String expressionString, String conditionString) {
        if (expressionString.isEmpty()) {
            return null;
        }

        try {
            return FunctionParser.parse(expressionString, conditionString);
        } catch (ParserException ex) {
            var errorAlert = new Alert(AlertType.ERROR);
            errorAlert.setHeaderText("Input not valid");
            errorAlert.setContentText(ex.getMessage());
            errorAlert.showAndWait();
            return null;
        }
    }
}
