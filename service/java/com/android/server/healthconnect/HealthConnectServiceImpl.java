/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.healthconnect;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.healthconnect.Constants.READ;
import static android.healthconnect.HealthPermissions.MANAGE_HEALTH_DATA_PERMISSION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.AttributionSource;
import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.healthconnect.AccessLog;
import android.healthconnect.Constants;
import android.healthconnect.GetDataOriginPriorityOrderResponse;
import android.healthconnect.HealthConnectException;
import android.healthconnect.HealthConnectManager;
import android.healthconnect.HealthDataCategory;
import android.healthconnect.HealthPermissions;
import android.healthconnect.aidl.AccessLogsResponseParcel;
import android.healthconnect.aidl.ActivityDatesRequestParcel;
import android.healthconnect.aidl.ActivityDatesResponseParcel;
import android.healthconnect.aidl.AggregateDataRequestParcel;
import android.healthconnect.aidl.ApplicationInfoResponseParcel;
import android.healthconnect.aidl.ChangeLogTokenRequestParcel;
import android.healthconnect.aidl.ChangeLogsRequestParcel;
import android.healthconnect.aidl.ChangeLogsResponseParcel;
import android.healthconnect.aidl.DeleteUsingFiltersRequestParcel;
import android.healthconnect.aidl.GetPriorityResponseParcel;
import android.healthconnect.aidl.HealthConnectExceptionParcel;
import android.healthconnect.aidl.IAccessLogsResponseCallback;
import android.healthconnect.aidl.IActivityDatesResponseCallback;
import android.healthconnect.aidl.IAggregateRecordsResponseCallback;
import android.healthconnect.aidl.IApplicationInfoResponseCallback;
import android.healthconnect.aidl.IChangeLogsResponseCallback;
import android.healthconnect.aidl.IEmptyResponseCallback;
import android.healthconnect.aidl.IGetChangeLogTokenCallback;
import android.healthconnect.aidl.IGetPriorityResponseCallback;
import android.healthconnect.aidl.IHealthConnectService;
import android.healthconnect.aidl.IInsertRecordsResponseCallback;
import android.healthconnect.aidl.IMigrationExceptionCallback;
import android.healthconnect.aidl.IReadRecordsResponseCallback;
import android.healthconnect.aidl.IRecordTypeInfoResponseCallback;
import android.healthconnect.aidl.InsertRecordsResponseParcel;
import android.healthconnect.aidl.ReadRecordsRequestParcel;
import android.healthconnect.aidl.RecordIdFiltersParcel;
import android.healthconnect.aidl.RecordTypeInfoResponseParcel;
import android.healthconnect.aidl.RecordsParcel;
import android.healthconnect.aidl.UpdatePriorityRequestParcel;
import android.healthconnect.datatypes.AppInfo;
import android.healthconnect.datatypes.DataOrigin;
import android.healthconnect.datatypes.Record;
import android.healthconnect.datatypes.RecordTypeIdentifier;
import android.healthconnect.internal.datatypes.RecordInternal;
import android.healthconnect.internal.datatypes.utils.AggregationTypeIdMapper;
import android.healthconnect.internal.datatypes.utils.RecordMapper;
import android.healthconnect.internal.datatypes.utils.RecordTypePermissionCategoryMapper;
import android.healthconnect.migration.MigrationDataEntity;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.server.healthconnect.permission.HealthConnectPermissionHelper;
import com.android.server.healthconnect.storage.AutoDeleteService;
import com.android.server.healthconnect.storage.TransactionManager;
import com.android.server.healthconnect.storage.datatypehelpers.AccessLogsHelper;
import com.android.server.healthconnect.storage.datatypehelpers.ActivityDateHelper;
import com.android.server.healthconnect.storage.datatypehelpers.AppInfoHelper;
import com.android.server.healthconnect.storage.datatypehelpers.ChangeLogsHelper;
import com.android.server.healthconnect.storage.datatypehelpers.ChangeLogsRequestHelper;
import com.android.server.healthconnect.storage.datatypehelpers.HealthDataCategoryPriorityHelper;
import com.android.server.healthconnect.storage.datatypehelpers.RecordHelper;
import com.android.server.healthconnect.storage.request.AggregateTransactionRequest;
import com.android.server.healthconnect.storage.request.DeleteTransactionRequest;
import com.android.server.healthconnect.storage.request.ReadTransactionRequest;
import com.android.server.healthconnect.storage.request.UpsertTransactionRequest;
import com.android.server.healthconnect.storage.utils.RecordHelperProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * IHealthConnectService's implementation
 *
 * @hide
 */
