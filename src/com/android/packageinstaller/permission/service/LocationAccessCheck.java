/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.packageinstaller.permission.service;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.app.AppOpsManager.OPSTR_FINE_LOCATION;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.app.PendingIntent.getBroadcast;
import static android.app.job.JobScheduler.RESULT_SUCCESS;
import static android.content.Context.MODE_PRIVATE;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_PERMISSION_NAME;
import static android.content.Intent.EXTRA_UID;
import static android.content.Intent.EXTRA_USER;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static android.graphics.Bitmap.createBitmap;
import static android.os.UserHandle.getUserHandleForUid;
import static android.os.UserHandle.myUserId;
import static android.provider.Settings.Secure.LOCATION_ACCESS_CHECK_DELAY_MILLIS;
import static android.provider.Settings.Secure.LOCATION_ACCESS_CHECK_INTERVAL_MILLIS;

import static com.android.packageinstaller.Constants.KEY_LAST_LOCATION_ACCESS_NOTIFICATION_SHOWN;
import static com.android.packageinstaller.Constants.LOCATION_ACCESS_CHECK_ALREADY_NOTIFIED_FILE;
import static com.android.packageinstaller.Constants.LOCATION_ACCESS_CHECK_JOB_ID;
import static com.android.packageinstaller.Constants.LOCATION_ACCESS_CHECK_NOTIFICATION_ID;
import static com.android.packageinstaller.Constants.PERIODIC_LOCATION_ACCESS_CHECK_JOB_ID;
import static com.android.packageinstaller.Constants.PERMISSION_REMINDER_CHANNEL_ID;
import static com.android.packageinstaller.Constants.PREFERENCES_FILE;
import static com.android.packageinstaller.permission.utils.LocationUtils.isNetworkLocationProvider;
import static com.android.packageinstaller.permission.utils.Utils.OS_PKG;
import static com.android.packageinstaller.permission.utils.Utils.getGroupOfPlatformPermission;
import static com.android.packageinstaller.permission.utils.Utils.getParcelableExtraSafe;
import static com.android.packageinstaller.permission.utils.Utils.getStringExtraSafe;
import static com.android.packageinstaller.permission.utils.Utils.getSystemServiceSafe;

import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.app.AppOpsManager;
import android.app.AppOpsManager.HistoricalOp;
import android.app.AppOpsManager.HistoricalOps;
import android.app.AppOpsManager.HistoricalPackageOps;
import android.app.AppOpsManager.HistoricalUidOps;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Preconditions;

