/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.audio;

import static android.Manifest.permission.ACCESS_ULTRASOUND;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.CALL_AUDIO_INTERCEPTION;
import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.CAPTURE_AUDIO_OUTPUT;
import static android.Manifest.permission.CAPTURE_MEDIA_OUTPUT;
import static android.Manifest.permission.CAPTURE_TUNER_AUDIO_INPUT;
import static android.Manifest.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT;
import static android.Manifest.permission.BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION;
import static android.Manifest.permission.MODIFY_AUDIO_ROUTING;
import static android.Manifest.permission.MODIFY_AUDIO_SETTINGS;
import static android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED;
import static android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS;
import static android.Manifest.permission.MODIFY_PHONE_STATE;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;

import android.annotation.Nullable;
import android.os.RemoteException;
import android.os.Trace;
import android.os.Process;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.media.permission.INativePermissionController;
import com.android.media.permission.PermissionEnum;
import com.android.media.permission.UidPackageState;
import com.android.media.permission.UidPackageState.PackageState;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * 负责将 system server 侧的权限状态同步到原生音频服务器（audioserver）。
 *
 * <h2>架构概述</h2>
 * <p>原生音频服务器（audioserver / AudioFlinger）在 SELinux 强制模式下，需要知道每个
 * UID 所属的包名及其权限，才能决定是否允许该 UID 建立 AudioTrack / AudioRecord 等音频
 * 通道。本类充当 system server 与原生音频服务器之间的权限状态同步桥梁：
 * <ol>
 *   <li><b>全量初始化</b>：{@link AudioService} 启动时，通过
 *       {@link AudioService#generatePackageMap} 对所有已安装包进行一次全量快照，并将结果
 *       传入本类构造函数，形成 {@link #mPackageMap}（app-id → 包名 → 包状态 的二级映射）。</li>
 *   <li><b>服务启动推送</b>：每当原生音频服务器（重新）启动时，{@link #onServiceStart} 被
 *       回调；它先通过 {@link #resetNativePackageState} 将 {@link #mPackageMap} 全量推送
 *       给原生侧，再通过 {@link #getUidsHoldingPerm} 推送各权限的持有者列表，完成初始状态
 *       同步。</li>
 *   <li><b>增量更新</b>：每当有包安装、替换或卸载时，{@link AudioService} 的广播接收器
 *       收到 {@link android.content.Intent#ACTION_PACKAGE_ADDED} /
 *       {@link android.content.Intent#ACTION_PACKAGE_REPLACED} /
 *       {@link android.content.Intent#ACTION_PACKAGE_REMOVED} 广播，并调用
 *       {@link #onModifyPackageState} 向原生音频服务器发送增量更新，无需全量重同步。</li>
 *   <li><b>权限变更</b>：当任何 UID 的权限发生变化时，{@link #onPermissionStateChanged}
 *       被调用，重新计算各权限的持有者列表并推送给原生侧。</li>
 * </ol>
 *
 * <h2>问题现象</h2>
 * <p>在 SELinux 强制模式（{@code enforcing}）下，应用<b>首次安装</b>后立即尝试创建
 * {@link android.media.AudioTrack} 时失败，logcat 中出现类似：
 * <pre>
 *   AudioTrack: AudioFlinger could not create track, status: -1
 * </pre>
 * 或 SELinux audit 日志：
 * <pre>
 *   avc: denied { ... } for pid=... comm="..." scontext=u:r:untrusted_app:s0:...
 *        tcontext=u:r:audioserver:s0 tclass=...
 * </pre>
 * 而<b>关闭 SELinux</b>（{@code setenforce 0}）或<b>重启设备</b>后恢复正常。
 *
 * <h2>根因分析</h2>
 * <p>问题根源在于 {@link AudioService#initializeAudioServerPermissionProvider} 中注册的
 * 广播接收器，原有实现将
 * {@link com.android.server.pm.PackageManagerInternal#getPackageStateInternal} 调用
 * 放在 {@code audioserverExecutor} 的 lambda 内部：
 * <pre>
 *   // ❌ 原有写法（存在竞态）
 *   audioserverExecutor.execute(() ->
 *       provider.onModifyPackageState(
 *           uid,
 *           makePackageState(pmi.getPackageStateInternal(pkgName)),  // 可能返回 null
 *           false));
 * </pre>
 * <b>竞态链路</b>如下：
 * <ol>
 *   <li>PackageManager 完成安装并发出 {@code ACTION_PACKAGE_ADDED} 广播。</li>
 *   <li>广播接收器在主线程收到广播，将 lambda 提交给 {@code audioserverExecutor}（异步）。</li>
 *   <li>{@code audioserverExecutor} 队列中可能存在其他任务，实际执行 lambda 时已产生延迟。</li>
 *   <li>在此期间，PackageManager 内部状态可能因其他操作发生变化，导致
 *       {@code getPackageStateInternal(pkgName)} 返回 {@code null}。</li>
 *   <li>{@code makePackageState(null)} 因对 {@code null} 解引用而抛出
 *       {@link NullPointerException}。</li>
 *   <li>该 NPE 被 {@link java.util.concurrent.Executor} 框架<b>静默吞掉</b>，既不打印
 *       堆栈，也不触发任何重试机制。</li>
 *   <li>{@link #onModifyPackageState} <b>从未被调用</b>，原生音频服务器的 {@link #mPackageMap}
 *       中始终没有该新包的记录。</li>
 *   <li>SELinux 强制模式下，音频服务器对未知 UID/包拒绝建立 AudioTrack，返回 {@code -1}。</li>
 * </ol>
 * <p><b>重启后恢复正常</b>的原因：{@link AudioService} 启动时调用
 * {@link AudioService#generatePackageMap} 对所有已安装包（包括上次安装的新包）进行全量
 * 快照，并在 {@link #onServiceStart} 中通过 {@link #resetNativePackageState} 全量推送
 * 给原生侧，因此新包的状态得以正确同步。
 * <p><b>关闭 SELinux 后恢复正常</b>的原因：SELinux 未强制执行时，音频服务器不会对未知
 * UID 做策略拒绝，AudioTrack 的建立不依赖包状态同步。
 *
 * <h2>修复方案</h2>
 * <p>将 {@code getPackageStateInternal} 及 {@code makePackageState} 的调用移至<b>广播线程</b>
 * 上同步执行，并在 executor lambda 之前增加 {@code null} 检查（已在
 * {@link AudioService#initializeAudioServerPermissionProvider} 中实施）：
 * <pre>
 *   // ✅ 修复后写法（与 AudioService.initializeAudioServerPermissionProvider 实际代码一致）
 *   final PackageState pkgState = pmi.getPackageStateInternal(pkgName);  // 广播线程，安全
 *   if (pkgState == null) {
 *       Slog.w(TAG, "onReceive: 未找到包 " + pkgName + " 的状态，跳过音频服务器包状态更新");
 *       return;
 *   }
 *   final UidPackageState.PackageState ps = makePackageState(pkgState);
 *   audioserverExecutor.execute(() ->
 *       provider.onModifyPackageState(uid, ps, false));
 * </pre>
 * {@code ACTION_PACKAGE_ADDED} 广播由 PackageManager 在完成安装提交后才发出，因此在广播
 * 回调线程上调用 {@code getPackageStateInternal} 可确保获得有效的包状态，不存在 null 风险。
 *
 * <h2>排查步骤与确认方法</h2>
 * <p>当遇到"SELinux 开启 + 首次安装 + AudioTrack 失败"时，可按以下步骤逐层确认：
 *
 * <h3>排查点 1：确认是否有 SELinux denial</h3>
 * <pre>
 *   # 抓取 SELinux audit 日志
 *   adb shell dmesg | grep "avc: denied"
 *   adb logcat -s "auditd"
 * </pre>
 * 若输出中出现 {@code tcontext=u:r:audioserver} 相关的拒绝记录，则可确认是 SELinux
 * 策略在发挥作用，而非纯软件逻辑问题。
 *
 * <h3>排查点 2：确认广播接收器是否收到 ACTION_PACKAGE_ADDED</h3>
 * <pre>
 *   adb logcat -s "AudioService" | grep "收到广播"
 *   # 或修复前的英文日志：
 *   adb logcat -s "AudioService" | grep "received"
 * </pre>
 * 若日志中没有对应新包的广播记录，说明广播未被接收（可能是 IntentFilter 未注册成功）。
 *
 * <h3>排查点 3：确认 onModifyPackageState 是否被调用</h3>
 * <p>在 {@link #onModifyPackageState} 入口处临时添加日志，或通过以下命令过滤：
 * <pre>
 *   adb logcat -s "AudioServerPermissionProvider"
 * </pre>
 * 若该方法未被调用（无日志输出），说明 executor lambda 在调用前已异常退出（即静默 NPE）。
 *
 * <h3>排查点 4：确认 NullPointerException 是否被静默吞掉</h3>
 * <p>在修复前，可临时将 {@code audioserverExecutor} 替换为会打印未捕获异常的包装器：
 * <pre>
 *   Executor wrappedExecutor = r -> audioserverExecutor.execute(() -> {
 *       try { r.run(); }
 *       catch (Throwable t) { Slog.e(TAG, "executor 异常", t); throw t; }
 *   });
 * </pre>
 * 若此时日志中出现 NPE 堆栈，即可定位到 {@code makePackageState(null)} 的调用点。
 *
 * <h3>排查点 5：确认 mPackageMap 是否包含新安装包</h3>
 * <p>在 {@link #resetNativePackageState} 或 {@link #onModifyPackageState} 中临时打印
 * {@link #mPackageMap} 内容，确认新包的 app-id 是否已存在于映射中。
 *
 * <h3>排查点 6：确认修复后行为正常</h3>
 * <p>修复后的 {@link AudioService} 在收到包状态为 null 时会打印如下警告（tag：AudioService）：
 * {@code "onReceive: 未找到包 <pkgName> 的状态，跳过音频服务器包状态更新"}。
 * 正常安装时该警告不应出现；若出现，说明 PackageManager 仍未及时提供包状态。
 * <pre>
 *   # 安装 apk
 *   adb install -r test.apk
 *   # 过滤 AudioService 日志，确认：
 *   #   1. 收到 ACTION_PACKAGE_ADDED 广播（含目标包名）
 *   #   2. 无 "跳过音频服务器包状态更新" 警告（即 pkgState 非 null）
 *   #   3. onModifyPackageState 成功执行（无异常）
 *   adb logcat -s "AudioService" "AudioServerPermissionProvider"
 *   # 启动应用并播放音频，确认 AudioTrack 建立成功（无 "could not create track" 错误）
 *   adb logcat -s "AudioTrack"
 * </pre>
 */
public class AudioServerPermissionProvider {

    static final String TAG = "AudioServerPermissionProvider";

    static final String[] MONITORED_PERMS = new String[PermissionEnum.ENUM_SIZE];

    static final byte[] HDS_PERMS = new byte[] {PermissionEnum.CAPTURE_AUDIO_HOTWORD,
            PermissionEnum.CAPTURE_AUDIO_OUTPUT, PermissionEnum.RECORD_AUDIO};

    // 部分非包 UID 具有静态分配的权限。由于这些 UID 不以已安装包的形式出现
    // （它们对应原生服务），需在此显式枚举。
    // （参见 frameworks/base/data/etc/platform.xml）
    // 注意：排除 system (1000) 和 AID_AUDIOSERVER (1041)，因为权限模型已授予它们全部音频权限。
    static final int[] NONPACKAGE_UIDS = new int[] { Process.MEDIA_UID, Process.CAMERASERVER_UID, };

    static {
        MONITORED_PERMS[PermissionEnum.RECORD_AUDIO] = RECORD_AUDIO;
        MONITORED_PERMS[PermissionEnum.MODIFY_AUDIO_ROUTING] = MODIFY_AUDIO_ROUTING;
        MONITORED_PERMS[PermissionEnum.MODIFY_AUDIO_SETTINGS] = MODIFY_AUDIO_SETTINGS;
        MONITORED_PERMS[PermissionEnum.MODIFY_PHONE_STATE] = MODIFY_PHONE_STATE;
        MONITORED_PERMS[PermissionEnum.MODIFY_DEFAULT_AUDIO_EFFECTS] = MODIFY_DEFAULT_AUDIO_EFFECTS;
        MONITORED_PERMS[PermissionEnum.WRITE_SECURE_SETTINGS] = WRITE_SECURE_SETTINGS;
        MONITORED_PERMS[PermissionEnum.CALL_AUDIO_INTERCEPTION] = CALL_AUDIO_INTERCEPTION;
        MONITORED_PERMS[PermissionEnum.ACCESS_ULTRASOUND] = ACCESS_ULTRASOUND;
        MONITORED_PERMS[PermissionEnum.CAPTURE_AUDIO_OUTPUT] = CAPTURE_AUDIO_OUTPUT;
        MONITORED_PERMS[PermissionEnum.CAPTURE_MEDIA_OUTPUT] = CAPTURE_MEDIA_OUTPUT;
        MONITORED_PERMS[PermissionEnum.CAPTURE_AUDIO_HOTWORD] = CAPTURE_AUDIO_HOTWORD;
        MONITORED_PERMS[PermissionEnum.CAPTURE_TUNER_AUDIO_INPUT] = CAPTURE_TUNER_AUDIO_INPUT;
        MONITORED_PERMS[PermissionEnum.CAPTURE_VOICE_COMMUNICATION_OUTPUT] =
                CAPTURE_VOICE_COMMUNICATION_OUTPUT;
        MONITORED_PERMS[PermissionEnum.BLUETOOTH_CONNECT] = BLUETOOTH_CONNECT;
        MONITORED_PERMS[PermissionEnum.BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION] =
                BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION;
        MONITORED_PERMS[PermissionEnum.MODIFY_AUDIO_SETTINGS_PRIVILEGED] =
                MODIFY_AUDIO_SETTINGS_PRIVILEGED;
    }

    private final Object mLock = new Object();
    private final Supplier<int[]> mUserIdSupplier;
    private final BiPredicate<Integer, String> mPermissionPredicate;

    @GuardedBy("mLock")
    private INativePermissionController mDest;

    @GuardedBy("mLock")
    private final Map<Integer, Map<String, PackageState>> mPackageMap;

    // 数组值已排序
    @GuardedBy("mLock")
    private final int[][] mPermMap = new int[PermissionEnum.ENUM_SIZE][];

    @GuardedBy("mLock")
    private boolean mIsUpdateDeferred = true;

    @GuardedBy("mLock")
    private int mHdsUid = -1;

    /**
     * @param packageMap          从 app-id 到（包名 → 包状态）映射的二级映射；包状态中也含包名
     * @param permissionPredicate 检查某 UID 是否持有指定 Android 权限（字符串）的断言函数
     * @param userIdSupplier      返回设备上所有用户 ID（非 UID）的供应者；应用可在这些用户下运行
     */
    public AudioServerPermissionProvider(
            Map<Integer, Map<String, PackageState>> packageMap,
            BiPredicate<Integer, String> permissionPredicate,
            Supplier<int[]> userIdSupplier) {
        for (int i = 0; i < PermissionEnum.ENUM_SIZE; i++) {
            Objects.requireNonNull(MONITORED_PERMS[i]);
        }
        mUserIdSupplier = userIdSupplier;
        mPermissionPredicate = permissionPredicate;
        // 初始化包状态映射
        mPackageMap = packageMap;
    }

    /**
     * 每当原生音频服务器启动（或在本类之前已启动）时调用。
     *
     * @param pc 来自原生音频服务器的权限控制器接口，本类将向其推送状态更新
     */
    public void onServiceStart(@Nullable INativePermissionController pc) {
        if (pc == null) return;
        synchronized (mLock) {
            mDest = pc;
            resetNativePackageState();
            try {
                for (byte i = 0; i < PermissionEnum.ENUM_SIZE; i++) {
                    if (mIsUpdateDeferred) {
                        mPermMap[i] = getUidsHoldingPerm(i);
                    }
                    mDest.populatePermissionState(i, mPermMap[i]);
                }
                mIsUpdateDeferred = false;
            } catch (RemoteException e) {
                // 服务重新上线后将重新初始化状态
                mDest = null;
            }
        }
    }

    /**
     * 当包被添加、修改或移除时调用。
     *
     * @param uid          被修改包的 UID（仅 app-id 部分有意义）
     * @param packageState 包的（最新）状态
     * @param isRemove     {@code true} 表示包正在被移除，{@code false} 表示正在添加
     */
    public void onModifyPackageState(int uid, PackageState packageState, boolean isRemove) {
        // 不同用户的同一 UID 对应相同的 app-id，包映射只需按 app-id 维护
        uid = UserHandle.getAppId(uid);
        synchronized (mLock) {
            // 更新本地状态
            Map<String, PackageState> packages;
            if (!isRemove) {
                packages = mPackageMap.computeIfAbsent(uid, unused -> new HashMap<>());
                if (packageState.equals(packages.put(packageState.packageName, packageState))) {
                    // 无变化，无需推送
                    return;
                }
            } else {
                packages = mPackageMap.get(uid);
                if (packages != null) {
                    if (packages.remove(packageState.packageName) == null) {
                        // 无变化，无需推送
                        return;
                    }
                    if (packages.isEmpty()) {
                        mPackageMap.remove(uid);
                    }
                } else {
                    // 无变化，无需推送
                    return;
                }
            }
            // 将状态推送至原生音频服务器
            if (mDest == null) {
                // 服务重新上线后将重新同步状态
                return;
            }
            var state = new UidPackageState();
            state.uid = uid;
            state.packageStates = List.copyOf(packages.values());
            try {
                mDest.updatePackagesForUid(state);
            } catch (RemoteException e) {
                // 服务重新上线后将重新初始化状态
                mDest = null;
            }
        }
    }

    /** 每当任何包或权限变更导致 UID 持有的权限集失效时调用 */
    public void onPermissionStateChanged() {
        synchronized (mLock) {
            if (mDest == null) {
                mIsUpdateDeferred = true;
                return;
            }
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "audioserver_permission_update");
            try {
                for (byte i = 0; i < PermissionEnum.ENUM_SIZE; i++) {
                    var newPerms = getUidsHoldingPerm(i);
                    if (!Arrays.equals(newPerms, mPermMap[i])) {
                        mPermMap[i] = newPerms;
                        mDest.populatePermissionState(i, newPerms);
                    }
                }
            } catch (RemoteException e) {
                // 服务重新上线后将重新初始化状态
                mDest = null;
                // 本次更新可能未完成，标记为延迟
                mIsUpdateDeferred = true;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        }
    }

    public void setIsolatedServiceUid(int uid, int owningUid) {
        synchronized (mLock) {
            if (mHdsUid == uid) return;
            var packages = mPackageMap.get(UserHandle.getAppId(owningUid));
            if (packages != null) { var packageState = packages.values().iterator().next();
                onModifyPackageState(uid, packageState, /* isRemove= */ false);
            } else {
                Log.wtf(TAG, "setIsolatedService 未找到 owning uid");
            }
            // 同步权限
            mHdsUid = uid;
            if (mDest == null) {
                mIsUpdateDeferred = true;
                return;
            }
            try {
                for (byte perm : HDS_PERMS) {
                    int[] newPerms = new int[mPermMap[perm].length + 1];
                    System.arraycopy(mPermMap[perm], 0, newPerms, 0, mPermMap[perm].length);
                    newPerms[newPerms.length - 1] = mHdsUid;
                    Arrays.sort(newPerms);
                    mPermMap[perm] = newPerms;
                    mDest.populatePermissionState(perm, newPerms);
                }
            } catch (RemoteException e) {
                // 服务重新上线后将重新初始化状态
                mDest = null;
                // 本次更新可能未完成，标记为延迟
                mIsUpdateDeferred = true;
            }
        }
    }

    public void clearIsolatedServiceUid(int uid) {
        synchronized (mLock) {
            var packages = mPackageMap.get(UserHandle.getAppId(uid));
            if (mHdsUid != uid) {
                Log.wtf(TAG,
                        "clearIsolatedService 清除了意外的隔离服务 uid: " + uid + packages
                                + "，期望 " + mHdsUid);
                return;
            }
            if (packages != null) {
                var packageState = packages.values().iterator().next();
                onModifyPackageState(uid, packageState, /* isRemove= */ true);
            } else {
                Log.wtf(TAG, "clearIsolatedService 未找到 uid");
            }
            // 同步权限
            if (mDest == null) {
                mIsUpdateDeferred = true;
                return;
            }
            try {
                for (byte perm : HDS_PERMS) {
                    int[] newPerms = new int[mPermMap[perm].length - 1];
                    int ind = Arrays.binarySearch(mPermMap[perm], uid);
                    if (ind < 0) continue;
                    System.arraycopy(mPermMap[perm], 0, newPerms, 0, ind);
                    System.arraycopy(mPermMap[perm], ind + 1, newPerms, ind,
                            mPermMap[perm].length - ind - 1);
                    mPermMap[perm] = newPerms;
                    mDest.populatePermissionState(perm, newPerms);
                }
            } catch (RemoteException e) {
                // 服务重新上线后将重新初始化状态
                mDest = null;
                // 本次更新可能未完成，标记为延迟
                mIsUpdateDeferred = true;
            }
            mHdsUid = -1;
        }
    }

    private boolean isSpecialHdsPermission(int perm) {
        for (var hdsPerm : HDS_PERMS) {
            if (perm == hdsPerm) return true;
        }
        return false;
    }

    /** 将包状态全量同步至原生音频服务器时调用。 */
    @GuardedBy("mLock")
    private void resetNativePackageState() {
        if (mDest == null) return;
        List<UidPackageState> states =
                mPackageMap.entrySet().stream()
                        .map(
                                entry -> {
                                    UidPackageState state = new UidPackageState();
                                    state.uid = entry.getKey();
                                    state.packageStates = List.copyOf(entry.getValue().values());
                                    return state;
                                })
                        .toList();
        try {
            mDest.populatePackagesForUids(states);
        } catch (RemoteException e) {
            // 服务重新上线后将重新初始化状态
            mDest = null;
        }
    }

    @GuardedBy("mLock")
    /** 返回当前持有指定权限的所有 UID（非 app-id）。不感知 App-Op。 */
    private int[] getUidsHoldingPerm(int perm) {
        IntArray acc = new IntArray();
        final IntArray appIds = new IntArray(mPackageMap.size() + NONPACKAGE_UIDS.length);
        for (int appId : NONPACKAGE_UIDS) {
            if (!mPackageMap.containsKey(appId)) {
                appIds.add(appId);
            }
        }
        for (int appId : mPackageMap.keySet()) {
            appIds.add(appId);
        }

        for (int userId : mUserIdSupplier.get()) {
            for (int i = 0; i < appIds.size(); i++) {
                int appId = appIds.get(i);
                int uid = UserHandle.getUid(userId, appId);
                if (mPermissionPredicate.test(uid, MONITORED_PERMS[perm])) {
                    acc.add(uid);
                }
            }
        }
        if (isSpecialHdsPermission(perm) && mHdsUid != -1) {
            acc.add(mHdsUid);
        }
        var unwrapped = acc.toArray();
        Arrays.sort(unwrapped);
        return unwrapped;
    }
}
