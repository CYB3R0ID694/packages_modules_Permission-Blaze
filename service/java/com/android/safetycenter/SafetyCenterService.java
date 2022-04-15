/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.safetycenter;

import static android.Manifest.permission.MANAGE_SAFETY_CENTER;
import static android.Manifest.permission.READ_SAFETY_CENTER_STATUS;
import static android.Manifest.permission.SEND_SAFETY_CENTER_UPDATE;
import static android.os.Build.VERSION_CODES.TIRAMISU;
import static android.safetycenter.SafetyCenterManager.RefreshReason;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.safetycenter.IOnSafetyCenterDataChangedListener;
import android.safetycenter.ISafetyCenterManager;
import android.safetycenter.SafetyCenterData;
import android.safetycenter.SafetyCenterErrorDetails;
import android.safetycenter.SafetyCenterManager;
import android.safetycenter.SafetyEvent;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceErrorDetails;
import android.safetycenter.SafetySourceIssue;
import android.safetycenter.config.SafetyCenterConfig;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.RequiresApi;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.utils.BackgroundThread;
import com.android.permission.util.UserUtils;
import com.android.safetycenter.SafetyCenterConfigReader.SafetyCenterConfigInternal;
import com.android.safetycenter.internaldata.SafetyCenterIds;
import com.android.safetycenter.internaldata.SafetyCenterIssueActionId;
import com.android.safetycenter.internaldata.SafetyCenterIssueId;
import com.android.safetycenter.resources.SafetyCenterResourcesContext;
import com.android.server.SystemService;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Service for the safety center.
 *
 * @hide
 */
@Keep
@RequiresApi(TIRAMISU)
public final class SafetyCenterService extends SystemService {

    private static final String TAG = "SafetyCenterService";

    /** Phenotype flag that determines whether SafetyCenter is enabled. */
    private static final String PROPERTY_SAFETY_CENTER_ENABLED = "safety_center_is_enabled";

    private final Object mApiLock = new Object();
    // Broadcasts to safety sources are guarded by another lock: we may want to do this sequentially
    // in a blocking fashion and the APIs that will be exercised by the receivers are already
    // protected by `mApiLock`.
    private final Object mBroadcastLock = new Object();

    @GuardedBy("mApiLock")
    private final SafetyCenterListeners mSafetyCenterListeners = new SafetyCenterListeners();

    @GuardedBy("mApiLock")
    @NonNull
    private final SafetyCenterConfigReader mSafetyCenterConfigReader;

    @GuardedBy("mApiLock")
    @NonNull
    private final SafetyCenterDataTracker mSafetyCenterDataTracker;

    @GuardedBy("mBroadcastLock")
    @NonNull
    private final SafetyCenterBroadcastDispatcher mSafetyCenterBroadcastDispatcher;

    @NonNull private final AppOpsManager mAppOpsManager;
    private final boolean mDeviceSupportsSafetyCenter;

    /** Whether the {@link SafetyCenterConfig} was successfully loaded. */
    private volatile boolean mConfigAvailable;

    public SafetyCenterService(@NonNull Context context) {
        super(context);
        SafetyCenterResourcesContext safetyCenterResourcesContext =
                new SafetyCenterResourcesContext(context);
        mSafetyCenterConfigReader = new SafetyCenterConfigReader(safetyCenterResourcesContext);
        mSafetyCenterDataTracker =
                new SafetyCenterDataTracker(context, safetyCenterResourcesContext);
        mSafetyCenterBroadcastDispatcher = new SafetyCenterBroadcastDispatcher(context);
        mAppOpsManager = requireNonNull(context.getSystemService(AppOpsManager.class));
        mDeviceSupportsSafetyCenter =
                context.getResources()
                        .getBoolean(
                                Resources.getSystem()
                                        .getIdentifier(
                                                "config_enableSafetyCenter", "bool", "android"));
    }

