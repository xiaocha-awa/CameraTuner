package com.starlight.cameratuner;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把智能构图的 Fragment 从 V1（那个「构图」模板小窗）换成 V2（真正的智能构图）。
 *
 * 背景（CaptureModule 的 Fragment 配置类 s.1 的 h() 方法）：
 *   if (F.d0(0xa3, caps) && u2.W.R()) {
 *       n(0x15, [0xee5]);     // V1「构图」功能小窗
 *       goto :goto_1;         // ★ 互斥，直接跳过下面
 *   }
 *   if (x.z0()) {
 *       n(0x14, [0xee7]);     // V2 智能构图（真正的那个）
 *   }
 *
 * 问题：我们之前为了打通 V1 把 F.d0 / u2.W.R 都抬成了 true，
 *       导致永远走第一个分支并 goto 跳过 V2，V2 Fragment（h5/C）从未被创建。
 *       而 h5/C 内部持有 h5/x（提示文案 Handler），
 *       所以「智能构图已开启」「当前画面暂无构图推荐」等提示一条都不会出现。
 *
 * 修复：hook y3.c.n(I[I)V，当收到 group=0x15 + [0xee5] 时，
 *       改写成 group=0x14 + [0xee7]，直接要 V2 Fragment。
 *
 * 说明：x.z0() = u2.G.a，我们已通过抬 U3 让它为 true，V2 具备创建条件。
 */
final class SmartCompFrag {

    private static final String TAG = "STFrag";

    /** V1「构图」功能小窗 */
    private static final int FRAG_V1 = 0xee5;
    /** V2 智能构图 */
    private static final int FRAG_V2 = 0xee7;
    /** V1 所在 group */
    private static final int GROUP_V1 = 0x15;
    /** V2 所在 group */
    private static final int GROUP_V2 = 0x14;

    private SmartCompFrag() {
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    static void init(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod("y3.c", sCl, "n",
                    int.class, int[].class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                int group = (Integer) p.args[0];
                                int[] ids = (int[]) p.args[1];
                                if (ids == null) return;

                                boolean hasV1 = false;
                                for (int id : ids) {
                                    if (id == FRAG_V1) {
                                        hasV1 = true;
                                        break;
                                    }
                                }
                                if (!hasV1) {
                                    log("n(group=0x" + Integer.toHexString(group)
                                            + ", ids=" + java.util.Arrays.toString(ids)
                                            + ") 非 V1，放行");
                                    return;
                                }

                                p.args[0] = GROUP_V2;
                                p.args[1] = new int[]{FRAG_V2};
                                log("拦截 V1 配置：n(0x15, [0xee5]) -> n(0x14, [0xee7])"
                                        + "，改为请求 V2 智能构图 Fragment");
                            } catch (Throwable th) {
                                log("n hook 异常 " + th);
                            }
                        }
                    });
            log("hook y3.c.n ok（V1 Frag -> V2 Frag）");
        } catch (Throwable th) {
            log("!! hook y3.c.n 失败 " + th);
        }

        probeFragmentCreate(sCl);
    }

    /** 探针：确认 h5/C（V2 Fragment）是否被创建 */
    private static void probeFragmentCreate(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod("h5.C", sCl, "initView",
                    android.view.View.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("★ V2 Fragment（h5.C）initView 被调用 —— 创建成功！");
                        }
                    });
            log("probe h5.C.initView ok");
        } catch (Throwable th) {
            log("!! probe h5.C.initView 失败（可能签名不同）" + th);
        }
    }
}
