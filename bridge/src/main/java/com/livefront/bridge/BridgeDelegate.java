package com.livefront.bridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcel;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Base64;

import com.livefront.bridge.wrapper.WrapperUtils;
import com.tencent.mmkv.MMKV;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

class BridgeDelegate {

    private static final String TAG = BridgeDelegate.class.getName();

    private static final String KEY_BUNDLE = "bundle_%s";
    private static final String KEY_UUID = "uuid_%s";

    private boolean mIsClearAllowed = false;
    private boolean mIsFirstCreateCall = true;
    private Map<String, Bundle> mUuidBundleMap = new HashMap<>();
    private Map<Object, String> mObjectUuidMap = new WeakHashMap<>();
    private SavedStateHandler mSavedStateHandler;
    private MMKV mMMKV;

    BridgeDelegate(@NonNull Context context,
                   @NonNull SavedStateHandler savedStateHandler) {
        mMMKV = MMKV.defaultMMKV();
        mSavedStateHandler = savedStateHandler;
        registerForLifecycleEvents(context);
    }

    void clear(@NonNull Object target) {
        if (!mIsClearAllowed) {
            return;
        }
        String uuid = mObjectUuidMap.remove(target);
        if (uuid == null) {
            return;
        }
        clearDataForUuid(uuid);
    }

    void clearAll() {
        mUuidBundleMap.clear();
        mObjectUuidMap.clear();
        mMMKV.clear();
    }

    private void clearDataForUuid(@NonNull String uuid) {
        mUuidBundleMap.remove(uuid);
        clearDataFromDisk(uuid);
    }

    private void clearDataFromDisk(@NonNull String uuid) {
        mMMKV.removeValueForKey(getKeyForEncodedBundle(uuid));
    }

    private String getKeyForEncodedBundle(@NonNull String uuid) {
        return String.format(KEY_BUNDLE, uuid);
    }

    private String getKeyForUuid(@NonNull Object target) {
        return String.format(KEY_UUID, target.getClass().getName());
    }

    @Nullable
    private Bundle readFromDisk(@NonNull String uuid) {
        String encodedString = mMMKV.getString(getKeyForEncodedBundle(uuid), null);
        if (encodedString == null) {
            return null;
        }
        byte[] parcelBytes = Base64.decode(encodedString, 0);
        Parcel parcel = Parcel.obtain();
        parcel.unmarshall(parcelBytes, 0, parcelBytes.length);
        parcel.setDataPosition(0);
        Bundle bundle = parcel.readBundle(BridgeDelegate.class.getClassLoader());
        parcel.recycle();
        return bundle;
    }

    @SuppressLint("NewApi")
    private void registerForLifecycleEvents(@NonNull Context context) {
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(
                new ActivityLifecycleCallbacksAdapter() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                        mIsClearAllowed = true;

                        // Make sure we clear all data after creating the first Activity if it does
                        // does not have a saved stated Bundle. (During state restoration, the
                        // first Activity will always have a non-null saved state Bundle.)
                        if (!mIsFirstCreateCall) {
                            return;
                        }
                        mIsFirstCreateCall = false;
                        if (savedInstanceState == null) {
                            mMMKV.clear();
                        }
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                        // Don't allow clearing during known configuration changes (and other
                        // events unrelated to calling "finish()".)
                        mIsClearAllowed = activity.isFinishing();
                    }
                }
        );
    }

    void restoreInstanceState(@NonNull Object target, @Nullable Bundle state) {
        if (state == null) {
            return;
        }
        String uuid = mObjectUuidMap.containsKey(target)
                ? mObjectUuidMap.get(target)
                : state.getString(getKeyForUuid(target), null);
        if (uuid == null) {
            return;
        }
        mObjectUuidMap.put(target, uuid);
        Bundle bundle = mUuidBundleMap.containsKey(uuid)
                ? mUuidBundleMap.get(uuid)
                : readFromDisk(uuid);
        if (bundle == null) {
            return;
        }
        WrapperUtils.unwrapOptimizedObjects(bundle);
        mSavedStateHandler.restoreInstanceState(target, bundle);
        clearDataForUuid(uuid);
    }

    void saveInstanceState(@NonNull Object target, @NonNull Bundle state) {
        String uuid = mObjectUuidMap.get(target);
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
            mObjectUuidMap.put(target, uuid);
        }
        state.putString(getKeyForUuid(target), uuid);
        Bundle bundle = new Bundle();
        mSavedStateHandler.saveInstanceState(target, bundle);
        if (bundle.isEmpty()) {
            // Don't bother saving empty bundles
            return;
        }
        WrapperUtils.wrapOptimizedObjects(bundle);
        mUuidBundleMap.put(uuid, bundle);
        writeToDisk(uuid, bundle);
    }

    private void writeToDisk(@NonNull String uuid,
                             @NonNull Bundle bundle) {
        Parcel parcel = Parcel.obtain();
        parcel.writeBundle(bundle);
        String encodedString = Base64.encodeToString(parcel.marshall(), 0);
        mMMKV.putString(getKeyForEncodedBundle(uuid), encodedString);
        parcel.recycle();
    }

}