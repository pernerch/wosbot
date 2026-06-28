package dev.frostguard.engine.listener;

import dev.frostguard.api.domain.AccountDescriptor;

import java.util.List;

// Profile CRUD operations and listener registration hooks exposed to the UI layer.
public interface ProfileServiceInterface {

    List<AccountDescriptor> fetchAllAccounts();

    boolean createAccount(AccountDescriptor profile);

    boolean persistAccount(AccountDescriptor profile);

    boolean removeAccount(AccountDescriptor profile);

    boolean applyBulkUpdate(AccountDescriptor templateAccount);

    void registerStatusObserver(ProfileStatusChangeListener observer);

    void registerDataObserver(ProfileDataChangeListener observer);
}
