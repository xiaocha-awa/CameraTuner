package com.starlight.cameratuner;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 【智能构图 V3 门·极简独立 hook】
 *
 * 反汇编定案（2026-11-01）：
 *   智能构图的门控在 CaptureModule.updateSmartComposition() 里分三扇门：
 *     V2 门  l9.h.U3(caps) 读 HAL tag 0xdead 版本 ==2
 *     V1 门  l9.h.V3(caps) 读 HAL tag 0xbabe enable ==true
 *     开关门  u2.G.isSwitchOn / F.w
 *   haotian 的 HAL 不报这两个 tag → V1/V2 门永久 false → 构图不启动。
 *
 *  但真相反汇编已核实：整条 V1 构图链路（模板加载 Li5/d 读 assets json
 *  → 状态轮换 Li5/h → 全局态 Lw2/a → 画框 v1/a/SmartCompositionPipView）
 *  除 V3 这一扇门外，没有任何第二处 HAL 依赖。
 *
 *  所以：只要让 V3 恒返 true，V1 本地模板构图就能完整跑起来。
 *
 *  本类与 SmartCompFix 无关，不查档位、不查用户开关、不查 gateOpen，
 *  无条件把 V3 强制成 true，用于【验证构图框到底能不能画出来】。
 *
 *  控制键（可选，默认强制）：
 *    smartcomp.v3 = 0 时本类不干预（保持相机原行为）
 *    smartcomp.v3 = 1 或不写 时强制 V3 = true
 */
public final class SmartCompV3 {

    private static final String TAG = "CameraTuner";
    private static final String HEAD = "构图V3: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    private static final String C_H = "l9.h";
    private static final String C_G = "l9.g";

    private static ClassLoader sCl;
    private static boolean sInstalled;

    private SmartCompV3() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !C_CAMERA_PKG.equals(lpparam.packageName)) {
            return;
        }
        sCl = lpparam.classLoader;
        if (sInstalled) {
            return;
        }
        sInstalled = true;

        // 可选控制键：smartcomp.v3 = 0 时不干预
        boolean enabled = !"0".equals(readCfg("smartcomp.v3"));
        log("installed enabled=" + enabled);

        if (!enabled) {
            return;
        }

        try {
            // 无条件抬 V3：不管 HAL 上报什么，一律返回 true
            XposedHelpers.findAndHookMethod(C_H, sCl, "V3", C_G, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    try {
                        Object orig = p.getResult();
                        p.setResult(Boolean.TRUE);
                        logN("V3 被调用 orig=" + orig + " -> force TRUE");
                    } catch (Throwable th) {
                        err(th);
                    }
                }
            });
            log("hook " + C_H + ".V3(" + C_G + ") ok");
        } catch (Throwable th) {
            log("!! hook " + C_H + ".V3 失败: " + th);
            err(th);
        }
    }

    /** 从模块配置读单个键；termux 无法直接取相机进程 pref，这里只做尽力读取。 */
    private static String readCfg(String key) {
        try {
            java.io.File f = new java.io.File("/sdcard/CameraTuner/config.conf");
            if (!f.exists()) {
                return null;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            try {
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#") || line.isEmpty()) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if (k.equals(key)) {
                        return v;
                    }
                }
            } finally {
                br.close();
            }
        } catch (Throwable t) {
            // 读取失败按默认（强制）处理
        }
        return null;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + HEAD + msg);
    }

    private static void logN(String msg) {
        log(msg);
    }

    private static void err(Throwable th) {
        XposedBridge.log(TAG + ": " + HEAD + "!! " + Log.getStackTraceString(th));
    }
}
