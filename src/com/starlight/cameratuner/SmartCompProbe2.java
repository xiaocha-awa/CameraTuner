package com.starlight.cameratuner;

import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 智能构图挂起原因探针。
 *
 * 背景：
 *   FragmentAi.startAiComposition() 开头会检查「其他挂起原因」：
 *     a ai = this.d1;                       // ai.a 实例
 *     LinkedHashMap map = ai.a;             // key=枚举, value=a$c
 *     a$c e = map.get(a$a.a);               // AI_COMPOSITION 那一项
 *     int n = (e == null) ? 0 : e.a;        // 计数
 *     if (n > 0) {
 *         log("startAiComposition: skipped, other suspend reasons still active");
 *         return;                            // ★ 不发起请求
 *     }
 *
 *   实测日志确实是 skipped，所以智能构图请求从未发出去，
 *   状态一直是 ON_NO_RESULT。
 *
 *   a$c 结构：
 *     int a;              // 计数
 *     Set b;             // 具体的挂起原因集合（a$b 枚举）
 *
 *   a$b 枚举（顺序按 smali 字段 a~h）：
 *     POSE_GUIDE_PANEL / EFFECT_RECOMMEND_PANEL / POSE_BORDER_OVERLAY /
 *     POSE_GUIDE_TIP / FIRST_TIME_BUBBLE / EFFECT_RECOMMEND_APPLIED_TIP /
 *     AI_TUNING_PRIORITY / RESET_ZOOM
 *
 * 本类只打印，不改变行为。
 */
final class SmartCompProbe2 {

    private static final String TAG = "STSuspend";

    private SmartCompProbe2() {
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    static void init(ClassLoader sCl) {
        hookStartAiComposition(sCl);
        hookAddReason(sCl);
        hookRemoveReason(sCl);
    }

    /** 打印 startAiComposition 时的挂起集合 */
    private static void hookStartAiComposition(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.camera.features.mode.ai.FragmentAi", sCl,
                    "vt", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object ai = XposedHelpers.getObjectField(p.thisObject, "d1");
                                if (ai == null) {
                                    log("startAiComposition: d1 为 null");
                                    return;
                                }
                                dumpReasons(ai, "startAiComposition");
                            } catch (Throwable th) {
                                log("startAiComposition probe 异常 " + th);
                            }
                        }
                    });
            log("probe FragmentAi.vt ok");
        } catch (Throwable th) {
            log("!! probe FragmentAi.vt 失败 " + th);
        }
    }

    /** 谁加了挂起原因 */
    private static void hookAddReason(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.camera.features.mode.ai.a", sCl, "a",
                    XposedHelpers.findClass("com.android.camera.features.mode.ai.a$b", sCl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("【挂起原因 +】 " + p.args[0]);
                            dumpReasons(p.thisObject, "afterAdd-pre");
                        }
                    });
            log("probe ai.a.a ok");
        } catch (Throwable th) {
            log("!! probe ai.a.a 失败 " + th);
        }
    }

    /** 谁移除了挂起原因 */
    private static void hookRemoveReason(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.camera.features.mode.ai.a", sCl, "c",
                    XposedHelpers.findClass("com.android.camera.features.mode.ai.a$b", sCl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("【挂起原因 -】 " + p.args[0]);
                        }
                    });
            log("probe ai.a.c ok");
        } catch (Throwable th) {
            log("!! probe ai.a.c 失败 " + th);
        }
    }

    /** 打印当前 AI_COMPOSITION 组的所有挂起原因 */
    private static void dumpReasons(Object ai, String from) {
        try {
            Object mapObj = XposedHelpers.getObjectField(ai, "a");
            if (!(mapObj instanceof LinkedHashMap)) {
                log("[" + from + "] a 不是 LinkedHashMap: " + mapObj);
                return;
            }
            LinkedHashMap<?, ?> map = (LinkedHashMap<?, ?>) mapObj;

            Class<?> aCls = XposedHelpers.findClass(
                    "com.android.camera.features.mode.ai.a$a", ai.getClass().getClassLoader());
            Object key = XposedHelpers.getStaticObjectField(aCls, "a");

            Object entry = map.get(key);
            if (entry == null) {
                log("[" + from + "] AI_COMPOSITION 无记录（等于无挂起）");
                return;
            }
            int count = XposedHelpers.getIntField(entry, "a");
            Object setObj = XposedHelpers.getObjectField(entry, "b");
            String reasons = "null";
            if (setObj instanceof Set) {
                StringBuilder sb = new StringBuilder();
                for (Object o : (Set<?>) setObj) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(String.valueOf(o));
                }
                reasons = sb.length() == 0 ? "(空)" : sb.toString();
            }
            log("[" + from + "] 计数=" + count + " 原因={" + reasons + "}");
        } catch (Throwable th) {
            log("[" + from + "] dump 异常 " + th);
        }
    }
}