final class HealthConnectServiceImpl extends IHealthConnectService.Stub {
    private static final String TAG = "HealthConnectService";
    private static final int MIN_BACKGROUND_EXECUTOR_THREADS = 2;
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final boolean DEBUG = false;
    // In order to unblock the binder queue all the async should be scheduled on SHARED_EXECUTOR, as
    // soon as they come.
    private static final Executor SHARED_EXECUTOR =
            new ThreadPoolExecutor(
                    Math.max(
                            MIN_BACKGROUND_EXECUTOR_THREADS,
                            Runtime.getRuntime().availableProcessors()),
                    Math.max(
                            MIN_BACKGROUND_EXECUTOR_THREADS,
                            Runtime.getRuntime().availableProcessors()),
                    KEEP_ALIVE_TIME,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>());
    private final TransactionManager mTransactionManager;
    private final HealthConnectPermissionHelper mPermissionHelper;
    private final Context mContext;
    private final PermissionManager mPermissionManager;

    HealthConnectServiceImpl(
            TransactionManager transactionManager,
            HealthConnectPermissionHelper permissionHelper,
            Context context) {
        mTransactionManager = transactionManager;
        mPermissionHelper = permissionHelper;
        mContext = context;
        mPermissionManager = mContext.getSystemService(PermissionManager.class);
    }

    @Override
    public void grantHealthPermission(
            @NonNull String packageName, @NonNull String permissionName, @NonNull UserHandle user) {
        mPermissionHelper.grantHealthPermission(packageName, permissionName, user);
    }

    @Override
    public void revokeHealthPermission(
            @NonNull String packageName,
            @NonNull String permissionName,
            @Nullable String reason,
            @NonNull UserHandle user) {
        mPermissionHelper.revokeHealthPermission(packageName, permissionName, reason, user);
    }

    @Override
    public void revokeAllHealthPermissions(
            @NonNull String packageName, @Nullable String reason, @NonNull UserHandle user) {
        mPermissionHelper.revokeAllHealthPermissions(packageName, reason, user);
    }

    @Override
    public List<String> getGrantedHealthPermissions(
            @NonNull String packageName, @NonNull UserHandle user) {
        return mPermissionHelper.getGrantedHealthPermissions(packageName, user);
    }

    @Override
    public long getHistoricalAccessStartDateInMilliseconds(
            @NonNull String packageName, @NonNull UserHandle userHandle) {
        Instant date = mPermissionHelper.getHealthDataStartDateAccess(packageName, userHandle);
        if (date == null) {
            return Constants.DEFAULT_LONG;
        } else {
            return date.toEpochMilli();
        }
    }

