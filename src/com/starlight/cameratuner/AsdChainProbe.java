package com.starlight.cameratuner;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ASD 拦截器链探针。
 *
 * 背景：
 *   智能构图的数据（RectF + 缩放比）只能来自本地 ASD 算法输出：
 *     HAL ASD → capture result → ASDInterceptorChain → s6/t0 解析 → h5/K → 绘制
 *
 *   但日志一直显示：
 *     ASDInterceptorChain: onCaptureResult: emitter is null, returning.
 *
 *   说明链对象 b.c（ObservableEmitter）从未被赋值，捕获结果根本进不了链，
 *   所以构图数据永远产生不了。
 *
 * 链的建立（com.android.camera.module.t 里）：
 *     l9.a camera = mCameraManager.T();
 *     if (camera == null) return;                  ← 条件1
 *     camera.K0(this);
 *     b chain = new b();
 *     mAsdInterceptorChain = chain;
 *     appendModuleExternalASD(chain);              ← 子类注册拦截器
 *     for (d it : chain.a) {
 *         if (it.create(callback, caps, chain)) chain.b.add(it);
 *     }
 *     chain.a.clear();
 *     ... RxJava subscribe → Lc/c.subscribe(emitter) → chain.c = emitter
 *
 * 本类只观察，不改变行为。
 */
final class AsdChainProbe {

    private static final String TAG = "STAsd";
    private static final String CLS_CHAIN = "com.android.camera.module.interceptor.base.b";
    private static final String CLS_BASE = "com.android.camera.module.t";
    private static final String CLS_CREATOR = "com.android.camera.module.interceptor.base.d";

