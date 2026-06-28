package dev.frostguard.app.panel.profile;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ConfigData;
import dev.frostguard.api.domain.ProfileStatusData;
import dev.frostguard.app.panel.launcher.ILauncherConstants;
import dev.frostguard.engine.listener.ProfileStatusChangeListener;
import dev.frostguard.engine.service.LoggingService;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ProfileManagerActionController implements ProfileStatusChangeListener {

	private final ProfileManagerLayoutController profileManagerLayoutController;

	private Stage newProfileStage;
	private IProfileModel iModel;

	public ProfileManagerActionController(ProfileManagerLayoutController profileManagerLayoutController) {
		this.profileManagerLayoutController = profileManagerLayoutController;
		this.iModel = new ProfileModel();
		this.iModel.addProfileStatusChangeListerner(this);
	}

	public void loadProfiles(ProfileCallback callback) {
		CompletableFuture.supplyAsync(iModel::getProfiles)
				.thenAccept(profiles -> {
					if (callback != null) {
						callback.onProfilesLoaded(profiles);
					}
				})
				.exceptionally(error -> {
					if (callback != null) {
						callback.onProfileLoadFailed(error);
					}
					error.printStackTrace();
					return null;
				});
	}

	public boolean deleteProfile(AccountDescriptor profile) {
		return iModel.deleteProfile(profile);
	}

	public boolean addProfile(AccountDescriptor profile) {
		return iModel.addProfile(profile);
	}

	public boolean saveProfile(ProfileAux currentProfile) {
		return iModel.saveProfile(toDescriptor(currentProfile));
	}

	public boolean bulkUpdateSelectedProfiles(ProfileAux templateProfile, List<ProfileAux> selectedProfiles) {
		if (templateProfile == null || selectedProfiles == null || selectedProfiles.isEmpty()) {
			return false;
		}

		boolean allUpdatesSuccessful = true;
		for (ProfileAux targetProfile : selectedProfiles) {
			copyTemplateConfigs(templateProfile, targetProfile);
			if (!saveProfile(targetProfile)) {
				allUpdatesSuccessful = false;
				log(TpMessageSeverityEnum.ERROR, "Failed to update profile: " + targetProfile.getName());
			} else {
				log(TpMessageSeverityEnum.INFO,
					"Successfully updated profile: " + targetProfile.getName()
						+ " with template from: " + templateProfile.getName());
			}
		}

		return allUpdatesSuccessful;
	}

	@Override
	public void onAccountStatusUpdated(ProfileStatusData status) {
		if (status != null) {
			profileManagerLayoutController.handleProfileStatusChange(status);
		}
	}

	public void showNewProfileDialog() {
		try {
			FXMLLoader loader = new FXMLLoader(layoutResource("NewProfileLayout.fxml"));
			loader.setController(new NewProfileLayoutController(this));

			Parent root = loader.load();
			newProfileStage = createDialogStage("New Profile", root, Modality.APPLICATION_MODAL, null);
			newProfileStage.setOnCloseRequest(event -> closeNewProfileDialog());
			newProfileStage.showAndWait();
		} catch (IOException e) {
			e.printStackTrace();
			log(TpMessageSeverityEnum.ERROR, "Error loading FXML " + e.getMessage());
		}
	}

	public void closeNewProfileDialog() {
		if (newProfileStage != null) {
			newProfileStage.close();
			newProfileStage = null;
		}
		profileManagerLayoutController.loadProfiles();
	}

	public void showBulkUpdateDialog(Long loadedProfileId, List<ProfileAux> profiles, Node ownerNode) {
		ProfileAux templateProfile = resolveBulkTemplate(loadedProfileId, profiles);
		if (templateProfile == null) {
			return;
		}

		try {
			FXMLLoader loader = new FXMLLoader(layoutResource("BulkUpdateDialog.fxml"));
			Parent root = loader.load();
			BulkUpdateDialogController dialogController = loader.getController();

			Stage dialogStage = createDialogStage("Bulk Update Profiles", root, Modality.WINDOW_MODAL, ownerNode);
			dialogController.setupDialog(templateProfile, new ArrayList<>(profiles), dialogStage);
			dialogStage.showAndWait();
			applyBulkUpdateIfConfirmed(dialogController, templateProfile);
		} catch (Exception e) {
			e.printStackTrace();
			log(TpMessageSeverityEnum.ERROR, "Failed to open bulk update dialog: " + e.getMessage());
			showAlert(AlertType.ERROR, "ERROR", "Failed to open bulk update dialog: " + e.getMessage());
		}
	}

	public void showEditProfileDialog(ProfileAux profile, Node ownerNode) {
		if (profile == null) {
			showAlert(AlertType.ERROR, "ERROR", "No profile selected for editing.");
			return;
		}

		try {
			FXMLLoader loader = new FXMLLoader(layoutResource("EditProfile.fxml"));
			Parent root = loader.load();
			EditProfileController dialogController = loader.getController();
			dialogController.setProfileToEdit(profile);
			dialogController.setActionController(this);

			Stage dialogStage = createDialogStage("Edit Profile - " + profile.getName(), root, Modality.WINDOW_MODAL, ownerNode);
			dialogController.setDialogStage(dialogStage);
			dialogStage.showAndWait();

			if (dialogController.isSaveClicked()) {
				profileManagerLayoutController.loadProfiles();
			}
		} catch (Exception e) {
			e.printStackTrace();
			log(TpMessageSeverityEnum.ERROR, "Failed to open edit profile dialog: " + e.getMessage());
			showAlert(AlertType.ERROR, "ERROR", "Failed to open edit profile dialog: " + e.getMessage());
		}
	}

	private AccountDescriptor toDescriptor(ProfileAux currentProfile) {
		AccountDescriptor descriptor = new AccountDescriptor(
			currentProfile.getId(),
			currentProfile.getName(),
			currentProfile.getEmulatorNumber(),
			currentProfile.isEnabled(),
			currentProfile.getPriority(),
			currentProfile.getReconnectionTime(),
			currentProfile.getCharacterId(),
			currentProfile.getCharacterName(),
			currentProfile.getCharacterAllianceCode(),
			currentProfile.getCharacterServer()
		);

		currentProfile.getConfigs().forEach(config ->
			descriptor.getConfigs().add(new ConfigData(currentProfile.getId(), config.getName(), config.getValue()))
		);
		return descriptor;
	}

	private void copyTemplateConfigs(ProfileAux templateProfile, ProfileAux targetProfile) {
		for (ConfigAux config : templateProfile.getConfigs()) {
			try {
				ConfigurationKeyEnum configKey = ConfigurationKeyEnum.valueOf(config.getName());
				targetProfile.setConfig(configKey, config.getValue());
			} catch (IllegalArgumentException e) {
				log(TpMessageSeverityEnum.WARNING,
					"Skipping unknown configuration: " + config.getName() + " for profile: " + targetProfile.getName());
			}
		}
	}

	private ProfileAux resolveBulkTemplate(Long loadedProfileId, List<ProfileAux> profiles) {
		if (loadedProfileId == null) {
			showAlert(AlertType.WARNING, "WARNING", "Please load a profile first to use as template for bulk update.");
			return null;
		}

		ProfileAux templateProfile = profiles.stream()
				.filter(profile -> profile.getId().equals(loadedProfileId))
				.findFirst()
				.orElse(null);

		if (templateProfile == null) {
			showAlert(AlertType.ERROR, "ERROR", "Could not find the loaded profile to use as template.");
			return null;
		}

		if (profiles.size() <= 1) {
			showAlert(AlertType.WARNING, "WARNING",
				"No other profiles available to update. You need at least 2 profiles for bulk update.");
			return null;
		}

		return templateProfile;
	}

	private void applyBulkUpdateIfConfirmed(BulkUpdateDialogController dialogController, ProfileAux templateProfile) {
		if (!dialogController.isUpdateConfirmed()) {
			return;
		}

		List<ProfileAux> selectedProfiles = dialogController.getSelectedProfiles();
		boolean success = bulkUpdateSelectedProfiles(templateProfile, selectedProfiles);

		if (success) {
			showAlert(AlertType.INFORMATION, "SUCCESS",
				"Successfully updated " + selectedProfiles.size()
					+ " profile(s) with configs from '" + templateProfile.getName() + "'.");
			profileManagerLayoutController.loadProfiles();
		} else {
			showAlert(AlertType.ERROR, "ERROR",
				"Error occurred while updating profiles. Some profiles may not have been updated.");
		}
	}

	private Stage createDialogStage(String title, Parent root, Modality modality, Node ownerNode) {
		Stage stage = new Stage();
		stage.setTitle(title);
		stage.setScene(createStyledScene(root));
		stage.initModality(modality);
		if (ownerNode != null && ownerNode.getScene() != null) {
			stage.initOwner(ownerNode.getScene().getWindow());
		}
		stage.setResizable(false);
		return stage;
	}

	private Scene createStyledScene(Parent root) {
		Scene scene = new Scene(root);
		scene.getStylesheets().add(ILauncherConstants.getCssPath());
		return scene;
	}

	private URL layoutResource(String fileName) {
		URL resource = getClass().getResource("/layout/" + fileName);
		if (resource == null) {
			throw new IllegalStateException("Missing profile dialog layout: " + fileName);
		}
		return resource;
	}

	private void showAlert(AlertType alertType, String title, String message) {
		Alert alert = new Alert(alertType);
		alert.setTitle(title);
		alert.setHeaderText(null);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void log(TpMessageSeverityEnum severity, String message) {
		LoggingService.obtain().emit(severity, "Profile Manager", "-", message);
	}
}