import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.Permission;
import com.android.packageinstaller.permission.ui.AppPermissionActivity;
import com.android.permissioncontroller.R;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Show notification that double-guesses the user if she/he really wants to grant fine background
 * location access to an app.
 *
 * <p>A notification is scheduled after the background permission access is granted via
 * {@link #checkLocationAccessSoon()} or periodically.
 *
 * <p>We rate limit the number of notification we show and only ever show one notification at a
 * time. Further we only shown notifications if the app has actually accessed the fine location
 * in the background.
 *
 * <p>As there are many cases why a notification should not been shown, we always schedule a
 * {@link #addLocationNotificationIfNeeded check} which then might add a notification.
 */
public class LocationAccessCheck {
    private static final String LOG_TAG = LocationAccessCheck.class.getSimpleName();
    private static final boolean DEBUG = false;

    /** Lock required for all methods called {@code ...Locked} */
    private static final Object sLock = new Object();

    private final Random mRandom = new Random();

    private final @NonNull Context mContext;
    private final @NonNull JobScheduler mJobScheduler;
    private final @NonNull ContentResolver mContentResolver;
    private final @NonNull AppOpsManager mAppOpsManager;
    private final @NonNull PackageManager mPackageManager;
    private final @NonNull UserManager mUserManager;
    private final @NonNull SharedPreferences mSharedPrefs;

    /** If the current long running operation should be canceled */
    private final @Nullable BooleanSupplier mShouldCancel;

    /**
     * Get time in between two periodic checks.
     *
     * <p>Default: 1 day
     *
     * @return The time in between check in milliseconds
     */
    private long getPeriodicCheckIntervalMillis() {
        return Settings.Secure.getLong(mContentResolver,
                LOCATION_ACCESS_CHECK_INTERVAL_MILLIS, DAYS.toMillis(1));
    }

    /**
     * Flexibility of the periodic check.
     *
     * <p>10% of {@link #getPeriodicCheckIntervalMillis()}
     *
     * @return The flexibility of the periodic check in milliseconds
     */
    private long getFlexForPeriodicCheckMillis() {
        return getPeriodicCheckIntervalMillis() / 10;
    }

    /**
     * Get the delay in between granting a permission and the follow up check.
     *
     * <p>Default: 10 minutes
     *
     * @return The delay in milliseconds
     */
    private long getDelayMillis() {
        return Settings.Secure.getLong(mContentResolver,
                LOCATION_ACCESS_CHECK_DELAY_MILLIS, MINUTES.toMillis(10));
    }

    /**
     * Minimum time in between showing two notifications.
     *
     * <p>This is just small enough so that the periodic check can always show a notification.
     *
     * @return The minimum time in milliseconds
     */
    private long getInBetweenNotificationsMillis() {
        return getPeriodicCheckIntervalMillis() - (long) (getFlexForPeriodicCheckMillis() * 2.1);
    }

    /**
     * Load the list of {@link UserPackage packages} we already shown a notification for.
     *
     * @return The list of packages we already shown a notification for.
     */
    private @NonNull ArraySet<UserPackage> loadAlreadyNotifiedPackagesLocked() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                mContext.openFileInput(LOCATION_ACCESS_CHECK_ALREADY_NOTIFIED_FILE)))) {
            ArraySet<UserPackage> packages = new ArraySet<>();

            /*
             * The format of the file is <package> <serial of user>, e.g.
             *
             * com.one.package 5630633845
             * com.two.package 5630633853
             * com.three.package 5630633853
             */
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                String[] lineComponents = line.split(" ");
                String pkg = lineComponents[0];
                UserHandle user = mUserManager.getUserForSerialNumber(
                        Long.valueOf(lineComponents[1]));

                if (user != null) {
                    packages.add(new UserPackage(pkg, user));
                } else {
                    Log.i(LOG_TAG, "Not restoring state \"" + line + "\" as user is unknown");
                }
            }

            return packages;
        } catch (FileNotFoundException ignored) {
            return new ArraySet<>();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Could not read " + LOCATION_ACCESS_CHECK_ALREADY_NOTIFIED_FILE, e);
            return new ArraySet<>();
        }
    }

    /**
     * Safe the list of {@link UserPackage packages} we have already shown a notification for.
     *
     * @param packages The list of packages we already shown a notification for.
     */
    private void safeAlreadyNotifiedPackagesLocked(@NonNull ArraySet<UserPackage> packages) {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                mContext.openFileOutput(LOCATION_ACCESS_CHECK_ALREADY_NOTIFIED_FILE,
                        MODE_PRIVATE)))) {
            /*
             * The format of the file is <package> <serial of user>, e.g.
             *
             * com.one.package 5630633845
             * com.two.package 5630633853
             * com.three.package 5630633853
             */
            int numPkgs = packages.size();
            for (int i = 0; i < numPkgs; i++) {
                UserPackage userPkg = packages.valueAt(i);

                writer.append(userPkg.pkg);
                writer.append(' ');
                writer.append(
                        Long.valueOf(mUserManager.getSerialNumberForUser(userPkg.user)).toString());
                writer.newLine();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Could not write " + LOCATION_ACCESS_CHECK_ALREADY_NOTIFIED_FILE, e);
        }
    }

    /**
     * Remember that we showed a notification for a {@link UserPackage}
     *
     * @param pkg The package we notified for
     * @param user The user we notified for
     */
    private void markAsNotified(@NonNull String pkg, @NonNull UserHandle user) {
        synchronized (sLock) {
            ArraySet<UserPackage> alreadyNotifiedPackages = loadAlreadyNotifiedPackagesLocked();
            alreadyNotifiedPackages.add(new UserPackage(pkg, user));
            safeAlreadyNotifiedPackagesLocked(alreadyNotifiedPackages);
        }
    }

    /**
     * Create the channel the location access notifications should be posted to.
     *
     * @param user The user to create the channel for
     */
    private void createPermissionReminderChannel(@NonNull UserHandle user) {
        NotificationManager notificationManager = getSystemServiceSafe(mContext,
                NotificationManager.class, user);

        NotificationChannel permissionReminderChannel = new NotificationChannel(
                PERMISSION_REMINDER_CHANNEL_ID, mContext.getString(R.string.permission_reminders),
                IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(permissionReminderChannel);
    }

    /**
     * If {@link #mShouldCancel} throw an {@link InterruptedException}.
     */
    private void throwInterruptedExceptionIfTaskIsCanceled() throws InterruptedException {
        if (mShouldCancel != null && mShouldCancel.getAsBoolean()) {
            throw new InterruptedException();
        }
    }

    /**
     * Create a new {@link LocationAccessCheck} object.
     *
     * @param context Used to resolve managers
     * @param shouldCancel If supplied, can be used to interrupt long running operations
     */
    public LocationAccessCheck(@NonNull Context context, @Nullable BooleanSupplier shouldCancel) {
        UserHandle parentUser = getSystemServiceSafe(context, UserManager.class)
                .getProfileParent(UserHandle.of(myUserId()));

        if (parentUser != null) {
            // In a multi profile environment perform all operations as the parent user of the
            // current profile
            try {
                mContext = context.createPackageContextAsUser(context.getPackageName(), 0,
                        parentUser);
            } catch (PackageManager.NameNotFoundException e) {
                // cannot happen
                throw new IllegalStateException("Could not switch to parent user " + parentUser, e);
            }
        } else {
            mContext = context;
        }

        mJobScheduler = getSystemServiceSafe(mContext, JobScheduler.class);
        mAppOpsManager = getSystemServiceSafe(mContext, AppOpsManager.class);
        mPackageManager = mContext.getPackageManager();
        mUserManager = getSystemServiceSafe(mContext, UserManager.class);
        mSharedPrefs = mContext.getSharedPreferences(PREFERENCES_FILE, MODE_PRIVATE);
        mContentResolver = mContext.getContentResolver();

        mShouldCancel = shouldCancel;
    }

    /**
     * Check if a location access notification should be shown and then add it.
     *
     * <p>Always run async inside a
     * {@link LocationAccessCheckJobService.AddLocationNotificationIfNeededTask}.
     */
    private void addLocationNotificationIfNeeded(@NonNull JobParameters params,
            @NonNull LocationAccessCheckJobService service) {
        synchronized (sLock) {
            if (currentTimeMillis() - mSharedPrefs.getLong(
                    KEY_LAST_LOCATION_ACCESS_NOTIFICATION_SHOWN, 0)
                    < getInBetweenNotificationsMillis()) {
                return;
            }

            if (getCurrentlyShownNotificationLocked() != null) {
                return;
            }

            mAppOpsManager.getHistoricalOps(Process.INVALID_UID, null /*packageName*/,
                    new String[]{OPSTR_FINE_LOCATION},
                    Calendar.getInstance().getTimeInMillis(), System.currentTimeMillis(),
                    mContext.getMainExecutor(), (HistoricalOps ops)
                            -> addLocationNotificationIfNeededInterruptive(ops, params, service));
        }
    }

    private void addLocationNotificationIfNeededInterruptive(@NonNull HistoricalOps ops,
            @Nullable JobParameters params, @NonNull LocationAccessCheckJobService service) {
        try {
            addLocationNotificationIfNeeded(ops);
            service.jobFinished(params, false);
        } catch (InterruptedException e) {
            service.jobFinished(params, true);
        } finally {
            synchronized (sLock) {
                service.mAddLocationNotificationIfNeededTask = null;
            }
        }
    }

    private void addLocationNotificationIfNeeded(@NonNull HistoricalOps ops)
            throws InterruptedException {
        synchronized (sLock) {
            List<UserPackage> packages = getLocationUsersWithNoNotificationYetLocked(ops);

            // Get a random package and resolve package info
            PackageInfo pkgInfo = null;
            while (pkgInfo == null) {
                throwInterruptedExceptionIfTaskIsCanceled();

                if (packages.isEmpty()) {
                    return;
                }

                UserPackage packageToNotifyFor = null;

                // Prefer location history
                /* TODO: Enable once we know the location history package
                int numPkgs = packages.size();
                for (int i = 0; i < numPkgs; i++) {
                    UserPackage pkg = packages.get(i);

                    if (pkg.pkg.equals(getSystemServiceSafe(this, LocationManager.class,
                            pkg.user).getLocationHistoryProviderPackage())) {
                        packageToNotifyFor = pkg;
                    }
                }
                */

                if (packageToNotifyFor == null) {
                    packageToNotifyFor = packages.get(mRandom.nextInt(packages.size()));
                }

                try {
                    pkgInfo = packageToNotifyFor.getPackageInfo(mContext);
                } catch (PackageManager.NameNotFoundException e) {
                    packages.remove(packageToNotifyFor);
                    continue;
                }

                if (!isBackgroundLocationPermissionGranted(pkgInfo)) {
                    pkgInfo = null;
                    packages.remove(packageToNotifyFor);
                }
            }

            createPermissionReminderChannel(getUserHandleForUid(pkgInfo.applicationInfo.uid));
            createNotificationForLocationUser(pkgInfo);
        }
    }

    /**
     * Get the {@link UserPackage packages} which accessed the location but we have not yet shown
     * a notification for.
     *
     * @return The packages we need to show a notification for
     *
     * @throws InterruptedException If {@link #mShouldCancel}
     */
    private @NonNull List<UserPackage> getLocationUsersWithNoNotificationYetLocked(
            @NonNull HistoricalOps ops) throws InterruptedException {
        List<UserPackage> pkgsWithLocationAccess = new ArrayList<>();
        List<UserHandle> profiles = mUserManager.getUserProfiles();

        final int numUidOps = ops.getUidCount();
        for (int uidOpsNum = 0; uidOpsNum < numUidOps; uidOpsNum++) {
            final HistoricalUidOps uidOps = ops.getUidOpsAt(uidOpsNum);
            final int numPkgOps = uidOps.getPackageCount();
            for (int pkgOpsNum = 0; pkgOpsNum < numPkgOps; pkgOpsNum++) {
                final HistoricalPackageOps pkgOps = uidOps.getPackageOpsAt(pkgOpsNum);
                final String pkg = pkgOps.getPackageName();
                if (pkg.equals(OS_PKG) || isNetworkLocationProvider(mContext, pkg)) {
                    continue;
                }
                final UserHandle user = getUserHandleForUid(uidOps.getUid());
                if (!profiles.contains(user)) {
                    continue;
                }
                final int numOps = pkgOps.getOpCount();
                for (int opsNum = 0; opsNum < numOps; opsNum++) {
                    final HistoricalOp op = pkgOps.getOpAt(opsNum);
                    if (op.getBackgroundAccessCount() > 0) {
                        pkgsWithLocationAccess.add(new UserPackage(pkg, user));
                        break;
                    }
                }
            }
        }

        ArraySet<UserPackage> alreadyNotifiedPkgs = loadAlreadyNotifiedPackagesLocked();
        throwInterruptedExceptionIfTaskIsCanceled();

        resetAlreadyNotifiedPackagesWithoutPermissionLocked(alreadyNotifiedPkgs);

        pkgsWithLocationAccess.removeAll(alreadyNotifiedPkgs);
        return pkgsWithLocationAccess;
    }

    /**
     * Create a notification reminding the user that a package used the location. From this
     * notification the user can directly go to the screen that allows to change the permission.
     *
     * @param pkg The {@link PackageInfo} for the package to to be changed
     */
    private void createNotificationForLocationUser(@NonNull PackageInfo pkg) {
        CharSequence pkgLabel = mPackageManager.getApplicationLabel(pkg.applicationInfo);
        Drawable pkgIcon = mPackageManager.getApplicationIcon(pkg.applicationInfo);
        Bitmap pkgIconBmp = createBitmap(pkgIcon.getIntrinsicWidth(), pkgIcon.getIntrinsicHeight(),
                ARGB_8888);
        Canvas canvas = new Canvas(pkgIconBmp);
        pkgIcon.setBounds(0, 0, pkgIcon.getIntrinsicWidth(), pkgIcon.getIntrinsicHeight());
        pkgIcon.draw(canvas);

        String pkgName = pkg.packageName;
        UserHandle user = getUserHandleForUid(pkg.applicationInfo.uid);

        NotificationManager notificationManager = getSystemServiceSafe(mContext,
                NotificationManager.class, user);

        Intent deleteIntent = new Intent(mContext, NotificationDeleteHandler.class);
        deleteIntent.putExtra(EXTRA_PACKAGE_NAME, pkgName);
        deleteIntent.putExtra(EXTRA_USER, user);

        Intent clickIntent = new Intent(mContext, NotificationClickHandler.class);
        clickIntent.putExtra(EXTRA_PACKAGE_NAME, pkgName);
        clickIntent.putExtra(EXTRA_USER, user);

        Notification.Builder b = (new Notification.Builder(mContext,
                PERMISSION_REMINDER_CHANNEL_ID))
                .setContentTitle(mContext.getString(
                        R.string.background_location_access_reminder_notification_title, pkgLabel))
                .setContentText(mContext.getString(
                        R.string.background_location_access_reminder_notification_content))
                .setStyle(new Notification.BigTextStyle().bigText(mContext.getString(
                        R.string.background_location_access_reminder_notification_content)))
                .setSmallIcon(R.drawable.ic_signal_location)
                .setLargeIcon(pkgIconBmp)
                .setColor(mContext.getColor(android.R.color.system_notification_accent_color))
                .setAutoCancel(true)
                .setDeleteIntent(getBroadcast(mContext, 0, deleteIntent,
                        FLAG_ONE_SHOT | FLAG_UPDATE_CURRENT))
                .setContentIntent(getBroadcast(mContext, 0, clickIntent,
                        FLAG_ONE_SHOT | FLAG_UPDATE_CURRENT));
        notificationManager.notify(pkgName, LOCATION_ACCESS_CHECK_NOTIFICATION_ID, b.build());

        if (DEBUG) Log.i(LOG_TAG, "Notified " + pkgName);

        mSharedPrefs.edit().putLong(KEY_LAST_LOCATION_ACCESS_NOTIFICATION_SHOWN,
                currentTimeMillis()).apply();
    }

    /**
     * Get currently shown notification. We only ever show one notification per profile group.
     *
     * @return The notification or {@code null} if no notification is currently shown
     */
    private @Nullable StatusBarNotification getCurrentlyShownNotificationLocked() {
        List<UserHandle> profiles = mUserManager.getUserProfiles();

        int numProfiles = profiles.size();
        for (int profileNum = 0; profileNum < numProfiles; profileNum++) {
            NotificationManager notificationManager = getSystemServiceSafe(mContext,
                    NotificationManager.class, profiles.get(profileNum));

            StatusBarNotification[] notifications = notificationManager.getActiveNotifications();

            int numNotifications = notifications.length;
            for (int notificationNum = 0; notificationNum < numNotifications; notificationNum++) {
                StatusBarNotification notification = notifications[notificationNum];

                if (notification.getId() == LOCATION_ACCESS_CHECK_NOTIFICATION_ID) {
                    return notification;
                }
            }
        }

        return null;
    }

    /**
     * Check is a package currently has the background access to
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} or can get it without user
     * interaction.
     *
     * @param pkg The package that might have access.
     *
     * @return {@code true} iff the app currently has access to the fine background location
     */
    private boolean isBackgroundLocationPermissionGranted(@NonNull PackageInfo pkg) {
        AppPermissionGroup locationGroup = AppPermissionGroup.create(mContext, pkg,
                ACCESS_FINE_LOCATION, false);

        if (locationGroup == null) {
            // All location permissions have been removed from this package
            return false;
        } else {
            AppPermissionGroup locationBgGroup = locationGroup.getBackgroundPermissions();
            Permission locationPerm = locationGroup.getPermission(ACCESS_FINE_LOCATION);

            // Individual permission have been removed
            return locationBgGroup != null
                    && locationPerm != null
                    && locationBgGroup.hasPermission(locationPerm.getBackgroundPermissionName())
                    && locationGroup.areRuntimePermissionsGranted()
                    && locationBgGroup.areRuntimePermissionsGranted();
        }
    }

    /**
     * Go through the list of packages we already shown a notification for and remove those that do
     * not request fine background location access.
     *
     * @param alreadyNotifiedPkgs The packages we already shown a notification for. This paramter is
     *                            modified inside of this method.
     *
     * @throws InterruptedException If {@link #mShouldCancel}
     */
    private void resetAlreadyNotifiedPackagesWithoutPermissionLocked(
            @NonNull ArraySet<UserPackage> alreadyNotifiedPkgs) throws InterruptedException {
        ArrayList<UserPackage> packagesToRemove = new ArrayList<>();

        for (UserPackage userPkg : alreadyNotifiedPkgs) {
            throwInterruptedExceptionIfTaskIsCanceled();

            PackageInfo pkgInfo;
            try {
                pkgInfo = userPkg.getPackageInfo(mContext);
            } catch (PackageManager.NameNotFoundException e) {
                packagesToRemove.add(userPkg);
                continue;
            }

            if (!isBackgroundLocationPermissionGranted(pkgInfo)) {
                packagesToRemove.add(userPkg);
            }
        }

        if (!packagesToRemove.isEmpty()) {
            alreadyNotifiedPkgs.removeAll(packagesToRemove);
            safeAlreadyNotifiedPackagesLocked(alreadyNotifiedPkgs);
            throwInterruptedExceptionIfTaskIsCanceled();
        }
    }

    /**
     * Remove all persisted state for a package.
     *
     * @param pkg name of package
     * @param user user the package belongs to
     */
    private void forgetAboutPackage(@NonNull String pkg, @NonNull UserHandle user) {
        synchronized (sLock) {
            StatusBarNotification notification = getCurrentlyShownNotificationLocked();
            if (notification != null && notification.getUser().equals(user)
                    && notification.getTag().equals(pkg)) {
                getSystemServiceSafe(mContext, NotificationManager.class, user).cancel(
                        pkg, LOCATION_ACCESS_CHECK_NOTIFICATION_ID);
            }

            ArraySet<UserPackage> packages = loadAlreadyNotifiedPackagesLocked();
            packages.remove(new UserPackage(pkg, user));
            safeAlreadyNotifiedPackagesLocked(packages);
        }
    }

    /**
     * After a small delay schedule a check if we should show a notification.
     *
     * <p>This is called when location access is granted to an app. In this case it is likely that
     * the app will access the location soon. If this happens the notification will appear only a
     * little after the user granted the location.
     */
    public void checkLocationAccessSoon() {
        JobInfo.Builder b = (new JobInfo.Builder(LOCATION_ACCESS_CHECK_JOB_ID,
                new ComponentName(mContext, LocationAccessCheckJobService.class)))
                .setMinimumLatency(getDelayMillis());

        int scheduleResult = mJobScheduler.schedule(b.build());
        if (scheduleResult != RESULT_SUCCESS) {
            Log.e(LOG_TAG, "Could not schedule location access check " + scheduleResult);
        }
    }

    /**
     * Check if the current user is the profile parent.
     *
     * @return {@code true} if the current user is the profile parent.
     */
    private boolean isRunningInParentProfile() {
        UserHandle user = UserHandle.of(myUserId());
        UserHandle parent = mUserManager.getProfileParent(user);

        return parent == null || user.equals(parent);
    }

    /**
     * On boot set up a periodic job that starts checks.
     */
    public static class SetupPeriodicBackgroundLocationAccessCheck extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            LocationAccessCheck locationAccessCheck = new LocationAccessCheck(context, null);
            JobScheduler jobScheduler = getSystemServiceSafe(context, JobScheduler.class);

            if (!locationAccessCheck.isRunningInParentProfile()) {
                // Profile parent handles child profiles too.
                return;
            }

            if (jobScheduler.getPendingJob(PERIODIC_LOCATION_ACCESS_CHECK_JOB_ID) == null) {
                JobInfo.Builder b = (new JobInfo.Builder(PERIODIC_LOCATION_ACCESS_CHECK_JOB_ID,
                        new ComponentName(context, LocationAccessCheckJobService.class)))
                        .setPeriodic(locationAccessCheck.getPeriodicCheckIntervalMillis(),
                                locationAccessCheck.getFlexForPeriodicCheckMillis());

                int scheduleResult = jobScheduler.schedule(b.build());
                if (scheduleResult != RESULT_SUCCESS) {
                    Log.e(LOG_TAG, "Could not schedule periodic location access check "
                            + scheduleResult);
                }
            }
        }
    }

    /**
     * Checks if a new notification should be shown.
     */
    public static class LocationAccessCheckJobService extends JobService {
        private LocationAccessCheck mLocationAccessCheck;

        /** If we currently check if we should show a notification, the task executing the check */
        // @GuardedBy("sLock")
        private @Nullable AddLocationNotificationIfNeededTask mAddLocationNotificationIfNeededTask;

        @Override
        public void onCreate() {
            super.onCreate();
            mLocationAccessCheck = new LocationAccessCheck(this, () -> {
                synchronized (sLock) {
                    AddLocationNotificationIfNeededTask task = mAddLocationNotificationIfNeededTask;

                    return task != null && task.isCancelled();
                }
            });
        }

        /**
         * Starts an asynchronous check if a location access notification should be shown.
         *
         * @param params Not used other than for interacting with job scheduling
         *
         * @return {@code false} iff another check if already running
         */
        @Override
        public boolean onStartJob(JobParameters params) {
            synchronized (LocationAccessCheck.sLock) {
                if (mAddLocationNotificationIfNeededTask != null) {
                    return false;
                }

                mAddLocationNotificationIfNeededTask =
                        new AddLocationNotificationIfNeededTask();

                mAddLocationNotificationIfNeededTask.execute(params, this);
            }

            return true;
        }

        /**
         * Abort the check if still running.
         *
         * @param params ignored
         *
         * @return false
         */
        @Override
        public boolean onStopJob(JobParameters params) {
            AddLocationNotificationIfNeededTask task;
            synchronized (sLock) {
                if (mAddLocationNotificationIfNeededTask == null) {
                    return false;
                } else {
                    task = mAddLocationNotificationIfNeededTask;
                }
            }

            task.cancel(false);

            try {
                // Wait for task to finish
                task.get();
            } catch (Exception e) {
                Log.e(LOG_TAG, "While waiting for " + task + " to finish", e);
            }

            return false;
        }

        /**
         * A {@link AsyncTask task} that runs the check in the background.
         */
        private class AddLocationNotificationIfNeededTask extends
                AsyncTask<Object, Void, Void> {
            @Override
            protected final Void doInBackground(Object... in) {
                JobParameters params = (JobParameters) in[0];
                LocationAccessCheckJobService service = (LocationAccessCheckJobService) in[1];
                mLocationAccessCheck.addLocationNotificationIfNeeded(params, service);
                return null;
            }
        }
    }

    /**
     * Handle the case where the notification is swiped away without further interaction.
     */
    public static class NotificationDeleteHandler extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String pkg = getStringExtraSafe(intent, EXTRA_PACKAGE_NAME);
            UserHandle user = getParcelableExtraSafe(intent, EXTRA_USER);

            new LocationAccessCheck(context, null).markAsNotified(pkg, user);
        }
    }

    /**
     * Show the location permission switch when the notification is clicked.
     */
    public static class NotificationClickHandler extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String pkg = getStringExtraSafe(intent, EXTRA_PACKAGE_NAME);
            UserHandle user = getParcelableExtraSafe(intent, EXTRA_USER);

            new LocationAccessCheck(context, null).markAsNotified(pkg, user);

            Intent manageAppPermission = new Intent(context, AppPermissionActivity.class);
            manageAppPermission.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK);
            manageAppPermission.putExtra(EXTRA_PERMISSION_NAME,
                    getGroupOfPlatformPermission(ACCESS_FINE_LOCATION));
            manageAppPermission.putExtra(EXTRA_PACKAGE_NAME, pkg);
            manageAppPermission.putExtra(EXTRA_USER, user);

            context.startActivity(manageAppPermission);
        }
    }

    /**
     * If a package gets removed or the data of the package gets cleared, forget that we showed a
     * notification for it.
     */
    public static class PackageResetHandler extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Uri data = Preconditions.checkNotNull(intent.getData());
            UserHandle user = getUserHandleForUid(intent.getIntExtra(EXTRA_UID, 0));

            if (DEBUG) Log.i(LOG_TAG, "Reset " + data.getSchemeSpecificPart());

            new LocationAccessCheck(context, null).forgetAboutPackage(
                    data.getSchemeSpecificPart(), user);
        }
    }

    /**
     * A immutable class containing a package name and a {@link UserHandle}.
     */
    private static final class UserPackage {
        public final @NonNull String pkg;
        public final @NonNull UserHandle user;

        private UserPackage(@NonNull String pkg, @NonNull UserHandle user) {
            this.pkg = pkg;
            this.user = user;
        }

        /**
         * Get {@link PackageInfo} for this user package.
         *
         * @param context A context used to resolve the info
         *
         * @return The package info
         *
         * @throws PackageManager.NameNotFoundException if package/user does not exist
         */
        public @NonNull PackageInfo getPackageInfo(@NonNull Context context)
                throws PackageManager.NameNotFoundException {
            return context.createPackageContextAsUser(pkg, 0, user)
                    .getPackageManager().getPackageInfo(pkg, GET_PERMISSIONS);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof UserPackage)) {
                return false;
            }

            UserPackage userPackage = (UserPackage) o;
            return pkg.equals(userPackage.pkg) && user.equals(userPackage.user);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pkg, user);
        }
    }
}