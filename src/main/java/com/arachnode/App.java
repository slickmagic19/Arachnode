package com.arachnode;

import com.arachnode.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Arachnode entry point — desktop SEO spider. */
public class App extends Application {
    private MainView view;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Arachnode — SEO Spider");
        try {
            var logo = getClass().getResourceAsStream("/logo.png");
            if (logo != null) stage.getIcons().add(new javafx.scene.image.Image(logo));
        } catch (Exception ignored) { /* default icon */ }
        view = new MainView(stage);
        Scene scene = view.build();
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();
    }

    @Override
    public void stop() {
        if (view != null) view.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
