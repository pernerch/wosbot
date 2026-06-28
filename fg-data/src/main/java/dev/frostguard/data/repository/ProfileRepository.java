package dev.frostguard.data.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ConfigData;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.Config;
import dev.frostguard.data.entity.Profile;

/**
 * Manages {@link Profile} persistence operations and the associated
 * configuration entries. Provides both intent-expressing domain methods
 * and legacy-compatible delegates for downstream callers.
 */
public class ProfileRepository {

	private static ProfileRepository instance;
	private final DataStore store = DataStore.getInstance();

	private ProfileRepository() {}

	public static ProfileRepository getRepository() {
		if (instance == null) {
			instance = new ProfileRepository();
		}
		return instance;
	}

	public List<AccountDescriptor> getAccounts() {
		List<AccountDescriptor> descriptors = fetchAllDescriptors();

		if (descriptors.isEmpty()) {
			provisionDefaultProfile();
			descriptors = fetchAllDescriptors();
		}

		attachConfigEntries(descriptors);
		return descriptors;
	}

	public AccountDescriptor getAccountWithSettingsById(Long id) {
		if (id == null) return null;

		return findDescriptorById(id)
			.map(this::attachSingleConfigs)
			.orElse(null);
	}

	public List<AccountDescriptor> findEnabledProfiles() {
		return getAccounts().stream()
			.filter(a -> Boolean.TRUE.equals(a.getEnabled()))
			.collect(Collectors.toList());
	}

	public boolean addAccount(Profile profile) { return store.persist(profile); }
	public boolean saveAccount(Profile profile) { return store.merge(profile); }
	public boolean deleteAccount(Profile profile) { return store.remove(profile); }

	public Profile getAccountById(Long id) {
		if (id == null) return null;
		return store.lookup(Profile.class, id);
	}

	public List<Config> getAccountSettings(Long accountId) {
		if (accountId == null) return Collections.emptyList();
		return querySettingsByProfile(accountId);
	}

	public boolean deleteSettings(List<Config> configs) {
		if (configs == null || configs.isEmpty()) return false;
		try {
			configs.forEach(store::remove);
			return true;
		} catch (Exception ex) {
			ex.printStackTrace();
			return false;
		}
	}

	public boolean saveSettings(List<Config> configs) {
		if (configs == null || configs.isEmpty()) return false;
		try {
			configs.forEach(store::persist);
			return true;
		} catch (Exception ex) {
			ex.printStackTrace();
			return false;
		}
	}

	private static final String DESCRIPTOR_PROJECTION =
		"SELECT new dev.frostguard.api.domain.AccountDescriptor(" +
			"p.id, p.label, p.deviceIndex, p.active, p.weight, p.retryInterval, " +
			"p.avatarId, p.avatarName, p.guildTag, p.realm" +
		") FROM Profile p";

	private static final String CONFIG_PROJECTION =
		"SELECT new dev.frostguard.api.domain.ConfigData(" +
			"c.owner.id, c.identifier, c.content" +
		") FROM Config c";

	private List<AccountDescriptor> fetchAllDescriptors() {
		List<AccountDescriptor> result = store.executeQuery(
			DESCRIPTOR_PROJECTION, AccountDescriptor.class, null);
		return result != null ? result : new ArrayList<>();
	}

	private Optional<AccountDescriptor> findDescriptorById(Long profileId) {
		String query = DESCRIPTOR_PROJECTION + " WHERE p.id = :profileId";
		List<AccountDescriptor> rows = store.executeQuery(
			query, AccountDescriptor.class, Map.of("profileId", profileId));
		return rows != null && !rows.isEmpty()
			? Optional.of(rows.get(0))
			: Optional.empty();
	}

	private void attachConfigEntries(List<AccountDescriptor> descriptors) {
		List<Long> profileIds = descriptors.stream()
			.map(AccountDescriptor::getId)
			.collect(Collectors.toList());

		if (profileIds.isEmpty()) return;

		String query = CONFIG_PROJECTION + " WHERE c.owner.id IN :profileIds";
		List<ConfigData> entries = store.executeQuery(
			query, ConfigData.class, Map.of("profileIds", profileIds));

		if (entries == null) return;

		Map<Long, List<ConfigData>> grouped = entries.stream()
			.collect(Collectors.groupingBy(ConfigData::getProfileId));

		descriptors.forEach(d ->
			d.setConfigs(grouped.getOrDefault(d.getId(), new ArrayList<>())));
	}

	private AccountDescriptor attachSingleConfigs(AccountDescriptor descriptor) {
		String query = CONFIG_PROJECTION + " WHERE c.owner.id = :profileId";
		List<ConfigData> entries = store.executeQuery(
			query, ConfigData.class, Map.of("profileId", descriptor.getId()));
		descriptor.setConfigs(entries != null ? entries : new ArrayList<>());
		return descriptor;
	}

	private List<Config> querySettingsByProfile(Long profileId) {
		return store.executeQuery(
			"SELECT c FROM Config c WHERE c.owner.id = :profileId",
			Config.class, Map.of("profileId", profileId));
	}

	private void provisionDefaultProfile() {
		store.persist(Profile.createDefault());
	}
}