    @Override
    public void onStart() {
        publishBinderService(Context.SAFETY_CENTER_SERVICE, new Stub());
        if (mDeviceSupportsSafetyCenter) {
            synchronized (mApiLock) {
                mConfigAvailable = mSafetyCenterConfigReader.loadConfig();
            }
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED && canUseSafetyCenter()) {
            Executor backgroundThreadExecutor = BackgroundThread.getExecutor();
            SafetyCenterEnabledListener listener = new SafetyCenterEnabledListener();
            // Ensure the listener is called first with the current state on the same thread.
            backgroundThreadExecutor.execute(
                    () -> listener.onSafetyCenterEnabledChanged(getSafetyCenterEnabledProperty()));
            DeviceConfig.addOnPropertiesChangedListener(
                    DeviceConfig.NAMESPACE_PRIVACY, backgroundThreadExecutor, listener);
        }
    }

    /** Service implementation of {@link ISafetyCenterManager.Stub}. */
    private final class Stub extends ISafetyCenterManager.Stub {
        @Override
        public boolean isSafetyCenterEnabled() {
            enforceAnyCallingOrSelfPermissions(
                    "isSafetyCenterEnabled", READ_SAFETY_CENTER_STATUS, SEND_SAFETY_CENTER_UPDATE);

            return isApiEnabled();
        }

        @Override
        public void setSafetySourceData(
                @NonNull String safetySourceId,
                @Nullable SafetySourceData safetySourceData,
                @NonNull SafetyEvent safetyEvent,
                @NonNull String packageName,
                @UserIdInt int userId) {
            getContext()
                    .enforceCallingOrSelfPermission(
                            SEND_SAFETY_CENTER_UPDATE, "setSafetySourceData");
            requireNonNull(safetySourceId);
            requireNonNull(safetyEvent);
            requireNonNull(packageName);
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
            if (!enforceCrossUserPermission("setSafetySourceData", userId)
                    || !checkApiEnabled("setSafetySourceData")) {
                return;
            }

            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);

            SafetyCenterData safetyCenterData;
            List<RemoteCallbackList<IOnSafetyCenterDataChangedListener>> listeners;
            synchronized (mApiLock) {
                SafetyCenterConfigInternal configInternal =
                        mSafetyCenterConfigReader.getCurrentConfigInternal();
                boolean hasUpdate =
                        mSafetyCenterDataTracker.setSafetySourceData(
                                configInternal,
                                safetySourceData,
                                safetySourceId,
                                safetyEvent,
                                packageName,
                                userId);
                if (!hasUpdate) {
                    return;
                }
                safetyCenterData =
                        mSafetyCenterDataTracker.getSafetyCenterData(
                                configInternal, userProfileGroup);
                listeners = mSafetyCenterListeners.getListeners(userProfileGroup);
            }

            // TODO(b/228832622): Ensure listeners are called only when data changes.
            SafetyCenterListeners.deliverUpdate(listeners, safetyCenterData, null);
        }

        @Override
        @Nullable
        public SafetySourceData getSafetySourceData(
                @NonNull String safetySourceId,
                @NonNull String packageName,
                @UserIdInt int userId) {
            getContext()
                    .enforceCallingOrSelfPermission(
                            SEND_SAFETY_CENTER_UPDATE, "getSafetySourceData");
            requireNonNull(safetySourceId);
            requireNonNull(packageName);
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
            if (!enforceCrossUserPermission("getSafetySourceData", userId)
                    || !checkApiEnabled("getSafetySourceData")) {
                return null;
            }

            synchronized (mApiLock) {
                SafetyCenterConfigReader.SafetyCenterConfigInternal configInternal =
                        mSafetyCenterConfigReader.getCurrentConfigInternal();
                return mSafetyCenterDataTracker.getSafetySourceData(
                        configInternal, safetySourceId, packageName, userId);
            }
        }

        @Override
        public void reportSafetySourceError(
                @NonNull String safetySourceId,
                @NonNull SafetySourceErrorDetails errorDetails,
                @NonNull String packageName,
                @UserIdInt int userId) {
            getContext()
                    .enforceCallingOrSelfPermission(
                            SEND_SAFETY_CENTER_UPDATE, "reportSafetySourceError");
            requireNonNull(safetySourceId);
            requireNonNull(errorDetails);
            requireNonNull(packageName);
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
            if (!enforceCrossUserPermission("reportSafetySourceError", userId)
                    || !checkApiEnabled("reportSafetySourceError")) {
                return;
            }

            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);