    /**
     * Inserts {@code recordsParcel} into the HealthConnect database.
     *
     * @param recordsParcel parcel for list of records to be inserted.
     * @param callback Callback to receive result of performing this operation. The keys returned in
     *     {@link InsertRecordsResponseParcel} are the unique IDs of the input records. The values
     *     are in same order as {@code record}. In case of an error or a permission failure the
     *     HealthConnect service, {@link IInsertRecordsResponseCallback#onError} will be invoked
     *     with a {@link HealthConnectExceptionParcel}.
     */
    @Override
    public void insertRecords(
            @NonNull String packageName,
            @NonNull RecordsParcel recordsParcel,
            @NonNull IInsertRecordsResponseCallback callback) {
        List<RecordInternal<?>> recordInternals = recordsParcel.getRecords();
        int uid = Binder.getCallingUid();
        enforceRecordWritePermissionForRecords(recordInternals, uid);
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        List<String> uuids =
                                mTransactionManager.insertAll(
                                        new UpsertTransactionRequest(
                                                packageName,
                                                recordInternals,
                                                mContext,
                                                /* isInsertRequest */ true));
                        finishDataDeliveryWriteRecords(recordInternals, uid);

                        // TODO(260399261): Debug and update the insertion to use a separate thread.
                        ActivityDateHelper.getInstance()
                                .insertRecordDate(recordsParcel.getRecords());

                        callback.onResult(new InsertRecordsResponseParcel(uuids));
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /**
     * Returns aggregation results based on the {@code request} into the HealthConnect database.
     *
     * @param packageName name of the package inserting the record.
     * @param request represents the request using which the aggregation is to be performed.
     * @param callback Callback to receive result of performing this operation.
     */
    public void aggregateRecords(
            String packageName,
            AggregateDataRequestParcel request,
            IAggregateRecordsResponseCallback callback) {
        List<Integer> recordTypesToTest = new ArrayList<>();
        for (int aggregateId : request.getAggregateIds()) {
            recordTypesToTest.addAll(
                    AggregationTypeIdMapper.getInstance()
                            .getAggregationTypeFor(aggregateId)
                            .getApplicableRecordTypeIds());
        }
        int uid = Binder.getCallingUid();
        try {
            mContext.enforcePermission(
                    MANAGE_HEALTH_DATA_PERMISSION, Binder.getCallingPid(), uid, null);
        } catch (SecurityException exception) {
            enforceRecordReadPermission(recordTypesToTest, uid);
        }
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        callback.onResult(
                                new AggregateTransactionRequest(packageName, request)
                                        .getAggregateDataResponseParcel());
                        finishDataDeliveryRead(recordTypesToTest, uid);
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /**
     * Read records {@code recordsParcel} from HealthConnect database.
     *
     * @param packageName packageName of calling app.
     * @param request ReadRecordsRequestParcel is parcel for the request object containing {@link
     *     RecordIdFiltersParcel}.
     * @param callback Callback to receive result of performing this operation. The records are
     *     returned in {@link RecordsParcel} . In case of an error or a permission failure the
     *     HealthConnect service, {@link IReadRecordsResponseCallback#onError} will be invoked with
     *     a {@link HealthConnectExceptionParcel}.
     */
    @Override
    public void readRecords(
            @NonNull String packageName,
            @NonNull ReadRecordsRequestParcel request,
            @NonNull IReadRecordsResponseCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        Pair<Boolean, Boolean> enforceSelfReadAndCheckUi =
                enforceRecordReadPermission(uid, pid, request.getRecordType());
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        try {
                            List<RecordInternal<?>> recordInternalList =
                                    mTransactionManager.readRecords(
                                            new ReadTransactionRequest(
                                                    packageName,
                                                    request,
                                                    enforceSelfReadAndCheckUi.first));
                            finishDataDeliveryRead(request.getRecordType(), uid);

                            // TODO(b/260399261): To be moved to a separate thread
                            // UI API calls should not be recorded in access logs.
                            // enforceSelfReadAndCheckUi.second signifies that the caller is UI.
                            if (!enforceSelfReadAndCheckUi.second) {
                                AccessLogsHelper.getInstance()
                                        .addAccessLog(
                                                packageName,
                                                Collections.singletonList(request.getRecordType()),
                                                READ);
                            }
                            callback.onResult(new RecordsParcel(recordInternalList));
                        } catch (TypeNotPresentException exception) {
                            // All the requested package names are not present, so simply return
                            // an empty list
                            if (ReadTransactionRequest.TYPE_NOT_PRESENT_PACKAGE_NAME.equals(
                                    exception.typeName())) {
                                callback.onResult(new RecordsParcel(new ArrayList<>()));
                            } else {
                                throw exception;
                            }
                        }
                    } catch (SQLiteException sqLiteException) {
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception e) {
                        tryAndThrowException(callback, e, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /**
     * @see HealthConnectManager#getChangeLogToken
     */
    @Override
    public void getChangeLogToken(
            @NonNull String packageName,
            @NonNull ChangeLogTokenRequestParcel request,
            @NonNull IGetChangeLogTokenCallback callback) {
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        callback.onResult(
                                ChangeLogsRequestHelper.getInstance()
                                        .getToken(packageName, request));
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception e) {
                        tryAndThrowException(callback, e, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /**
     * Updates {@code recordsParcel} into the HealthConnect database.
     *
     * @param recordsParcel parcel for list of records to be updated.
     * @param callback Callback to receive result of performing this operation. In case of an error
     *     or a permission failure the HealthConnect service, {@link IEmptyResponseCallback#onError}
     *     will be invoked with a {@link HealthConnectException}.
     */
    @Override
    public void updateRecords(
            @NonNull String packageName,
            @NonNull RecordsParcel recordsParcel,
            @NonNull IEmptyResponseCallback callback) {
        int uid = Binder.getCallingUid();
        List<RecordInternal<?>> recordInternals = recordsParcel.getRecords();
        enforceRecordWritePermissionForRecords(recordInternals, uid);
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        mTransactionManager.updateAll(
                                new UpsertTransactionRequest(
                                        packageName,
                                        recordInternals,
                                        mContext,
                                        /* isInsertRequest */ false));
                        enforceRecordWritePermissionForRecords(recordInternals, uid);
                        callback.onResult();
                    } catch (SecurityException securityException) {
                        tryAndThrowException(
                                callback, securityException, HealthConnectException.ERROR_SECURITY);
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SqlException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (IllegalArgumentException illegalArgumentException) {
                        Slog.e(TAG, "Exception: ", illegalArgumentException);
                        tryAndThrowException(
                                callback,
                                illegalArgumentException,
                                HealthConnectException.ERROR_INVALID_ARGUMENT);
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /**
     * Returns a list of unique dates for which the database has at least one entry
     *
     * @param activityDatesRequestParcel Parcel request containing records classes
     * @param callback Callback to receive result of performing this operation. The results are
     *     returned in {@link List<LocalDate>} . In case of an error or a permission failure the
     *     HealthConnect service, {@link IActivityDatesResponseCallback#onError} will be invoked
     *     with a {@link HealthConnectExceptionParcel}.
     */
    @Override
    public void getActivityDates(
            @NonNull ActivityDatesRequestParcel activityDatesRequestParcel,
            IActivityDatesResponseCallback callback) {

        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        List<LocalDate> localDates =
                                ActivityDateHelper.getInstance()
                                        .getActivityDates(
                                                activityDatesRequestParcel.getRecordTypes());

                        callback.onResult(new ActivityDatesResponseParcel(localDates));
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SqlException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_SECURITY);
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /**
     * @hide
     * @see HealthConnectManager#getChangeLogs
     */
    @Override
    public void getChangeLogs(
            @NonNull String packageName,
            @NonNull ChangeLogsRequestParcel token,
            IChangeLogsResponseCallback callback) {
        int uid = Binder.getCallingUid();
        ChangeLogsRequestHelper.TokenRequest changeLogsTokenRequest =
                ChangeLogsRequestHelper.getRequest(packageName, token.getToken());
        enforceRecordReadPermission(changeLogsTokenRequest.getRecordTypes(), uid);
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        final ChangeLogsHelper.ChangeLogsResponse changeLogsResponse =
                                ChangeLogsHelper.getInstance()
                                        .getChangeLogs(changeLogsTokenRequest, token.getPageSize());

                        List<RecordInternal<?>> recordInternals =
                                mTransactionManager.readRecords(
                                        new ReadTransactionRequest(
                                                ChangeLogsHelper.getRecordTypeToInsertedUuids(
                                                        changeLogsResponse.getChangeLogsMap())));
                        finishDataDeliveryRead(changeLogsTokenRequest.getRecordTypes(), uid);
                        callback.onResult(
                                new ChangeLogsResponseParcel(
                                        new RecordsParcel(recordInternals),
                                        ChangeLogsHelper.getDeletedIds(
                                                changeLogsResponse.getChangeLogsMap()),
                                        changeLogsResponse.getNextPageToken(),
                                        changeLogsResponse.hasMorePages()));
                    } catch (IllegalArgumentException illegalArgumentException) {
                        Slog.e(TAG, "IllegalArgumentException: ", illegalArgumentException);
                        tryAndThrowException(
                                callback,
                                illegalArgumentException,
                                HealthConnectException.ERROR_INVALID_ARGUMENT);
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(
                                callback, exception, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /**
     * API to delete records based on {@code request}
     *
     * <p>NOTE: Internally we only need a single API to handle deletes as SDK code transform all its
     * delete requests to {@link DeleteUsingFiltersRequestParcel}
     */
    @Override
    public void deleteUsingFilters(
            @NonNull String packageName,
            @NonNull DeleteUsingFiltersRequestParcel request,
            @NonNull IEmptyResponseCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        List<Integer> recordTypeIdsToDelete =
                (!request.getRecordTypeFilters().isEmpty())
                        ? request.getRecordTypeFilters()
                        : new ArrayList<>(
                                RecordMapper.getInstance()
                                        .getRecordIdToExternalRecordClassMap()
                                        .keySet());

        try {
            mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
        } catch (SecurityException exception) {
            enforceRecordWritePermission(recordTypeIdsToDelete, uid);
        }
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        mTransactionManager.deleteAll(
                                new DeleteTransactionRequest(packageName, request, mContext)
                                        .setHasManageHealthDataPermission(
                                                hasDataManagementPermission(uid, pid)));
                        finishDataDeliveryWrite(recordTypeIdsToDelete, uid);
                        callback.onResult();
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (IllegalArgumentException illegalArgumentException) {
                        Slog.e(TAG, "SQLiteException: ", illegalArgumentException);
                        tryAndThrowException(
                                callback,
                                illegalArgumentException,
                                HealthConnectException.ERROR_SECURITY);
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(
                                callback, exception, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /** API to get Priority for {@code dataCategory} */
    @Override
    public void getCurrentPriority(
            @NonNull String packageName,
            @HealthDataCategory.Type int dataCategory,
            @NonNull IGetPriorityResponseCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        List<DataOrigin> dataOriginInPriorityOrder =
                                HealthDataCategoryPriorityHelper.getInstance()
                                        .getPriorityOrder(dataCategory)
                                        .stream()
                                        .map(
                                                (name) ->
                                                        new DataOrigin.Builder()
                                                                .setPackageName(packageName)
                                                                .build())
                                        .collect(Collectors.toList());
                        callback.onResult(
                                new GetPriorityResponseParcel(
                                        new GetDataOriginPriorityOrderResponse(
                                                dataOriginInPriorityOrder)));
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(
                                callback, exception, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /** API to update priority for permission category(ies) */
    @Override
    public void updatePriority(
            @NonNull String packageName,
            @NonNull UpdatePriorityRequestParcel updatePriorityRequest,
            @NonNull IEmptyResponseCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        HealthDataCategoryPriorityHelper.getInstance()
                                .setPriorityOrder(
                                        updatePriorityRequest.getDataCategory(),
                                        updatePriorityRequest.getPackagePriorityOrder());
                        callback.onResult();
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(
                                callback, exception, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /**
     * Returns information, represented by {@code ApplicationInfoResponse}, for all the packages
     * that have contributed to the health connect DB.
     *
     * @param callback Callback to receive result of performing this operation. In case of an error
     *     or a permission failure the HealthConnect service, {@link IEmptyResponseCallback#onError}
     *     will be invoked with a {@link HealthConnectException}.
     */
    @Override
    public void getContributorApplicationsInfo(@NonNull IApplicationInfoResponseCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        List<AppInfo> applicationInfos =
                                AppInfoHelper.getInstance().getApplicationInfos();

                        callback.onResult(new ApplicationInfoResponseParcel(applicationInfos));
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SqlException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    @Override
    public void setRecordRetentionPeriodInDays(
            int days, @NonNull UserHandle user, IEmptyResponseCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        AutoDeleteService.setRecordRetentionPeriodInDays(
                                days, user.getIdentifier());
                        callback.onResult();
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(
                                callback, exception, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    @Override
    public int getRecordRetentionPeriodInDays(@NonNull UserHandle user) {
        try {
            mContext.enforceCallingPermission(MANAGE_HEALTH_DATA_PERMISSION, null);
            return AutoDeleteService.getRecordRetentionPeriodInDays(user.getIdentifier());
        } catch (Exception e) {
            if (e instanceof SecurityException) {
                throw e;
            }
            Slog.e(TAG, "Unable to get record retention period for " + user);
        }

        throw new RuntimeException();
    }

    /** Retrieves {@link android.healthconnect.RecordTypeInfoResponse} for each RecordType. */
    @Override
    public void queryAllRecordTypesInfo(@NonNull IRecordTypeInfoResponseCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        callback.onResult(
                                new RecordTypeInfoResponseParcel(
                                        getPopulatedRecordTypeInfoResponses()));
                    } catch (SQLiteException sqLiteException) {
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (Exception exception) {
                        tryAndThrowException(
                                callback, exception, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    /**
     * @see HealthConnectManager#queryAccessLogs
     */
    @Override
    public void queryAccessLogs(@NonNull String packageName, IAccessLogsResponseCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (!hasDataManagementPermission(uid, pid)) {
            throw new SecurityException(" Client doesn't hold " + MANAGE_HEALTH_DATA_PERMISSION);
        }
        SHARED_EXECUTOR.execute(
                () -> {
                    try {
                        final List<AccessLog> accessLogsList =
                                AccessLogsHelper.getInstance().queryAccessLogs();
                        callback.onResult(new AccessLogsResponseParcel(accessLogsList));
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(
                                callback, exception, HealthConnectException.ERROR_INTERNAL);
                    }
                });
    }

    @Override
    public void startMigration() {
        // Mark the start of the migration.
    }

    @Override
    public void finishMigration() {
        // Mark the end of the migration.

    }

    @Override
    public void writeMigrationData(
            List<MigrationDataEntity> entities, IMigrationExceptionCallback callback) {
        // Write data.
    }

    private Map<Integer, List<DataOrigin>> getPopulatedRecordTypeInfoResponses() {
        Map<Integer, Class<? extends Record>> recordIdToExternalRecordClassMap =
                RecordMapper.getInstance().getRecordIdToExternalRecordClassMap();
        Map<Integer, List<DataOrigin>> recordTypeInfoResponses =
                new ArrayMap<>(recordIdToExternalRecordClassMap.size());
        recordIdToExternalRecordClassMap
                .keySet()
                .forEach(
                        (recordType) -> {
                            RecordHelper<?> recordHelper =
                                    RecordHelperProvider.getInstance().getRecordHelper(recordType);
                            Objects.requireNonNull(recordHelper);
                            List<DataOrigin> packages =
                                    mTransactionManager.getDistinctPackageNamesForRecordTable(
                                            recordHelper);
                            recordTypeInfoResponses.put(recordType, packages);
                        });
        return recordTypeInfoResponses;
    }

    private void enforceRecordWritePermissionForRecords(
            List<RecordInternal<?>> recordInternals, int uid) {
        Set<Integer> recordTypeIdsToEnforce = new ArraySet<>();
        for (RecordInternal<?> recordInternal : recordInternals) {
            recordTypeIdsToEnforce.add(recordInternal.getRecordType());
        }

        enforceRecordWritePermissionInternal(recordTypeIdsToEnforce.stream().toList(), uid);
    }

    private void finishDataDeliveryWriteRecords(List<RecordInternal<?>> recordInternals, int uid) {
        Set<Integer> recordTypeIdsToEnforce = new ArraySet<>();
        for (RecordInternal<?> recordInternal : recordInternals) {
            recordTypeIdsToEnforce.add(recordInternal.getRecordType());
        }

        finishDataDeliveryWrite(recordTypeIdsToEnforce.stream().toList(), uid);
    }

    private boolean hasDataManagementPermission(int uid, int pid) {
        return mContext.checkPermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid)
                == PERMISSION_GRANTED;
    }

    private void enforceRecordWritePermission(List<Integer> recordTypeIds, int uid) {
        enforceRecordWritePermissionInternal(recordTypeIds, uid);
    }

    private void enforceRecordReadPermission(
            @RecordTypeIdentifier.RecordType int recordTypeId, int uid) {
        enforceRecordReadPermission(Collections.singletonList(recordTypeId), uid);
    }

    private void enforceRecordReadPermission(List<Integer> recordTypeIds, int uid) {
        for (Integer recordTypeId : recordTypeIds) {
            String permissionName =
                    HealthPermissions.getHealthReadPermission(
                            RecordTypePermissionCategoryMapper
                                    .getHealthPermissionCategoryForRecordType(recordTypeId));
            if (mPermissionManager.checkPermissionForStartDataDelivery(
                            permissionName, new AttributionSource.Builder(uid).build(), null)
                    != PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Caller doesn't have "
                                + permissionName
                                + " to read record of type "
                                + RecordMapper.getInstance()
                                        .getRecordIdToExternalRecordClassMap()
                                        .get(recordTypeId));
            }
        }
    }

    /**
     * Returns a pair of boolean values. where the first value specifies enforceSelfRead, i.e., the
     * app is allowed to read self data, and the second boolean value is true if the caller has
     * MANAGE_HEALTH_DATA_PERMISSION, which signifies that the caller is UI.
     */
    private Pair<Boolean, Boolean> enforceRecordReadPermission(int uid, int pid, int recordTypeId) {
        boolean enforceSelfRead = false;
        boolean callerIsUi = false;
        try {
            // UI must be able to read records with MANAGE_HEALTH_DATA_PERMISSION
            mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
            callerIsUi = true;
        } catch (SecurityException exception) {
            try {
                enforceRecordReadPermission(recordTypeId, uid);
            } catch (SecurityException readSecurityException) {
                try {
                    enforceRecordWritePermission(Collections.singletonList(recordTypeId), uid);
                    // Apps are always allowed to read self data if they have insert
                    // permission.
                    enforceSelfRead = true;
                } catch (SecurityException writeSecurityException) {
                    throw readSecurityException;
                }
            }
        }
        return Pair.create(enforceSelfRead, callerIsUi);
    }

    private void enforceRecordWritePermissionInternal(List<Integer> recordTypeIds, int uid) {
        for (Integer recordTypeId : recordTypeIds) {
            String permissionName =
                    HealthPermissions.getHealthWritePermission(
                            RecordTypePermissionCategoryMapper
                                    .getHealthPermissionCategoryForRecordType(recordTypeId));

            if (mPermissionManager.checkPermissionForStartDataDelivery(
                            permissionName, new AttributionSource.Builder(uid).build(), null)
                    != PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Caller doesn't have "
                                + permissionName
                                + " to write to record type "
                                + RecordMapper.getInstance()
                                        .getRecordIdToExternalRecordClassMap()
                                        .get(recordTypeId));
            }
        }
    }

    private void finishDataDeliveryRead(int recordTypeId, int uid) {
        finishDataDeliveryRead(Collections.singletonList(recordTypeId), uid);
    }

    private void finishDataDeliveryRead(List<Integer> recordTypeIds, int uid) {
        for (Integer recordTypeId : recordTypeIds) {
            String permissionName =
                    HealthPermissions.getHealthReadPermission(
                            RecordTypePermissionCategoryMapper
                                    .getHealthPermissionCategoryForRecordType(recordTypeId));
            mPermissionManager.finishDataDelivery(
                    permissionName, new AttributionSource.Builder(uid).build());
        }
    }

    private void finishDataDeliveryWrite(List<Integer> recordTypeIds, int uid) {
        for (Integer recordTypeId : recordTypeIds) {
            String permissionName =
                    HealthPermissions.getHealthWritePermission(
                            RecordTypePermissionCategoryMapper
                                    .getHealthPermissionCategoryForRecordType(recordTypeId));
            mPermissionManager.finishDataDelivery(
                    permissionName, new AttributionSource.Builder(uid).build());
        }
    }

    private static void tryAndThrowException(
            @NonNull IInsertRecordsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.getMessage())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IAggregateRecordsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.getMessage())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IReadRecordsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.getMessage())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IActivityDatesResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.getMessage())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IGetChangeLogTokenCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.getMessage())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IAccessLogsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IEmptyResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IApplicationInfoResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.getMessage())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IChangeLogsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IRecordTypeInfoResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.getMessage())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IGetPriorityResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }
}
