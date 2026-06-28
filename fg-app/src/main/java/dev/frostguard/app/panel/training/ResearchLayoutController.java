package dev.frostguard.app.panel.training;

import java.util.Map;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

public class ResearchLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxEnableResearch;

	@FXML
	private CheckBox checkBoxGrowth;

	@FXML
	private CheckBox checkBoxEconomy;

	@FXML
	private CheckBox checkBoxBattle;

	@FXML
	private void initialize() {
		registerResearchControls();
		initializeChangeEvents();
	}

	private void registerResearchControls() {
		Map.of(
				checkBoxEnableResearch, ConfigurationKeyEnum.RESEARCH_ENABLED_BOOL,
				checkBoxGrowth, ConfigurationKeyEnum.RESEARCH_GROWTH_BOOL,
				checkBoxEconomy, ConfigurationKeyEnum.RESEARCH_ECONOMY_BOOL,
				checkBoxBattle, ConfigurationKeyEnum.RESEARCH_BATTLE_BOOL)
				.forEach(this::registerCheckBox);
	}
}
