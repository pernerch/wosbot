package dev.frostguard.app.panel.profile;

import java.util.List;
import java.util.function.Function;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.listener.ProfileServiceInterface;
import dev.frostguard.engine.listener.ProfileStatusChangeListener;
import dev.frostguard.engine.service.ProfileService;

public class ProfileModel implements IProfileModel {

	private final ProfileServiceInterface profileService;

	public ProfileModel() {
		this(ProfileService.obtain());
	}

	ProfileModel(ProfileServiceInterface profileService) {
		this.profileService = profileService;
	}

	@Override
	public List<AccountDescriptor> getProfiles() {
		return withProfileService(ProfileServiceInterface::fetchAllAccounts);
	}

	@Override
	public boolean addProfile(AccountDescriptor profile) {
		return withProfileService(service -> service.createAccount(profile));
	}

	@Override
	public boolean saveProfile(AccountDescriptor profile) {
		return withProfileService(service -> service.persistAccount(profile));
	}

	@Override
	public void addProfileStatusChangeListerner(ProfileStatusChangeListener listener) {
		withProfileService(service -> {
			service.registerStatusObserver(listener);
			return null;
		});
	}

	@Override
	public boolean deleteProfile(AccountDescriptor profile) {
		return withProfileService(service -> service.removeAccount(profile));
	}

	@Override
	public boolean bulkUpdateProfiles(AccountDescriptor templateProfile) {
		return withProfileService(service -> service.applyBulkUpdate(templateProfile));
	}

	private <T> T withProfileService(Function<ProfileServiceInterface, T> operation) {
		return operation.apply(profileService);
	}
}
