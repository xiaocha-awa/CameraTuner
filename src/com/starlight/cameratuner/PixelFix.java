package com.starlight.cameratuner;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 2 亿像素按快门闪退修复（移植自 XiaomiCamPort 0.11.5 PixelFix）
 *
 * 根因：PixelModule.onCaptureStart() 里要拿 mPixelManager 做像素位移动画，
 *       但 2 亿像素模式下这个对象是 null，一按快门就 NPE 闪退。
 *       表现为「2 亿模式能进，按快门立刻崩」。
 *
 * 修法：在 onCaptureStart 这一次调用栈内，把 l9.h.l3() 的结果压成 false，
 *       让代码认为「不需要做像素动画」，从而跳过 mPixelManager 分支，
 *       走正常拍照的优雅降级路径（复刻 prepareNormalCapture 的做法）。
 *
 * 关键：用 ThreadLocal 圈定作用域，只在 onCaptureStart 内生效，
 *       其它地方调用 l3() 完全不受影响。这比全局压 false 安全得多。
 */
public final class PixelFix {

    private static final String TAG = "pixelfix";

    /** 只在 onCaptureStart 调用栈内为 TRUE */
    private static final ThreadLocal<Boolean> IN_CAPTURE = new ThreadLocal<Boolean>();

    private static MainHook host;
    private static boolean logged = false;

    private PixelFix() {
    }

    /**
     * @param h        MainHook 实例，用于走统一日志过滤
     * @param cl       相机进程的 ClassLoader
     * @param fix      true = 真正压制 l3()；false = 只挂观察点打日志
     * @return 成功挂载的 hook 点数
     */
    public static int init(MainHook h, ClassLoader cl, boolean fix) {
        host = h;
        int n = install(cl, fix);
        log("2亿像素: 已挂载 " + n + " 个 / 未找到 " + (3 - n) + " 个（修复="
                + (fix ? "开" : "关") + "）");
        return n;
    }

    private static void log(String s) {
        XposedBridge.log("[CamTuner/PixelFix] " + s);
        if (host != null) {
            try {
                host.logiTag(TAG, s);
            } catch (Throwable ignored) {
            }
        }
    }

    public static int install(ClassLoader cl, boolean fix) {
        if (cl == null) {
            XposedBridge.log("[CamTuner/PixelFix] classLoader 为 null，放弃");
            return 0;
        }
        int ok = 0;

        // ── ① 闸门：l9.h.l3() 在拍摄时压成 false ──────────────────
        try {
            Class<?> l9h = XposedHelpers.findClass("l9.h", cl);
            XposedBridge.hookAllMethods(l9h, "l3", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!fix) return;
                    if (!Boolean.TRUE.equals(IN_CAPTURE.get())) return;
                    p.setResult(Boolean.FALSE);
                    if (!logged) {
                        logged = true;
                        log("2亿像素【已修复】onCaptureStart 内 l3() 被压成 false，"
                                + "跳过 mPixelManager 空指针（复刻 prepareNormalCapture 的优雅降级）");
                    }
                }
            });
            ok++;
            log("2亿像素: 闸门 l9.h->l3 已挂（修复=" + (fix ? "开" : "关") + "）");
        } catch (Throwable t) {
            log("2亿像素: 闸门 hook 失败 " + t);
        }

        // ── ② 圈定作用域 + 观察 mPixelManager ──────────────────────
        try {
            Class<?> pm = XposedHelpers.findClass(
                    "com.android.camera.features.mode.pixel.PixelModule", cl);
            XposedBridge.hookAllMethods(pm, "onCaptureStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    IN_CAPTURE.set(Boolean.TRUE);
                    if (fix) {
                        Object mgr = null;
                        try {
                            mgr = XposedHelpers.getObjectField(p.thisObject, "mPixelManager");
                        } catch (Throwable ignored) {
                        }
                        log("2亿像素: onCaptureStart 进入，mPixelManager=" + mgr
                                + (mgr == null ? "（空 ⇒ 已跳过像素动画块）" : ""));
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    IN_CAPTURE.remove();
                }
            });
            ok++;
            log("2亿像素: 观察点 PixelModule.onCaptureStart 已挂");
        } catch (Throwable t) {
            log("2亿像素: onCaptureStart hook 失败 " + t);
        }

        // ── ③ 超像素配置取值观察（纯日志，仅变化时打） ──────────────
        try {
            Class<?> r2e0 = XposedHelpers.findClass("r2.e0", cl);
            XposedBridge.hookAllMethods(r2e0, "getComponentValue", new XC_MethodHook() {
                private String last = null;

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    String cur = String.valueOf(p.getResult());
                    if (!cur.equals(last)) {
                        last = cur;
                        log("2亿像素: ComponentConfigUltraPixel.getComponentValue("
                                + (p.args.length > 0 ? p.args[0] : "?") + ") = " + cur);
                    }
                }
            });
            ok++;
            log("2亿像素: 超像素配置观察点已挂（仅在取值变化时打日志）");
        } catch (Throwable t) {
            log("2亿像素: 超像素配置 hook 失败 " + t);
        }

        return ok;
    }
}