            SafetyCenterData safetyCenterData;
            SafetyCenterErrorDetails safetyCenterErrorDetails;
            List<RemoteCallbackList<IOnSafetyCenterDataChangedListener>> listeners;
            synchronized (mApiLock) {
                SafetyCenterConfigReader.SafetyCenterConfigInternal configInternal =
                        mSafetyCenterConfigReader.getCurrentConfigInternal();
                safetyCenterErrorDetails =
                        mSafetyCenterDataTracker.reportSafetySourceError(
                                configInternal, safetySourceId, errorDetails, packageName, userId);
                safetyCenterData =
                        mSafetyCenterDataTracker.getSafetyCenterData(
                                configInternal, userProfileGroup);
                listeners = mSafetyCenterListeners.getListeners(userProfileGroup);
            }

            // TODO(b/228832622): Ensure listeners are called only when data changes.
            SafetyCenterListeners.deliverUpdate(
                    listeners, safetyCenterData, safetyCenterErrorDetails);
        }

        @Override
        public void refreshSafetySources(@RefreshReason int refreshReason, @UserIdInt int userId) {
            getContext().enforceCallingPermission(MANAGE_SAFETY_CENTER, "refreshSafetySources");
            if (!enforceCrossUserPermission("refreshSafetySources", userId)
                    || !checkApiEnabled("refreshSafetySources")) {
                return;
            }

            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);

            SafetyCenterConfigInternal configInternal;
            synchronized (mApiLock) {
                configInternal = mSafetyCenterConfigReader.getCurrentConfigInternal();
            }

            synchronized (mBroadcastLock) {
                mSafetyCenterBroadcastDispatcher.sendRefreshSafetySources(
                        configInternal, refreshReason, userProfileGroup);
            }
        }

        @Override
        @Nullable
        public SafetyCenterConfig getSafetyCenterConfig() {
            getContext()
                    .enforceCallingOrSelfPermission(MANAGE_SAFETY_CENTER, "getSafetyCenterConfig");

            synchronized (mApiLock) {
                return mSafetyCenterConfigReader.getCurrentConfigInternal().getSafetyCenterConfig();
            }
        }

        @Override
        @NonNull
        public SafetyCenterData getSafetyCenterData(@UserIdInt int userId) {
            getContext()
                    .enforceCallingOrSelfPermission(MANAGE_SAFETY_CENTER, "getSafetyCenterData");
            if (!enforceCrossUserPermission("getSafetyCenterData", userId)
                    || !checkApiEnabled("getSafetyCenterData")) {
                return SafetyCenterDataTracker.getDefaultSafetyCenterData();
            }

            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);

            synchronized (mApiLock) {
                return mSafetyCenterDataTracker.getSafetyCenterData(
                        mSafetyCenterConfigReader.getCurrentConfigInternal(), userProfileGroup);
            }
        }

        @Override
        public void addOnSafetyCenterDataChangedListener(
                @NonNull IOnSafetyCenterDataChangedListener listener, @UserIdInt int userId) {
            getContext()
                    .enforceCallingOrSelfPermission(
                            MANAGE_SAFETY_CENTER, "addOnSafetyCenterDataChangedListener");
            requireNonNull(listener);
            if (!enforceCrossUserPermission("addOnSafetyCenterDataChangedListener", userId)
                    || !checkApiEnabled("addOnSafetyCenterDataChangedListener")) {
                return;
            }

            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);

            SafetyCenterData safetyCenterData;
            synchronized (mApiLock) {
                boolean registered = mSafetyCenterListeners.addListener(listener, userId);
                if (!registered) {
                    return;
                }
                safetyCenterData =
                        mSafetyCenterDataTracker.getSafetyCenterData(
                                mSafetyCenterConfigReader.getCurrentConfigInternal(),
                                userProfileGroup);
            }

            SafetyCenterListeners.deliverUpdate(listener, safetyCenterData, null);
        }

        @Override
        public void removeOnSafetyCenterDataChangedListener(
                @NonNull IOnSafetyCenterDataChangedListener listener, @UserIdInt int userId) {
            getContext()
                    .enforceCallingOrSelfPermission(
                            MANAGE_SAFETY_CENTER, "removeOnSafetyCenterDataChangedListener");
            requireNonNull(listener);
            if (!enforceCrossUserPermission("removeOnSafetyCenterDataChangedListener", userId)
                    || !checkApiEnabled("removeOnSafetyCenterDataChangedListener")) {
                return;
            }

            synchronized (mApiLock) {
                mSafetyCenterListeners.removeListener(listener, userId);
            }
        }

        @Override
        public void dismissSafetyCenterIssue(@NonNull String issueId, @UserIdInt int userId) {
            getContext()
                    .enforceCallingOrSelfPermission(
                            MANAGE_SAFETY_CENTER, "dismissSafetyCenterIssue");
            requireNonNull(issueId);
            if (!enforceCrossUserPermission("dismissSafetyCenterIssue", userId)
                    || !checkApiEnabled("dismissSafetyCenterIssue")) {
                return;
            }

            SafetyCenterIssueId safetyCenterIssueId = SafetyCenterIds.issueIdFromString(issueId);
            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);
            enforceSameUserProfileGroup(
                    "dismissSafetyCenterIssue", userProfileGroup, safetyCenterIssueId.getUserId());

            SafetySourceIssue safetySourceIssue;
            SafetyCenterData safetyCenterData;
            List<RemoteCallbackList<IOnSafetyCenterDataChangedListener>> listeners;
            synchronized (mApiLock) {
                safetySourceIssue =
                        mSafetyCenterDataTracker.getSafetySourceIssue(safetyCenterIssueId);
                if (safetySourceIssue == null) {
                    Log.w(
                            TAG,
                            "Attempt to dismiss an issue that is not provided by the source, or "
                                    + "that was dismissed already");
                    return;
                }
                mSafetyCenterDataTracker.dismissSafetyCenterIssue(safetyCenterIssueId);
                safetyCenterData =
                        mSafetyCenterDataTracker.getSafetyCenterData(
                                mSafetyCenterConfigReader.getCurrentConfigInternal(),
                                userProfileGroup);
                listeners = mSafetyCenterListeners.getListeners(userProfileGroup);
            }

            // TODO(b/228832622): Ensure listeners are called only when data changes.
            SafetyCenterListeners.deliverUpdate(listeners, safetyCenterData, null);

            PendingIntent onDismissPendingIntent = safetySourceIssue.getOnDismissPendingIntent();
            if (onDismissPendingIntent != null) {
                dispatchPendingIntent(onDismissPendingIntent);
            }
        }

        @Override
        public void executeSafetyCenterIssueAction(
                @NonNull String issueId, @NonNull String issueActionId, @UserIdInt int userId) {
            getContext()
                    .enforceCallingOrSelfPermission(
                            MANAGE_SAFETY_CENTER, "executeSafetyCenterIssueAction");
            requireNonNull(issueId);
            requireNonNull(issueActionId);
            if (!enforceCrossUserPermission("executeSafetyCenterIssueAction", userId)
                    || !checkApiEnabled("executeSafetyCenterIssueAction")) {
                return;
            }

            SafetyCenterIssueId safetyCenterIssueId = SafetyCenterIds.issueIdFromString(issueId);
            SafetyCenterIssueActionId safetyCenterIssueActionId =
                    SafetyCenterIds.issueActionIdFromString(issueActionId);
            if (!safetyCenterIssueActionId.getSafetyCenterIssueId().equals(safetyCenterIssueId)) {
                throw new IllegalArgumentException(
                        String.format(
                                "issueId: %s and issueActionId: %s do not match",
                                safetyCenterIssueId, safetyCenterIssueActionId));
            }
            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);
            enforceSameUserProfileGroup(
                    "executeSafetyCenterIssueAction",
                    userProfileGroup,
                    safetyCenterIssueId.getUserId());

            SafetySourceIssue.Action safetySourceIssueAction;
            SafetyCenterData safetyCenterData = null;
            List<RemoteCallbackList<IOnSafetyCenterDataChangedListener>> listeners = null;
            synchronized (mApiLock) {
                safetySourceIssueAction =
                        mSafetyCenterDataTracker.getSafetySourceIssueAction(
                                safetyCenterIssueActionId);
                if (safetySourceIssueAction == null) {
                    Log.w(
                            TAG,
                            "Attempt to execute an issue action that is not provided by the source,"
                                    + " that was dismissed, or is already in flight");
                    return;
                }
                if (safetySourceIssueAction.willResolve()) {
                    mSafetyCenterDataTracker.markSafetyCenterIssueActionAsInFlight(
                            safetyCenterIssueActionId);
                    safetyCenterData =
                            mSafetyCenterDataTracker.getSafetyCenterData(
                                    mSafetyCenterConfigReader.getCurrentConfigInternal(),
                                    userProfileGroup);
                    listeners = mSafetyCenterListeners.getListeners(userProfileGroup);
                }
            }

            if (listeners != null) {
                // TODO(b/228832622): Ensure listeners are called only when data changes.
                SafetyCenterListeners.deliverUpdate(listeners, safetyCenterData, null);
            }
            // TODO(b/229080116): Unmark as in flight if there is an issue dispatching the
            //  PendingIntent.
            dispatchPendingIntent(safetySourceIssueAction.getPendingIntent());
        }

        @Override
        public void clearAllSafetySourceDataForTests() {
            getContext()
                    .enforceCallingOrSelfPermission(
                            MANAGE_SAFETY_CENTER, "clearAllSafetySourceDataForTests");
            if (!checkApiEnabled("clearAllSafetySourceDataForTests")) {
                return;
            }

            synchronized (mApiLock) {
                mSafetyCenterDataTracker.clear();
                // TODO(b/223550097): Should we dispatch a new listener update here? This call can
                //  modify the SafetyCenterData.
            }
        }

        @Override
        public void setSafetyCenterConfigForTests(@NonNull SafetyCenterConfig safetyCenterConfig) {
            getContext()
                    .enforceCallingOrSelfPermission(
                            MANAGE_SAFETY_CENTER, "setSafetyCenterConfigForTests");
            requireNonNull(safetyCenterConfig);
            if (!checkApiEnabled("setSafetyCenterConfigForTests")) {
                return;
            }

            synchronized (mApiLock) {
                mSafetyCenterConfigReader.setConfigOverrideForTests(safetyCenterConfig);
                mSafetyCenterDataTracker.clear();
                // TODO(b/223550097): Should we clear the listeners here? Or should we dispatch a
                //  new listener update since the SafetyCenterData will have changed?
            }
        }

        @Override
        public void clearSafetyCenterConfigForTests() {
            getContext()
                    .enforceCallingOrSelfPermission(
                            MANAGE_SAFETY_CENTER, "clearSafetyCenterConfigForTests");
            if (!checkApiEnabled("clearSafetyCenterConfigForTests")) {
                return;
            }

            synchronized (mApiLock) {
                mSafetyCenterConfigReader.clearConfigOverrideForTests();
                mSafetyCenterDataTracker.clear();
                // TODO(b/223550097): Should we clear the listeners here? Or should we dispatch a
                //  new listener update since the SafetyCenterData will have changed?
            }
        }

        private boolean isApiEnabled() {
            return canUseSafetyCenter() && getSafetyCenterEnabledProperty();
        }

        private void enforceAnyCallingOrSelfPermissions(
                @NonNull String message, String... permissions) {
            if (permissions.length == 0) {
                throw new IllegalArgumentException("Must check at least one permission");
            }
            for (int i = 0; i < permissions.length; i++) {
                if (getContext().checkCallingOrSelfPermission(permissions[i])
                        == PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }
            throw new SecurityException(
                    String.format(
                            "%s requires any of: %s, but none were granted",
                            message, Arrays.toString(permissions)));
        }

        /** Enforces cross user permission and returns whether the user is existent. */
        private boolean enforceCrossUserPermission(@NonNull String message, @UserIdInt int userId) {
            UserUtils.enforceCrossUserPermission(userId, false, message, getContext());
            if (!UserUtils.isUserExistent(userId, getContext())) {
                Log.e(
                        TAG,
                        String.format(
                                "Called %s with user id %s, which does not correspond to an"
                                        + " existing user",
                                message, userId));
                return false;
            }
            // TODO(b/223132917): Check if user is enabled, running and/or if quiet mode is enabled?
            return true;
        }

        private boolean checkApiEnabled(@NonNull String message) {
            if (!isApiEnabled()) {
                Log.w(TAG, String.format("Called %s, but Safety Center is disabled", message));
                return false;
            }
            return true;
        }

        private void enforceSameUserProfileGroup(
                @NonNull String message,
                @NonNull UserProfileGroup userProfileGroup,
                @UserIdInt int userId) {
            if (!userProfileGroup.contains(userId)) {
                throw new SecurityException(
                        String.format(
                                "%s requires target user id %s to be within the same profile group"
                                        + " of the caller: %s",
                                message, userId, userProfileGroup));
            }
        }

        private void dispatchPendingIntent(@NonNull PendingIntent pendingIntent) {
            try {
                pendingIntent.send();
            } catch (PendingIntent.CanceledException ex) {
                Log.w(TAG, "Couldn't dispatch PendingIntent", ex);
                // TODO(b/229080116): Propagate error with listeners here?
            }
        }
    }

    /**
     * An {@link OnPropertiesChangedListener} for {@link #PROPERTY_SAFETY_CENTER_ENABLED} that sends
     * broadcasts when the SafetyCenter property is enabled or disabled.
     *
     * <p>This listener assumes that the {@link #PROPERTY_SAFETY_CENTER_ENABLED} value maps to
     * {@link SafetyCenterManager#isSafetyCenterEnabled()}. It should only be registered if the
     * device supports SafetyCenter and the {@link SafetyCenterConfig} was loaded successfully.
     *
     * <p>This listener is not thread-safe; it should be called on a single thread.
     */
    private final class SafetyCenterEnabledListener implements OnPropertiesChangedListener {

        private boolean mSafetyCenterEnabled;

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            if (!properties.getKeyset().contains(PROPERTY_SAFETY_CENTER_ENABLED)) {
                return;
            }
            boolean safetyCenterEnabled =
                    properties.getBoolean(PROPERTY_SAFETY_CENTER_ENABLED, false);
            if (mSafetyCenterEnabled == safetyCenterEnabled) {
                return;
            }
            onSafetyCenterEnabledChanged(safetyCenterEnabled);
        }

        private void onSafetyCenterEnabledChanged(boolean safetyCenterEnabled) {
            if (safetyCenterEnabled) {
                onApiEnabled();
            } else {
                onApiDisabled();
            }
            mSafetyCenterEnabled = safetyCenterEnabled;
        }

        private void onApiEnabled() {
            SafetyCenterConfigInternal configInternal;

            synchronized (mApiLock) {
                configInternal = mSafetyCenterConfigReader.getCurrentConfigInternal();
            }

            synchronized (mBroadcastLock) {
                mSafetyCenterBroadcastDispatcher.sendEnabledChanged(configInternal);
            }
        }

        private void onApiDisabled() {
            SafetyCenterConfigInternal configInternal;

            synchronized (mApiLock) {
                configInternal = mSafetyCenterConfigReader.getCurrentConfigInternal();
                mSafetyCenterDataTracker.clear();
                mSafetyCenterListeners.clear();
            }

            synchronized (mBroadcastLock) {
                mSafetyCenterBroadcastDispatcher.sendEnabledChanged(configInternal);
            }
        }
    }

    private boolean canUseSafetyCenter() {
        return mDeviceSupportsSafetyCenter && mConfigAvailable;
    }

    private boolean getSafetyCenterEnabledProperty() {
        // This call requires the READ_DEVICE_CONFIG permission.
        final long callingId = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_PRIVACY,
                    PROPERTY_SAFETY_CENTER_ENABLED,
                    /* defaultValue = */ false);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }
}
