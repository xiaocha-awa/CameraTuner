package com.starlight.cameratuner;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 智能构图 V2 分支（云端 AI 姿势分析 + 检测框 + 自动变焦）。
 *
 * 背景：
 *   CaptureModule.updateSmartComposition() 有两分支：
 *     if (U3(caps))  -> V2 分支（云端 AI，这才是真正的「智能构图」）
 *     elif (V3(caps))-> V1 分支（本地模板 = 那个「构图」功能，不是目标）
 *
 *   U3 唯一门槛：HAL vendor tag 0xdead == 2。
 *   haotian（小米15 Pro）HAL 不报这个 tag，日志表现为：
 *     CameraCapabilities: SupportSmartCompositionVersion:null
 *   所以 U3 恒 false，V2 分支永远走不到。
 *
 * V2 分支原生逻辑（无其他 HAL 依赖）：
 *   u2.G g = ...;
 *   boolean on = g.isSwitchOn(mode);
 *   Log.i("CaptureModule", "updateSmartComposition v2: " + on);
 *   l9.j0.Q3 = on;                          // ① ASD 请求参数
 *   cameraManager.K0().d().ifPresent(N);     // ② 触发下发
 *   cameraManager.K0().q(0x10);             // ③ 打开 ASD 算法标志
 *
 * 本类做法：只抬 U3，其余全交给原生代码。同时挂探针观察链路。
 */
final class SmartCompV2 {

    private static final String TAG = "STV2";

    private SmartCompV2() {
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    static void init(ClassLoader sCl) {
        hookU3(sCl);
        probeSwitchOn(sCl);
        probeQ3(sCl);
        probeAsdEnable(sCl);
    }

    // ---------------------------------------------------------------
    // 核心：l9.h.U3(l9.g) -> true
    // ---------------------------------------------------------------
    private static void hookU3(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod("l9.h", sCl, "U3",
                    XposedHelpers.findClass("l9.g", sCl), new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            Boolean orig = (Boolean) p.getResult();
                            p.setResult(Boolean.TRUE);
                            log("U3 -> true（抬 V2 门，原值=" + orig + "）");
                        }
                    });
            log("hook l9.h.U3 ok");
        } catch (Throwable th) {
            log("!! hook l9.h.U3 失败 " + th);
        }
    }

    // ---------------------------------------------------------------
    // 探针：u2.G.isSwitchOn — 看 V2 分支读到的开关值
    // ---------------------------------------------------------------
    private static void probeSwitchOn(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod("u2.G", sCl, "isSwitchOn",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                boolean a = XposedHelpers.getBooleanField(
                                        p.thisObject, "a");
                                Object v = XposedHelpers.callMethod(
                                        p.thisObject, "getComponentValue", p.args[0]);
                                log("u2.G.isSwitchOn(" + p.args[0] + ") = " + p.getResult()
                                        + "   [能力a=" + a + " 配置值=" + v + "]");
                            } catch (Throwable th) {
                                log("u2.G.isSwitchOn probe 异常 " + th);
                            }
                        }
                    });
            log("probe u2.G.isSwitchOn ok");
        } catch (Throwable th) {
            log("!! probe u2.G.isSwitchOn 失败 " + th);
        }
    }

    // ---------------------------------------------------------------
    // 探针：l9.j0.Q3 写入 — ASD 请求参数
    // ---------------------------------------------------------------
    private static void probeQ3(ClassLoader sCl) {
        try {
            Class<?> i0 = XposedHelpers.findClass("l9.i0", sCl);
            XposedHelpers.findAndHookMethod(i0, "q", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    log("l9.i0.q(0x" + Integer.toHexString((Integer) p.args[0])
                            + ") 调用（打开 ASD 算法标志）");
                }
            });
            log("probe l9.i0.q ok");
        } catch (Throwable th) {
            log("!! probe l9.i0.q 失败 " + th);
        }
    }

    // ---------------------------------------------------------------
    // 探针：updateSmartComposition 走哪个分支
    // ---------------------------------------------------------------
    private static void probeAsdEnable(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.camera.features.mode.capture.CaptureModule", sCl,
                    "updateSmartComposition", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("updateSmartComposition 进入");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            log("updateSmartComposition 返回"
                                    + (p.getThrowable() == null ? "" : "（异常 " + p.getThrowable() + "）"));
                        }
                    });
            log("probe CaptureModule.updateSmartComposition ok");
        } catch (Throwable th) {
            log("!! probe CaptureModule.updateSmartComposition 失败 " + th);
        }
    }
}