    private AsdChainProbe() {
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    static void init(ClassLoader sCl) {
        probeAppendExternalAsd(sCl);
        probeInterceptorCreate(sCl);
        probeEmitterSubscribe(sCl);
        probeNewChain(sCl);
        probeChainSize(sCl);
        probeU2Gj(sCl);
        probeT0(sCl);
        probeS6T0(sCl);
    }

    // ---------------------------------------------------------------
    // ① appendModuleExternalASD(chain) —— 子类把拦截器注册进来
    // ---------------------------------------------------------------
    private static void probeAppendExternalAsd(ClassLoader sCl) {
        try {
            Class<?> chainCls = XposedHelpers.findClass(CLS_CHAIN, sCl);
            XposedHelpers.findAndHookMethod(CLS_BASE, sCl, "appendModuleExternalASD",
                    chainCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("appendModuleExternalASD 进入（开始注册拦截器）");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object chain = p.args[0];
                                Object list = XposedHelpers.getObjectField(chain, "a");
                                int n = (list instanceof ArrayList) ? ((ArrayList<?>) list).size() : -1;
                                log("appendModuleExternalASD 返回，待创建的拦截器数=" + n);
                            } catch (Throwable th) {
                                log("appendModuleExternalASD 返回值读取失败 " + th);
                            }
                        }
                    });
            log("probe appendModuleExternalASD ok");
        } catch (Throwable th) {
            log("!! probe appendModuleExternalASD 失败 " + th);
        }
    }

    // ---------------------------------------------------------------
    // ② 每个拦截器的 create() —— 返回 false 的就进不去
    // ---------------------------------------------------------------
    private static void probeInterceptorCreate(ClassLoader sCl) {
        try {
            Class<?> createCls = XposedHelpers.findClass(CLS_CREATOR, sCl);
            // create 是子类各自实现的，这里挂在接口/基类上
            XposedBridge.hookAllMethods(createCls, "create", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    log("拦截器 create: " + p.thisObject.getClass().getSimpleName()
                            + " -> " + p.getResult());
                }
            });
            log("probe interceptor.create ok");
        } catch (Throwable th) {
            log("!! probe interceptor.create 失败（可能没有统一基类）" + th);
        }
    }

    // ---------------------------------------------------------------
    // ③ Lc/c.subscribe(emitter) —— chain.c = emitter 的唯一赋值点
    // ---------------------------------------------------------------
    private static void probeEmitterSubscribe(ClassLoader sCl) {
        try {
            // Lc/c 是混淆名，实际类名可能带后缀；用模糊查找
            Class<?> cls = null;
            try {
                cls = XposedHelpers.findClass("Lc.c", sCl);
            } catch (Throwable t1) {
                try {
                    cls = XposedHelpers.findClass("Lc.c.1", sCl);
                } catch (Throwable t2) {
                    log("Lc/c 两种写法都找不到: " + t2);
                }
            }
            if (cls == null) {
                log("!! 未找到 Lc/c 类（emit 订阅点）");
                return;
            }
            XposedBridge.hookAllMethods(cls, "subscribe", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    log("★ subscribe 被调用 —— emitter 即将写入 chain.c " + p.args[0]);
                }
            });
            log("probe subscribe ok");
        } catch (Throwable th) {
            log("!! probe subscribe 失败 " + th);
        }
    }

    // ---------------------------------------------------------------
    // ④ 链对象创建 —— 确认 initAsdInterceptorChain 是否执行
    // ---------------------------------------------------------------
    private static void probeNewChain(ClassLoader sCl) {
        try {
            Class<?> chainCls = XposedHelpers.findClass(CLS_CHAIN, sCl);
            XposedBridge.hookAllConstructors(chainCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    log("★ new ASDIinterceptorChain 创建了：" + p.thisObject);
                }
            });
            log("probe chain 构造 ok");
        } catch (Throwable th) {
            log("!! probe chain 构造失败 " + th);
        }
    }

    // ---------------------------------------------------------------
    // ⑤ 打印链里最终留下了几个拦截器
    // ---------------------------------------------------------------
    private static void probeChainSize(ClassLoader sCl) {
        try {
            Class<?> chainCls = XposedHelpers.findClass(CLS_CHAIN, sCl);
            // 挂 onSubmit / onNext 之类不好找，改在 appendModuleExternalASD 之后延时打印
            log("probe chainSize 占位（见 appendModuleExternalASD 返回值）");
        } catch (Throwable th) {
            log("!! probe chainSize 失败 " + th);
        }
    }

    // ---------------------------------------------------------------
    // ⑥ u2.G.j() —— 智能构图 ASD 拦截器的启用判断（三道门）
    // ---------------------------------------------------------------
    private static void probeU2Gj(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod("u2.G", sCl, "j", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        boolean a = XposedHelpers.getBooleanField(p.thisObject, "a");
                        Object mode = XposedHelpers.getObjectField(
                                p.thisObject, "mCurrentMode");
                        log("u2.G.j() 进入  [能力a=" + a + " mode=" + mode + "]");
                    } catch (Throwable th) {
                        log("u2.G.j() 入参读取失败 " + th);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    log("u2.G.j() 返回 " + p.getResult()
                            + (Boolean.TRUE.equals(p.getResult()) ? "  → 拦截器启用" : "  → 拦截器不启用"));
                }
            });
            log("probe u2.G.j ok");
        } catch (Throwable th) {
            log("!! probe u2.G.j 失败 " + th);
        }
    }

    // ---------------------------------------------------------------
    // ⑦ l9.h.t0(caps) —— 支持的比例列表
    // ---------------------------------------------------------------
    private static void probeT0(ClassLoader sCl) {
        try {
            XposedHelpers.findAndHookMethod("l9.h", sCl, "t0",
                    XposedHelpers.findClass("l9.g", sCl), new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            log("l9.h.t0(caps) 返回 " + p.getResult());
                        }
                    });
            log("probe l9.h.t0 ok");
        } catch (Throwable th) {
            log("!! probe l9.h.t0 失败 " + th);
        }

        // 当前画幅比例
        try {
            XposedHelpers.findAndHookMethod("r2.T", sCl, "getComponentValue",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            log("r2.T.getComponentValue(" + p.args[0] + ") = " + p.getResult());
                        }
                    });
            log("probe r2.T.getComponentValue ok");
        } catch (Throwable th) {
            log("!! probe r2.T.getComponentValue 失败 " + th);
        }
    }

    // ---------------------------------------------------------------
    // ⑧ s6.t0 (SmartCompositionV2MultipleASD) —— 构图数据解析器
    //    确认 CaptureResult 里到底有没有 ASD 构图 tag
    // ---------------------------------------------------------------
    private static void probeS6T0(ClassLoader sCl) {
        final String C = "s6.t0";
        // acceptResult —— 拦截器被触发
        try {
            XposedHelpers.findAndHookMethod(C, sCl, "acceptResult", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    log("★★★ s6.t0.acceptResult 被调用 —— 拦截器真的在跑！");
                }
            });
            log("probe s6.t0.acceptResult ok");
        } catch (Throwable th) {
            log("!! probe s6.t0.acceptResult 失败 " + th);
        }

        // parseComplexValueManually —— 解析 CaptureResult
        try {
            XposedHelpers.findAndHookMethod(C, sCl, "parseComplexValueManually",
                    android.hardware.camera2.CaptureResult.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("s6.t0.parseComplexValueManually 进入");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            log("s6.t0.parseComplexValueManually 返回"
                                    + (p.getThrowable() == null ? "（正常）" : "（异常 " + p.getThrowable() + "）"));
                        }
                    });
            log("probe s6.t0.parseComplexValueManually ok");
        } catch (Throwable th) {
            log("!! probe s6.t0.parseComplexValueManually 失败 " + th);
        }

        // consumeResultOnMainThreadIfDataChanged —— 消费并写 h5/L.d
        try {
            XposedHelpers.findAndHookMethod(C, sCl,
                    "consumeResultOnMainThreadIfDataChanged", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("s6.t0.consumeResultOnMainThreadIfDataChanged 进入");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object data = XposedHelpers.getObjectField(
                                        p.thisObject, "a");
                                log("consume 后 h5.L=" + data);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("probe s6.t0.consume ok");
        } catch (Throwable th) {
            log("!! probe s6.t0.consume 失败 " + th);
        }

        // declareTags 里要的两个 tag，打印它们的实际值
        try {
            Class<?> b0 = XposedHelpers.findClass("ia.B0", sCl);
            Object k1 = XposedHelpers.getStaticObjectField(b0, "N2");
            log("declareTags 第一个 key 对象 = " + k1);
            if (k1 != null) {
                try {
                    Object real = XposedHelpers.callMethod(k1, "a");
                    log("  实际 CaptureResult.Key = " + real);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable th) {
            log("!! 读取 ia.B0.N2 失败 " + th);
        }
    }
}
